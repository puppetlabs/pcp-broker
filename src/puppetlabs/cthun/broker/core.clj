(ns puppetlabs.cthun.broker.core
  (:require [clamq.protocol.consumer :as mq-cons]
            [clj-time.coerce :as time-coerce]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [metrics.gauges :as gauges]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.cthun.broker.activemq :as activemq]
            [puppetlabs.cthun.broker.capsule :as capsule :refer [Capsule]]
            [puppetlabs.cthun.broker.metrics :as metrics]
            [puppetlabs.cthun.message :as message :refer [Message]]
            [puppetlabs.cthun.protocol :as p]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.metrics :refer [time!]]
            [puppetlabs.puppetdb.mq :as mq]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (clojure.lang IFn Atom)))

(def Connection
  "The state of a websocket in the connections map"
  {:state (s/enum :open :associated)
   (s/optional-key :uri) p/Uri
   :created-at p/ISO8601})

(def Websocket
  "Schema for a websocket session"
  Object)

(def UriMap
  "Mapping of Uri to Websocket, for sending"
  {p/Uri Websocket})

(def Connections
  "Mapping of Websocket session to Connection state"
  {Websocket Connection})

(def Broker
  {:activemq-broker    Object
   :activemq-consumers [Object]
   :record-client      IFn
   :find-clients       IFn
   :authorized         IFn
   :uri-map            Atom ;; atom with schema UriMap. will be checked with :validator
   :connections        Atom ;; atom with schema Connections. will be checked with :validator
   :metrics-registry   Object
   :metrics            {s/Keyword Object}})

;; Metrics
(s/defn metrics-app
  [broker :- Broker request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (metrics/render-metrics (:metrics-registry broker))})

(s/defn ^:always-validate build-and-register-metrics :- {s/Keyword Object}
  [broker :- Broker]
  (let [registry (:metrics-registry broker)]
    (gauges/gauge-fn registry ["puppetlabs.cthun.connections"]
                     (fn [] (count (keys @(:connections broker)))))
    {:on-connect       (.timer registry "puppetlabs.cthun.on-connect")
     :on-close         (.timer registry "puppetlabs.cthun.on-close")
     :on-message       (.timer registry "puppetlabs.cthun.on-message")
     :message-queueing (.timer registry "puppetlabs.cthun.message-queueing")
     :on-send          (.timer registry "puppetlabs.cthun.on-send")}))

;; names of activemq queues - as vars so they're harder to typo
(def accept-queue "accept")

(def delivery-queue "delivery")

(s/defn ^:always-validate broker-uri :- p/Uri
  [broker :- Broker]
  ;; TODO(richardc) should come from config or the cert of this instance
  "cth:///server")

;; connection lifecycle
(s/defn ^:always-validate new-socket :- Connection
  "Return the initial state for a websocket"
  []
  {:state :open
   :created-at (ks/timestamp)})

(s/defn ^:always-validate add-connection!
  "Add a Connection to the connections to track a websocket"
  [broker :- Broker ws :- Websocket]
  (swap! (:connections broker) assoc ws (new-socket)))

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

(s/defn ^:always-validate session-associated?
  "Determine if a websocket is logged in"
  [broker :- Broker ws :- Websocket]
  (= (:state (get-connection broker ws)) :associated))

;; message lifecycle
(s/defn ^:always-validate accept-message-for-delivery :- Capsule
  "Accept a message for later delivery"
  [broker :- Broker capsule :- Capsule]
  (if ((:authorized broker) (:message capsule))
    (time! (:message-queueing (:metrics broker))
           (let [capsule (capsule/add-hop capsule (broker-uri broker) "accept-to-queue")]
             (activemq/queue-message accept-queue capsule)))
    (log/info "Message not authorized, dropping" capsule))
  capsule)

