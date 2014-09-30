(ns puppetlabs.cthun.meshing.in-memory
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.connection-states :as cs]
            [puppetlabs.cthun.meshing :refer [MeshingService]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

(defn make-service
  [context]
  (assoc context :delivery-fn (atom (fn [message] (log/warn "delivery-fn not specified")))))

(defn deliver-to-client
  [this client message]
  (let [{:keys [delivery-fn]} (service-context this)]
    (@delivery-fn message)))

(defn register-local-delivery
  [this new-fn]
  (let [{:keys [delivery-fn]} (service-context this)]
    (reset! delivery-fn new-fn)))

(defservice meshing-service
  "In-memory (single process) implementation of the meshing service"
  MeshingService
  []
  (init [this context] (make-service context))
  ;; record and forget are no-ops for this
  (record-client-location [this client])
  (forget-client-location [this client])
  (deliver-to-client [this client message] (deliver-to-client this client message))
  (register-local-delivery [this delivery-fn] (register-local-delivery this delivery-fn)))
