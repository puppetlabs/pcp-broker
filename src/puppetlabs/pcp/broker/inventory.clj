(ns puppetlabs.pcp.broker.inventory
  (:require [puppetlabs.pcp.protocol :as p]
            [clojure.set :refer [intersection union]]))

(defn endpoint-pattern-match?
  "Does an endpoint pattern match the subject value. Here is where wildcards happen"
  [pattern subject]
  (let [[pattern-client pattern-type] (p/explode-uri pattern)
        [subject-client subject-type] (p/explode-uri subject)]
    (and (some (partial = pattern-client) ["*" subject-client])
         (some (partial = pattern-type)   ["*" subject-type]))))

(defn find-clients
  [{:keys [inventory]} patterns]
  (let [explicit (set (filter (complement p/uri-wildcard?) patterns))
        wildcard (set (filter p/uri-wildcard? patterns))
        uris (set (keys inventory))
        explicit-matched (intersection explicit uris)
        wildcard-matched (flatten (map #(filter (partial endpoint-pattern-match? %) uris) wildcard))]
    (sort (union explicit-matched (set wildcard-matched)))))
