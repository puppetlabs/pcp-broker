(ns puppetlabs.pcp.broker.util
  (:require [schema.core :as s]))

(defn update-cond
  "Works like update, but only if pred is satisfied"
  [m pred ks f & args]
  (if pred
    (apply update-in m ks f args)
    m))

(defn update-when
  "Works like update, but only if ks is found in the map(s)"
  [m ks f & args]
  (let [val (get-in m ks ::not-found)]
    (apply update-cond m (not= val ::not-found) ks f args)))

(defmacro assoc-when
  "Assocs the provided values with the corresponding keys if and only
  if the key is not already present in map."
  [map key val & kvs]
  {:pre [(even? (count kvs))]}
  (let [deferred-kvs (vec (for [[k v] (cons [key val] (partition 2 kvs))]
                            [k `(fn [] ~v)]))]
    `(let [updates# (for [[k# v#] ~deferred-kvs
                          :when (= ::not-found (get ~map k# ::not-found))]
                      [k# (v#)])]
       (merge ~map (into {} updates#)))))

(defn ensure-vec
  [v]
  (if (vector? v) v (vector v)))

(s/defn hexdump :- s/Str
  [data :- (s/either bytes s/Str)]
  (let [bytes (if (string? data) (.getBytes data) data)]
    (clojure.string/join " " (map #(format "%02X" %) bytes))))
