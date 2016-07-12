(ns puppetlabs.pcp.broker.core-test
  (:require [clojure.test :refer :all]
            [metrics.core]
            [puppetlabs.pcp.broker.core :refer :all]
            [puppetlabs.pcp.broker.capsule :as capsule]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.message :as message]
            [schema.core :as s]
            [schema.test :as st]
            [slingshot.test])
  (:import (puppetlabs.pcp.broker.connection Connection)
           (java.util.concurrent ConcurrentHashMap)))

(s/defn make-test-broker :- Broker
  "Return a minimal clean broker state"
  []
  (let [broker {:activemq-broker    "JMSOMGBBQ"
                :accept-consumers   2
                :delivery-consumers 2
                :activemq-consumers (atom [])
                :record-client      (constantly true)
                :find-clients       (constantly ())
                :authorization-check (constantly true)
                :uri-map            (ConcurrentHashMap.)
                :connections        (ConcurrentHashMap.)
                :metrics-registry   metrics.core/default-registry
                :metrics            {}
                :transitions        {}
                :broker-cn          "broker.example.com"
                :state              (atom :running)}
        metrics (build-and-register-metrics broker)
        broker (assoc broker :metrics metrics)]
    broker))

(s/def identity-codec :- Codec
  {:encode identity
   :decode identity})

(use-fixtures :once st/validate-schemas)

(deftest get-broker-cn-test
  (testing "It returns the correct cn"
    (let [cn (get-broker-cn "./test-resources/ssl/certs/broker.example.com.pem")]
      (is (= "broker.example.com" cn)))))

(deftest add-connection!-test
  (testing "It should add a connection to the connection map"
    (let [broker (make-test-broker)]
      (add-connection! broker "ws" identity-codec)
      (is (= (get-in (:connections broker) ["ws" :state]) :open)))))

(deftest remove-connection!-test
  (testing "It should remove a connection from the connection map"
    (let [broker (make-test-broker)]
      (.put (:connections broker) "ws" (connection/make-connection "ws" identity-codec))
      (remove-connection! broker "ws")
      (is (= {} (:connections broker))))))

(deftest get-websocket-test
  (let [broker (make-test-broker)]
    (.put (:uri-map broker) "pcp://bill/agent" "ws1")
    (.put (:uri-map broker) "pcp://bob" "ws2")
    (testing "it finds a single websocket explictly"
      (is (= "ws1" (get-websocket broker "pcp://bill/agent"))))
    (testing "it finds nothing by wildcard"
      (is (not (get-websocket broker "pcp://*/agent"))))
    (testing "it finds nothing when it's not there"
      (is (not (get-websocket broker "pcp://bob/nonsuch"))))))

(deftest retry-delay-test
  (testing "low bounds should be 1 second"
    (is (= 1 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) -1 :seconds)))))
    (is (= 1 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 0 :seconds)))))
    (is (= 1 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 1 :seconds))))))

  (testing "it's about half the time we have left"
    (is (<= 1 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 2 :seconds))) 2))
    (is (<= 4 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 9 :seconds))) 5)))

  (testing "high bounds should be 15 seconds"
    (is (= 15 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 3000000 :seconds)))))))

(deftest handle-delivery-failure-test
  (let [broker (make-test-broker)
        capsule (capsule/wrap (message/set-expiry (message/make-message) 300 :seconds))
        expired-capsule (capsule/wrap (message/set-expiry (message/make-message) -3 :seconds))
        queued (atom [])
        expired (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.activemq/queue-message (fn [queue capsule delay]
                                                                 (swap! queued conj {:queue queue
                                                                                     :capsule capsule
                                                                                     :delay delay}))
                  puppetlabs.pcp.broker.core/process-expired-message (fn [broker capsule]
                                                                       (reset! expired capsule))]
      (testing "redelivery if not expired"
        (handle-delivery-failure broker capsule "Out of cheese")
        (is (= nil @expired))
        (is (= 1 (count @queued)))
        (is (= delivery-queue (:queue (first @queued)))))
      (testing "process-expired if expired"
        (reset! queued [])
        (reset! expired nil)
        (handle-delivery-failure broker expired-capsule "Out of cheese")
        (is (= expired-capsule @expired)
            (is (= [] @queued)))))))

