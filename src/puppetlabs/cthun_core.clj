(ns puppetlabs.cthun-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.websockets :as websockets]
            [puppetlabs.cthun.connection-states :as cs]
            [puppetlabs.cthun.metrics :as metrics]
            [puppetlabs.puppetdb.mq :as mq]
            [schema.core :as s]))

(defn app
  [conf]
  (log/info "Metrics App initiated")
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (metrics/get-metrics-string)})

(defn make-cthun-broker
  [get-in-config inventory]
  (let [url-prefix (get-in-config [:cthun :url-prefix])
        activemq-spool (get-in-config [:cthun :broker-spool] "tmp/activemq")
        activemq-broker (mq/build-embedded-broker activemq-spool)
        accept-consumers   (get-in-config [:cthun :accept-consumers] 4)
        delivery-consumers (get-in-config [:cthun :delivery-consumers] 16)]
    (cs/use-this-inventory inventory)
    (cs/subscribe-to-queues accept-consumers delivery-consumers)
    {:activemq-broker activemq-broker}))

(defn start
  [broker]
  (let [{:keys [activemq-broker]} broker]
    (mq/start-broker! activemq-broker)))

(defn stop
  [{:keys [cthun] :as service}]
  (let [{:keys [activemq-broker]} cthun]
    (mq/stop-broker! activemq-broker)))

(defn state
  "Return the service state"
  [caller]
  (log/info "Not yet implemented"))
