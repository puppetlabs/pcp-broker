(ns puppetlabs.cthun-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun-core :as core]
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
   InventoryService]
  (init [this context]
        (log/info "Initializing cthun service")
        (let [service (core/make-cthun-broker get-in-config InventoryService)]
          (assoc context :cthun service)))
  (start [this context]
         (log/info "Starting cthun service")
    (let [service (:cthun (service-context this))
          ;; TODO: these Jetty lines will go away if we switch to tk-j9
          jetty-server (core/start service)]
         (assoc context :jetty-server jetty-server)))
  (stop [this context]
        (log/info "Shutting down cthun service")
        (let [service (select-keys (service-context this) [:cthun :jetty-server])]
          (core/stop service))
        context)
  (state [this caller]
         (core/state caller)))
