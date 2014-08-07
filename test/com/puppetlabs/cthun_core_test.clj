(ns com.puppetlabs.cthun-core-test
  (:require [clojure.test :refer :all]
            [com.puppetlabs.cthun-core :refer :all]))

(deftest hello-test
  (testing "says hello to caller"
    (is (= "Hello, foo!" (hello "foo")))))
