(ns puppetlabs.cthun-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.websockets :as websockets]
            [puppetlabs.cthun.connection-states :as cs]
            [puppetlabs.cthun.metrics :as metrics]
            [puppetlabs.puppetdb.mq :as mq]
            [compojure.core :as compojure]
            [cheshire.core :as cheshire]
            [compojure.route :as route]))

(defn- websocket-app
  [conf]
  (log/info "Websocket App starting"))

(defn- metrics-app
  [conf]
  (log/info "Metrics App initiated")
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (metrics/get-metrics-string)})

(defn make-cthun-broker
  [get-in-config inventory]
  (let [config (get-in-config [:cthun])
        host (get-in-config [:cthun :host])
        port (get-in-config [:cthun :port])
        url-prefix (get-in-config [:cthun :url-prefix])
        activemq-spool (get-in-config [:cthun :broker-spool] "tmp/activemq")
        activemq-broker (mq/build-embedded-broker activemq-spool)
        accept-threads (get-in-config [:cthun :accept-consumers] 4)
        redeliver-threads (get-in-config [:cthun :redeliver-consumers] 2)]
    (cs/use-this-inventory inventory)
    (cs/subscribe-to-topics accept-threads redeliver-threads)
    (metrics/enable-cthun-metrics)
    {:host host
     :port port
     :url-prefix url-prefix
     :config config
     :activemq-broker activemq-broker}))

(defn start
  [broker]
  (let [{:keys [host port url-prefix config activemq-broker]} broker]
    (mq/start-broker! activemq-broker)
    (websockets/start-metrics metrics-app)
    (websockets/start-jetty websocket-app url-prefix host port config)))

(defn stop
  [broker]
  (let [{:keys [activemq-broker]} broker]
    (mq/stop-broker! activemq-broker)))

(defn state
  "Return the service state"
  [caller]
  (log/info "Not yet implemented"))
