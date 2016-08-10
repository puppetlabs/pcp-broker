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
            [slingshot.slingshot :refer [throw+ try+]]
            [puppetlabs.i18n.core :as i18n])
  (:import (puppetlabs.pcp.broker.capsule Capsule)
           (puppetlabs.pcp.broker.connection Connection)
           (clojure.lang IFn Atom)
           (java.util.concurrent ConcurrentHashMap)))

(def Broker
  {:activemq-broker    Object
   :accept-consumers   s/Int
   :delivery-consumers s/Int
   :activemq-consumers Atom
   :record-client      IFn
   :find-clients       IFn
   :authorization-check IFn
   :reject-expired-msg s/Bool
   :uri-map            ConcurrentHashMap ;; Mapping of Uri to Websocket, for sending
   :connections        ConcurrentHashMap ;; Mapping of Websocket session to Connection state
   :metrics-registry   Object
   :metrics            {s/Keyword Object}
   :broker-cn          s/Str
   :state              Atom})

(s/defn build-and-register-metrics :- {s/Keyword Object}
  [broker :- Broker]
  (let [registry (:metrics-registry broker)]
    (gauges/gauge-fn registry ["puppetlabs.pcp.connections"]
                     (fn [] (count (keys (:connections broker)))))
    {:on-connect       (.timer registry "puppetlabs.pcp.on-connect")
     :on-close         (.timer registry "puppetlabs.pcp.on-close")
     :on-message       (.timer registry "puppetlabs.pcp.on-message")
     :message-queueing (.timer registry "puppetlabs.pcp.message-queueing")
     :on-send          (.timer registry "puppetlabs.pcp.on-send")}))

;; names of activemq queues - as vars so they're harder to typo
(def accept-queue "accept")

(def delivery-queue "delivery")

(s/defn get-broker-cn :- s/Str
  [certificate :- s/Str]
  (let [x509-chain (ssl-utils/pem->certs certificate)]
    (when (empty? x509-chain)
      (throw (IllegalArgumentException.
               (i18n/trs "{0} must contain at least 1 certificate", certificate))))
    (ssl-utils/get-cn-from-x509-certificate (first x509-chain))))

(s/defn broker-uri :- p/Uri
  [broker :- Broker]
  (str "pcp://" (:broker-cn broker) "/server"))

;; connection map lifecycle
(s/defn add-connection! :- Connection
  "Add a Connection to the connections to track a websocket"
  [broker :- Broker ws :- Websocket codec :- Codec]
  (let [connection (connection/make-connection ws codec)]
    (.put (:connections broker) ws connection)
    connection))

(s/defn remove-connection!
  "Remove tracking of a Connection from the broker by websocket"
  [broker :- Broker ws :- Websocket]
  (if-let [uri (get-in (:connections broker) [ws :uri])]
    (.remove (:uri-map broker) uri))
  (.remove (:connections broker) ws))

(s/defn get-connection :- (s/maybe Connection)
  [broker :- Broker ws :- Websocket]
  (get (:connections broker) ws))

(s/defn get-websocket :- (s/maybe Websocket)
  "Return the websocket a node identified by a uri is connected to, false if not connected"
  [broker :- Broker uri :- p/Uri]
  (get (:uri-map broker) uri))

;;
;; Message queueing and processing
;;

;; message lifecycle
(s/defn accept-message-for-delivery :- Capsule
  "Add a debug hop entry to the specified Capsule and accept the contained
   Message for later delivery. Return the specified (unmodified) Capsule."
  [broker :- Broker capsule :- Capsule]
  (time! (:message-queueing (:metrics broker))
         (let [capsule (capsule/add-hop capsule (broker-uri broker) "accept-to-queue")]
           (activemq/queue-message accept-queue capsule)))
  capsule)

(s/defn make-ttl_expired-data-content :- p/TTLExpiredMessage
  [in-reply-to]
  {:id in-reply-to})

(s/defn make-ttl_expired-message :- Message
  "Return the ttl_expired message advising about the expiry of the given message"
  [message :- Message]
  (let [sender (:sender message)
        in-reply-to (:id message)
        response-data (make-ttl_expired-data-content in-reply-to)
        response (-> (message/make-message :message_type "http://puppetlabs.com/ttl_expired"
                                           :targets [sender]
                                           :sender "pcp:///server"
                                           :in-reply-to in-reply-to)
                     (message/set-json-data response-data)
                     (message/set-expiry 3 :seconds))]
    response))

