(ns puppetlabs.pcp.broker.in-memory-inventory
  (:require [clojure.tools.logging :as log]
            [puppetlabs.pcp.protocol :refer [explode-uri]]
            [schema.core :as s]))

(defn endpoint-pattern-match?
  "does an endpoint pattern match the subject value.  Here is where wildards happen"
  [pattern subject]
  (let [[pattern-client pattern-type] (explode-uri pattern)
        [subject-client subject-type] (explode-uri subject)]
    (and (some (partial = pattern-client) ["*" subject-client])
         (some (partial = pattern-type)   ["*" subject-type]))))

(defn make-inventory
  "Make an inventory state"
  []
  (atom #{}))

(defn find-clients
  [inventory patterns]
  (flatten (map (fn [pattern]
                  (filter (partial endpoint-pattern-match? pattern) @inventory))
                patterns)))

(defn record-client
  [inventory endpoint]
  (swap! inventory conj endpoint))
