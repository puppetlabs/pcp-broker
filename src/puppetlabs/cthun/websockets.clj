(ns puppetlabs.cthun.websockets
  (:require  [clojure.tools.logging :as log]
             [ring.adapter.jetty9 :as jetty-adapter]
             [cheshire.core :as cheshire]
             [puppetlabs.cthun.validation :as validation]
             [puppetlabs.cthun.connection-states :as cs]))

(defn- get-hostname*
  "Get the hostname from a websocket"
  [ws]
  (.getHostString (jetty-adapter/remote-addr ws)))

(def get-hostname (memoize get-hostname*))

; Websocket event handlers

(defn- on-connect!
  "OnConnect websocket event handler"
  [ws]
  (let [host (get-hostname ws)
        idle-timeout (* 1000 60 15)]
    (log/debug "Connection established from host:" host)
    (jetty-adapter/idle-timeout! ws idle-timeout)
    (cs/add-connection host ws)))

; TODO(ploubser): Action on valid message
; Forward non server messages to intended destination
(defn- on-text!
  "OnMessage (text) websocket event handler"
  [ws message]
  (log/info "Received message from client")
  (log/info "Validating Message...")
  (if-let [message-body (validation/validate-message message)]
    (cs/process-message (get-hostname ws) ws message-body)
    (log/warn "Received message does not match valid message schema. Dropping.")))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [ws bytes offset len]
  (log/error "Binary transmission not supported yet. Send me a text message"))

(defn- on-error
  "OnError websocket event handler"
  [ws e]
  (log/error e))

(defn- on-close!
  "OnClose websocket event handler"
  [ws status-code reason]
  (log/info "Connection terminated with statuscode: " status-code ". Reason: " reason)
  (log/debug "Removing connection from connection-map")
  (cs/remove-connection (get-hostname ws) ws))

; Public Interface

(defn websocket-handlers
  "Return a map of websocket event handler functions"
  []
  {:on-connect on-connect!
   :on-error on-error
   :on-close on-close!
   :on-text on-text!
   :on-bytes on-bytes!})

(defn start-jetty
  [app prefix host port]
  (jetty-adapter/run-jetty app {:websockets {prefix (websocket-handlers)}
                                :port port
                                :host host}))