(s/defn process-expired-message :- Capsule
  "If the sender of the specified Capsule is not `pcp:///sender`, enqueue a
   ttl_expired for it and return a Capsule containing the ttl_expired.
   Otherwise, if the sender is `pcp:///sender`, don't do anything but logging
   and return the specified Capsule."
  [broker :- Broker capsule :- Capsule]
  (sl/maplog
    :trace (assoc (capsule/summarize capsule)
                  :type :message-expired)
    (i18n/trs "Message '{messageid}' for '{destination}' has expired. Sending a ttl_expired."))
  (let [message (:message capsule)
        sender  (:sender message)]
    (if (= "pcp:///server" sender)
      (do
        (sl/maplog :trace {:type :message-expired-from-server}
                   (i18n/trs "Server generated message expired; dropping it"))
        capsule)
      (accept-message-for-delivery broker (capsule/wrap (make-ttl_expired-message message))))))

(s/defn retry-delay :- s/Num
  "Compute the delay we should pause for before retrying the delivery of this Capsule"
  [capsule :- Capsule]
  (let [expires (:expires capsule)
        now (time/now)]
    ;; time/interval will raise if the times are not different
    (if (or (= expires now) (time/after? now expires))
      1
      (let [difference (time/in-seconds (time/interval now expires))]
        (min (max 1 (/ difference 2)) 15)))))

(s/defn handle-delivery-failure
  "If the message is not expired, schedule it for a future delivery by inserting
  a `redelivery` hop entry into its debug chunk and adding the message to the
  delivery queue with a delay property.
  Otherwise, if the message is expired, reply with a TTL expired message."
  [broker :- Broker capsule :- Capsule reason :- s/Str]
  (sl/maplog :trace (assoc (capsule/summarize capsule)
                           :type :message-delivery-failure
                           :reason reason)
             (i18n/trs "Failed to deliver '{messageid}' for '{destination}': '{reason}'"))
  (if (capsule/expired? capsule)
    (process-expired-message broker capsule)
    (let [retry-delay (retry-delay capsule)
          capsule     (capsule/add-hop capsule (broker-uri broker) "redelivery")]
      (sl/maplog
        :trace (assoc (capsule/summarize capsule)
                      :type :message-redelivery
                      :delay retry-delay)
        (i18n/trs "Scheduling message '{messageid}' to be delivered in '{delay}' seconds"))
      (time! (:message-queueing (:metrics broker))
             (activemq/queue-message delivery-queue capsule (mq/delay-property retry-delay :seconds))))))

(s/defn make-destination-report :- p/DestinationReport
  [id targets]
  {:id id
   :targets targets})

(s/defn maybe-send-destination-report
  "Send a destination_report containing the targets of the specified Message,
   if requested"
  [broker :- Broker message :- Message targets :- [p/Uri]]
  (when (:destination_report message)
    (let [report (make-destination-report (:id message) targets)]
      (accept-message-for-delivery
        broker
        (-> (message/make-message :targets [(:sender message)]
                                  :message_type "http://puppetlabs.com/destination_report"
                                  :in-reply-to (:id message)
                                  :sender "pcp:///server")
            (message/set-json-data report)
            (message/set-expiry 3 :seconds)
            (capsule/wrap))))))

;; ActiveMQ queue consumers
(s/defn deliver-message
  "Message consumer. Delivers a message to the websocket indicated by the :target field"
  [broker :- Broker capsule :- Capsule]
  (if (and (:reject-expired-msg broker) (capsule/expired? capsule))
    (process-expired-message broker capsule)
    (if-let [websocket (get-websocket broker (:target capsule))]
      (try
        (let [connection (get-connection broker websocket)
              encode (get-in connection [:codec :encode])]
          (sl/maplog
            :debug (merge (capsule/summarize capsule)
                          (connection/summarize connection)
                          {:type :message-delivery})
            (i18n/trs "Delivering '{messageid}' for '{destination}' to '{commonname}' at '{remoteaddress}'"))
          (locking websocket
            (time! (:on-send (:metrics broker))
                   (let [capsule (capsule/add-hop capsule (broker-uri broker) "deliver")]
                     (websockets-client/send! websocket (encode (capsule/encode capsule)))))))
        (catch Exception e
          (sl/maplog :error e
                     {:type :message-delivery-error}
                     (i18n/trs "Error in deliver-message"))
          (handle-delivery-failure broker capsule (str e))))
      (handle-delivery-failure broker capsule (i18n/trs "not connected")))))

