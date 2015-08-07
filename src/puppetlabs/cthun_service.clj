(ns puppetlabs.cthun-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun-core :as core]
            [puppetlabs.cthun.websockets :as websockets]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

; TODO(ploubser): Define a protocol
(defprotocol CthunService
;  (start [this get-in-config-fn])
  (state [this caller]))

(trapperkeeper/defservice cthun-service
  CthunService
  [[:ConfigService get-in-config]
   [:WebserverService add-ring-handler add-websocket-handler]
   InventoryService]
  (init [this context]
        (log/info "Initializing cthun service")
        (let [cthun-path (get-in-config [:cthun :url-prefix] "/cthun")
              service (core/make-cthun-broker get-in-config InventoryService)]
          (add-ring-handler core/app "/")
          (add-websocket-handler (websockets/websocket-handlers) cthun-path)
          (assoc context :cthun service)))
  (start [this context]
         (log/info "Starting cthun service")
         (let [cthun (:cthun (service-context this))]
           (core/start cthun))
         context)
  (stop [this context]
        (log/info "Shutting down cthun service")
        (let [cthun (:cthun (service-context this))]
          (core/stop cthun))
        context)
  (state [this caller]
         (core/state caller)))
