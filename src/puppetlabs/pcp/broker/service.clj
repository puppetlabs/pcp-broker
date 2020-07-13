(ns puppetlabs.pcp.broker.service
  (:require [puppetlabs.pcp.broker.core :as core]
            [puppetlabs.structured-logging.core :as sl]
            [clj-time.core :as t]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context get-service maybe-get-service]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [puppetlabs.trapperkeeper.services.protocols.filesystem-watch-service :as watch-protocol]
            [puppetlabs.i18n.core :as i18n]))

(defprotocol BrokerService)

(trapperkeeper/defservice broker-service
  BrokerService
  {:required [[:AuthorizationService authorization-check]
              [:ConfigService get-in-config]
              [:WebroutingService add-websocket-handler get-server get-route]
              [:MetricsService get-metrics-registry]
              [:StatusService register-status]]
   :optional [FileSyncStorageService]}
  (init [this context]
        (sl/maplog :info {:type :broker-init} (fn [_] (i18n/trs "Initializing broker service.")))
        (let [max-connections    (get-in-config [:pcp-broker :max-connections] 0)
              max-message-size   (get-in-config [:pcp-broker :max-message-size] (* 64 1024 1024))
              idle-timeout       (get-in-config [:pcp-broker :idle-timeout] (* 1000 60 6))
              broker             (core/init {:add-websocket-handler (partial add-websocket-handler this)
                                             :authorization-check   authorization-check
                                             :get-metrics-registry  get-metrics-registry
                                             :max-connections       max-connections
                                             :max-message-size      max-message-size
                                             :idle-timeout          idle-timeout
                                             :get-route             (partial get-route this)})]
          (register-status "broker-service"
                           (status-core/get-artifact-version "puppetlabs" "pcp-broker")
                           1
                           (partial core/status broker))
          (assoc context :broker broker)))
  (start [this context]
         (sl/maplog :info {:type :broker-start} (fn [_] (i18n/trs "Starting broker service.")))
         (let [controller-uris (get-in-config [:pcp-broker :controller-uris] [])
               controller-disconnection-ms (-> (get-in-config [:pcp-broker
                                                               :controller-disconnection-graceperiod]
                                                              "90s")
                                               ks/parse-interval
                                               t/in-millis)
               controller-whitelist (set (get-in-config [:pcp-broker :controller-whitelist]
                                                        ["http://puppetlabs.com/inventory_request"]))
               broker (:broker context)
               server-context (some-> (get-service this :WebserverService)
                                      service-context
                                      (jetty9-core/get-server-context (keyword (get-server this :v1))))
               broker-name (or (:broker-name broker)
                               (core/get-webserver-cn server-context)
                               (core/get-localhost-hostname))
               ssl-context-factory (-> server-context
                                       :state
                                       deref
                                       :ssl-context-factory)

               ssl-context (.getSslContext ssl-context-factory)
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
                                                         controller-disconnection-ms))
           (core/start broker)
           (if-let [filesystem-watcher-service (maybe-get-service this :FilesystemWatchService)]
             (let [watcher (watch-protocol/create-watcher filesystem-watcher-service {:recursive false})]
               (core/watch-crl watcher broker ssl-context-factory)))
           (sl/maplog :info {:type :broker-started :brokername broker-name}
                      ;; 0 : broker name
                      #(i18n/trs "Started broker service <{0}>." (:brokername %)))
           context))
  (stop [this context]
        (sl/maplog :info {:type :broker-stop} (fn [_] (i18n/trs "Shutting down broker service.")))
        (let [broker (:broker context)
              broker-name (:broker-name broker)]
          (core/stop broker)
          (sl/maplog :info {:type :broker-stopped :brokername broker-name}
                     ;; 0 : broker name
                     #(i18n/trs "Stopped broker service <{0}>." (:brokername %))))
        context))