(s/defn expand-destinations
  "Message consumer. Process the specified Capsule by expanding the message
  targets (resolving wildcards), sending a destination_report (if requested),
  and enqueueing the message to the `delivery-queue`"
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

(s/defn subscribe-to-queues!
  [broker :- Broker]
  (let [{:keys [activemq-consumers accept-consumers delivery-consumers]} broker]
    (reset! activemq-consumers
            (concat (activemq/subscribe-to-queue accept-queue (partial expand-destinations broker) accept-consumers)
                    (activemq/subscribe-to-queue delivery-queue (partial deliver-message broker) delivery-consumers)))))

(s/defn session-association-request? :- s/Bool
  "Return true if message is a session association message"
  [message :- Message]
  (and (= (:targets message) ["pcp:///server"])
       (= (:message_type message) "http://puppetlabs.com/associate_request")))

;; process-associate-request! helper
(s/defn reason-to-deny-association :- (s/maybe s/Str)
  "Returns an error message describing why the session should not be
  allowed, if it should be denied"
  [broker :- Broker connection :- Connection as :- p/Uri]
  (let [[_ type] (p/explode-uri as)]
    (cond
      (= type "server")
      (i18n/trs "''server'' type connections not accepted")
      (= :associated (:state connection))
      (let [{:keys [uri]} connection]
        (sl/maplog
          :debug (assoc (connection/summarize connection)
                        :uri as
                        :existinguri uri
                        :type :connection-already-associated)
          (i18n/trs "Received session association for '{uri}' from '{commonname}' '{remoteaddress}'. Session was already associated as '{existinguri}'"))
        (i18n/trs "Session already associated")))))

(s/defn make-associate_response-data-content :- p/AssociateResponse
  [id reason-to-deny]
  (if reason-to-deny
    {:id id :success false :reason reason-to-deny}
    {:id id :success true}))

(s/defn process-associate-request! :- (s/maybe Connection)
  "Send an associate_response that will be successful if:
    - a reason-to-deny is not specified as an argument nor determined by
      reason-to-deny-association;
    - the requester `client_type` is not `server`;
    - the specified WebSocket connection has not been associated previously.
  If the request gets denied, the WebSocket connection will be closed and the
  function returns nil.
  Otherwise, the 'Connection' object's state will be marked as associated and
  returned. Also, in case another WebSocket connection with the same client
  is currently associated, such old connection will be superseded by the new
  one (i.e. the old connection will be closed by the brocker).

  Note that this function will not update the broker by removing the connection
  from the 'connections' map, nor the 'uri-map'. It is assumed that such update
  will be done asynchronously by the onClose handler."
  ;; TODO(ale): make associate_request idempotent when succeed (PCP-521)
  ([broker :- Broker capsule :- Capsule connection :- Connection]
    (let [requester-uri (get-in capsule [:message :sender])
          reason-to-deny (reason-to-deny-association broker connection requester-uri)]
      (process-associate-request! broker capsule connection reason-to-deny)))
  ([broker :- Broker capsule :- Capsule connection :- Connection reason-to-deny :- (s/maybe s/Str)]
    ;; NB(ale): don't validate the associate_request as there's no data chunk...
    (let [ws (:websocket connection)
          request (:message capsule)
          id (:id request)
          encode (get-in connection [:codec :encode])
          requester-uri (:sender request)
          response-data (make-associate_response-data-content id reason-to-deny)]
      (let [message (-> (message/make-message :message_type "http://puppetlabs.com/associate_response"
                                              :targets [requester-uri]
                                              :in-reply-to id
                                              :sender "pcp:///server")
                        (message/set-json-data response-data)
                        (message/set-expiry 3 :seconds))]
        (sl/maplog :debug {:type :associate_response-trace
                           :requester requester-uri
                           :rawmsg message}
                   (i18n/trs "Replying to '{requester}' with associate_response: '{rawmsg}'"))
        (websockets-client/send! ws (encode message)))
      (if reason-to-deny
        (do
          (sl/maplog
            :debug {:type   :connection-association-failed
                    :uri    requester-uri
                    :reason reason-to-deny}
            (i18n/trs "Invalid associate_request ('{reason}'); closing '{uri}' WebSocket"))
          (websockets-client/close! ws 4002 (i18n/trs "association unsuccessful"))
          nil)
        (let [{:keys [uri-map record-client]} broker]
          (when-let [old-ws (get-websocket broker requester-uri)]
            (let [connections (:connections broker)]
              (sl/maplog
                :debug (assoc (connection/summarize connection)
                         :uri requester-uri
                         :type :connection-association-failed)
                (i18n/trs "Node with URI '{uri}' already associated with connection '{commonname}' '{remoteaddress}'"))
              (websockets-client/close! old-ws 4000 (i18n/trs "superseded"))
              (.remove connections old-ws)))
          (.put uri-map requester-uri ws)
          (record-client requester-uri)
          (assoc connection
            :uri requester-uri
            :state :associated))))))

(s/defn make-inventory_response-data-content :- p/InventoryResponse
  [uris]
  {:uris uris})

(s/defn process-inventory-request
  "Process a request for inventory data.
   This function assumes that the requester client is associated.
   Returns nil."
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (assert (= (:state connection) :associated))
  (let [message (:message capsule)
        data (message/get-json-data message)]
    (s/validate p/InventoryRequest data)
    (let [uris (doall (filter (partial get-websocket broker) ((:find-clients broker) (:query data))))
          response-data (make-inventory_response-data-content uris)]
      (accept-message-for-delivery
        broker
        (-> (message/make-message :message_type "http://puppetlabs.com/inventory_response"
                                  :targets [(:sender message)]
                                  :in-reply-to (:id message)
                                  :sender "pcp:///server")
            (message/set-json-data response-data)
            ;; set expiration last so if any of the previous steps take
            ;; significant time the message doesn't expire
            (message/set-expiry 3 :seconds)
            (capsule/wrap)))))
  nil)

(s/defn process-server-message! :- (s/maybe Connection)
  "Process a message directed at the middleware"
  [broker :- Broker capsule :- Capsule connection :- Connection]
  (let [message-type (get-in capsule [:message :message_type])]
    (case message-type
      "http://puppetlabs.com/associate_request" (process-associate-request! broker capsule connection)
      "http://puppetlabs.com/inventory_request" (process-inventory-request broker capsule connection)
      (do
        (sl/maplog
          :debug (assoc (connection/summarize connection)
                   :messagetype message-type
                   :type :broker-unhandled-message)
          (i18n/trs "Unhandled message type '{messagetype}' received from '{commonname}' '{remoteaddr}'"))))))

;;
;; Message validation
;;

(s/defn make-ring-request :- ring/Request
  [capsule :-  Capsule connection :- (s/maybe Connection)]
  (let [{:keys [sender targets message_type destination_report]} (:message capsule)
        form-params {}
        query-params {"sender" sender
                      "targets" (if (= 1 (count targets)) (first targets) targets)
                      "message_type" message_type
                      "destination_report" (boolean destination_report)}
        request {:uri            "/pcp-broker/send"
                 :request-method :post
                 :remote-addr    ""
                 :form-params    form-params
                 :query-params   query-params
                 :params         (merge query-params form-params)}]
    ;; NB(ale): we may not have the Connection when running tests
    (if connection
      (let [remote-addr (:remote-address connection)
            ssl-client-cert (first (websockets-client/peer-certs (:websocket connection)))]
        (merge request {:remote-addr remote-addr
                        :ssl-client-cert ssl-client-cert}))
      request)))

;; NB(ale): using (s/Maybe Connection) in the signature for the sake of testing
(s/defn authorized? :- s/Bool
  "Check if the message within the specified capsule is authorized"
  [broker :- Broker capsule :- Capsule connection :- (s/maybe Connection)]
  (let [ring-request (make-ring-request capsule connection)
        {:keys [authorization-check]} broker
        {:keys [authorized message]} (authorization-check ring-request)
        allowed (boolean authorized)]
    (sl/maplog
      :trace (merge (capsule/summarize capsule)
                    {:type         :message-authorization
                     :allowed      allowed
                     :auth-message message})
      (i18n/trs "Authorizing '{messageid}' for '{destination}' - '{allowed}': '{auth-message}'"))
    allowed))

(s/defn authenticated? :- s/Bool
  "Check if the cert name advertised by the sender of the message contained in
   the specified Capsule matches the cert name in the certificate of the
   given Connection"
  [capsule :- Capsule connection :- Connection]
  (let [{:keys [common-name]} connection
        sender (get-in capsule [:message :sender])
        [client] (p/explode-uri sender)]
    (= client common-name)))

(def MessageValidationOutcome
  "Outcome of validate-message"
  (s/enum :to-be-ignored-during-association
          :not-authenticated
          :not-authorized
          :expired
          :to-be-processed))

(s/defn validate-message :- MessageValidationOutcome
  "Determine whether the specified message should be processed by checking,
   in order, if the message: 1) is an associate-request as expected during
   Session Association; 2) is authenticated; 3) is authorized; 4) expired"
  [broker :- Broker capsule :- Capsule connection :- Connection is-association-request :- s/Bool]
  (cond
    (and (= :open (:state connection))
         (not is-association-request)) :to-be-ignored-during-association
    (not (authenticated? capsule connection)) :not-authenticated
    (not (authorized? broker capsule connection)) :not-authorized
    (and (:reject-expired-msg broker) (capsule/expired? capsule)) :expired
    :else :to-be-processed))

