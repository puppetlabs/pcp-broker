(ns puppetlabs.pcp.broker.core
  (:require [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.pcp.broker.shared :as shared :refer
             [Broker get-connection get-controller summarize send-error-message send-message deliver-message deliver-server-message]]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.broker.websocket :refer [Websocket ws->uri ws->client-type ws->common-name ws->remote-address]]
            [puppetlabs.pcp.broker.metrics :as metrics]
            [puppetlabs.pcp.broker.message :as message :refer [Message multicast-message?]]
            [puppetlabs.pcp.broker.inventory :as inventory]
            [puppetlabs.pcp.broker.util :refer [assoc-when] :as util]
            [puppetlabs.pcp.client :as pcp-client]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.metrics :refer [time!]]
            [clj-time.core :refer [now plus equal?]]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.structured-logging.core :as sl]
            [puppetlabs.trapperkeeper.authorization.ring :as ring]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [puppetlabs.trapperkeeper.services.protocols.filesystem-watch-service :as watch]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]
            [me.raynes.fs :as fs]
            [puppetlabs.i18n.core :as i18n])
  (:import [puppetlabs.pcp.broker.connection Connection]
           [puppetlabs.pcp.client Client]
           [clojure.lang IFn]
           [java.net InetAddress UnknownHostException URI]
           [org.eclipse.jetty.util.ssl SslContextFactory]
           [javax.net.ssl SSLContext]
           [java.security KeyStore]))

(defn get-certificate-chain
  "Return the first non-empty certificate chain encountered while scanning the
  entries in the key store of the specified org.eclipse.jetty.util.ssl.SslContextFactory
  instance - `ssl-context-factory`."
  [ssl-context-factory]
  (let [^KeyStore key-store (.getKeyStore ssl-context-factory)]
    (when key-store
      (->> (.aliases key-store)
           enumeration-seq
           (some #(.getCertificateChain key-store %))))))

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
         :info (assoc (connection/summarize connection)
                      :uri as
                      :existinguri uri
                      :type :connection-already-associated)
         ;; Logs association status with the following arguments
         ;; 0 : uri associated with incoming connection
         ;; 1 : certificate common name of connection
         ;; 2 : remote address of connection
         ;; 3 : uri previously associated with the connection
         #(i18n/trs "Rejecting session association for {0} from {1} {2}. Session was already associated as {3}."
           (:uri %) (:commonname %) (:remoteaddress %) (:existinguri %)))
        (i18n/trs "Session already associated.")))))

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
       (locking (:websocket connection)
         (send-message connection message))
       (catch Exception e
         (sl/maplog :warn e
                    {:target requester-uri
                     :type :message-delivery-error}
                    #(i18n/trs "Attempted associate response delivery to {0} failed." (:target %)))))
     (if reason-to-deny
       (do
         (sl/maplog
          :warn {:type   :connection-association-failed
                 :uri    requester-uri
                 :reason reason-to-deny}
          ;; 0 : reason to deny request
          ;; 1 : uri of connection
          #(i18n/trs "Invalid associate_request ({0}). Closing {1} WebSocket." (:reason %) (:uri %)))
         (websockets-client/close! (:websocket connection) 4002 (i18n/trs "Association unsuccessful."))
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
          connection-promise (promise)
          database (if (:subscribe data)
                     (inventory/subscribe-client! broker requester-uri connection-promise pattern-sets)
                     (inventory/unsubscribe-client! broker requester-uri))
          data (-> database :inventory (inventory/build-inventory-data pattern-sets))
          response (message/make-message
                     {:message_type "http://puppetlabs.com/inventory_response"
                      :target requester-uri
                      :in_reply_to (:id message)
                      :data data})]
      (try
        (deliver-server-message broker response connection)
        (finally
          (deliver connection-promise connection)))))
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
       :warn (assoc (connection/summarize connection)
                    :messagetype message-type
                    :type :broker-unhandled-message)
       ;; 0 : message type
       ;; 1 : common name of connection
       ;; 2 : remote address of connection
       #(i18n/trs "Unhandled message type {0} received from {1} {2}."
         (:messagetype %) (:commonname %) (:remoteaddr %))))))

