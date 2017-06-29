(ns puppetlabs.pcp.testutils
  (:require [clojure.test :refer :all]))

(defmacro dotestseq [bindings & body]
  (if-not (seq bindings)
    `(do ~@body)
    (let [case-versions (remove keyword? (take-nth 2 bindings))]
      `(doseq ~bindings
         (testing (str "with " (clojure.string/join
                                ", "
                                (map #(str (pr-str %1) ": " (pr-str %2))
                                     '~case-versions
                                     (list ~@case-versions))))
           ~@body)))))

(defn received?
  "We sometimes see a 1006/abnormal close.
   This can be removed when PCP-714 is addressed."
  [response expected]
  (let [[status message] response]
    (or (= 1006 status)
        (= response expected))))

(defn retry-until-true
  [times condf]
  (Thread/sleep 1)
  (cond
    (condf) true
    (not (pos? times)) false
    :else (recur (dec times) condf)))
