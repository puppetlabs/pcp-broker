(ns puppetlabs.cthun.websockets
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.cthun.connection-states :as cs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [metrics.counters :refer [inc! dec!]]
            [metrics.meters :refer [mark!]]
            [metrics.timers :refer [time!]]
            [puppetlabs.cthun.metrics :as metrics]
            [puppetlabs.experimental.websockets.client :as websocket-client]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+]]))

(defn get-cn
  "Get the hostname client certificate name from a websocket"
  [ws]
  (when-let [cert (first (websocket-client/peer-certs ws))]
    (kitchensink/cn-for-cert cert)))

; Websocket event handlers

(defn- on-connect!
  "OnConnect websocket event handler"
  [ws]
  (time! metrics/time-in-on-connect
         ((let [host (get-cn ws)
                idle-timeout (* 1000 60 15)]
            (log/info "Connection established from host:" host " on " ws)
            (websocket-client/idle-timeout! ws idle-timeout)
            (cs/add-connection host ws))
          (inc! metrics/active-connections))))

(defn on-message!
  [ws bytes]
  (let [timestamp (kitchensink/timestamp)]
    (inc! metrics/total-messages-in)
    (mark! metrics/rate-messages-in)
    (time! metrics/time-in-on-text
           (let [host (get-cn ws)]
             (log/info "Received message from client" host " on " ws)
             (try+
              (let [message (message/decode bytes)]
                (validation/validate-certname (:sender message) host)
                (let [message (message/add-hop message "accepted" timestamp)]
                  (log/info "Processing message")
                  (cs/process-message host ws message)))
              (catch map? m
                (let [error-body {:description (str "Error " (:type m) " handling message: " (:message &throw-context))}
                      error-message (-> (message/make-message)
                                        (assoc :id (kitchensink/uuid)
                                               :message_type "http://puppetlabs.com/error_message"
                                               :sender "cth:///server")
                                        (message/set-json-data error-body))]
                  (s/validate validation/ErrorMessage error-body)
                  (log/warn "sending error message" error-body)
                  (websocket-client/send! ws (message/encode error-message))))
              (catch Throwable e
                (log/error "Unhandled exception" e)))))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [ws message]
  (on-message! ws (message/string->bytes message)))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [ws bytes offset len]
  (on-message! ws bytes))

(defn- on-error
  "OnError websocket event handler"
  [ws e]
  (log/error e)
  (dec! metrics/active-connections))

(defn- on-close!
  "OnClose websocket event handler"
  [ws status-code reason]
  (let [hostname (get-cn ws)]
    (log/info "Connection from" hostname " on " ws " terminated with statuscode:" status-code " Reason:" reason)
    (dec! metrics/active-connections)
    (time! metrics/time-in-on-close
           (cs/remove-connection hostname ws))))

; Public Interface

(defn websocket-handlers
  "Return a map of websocket event handler functions"
  []
  {:on-connect on-connect!
   :on-error on-error
   :on-close on-close!
   :on-text on-text!
   :on-bytes on-bytes!})
