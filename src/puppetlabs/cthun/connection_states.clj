(ns puppetlabs.cthun.connection-states
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [ring.adapter.jetty9 :as jetty-adapter]))

(def connection-map (atom {}))
(def endpoint-map (atom {}))

; TODO(ploubser): Some of the functions defined here don't feel
; like they fit with the name you've currently got for the namespace.

(defn- get-endpoint-string
  "Create a new endpoint string"
  [host type]
  (str "cth://" host "/" type "/" (str (java.util.UUID/randomUUID))))

(defn- new-socket
  "Return a new, unconfigured connection map"
  []
  {:socket-type "undefined"
   :status "connected"
   :user "undefined"
   :endpoint "undefined"
   :created-at (ks/timestamp)})

(defn- process-login-message
  "Process a login message from a client"
  [host ws message-body]
  (log/info "Processing login message")
  (when (validation/validate-login-data (:data message-body))
    (log/info "Valid login message received")
    (if (= (:status (get (get @connection-map host) ws)) "ready")
      ;There has already been a login even on the websocket
      (throw (Exception. (str "Received login attempt from host '" host "' on socket '"
                         ws "' but already logged in at " (:create-at (get (get @connection-map host) ws))
                         " as "
                         (:user (get (get @connection-map host) ws))
                         ". Ignoring")))
      (let [data (:data message-body)
            type (:type data)
            user (:user data)
            endpoint (get-endpoint-string host type)]
        (swap! connection-map assoc-in [host ws]
               (-> (assoc (new-socket) :socket-type type)
                   (assoc :status "ready")
                   (assoc :endpoint endpoint)
                   (assoc :user user)))
        (swap! endpoint-map assoc endpoint ws)
        (log/info "Successfully logged in user: " user " of type: " type
                  " on websocket: " ws)))))

(defn- process-server-message
  "Process a message directed at the middleware"
  [host ws message-body]
  (log/info "Procesesing server message")
  ; We've only got one message type at the moment - login
  ; More will be added as we add server functionality
  ; To define a new message type add a schema to
  ; puppetlabs.cthun.validation, check for it here and process it.
  (let [data-schema (:data_schema message-body)]
    (case data-schema
      "http://puppetlabs.com/loginschema" (process-login-message host ws message-body)
      (log/warn "Invalid server message type received: " data-schema))))


(defn- process-client-message
  "Process a message directed at a connected client"
  [host ws message-body]
  (map (fn [endpoint]
  (doseq [endpoints (message-body "endpoints")]
                (fn [endpoint]
        (if (contains? @endpoint-map endpoint)
          (jetty-adapter/send! (@endpoint-map endpoint) 
                               (cheshire/generate-string message-body))
          (println "Message directed at non existing endpoint: " endpoint
                   ". Ignoring message.")))
             (message-body "endpoints")))))

(defn- logged-in?
  "Determine if host/websocket combination has logged in"
  [host ws]
  (= (:status (get (get @connection-map host) ws)) "ready"))


(defn- login-message?
  "Return true if message is a login type message"
  [message]
  (and (= (get (:endpoints message) 0) "cth://server") 
       (= (:data_schema message) "http://puppetlabs.com/loginschema")))

(defn add-connection
  "Add a connection to the connection state map"
  [host ws]
  (swap! connection-map assoc-in [host ws] (new-socket)))

(defn remove-connection
  "Remove a connection from the connection state map"
  [host ws]
  ; Remove the endpoint
  (swap! endpoint-map dissoc (:endpoint (get ws (get host @connection-map)))
  ; Remove the connection
  (swap! connection-map update-in [host] dissoc ws))
  (when (empty? (@connection-map host))
    (swap! connection-map dissoc host)))

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

