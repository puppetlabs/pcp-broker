(ns puppetlabs.cthun.websockets
  (:import (org.eclipse.jetty.server
            Server ServerConnector ConnectionFactory HttpConnectionFactory
            Connector HttpConfiguration Request)
           (org.eclipse.jetty.websocket.api
            WebSocketAdapter))
  (:require  [clojure.tools.logging :as log]
             [ring.adapter.jetty9 :as jetty-adapter]
             [puppetlabs.cthun.validation :as validation]
             [puppetlabs.cthun.message :as message]
             [puppetlabs.cthun.connection-states :as cs]
             [puppetlabs.kitchensink.core :as kitchensink]
             [metrics.counters :refer [inc! dec!]]
             [metrics.meters :refer [mark!]]
             [metrics.timers :refer [time!]]
             [puppetlabs.cthun.metrics :as metrics]
             [puppetlabs.trapperkeeper.services.webserver.jetty9-config :as jetty9-config]
             [schema.core :as s]
             [slingshot.slingshot :refer [try+]]))

(def remote-cns (atom {}))

(defn- get-hostname*
  "Get the hostname or client certificate name from a websocket"
  [^WebSocketAdapter ws]
  (if (.. ws getSession getUpgradeRequest isSecure)
    (let [remoteaddr (.. ws getSession getRemoteAddress toString)
          cn         (get @remote-cns remoteaddr)]
      cn)
    (.getHostString (jetty-adapter/remote-addr ws))))

(def get-hostname (memoize get-hostname*))

; Websocket event handlers

(defn- on-connect!
  "OnConnect websocket event handler"
  [^WebSocketAdapter ws]
  (time! metrics/time-in-on-connect
         ((let [host (get-hostname ws)
                idle-timeout (* 1000 60 15)]
            (log/debug "Connection established from host:" host)
            (jetty-adapter/idle-timeout! ws idle-timeout)
            (cs/add-connection host ws))
          (inc! metrics/active-connections))))

(defn on-message!
  [^WebSocketAdapter ws bytes]
  (let [timestamp (kitchensink/timestamp)]
    (inc! metrics/total-messages-in)
    (mark! metrics/rate-messages-in)
    (time! metrics/time-in-on-text
           (let [host (get-hostname ws)]
             (log/info "Received message from client" host)
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
                                               :expires ""
                                               :data_schema "http://puppetlabs.com/error_message"
                                               :sender "cth://server")
                                        (message/set-json-data error-body))]
                  (s/validate validation/ErrorMessage error-body)
                  (log/warn "sending error message" error-body)
                  (jetty-adapter/send! ws (message/encode error-message))))
              (catch Throwable e
                (log/error "Unhandled exception" e)))))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [^WebSocketAdapter ws message]
  (on-message! ws (message/string->bytes message)))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [^WebSocketAdapter ws bytes offset len]
  (on-message! ws bytes))

(defn- on-error
  "OnError websocket event handler"
  [^WebSocketAdapter ws e]
  (log/error e)
  (dec! metrics/active-connections))

(defn- on-close!
  "OnClose websocket event handler"
  [^WebSocketAdapter ws status-code reason]
  (let [hostname (get-hostname ws)]
    (log/info "Connection from" hostname "terminated with statuscode:" status-code " Reason:" reason)
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

(defn- make-cthun-customizer
  "Returns a customizer that updates the remote-cns map of Remote
  Address to certificate common name.  Needs to be after a
  org.eclipse.jetty.server.SecureRequestCustomizer which populates the
  javax.servlet.request.X509Certificate attribute"
  []
  (reify org.eclipse.jetty.server.HttpConfiguration$Customizer
    (^void customize [this ^Connector connector ^HttpConfiguration config ^Request request]
      (if-let [ssl-client-cert (first (.getAttribute request "javax.servlet.request.X509Certificate"))]
        (let [remoteaddr (.. request getRemoteInetSocketAddress toString)]
          (swap! remote-cns assoc remoteaddr (kitchensink/cn-for-cert ssl-client-cert)))))))

(defn- make-ssl-customizers
  "Returns the customizers we want to apply to the ssl Configurator"
  []
  [(org.eclipse.jetty.server.SecureRequestCustomizer.)
   (make-cthun-customizer)])

;; TODO(richardc) A lot of this code is here because
;; ring.adapter.jetty9 doesn't give us a way of specifying https only,
;; or the ability to call .setCustomizers on the HttpConfiguration
;; object.  We rudely reach into it and call private functions to get
;; the HttpConfiguration to set the customizers on.
;; We should propose a saner way of extending this.
(defn- https-config
  "Returns a jetty.server.HttpConfiguration with the
  desired Customizers set"
  [options]
  (doto (#'jetty-adapter/http-config options)
    (.setCustomizers (make-ssl-customizers))))

(defn- make-ssl-context-factory
  [options]
  "Returns a jetty.util.SslContextFactory.  This is the one from
  ring.adapter.jetty9 with options set to use the crl from
  ssl-crl-path, and to verify the peer"
  (let [factory (#'jetty-adapter/ssl-context-factory options)]
    (.setCrlPath factory (:ssl-crl-path options))
    (.setValidatePeerCerts factory true)
    factory))

(defn- make-jetty9-configurator
  "Returns a configurator function that is called with
  jetty.server.Server before it's started.  We take this as a way of
  completely replacing the connectors with an ssl connector with the
  customizers we need.  This is heavy and involves more private
  function spelunking."
  [options]
  (fn [server]
    (let [https-configuration (https-config options)
          https-connector (doto (ServerConnector.
                                 ^Server server
                                 (make-ssl-context-factory options)
                                 (into-array ConnectionFactory [(HttpConnectionFactory. https-configuration)]))
                            (.setPort (:ssl-port options))
                            (.setHost (:host options)))
          connectors (into-array [https-connector])]
      (.setConnectors server connectors))
    server))

(s/defn ^:always-validate start-jetty :- Server
  [app prefix host port config]
  (let [jetty-config {:websockets {prefix (websocket-handlers)}
                      :ssl-port port
                      :host host
                      :client-auth :want
                      :ssl-crl-path (:ssl-crl-path config)
                      :join? false}
        jetty-config (merge jetty-config (jetty9-config/pem-ssl-config->keystore-ssl-config
                                          (select-keys config [:ssl-key :ssl-cert :ssl-ca-cert])))
        jetty-config (assoc jetty-config :configurator (make-jetty9-configurator jetty-config))]
    (jetty-adapter/run-jetty app jetty-config)))