;;
;; Message validation
;;

(defn- validate-message-type
  [^String message-type]
  (if-not (re-matches #"^[\w\-.:/]*$" message-type)
    (i18n/trs "Illegal message type: ''{0}''." message-type)))

(defn- validate-target
  [^String target]
  (if-not (re-matches #"^[\w\-.:/*]*$" target)
    (i18n/trs "Illegal message target: ''{0}''." target)))

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
         ;; 0 : message id (uuid)
         ;; 1 : uri of destination
         ;; 2 : message content
         #(i18n/trs "Message {0} for {1} didn''t pass pre-authorization validation: {2}"
           (:messageid %) (:destination %) (:message %)))
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
          (let [websocket (:websocket connection)
                remote-addr (ws->remote-address websocket)
                ssl-client-cert (first (websockets-client/peer-certs websocket))]
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
       ;; 0 : message id (uuid)
       ;; 1 : uri of destination
       ;; 2 : whether the message is authorized (true or false)
       ;; 3 : message content
       #(i18n/trs "Authorizing {0} for {1} - {2}: {3}"
         (:messageid %) (:destination %) (:allowed %) (:message %)))
      allowed)
    false))

(s/defn authenticated? :- s/Bool
  "Check if the cert name advertised by the sender of the message contained in
   the specified Message matches the cert name in the certificate of the
   given Connection"
  [message :- Message connection :- Connection]
  (let [sender (:sender message)
        [client] (p/explode-uri sender)]
    (= client (ws->common-name (:websocket connection)))))

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
   #(str (:accessoutcome %) (:remoteaddress %) (:commonname %) (:source %) (:messagetype %) (:messageid %) (:destination %))))

(s/defn process-message!
  "Deserialize, validate (authentication, authorization, and expiration), and
  process the specified raw message. Return the 'Connection' object associated
  to the specified 'Websocket' in case it gets modified (hence the '!' in the
  function name), otherwise nil.
  Also, log the message validation outcome via 'pcp-access' logger."
  [broker :- Broker
   bytes :- (s/either bytes s/Str)
   ws :- Websocket]
  (let [uri (ws->uri ws)]
    (if-let [connection (get-connection broker uri)]
      (let [decoder (get-in connection [:codec :decode])]
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
                       ;; 0 : uri of connection
                       ;; 1 : raw message string
                       #(i18n/trs "Processing PCP message from {0}: {1}"
                                  (:uri %) (:rawmsg %)))
            (try+
              (case (validate-message broker message connection is-association-request)
                :not-authenticated
                (let [not-authenticated-msg (i18n/trs "Message not authenticated.")]
                  (log-access :warn (assoc message-data :accessoutcome "AUTHENTICATION_FAILURE"))
                  (if is-association-request
                    ;; send an unsuccessful associate_response and close the WebSocket
                    (process-associate-request! broker message connection not-authenticated-msg)
                    (send-error-message message not-authenticated-msg connection)))
                :not-authorized
                (let [not-authorized-msg (i18n/trs "Message not authorized.")]
                  (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
                  (if is-association-request
                    ;; send an unsuccessful associate_response and close the WebSocket
                    (process-associate-request! broker message connection not-authorized-msg)
                    ;; TODO(ale): use 'unauthorized' in version 2
                    (send-error-message message not-authorized-msg connection)))
                :multicast-unsupported
                (let [multicast-unsupported-message (i18n/trs "Multiple recipients no longer supported.")]
                  (log-access :warn (assoc message-data :accessoutcome "MULTICAST_UNSUPPORTED"))
                  (send-error-message message multicast-unsupported-message connection))
                :to-be-processed
                (do
                  (log-access :info (assoc message-data :accessoutcome "AUTHORIZATION_SUCCESS"))
                  (if (= (:target message) "pcp:///server")
                    (process-server-message! broker message connection)
                    (deliver-message broker message connection)))
                ;; default case
                (assert false (i18n/trs "Unexpected message validation outcome.")))
              (catch map? m
                (sl/maplog
                  :warn (merge (connection/summarize connection)
                               (summarize message)
                               {:type :processing-error :errortype (:type m)})
                  ;; Failure while reading the message.
                  ;; 0 : message type
                  ;; 1 : message id (uuid)
                  ;; 2 : common name of connection
                  ;; 3 : remote address for connection
                  ;; 4 : error type
                  #(i18n/trs "Failed to process {0} {1} from {2} {3}: {4}"
                             (:messagetype %) (:messageid %) (:commonname %) (:remoteaddress %) (:errortype %)))
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
              #(str (:outcome %) (:remoteaddress %) (:commonname %) "unknown unknown unknown unknown unknown"))
            ;; TODO(richardc): this could use a different message_type to
            ;; indicate an encoding error rather than a processing error
            (send-error-message nil (i18n/trs "Could not decode message") connection))))
      (sl/maplog :trace {:type :incoming-message-trace
                         :uri uri
                         :remoteaddress (ws->remote-address ws)
                         :msgdump (util/hexdump bytes)}
                 ;; 0 : uri of connection
                 ;; 1 : remote address of connection
                 ;; 2 : hexdump of the websocket message payload
                 #(i18n/trs "Ignoring message received on stale connection {0} {1}. Message dump: {2}"
                            (:uri %) (:remoteaddress %) (:msgdump %))))))

