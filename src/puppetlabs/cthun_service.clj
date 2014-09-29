(ns puppetlabs.cthun-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun-core :as core]
            [puppetlabs.cthun.meshing :refer [MeshingService]]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]))

; TODO(ploubser): Define a protocol
(defprotocol CthunService
;  (start [this get-in-config-fn])
  (state [this caller]))

(trapperkeeper/defservice cthun-service
  CthunService
  [[:ConfigService get-in-config]
   MeshingService
   QueueingService]
  (init [this context]
        (log/info "Initializing cthun service")
        context)
  (start [this context]
         (log/info "Starting cthun service")
         (core/start get-in-config MeshingService QueueingService)
         context)
  (stop [this context]
        (log/info "Shutting down cthun service")
        context)
  (state [this caller]
         (core/state caller)))
