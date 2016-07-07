(ns puppetlabs.pcp.broker.in-memory-inventory
  (:require [clojure.tools.logging :as log]
            [puppetlabs.pcp.protocol :refer [explode-uri uri-wildcard?]]
            [schema.core :as s]))

(defn endpoint-pattern-match?
  "Does an endpoint pattern match the subject value. Here is where wildcards happen"
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
  (let [explicit (set (filter (complement uri-wildcard?) patterns))
        wildcard (set (filter uri-wildcard? patterns))
        explicit-matched (clojure.set/intersection explicit @inventory)
        wildcard-matched (flatten (map #(filter (partial endpoint-pattern-match? %) @inventory) wildcard))]
    (sort (clojure.set/union explicit-matched (set wildcard-matched)))))

(defn record-client
  [inventory endpoint]
  (swap! inventory conj endpoint))
