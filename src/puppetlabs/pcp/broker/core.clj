(ns puppetlabs.pcp.broker.core
  (:require [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.pcp.broker.shared :as shared :refer
             [Broker get-connection summarize send-error-message send-message deliver-message deliver-server-message]]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.broker.websocket :refer [Websocket ws->uri ws->client-type]]
            [puppetlabs.pcp.broker.metrics :as metrics]
            [puppetlabs.pcp.broker.message :as message :refer [Message multicast-message?]]
            [puppetlabs.pcp.broker.inventory :as inventory]
            [puppetlabs.pcp.broker.util :refer [assoc-when]]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.metrics :refer [time!]]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.structured-logging.core :as sl]
            [puppetlabs.trapperkeeper.authorization.ring :as ring]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]
            [puppetlabs.i18n.core :as i18n])
  (:import [puppetlabs.pcp.broker.connection Connection]
           [clojure.lang IFn]
           [java.net InetAddress UnknownHostException]
           [java.security KeyStore]))

(defn get-certificate-chain
  "Return the first non-empty certificate chain encountered while scanning the
  entries in the key store of the specified org.eclipse.jetty.util.ssl.SslContextFactory
  instance - `ssl-context-factory`."
  [ssl-context-factory]
  (try
    (let [load-key-store-method (doto
                                 (-> (class ssl-context-factory)
                                     (.getDeclaredMethod "loadKeyStore" (into-array Class [])))
                                  (.setAccessible true))
          ^KeyStore key-store (.invoke load-key-store-method ssl-context-factory (into-array Object []))]
      (->> (.aliases key-store)
           enumeration-seq
           (some #(.getCertificateChain key-store %))))
    (catch Exception _)))

(s/defn get-webserver-cn :- (s/maybe s/Str)
  "Return the common name from the certificate the webserver specified by its
  context - `webserver-context` - will use when establishing SSL connections
  or nil if there was a problem finding out the certificate (for instance
  when the webserver is not SSL enabled)."
  [webserver-context :- jetty9-core/ServerContext]
  (some-> webserver-context
          :state
          deref
          :ssl-context-factory
          get-certificate-chain
          first
          ssl-utils/get-cn-from-x509-certificate))

(s/defn get-localhost-hostname :- s/Str
  "Return the hostname of the host executing the code."
  []
  (try
    (-> (InetAddress/getLocalHost)
        .getHostName)
    (catch UnknownHostException e
      (let [message (.getMessage e)]
        (subs message 0 (.indexOf message (int \:)))))))

;; broker database lifecycle
(s/defn add-connection! :- shared/BrokerDatabase
  "Add a Connection to the ':inventory' to track the websocket and add
  a corresponding change record the ':updates' vector."
  [broker :- Broker
   connection :- Connection]
  (let [database (:database broker)
        uri (:uri connection)
        change {:client uri :change 1}]
    (swap! database #(-> %
                         (update :inventory assoc uri connection)
                         (update :updates conj change)))))

(s/defn remove-connection! :- shared/BrokerDatabase
  "Remove a Connection from ':inventory' and possibly ':subscriptions' and
  add a corresponding change record the ':updates' vector."
  [broker :- Broker
   uri :- p/Uri]
  (let [database (:database broker)
        change {:client uri :change -1}]
    (swap! database #(-> %
                         (update :inventory dissoc uri)
                         (update :subscriptions dissoc uri)
                         (update :updates conj change)))))

;;
;; Message processing
;;

(s/defn session-association-request? :- s/Bool
  "Return true if message is a session association message"
  [message :- Message]
  (and (= (:target message) "pcp:///server")
       (= (:message_type message) "http://puppetlabs.com/associate_request")))

;; process-associate-request! helper
(s/defn reason-to-deny-association :- (s/maybe s/Str)
  "Returns an error message describing why the session should not be
  allowed, if it should be denied"
  [_ :- Broker connection :- Connection as :- p/Uri]
  (let [[_ type] (p/explode-uri as)]
    (when (not= type (ws->client-type (:websocket connection)))
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
  from the 'inventory' map. It is assumed that such update will be done
  asynchronously by the onClose handler."
  ([broker :- Broker
    message :- Message
    connection :- Connection]
   (let [requester-uri (:sender message)
         reason-to-deny (reason-to-deny-association broker connection requester-uri)]
     (process-associate-request! broker message connection reason-to-deny)))
  ([_ :- Broker
    request :- Message
    connection :- Connection
    reason-to-deny :- (s/maybe s/Str)]
   ;; NB(ale): don't validate the associate_request as there's no data chunk...
   (let [id (:id request)
         requester-uri (:sender request)
         response-data (make-associate_response-data-content id reason-to-deny)
         message (message/make-message
                  {:message_type "http://puppetlabs.com/associate_response"
                   :target requester-uri
                   :in_reply_to id
                   :sender "pcp:///server"
                   :data response-data})]
     (try
       (send-message connection message)
       (catch Exception e
         (sl/maplog :debug e
                    {:type :message-delivery-error}
                    (i18n/trs "Error in process-associate-request!"))))
     (if reason-to-deny
       (do
         (sl/maplog
          :debug {:type   :connection-association-failed
                  :uri    requester-uri
                  :reason reason-to-deny}
          (i18n/trs "Invalid associate_request ('{reason}'); closing '{uri}' WebSocket"))
         (websockets-client/close! (:websocket connection) 4002 (i18n/trs "association unsuccessful"))
         nil)
       (assoc connection :uri requester-uri)))))

(s/defn process-inventory-request
  "Process a request for inventory data.
   This function assumes that the requester client is associated.
   Returns nil."
  [broker :- Broker
   message :- Message
   connection :- Connection]
  (let [data (:data message)]
    (s/validate p/InventoryRequest data)
    (let [requester-uri (:sender message)
          pattern-sets (inventory/build-pattern-sets (:query data))
          database (if (:subscribe data)
                     (inventory/subscribe-client! broker requester-uri connection pattern-sets)
                     (inventory/unsubscribe-client! broker requester-uri))
          data (-> database :inventory (inventory/build-inventory-data pattern-sets))
          response (message/make-message
                     {:message_type "http://puppetlabs.com/inventory_response"
                      :target requester-uri
                      :in_reply_to (:id message)
                      :data data})]
      (deliver-server-message broker response connection)))
  nil)

(s/defn process-server-message! :- (s/maybe Connection)
  "Process a message directed at the middleware"
  [broker :- Broker
   message :- Message
   connection :- Connection]
  (let [message-type (:message_type message)]
    (case message-type
      "http://puppetlabs.com/associate_request" (process-associate-request! broker message connection)
      "http://puppetlabs.com/inventory_request" (process-inventory-request broker message connection)
      (sl/maplog
       :debug (assoc (connection/summarize connection)
                     :messagetype message-type
                     :type :broker-unhandled-message)
       (i18n/trs "Unhandled message type '{messagetype}' received from '{commonname}' '{remoteaddr}'")))))

;;
;; Message validation
;;

(defn- validate-message-type
  [^String message-type]
  (if-not (re-matches #"^[\w\-.:/]*$" message-type)
    (i18n/trs "Illegal message type: ''{0}''" message-type)))

(defn- validate-target
  [^String target]
  (if-not (re-matches #"^[\w\-.:/*]*$" target)
    (i18n/trs "Illegal message target: ''{0}''" target)))

(s/defn make-ring-request :- (s/maybe ring/Request)
  [message :- Message connection :- (s/maybe Connection)]
  (let [{:keys [sender target message_type]} message]
    (if-let [validation-result (or (validate-message-type message_type)
                                   (validate-target target))]
      (do
        (sl/maplog
         :warn (merge (summarize message)
                      {:type    :message-authorization
                       :allowed false
                       :message validation-result})
         (i18n/trs "Message '{messageid}' for '{destination}' didn''t pass pre-authorization validation: '{message}'"))
        nil)                                                ; make sure to return nil
      (let [query-params {"sender" sender
                          "target" target
                          "message_type" message_type}
            request {:uri            "/pcp-broker/send"
                     :request-method :post
                     :remote-addr    ""
                     :form-params    {}
                     :query-params   query-params
                     :params         query-params}]
        ;; NB(ale): we may not have the Connection when running tests
        (if connection
          (let [remote-addr (:remote-address connection)
                ssl-client-cert (first (websockets-client/peer-certs (:websocket connection)))]
            (assoc request :remote-addr remote-addr
                   :ssl-client-cert ssl-client-cert))
          request)))))

;; NB(ale): using (s/maybe Connection) in the signature for the sake of testing
(s/defn authorized? :- s/Bool
  "Check if the message within the specified message is authorized"
  [broker :- Broker request :- Message connection :- (s/maybe Connection)]
  (if-let [ring-request (make-ring-request request connection)]
    (let [{:keys [authorization-check]} broker
          {:keys [authorized message]} (authorization-check ring-request)
          allowed (boolean authorized)]
      (sl/maplog
       :trace (merge (summarize request)
                     {:type    :message-authorization
                      :allowed allowed
                      :message message})
       (i18n/trs "Authorizing '{messageid}' for '{destination}' - '{allowed}': '{message}'"))
      allowed)
    false))

(s/defn authenticated? :- s/Bool
  "Check if the cert name advertised by the sender of the message contained in
   the specified Message matches the cert name in the certificate of the
   given Connection"
  [message :- Message connection :- Connection]
  (let [{:keys [common-name]} connection
        sender (:sender message)
        [client] (p/explode-uri sender)]
    (= client common-name)))

(def MessageValidationOutcome
  "Outcome of validate-message"
  (s/enum :not-authenticated
          :not-authorized
          :multicast-unsupported
          :to-be-processed))

(s/defn validate-message :- MessageValidationOutcome
  "Determine whether the specified message should be processed by checking,
   in order, if the message: 1) is an associate-request as expected during
   Session Association; 2) is authenticated; 3) is authorized; 4) does not
   use multicast delivery."
  [broker :- Broker message :- Message connection :- Connection is-association-request :- s/Bool]
  (cond
    (not (authenticated? message connection)) :not-authenticated
    (not (authorized? broker message connection)) :not-authorized
    (multicast-message? message) :multicast-unsupported
    :else :to-be-processed))

;;
;; WebSocket onMessage handling
;;

(defn log-access
  [lvl message-data]
  (sl/maplog
   [:puppetlabs.pcp.broker.pcp_access lvl]
   message-data
   "{accessoutcome} {remoteaddress} {commonname} {source} {messagetype} {messageid} {destination}"))

(s/defn process-message!
  "Deserialize, validate (authentication, authorization, and expiration), and
  process the specified raw message. Return the 'Connection' object associated
  to the specified 'Websocket' in case it gets modified (hence the '!' in the
  function name), otherwise nil.
  Also, log the message validation outcome via 'pcp-access' logger."
  [broker :- Broker
   bytes :- (s/either bytes s/Str)
   ws :- Websocket]
  (let [uri (ws->uri ws)
        connection (get-connection broker uri)
        decoder (get-in connection [:codec :decode])]
    (try+
     (let [message (-> bytes
                       decoder
                       (assoc-when :sender uri
                                   :target "pcp:///server"))
           message-data (merge (connection/summarize connection)
                               (summarize message))
           is-association-request (session-association-request? message)]
        ;; Note that the data section of messages could be large. Hence :trace.
       (sl/maplog :trace {:type :incoming-message-trace
                          :uri uri
                          :rawmsg message}
                  (i18n/trs "Processing PCP message from '{uri}': '{rawmsg}'"))
       (try+
        (case (validate-message broker message connection is-association-request)
          :not-authenticated
          (let [not-authenticated-msg (i18n/trs "Message not authenticated")]
            (log-access :warn (assoc message-data :accessoutcome "AUTHENTICATION_FAILURE"))
            (if is-association-request
                ;; send an unsuccessful associate_response and close the WebSocket
              (process-associate-request! broker message connection not-authenticated-msg)
              (send-error-message message not-authenticated-msg connection)))
          :not-authorized
          (let [not-authorized-msg (i18n/trs "Message not authorized")]
            (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
            (if is-association-request
                ;; send an unsuccessful associate_response and close the WebSocket
              (process-associate-request! broker message connection not-authorized-msg)
                ;; TODO(ale): use 'unauthorized' in version 2
              (send-error-message message not-authorized-msg connection)))
          :multicast-unsupported
          (let [multicast-unsupported-message (i18n/trs "Multiple recipients no longer supported")]
            (log-access :warn (assoc message-data :accessoutcome "MULTICAST_UNSUPPORTED"))
            (send-error-message message multicast-unsupported-message connection))
          :to-be-processed
          (do
            (log-access :info (assoc message-data :accessoutcome "AUTHORIZATION_SUCCESS"))
            (if (= (:target message) "pcp:///server")
              (process-server-message! broker message connection)
              (deliver-message broker message connection)))
            ;; default case
          (assert false (i18n/trs "unexpected message validation outcome")))
        (catch map? m
          (sl/maplog
           :debug (merge (connection/summarize connection)
                         (summarize message)
                         {:type :processing-error :errortype (:type m)})
           (i18n/trs "Failed to process '{messagetype}' '{messageid}' from '{commonname}' '{remoteaddress}': '{errortype}'"))
          (send-error-message
           message
           (i18n/trs "Error {0} handling message: {1}" (:type m) &throw-context)
           connection))))
     (catch map? m
       (sl/maplog
        [:puppetlabs.pcp.broker.pcp_access :warn]
        (assoc (connection/summarize connection)
               :type :deserialization-failure
               :outcome "DESERIALIZATION_ERROR")
        "{outcome} {remoteaddress} {commonname} unknown unknown unknown unknown unknown")
        ;; TODO(richardc): this could use a different message_type to
        ;; indicate an encoding error rather than a processing error
       (send-error-message nil (i18n/trs "Could not decode message") connection)))))

(defn on-message!
  "If the broker service is not running, close the WebSocket connection.
   Otherwise process the message. Association is assumed to be completed
   on connection."
  [broker ws bytes]
  (time!
   (:on-message (:metrics broker))
   (if-not (= :running @(:state broker))
     (websockets-client/close! ws 1011 (i18n/trs "Broker is not running"))
     (process-message! broker bytes ws))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [broker ws message]
  (on-message! broker ws message))

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
   (if (not= :running @(:state broker))
    (websockets-client/close! ws 1011 (i18n/trs "Broker is not running"))

    (if (nil? (ws->common-name ws))
      (do
        (sl/maplog :debug {:remoteaddress (ws->remote-address ws)
                           :type :connection-no-peer-certificate}
                   (i18n/trs "No client certificate, closing '{remoteaddress}'"))
        (websockets-client/close! ws 4003 (i18n/trs "No client certificate")))

      (let [uri (ws->uri ws)
            connection (connection/make-connection ws codec uri)
            message (message/make-message
                      {:target "pcp:///server"
                       :sender uri
                       :message_type "http://puppetlabs.com/associate_request"})]
        (if (not (authorized? broker message connection))
          (let [message-data (merge (connection/summarize connection)
                                    (summarize message))]
            (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
            (websockets-client/close! ws 4002 (i18n/trs "association unsuccessful")))

          (do
            (when-let [old-conn (get-connection broker uri)]
              (sl/maplog :debug (assoc (connection/summarize old-conn)
                                       :uri uri
                                       :type :connection-association-failed)
                         (i18n/trs "Node with URI '{uri}' already associated with connection '{commonname}' '{remoteaddress}'"))
              (websockets-client/close! (:websocket old-conn) 4000 (i18n/trs "superseded")))
            (websockets-client/idle-timeout! ws (* 1000 60 15))
            (add-connection! broker connection)
            (sl/maplog :debug (assoc (connection/summarize connection)
                                     :uri uri
                                     :type :connection-open)
                       (i18n/trs "'{uri}' connected from '{remoteaddress}'")))))))))

(defn- on-error
  "OnError WebSocket event handler. Just log the event."
  [broker ws e]
  (let [connection (get-connection broker (ws->uri ws))]
    (sl/maplog :error e (assoc (connection/summarize connection)
                               :type :connection-error)
               (i18n/trs "Websocket error '{commonname}' '{remoteaddress}'"))))

(defn- on-close!
  "OnClose WebSocket event handler. Remove the Connection instance out of the
   broker's 'inventory' map."
  [broker ws status-code reason]
  (time! (:on-close (:metrics broker))
         (let [uri (ws->uri ws)
               connection (get-connection broker uri)]
           (sl/maplog
            :debug (assoc (connection/summarize connection)
                          :uri uri
                          :type :connection-close
                          :statuscode status-code
                          :reason reason)
            (i18n/trs "'{uri}' disconnected from '{remoteaddress}' '{statuscode}' '{reason}'"))
           (remove-connection! broker uri))))

(s/defn build-websocket-handlers :- {s/Keyword IFn}
  [broker :- Broker codec :- Codec]
  {:on-connect (partial on-connect! broker codec)
   :on-error   (partial on-error broker)
   :on-close   (partial on-close! broker)
   :on-text    (partial on-text! broker)
   :on-bytes   (partial on-bytes! broker)})

;;
;; Broker service lifecycle, status service
;;

(def InitOptions
  {:add-websocket-handler IFn
   :authorization-check IFn
   :get-route IFn
   :get-metrics-registry IFn
   (s/optional-key :broker-name) s/Str})

(s/defn init :- Broker
  [options :- InitOptions]
  (let [{:keys [broker-name
                add-websocket-handler
                authorization-check
                get-route
                get-metrics-registry]} options
        broker  {:broker-name         broker-name
                 :authorization-check authorization-check
                 :database            (atom (inventory/init-database))
                 :should-stop         (promise)
                 :metrics             {}
                 :metrics-registry    (get-metrics-registry)
                 :state               (atom :starting)}
        metrics (shared/build-and-register-metrics broker)
        broker  (assoc broker :metrics metrics)]
    (add-websocket-handler (build-websocket-handlers broker message/v1-codec) {:route-id :v1})
    (try
      (when (get-route :v2)
        (add-websocket-handler (build-websocket-handlers broker message/v2-codec) {:route-id :v2}))
      (catch IllegalArgumentException e
        (sl/maplog :trace {:type :v2-unavailable}
                   (i18n/trs "v2 protocol endpoint not configured"))))
    broker))

(s/defn start
  [broker :- Broker]
  (inventory/start-inventory-updates! broker)
  (-> broker :state (reset! :running)))

(s/defn stop
  [broker :- Broker]
  (-> broker :state (reset! :stopping))
  (inventory/stop-inventory-updates! broker))

(s/defn status :- status-core/StatusCallbackResponse
  [broker :- Broker level :- status-core/ServiceStatusDetailLevel]
  (let [{:keys [state metrics-registry]} broker
        level>= (partial status-core/compare-levels >= level)]
    {:state  @state
     :status (cond-> {}
               (level>= :info) (assoc :metrics (metrics/get-pcp-metrics metrics-registry))
               (level>= :debug) (assoc :threads (metrics/get-thread-metrics)
                                       :memory (metrics/get-memory-metrics)))}))