(defn on-message!
  "If the broker service is not running, close the WebSocket connection.
   Otherwise process the message. Association is assumed to be completed
   on connection."
  [broker ws bytes]
  (time!
   (:on-message (:metrics broker))
   (if-not (= :running @(:state broker))
     (websockets-client/close! ws 1011 (i18n/trs "Broker is not running."))
     (process-message! broker bytes ws))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [broker ws message]
  (on-message! broker ws message))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [broker ws bytes offset len]
  (on-message! broker ws bytes))

(defn all-controllers-disconnected?
  [broker]
  (and (not (empty? @(:controllers broker)))
       (= (set (keys @(:controllers broker)))
          (set (keys (:warning-bin @(:database broker)))))))

;; Should only be called within a lock of the broker object, to prevent multiple actions on a handler.
(defn stop-handlers
  [broker]
  (doseq [handler @(:handlers broker)]
    (when (.isRunning handler)
      (sl/maplog :info {:handler (.getContextPath handler)
                         :type :rejecting-connections}
                 ;; 0 : remote address of connection
                 #(i18n/trs "Stopping handler {0}, connections will be rejected." (:handler %)))
      (.stop handler))))

;; Should only be called within a lock of the broker object, to prevent multiple actions on a handler.
(defn start-handlers
  [broker]
  (doseq [handler @(:handlers broker)]
    (when-not (.isRunning handler)
      (sl/maplog :info {:handler (.getContextPath handler)
                         :type :accepting-connections}
                 ;; 0 : remote address of connection
                 #(i18n/trs "Starting handler {0}, connections are now accepted." (:handler %)))
      (.start handler))))

;;
;; Other WebSocket event handlers
;;

(defn- on-connect!
  "OnOpen WebSocket event handler. Close the WebSocket connection if the
   Broker service is not running or if the client common name is not obtainable
   from its cert. Otherwise set the idle timeout of the WebSocket connection
   to 15 min."
  [{:keys [max-connections idle-timeout] :as broker} codec ws]
  (time!
   (:on-connect (:metrics broker))
   (cond
     (not= :running @(:state broker))
     (websockets-client/close! ws 1011 (i18n/trs "Broker is not running."))

     (nil? (ws->common-name ws))
     (do (sl/maplog :warn {:remoteaddress (ws->remote-address ws)
                           :type :connection-no-peer-certificate}
                    ;; 0 : remote address of connection
                    #(i18n/trs "No client certificate. Closing {0}." (:remoteaddress %)))
         (websockets-client/close! ws 4003 (i18n/trs "No client certificate.")))

     ;; Should no longer happen
     (all-controllers-disconnected? broker)
     (websockets-client/close! ws 1011 (i18n/trs "All controllers disconnected."))

     (and (pos? max-connections) (>= (count (:inventory @(:database broker))) max-connections))
     (websockets-client/close! ws 1011 (i18n/trs "Connection limit exceeded."))

     :else
     ;; Generate an implicit association request and authorize association.
     (let [uri (ws->uri ws)
           connection (connection/make-connection ws codec uri false)
           message (message/make-message
                     {:target "pcp:///server"
                      :sender uri
                      :message_type "http://puppetlabs.com/associate_request"})]
       ;; Also prevent association as "server" type. Reserved for outgoing connections.
       (if (or (not (authorized? broker message connection))
               (= (ws->client-type ws) "server"))
         (let [message-data (merge (connection/summarize connection)
                                   (summarize message))]
           (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
           (websockets-client/close! ws 4002 (i18n/trs "Association unsuccessful.")))

         (do
           (when-let [old-conn (get-connection broker uri)]
             (sl/maplog :info (assoc (connection/summarize old-conn)
                                     :uri uri
                                     :type :connection-association-failed)
                        ;; 0 : uri of connection
                        ;; 1 : common name of connection
                        ;; 2 : remote address of connection
                        #(i18n/trs "Node with URI {0} already associated with connection {1} {2}."
                          (:uri %) (:commonname %) (:remoteaddress %)))
             (websockets-client/disconnect (:websocket old-conn)))
           (websockets-client/idle-timeout! ws idle-timeout)
           (let [policy (.. ws getSession getPolicy)]
             ;; Support both v1 and v2 agents
             (.setMaxTextMessageSize policy (:max-message-size broker))
             (.setMaxBinaryMessageSize policy (:max-message-size broker)))
           (add-connection! broker connection)
           (sl/maplog :info (assoc (connection/summarize connection)
                                   :uri uri
                                   :type :connection-open)
                      ;; Connection successfully established.
                      ;; 0 : uri
                      ;; 1 : remote address
                      #(i18n/trs "{0} connected from {1}"
                        (:uri %) (:remoteaddress %)))))))))

(defn- on-error
  "OnError WebSocket event handler. Just log the event."
  [broker ws e]
  (if-let [connection (get-connection broker (ws->uri ws))]
    (sl/maplog :error e (assoc (connection/summarize connection)
                               :type :connection-error)
               ;; 0 : common name of connection
               ;; 1 : remote address of connection
               #(i18n/trs "Websocket error on connection {0} {1}."
                 (:commonname %) (:remoteaddress %)))
    (sl/maplog :debug e {:commonname    (ws->common-name ws)
                         :remoteaddress (ws->remote-address ws)
                         :type          :connection-error}
               ;; 0 : common name of connection
               ;; 1 : remote address of connection
               #(i18n/trs "Websocket error on stale connection {0} {1}."
                          (:commonname %) (:remoteaddress %)))))