(s/defn ^:always-validate process-expired-message :- Capsule
  "Reply with a ttl_expired message to the original message sender"
  [broker :- Broker capsule :- Capsule]
  (log/warn "Message " capsule " has expired. Replying with a ttl_expired.")
  (let [message (:message capsule)
        response_data {:id (:id message)}
        response (-> (message/make-message)
                     (assoc :message_type "http://puppetlabs.com/ttl_expired"
                            :targets      [(:sender message)]
                            :sender       "cth:///server")
                     (message/set-expiry 3 :seconds)
                     (message/set-json-data response_data))]
    (s/validate p/TTLExpiredMessage response_data)
    (accept-message-for-delivery broker (capsule/wrap response))))

(s/defn ^:always-validate handle-delivery-failure
  "If the message is not expired schedule for a future delivery by
  adding to the delivery queue with a delay property, otherwise reply
  with a TTL expired message"
  [broker :- Broker capsule :- Capsule reason :- s/Str]
  (log/error "Failed to deliver message" capsule reason)
  (let [expires (:expires capsule)
        now     (time/now)]
    (if (time/after? expires now)
      (let [difference  (time/in-seconds (time/interval now expires))
            retry-delay (if (<= (/ difference 2) 1) 1 (float (/ difference 2)))
            capsule     (capsule/add-hop capsule (broker-uri broker) "redelivery")]
        (log/info "Moving message to the redeliver queue for redelivery in" retry-delay "seconds")
        (time! (:message-queueing (:metrics broker))
               (activemq/queue-message delivery-queue capsule (mq/delay-property retry-delay :seconds))))
      (process-expired-message broker capsule))))

(s/defn ^:always-validate maybe-send-destination-report
  "Send a destination report about the given message, if requested"
  [broker :- Broker message :- Message targets :- [p/Uri]]
  (when (:destination_report message)
    (let [report {:id (:id message)
                  :targets targets}
          reply (-> (message/make-message)
                    (assoc :targets [(:sender message)]
                           :message_type "http://puppetlabs.com/destination_report"
                           :sender "cth:///server")
                    (message/set-expiry 3 :seconds)
                    (message/set-json-data report))]
      (s/validate p/DestinationReport report)
      (accept-message-for-delivery broker (capsule/wrap reply)))))

;; ActiveMQ queue consumers
(s/defn ^:always-validate deliver-message
  "Message consumer. Delivers a message to the websocket indicated by the :target field"
  [broker :- Broker capsule :- Capsule]
  (let [message (:message capsule)]
    (if-let [websocket (get-websocket broker (:target capsule))]
      (try
        (log/info "delivering message to websocket" capsule)
        (locking websocket
          (time! (:on-send (:metrics broker))
                 (let [capsule (capsule/add-hop capsule (broker-uri broker) "deliver")]
                   (websockets-client/send! websocket (capsule/encode capsule)))))
        (catch Exception e
          (log/error e "deliver-message")
          (handle-delivery-failure broker capsule (str e))))
      (handle-delivery-failure broker capsule "not connected"))))

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

(s/defn ^:always-validate subscribe-to-queues
  [broker :- Broker accept-count delivery-count]
  (concat (activemq/subscribe-to-queue accept-queue (partial expand-destinations broker) accept-count)
          (activemq/subscribe-to-queue delivery-queue (partial deliver-message broker) delivery-count)))

(s/defn ^:always-validate session-association-message? :- s/Bool
  "Return true if message is a session association message"
  [message :- Message]
  (and (= (:targets message) ["cth:///server"])
       (= (:message_type message) "http://puppetlabs.com/associate_request")))

(s/defn ^:always-validate association-response :- (dissoc p/AssociateResponse :id)
  [broker :- Broker ws :- Websocket message :- Message]
  (let [uri (:sender message)
        [certname type] (p/explode-uri uri)]
    (log/info "Processing associate_request for" uri)
    (if (= type "server") ;; currently we don't support inter-server connects
      {:success false
       :reason "'server' type connections not accepted"}
      (if (session-associated? broker ws)
        (let [current (get-connection broker ws)]
          (log/errorf (str "Received session association for '%s' on socket '%s'.  "
                           "Socket was already associated as '%s' connected since %s.  "
                           "Closing connection.")
                      uri ws (:uri current) (:created-at current))
          {:success false
           :reason "session already associated"})
        (do
          (if-let [old-ws (get-websocket broker uri)]
            (do
              (log/infof "Node with uri %s already associated at with socket '%s'. Closing old connection." uri old-ws)
              (websockets-client/close! old-ws 4000 "superceded")
              (swap! (:connections broker) dissoc old-ws)))
          (swap! (:connections broker) update-in [ws] merge {:state :associated
                                                             :uri  uri})
          (swap! (:uri-map broker) assoc uri ws)
          ((:record-client broker) uri)
          (log/infof "Successfully associated %s with websocket %s" uri ws)
          {:success true})))))

