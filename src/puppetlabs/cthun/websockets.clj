(ns puppetlabs.cthun.websockets
  (:import (org.eclipse.jetty.server
            Server ServerConnector ConnectionFactory HttpConnectionFactory
            Connector HttpConfiguration Request))
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
             [puppetlabs.trapperkeeper.services.webserver.jetty9-config :as jetty9-config]))

(def remote-cns (atom {}))

(defn- get-hostname*
  "Get the hostname or client certificate name from a websocket"
  [ws]
  (if (.. ws getSession getUpgradeRequest isSecure)
    (let [remoteaddr (.. ws getSession getRemoteAddress toString)
          cn         (get @remote-cns remoteaddr)]
      cn)
    (.getHostString (jetty-adapter/remote-addr ws))))

(def get-hostname (memoize get-hostname*))

; Websocket event handlers

(defn- on-connect!
  "OnConnect websocket event handler"
  [ws]
  (time! metrics/time-in-on-connect
         ((let [host (get-hostname ws)
                idle-timeout (* 1000 60 15)]
            (log/debug "Connection established from host:" host)
            (jetty-adapter/idle-timeout! ws idle-timeout)
            (cs/add-connection host ws))
          (inc! metrics/active-connections))))

(defn- on-text!
  "OnMessage (text) websocket event handler"
  [ws message]
  (let [timestamp (kitchensink/timestamp)]
    (inc! metrics/total-messages-in)
    (mark! metrics/rate-messages-in)
    (time! metrics/time-in-on-text
           (let [host (get-hostname ws)]
             (log/info "Received message from client" host)
             (if-let [message-body (message/decode message)]
               (if (validation/check-certname (:sender message-body) host)
                 (let [message-body (message/add-hop message-body "accepted" timestamp)]
                   (cs/process-message host ws message-body))
                 (do
                   (log/warn "Recieved message does not match certname.  Disconnecting websocket.")
                   (jetty-adapter/close! ws)))
               (log/warn "Received message does not match valid message schema. Dropping."))))))

(defn- on-bytes!
  "OnMessage (binary) websocket event handler"
  [ws bytes offset len]
  (let [timestamp (kitchensink/timestamp)]
    (inc! metrics/total-messages-in)
    (mark! metrics/rate-messages-in)
    (time! metrics/time-in-on-text
           (let [host (get-hostname ws)
                 message (String. bytes)]
             (log/info "Received message from client" host)
             (if-let [message-body (message/decode message)]
               (if (validation/check-certname (:sender message-body) host)
                 (let [message-body (message/add-hop message-body "accepted" timestamp)]
                   (cs/process-message host ws message-body))
                 (do
                   (log/warn "Recieved message does not match certname.  Disconnecting websocket.")
                   (jetty-adapter/close! ws)))
               (log/warn "Received message does not match valid message schema. Dropping."))))))

(defn- on-error
  "OnError websocket event handler"
  [ws e]
  (log/error e)
  (dec! metrics/active-connections))

(defn- on-close!
  "OnClose websocket event handler"
  [ws status-code reason]
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
       (let [remoteaddr      (.. request getRemoteInetSocketAddress toString)
             ssl-client-cert (first (.getAttribute request "javax.servlet.request.X509Certificate"))
             cn              (kitchensink/cn-for-cert ssl-client-cert)]
         (swap! remote-cns assoc remoteaddr cn)))))

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
  [{:as options
    :keys [customizers]}]
  (doto (#'jetty-adapter/http-config options)
    (.setCustomizers customizers)))

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
  function spelunking.
"
  [options]
  (fn [server]
    (let [https-configuration (https-config options)
          https-connector (doto (ServerConnector.
                                 ^Server server
                                 (make-ssl-context-factory options)
                                 (into-array ConnectionFactory [(HttpConnectionFactory. https-configuration)]))
                            (.setPort (:ssl-port options))
                            (.setHost (:host options)))
          http-connector (first (.getConnectors server))
          connectors (into-array [http-connector https-connector])]
      (.setConnectors server connectors))
    server))

(defn- maybe-ssl-config
  "Takes a config map.  Returns a map with ssl-related options if ssl-port was set in the config"
  [config]
  (if-let [ssl-port (:ssl-port config)]
    (let [config (assoc config :client-auth :need)
          config (assoc config :customizers (make-ssl-customizers))
          config (merge config (jetty9-config/pem-ssl-config->keystore-ssl-config
                                (select-keys config [:ssl-key :ssl-cert :ssl-ca-cert])))
          config (assoc config :configurator (make-jetty9-configurator config))]
      config)
    {}))

(defn start-metrics
  [app]
  (jetty-adapter/run-jetty app {:port 3000
                                :join? false}))

(defn start-jetty
  [app prefix host port config]
  (jetty-adapter/run-jetty app (merge
                                {:websockets {prefix (websocket-handlers)}
                                 :port port
                                 :host host}
                                (maybe-ssl-config config))))
