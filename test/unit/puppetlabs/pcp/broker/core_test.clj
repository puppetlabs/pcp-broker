(ns puppetlabs.pcp.broker.core-test
  (:require [clojure.test :refer :all]
            [metrics.core]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
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
                :reject-expired-msg false
                :uri-map            (ConcurrentHashMap.)
                :connections        (ConcurrentHashMap.)
                :metrics-registry   metrics.core/default-registry
                :metrics            {}
                :broker-cn          "broker.example.com"
                :state              (atom :running)}
        metrics (build-and-register-metrics broker)
        broker (assoc broker :metrics metrics)]
    broker))

(s/def identity-codec :- Codec
  {:encode identity
   :decode identity})

(deftest get-broker-cn-test
  (testing "It returns the correct cn"
    (let [cn (get-broker-cn "./test-resources/ssl/certs/broker.example.com.pem")]
      (is (= "broker.example.com" cn))))
  (testing "It returns the correct cn from a certificate chain"
    (let [cn (get-broker-cn "./test-resources/ssl/certs/broker-chain.example.com.pem")]
      (is (= "broker.example.com" cn))))
  (testing "It throws an exception when the certificate file is empty"
    (is (thrown-with-msg? IllegalArgumentException
                          #"\./test-resources/ssl/certs/broker-empty\.example\.com.pem must contain at least 1 certificate"
                          (get-broker-cn "./test-resources/ssl/certs/broker-empty.example.com.pem")))))

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
        failure (atom nil)]
    (testing "delivers messages"
      (let [message (-> (message/make-message)
                        (message/set-expiry 3 :seconds))
            capsule (assoc (capsule/wrap message) :target "pcp://example01.example.com/foo")]
        (with-redefs [puppetlabs.pcp.broker.core/handle-delivery-failure (fn [broker capsule message]
                                                                           (reset! failure {:capsule capsule
                                                                                            :message message}))]
          (deliver-message broker capsule)
          (is (= "not connected" (:message @failure))))))
    (testing "delivers expired messages if reject-expired-msg is disabled"
      (let [message (-> (message/make-message)
                        (message/set-expiry 0 :seconds))
            capsule (assoc (capsule/wrap message) :target "pcp://example01.example.com/foo")]
        (with-redefs [puppetlabs.pcp.broker.core/handle-delivery-failure (fn [broker capsule message]
                                                                           (reset! failure {:capsule capsule
                                                                                            :message message}))]
          (deliver-message (assoc broker :reject-expired-msg false) capsule)
          (is (= "not connected" (:message @failure))))))
    (testing "drops expired messages if reject-expired-msg is enabled"
      (let [message (-> (message/make-message)
                        (message/set-expiry 0 :seconds))
            capsule (assoc (capsule/wrap message) :target "pcp://example01.example.com/foo")]
        (with-redefs [puppetlabs.pcp.broker.core/process-expired-message (fn [broker capsule]
                                                                           (reset! failure "expired!"))]
          (deliver-message (assoc broker :reject-expired-msg true) capsule)
          (is (= "expired!" @failure)))))))

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
  (testing "it should return a ring request - one target"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"])
          capsule (capsule/wrap message)]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" false}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" false}}
             (make-ring-request capsule nil)))))
  (testing "it should return a ring request - two targets"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"
                                                  "pcp://example03.example.com/agent"])
          capsule (capsule/wrap message)]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            ["pcp://example02.example.com/agent"
                                                     "pcp://example03.example.com/agent"]
                               "message_type"       "example1"
                               "destination_report" false}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            ["pcp://example02.example.com/agent"
                                                     "pcp://example03.example.com/agent"]
                               "message_type"       "example1"
                               "destination_report" false}}
             (make-ring-request capsule nil)))))
  (testing "it should return a ring request - destination report"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"]
                                        :destination_report true)
          capsule (capsule/wrap message)]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" true}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" true}}
             (make-ring-request capsule nil))))))

