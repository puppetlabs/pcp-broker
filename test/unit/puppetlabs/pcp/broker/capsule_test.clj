(ns puppetlabs.pcp.broker.capsule-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.capsule :refer :all]
            [puppetlabs.pcp.message :as message]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [schema.core :as s]
            [schema.test :as st]
            [slingshot.test])
  (:import [puppetlabs.pcp.broker.capsule Capsule]))

(use-fixtures :once st/validate-schemas)

(deftest wrap-message-test
  (testing "wrapping a Message in a capsule"
    (is (instance? Capsule (wrap (message/make-message)))))
  (testing "fails if an invalid message is given"
    (is (thrown? Exception (wrap {:things "bad stuff"}))))
  (testing "does not add any debug hop entry"
    (is (empty? (:hops (wrap (message/make-message)))))))

(def messages [(message/make-message)
               (assoc (message/make-message) :sender "pcp://localhost/tester")
               (assoc (message/make-message) :message_type "bomb")
               (assoc (message/make-message) :targets ["pcp://localhost/a",
                                                       "pcp://localhost/b"])])

(deftest summarize-test
  (dotestseq [msg messages]
    (testing "can summarize capsules"
    (let [capsule (wrap msg)]
      (is (s/validate CapsuleLog (summarize capsule)))))))

(deftest add-hop-test
  (let [capsule (wrap (message/make-message))]
    (testing "can add a hop"
      (let [capsule (add-hop capsule "pcp://server/a" "cooking")]
        (is (not (empty? (:hops capsule))))))))

(deftest encode-test
  (let [capsule (wrap (message/make-message))]
    (testing "can encode without hops"
      (is (s/validate message/Message (encode capsule))))
    (testing "can encode with hops"
      (let [capsule (add-hop capsule "pcp://server/b" "cleaning")]
        (is (s/validate message/Message (encode capsule)))))))
