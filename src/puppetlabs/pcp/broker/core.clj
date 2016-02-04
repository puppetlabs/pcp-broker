(ns puppetlabs.pcp.broker.core
  (:require [clamq.protocol.consumer :as mq-cons]
            [clj-time.coerce :as time-coerce]
            [clj-time.core :as time]
            [metrics.gauges :as gauges]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.pcp.broker.activemq :as activemq]
            [puppetlabs.pcp.broker.capsule :as capsule]
            [puppetlabs.pcp.broker.connection :as connection :refer [Websocket Codec ConnectionState]]
            [puppetlabs.pcp.broker.metrics :as metrics]
            [puppetlabs.pcp.message :as message :refer [Message]]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.metrics :refer [time!]]
            [puppetlabs.pcp.broker.borrowed.mq :as mq]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.structured-logging.core :as sl]
            [puppetlabs.trapperkeeper.authorization.ring :as ring]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (puppetlabs.pcp.broker.capsule Capsule)
           (puppetlabs.pcp.broker.connection Connection)
           (clojure.lang IFn Atom)))

(def UriMap
  "Mapping of Uri to Websocket, for sending"
  {p/Uri Websocket})

(def Connections
  "Mapping of Websocket session to Connection state"
  {Websocket Connection})

(def Broker
  {:activemq-broker    Object
   :accept-consumers   s/Int
   :delivery-consumers s/Int
   :activemq-consumers Atom
   :record-client      IFn
   :find-clients       IFn
   :authorization-check IFn
   :uri-map            Atom ;; atom with schema UriMap. will be checked with :validator
   :connections        Atom ;; atom with schema Connections. will be checked with :validator
   :metrics-registry   Object
   :metrics            {s/Keyword Object}
   :transitions        {ConnectionState IFn}
   :broker-cn          s/Str})

(s/defn ^:always-validate build-and-register-metrics :- {s/Keyword Object}
  [broker :- Broker]
  (let [registry (:metrics-registry broker)]
    (gauges/gauge-fn registry ["puppetlabs.pcp.connections"]
                     (fn [] (count (keys @(:connections broker)))))
    {:on-connect       (.timer registry "puppetlabs.pcp.on-connect")
     :on-close         (.timer registry "puppetlabs.pcp.on-close")
     :on-message       (.timer registry "puppetlabs.pcp.on-message")
     :message-queueing (.timer registry "puppetlabs.pcp.message-queueing")
     :on-send          (.timer registry "puppetlabs.pcp.on-send")}))

;; names of activemq queues - as vars so they're harder to typo
(def accept-queue "accept")

(def delivery-queue "delivery")

(s/defn ^:always-validate get-broker-cn :- s/Str
  [certificate :- s/Str]
  (let [x509     (ssl-utils/pem->cert certificate)]
    (ssl-utils/get-cn-from-x509-certificate x509)))

(s/defn ^:always-validate broker-uri :- p/Uri
  [broker :- Broker]
  (str "pcp://" (:broker-cn broker) "/server"))

;; connection map lifecycle
(s/defn ^:always-validate add-connection! :- Connection
  "Add a Connection to the connections to track a websocket"
  [broker :- Broker ws :- Websocket codec :- Codec]
  (let [connection (connection/make-connection ws codec)]
    (swap! (:connections broker) assoc ws connection)
    connection))

(s/defn ^:always-validate remove-connection!
  "Remove tracking of a Connection from the broker by websocket"
  [broker :- Broker ws :- Websocket]
  (if-let [uri (get-in @(:connections broker) [ws :uri])]
    (swap! (:uri-map broker) dissoc uri))
  (swap! (:connections broker) dissoc ws))

(s/defn ^:always-validate get-connection :- (s/maybe Connection)
  [broker :- Broker ws :- Websocket]
  (get @(:connections broker) ws))

(s/defn ^:always-validate get-websocket :- (s/maybe Websocket)
  "Return the websocket a node identified by a uri is connected to, false if not connected"
  [broker :- Broker uri :- p/Uri]
  (get @(:uri-map broker) uri))