(defn yes-authorization-check [r] {:authorized true
                                   :message ""
                                   :request r})

(defn no-authorization-check [r] {:authorized false
                                  :message "Danger Zone"
                                  :request r})

(deftest authorized?-test
  (let [yes-broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
        no-broker (assoc (make-test-broker) :authorization-check no-authorization-check)
        message (message/make-message :message_type "example1"
                                      :sender "pcp://example01.example.com/agent"
                                      :targets ["pcp://example02.example.com/agent"])
        capsule (capsule/wrap message)]
    (is (= true (authorized? yes-broker capsule nil)))
    (is (= false (authorized? no-broker capsule nil)))))

(deftest accept-message-for-delivery-test
  (let [broker (make-test-broker)
        capsule (capsule/wrap (message/make-message))
        queued (atom [])]
    (with-redefs [puppetlabs.pcp.broker.activemq/queue-message (fn [queue capsule]
                                                                 (swap! queued conj {:queue queue
                                                                                     :capsule capsule}))]
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
      (is (= true (session-association-request? message)))))
  (testing "It returns false when passed a message of an unknown type"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp:///server"]
                             ;; OLDJOKE(richardc): we used to call association_request the loginschema
                             :message_type "http://puppetlabs.com/kennylogginsschema"))]
      (is (= false (session-association-request? message)))))
  (testing "It returns false when passed a message not aimed to the server target"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp://other/server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= false (session-association-request? message))))))

(deftest reason-to-deny-association-test
  (let [broker     (make-test-broker)
        connection (connection/make-connection "websocket" identity-codec)
        associated (assoc connection :state :associated :uri "pcp://test/foo")]
    (is (= nil (reason-to-deny-association broker connection "pcp://test/foo")))
    (is (= "'server' type connections not accepted"
           (reason-to-deny-association broker connection "pcp://test/server")))
    (is (= "Session already associated"
           (reason-to-deny-association broker associated "pcp://test/foo")))
    (is (= "Session already associated"
           (reason-to-deny-association broker associated "pcp://test/bar")))))

(deftest process-associate-request!-test
  (let [closed (atom (promise))]
    (with-redefs [puppetlabs.experimental.websockets.client/close! (fn [& args] (deliver @closed args))
                  puppetlabs.experimental.websockets.client/send! (constantly false)]
      (let [message (-> (message/make-message :sender "pcp://localhost/controller"
                                              :message_type "http://puppetlabs.com/login_message")
                        (message/set-expiry 3 :seconds))
            capsule (capsule/wrap message)]
        (testing "It should return an associated Connection if there's no reason to deny association"
          (reset! closed (promise))
          (let [broker     (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-request! broker capsule connection)]
            (is (not (realized? @closed)))
            (is (= :associated (:state connection)))
            (is (= "pcp://localhost/controller" (:uri connection)))))
        (testing "Associates a client already associated on a different session, but disconnects the first"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection1 (add-connection! broker "ws1" identity-codec)
                connection2 (add-connection! broker "ws2" identity-codec)]
            (process-associate-request! broker capsule connection1)
            (is (process-associate-request! broker capsule connection2))
            (is (= ["ws1" 4000 "superseded"] @@closed))
            (is (= ["ws2"] (keys (:connections broker))))))
        ;; TODO(ale): change this behaviour (PCP-521)
        (testing "No association for the same WebSocket session; closes and returns nil"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-request! broker capsule connection)
                outcome1 (process-associate-request! broker capsule connection)
                outcome2 (process-associate-request! broker capsule connection)]
            ;; NB(ale): in this case, the Connection object is removed from the
            ;; broker's connections map by the onClose handler once triggered
            (is (= :associated (:state connection)))
            (is (nil? outcome1))
            (is (nil? outcome2))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))
        (testing "No association if a reason to deny is provided; closes the session and return nil"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                outcome (process-associate-request! broker capsule connection "because I said so!")]
            ;; NB(ale): as above, the connections map is updated after onClose
            (is (nil? outcome))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))))))

