(ns puppetlabs.cthun.broker.service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.broker.core :as core]
            [puppetlabs.cthun.broker.basic-authorization :as authz]
            [puppetlabs.cthun.broker.in-memory-inventory :refer [make-inventory record-client find-clients]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

(trapperkeeper/defservice broker-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler add-websocket-handler]
   [:MetricsService get-metrics-registry]]
  (init [this context]
        (log/info "Initializing broker service")
        (let [activemq-spool     (get-in-config [:cthun :broker-spool])
              accept-consumers   (get-in-config [:cthun :accept-consumers] 4)
              delivery-consumers (get-in-config [:cthun :delivery-consumers] 16)
              authorization      (get-in-config [:cthun :authorization] {:accept {:default :allow}})
              inventory          (make-inventory)
              broker             (core/init {:activemq-spool activemq-spool
                                             :accept-consumers accept-consumers
                                             :delivery-consumers delivery-consumers
                                             :add-ring-handler (partial add-ring-handler this)
                                             :add-websocket-handler (partial add-websocket-handler this)
                                             :record-client  (partial record-client inventory)
                                             :find-clients   (partial find-clients inventory)
                                             :authorized (partial authz/authorized authorization)
                                             :get-metrics-registry get-metrics-registry})]
          (assoc context :broker broker)))
  (start [this context]
         (log/info "Starting broker service")
         (let [broker (:broker (service-context this))]
           (core/start broker))
         context)
  (stop [this context]
        (log/info "Shutting down broker service")
        (let [broker (:broker (service-context this))]
          (core/stop broker))
        context))
