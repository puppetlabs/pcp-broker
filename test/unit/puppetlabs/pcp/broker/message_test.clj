(ns puppetlabs.pcp.broker.message-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.message :refer [make-message multicast-message? v1-decode v1-encode v2-decode
                                                   v2-encode]]))

(deftest multicast-message?-test
  (testing "returns false if target specifies a single host with no wildcards"
    (let [message (make-message
                   {:target "pcp://example01.example.com/foo"})]
      (is (not (multicast-message? message)))))
  (testing "returns true if target includes wildcard hostname"
    (let [message (make-message
                   {:target "pcp://*/foo"})]
      (is (multicast-message? message))))
  (testing "returns true if target includes wildcard client type"
    (let [message (make-message
                   {:target "pcp://example01.example.com/*"})]
      (is (multicast-message? message))))
  (testing "returns true if multicast-message key is inserted"
    (let [message (-> (make-message
                       {:target "pcp://example01.example.com/agent"})
                      (assoc :multicast-message true))]
      (is (multicast-message? message)))))

(deftest message-roundtrip-test
  (testing "message survives v1 roundtrip"
    (let [message (make-message
                   {:sender "pcp://gangoffour/entity"
                    :message_type "ether"
                    :target "pcp://gangoffour/entity"})]
      (is (= message (-> message
                         v1-encode
                         v1-decode)))))
  (testing "message survives v2 roundtrip"
    (let [message (make-message
                   {:sender "pcp://gangoffour/entity"
                    :message_type "ether"
                    :target "pcp://gangoffour/entity"})]
      (is (= message (-> message
                         v2-encode
                         v2-decode))))))
