(ns puppetlabs.pcp.broker.service
  (:require [puppetlabs.pcp.broker.core :as core]
            [puppetlabs.pcp.broker.in-memory-inventory :refer [make-inventory record-client find-clients]]
            [puppetlabs.structured-logging.core :as sl]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

(trapperkeeper/defservice broker-service
  [[:AuthorizationService authorization-check]
   [:ConfigService get-in-config]
   [:WebroutingService add-websocket-handler get-server]
   [:MetricsService get-metrics-registry]
   [:StatusService register-status]]
  (init [this context]
    (sl/maplog :info {:type :broker-init} "Initializing broker service")
    (let [activemq-spool     (get-in-config [:pcp-broker :broker-spool])
          accept-consumers   (get-in-config [:pcp-broker :accept-consumers] 4)
          delivery-consumers (get-in-config [:pcp-broker :delivery-consumers] 16)
          protocol-next      (get-in-config [:pcp-broker :protocol-next] false)
          inventory          (make-inventory)
          ssl-cert           (if-let [server (get-server this :v1)]
                               (get-in-config [:webserver (keyword server) :ssl-cert])
                               (get-in-config [:webserver :ssl-cert]))
          broker             (core/init {:activemq-spool activemq-spool
                                         :accept-consumers accept-consumers
                                         :delivery-consumers delivery-consumers
                                         :add-websocket-handler (partial add-websocket-handler this)
                                         :record-client  (partial record-client inventory)
                                         :find-clients   (partial find-clients inventory)
                                         :authorization-check authorization-check
                                         :get-metrics-registry get-metrics-registry
                                         :protocol-next protocol-next
                                         :ssl-cert ssl-cert})]
      (register-status "broker-service"
                       (status-core/get-artifact-version "puppetlabs" "pcp-broker")
                       1
                       (partial core/status broker))
      (assoc context :broker broker)))
  (start [this context]
    (sl/maplog :info {:type :broker-start} "Starting broker service")
    (let [broker (:broker (service-context this))]
      (core/start broker))
    (sl/maplog :debug {:type :broker-started} "Broker service started")
    context)
  (stop [this context]
    (sl/maplog :info {:type :broker-stop} "Shutting down broker service")
    (let [broker (:broker (service-context this))]
      (core/stop broker))
    (sl/maplog :debug {:type :broker-stopped} "Broker service stopped")
    context))