(s/defn ^:always-validate make-ring-request :- ring/Request
  [broker :- Broker capsule :- Capsule]
  (let [{:keys [sender targets message_type]} (:message capsule)
        form-params {}
        query-params {"sender" sender
                      "targets" (if (= 1 (count targets)) (first targets) targets)
                      "message_type" message_type}
        request {:uri "/pcp-broker/send"
                 :request-method :post
                 :remote-addr ""
                 :form-params form-params
                 :query-params query-params
                 :params (merge query-params form-params)}]
    ;; some things we can only know when sender is connected
    (if-let [websocket (get-websocket broker sender)]
      (let [remote-addr (.. websocket (getSession) (getRemoteAddress) (toString))
            ssl-client-cert (first (websockets-client/peer-certs websocket))]
        (merge request {:remote-addr remote-addr
                        :ssl-client-cert ssl-client-cert}))
      request)))

(s/defn ^:always-validate authorized? :- s/Bool
  "Check if the message is authorized"
  [broker :- Broker capsule :- Capsule]
  (let [ring-request (make-ring-request broker capsule)
        {:keys [authorization-check]} broker]
    (let [{:keys [authorized message]} (authorization-check ring-request)
          allowed (boolean authorized)]
      (sl/maplog :trace (assoc (capsule/summarize capsule)
                               :type :message-authorization
                               :allowed allowed
                               :message message)
                 "Authorizing {messageid} for {destination} - {allowed}: {message}")
      allowed)))

;; message lifecycle
(s/defn ^:always-validate accept-message-for-delivery :- Capsule
  "Accept a message for later delivery"
  [broker :- Broker capsule :- Capsule]
  (if (authorized? broker capsule)
    (time! (:message-queueing (:metrics broker))
           (let [capsule (capsule/add-hop capsule (broker-uri broker) "accept-to-queue")]
             (activemq/queue-message accept-queue capsule))))
  capsule)

(s/defn ^:always-validate process-expired-message :- Capsule
  "Reply with a ttl_expired message to the original message sender"
  [broker :- Broker capsule :- Capsule]
  (sl/maplog :trace (assoc (capsule/summarize capsule)
                           :type :message-expired)
             "Message {messageid} for {destination} has expired. Sending a ttl_expired.")
  (let [message (:message capsule)
        sender  (:sender message)]
    (if (= "pcp:///server" sender)
      (do
        (sl/maplog :trace {:type :message-expired-from-server}
                   "Server generated message expired.  Dropping")
        capsule)
      (let [in-reply-to (:id message)
            response_data {:id in-reply-to}
            response (-> (message/make-message :message_type "http://puppetlabs.com/ttl_expired"
                                               :targets [sender]
                                               :sender "pcp:///server"
                                               :in-reply-to in-reply-to)
                         (message/set-expiry 3 :seconds)
                         (message/set-json-data response_data))]
        (s/validate p/TTLExpiredMessage response_data)
        (accept-message-for-delivery broker (capsule/wrap response))))))

(s/defn ^:always-validate retry-delay :- s/Num
  "Compute the delay we should pause for before retrying the delivery of this Capsule"
  [capsule :- Capsule]
  (let [expires (:expires capsule)
        now (time/now)]
    ;; time/interval will raise if the times are not different
    (if (or (= expires now) (time/after? now expires))
      1
      (let [difference (time/in-seconds (time/interval now expires))]
        (min (max 1 (/ difference 2)) 15)))))

