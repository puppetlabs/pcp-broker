(ns puppetlabs.pcp.broker.service
  (:require [puppetlabs.pcp.broker.core :as core]
            [puppetlabs.pcp.broker.in-memory-inventory :refer [make-inventory record-client find-clients]]
            [puppetlabs.structured-logging.core :as sl]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.i18n.core :as i18n]))

(trapperkeeper/defservice broker-service
  [[:AuthorizationService authorization-check]
   [:ConfigService get-in-config]
   [:WebroutingService add-websocket-handler get-server get-route]
   [:MetricsService get-metrics-registry]
   [:StatusService register-status]]
  (init [this context]
    (sl/maplog :info {:type :broker-init} (i18n/trs "Initializing broker service"))
    (let [activemq-spool     (get-in-config [:pcp-broker :broker-spool])
          accept-consumers   (get-in-config [:pcp-broker :accept-consumers] 4)
          delivery-consumers (get-in-config [:pcp-broker :delivery-consumers] 16)
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
                                         :get-route (partial get-route this)
                                         :ssl-cert ssl-cert})]
      (register-status "broker-service"
                       (status-core/get-artifact-version "puppetlabs" "pcp-broker")
                       1
                       (partial core/status broker))
      (assoc context :broker broker)))
  (start [this context]
    (sl/maplog :info {:type :broker-start} (i18n/trs "Starting broker service"))
    (let [broker (:broker (service-context this))]
      (core/start broker))
    (sl/maplog :debug {:type :broker-started} (i18n/trs "Broker service started"))
    context)
  (stop [this context]
    (sl/maplog :info {:type :broker-stop} (i18n/trs "Shutting down broker service"))
    (let [broker (:broker (service-context this))]
      (core/stop broker))
    (sl/maplog :debug {:type :broker-stopped} (i18n/trs "Broker service stopped"))
    context))
