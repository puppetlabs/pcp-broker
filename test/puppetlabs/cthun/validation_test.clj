(ns puppetlabs.cthun.validation-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.validation :refer :all]))

(deftest check-schema-test
  (testing "it raises an exception when an invalid message is received"
    (is (thrown? Exception (check-schema "invalid message"))))
  (testing "it returns the json structure when a valid message is passed"
    (let [json {:version "1"
                :id 1234
                :endpoints ["mco://host2.example.com/cnc/01"]
                :data_schema "/location/to/a/schema"
                :sender "mco://host1.example.com/controller/01",
                :expires  "2014-07-14T11:51:03+00:00"
                :hops [{:hop1 "2014-07-14T11:51:03+00:00"}]}]
      (is (= json (check-schema json))))))

(deftest validate-message-test
  (testing "it returns nil if message is invalid json"
    (is (= nil (validate-message "foo :")))))
