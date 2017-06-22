(ns puppetlabs.pcp.broker.shared-test
  (:require [clojure.test :refer :all]
            [metrics.core]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.broker.shared :refer :all]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.broker.inventory :as inventory]
            [puppetlabs.pcp.broker.websocket :refer [ws->uri ws->common-name]]
            [puppetlabs.pcp.broker.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [slingshot.test])
  (:import [puppetlabs.pcp.broker.connection Connection]))

(def mock-uri "pcp://foo.com/agent")
(defn mock-ws->uri [_] mock-uri)

(s/defn make-test-broker :- Broker
  "Return a minimal clean broker state"
  []
  (let [broker {:broker-name         nil
                :authorization-check (constantly true)
                :database            (atom (inventory/init-database))
                :controllers         (atom {})
                :max-connections     0
                :should-stop         (promise)
                :metrics             {}
                :metrics-registry    metrics.core/default-registry
                :state               (atom :running)}
        metrics (build-and-register-metrics broker)
        broker (assoc broker :metrics metrics)]
    broker))

(deftest handle-delivery-failure-test
  (let [_ (make-test-broker)
        msg (message/make-message)
        delivered (atom [])]
    (with-redefs [puppetlabs.pcp.broker.shared/send-error-message
                  (fn [mg _ _]
                    (swap! delivered conj mg))]
      (testing "redelivery always"
        (handle-delivery-failure msg nil "Out of cheese")
        (is (= 1 (count @delivered)))))))

(deftest deliver-message-test
  (let [broker (make-test-broker)
        failure (atom nil)]
    (testing "delivers messages"
      (let [message (message/make-message
                     {:target "pcp://example01.example.com/foo"})]
        (with-redefs [puppetlabs.pcp.broker.shared/handle-delivery-failure
                      (fn [_ _ err] (reset! failure err))]
          (deliver-message broker message nil)
          (is (= "Not connected." @failure)))))
    (testing "errors if multicast message"
      (let [message (message/make-message)]
        (with-redefs [puppetlabs.pcp.broker.message/multicast-message?  (fn [_] true)]
          (is (thrown? java.lang.AssertionError (deliver-message broker message nil))))))))

(deftest send-error-message-test
  (with-redefs [ws->uri mock-ws->uri]
    (let [error-msg (atom nil)
          connection (connection/make-connection :dummy-ws message/v2-codec mock-uri)]
      (with-redefs [ws->common-name (fn [_] "host_x")
                    puppetlabs.experimental.websockets.client/send!
                    (fn [_ raw-message]
                      (reset! error-msg (message/v2-decode raw-message)))]
        (testing "sends error_message correctly and returns nil if received msg is NOT given"
          (let [received-msg nil
                error-description "something wrong!"
                outcome (send-error-message received-msg error-description connection)
                msg-data (:data @error-msg)]
            (is (nil? outcome))
            (is (not (contains? @error-msg :in_reply_to)))
            (is (= error-description msg-data))))
        (testing "sends error_message correctly and returns nil if received msg is given"
          (reset! error-msg nil)
          (let [the-uuid (ks/uuid)
                received-msg (message/make-message
                              {:sender "pcp://test_example/pcp_client_alpha"
                               :message_type "gossip"
                               :target "pcp://test_broker/server"})
                received-msg (assoc received-msg :id the-uuid)
                error-description "something really wrong :o"
                outcome (send-error-message received-msg error-description connection)
                msg-data (:data @error-msg)]
            (is (nil? outcome))
            (is (= the-uuid (:in_reply_to @error-msg)))
            (is (= error-description msg-data))))))))