(deftest maybe-send-destination-report-test
  (let [broker (make-test-broker)
        message (message/make-message)
        message-requesting (message/make-message :sender "pcp://example01.example.com/fooo"
                                                 :destination_report true)
        accepted (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/accept-message-for-delivery (fn [broker capsule] (reset! accepted capsule))]
      (testing "when not requested"
        (maybe-send-destination-report broker message ["pcp://example01.example.com/example"])
        (is (= nil @accepted)))
      (testing "when requested"
        (maybe-send-destination-report broker message-requesting ["pcp://example01.example.com/example"])
        (is ["pcp://example01.example.com/example"] (:targets (message/get-json-data (:message @accepted))))))))

(deftest deliver-message-test
  (let [broker (make-test-broker)
        message (-> (message/make-message)
                    (message/set-expiry 3 :seconds))
        capsule (assoc (capsule/wrap message) :target "pcp://example01.example.com/foo")
        failure (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/handle-delivery-failure (fn [broker capsule message]
                                                                       (reset! failure {:capsule capsule
                                                                                        :message message}))]
      (deliver-message broker capsule)
      (is (= "not connected" (:message @failure))))))

(deftest expand-destinations-test
  (let [broker (make-test-broker)
        message (message/make-message :targets ["pcp://example01.example.com/foo"])
        capsule (capsule/wrap message)
        queued (atom [])]
    (with-redefs [puppetlabs.pcp.broker.core/maybe-send-destination-report (constantly true)
                  puppetlabs.pcp.broker.activemq/queue-message (fn [queue capsule]
                                                                 (swap! queued conj {:queue queue
                                                                                     :capsule capsule}))]
      (expand-destinations broker capsule)
      (is (= 1 (count @queued)))
      (is (= delivery-queue (:queue (first @queued))))
      (is (= "pcp://example01.example.com/foo" (:target (:capsule (first @queued))))))))

(deftest make-ring-request-test
  (let [broker (make-test-broker)]
    (testing "it should return a ring request - one target"
      (let [message (message/make-message :message_type "example1"
                                          :sender "pcp://example01.example.com/agent"
                                          :targets ["pcp://example02.example.com/agent"])
            capsule (capsule/wrap message)]
        (is (= {:uri "/pcp-broker/send"
                :request-method :post
                :remote-addr ""
                :form-params {}
                :query-params {"sender" "pcp://example01.example.com/agent"
                               "targets" "pcp://example02.example.com/agent"
                               "message_type" "example1"
                               "destination_report" false}
                :params {"sender" "pcp://example01.example.com/agent"
                         "targets" "pcp://example02.example.com/agent"
                         "message_type" "example1"
                         "destination_report" false}}
               (make-ring-request broker capsule nil)))))
    (testing "it should return a ring request - two targets"
      (let [message (message/make-message :message_type "example1"
                                          :sender "pcp://example01.example.com/agent"
                                          :targets ["pcp://example02.example.com/agent"
                                                    "pcp://example03.example.com/agent"])
            capsule (capsule/wrap message)]
        (is (= {:uri "/pcp-broker/send"
                :request-method :post
                :remote-addr ""
                :form-params {}
                :query-params {"sender" "pcp://example01.example.com/agent"
                               "targets" ["pcp://example02.example.com/agent"
                                          "pcp://example03.example.com/agent"]
                               "message_type" "example1"
                               "destination_report" false}
                :params {"sender" "pcp://example01.example.com/agent"
                         "targets" ["pcp://example02.example.com/agent"
                                    "pcp://example03.example.com/agent"]
                         "message_type" "example1"
                         "destination_report" false}}
               (make-ring-request broker capsule nil)))))
    (testing "it should return a ring request - destination report"
      (let [message (message/make-message :message_type "example1"
                                          :sender "pcp://example01.example.com/agent"
                                          :targets ["pcp://example02.example.com/agent"]
                                          :destination_report true)
            capsule (capsule/wrap message)]
        (is (= {:uri "/pcp-broker/send"
                :request-method :post
                :remote-addr ""
                :form-params {}
                :query-params {"sender" "pcp://example01.example.com/agent"
                               "targets" "pcp://example02.example.com/agent"
                               "message_type" "example1"
                               "destination_report" true}
                :params {"sender" "pcp://example01.example.com/agent"
                         "targets" "pcp://example02.example.com/agent"
                         "message_type" "example1"
                         "destination_report" true}}
               (make-ring-request broker capsule nil)))))))

