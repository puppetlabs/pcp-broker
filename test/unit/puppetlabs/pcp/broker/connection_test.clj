(ns puppetlabs.pcp.broker.connection-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.connection :refer :all]))

(deftest make-connection-test
  (testing "It returns a map that matches represents a new socket"
    (let [socket (make-connection :dummy-ws {:encode identity :decode identity} "pcp:///dummy-uri")]
      (is (= :dummy-ws (:websocket socket)))
      (is (= nil (:endpoint socket))))))
