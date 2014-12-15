(ns puppetlabs.cthun.validation-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.validation :refer :all]
            [schema.core :as s]))

(deftest check-endpoint-test
  (testing "it raises an exception for invalid endpoints"
    (is (thrown? Exception (s/validate Endpoint "")))
    (is (thrown? Exception (s/validate Endpoint "http://")))
    (is (thrown? Exception (s/validate Endpoint "cth:/foo"))))
  (testing "it accepts valid endpoints"
    (is (= "cth://server"
           (s/validate Endpoint "cth://server")))
    (is (= "cth://me.example.com/agent/1"
           (s/validate Endpoint "cth://me.example.com/agent/1")))))

(deftest check-schema-test
  (testing "it raises an exception when an invalid message is received"
    (is (thrown? Exception (check-schema "invalid message"))))
  (testing "it returns the json structure when a valid message is passed"
    (let [json {:version "1"
                :id "1234"
                :endpoints ["cth://host2.example.com/cnc/01"]
                :data_schema "/location/to/a/schema"
                :sender "cth://host1.example.com/controller/01",
                :expires  "2014-07-14T11:51:03+00:00"
                :hops [{:server "cth://hop1/server"
                        :time "2014-07-14T11:51:03+00:00"}]}]
      (is (= json (check-schema json))))))

(deftest validate-message-test
  (testing "it returns nil if message is invalid json"
    (is (= nil (validate-message "foo :" "lolcathost")))))

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
