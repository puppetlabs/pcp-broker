(ns puppetlabs.cthun.websockets
  (:require  [clojure.tools.logging :as log]
             [ring.adapter.jetty9 :as jetty-adapter]))

; TODO(ploubser): This doesn't feel very idiomatic.
(def websocket-state (atom{}))

(defn- on-connect
  "OnConnect websocket event handler"
  [ws]
  (let [host (.getHostString (jetty-adapter/remote-addr ws))]
    (log/info "Connection established from host:" host)
    (swap! websocket-state conj {host  "active"})
    (jetty-adapter/send! ws "connect ack")))

; TODO(ploubser): Message validation
;                 Action on valid message
(defn- on-text
  "OnMessage (text) websocket event handler"
  [ws message]
  (log/info "Received message from client")
  (log/info message)
  (jetty-adapter/send! ws "message ack"))

(defn- on-bytes
  "OnMessage (binary) websocket event handler"
  [ws bytes offset len]
  (log/error "Binary transmission not supported yet. Send me a text message"))

(defn- on-error
  "OnError websocket event handler"
  [ws e]
  (log/error e))

(defn- on-close
  "OnClose websocket event handler"
  [ws status-code reason]
  (log/info "Connection terminated with statuscode: " status-code ". Reason: " reason))

(defn websocket-handlers
  "Return a map of websocket event handler functions"
  []
  {:on-connect on-connect
   :on-error on-error
   :on-close on-close
   :on-text on-text
   :on-bytes on-bytes})
  

(defn start-jetty
  [app prefix host port]
  (jetty-adapter/run-jetty app {:websockets {prefix (websocket-handlers)}
                                :port port
                                :host host}))
