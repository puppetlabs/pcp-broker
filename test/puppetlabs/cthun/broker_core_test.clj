(ns puppetlabs.cthun.broker-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.broker-core :refer :all]
            [puppetlabs.cthun.message :as message]
            [schema.core :as s]
            [slingshot.test]))

(s/defn ^:always-validate make-test-broker :- Broker
  "Return a minimal clean broker state"
  []
  {:activemq-broker    "JMSOMGBBQ"
   :activemq-consumers []
   :record-client      (constantly true)
   :find-clients       (constantly true)
   :authorized         (constantly true)
   :uri-map            (atom {})
   :connections        (atom {})
   :metrics-registry   ""
   :metrics            {}})

(deftest new-socket-test
  (testing "It returns a map that matches represents a new socket"
    (let [socket (new-socket)]
      (is (= (:state socket) :open))
      (is (= nil (:endpoint socket))))))

(deftest add-connection!-test
  (testing "It should add a connection to the connection map"
    (let [broker (make-test-broker)]
      (add-connection! broker "ws")
      (is (= (get-in @(:connections broker) ["ws" :state]) :open)))))

(deftest remove-connection!-test
  (testing "It should remove a connection from the connection map"
    (let [connections (atom {"ws" (new-socket)})
          broker      (assoc (make-test-broker) :connections connections)]
      (remove-connection! broker "ws")
      (is (= {} @(:connections broker))))))

(deftest get-websocket-test
  (let [broker (assoc (make-test-broker)
                      :uri-map (atom {"cth://bill/agent" "ws1"
                                      "cth://bob/agent" "ws2"}))]
    (testing "it finds a single websocket explictly"
      (is (= "ws1" (get-websocket broker "cth://bill/agent"))))
    (testing "it finds nothing by wildcard"
      (is (not (get-websocket broker "cth://*/agent"))))
    (testing "it finds nothing when it's not there"
      (is (not (get-websocket broker "cth://bob/nonsuch"))))))

(deftest session-associated?-test
  (let [connections (atom {"ws1" (assoc (new-socket) :state :associated)
                           "ws2" (new-socket)})
        broker (assoc (make-test-broker) :connections connections)]
    (testing "It returns true if the websocket is associated"
      (is (= true (session-associated? broker "ws1"))))
    (testing "It returns false if the websocket is not associated"
      (is (= false (session-associated? broker "ws2"))))))

(deftest process-expired-message-test
  (with-redefs [accept-message-for-delivery (fn [broker response] response)]
    (testing "It will create and send a ttl expired message"
      (let [expired (-> (message/make-message)
                        (assoc :id "12347890"
                               :sender "cth://client2.com/tester"))
            broker (make-test-broker)
            response (process-expired-message broker expired)
            response-data (message/get-json-data response)]
        (is (= ["cth://client2.com/tester"] (:targets response)))
        (is (= "http://puppetlabs.com/ttl_expired" (:message_type response)))
        (is (= "12347890" (:id response-data)))))))

