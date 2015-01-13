(ns puppetlabs.cthun.executor-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.executor :refer :all]))

(deftest build-executor-test
  (testing "it builds an executor"
    (is (build-executor 0.9 64))))