(defn- on-close!
  "OnClose WebSocket event handler. Remove the Connection instance out of the
   broker's 'inventory' map."
  [broker ws status-code reason]
  (time! (:on-close (:metrics broker))
         (let [uri (ws->uri ws)]
           (sl/maplog
            :info {:uri uri
                   :type :connection-close
                   :statuscode status-code
                   :reason reason}
            ;; Connection closed.
            ;; 0 : uri of closed connection
            ;; 1 : status code from close event
            ;; 2 : reason close occurred
            #(i18n/trs "{0} disconnected {1} {2}"
              (:uri %) (str (:statuscode %)) (:reason %)))
           (remove-connection! broker uri))))

(s/defn build-websocket-handlers :- {s/Keyword IFn}
  [broker :- Broker codec :- Codec]
  {:on-connect (partial on-connect! broker codec)
   :on-error   (partial on-error broker)
   :on-close   (partial on-close! broker)
   :on-text    (partial on-text! broker)
   :on-bytes   (partial on-bytes! broker)})

;;
;; Outgoing client lifecycle. All messages, authentication managed by the client.
;;
;; The underlying client connection from Jetty is a different class than the incoming
;; connections: WebSocketClient instead of WebSocketAdapter. Code around authenticating
;; and authorizing messages from inbound connections relies on having access to the
;; client's certificates; WebSocketClient doesn't provide access to the server certs
;; for an outbound connection, so we instead rely on clj-pcp-client's setup of hostname
;; verification to ensure the identity (authentication) of the outbound connection and
;; use a whitelist of message types instead of trapperkeeper-authorization to authorize
;; messages.
;;