(deftest authorized?-test
  (let [yes-check (fn [r] {:authorized true
                           :message ""
                           :request r})
        no-check (fn [r] {:authorized false
                          :message "Danger Zone"
                          :request r})
        yes-broker (assoc (make-test-broker) :authorization-check yes-check)
        no-broker (assoc (make-test-broker) :authorization-check no-check)
        message (message/make-message :message_type "example1"
                                      :sender "pcp://example01.example.com/agent"
                                      :targets ["pcp://example02.example.com/agent"])
        capsule (capsule/wrap message)]
    (is (= true (authorized? yes-broker capsule)))
    (is (= false (authorized? no-broker capsule)))))

(deftest accept-message-for-delivery-test
  (let [broker (make-test-broker)
        capsule (capsule/wrap (message/make-message))
        queued (atom [])]
    (with-redefs [puppetlabs.pcp.broker.activemq/queue-message (fn [queue capsule] (swap! queued conj {:queue queue
                                                                                                       :capsule capsule}))
                  puppetlabs.pcp.broker.core/authorized? (constantly true)]
      (accept-message-for-delivery broker capsule)
      (is (= 1 (count @queued)))
      (is (= accept-queue (:queue (first @queued)))))))

(deftest process-expired-message-test
  (with-redefs [accept-message-for-delivery (fn [broker response] response)]
    (testing "It will create and send a ttl expired message"
      (let [expired (-> (message/make-message)
                        (assoc :sender "pcp://client2.com/tester"))
            broker (make-test-broker)
            capsule (process-expired-message broker (capsule/wrap expired))
            response (:message capsule)
            response-data (message/get-json-data response)]
        (is (= ["pcp://client2.com/tester"] (:targets response)))
        (is (= "http://puppetlabs.com/ttl_expired" (:message_type response)))
        (is (= (:id expired) (:id response-data)))))))

(deftest session-association-message?-test
  (testing "It returns true when passed a sessions association messge"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp:///server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= true (session-association-message? message)))))
  (testing "It returns false when passed a message of an unknown type"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp:///server"]
                             ;; OLDJOKE(richardc): we used to call association_request the loginschema
                             :message_type "http://puppetlabs.com/kennylogginsschema"))]
      (is (= false (session-association-message? message)))))
  (testing "It returns false when passed a message not aimed to the server target"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp://other/server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= false (session-association-message? message))))))

(defn association-capsule
  [sender seconds]
  (capsule/wrap (-> (message/make-message :sender sender)
                    (message/set-expiry seconds :seconds))))

(deftest reason-to-deny-association-test
  (let [broker     (make-test-broker)
        connection (connection/make-connection "websocket" identity-codec)
        associated (assoc connection :state :associated :uri "pcp://test/foo")]
    (is (= nil (reason-to-deny-association broker connection "pcp://test/foo")))
    (is (= "'server' type connections not accepted"
           (reason-to-deny-association broker connection "pcp://test/server")))
    (is (= "session already associated"
           (reason-to-deny-association broker associated "pcp://test/foo")))
    (is (= "session already associated"
           (reason-to-deny-association broker associated "pcp://test/bar")))))

(deftest process-associate-message-test
  (let [closed (atom (promise))]
    (with-redefs [puppetlabs.experimental.websockets.client/close! (fn [& args] (deliver @closed args))
                  puppetlabs.experimental.websockets.client/send! (constantly false)
                  puppetlabs.pcp.broker.core/authorized? (constantly true)]
      (let [message (-> (message/make-message :sender "pcp://localhost/controller"
                                              :message_type "http://puppetlabs.com/login_message")
                        (message/set-expiry 3 :seconds))
            capsule (capsule/wrap message)]
        (testing "It should return an associated session"
          (reset! closed (promise))
          (let [broker     (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-message broker capsule connection)]
            (is (not (realized? @closed)))
            (is (= :associated (:state connection)))
            (is (= "pcp://localhost/controller" (:uri connection)))))

        (testing "It allows a login to from two locations for the same uri, but disconnects the first"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection1 (add-connection! broker "ws1" identity-codec)
                connection2 (add-connection! broker "ws2" identity-codec)]
            (process-associate-message broker capsule connection1)
            (is (process-associate-message broker capsule connection2))
            (is (= ["ws1" 4000 "superceded"] @@closed))
            (is (= ["ws2"] (keys (:connections broker))))))

        (testing "It does not allow a login to happen twice on the same websocket"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-message broker capsule connection)
                connection (process-associate-message broker capsule connection)]
            (is (= :associated (:state connection)))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))))))