(deftest process-inventory-request-test
  (let [broker (make-test-broker)
        message (-> (message/make-message :sender "pcp://test.example.com/test")
                    (message/set-json-data  {:query ["pcp://*/*"]}))
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        connection (assoc connection :state :associated)
        accepted (atom nil)]
    (with-redefs
      [puppetlabs.pcp.broker.core/accept-message-for-delivery (fn [broker capsule]
                                                                (reset! accepted capsule))]
      (let [outcome (process-inventory-request broker capsule connection)]
        (is (nil? outcome))
        (is (= [] (:uris (message/get-json-data (:message @accepted)))))))))

(deftest process-server-message!-test
  (let [broker (make-test-broker)
        message (message/make-message :message_type "http://puppetlabs.com/associate_request")
        capsule (capsule/wrap message)
        connection (connection/make-connection "ws1" identity-codec)
        associate-request (atom nil)]
    (with-redefs
      [puppetlabs.pcp.broker.core/process-associate-request! (fn [broker capsule connection]
                                                               (reset! associate-request capsule)
                                                               connection)]
      (process-server-message! broker capsule connection)
      (is (not= nil @associate-request)))))

(s/defn dummy-connection-from :- Connection
  [common-name]
  (assoc (connection/make-connection "ws1" identity-codec)
         :common-name common-name))

(deftest authenticated?-test
  (let [capsule (capsule/wrap (message/make-message :sender "pcp://lolcathost/agent"))]
    (testing "simple match"
      (is (authenticated? capsule (dummy-connection-from "lolcathost"))))
    (testing "simple mismatch"
      (is (not (authenticated? capsule (dummy-connection-from "remotecat")))))
    (testing "accidental regex collisions"
      (is (not (authenticated? capsule (dummy-connection-from "lol.athost")))))))

(defn make-valid-ring-request
  [message _]
  (let [{:keys [sender targets message_type destination_report]} message
        params {"sender"             sender
                "targets"            targets
                "message_type"       message_type
                "destination_report" destination_report}]
    {:uri            "/pcp-broker/send"
     :request-method :post
     :remote-addr    ""
     :form-params    {}
     :query-params   params
     :params         params}))

(deftest validate-message-test
  (testing "ignores messages other than associate_request if connection not associated"
    (let [broker (make-test-broker)
          capsule (capsule/wrap (message/make-message))
          connection (dummy-connection-from "localpost")
          is-association-request false]
      (is (= :to-be-ignored-during-association
             (validate-message broker capsule connection is-association-request)))))
  (testing "correctly marks not authenticated messages"
    (let [broker (make-test-broker)
          msg (message/make-message :sender "pcp://localpost/office"
                                    :message_type "http://puppetlabs.com/associate_request")
          capsule (capsule/wrap msg)
          connection (dummy-connection-from "groceryshop")
          is-association-request true]
      (is (= :not-authenticated
             (validate-message broker capsule connection is-association-request)))))
  (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request]
    (testing "correctly marks not authorized messages"
      (let [no-broker (assoc (make-test-broker) :authorization-check no-authorization-check)
            msg (message/make-message :sender "pcp://greyhacker/exploit"
                                      :message_type "http://puppetlabs.com/associate_request")
            capsule (capsule/wrap msg)
            connection (dummy-connection-from "greyhacker")
            is-association-request true]
        (is (= :not-authorized
               (validate-message no-broker capsule connection is-association-request)))))
    (testing "marks expired messages as to be processed when reject-expired-msg disabled"
      (let [yes-broker (assoc (make-test-broker)
                              :authorization-check yes-authorization-check
                              :reject-expired-msg false)
            msg (message/make-message :sender "pcp://localcost/gbp"
                                      :message_type "http://puppetlabs.com/associate_request")
            capsule (capsule/wrap (message/set-expiry msg -3 :seconds))
            connection (dummy-connection-from "localcost")
            is-association-request true]
        (is (= :to-be-processed
               (validate-message yes-broker capsule connection is-association-request)))))
    (testing "marks expired messages when reject-expired-msg enabled"
      (let [yes-broker (assoc (make-test-broker)
                              :authorization-check yes-authorization-check
                              :reject-expired-msg true)
            msg (message/make-message :sender "pcp://localcost/gbp"
                                      :message_type "http://puppetlabs.com/associate_request")
            capsule (capsule/wrap (message/set-expiry msg -3 :seconds))
            connection (dummy-connection-from "localcost")
            is-association-request true]
        (is (= :expired
               (validate-message yes-broker capsule connection is-association-request)))))
    (testing "correctly marks messages to be processed"
      (let [yes-broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
            msg (message/make-message :sender "pcp://localghost/opera"
                                      :message_type "http://puppetlabs.com/associate_request")
            capsule (capsule/wrap (message/set-expiry msg 5 :seconds))
            connection (dummy-connection-from "localghost")
            is-association-request true]
        (is (= :to-be-processed
               (validate-message yes-broker capsule connection is-association-request)))))))

