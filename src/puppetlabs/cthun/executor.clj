(ns puppetlabs.cthun.executor
  (:require [clojure.tools.logging :as log])
  (:import [java.util EnumSet]
           [io.aleph.dirigiste Executors Executor Executor$Metric]))


(defn build-executor
  "return a new Executor Service"
  [utilization max-threads]
  (Executors/utilization utilization max-threads (EnumSet/allOf Executor$Metric)))
