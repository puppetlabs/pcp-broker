(ns puppetlabs.cthun.connection-states
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [ring.adapter.jetty9 :as jetty-adapter]))

(def connection-map (atom {}))
(def endpoint-map (atom {}))

(defn- find-websockets
  "Find all websockets matching and endpoint array"
  [points sub-map]
  (if (> (count points) 0)
    (let [point (first points)
          rest-of-points (subvec points 1)]
      (if (= point "*")
        (flatten (map #(find-websockets rest-of-points (get sub-map %)) (keys sub-map)))
        (when-let [new-sub-map (get sub-map point)]
          (find-websockets rest-of-points new-sub-map))))
    sub-map))

(defn- parse-endpoints
  "Return a lazy sequence of websockets derived from the endpoint key in a message"
  [endpoints e-map]
  (remove nil? 
          (flatten (map (fn [endpoint]
                          (let [protocol (subs endpoint 0 6)
                                points (str/split (subs endpoint 6) #"/")]
                            (when-not (= protocol "cth://")
                              (throw (Exception. (str "Invalid protocol: " protocol))))
                            (let [websockets (find-websockets points e-map)]
                              (if (nil? websockets)
                                (log/info "No endpoints registered matching: " endpoint " - Discarding message")
                                websockets))))
                        endpoints))))

(defn- get-endpoint-string
  "Create a new endpoint string"
  [host type]
  (str "cth://" host "/" type "/" (str (java.util.UUID/randomUUID))))

; TODO(ploubser): This seems ever so janky. I'm willing to bet money that there
; is a more lispy way of doing this.
(defn- insert-endpoint!
  "Create a map from an endpoint string and websocket object"
  [endpoint ws]
  (let [points (str/split (subs endpoint 6) #"/")
        host (get points 0)
        type (get points 1)
        uid (get points 2)]
    (swap! endpoint-map (fn [e-map]
                          (if-let [found-host (get @endpoint-map host)]
                            (if-let [found-type (get found-host type)]
                              (if-let [found-uid (get found-type uid)]
                                (throw (Exception. (str "Endpoint already exists: " endpoint)))
                                (assoc-in e-map [host type] (conj (get (get e-map host) type) {uid ws})))
                              (assoc-in e-map [host] (conj (get e-map host) {type {uid ws}})))
                            (merge e-map {host {type {uid ws}}}))))))

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
               (-> (new-socket)
                   (assoc :socket-type type)
                   (assoc :status "ready")
                   (assoc :endpoint endpoint)
                   (assoc :user user)))
        (insert-endpoint! endpoint ws)
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

; Forwards a message to the defined endpoints. Getting the endpoints is left as an exercise
; to the reader.
(defn- process-client-message
  "Process a message directed at a connected client"
  [host ws message-body]
  (doseq [websocket (parse-endpoints (:endpoints message-body) @endpoint-map)]
    (jetty-adapter/send! websocket (cheshire/generate-string message-body))))
 
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

