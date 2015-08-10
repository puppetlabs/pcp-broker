(ns puppetlabs.cthun.broker-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.broker-core :as core]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

(trapperkeeper/defservice broker-service
  [[:ConfigService get-in-config]
   [:WebserverService add-ring-handler add-websocket-handler]
   [:InventoryService record-client find-clients]
   [:AuthorizationService authorized]]
  (init [this context]
        (log/info "Initializing broker service")
        (let [path               (get-in-config [:cthun :url-prefix] "/cthun")
              activemq-spool     (get-in-config [:cthun :broker-spool])
              accept-consumers   (get-in-config [:cthun :accept-consumers] 4)
              delivery-consumers (get-in-config [:cthun :delivery-consumers] 16)
              broker             (core/init {:path path
                                             :activemq-spool activemq-spool
                                             :accept-consumers accept-consumers
                                             :delivery-consumers delivery-consumers
                                             :add-ring-handler add-ring-handler
                                             :add-websocket-handler add-websocket-handler
                                             :record-client record-client
                                             :find-clients find-clients
                                             :authorized authorized})]
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