;; When a controller connection is initially established, it's possible that
;; messages can be received from the connection (in particular, the initial
;; inventory request) before our internal record-keeping considers the
;; connection to be ready. In that case, we briefly wait for the connection to
;; "complete" before processing the message, so that we can properly send a
;; response.
(def ^:const controller-connection-timeout 1000)

(s/defn default-message-handler
  [broker :- Broker
   whitelist :- #{s/Str}
   client :- Client
   message :- Message]
  (let [uri (ws->uri client)]
    (if-let [connection (get-controller broker uri controller-connection-timeout)]
      (let [message (assoc-when message :sender uri :target "pcp:///server")
            message-data (merge (connection/summarize connection) (summarize message))]
        (time!
         (:on-message (:metrics broker))
          (sl/maplog :trace {:type :controller-message-trace
                             :uri uri
                             :rawmsg message}
                     ;; 0 : uri
                     ;; 1 : raw message
                     #(i18n/trs "Received PCP message from {0}: {1}"
                       (:uri %) (:rawmsg %)))
          (cond
            ;; only accept messages from the sender
            (not (authenticated? message connection))
            (do
              (log-access :warn (assoc message-data :accessoutcome "AUTHENTICATION_FAILURE"))
              (send-error-message message (i18n/trs "Message not authenticated.") connection))

            ;; deny messages unless in `whitelist`
            (not (contains? whitelist (:message_type message)))
            (do
              (log-access :warn (assoc message-data :accessoutcome "AUTHORIZATION_FAILURE"))
              ;; TODO(ale): use 'unauthorized' in version 2
              (send-error-message message (i18n/trs "Message not authorized.") connection))

            :else
            (do
              (log-access :info (assoc message-data :accessoutcome "AUTHORIZATION_SUCCESS"))
              (if (= (:target message) "pcp:///server")
                (process-server-message! broker message connection)
                (deliver-message broker message connection))))))
      (sl/maplog :trace {:type :controller-message-trace
                         :uri uri
                         :remoteaddress (ws->remote-address client)
                         :rawmsg message}
                 ;; 0 : uri of connection
                 ;; 1 : remote address of connection
                 ;; 2 : raw message
                 #(i18n/trs "Ignoring message received on stale controller connection {0} {1}: {2}"
                            (:uri %) (:remoteaddress %) (:rawmsg %))))))

(defn maybe-purge-clients!
  "After having slept for the grace period, if all controllers are still
   disconnected and the latest disconnection timestamp matches that expected,
   purge all clients."
  [broker target-timestamp]
  (when (and (all-controllers-disconnected? broker)
             (equal? target-timestamp
                     (last (sort (vals (:warning-bin @(:database broker)))))))
    ;; lock to ensure that restarting handlers only happens when no controllers are connected
    ;; i.e. ensure that we don't check for controller connections, then on-controller-connect!
    ;; runs, then we stop all handlers. This would result in a non-functional broker.
    (locking broker
      (when (all-controllers-disconnected? broker)
        (stop-handlers broker)))
    (doseq [[_ client-connection] (:inventory @(:database broker))]
      (websockets-client/close!
        (:websocket client-connection)
        1011 (i18n/trs "All controllers disconnected.")))
    (sl/maplog
      :info {}
      (fn [_] (i18n/trs "Evicted all clients as there is no controller connection.")))))