;; TODO(ale): add more tests for onMessage processing (PCP-523)
(deftest process-message!-test
  (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request]
    (testing "sends an error message and returns nil, in case it fails to deserialize"
      (let [broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
            error-message-description (atom nil)]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] (add-connection! broker "ws" v1-codec))
                      puppetlabs.pcp.broker.core/send-error-message
                      (fn [msg description connection] (reset! error-message-description description) nil)]
          (dotestseq
            [raw-message [(byte-array [])           ; empty message
                          (byte-array [0])          ; bad message
                          (byte-array [3 3 3 3 3])  ; bad message
                          (byte-array [1,           ; first chunk not envelope
                                       2, 0 0 0 2, 123 125])
                          (byte-array [1,           ; bad envelope
                                       1, 0 0 0 1, 12 12 12 12])
                          (byte-array [1,           ; bad (incomplete) envelope
                                       1, 0 0 0 2, 123])]]
            (let [outcome (process-message! broker (byte-array raw-message) nil)]
              (is (= "Could not decode message" @error-message-description))
                       (is (nil? outcome)))))))
    (testing "queues message for delivery in case of expired msg (not associate_session)"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check
                          :reject-expired-msg false)
            called-accept-message (atom false)
            msg (message/make-message :sender "pcp://host_a/entity"
                                      :message_type "some_kinda_love"
                                      :targets ["pcp://host_b/entity"])
            capsule (capsule/wrap (message/set-expiry msg 5 :seconds))
            raw-msg (->> capsule (capsule/encode) (message/encode))
            connection (merge (dummy-connection-from "host_a")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (.put (:connections broker) "ws1" connection)
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/accept-message-for-delivery
                      (fn [broker capsule] (reset! called-accept-message true))]
          (let [outcome (process-message! broker raw-msg nil)]
            (is @called-accept-message)
            (is (nil? outcome))))))
    (testing "queues a ttl_expired in case of expired msg (not associate_session) when reject_expired_msg enabled"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check
                          :reject-expired-msg true)
            called-process-expired (atom false)
            msg (message/make-message :sender "pcp://host_a/entity"
                                      :message_type "some_kinda_love"
                                      :targets ["pcp://host_b/entity"])
            capsule (capsule/wrap (message/set-expiry msg 5 :seconds))
            raw-msg (->> capsule (capsule/encode) (message/encode))
            connection (merge (dummy-connection-from "host_a")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (.put (:connections broker) "ws1" connection)
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/process-expired-message
                      (fn [broker capsule] (reset! called-process-expired true))]
          (let [outcome (process-message! broker raw-msg nil)]
            (is @called-process-expired)
            (is (nil? outcome))))))))