(s/defn ^:always-validate process-session-association-message
  "Process a session association message on a websocket"
  [broker :- Broker ws :- Websocket request :- Message]
  (let [response (merge {:id (:id request)}
                        (association-response broker ws request))]
    (s/validate p/AssociateResponse response)
    (let [message (-> (message/make-message)
                      (assoc :message_type "http://puppetlabs.com/associate_response"
                             :targets [ (:sender request) ]
                             :sender "cth:///server")
                      (message/set-expiry 3 :seconds)
                      (message/set-json-data response))]
      (websockets-client/send! ws (message/encode message))
      (if (not (:success response))
        (websockets-client/close! ws 4002 "association unsuccessful"))
      (:success response))))

(s/defn ^:always-validate process-inventory-message
  "Process a request for inventory data"
  [broker :- Broker ws :- Websocket message :- Message]
  (log/info "Processing inventory message")
  (let [data (message/get-json-data message)]
    (s/validate p/InventoryRequest data)
    (let [uris ((:find-clients broker) (:query data))
          response-data {:uris uris}
          response (-> (message/make-message)
                       (assoc :message_type "http://puppetlabs.com/inventory_response"
                              :targets [(:sender message)]
                              :sender "cth:///server")
                       (message/set-expiry 3 :seconds)
                       (message/set-json-data response-data))]
      (s/validate p/InventoryResponse response-data)
      (accept-message-for-delivery broker (capsule/wrap response)))))

(s/defn ^:always-validate process-server-message
  "Process a message directed at the middleware"
  [broker :- Broker ws :- Websocket capsule :- Capsule]
  (log/info "Procesesing server message")
  ; We've only got two message types at the moment - session-association and inventory
  ; More will be added as we add server functionality
  (let [message      (:message capsule)
        message-type (:message_type message)]
    (case message-type
      "http://puppetlabs.com/associate_request" (process-session-association-message broker ws message)
      "http://puppetlabs.com/inventory_request" (process-inventory-message broker ws message)
      (log/warn "Invalid server message type received: " message-type))))

(s/defn ^:always-validate process-message
  "Process an incoming message from a websocket"
  [broker :- Broker ws :- Websocket capsule :- Capsule]
  (log/info "processing incoming message")
  ; check if message has expired
  (if (capsule/expired? capsule)
    (process-expired-message broker capsule)
    ; Check if socket is associated
    (if (session-associated? broker ws)
      ; check if this is a message directed at the middleware
      (if (= (:targets (:message capsule)) ["cth:///server"])
        (process-server-message broker ws capsule)
        (accept-message-for-delivery broker capsule))
      (if (session-association-message? (:message capsule))
        (process-server-message broker ws capsule)
        (log/warn "Connection cannot accept messages until session has been associated.  Dropping message.")))))

(s/defn ^:always-validate validate-certname :- s/Bool
  "Validate that the cert name advertised by the client matches the cert name in the certificate"
  [endpoint :- p/Uri certname :- s/Str]
  (let [[client] (p/explode-uri endpoint)]
    (if-not (= client certname)
      (throw+ {:type ::identity-invalid
               :message (str "Certificate mismatch.  Sender: '" client "' CN: '" certname "'")})
      true)))

;; Websocket event handlers
(defn get-cn
  "Get the client certificate name from a websocket"
  [ws]
  (when-let [cert (first (websockets-client/peer-certs ws))]
    (ks/cn-for-cert cert)))