(deftest session-association-message?-test
  (testing "It returns true when passed a sessions association messge"
    (let [message (-> (message/make-message)
                      (assoc :targets ["cth:///server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= true (session-association-message? message)))))
  (testing "It returns false when passed a message of an unknown type"
    (let [message (-> (message/make-message)
                      (assoc :targets ["cth:///server"]
                             ;; OLDJOKE(richardc): we used to call association_request the loginschema
                             :message_type "http://puppetlabs.com/kennylogginsschema"))]
      (is (= false (session-association-message? message)))))
  (testing "It returns false when passed a message not aimed to the server target"
    (let [message (-> (message/make-message)
                      (assoc :targets ["cth://other/server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= false (session-association-message? message))))))

(deftest explode-uri-test
  (testing "It raises on invalid uris"
    (is (thrown? Exception (explode-uri ""))))
  (testing "It returns component chunks"
    (is (= [ "localhost" "agent"] (explode-uri "cth://localhost/agent")))
    (is (= [ "localhost" "*" ] (explode-uri "cth://localhost/*")))
    (is (= [ "*" "agent" ] (explode-uri "cth://*/agent")))))

(deftest process-session-association-message-test
  (with-redefs [puppetlabs.experimental.websockets.client/close! (fn [ws] false)
                puppetlabs.experimental.websockets.client/send! (fn [ws bytes] false)]
    (let [message (-> (message/make-message)
                      (assoc  :sender "cth://localhost/controller"
                              :message_type "http://puppetlabs.com/login_message"))]
      (testing "It should perform a login"
        (let [broker (make-test-broker)]
          (add-connection! broker "ws")
          (process-session-association-message broker "ws" message)
          (let [connection (get @(:connections broker) "ws")]
            (is (= :associated (:state connection)))
            (is (= "cth://localhost/controller" (:uri connection))))))

      (testing "It allows a login to from two locations for the same uri, but disconnects the first"
        (let [broker (make-test-broker)]
          (add-connection! broker "ws1")
          (add-connection! broker "ws2")
          (process-session-association-message broker "ws1" message)
          (is (process-session-association-message broker "ws2" message))
          (is (= ["ws2"] (keys @(:connections broker))))))

      (testing "It does not allow a login to happen twice on the same websocket"
        (let [broker (make-test-broker)]
          (add-connection! broker "ws")
          (process-session-association-message broker "ws" message)
          (is (not (process-session-association-message broker "ws" message))))))))

(deftest process-server-message-test
  (with-redefs [process-session-association-message (constantly true)]
    (let [broker (make-test-broker)
          message (message/make-message)]
      (testing "It should identify a session association message from the data schema"
        (is (= (process-server-message broker "w" (assoc message :message_type "http://puppetlabs.com/associate_request")) true)))
      (testing "It should not process an unkown type of server message"
        (is (= (process-server-message broker "w" (assoc message :message_type "http://puppetlabs.com")) nil))))))

(deftest process-message-test
  (let [message (-> (message/make-message)
                    (message/set-expiry 3 :seconds))
        broker  (make-test-broker)]
  (testing "It will ignore messages until the the client is associated"
    (is (= (process-message broker "ws" message) nil)))
  (testing "It will process an association message if the client is not associated"
    (with-redefs [process-server-message (fn [broker ws message] "login")
                  session-association-message? (constantly true)]
      (is (= (process-message broker "ws" message) "login"))))
  (testing "It will process a client message"
    (with-redefs [session-associated? (fn [broker ws] true)
                  accept-message-for-delivery (constantly "client")]
      (let [message (assoc message :targets ["cth://client1.com/somerole"])]
        (is (= (process-message broker "ws" message) "client")))))
  (testing "It will process a server message"
    (let [message (assoc message :targets ["cth:///server"])]
      (with-redefs [session-associated? (constantly true)
                    process-server-message (constantly "server")]
        (is (= (process-message broker "ws" message) "server")))))
  (testing "It will process an expired message"
    (with-redefs [message-expired? (constantly true)
                  process-expired-message (constantly :processed-expired)]
      (is (= (process-message broker "ws" message) :processed-expired))))))

(deftest validate-certname-test
  (testing "simple match, no exception"
    (is (validate-certname "cth://lolcathost/agent" "lolcathost")))
  (testing "simple mismatch"
    (is (thrown+? [:type :puppetlabs.cthun.broker-core/identity-invalid
                   :message "Certificate mismatch.  Sender: 'lolcathost' CN: 'remotecat'"]
                  (validate-certname "cth://lolcathost/agent" "remotecat"))))
  (testing "accidental regex collisions"
    (is (thrown+? [:type :puppetlabs.cthun.broker-core/identity-invalid
                   :message "Certificate mismatch.  Sender: 'lolcathost' CN: 'lol.athost'"]
                  (validate-certname "cth://lolcathost/agent" "lol.athost")))))