(s/defn ^:always-validate handle-delivery-failure
  "If the message is not expired schedule for a future delivery by
  adding to the delivery queue with a delay property, otherwise reply
  with a TTL expired message"
  [broker :- Broker capsule :- Capsule reason :- s/Str]
  (sl/maplog :trace (assoc (capsule/summarize capsule)
                           :type :message-delivery-failure
                           :reason reason)
             "Failed to deliver {messageid} for {destination}: {reason}")
  (let [expires (:expires capsule)
        now     (time/now)]
    (if (time/after? expires now)
      (let [retry-delay (retry-delay capsule)
            capsule     (capsule/add-hop capsule (broker-uri broker) "redelivery")]
        (sl/maplog :trace (assoc (capsule/summarize capsule)
                                 :type :message-redelivery
                                 :delay retry-delay)
                   "Scheduling message {messageid} to be delivered in {delay} seconds")
        (time! (:message-queueing (:metrics broker))
               (activemq/queue-message delivery-queue capsule (mq/delay-property retry-delay :seconds))))
      (process-expired-message broker capsule))))

(s/defn ^:always-validate maybe-send-destination-report
  "Send a destination report about the given message, if requested"
  [broker :- Broker message :- Message targets :- [p/Uri]]
  (when (:destination_report message)
    (let [report {:id (:id message)
                  :targets targets}
          reply (-> (message/make-message :targets [(:sender message)]
                                          :message_type "http://puppetlabs.com/destination_report"
                                          :in-reply-to (:id message)
                                          :sender "pcp:///server")
                    (message/set-expiry 3 :seconds)
                    (message/set-json-data report))]
      (s/validate p/DestinationReport report)
      (accept-message-for-delivery broker (capsule/wrap reply)))))

;; ActiveMQ queue consumers
(s/defn ^:always-validate deliver-message
  "Message consumer. Delivers a message to the websocket indicated by the :target field"
  [broker :- Broker capsule :- Capsule]
  (if-let [websocket (get-websocket broker (:target capsule))]
    (try
      (let [connection (get-connection broker websocket)
            encode (get-in connection [:codec :encode])]
        (sl/maplog :debug (merge (capsule/summarize capsule)
                                 (connection/summarize connection)
                                 {:type :message-delivery})
                   "Delivering {messageid} for {destination} to {commonname} at {remoteaddress}")
        (locking websocket
          (time! (:on-send (:metrics broker))
                 (let [capsule (capsule/add-hop capsule (broker-uri broker) "deliver")]
                   (websockets-client/send! websocket (encode (capsule/encode capsule)))))))
      (catch Exception e
        (sl/maplog :error e
                   {:type :message-delivery-error}
                   "Error in deliver-message")
        (handle-delivery-failure broker capsule (str e))))
    (handle-delivery-failure broker capsule "not connected")))