(defn- on-connect!
  "OnConnect websocket event handler"
  [broker ws]
  (time! (:on-connect (:metrics broker))
         (let [host (get-cn ws)
               idle-timeout (* 1000 60 15)]
           (log/infof "Connection established from client %s on %s" host ws)
           (websockets-client/idle-timeout! ws idle-timeout)
           (add-connection! broker ws))))

(defn on-message!
  [broker ws bytes]
  (let [timestamp (ks/timestamp)]
    (time! (:on-message (:metrics broker))
           (let [cn (get-cn ws)]
             (log/infof "Received message from client %s on %s" cn ws)
             (try+
              (let [message (message/decode bytes)]
                (validate-certname (:sender message) cn)
                (let [capsule (capsule/wrap message)
                      capsule (capsule/add-hop capsule (broker-uri broker) "accepted" timestamp)]
                  (log/info "Processing message")
                  (process-message broker ws capsule)))
              (catch map? m
                (let [error-body {:description (str "Error " (:type m) " handling message: " (:message &throw-context))}
                      error-message (-> (message/make-message)
                                        (assoc :id (ks/uuid)
                                               :message_type "http://puppetlabs.com/error_message"
                                               :sender "cth:///server")
                                        (message/set-json-data error-body))]
                  (s/validate p/ErrorMessage error-body)
                  (log/warn "sending error message" error-body)
                  (websockets-client/send! ws (message/encode error-message))))
              (catch Throwable e
                (log/error e "on-message")))))))

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
  (log/error e))

(defn- on-close!
  "OnClose websocket event handler"
  [broker ws status-code reason]
  (time! (:on-close (:metrics broker))
         (let [cn (get-cn ws)]
           (log/infof "Connection from %s on %s terminated with statuscode: %s Reason: %s"
                      cn ws status-code reason)
           (remove-connection! broker ws))))

(s/defn ^:always-validate build-websocket-handlers :- {s/Keyword IFn}
  [broker :- Broker]
  {:on-connect (partial on-connect! broker)
   :on-error   (partial on-error broker)
   :on-close   (partial on-close! broker)
   :on-text    (partial on-text! broker)
   :on-bytes   (partial on-bytes! broker)})

;; service lifecycle
(def InitOptions
  {:activemq-spool s/Str
   :accept-consumers s/Num
   :delivery-consumers s/Num
   :add-ring-handler IFn
   :add-websocket-handler IFn
   :record-client IFn
   :find-clients IFn
   :authorized IFn
   :get-metrics-registry IFn})

(s/defn ^:always-validate init :- Broker
  [options :- InitOptions]
  (let [{:keys [path activemq-spool accept-consumers delivery-consumers
                add-ring-handler add-websocket-handler
                record-client find-clients authorized get-metrics-registry]} options]
    (let [activemq-broker    (mq/build-embedded-broker activemq-spool)
          broker             {:activemq-broker    activemq-broker
                              :activemq-consumers []
                              :record-client      record-client
                              :find-clients       find-clients
                              :authorized         authorized
                              :metrics            {}
                              :metrics-registry   (get-metrics-registry)
                              :connections        (atom {} :validator (partial s/validate Connections))
                              :uri-map            (atom {} :validator (partial s/validate UriMap))}
          metrics            (build-and-register-metrics broker)
          broker             (assoc broker :metrics metrics)
          activemq-consumers (subscribe-to-queues broker accept-consumers delivery-consumers)
          broker             (assoc broker :activemq-consumers activemq-consumers)]
      (add-ring-handler (partial metrics-app broker) {:route-id :metrics})
      (add-websocket-handler (build-websocket-handlers broker) {:route-id :websocket})
      broker)))

(s/defn ^:always-validate start
  [broker :- Broker]
  (let [{:keys [activemq-broker]} broker]
    (mq/start-broker! activemq-broker)))

(s/defn ^:always-validate stop
  [broker :- Broker]
  (let [{:keys [activemq-broker activemq-consumers]} broker]
    (doseq [consumer activemq-consumers]
      (mq-cons/close consumer))
    (mq/stop-broker! activemq-broker)))