;;
;; WebSocket onMessage handling
;;

(s/defn make-error-data-content :- p/ErrorMessage
  [in-reply-to-message description]
  (let [data-content {:description description}
        data-content (if in-reply-to-message
                       (assoc data-content :id (:id in-reply-to-message))
                       data-content)]
    data-content))

(s/defn send-error-message
  [in-reply-to-message :- (s/maybe Message) description :- String connection :- Connection]
  (let [data-content (make-error-data-content in-reply-to-message description)
        error-msg (-> (message/make-message :message_type "http://puppetlabs.com/error_message"
                                            :sender "pcp:///server")
                      (message/set-json-data data-content))
        error-msg (if in-reply-to-message
                    (assoc error-msg :in-reply-to (:id in-reply-to-message))
                    error-msg)
        {:keys [codec websocket]} connection
        encode (:encode codec)]
    (websockets-client/send! websocket (encode error-msg))
    nil))

(defn log-access
  [lvl message-data]
  (sl/maplog
    [:puppetlabs.pcp.broker.pcp_access lvl]
    message-data
    "{accessoutcome} {remoteaddress} {commonname} {source} {messagetype} {messageid} {destination}"))

;; NB(ale): using (s/Maybe Websocket) in the signature for the sake of testing
(s/defn process-message! :- (s/maybe Connection)
  "Deserialize, validate (authentication, authorization, and expiration), and
  process the specified raw message. Return the 'Connection' object associated
  to the specified 'Websocket' in case it gets modified (hence the '!' in the
  function name), otherwise nil.
  Also, log the message validation outcome via 'pcp-access' logger."
  [broker :- Broker bytes :- message/ByteArray ws :- (s/maybe Websocket)]
  (let [connection (get-connection broker ws)
        decode (get-in connection [:codec :decode])]
    (try+
      (let [message (decode bytes)
            capsule (capsule/wrap message)
            message-data (merge (connection/summarize connection)
                                (capsule/summarize capsule))
            is-association-request (session-association-request? message)]
        (sl/maplog :trace {:type :incoming-message-trace :rawmsg message}
                   (i18n/trs "Processing PCP message: '{rawmsg}'"))
        (try+
          (case (validate-message broker capsule connection is-association-request)
            :to-be-ignored-during-association
            (log-access :warn (assoc message-data :accessoutcome "IGNORED_DURING_ASSOCIATION"))
            :not-authenticated
            (let [not-authenticated-msg (i18n/trs "Message not authenticated")]
              (log-access :warn (assoc message-data :accessoutcome "AUTHENTICATION_FAILURE"))
              (if is-association-request
                ;; send an unsuccessful associate_response and close the WebSocket
                (process-associate-request! broker capsule connection not-authenticated-msg)
                (send-error-message message not-authenticated-msg connection)))
            :not-authorized
            (let [not-authorized-msg (i18n/trs "Message not authorized")]
              (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
              (if is-association-request
                ;; send an unsuccessful associate_response and close the WebSocket
                (process-associate-request! broker capsule connection not-authorized-msg)
                ;; TODO(ale): use 'unauthorized' in version 2
                (send-error-message message not-authorized-msg connection)))
            :expired
            (do
              (log-access :warn (assoc message-data :accessoutcome "EXPIRED"))
              (if is-association-request
                ;; send back the ttl-expired immediately, without queueing
                (let [response (make-ttl_expired-message message)
                      encode (get-in connection [:codec :encode])]
                  (websockets-client/send! ws (encode response)))
                (process-expired-message broker capsule))
              nil)
            :to-be-processed
            (do
              (log-access :info (assoc message-data :accessoutcome "AUTHORIZATION_SUCCESS"))
              (let [uri (broker-uri broker)
                    capsule (capsule/add-hop capsule uri "accepted")]
                (if (= (:targets message) ["pcp:///server"])
                  (process-server-message! broker capsule connection)
                  (do
                    (assert (= (:state connection) :associated))
                    (accept-message-for-delivery broker capsule)
                    nil))))
            ;; default case
            (assert false (i18n/trs "unexpected message validation outcome")))
          (catch map? m
            (sl/maplog
              :debug (merge (connection/summarize connection)
                            (capsule/summarize capsule)
                            {:type :processing-error :errortype (:type m)})
              (i18n/trs "Failed to process '{messagetype}' '{messageid}' from '{commonname}' '{remoteaddress}': '{errortype}'"))
            (send-error-message
              message
              (i18n/trs "Error {0} handling message: {1}" (:type m) (:message &throw-context))
              connection))))
      (catch map? m
        (sl/maplog
          [:puppetlabs.pcp.broker.pcp_access :warn]
          (assoc (connection/summarize connection) :type :deserialization-failure
                                                   :outcome "DESERIALIZATION_ERROR")
          "{outcome} {remoteaddress} {commonname} unknown unknown unknown unknown unknown")
        ;; TODO(richardc): this could use a different message_type to
        ;; indicate an encoding error rather than a processing error
        (send-error-message nil (i18n/trs "Could not decode message") connection)))))

(defn on-message!
  "If the broker service is not running, close the WebSocket connection.
   Otherwise process the message and, in case a 'Connection' object is
   returned, updates the related broker's 'connections' map entry."
  [broker ws bytes]
  (time!
    (:on-message (:metrics broker))
    (if-not (= :running @(:state broker))
      (websockets-client/close! ws 1011 (i18n/trs "Broker is not running"))
      (when-let [connection (process-message! broker bytes ws)]
        (assert (instance? Connection connection))
        (.put (:connections broker) ws connection)))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [broker ws message]
  (on-message! broker ws (message/string->bytes message)))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [broker ws bytes offset len]
  (on-message! broker ws bytes))

;;
;; Other WebSocket event handlers
;;

(defn- on-connect!
  "OnOpen WebSocket event handler. Close the WebSocket connection if the
   Broker service is not running or if the client common name is not obtainable
   from its cert. Otherwise set the idle timeout of the WebSocket connection
   to 15 min."
  [broker codec ws]
  (time!
    (:on-connect (:metrics broker))
    (if-not (= :running @(:state broker))
      (websockets-client/close! ws 1011 (i18n/trs "Broker is not running"))
      (let [connection (add-connection! broker ws codec)
            {:keys [common-name]} connection
            idle-timeout (* 1000 60 15)]
        (if (nil? common-name)
          (do
            (sl/maplog :debug (assoc (connection/summarize connection)
                                :type :connection-no-peer-certificate)
                       (i18n/trs "No client certificate, closing '{remoteaddress}'"))
            (websockets-client/close! ws 4003 (i18n/trs "No client certificate")))
          (do
            (websockets-client/idle-timeout! ws idle-timeout)
            (sl/maplog :debug (assoc (connection/summarize connection)
                                :type :connection-open)
                       (i18n/trs "client '{commonname}' connected from '{remoteaddress}'"))))))))

(defn- on-error
  "OnError WebSocket event handler. Just log the event."
  [broker ws e]
  (let [connection (get-connection broker ws)]
    (sl/maplog :error e (assoc (connection/summarize connection)
                               :type :connection-error)
               (i18n/trs "Websocket error '{commonname}' '{remoteaddress}'"))))

(defn- on-close!
  "OnClose WebSocket event handler. Remove the Connection instance out of the
   broker's 'connections' map."
  [broker ws status-code reason]
  (time! (:on-close (:metrics broker))
         (let [connection (get-connection broker ws)]
           (sl/maplog
             :debug (assoc (connection/summarize connection)
                           :type :connection-close
                           :statuscode status-code
                           :reason reason)
             (i18n/trs "client '{commonname}' disconnected from '{remoteaddress}' '{statuscode}' '{reason}'"))
           (remove-connection! broker ws))))

(s/defn build-websocket-handlers :- {s/Keyword IFn}
  [broker :- Broker codec]
  {:on-connect (partial on-connect! broker codec)
   :on-error   (partial on-error broker)
   :on-close   (partial on-close! broker)
   :on-text    (partial on-text! broker)
   :on-bytes   (partial on-bytes! broker)})

;;
;; Broker service lifecycle, codecs, status service
;;

(def InitOptions
  {:activemq-spool s/Str
   :accept-consumers s/Num
   :delivery-consumers s/Num
   :add-websocket-handler IFn
   :record-client IFn
   :find-clients IFn
   :authorization-check IFn
   :get-metrics-registry IFn
   :get-route IFn
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

(s/defn init :- Broker
  [options :- InitOptions]
  (let [{:keys [path activemq-spool accept-consumers delivery-consumers
                add-websocket-handler
                record-client find-clients authorization-check
                get-route
                get-metrics-registry ssl-cert]} options]
    (let [activemq-broker    (mq/build-embedded-broker activemq-spool)
          broker             {:activemq-broker    activemq-broker
                              :accept-consumers   accept-consumers
                              :delivery-consumers delivery-consumers
                              :activemq-consumers (atom [])
                              :record-client      record-client
                              :find-clients       find-clients
                              :authorization-check authorization-check
                              :reject-expired-msg false
                              :metrics            {}
                              :metrics-registry   (get-metrics-registry)
                              :connections        (ConcurrentHashMap.)
                              :uri-map            (ConcurrentHashMap.)
                              :broker-cn          (get-broker-cn ssl-cert)
                              :state              (atom :starting)}
          metrics            (build-and-register-metrics broker)
          broker             (assoc broker :metrics metrics)]
      (add-websocket-handler (build-websocket-handlers broker v1-codec) {:route-id :v1})
      (try
        (when (get-route :vNext)
          (add-websocket-handler (build-websocket-handlers broker default-codec) {:route-id :vNext}))
        (catch IllegalArgumentException e
          (sl/maplog :trace {:type :vnext-unavailable}
                     (i18n/trs "vNext protocol endpoint not configured"))))
      broker)))

(s/defn start
  [broker :- Broker]
  (let [{:keys [activemq-broker state]} broker]
    (mq/start-broker! activemq-broker)
    (subscribe-to-queues! broker)
    (reset! state :running)))

(s/defn stop
  [broker :- Broker]
  (let [{:keys [activemq-broker activemq-consumers state]} broker]
    (reset! state :stopping)
    (doseq [consumer @activemq-consumers]
      (mq-cons/close consumer))
    (mq/stop-broker! activemq-broker)))

(s/defn status :- status-core/StatusCallbackResponse
  [broker :- Broker level :- status-core/ServiceStatusDetailLevel]
  (let [{:keys [state metrics-registry]} broker
        level>= (partial status-core/compare-levels >= level)]
    {:state  @state
     :status (cond-> {}
               (level>= :info) (assoc :metrics (metrics/get-pcp-metrics metrics-registry))
               (level>= :debug) (assoc :threads (metrics/get-thread-metrics)
                                       :memory (metrics/get-memory-metrics)))}))
