(ns puppetlabs.pcp.broker.core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.core :refer :all]
            [puppetlabs.pcp.broker.capsule :as capsule]
            [puppetlabs.pcp.broker.connection :as connection]
            [puppetlabs.pcp.message :as message]
            [schema.core :as s]
            [slingshot.test]))

(s/defn ^:always-validate make-test-broker :- Broker
  "Return a minimal clean broker state"
  []
  {:activemq-broker    "JMSOMGBBQ"
   :accept-consumers   2
   :delivery-consumers 2
   :activemq-consumers (atom [])
   :record-client      (constantly true)
   :find-clients       (constantly true)
   :authorization-check (constantly true)
   :uri-map            (atom {})
   :connections        (atom {})
   :metrics-registry   ""
   :metrics            {}
   :transitions        {}
   :broker-cn          "broker.example.com"})

(deftest get-broker-cn-test
  (testing "It returns the correct cn"
    (let [cn (get-broker-cn "./test-resources/ssl/certs/broker.example.com.pem")]
      (is (= "broker.example.com" cn)))))

(deftest add-connection!-test
  (testing "It should add a connection to the connection map"
    (let [broker (make-test-broker)]
      (add-connection! broker "ws")
      (is (= (get-in @(:connections broker) ["ws" :state]) :open)))))

(deftest remove-connection!-test
  (testing "It should remove a connection from the connection map"
    (let [connections (atom {"ws" (connection/make-connection "ws")})
          broker      (assoc (make-test-broker) :connections connections)]
      (remove-connection! broker "ws")
      (is (= {} @(:connections broker))))))

(deftest get-websocket-test
  (let [broker (assoc (make-test-broker)
                      :uri-map (atom {"pcp://bill/agent" "ws1"
                                      "pcp://bob/agent" "ws2"}))]
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
    (is (= 1 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 2 :seconds)))))
    (is (= 4 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 9 :seconds))))))

  (testing "high bounds should be 15 seconds"
    (is (= 15 (retry-delay (capsule/wrap (message/set-expiry (message/make-message) 3000000 :seconds)))))))

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
                               "message_type" "example1"}
                :params {"sender" "pcp://example01.example.com/agent"
                         "targets" "pcp://example02.example.com/agent"
                         "message_type" "example1"}}
               (make-ring-request broker capsule)))))
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
                               "message_type" "example1"}
                :params {"sender" "pcp://example01.example.com/agent"
                         "targets" ["pcp://example02.example.com/agent"
                                    "pcp://example03.example.com/agent"]
                         "message_type" "example1"}}
               (make-ring-request broker capsule)))))))

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

(deftest reason-to-deny-association-test
  (let [broker     (make-test-broker)
        connection (connection/make-connection "websocket")
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
                  puppetlabs.experimental.websockets.client/send! (constantly false)]
      (let [message (-> (message/make-message)
                        (assoc  :sender "pcp://localhost/controller"
                                :message_type "http://puppetlabs.com/login_message"))
            capsule (capsule/wrap message)]
        (testing "It should return an associated session"
          (reset! closed (promise))
          (let [broker     (make-test-broker)
                connection (add-connection! broker "ws")
                connection (process-associate-message broker capsule connection)]
            (is (not (realized? @closed)))
            (is (= :associated (:state connection)))
            (is (= "pcp://localhost/controller" (:uri connection)))))

        (testing "It allows a login to from two locations for the same uri, but disconnects the first"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection1 (add-connection! broker "ws1")
                connection2 (add-connection! broker "ws2")]
            (process-associate-message broker capsule connection1)
            (is (process-associate-message broker capsule connection2))
            (is (= ["ws1" 4000 "superceded"] @@closed))
            (is (= ["ws2"] (keys @(:connections broker))))))

        (testing "It does not allow a login to happen twice on the same websocket"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws")
                connection (process-associate-message broker capsule connection)
                connection (process-associate-message broker capsule connection)]
            (is (= :associated (:state connection)))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))))))

(deftest validate-certname-test
  (testing "simple match, no exception"
    (is (validate-certname "pcp://lolcathost/agent" "lolcathost")))
  (testing "simple mismatch"
    (is (thrown+? [:type :puppetlabs.pcp.broker.core/identity-invalid
                   :message "Certificate mismatch.  Sender: 'lolcathost' CN: 'remotecat'"]
                  (validate-certname "pcp://lolcathost/agent" "remotecat"))))
  (testing "accidental regex collisions"
    (is (thrown+? [:type :puppetlabs.pcp.broker.core/identity-invalid
                   :message "Certificate mismatch.  Sender: 'lolcathost' CN: 'lol.athost'"]
                  (validate-certname "pcp://lolcathost/agent" "lol.athost")))))

(deftest determine-next-state-test
  (testing "illegal next states raise due to schema validation"
    (let [broker (make-test-broker)
          broker (assoc broker :transitions {:open (fn [_ _ c] (assoc c :state :badbadbad))})
          connection (connection/make-connection "ws")
          message (message/make-message)
          capsule (capsule/wrap message)]
      (is (= :open (:state connection)))
      (is (thrown+? [:type :schema.core/error]
                    (determine-next-state broker capsule connection)))))
  (testing "legal next states are accepted"
    (let [broker (make-test-broker)
          broker (assoc broker :transitions {:open (fn [_ _ c] (assoc c :state :associated))})
          connection (connection/make-connection "ws")
          message (message/make-message)
          capsule (capsule/wrap message)
          next (determine-next-state broker capsule connection)]
      (is (= :associated (:state next))))))