(defn close-expired-connections!
  "Forces clients to reconnect because the broker's CRL is out of date"
  [broker]
  (let [inventory-snapshot (:inventory @(:database broker))
        throttle-duration (:expired-conn-throttle broker)]
    (sl/maplog :debug
               {:count (count inventory-snapshot)}
               #(i18n/trs "Checking the existing {0} connections for expired CRLs." (:count %)))
    (doseq [[uri client-connection] inventory-snapshot]
      (when (:expired client-connection)
        (do
          (sl/maplog :debug {:uri uri} #(i18n/trs "Closing expired connection for {0}." (:uri %)))
          (Thread/sleep throttle-duration)
          (websockets-client/close!
            (:websocket client-connection)
            1012 ;; code SERVER_RESTART, client *should* reconnect soon
            (i18n/trs "CRL reloaded"))
          (sl/maplog
            :info {}
            (fn [_] (i18n/trs "Evicted stale client connections because of CRL reload."))))))))

(defn on-controller-connect!
  [broker controller-uri ws]
  ;; lock to ensure that updating connected controllers and restarting handlers happen together
  (locking broker
    (swap! (:database broker) update :warning-bin dissoc controller-uri)
    (start-handlers broker))
  (sl/maplog
    :info {:uri controller-uri}
    ;; 0 : connection uri
    #(i18n/trs "Established connection with controller {0}." (:uri %))))

(defn schedule-client-purge!
  [broker timestamp controller-disconnection-ms]
  (future (do (Thread/sleep controller-disconnection-ms)
              (maybe-purge-clients! broker timestamp)))
  (sl/maplog
    :debug {:timeout controller-disconnection-ms}
    ;; 0 : number of milliseconds
    #(i18n/trs "Scheduled potential full client eviction in {0,number,integer} ms." (:timeout %))))

(s/defn forget-controller-subscription
  [broker :- Broker
   uri :- s/Str
   controller-disconnection-ms :- s/Int
   client :- Client]
  (let [timestamp (now)]
    (sl/maplog
      :info {:uri uri}
      ;; 0 : connection uri
      #(i18n/trs "Lost connection to controller: {0}." (:uri %)))
    (swap! (:database broker) update :subscriptions dissoc uri)
    (swap! (:database broker) update :warning-bin assoc uri timestamp)
    (when (and (= :running @(:state broker)) (all-controllers-disconnected? broker))
      (schedule-client-purge! broker timestamp controller-disconnection-ms))))

(s/defn start-client
  [broker :- Broker
   ssl-context :- SSLContext
   controller-whitelist :- #{s/Str}
   controller-disconnection-ms :- s/Int
   uri :- s/Str]
  (let [;; Note that the use of getAuthority here must match how ws->common-name is computed for Clients.
        pcp-uri (str "pcp://" (.getAuthority (URI. uri)) "/server")
        client (pcp-client/connect {:server uri
                                    :ssl-context ssl-context
                                    :type "server"
                                    :max-message-size (:max-message-size broker)
                                    :on-connect-cb (partial on-controller-connect! broker pcp-uri)
                                    :on-close-cb (partial forget-controller-subscription broker pcp-uri
                                                          controller-disconnection-ms)}
                                    {:default (partial default-message-handler broker controller-whitelist)})
        identity-codec {:decode identity :encode identity}]
    (sl/maplog :info {:type :controller-connection :uri uri :pcpuri pcp-uri}
               ;; 0 : uri identifying connection
               ;; 1 : url to connect to
               #(i18n/trs "Connecting to {0} at {1}." (:pcpuri %) (:uri %)))
    [pcp-uri (connection/make-connection client identity-codec pcp-uri false)]))

(s/defn initiate-controller-connections :- {p/Uri Connection}
  "Create PCP Clients for each controller URI"
  [broker :- Broker
   ssl-context :- SSLContext
   controller-uris :- [s/Str]
   controller-whitelist :- #{s/Str}
   controller-disconnection-ms :- s/Int]
  (into {} (pmap (partial start-client broker ssl-context
                          controller-whitelist controller-disconnection-ms)
                 controller-uris)))

(s/defn start-crl-monitoring!
  "Start periodic refreshing of connections using outdated crls"
  [broker :- Broker]
  (future
    (let [should-stop (:should-stop broker)
          check-interval (:crl-check-period broker)]
      (loop []
        (close-expired-connections! broker)
        (if (nil? (deref should-stop check-interval nil))
          (recur))))))

(s/defn expire-ssl-connections*
  [inventory :- shared/Inventory]
  (sl/maplog :info
             {:count (count inventory)}
             #(i18n/trs "Expiring existing {0} connections due to CRL update." (:count %)))
  (into {} (for [[k {:keys [websocket codec uri]}] inventory]
             [k (connection/make-connection websocket codec uri true)])))

(s/defn expire-ssl-connections
  [broker :- Broker]
  (swap! (:database broker) update :inventory expire-ssl-connections* ))

(s/defn watch-crl
  [watcher :- (s/protocol watch/Watcher)
   broker :- Broker
   ssl-context-factory :- SslContextFactory]
  (when-let [crl-config-path (.getCrlPath ssl-context-factory)]
    (let [crl-path (.getCanonicalPath (fs/file crl-config-path))]
      (watch/add-watch-dir! watcher (fs/parent crl-path))
      (watch/add-callback!
       watcher
       (fn [events]
         (when (some #(and
                       (:changed-path %)
                       (= (.getCanonicalPath (:changed-path %))
                          crl-path))
                     events)
           (expire-ssl-connections broker)))))))

;;
;; Broker service lifecycle, status service
;;

(def InitOptions
  {:add-websocket-handler IFn
   :authorization-check IFn
   :get-route IFn
   :get-metrics-registry IFn
   :max-connections s/Int
   :max-message-size s/Int
   :idle-timeout s/Int
   :crl-check-period s/Int
   :expired-conn-throttle s/Int
   (s/optional-key :broker-name) s/Str})

(s/defn init :- Broker
  [options :- InitOptions]
  (let [{:keys [broker-name
                add-websocket-handler
                authorization-check
                get-route
                get-metrics-registry
                max-connections
                max-message-size
                idle-timeout
                crl-check-period
                expired-conn-throttle]} options
        broker  {:broker-name           broker-name
                 :max-connections       max-connections
                 :max-message-size      max-message-size
                 :idle-timeout          idle-timeout
                 :crl-check-period      crl-check-period
                 :expired-conn-throttle expired-conn-throttle
                 :authorization-check   authorization-check
                 :database              (atom (inventory/init-database))
                 :controllers           (atom {})
                 :should-stop           (promise)
                 :metrics               {}
                 :metrics-registry      (get-metrics-registry)
                 :state                 (atom :starting)
                 :handlers              (atom [])}
        metrics (shared/build-and-register-metrics broker)
        broker  (assoc broker :metrics metrics)]
    (swap! (:handlers broker) conj
           (add-websocket-handler (build-websocket-handlers broker message/v1-codec) {:route-id :v1}))
    (try
      (when (get-route :v2)
        (swap! (:handlers broker) conj
               (add-websocket-handler (build-websocket-handlers broker message/v2-codec) {:route-id :v2})))
      (catch IllegalArgumentException e
        (sl/maplog :info {:type :v2-unavailable}
                   (fn [_] (i18n/trs "v2 protocol endpoint not configured.")))))
    broker))

(s/defn start
  [broker :- Broker]
  ;; either start accepting websocket connections now, or wait for a controller connection
  (locking broker
    (when (all-controllers-disconnected? broker)
      (stop-handlers broker)))
  (inventory/start-inventory-updates! broker)
  (start-crl-monitoring! broker)
  (-> broker :state (reset! :running)))

(s/defn stop
  [broker :- Broker]
  (-> broker :state (reset! :stopping))
  (inventory/stop-inventory-updates! broker)
  (doseq [[_ client] @(:controllers broker)] (pcp-client/close (:websocket client))))

(s/defn status :- status-core/StatusCallbackResponse
  [broker :- Broker level :- status-core/ServiceStatusDetailLevel]
  (let [{:keys [state metrics-registry]} broker
        level>= (partial status-core/compare-levels >= level)
        state-now @state]
    {:state (if (and (= state-now :running) (all-controllers-disconnected? broker))
              :error
              state-now)
     :status (cond-> {}
               (level>= :info) (assoc :metrics (metrics/get-pcp-metrics metrics-registry))
               (level>= :debug) (assoc :threads (metrics/get-thread-metrics)
                                       :memory (metrics/get-memory-metrics)))}))
