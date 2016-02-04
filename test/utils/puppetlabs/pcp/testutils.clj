(ns puppetlabs.pcp.testutils
  (:require [clojure.test :refer :all]))

(defmacro dotestseq [bindings & body]
  (if-not (seq bindings)
    `(do ~@body)
    (let [case-versions (remove keyword? (take-nth 2 bindings))]
      `(doseq ~bindings
         (testing (str "Testing case: " (clojure.string/join
                                         ", "
                                         (map #(clojure.string/join ": " %)
                                              (partition
                                               2
                                               (interleave '~case-versions (list ~@case-versions))))))
           ~@body)))))