(s/defn ^:always-validate expand-destinations
  "Message consumer.  Takes a message from the accept queue, expands
  destinations, and enqueues to the `delivery-queue`"
  [broker :- Broker capsule :- Capsule]
  (let [message   (:message capsule)
        explicit  (filter (complement p/uri-wildcard?) (:targets message))
        wildcards (filter p/uri-wildcard? (:targets message))
        targets   (flatten [explicit ((:find-clients broker) wildcards)])
        capsules  (map #(assoc capsule :target %) targets)]
    (maybe-send-destination-report broker message targets)
    ;; TODO(richardc): can we wrap all these enqueues in a JMS transaction?
    ;; if so we should
    (time! (:message-queueing (:metrics broker))
           (doall (map #(activemq/queue-message delivery-queue %) capsules)))))

(s/defn ^:always-validate subscribe-to-queues!
  [broker :- Broker]
  (let [{:keys [activemq-consumers accept-consumers delivery-consumers]} broker]
    (reset! activemq-consumers
            (concat (activemq/subscribe-to-queue accept-queue (partial expand-destinations broker) accept-consumers)
                    (activemq/subscribe-to-queue delivery-queue (partial deliver-message broker) delivery-consumers)))))

(s/defn ^:always-validate session-association-message? :- s/Bool
  "Return true if message is a session association message"
  [message :- Message]
  (and (= (:targets message) ["pcp:///server"])
       (= (:message_type message) "http://puppetlabs.com/associate_request")))

(s/defn ^:always-validate reason-to-deny-association :- (s/maybe s/Str)
  "Returns an error message describing why the session should not be
  allowed, if it should be denied"
  [broker :- Broker connection :- Connection as :- p/Uri]
  (let [[_ type] (p/explode-uri as)]
    (cond
      (= type "server")
      "'server' type connections not accepted"

      (= :associated (:state connection))
      (let [{:keys [uri]} connection]
        (sl/maplog :debug (assoc (connection/summarize connection)
                                 :uri as
                                 :existinguri uri
                                 :type :connection-already-associated)
                   "Received session association for {uri} from {commonname} {remoteaddress}.  Session was already associated as {existinguri}")
        "session already associated"))))

(s/defn ^:always-validate process-associate-message :- Connection
  "Process a session association message on a websocket"
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [request  (:message capsule)
        id       (:id request)
        uri      (:sender request)
        ws       (:websocket connection)
        encode (get-in connection [:codec :encode])
        reason   (reason-to-deny-association broker connection uri)
        response (if reason {:id id :success false :reason reason} {:id id :success true})]
    (s/validate p/AssociateResponse response)
    (let [message (-> (message/make-message :message_type "http://puppetlabs.com/associate_response"
                                            :targets [uri]
                                            :in-reply-to id
                                            :sender "pcp:///server")
                      (message/set-expiry 3 :seconds)
                      (message/set-json-data response))]
      (websockets-client/send! ws (encode message)))
    (if reason
      (do
        (websockets-client/close! ws 4002 "association unsuccessful")
        connection)
      (let [{:keys [uri-map record-client]} broker]
        (when-let [old-ws (get-websocket broker uri)]
          (let [connections (:connections broker)]
            (sl/maplog :debug (assoc (connection/summarize connection)
                                     :uri uri
                                     :type :connection-association-failed)
                       "Node with uri {uri} already associated with connection {commonname} {remoteaddress}")
            (websockets-client/close! old-ws 4000 "superceded")
            (swap! connections dissoc old-ws)))
        (swap! uri-map assoc uri ws)
        (record-client uri)
        (assoc connection
               :uri uri
               :state :associated)))))

(s/defn ^:always-validate process-inventory-message :- Connection
  "Process a request for inventory data"
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [message (:message capsule)
        data (message/get-json-data message)]
    (s/validate p/InventoryRequest data)
    (let [uris (filter (partial get-websocket broker) ((:find-clients broker) (:query data)))
          response-data {:uris uris}
          response (-> (message/make-message :message_type "http://puppetlabs.com/inventory_response"
                                             :targets [(:sender message)]
                                             :in-reply-to (:id message)
                                             :sender "pcp:///server")
                       (message/set-expiry 3 :seconds)
                       (message/set-json-data response-data))]
      (s/validate p/InventoryResponse response-data)
      (accept-message-for-delivery broker (capsule/wrap response))))
  connection)

(s/defn ^:always-validate process-server-message :- Connection
  "Process a message directed at the middleware"
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [message-type (get-in capsule [:message :message_type])]
    (case message-type
      "http://puppetlabs.com/associate_request" (process-associate-message broker capsule connection)
      "http://puppetlabs.com/inventory_request" (process-inventory-message broker capsule connection)
      (do
        (sl/maplog :debug (assoc (connection/summarize connection)
                                 :messagetype message-type
                                 :type :broker-unhandled-message)
                   "Unhandled message type {messagetype} received from {commonname} {remoteaddr}")))))

(s/defn ^:always-validate check-sender-matches :- s/Bool
  "Validate that the cert name advertised by the sender matches the cert name in the certificate"
  [message :- Message connection :- Connection]
  (let [{:keys [common-name]} connection
        {:keys [sender]} message
        [client] (p/explode-uri sender)]
    (= client common-name)))

;; Websocket event handlers

(defn- on-connect!
  "OnConnect websocket event handler"
  [broker codec ws]
  (time! (:on-connect (:metrics broker))
         (let [connection (add-connection! broker ws codec)
               {:keys [common-name]} connection
               idle-timeout (* 1000 60 15)]
           (if (nil? common-name)
             (do
               (sl/maplog :debug (assoc (connection/summarize connection)
                                        :type :connection-no-peer-certificate)
                          "No client certificate, closing {remoteaddress}")
               (websockets-client/close! ws 4003 "No client certificate"))
             (do
               (websockets-client/idle-timeout! ws idle-timeout)
               (sl/maplog :debug (assoc (connection/summarize connection)
                                        :type :connection-open)
                          "client {commonname} connected from {remoteaddress}"))))))

(s/defn ^:always-validate connection-open :- Connection
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [message (:message capsule)]
    (if (session-association-message? message)
      (process-associate-message broker capsule connection)
      (do
        (sl/maplog :warn (merge (connection/summarize connection)
                                (capsule/summarize capsule)
                                {:type :connection-message-before-association})
                   "client {commonname} from {remoteaddress}: cannot accept messages until session has been associated.  Dropping message.")
        connection))))

(s/defn ^:always-validate connection-associated :- Connection
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [targets (get-in capsule [:message :targets])]
    (if (= ["pcp:///server"] targets)
      (process-server-message broker capsule connection)
      (do
        (accept-message-for-delivery broker capsule)
        connection))))

(s/defn ^:always-validate determine-next-state :- Connection
  "Determine the next state for a connection given a capsule and some transitions"
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [transitions (:transitions broker)
        state       (:state connection)]
    (if-let [transition (get transitions state)]
      (transition broker capsule connection)
      (do
        (sl/maplog :error {:type :broker-state-transition-unknown
                           :state state}
                   "Cannot find transition for state {state}")
        connection))))

(s/defn send-error-message
  [message :- (s/maybe Message) description :- String connection :- Connection]
  (let [body {:description description}
        error (-> (message/make-message
                     :message_type "http://puppetlabs.com/error_message"
                     :sender "pcp:///server")
                    (message/set-json-data body))
        error (if message
                (assoc error :in-reply-to (:id message))
                error)
        {:keys [codec websocket]} connection
        encode (:encode codec)]
    (s/validate p/ErrorMessage body)
    (websockets-client/send! websocket (encode error))))

(defn on-message!
  [broker ws bytes]
  (time! (:on-message (:metrics broker))
         (let [connection (get-connection broker ws)
               decode (get-in connection [:codec :decode])]
           (try+
             (let [message (decode bytes)]
               (try+
                 (if-not (check-sender-matches message connection)
                   ;; TODO(richardc): When we have the message type for
                   ;; 'authorization_denied' use this instead of
                   ;; error_message
                   (send-error-message message "Message not authorized" connection)
                   (let [uri (broker-uri broker)
                         capsule (capsule/wrap message)
                         capsule (capsule/add-hop capsule uri "accepted")]
                     (sl/maplog :trace (merge (connection/summarize connection)
                                              (capsule/summarize capsule)
                                              {:type :connection-message})
                                "Message {messageid} for {destination} from {commonname} {remoteaddress}")
                     (->> (determine-next-state broker capsule connection)
                          (swap! (:connections broker) assoc ws))))
                 (catch map? m
                   ;; This is a processing error, say an uncaught exception in any of the stuff we meant to do
                   (send-error-message message (str "Error " (:type m) " handling message: " (:message &throw-context)) connection))))
             (catch map? m
               ;; TODO(richardc): this could use a different message_type to
               ;; indicate an encoding error rather than a processing error
               (send-error-message nil "Could not decode message" connection))))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [broker ws message]
  (on-message! broker ws (message/string->bytes message)))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [broker ws bytes offset len]
  (on-message! broker ws bytes))

(defn- on-error
  "OnError websocket event handler"
  [broker ws e]
  (let [connection (get-connection broker ws)]
    (sl/maplog :error e (assoc (connection/summarize connection)
                               :type :connection-error)
               "Websocket error {commonname} {remoteaddress}")))

(defn- on-close!
  "OnClose websocket event handler"
  [broker ws status-code reason]
  (time! (:on-close (:metrics broker))
         (let [connection (get-connection broker ws)]
           (sl/maplog :debug (assoc (connection/summarize connection)
                                    :type :connection-close
                                    :statuscode status-code
                                    :reason reason)
                      "client {commonname} disconnected from {remoteaddress} {statuscode} {reason}")
           (remove-connection! broker ws))))

(s/defn ^:always-validate build-websocket-handlers :- {s/Keyword IFn}
  [broker :- Broker codec]
  {:on-connect (partial on-connect! broker codec)
   :on-error   (partial on-error broker)
   :on-close   (partial on-close! broker)
   :on-text    (partial on-text! broker)
   :on-bytes   (partial on-bytes! broker)})

;; service lifecycle
(def InitOptions
  {:activemq-spool s/Str
   :accept-consumers s/Num
   :delivery-consumers s/Num
   :add-websocket-handler IFn
   :record-client IFn
   :find-clients IFn
   :authorization-check IFn
   :get-metrics-registry IFn
   :ssl-cert s/Str})

(s/def default-codec :- Codec
  {:decode message/decode
   :encode message/encode})

(s/def v1-codec :- Codec
  "Codec for handling v1.0 messages"
  {:decode message/decode
   :encode (fn [message]
             ;; strip in-reply-to for everything but inventory_response
             (let [message_type (:message_type message)
                   message (if (= "http://puppetlabs.com/inventory_response" message_type)
                             message
                             (dissoc message :in-reply-to))]
               (message/encode message)))})

(s/defn ^:always-validate init :- Broker
  [options :- InitOptions]
  (let [{:keys [path activemq-spool accept-consumers delivery-consumers
                add-websocket-handler
                record-client find-clients authorization-check
                get-metrics-registry ssl-cert]} options]
    (let [activemq-broker    (mq/build-embedded-broker activemq-spool)
          broker             {:activemq-broker    activemq-broker
                              :accept-consumers   accept-consumers
                              :delivery-consumers delivery-consumers
                              :activemq-consumers (atom [])
                              :record-client      record-client
                              :find-clients       find-clients
                              :authorization-check authorization-check
                              :metrics            {}
                              :metrics-registry   (get-metrics-registry)
                              :connections        (atom {} :validator (partial s/validate Connections))
                              :uri-map            (atom {} :validator (partial s/validate UriMap))
                              :transitions        {:open connection-open
                                                   :associated connection-associated}
                              :broker-cn          (get-broker-cn ssl-cert)}
          metrics            (build-and-register-metrics broker)
          broker             (assoc broker :metrics metrics)]
      (add-websocket-handler (build-websocket-handlers broker v1-codec) {:route-id :v1})
      (add-websocket-handler (build-websocket-handlers broker default-codec) {:route-id :vNext})
      broker)))

(s/defn ^:always-validate start
  [broker :- Broker]
  (let [{:keys [activemq-broker]} broker]
    (mq/start-broker! activemq-broker)
    (subscribe-to-queues! broker)))

(s/defn ^:always-validate stop
  [broker :- Broker]
  (let [{:keys [activemq-broker activemq-consumers]} broker]
    (doseq [consumer @activemq-consumers]
      (mq-cons/close consumer))
    (mq/stop-broker! activemq-broker)))

(s/defn ^:always-validate status :- status-core/StatusCallbackResponse
  [broker :- Broker level :- status-core/ServiceStatusDetailLevel]
  (let [{:keys [metrics-registry]} broker
        level>= (partial status-core/compare-levels >= level)]
  {:state :running
   :status (cond-> {}

             (level>= :info)
             (assoc :metrics (metrics/get-pcp-metrics metrics-registry))

             (level>= :debug)
             (assoc :threads (metrics/get-thread-metrics)
                    :memory (metrics/get-memory-metrics)))}))