(deftest process-inventory-message-test
  (let [broker (make-test-broker)
        message (-> (message/make-message :sender "pcp://test.example.com/test")
                    (message/set-json-data  {:query ["pcp://*/*"]}))
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        accepted (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/accept-message-for-delivery (fn [broker capsule] (reset! accepted capsule))
                  puppetlabs.pcp.broker.core/authorized? (constantly true)]
      (process-inventory-message broker capsule connection)
      (is (= [] (:uris (message/get-json-data (:message @accepted))))))))

(deftest process-server-message-test
  (let [broker (make-test-broker)
        message (message/make-message :message_type "http://puppetlabs.com/associate_request")
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        associate-request (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/process-associate-message (fn [broker capsule connection]
                                                                         (reset! associate-request capsule)
                                                                         connection)]
      (process-server-message broker capsule connection)
      (is (not= nil @associate-request)))))

(s/defn dummy-connection-from :- Connection
  [common-name]
  (assoc (connection/make-connection "ws1" identity-codec)
         :common-name common-name))

(deftest check-sender-matches-test
  (testing "simple match"
    (is (check-sender-matches (message/make-message :sender "pcp://lolcathost/agent")
                              (dummy-connection-from "lolcathost"))))
  (testing "simple mismatch"
    (is (not (check-sender-matches (message/make-message :sender "pcp://lolcathost/agent")
                                   (dummy-connection-from "remotecat")))))
  (testing "accidental regex collisions"
    (is (not (check-sender-matches (message/make-message :sender "pcp://lolcathost/agent")
                                   (dummy-connection-from "lol.athost"))))))

(deftest connection-open-test
  (let [broker (make-test-broker)
        message (message/make-message :targets ["pcp:///server"]
                                      :message_type "http://puppetlabs.com/associate_request")
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        associate-request (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/process-associate-message (fn [broker capsule connection]
                                                                         (reset! associate-request capsule)
                                                                         connection)]
      (connection-open broker capsule connection)
      (is (not= nil @associate-request)))))

(deftest connection-associated-test
  (let [broker (make-test-broker)
        message (-> (message/make-message :message_type "http://puppetlabs.com/associate_request")
                    (message/set-expiry 3 :seconds))
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        accepted (atom nil)]
    (with-redefs [puppetlabs.pcp.broker.core/accept-message-for-delivery (fn [broker capsule]
                                                                           (reset! accepted capsule))]
      (connection-associated broker capsule connection)
      (is (not= nil @accepted)))))

(deftest determine-next-state-test
  (testing "illegal next states raise due to schema validation"
    (let [broker (make-test-broker)
          broker (assoc broker :transitions {:open (fn [_ _ c] (assoc c :state :badbadbad))})
          connection (connection/make-connection "ws" identity-codec)
          message (message/make-message)
          capsule (capsule/wrap message)]
      (is (= :open (:state connection)))
      (is (thrown+? [:type :schema.core/error]
                    (determine-next-state broker capsule connection)))))
  (testing "legal next states are accepted"
    (let [broker (make-test-broker)
          broker (assoc broker :transitions {:open (fn [_ _ c] (assoc c :state :associated))})
          connection (connection/make-connection "ws" identity-codec)
          message (message/make-message)
          capsule (capsule/wrap message)
          next (determine-next-state broker capsule connection)]
      (is (= :associated (:state next))))))
