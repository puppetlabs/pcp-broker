(ns puppetlabs.cthun.connection-states
  (:require [clojure.core.async :as async]
            [clojure.core.incubator :refer [dissoc-in]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [schema.core :as s]
            [ring.adapter.jetty9 :as jetty-adapter]))

(def ConnectionMap
  "The state of a websocket in the connection-map"
  {:socket-type s/Str
   :status s/Str
   :user s/Str
   (s/optional-key :endpoint) validation/Endpoint
   :created-at validation/ISO8601})

(def connection-map (atom {})) ;; Nested map of host -> websocket -> ConnectionState
(def endpoint-map (atom {})) ;; Nested map of host -> type -> id -> websocket

(defn- make-endpoint-string
  "Make a new endpoint string for a host and type"
  [host type]
  (str "cth://" host "/" type "/" (str (java.util.UUID/randomUUID))))

(s/defn ^:always-validate
  explode-endpoint :- [s/Str]
  "Parse an endpoint string into its component parts.  Raises if incomplete"
  [endpoint :- validation/Endpoint]
  (let [points (str/split (subs endpoint 6) #"/")]
    (when-not (= (count points) 3)
      (throw (Exception. (str "Endpoints should have 3 parts"))))
    points))

(defn- find-websockets
  "Find all websockets matching an endpoint array"
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
                          (let [points     (explode-endpoint endpoint)
                                websockets (find-websockets points e-map)]
                            (when-not websockets
                              (log/info "No endpoints registered matching: " endpoint " - Discarding message"))
                            websockets))
                        endpoints))))

(defn- insert-endpoint!
  "Record the websocket for an endpoint into the endpoint map.
  Returns new version of the endpoint map.  Raises if endpoint already
  exists"
  [endpoint ws]
  (let [points (explode-endpoint endpoint)
        host (get points 0)
        type (get points 1)
        uid  (get points 2)]
    (swap! endpoint-map (fn [map]
                          (if-let [existing (get-in map [host type uid])]
                            (throw (Exception. (str "Endpoint already exists: " endpoint)))
                            (assoc-in map [host type uid] ws))))))

(s/defn ^:always-validate
  new-socket :- ConnectionMap
  "Return a new, unconfigured connection map"
  []
  {:socket-type "undefined"
   :status "connected"
   :user "undefined"
   :created-at (ks/timestamp)})

 (defn- logged-in?
  "Determine if host/websocket combination has logged in"
  [host ws]
  (= (get-in @connection-map [host ws :status]) "ready"))

(defn- process-login-message
  "Process a login message from a client"
  [host ws message-body]
  (log/info "Processing login message")
  (when (validation/validate-login-data (:data message-body))
    (log/info "Valid login message received")
    (if (logged-in? host ws)
      (throw (Exception. (str "Received login attempt from host '" host "' on socket '"
                         ws "' but already logged in at "
                         (get-in @connection-map [host ws :created-at])
                         " as "
                         (get-in @connection-map [host ws :user])
                         ". Ignoring")))
      (let [data (:data message-body)
            type (:type data)
            user (:user data)
            endpoint (make-endpoint-string host type)]
        (swap! connection-map update-in [host ws] merge {:socket-type type
                                                         :status "ready"
                                                         :endpoint endpoint
                                                         :user user})
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

; Forwards a message to the defined endpoints.
(defn- process-client-message
  "Process a message directed at a connected client(s)"
  [host ws message-body]
  (doseq [websocket (parse-endpoints (:endpoints message-body) @endpoint-map)]
    (try
      (let [sender (get-in @connection-map [host ws :endpoint])
            modified-body (assoc message-body :sender sender)]
        (jetty-adapter/send! websocket (cheshire/generate-string modified-body)))
      (catch Exception e (log/warn (str "Exception raised while trying to process a client message: "
                              (.getMessage e)
                              ". Dropping message"))))))

(def queue (async/chan))

(defn- enqueue-client-message
  "Take a client message, queue it for later dispatch"
  [host ws message]
  (log/info "enqueuing message")
  (async/>!! queue {:host host :ws ws :message message}))

(defn- deliver-from-queue
  "Consume a client message from there queue, ship it out"
  []
  (let [envelope (async/<!! queue)
        host     (:host envelope)
        ws       (:ws envelope)
        message  (:message envelope)]
    (log/info "dequeued message")
    (process-client-message host ws message)))

(defn run-the-queue
  "loop around deliver-from-queue"
  []
  (async/thread (while true (deliver-from-queue))))

(defn- login-message?
  "Return true if message is a login type message"
  [message]
  (and (= (first (:endpoints message)) "cth://server")
       (= (:data_schema message) "http://puppetlabs.com/loginschema")))

(defn add-connection
  "Add a connection to the connection state map"
  [host ws]
  (swap! connection-map assoc-in [host ws] (new-socket)))

(defn remove-connection
  "Remove a connection from the connection state map"
  [host ws]
  (when-let [endpoint (get-in @connection-map [host ws :endpoint])]
    (swap! endpoint-map dissoc-in (explode-endpoint endpoint)))
  (swap! connection-map dissoc-in [host ws]))

(defn process-message
  "Process an incoming message from a websocket"
  [host ws message-body]
  (log/info "processing incoming message")
  ; Check if socket has been logged into
  (if (logged-in? host ws)
  ; check if this is a message directed at the middleware
    (if (= (get (:endpoints message-body) 0) "cth://server")
      (process-server-message host ws message-body)
      (enqueue-client-message host ws message-body))
    (if (login-message? message-body)
      (process-server-message host ws message-body)
      (log/warn "Connection cannot accept messages until login message has been "
                 "processed. Dropping message."))))
