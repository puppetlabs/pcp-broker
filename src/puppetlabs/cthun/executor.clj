(ns puppetlabs.cthun.executor
  (:require [clojure.tools.logging :as log])
  (:import [java.util EnumSet]
           [io.aleph.dirigiste Executors Executor Executor$Metric]))


(defn build-executor
  "return a new Executor Service"
  [max-threads]
  (Executors/utilization 1.0 max-threads (EnumSet/allOf Executor$Metric)))
