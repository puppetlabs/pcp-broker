(ns puppetlabs.cthun.metrics
  (:require [clojure.tools.logging :as log]
            [clojure.java.jmx :as jmx]
            [clj-time.core :as time]
            [metrics.core]
            [metrics.counters :as counters]
            [metrics.meters :as meters]
            [metrics.timers :as timers]
            [cheshire.core :as cheshire]))

(def total-messages-in (counters/counter ["puppetlabs.cthun" "global" "total-messages-in"]))
(def total-messages-out (counters/counter ["puppetlabs.cthun" "global" "total-messages-out"]))
(def active-connections (counters/counter ["puppetlabs.cthun" "global" "active-connections"]))
(def rate-messages-in (meters/meter ["puppetlabs.cthun" "global" "rate-messages-in"]))
(def rate-messages-out (meters/meter ["puppetlabs.cthun" "global" "rate-messages-out"]))
(def time-in-on-connect (timers/timer ["puppetlabs.cthun" "handlers" "time-in-on-connect"]))
(def time-in-on-text (timers/timer ["puppetlabs.cthun" "handlers" "time-in-on-text"]))
(def time-in-on-close (timers/timer ["puppetlabs.cthun" "handlers" "time-in-on-close"]))
(def time-in-message-queueing (timers/timer ["puppetlabs.cthun" "global" "time-in-message-queueing"]))

(defn- get-cthun-metrics
  "Returns cthun specific metrics as a map"
  []
  (reduce into {}
          [(map (fn [[k v]] {k (meters/rates v)}) (.getMeters metrics.core/default-registry))
           (map (fn [[k v]] {k (counters/value v)}) (.getCounters metrics.core/default-registry))
           (map (fn [[k v]] {k {:rates (timers/rates v)
                                :mean (timers/mean v)
                                :std-dev (timers/std-dev v)
                                :percentiles (timers/percentiles v)
                                :largest (timers/largest v)
                                :smallest (timers/smallest v)} }) (.getTimers metrics.core/default-registry))]))

(defn- get-memory-metrics
  "Returns memory related metrics as a map"
  []
  (dissoc (jmx/mbean "java.lang:type=Memory") :ObjectName))

(defn- get-thread-metrics
  "Returns thread related metrics as a map"
  []
  (apply dissoc (jmx/mbean "java.lang:type=Threading") [:ObjectName :AllThreadIds]))

; TODO(ploubser): Flesh this out
(defn get-metrics-string
  "Returns some clean jmx metrics as a json string"
  []
  (cheshire/generate-string (-> (assoc {} :memory (get-memory-metrics))
                                (assoc :threads (get-thread-metrics))
                                (assoc :cthun (get-cthun-metrics)))
                            {:pretty true}))
