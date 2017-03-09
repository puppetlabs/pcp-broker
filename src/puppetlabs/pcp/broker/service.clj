(ns puppetlabs.pcp.broker.service
  (:require [puppetlabs.pcp.broker.core :as core]
            [puppetlabs.structured-logging.core :as sl]
            [clj-time.core :as t]
            [clojure.string :as str]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context get-service]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [puppetlabs.i18n.core :as i18n]))

(defprotocol BrokerService)

(trapperkeeper/defservice broker-service
  BrokerService
  [[:AuthorizationService authorization-check]
   [:ConfigService get-in-config]
   [:WebroutingService add-websocket-handler get-server get-route]
   [:MetricsService get-metrics-registry]
   [:StatusService register-status]]
  (init [this context]
        (sl/maplog :info {:type :broker-init} (i18n/trs "Initializing broker service"))
        (let [broker             (core/init {:add-websocket-handler (partial add-websocket-handler this)
                                             :authorization-check   authorization-check
                                             :get-metrics-registry  get-metrics-registry
                                             :get-route             (partial get-route this)})]
          (register-status "broker-service"
                           (status-core/get-artifact-version "puppetlabs" "pcp-broker")
                           1
                           (partial core/status broker))
          (assoc context :broker broker)))
  (start [this context]
         (sl/maplog :info {:type :broker-start} (i18n/trs "Starting broker service"))
         (let [controller-uris (get-in-config [:pcp-broker :controller-uris] [])
               controller-disconnection-graceperiod (get-in-config [:pcp-broker
                                                                    :controller-disconnection-graceperiod]
                                                                   45000)
               controller-whitelist (set (get-in-config [:pcp-broker :controller-whitelist]
                                                        ["http://puppetlabs.com/inventory_request"]))
               broker (:broker context)
               server-context (some-> (get-service this :WebserverService)
                                      service-context
                                      (jetty9-core/get-server-context (keyword (get-server this :v1))))
               broker-name (or (:broker-name broker)
                               (core/get-webserver-cn server-context)
                               (core/get-localhost-hostname))
               ssl-context (-> server-context
                               :state
                               deref
                               :ssl-context-factory
                               .getSslContext)
               broker (assoc broker :broker-name broker-name)
               context (assoc context :broker broker)
               timestamp (t/now)
               controller-pcp-uris (map #(str/replace % #"wss" "pcp") controller-uris)]
           (swap! (:database broker) assoc :warning-bin
                  (zipmap controller-pcp-uris (repeat timestamp)))
           (reset! (:controllers broker)
                   (core/initiate-controller-connections broker
                                                         ssl-context
                                                         controller-uris
                                                         controller-whitelist
                                                         controller-disconnection-graceperiod))
           (core/start broker)
           (sl/maplog :debug {:type :broker-started :brokername broker-name}
                      (i18n/trs "Broker service <'{brokername}'> started"))
           context))
  (stop [this context]
        (sl/maplog :info {:type :broker-stop} (i18n/trs "Shutting down broker service"))
        (let [broker (:broker context)
              broker-name (:broker-name broker)]
          (core/stop broker)
          (sl/maplog :debug {:type :broker-stopped :brokername broker-name}
                     (i18n/trs "Broker service <'{brokername}'> stopped")))
        context))
