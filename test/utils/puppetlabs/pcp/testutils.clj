(ns puppetlabs.pcp.testutils
  (:require [clojure.test :refer :all]))

(defmacro dotestseq [bindings & body]
  (if-not (seq bindings)
    `(do ~@body)
    (let [case-versions (remove keyword? (take-nth 2 bindings))]
      `(doseq ~bindings
         (testing (str "Testing case " '~case-versions)
           ~@body)))))
