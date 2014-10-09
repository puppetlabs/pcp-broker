(ns puppetlabs.cthun.connection-states
  (:require [clojure.core.async :as async]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [schema.core :as s]
            [ring.adapter.jetty9 :as jetty-adapter])
  (:import com.hazelcast.core.Hazelcast)
  (:import com.hazelcast.core.MessageListener))

(def ConnectionState
  "The state of a websocket in the connection-map"
  {:client s/Str
   :type s/Str
   :status s/Str
   (s/optional-key :endpoint) validation/Endpoint
   :created-at validation/ISO8601})

(def connection-map (atom {})) ;; Map of ws -> ConnectionState

(def mesh (atom {}))

(def queueing (atom {}))

(def inventory (atom {}))

(defn- make-endpoint-string
  "Make a new endpoint string for a host and type"
  [host type]
  (str "cth://" host "/" type))

(s/defn ^:always-validate
  explode-endpoint :- [s/Str]
  "Parse an endpoint string into its component parts.  Raises if incomplete"
  [endpoint :- validation/Endpoint]
  (str/split (subs endpoint 6) #"/"))

(defn websockets-for-endpoints
  "return list of ws given endpoints name"
  [endpoints]
  (flatten
   (map (fn [endpoint]
          (remove nil? (keys (filter (fn [[ws state]]
                                       (= endpoint (:endpoint state)))
                                     @connection-map))))
        endpoints)))

(s/defn ^:always-validate
  new-socket :- ConnectionState
  "Return a new, unconfigured connection map"
  [client]
  {:client client
   :type "undefined"
   :status "connected"
   :created-at (ks/timestamp)})

 (defn- logged-in?
  "Determine if a websocket combination is logged in"
  [host ws]
  (= (get-in @connection-map [ws :status]) "ready"))

(defn deliver-message
  [message]
  (doseq [websocket (websockets-for-endpoints (:endpoints message))]
    (try
      (jetty-adapter/send! websocket (cheshire/generate-string message))
      (catch Exception e (log/warn (str "Exception raised while trying to process a client message: "
                                        (.getMessage e) ". Dropping message"))))))

(defn messages-to-destinations
  "Returns a sequence of messages, each with a single endpoint.  All wildcards are expanded here by the InventoryService."
  [message]
  (map (fn [endpoint]
         (assoc message :endpoints [ endpoint ]))
       ((:find-clients @inventory) (:endpoints message))))

(defn deliver-from-accept-queue
  [message]
  (doseq [message (messages-to-destinations message)]
    (log/info "delivering message from accept queue to mesh" message)
    ((:deliver-to-client @mesh) (first (:endpoints message)) message)))

(defn use-this-mesh
  "Specify which mesh to use"
  [new-mesh]
  (reset! mesh new-mesh)
  ((:register-local-delivery @mesh) deliver-message))

(defn use-this-queueing
  "Specify which queuing to use"
  [new-queueing]
  (reset! queueing new-queueing)
  ((:subscribe-to-topic @queueing) "accept" deliver-from-accept-queue))

(defn use-this-inventory
  "Specify which inventory to use"
  [new-inventory]
  (reset! inventory new-inventory))

(defn- process-client-message
  "Process a message directed at a connected client(s)"
  [host ws message]
  (let [sender (get-in @connection-map [ws :endpoint])
        message (assoc message :sender sender)]
    ((:queue-message @queueing) "accept" message)))

(defn- login-message?
  "Return true if message is a login type message"
  [message]
  (and (= (first (:endpoints message)) "cth://server")
       (= (:data_schema message) "http://puppetlabs.com/loginschema")))

(defn add-connection
  "Add a connection to the connection state map"
  [host ws]
  (swap! connection-map assoc ws (new-socket host)))

(defn remove-connection
  "Remove a connection from the connection state map"
  [host ws]
  (if-let [endpoint (get-in @connection-map [ws :endpoint])]
    ((:forget-client-location @mesh) endpoint))
  (swap! connection-map dissoc ws))


(defn- process-login-message
  "Process a login message from a client"
  [host ws message-body]
  (log/info "Processing login message")
  (when (validation/validate-login-data (:data message-body))
    (log/info "Valid login message received")
    (if (logged-in? host ws)
      (throw (Exception. (str "Received login attempt for '" host "/" (get-in message-body [:data :type]) "' on socket '"
                         ws "'.  Socket was already logged in as " host / (get-in @connection-map [ws :type])
                         " connected since " (get-in @connection-map [ws :created-at])
                         ". Ignoring")))
      (let [data (:data message-body)
            type (:type data)
            endpoint (make-endpoint-string host type)]
        (swap! connection-map update-in [ws] merge {:type type
                                                    :status "ready"
                                                    :endpoint endpoint})
        ((:record-client-location @mesh) endpoint)
        ((:record-client @inventory) endpoint)
        (log/info "Successfully logged in " host "/" type " on websocket: " ws)))))

(defn- process-inventory-message
  "Process a request for inventory data"
  [host ws message-body]
  (log/info "Processing inventory message")
  (when (validation/validate-inventory-data (:data message-body))
    (log/info "Valid inventory message received")
    (let [data (:data message-body)]
      (process-client-message host ws  {:version 1
                                        :id 12345
                                        :endpoints [(get-in @connection-map [ws :endpoint])]
                                        :data_schema "http://puppetlabs.com/inventoryresponseschema"
                                        :sender "cth://server"
                                        :expires ""
                                        :hops []
                                        :data {:response
                                               {:endpoints ((:find-clients @inventory) (:query data))}}}))))

(defn- process-server-message
  "Process a message directed at the middleware"
  [host ws message-body]
  (log/info "Procesesing server message")
  ; We've only got two message types at the moment - login and inventory
  ; More will be added as we add server functionality
  ; To define a new message type add a schema to
  ; puppetlabs.cthun.validation, check for it here and process it.
  (let [data-schema (:data_schema message-body)]
    (case data-schema
      "http://puppetlabs.com/loginschema" (process-login-message host ws message-body)
      "http://puppetlabs.com/inventoryschema" (process-inventory-message host ws message-body)
      (log/warn "Invalid server message type received: " data-schema))))

(defn process-message
  "Process an incoming message from a websocket"
  [host ws message-body]
  (log/info "processing incoming message")
  ; Check if socket has been logged into
  (if (logged-in? host ws)
  ; check if this is a message directed at the middleware
    (if (= (get (:endpoints message-body) 0) "cth://server")
      (process-server-message host ws message-body)
      (process-client-message host ws message-body))
    (if (login-message? message-body)
      (process-server-message host ws message-body)
      (log/warn "Connection cannot accept messages until login message has been "
                 "processed. Dropping message."))))
