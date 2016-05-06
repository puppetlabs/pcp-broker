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
