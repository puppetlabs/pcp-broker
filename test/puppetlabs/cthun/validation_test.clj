(ns puppetlabs.cthun.validation-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.validation :refer :all]
            [schema.core :as s]))

(deftest endpoint-test
  (testing "it raises an exception for invalid endpoints"
    (is (thrown? Exception (s/validate Endpoint "")))
    (is (thrown? Exception (s/validate Endpoint "http://")))
    (is (thrown? Exception (s/validate Endpoint "cth:/foo"))))
  (testing "it accepts valid endpoints"
    (is (= "cth://server"
           (s/validate Endpoint "cth://server")))
    (is (= "cth://me.example.com/agent/1"
           (s/validate Endpoint "cth://me.example.com/agent/1")))))

(deftest explode-endpoint-test
  (testing "It raises on invalid endpoints"
    (is (thrown? Exception (explode-endpoint ""))))
  (testing "It returns component chunks"
    (is (= [ "localhost" "agent"] (explode-endpoint "cth://localhost/agent")))
    (is (= [ "localhost" "*" ] (explode-endpoint "cth://localhost/*")))
    (is (= [ "*" "agent" ] (explode-endpoint "cth://*/agent")))))

(deftest check-certname-test
  (testing "simple match"
    (is (check-certname "cth://lolcathost/agent" "lolcathost")))
  (testing "simple mismatch"
    (is (not (check-certname "cth://lolcathost/agent" "remotecat"))))
  (testing "accidental regex collisions"
    (is (not (check-certname "cth://lolcathost/agent" "lol.athost")))))
