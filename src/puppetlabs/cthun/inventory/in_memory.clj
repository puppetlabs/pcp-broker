(ns puppetlabs.cthun.inventory.in-memory
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.broker-core :refer [explode-uri]]
            [puppetlabs.cthun.inventory :refer [InventoryService]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

(defn record-client
  [store endpoint]
  (swap! store conj endpoint))

(defn endpoint-pattern-match?
  "does an endpoint pattern match the subject value.  Here is where wildards happen"
  [pattern subject]
  (let [[pattern-client pattern-type] (explode-uri pattern)
        [subject-client subject-type] (explode-uri subject)]
    (and (some (partial = pattern-client) ["*" subject-client])
         (some (partial = pattern-type)   ["*" subject-type]))))

(defn find-clients
  [store patterns]
  (flatten (map (fn [pattern]
                  (filter (partial endpoint-pattern-match? pattern) @store))
                patterns)))

(defservice inventory-service
  "in-memory implementation of the inventory service"
  InventoryService
  []
  (init
   [this context]
   (log/info "Configuring in-memory InventoryService")
   (assoc context :store (atom #{})))

  (record-client
   [this endpoint]
   (let [store (:store (service-context this))]
     (record-client store endpoint)))

  (find-clients
   [this endpoints]
   (let [store (:store (service-context this))]
     (find-clients store endpoints))))
