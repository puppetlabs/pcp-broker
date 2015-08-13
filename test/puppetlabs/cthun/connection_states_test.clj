(ns puppetlabs.cthun.connection-states-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.cthun.connection-states :refer :all]
            [puppetlabs.kitchensink.core :as ks]))


; private symbols
(def make-uri #'puppetlabs.cthun.connection-states/make-uri)
(def process-session-association-message #'puppetlabs.cthun.connection-states/process-session-association-message)
(def process-server-message  #'puppetlabs.cthun.connection-states/process-server-message)
(def session-associated?  #'puppetlabs.cthun.connection-states/session-associated?)
(def session-association-message?  #'puppetlabs.cthun.connection-states/session-association-message?)

(deftest make-uri-test
  (testing "It creates a correct uri string"
    (is (= "cth://localhost/controller" (make-uri "localhost" "controller")))))

(deftest websocket-for-uri-test
  (reset! uri-map {"cth://bill/agent" "ws1"
                   "cth://bob/agent" "ws2"})
  (testing "it finds a single websocket explictly"
    (is (= "ws1"
           (websocket-for-uri "cth://bill/agent"))))
  (testing "it finds nothing by wildcard"
    (is (not (websocket-for-uri "cth://*/agent"))))
  (testing "it finds nothing when it's not there"
    (is (not (websocket-for-uri "cth://bob/nonsuch")))))

(deftest new-socket-test
  (testing "It returns a map that matches represents a new socket"
    (let [socket (new-socket "localhost")]
      (is (= (:client socket) "localhost"))
      (is (= (:status socket) "connected"))
      (is (= nil (:endpoint socket)))
      (is (not= nil (ks/datetime? (:created-at socket)))))))

(deftest session-associated?-test
  (testing "It returns true if the websocket is associated"
    (swap! connection-map assoc "ws" {:status "ready"})
    (is (= true (session-associated? "ws"))))
  (testing "It returns false if the websocket is not associated"
    (swap! connection-map assoc "ws" {:status "connected"})
    (is (= false (session-associated? "ws")))))

(deftest session-association-message?-test
  (testing "It returns true when passed a sessions association messge"
    (is (= true (session-association-message? {:targets ["cth:///server"] :message_type "http://puppetlabs.com/associate_request"}))))
  (testing "It returns false when passed a message of an unknown type"
    (is (= false (session-association-message? {:targets ["cth:///server"] :message_type "http://puppetlabs.com/kennylogginsschema"}))))
  (testing "It returns false when passed a message not aimed to the server target"
    (is (= false (session-association-message? {:targets ["cth://other/server"] :message_type "http://puppetlabs.com/loginschema"})))))

(deftest add-connection-test
  (testing "It should add a connection to the connection map"
    (add-connection "localhost" "ws")
    (is (= (get-in @connection-map ["ws" :status]) "connected"))))

(deftest remove-connection-test
  (reset! connection-map {})
  (testing "It should remove a connection from the connection map"
    (add-connection "localhost" "ws")
    (remove-connection "localhost" "ws")
    (is (= {} @connection-map))))

(deftest process-session-association-message-test
  (with-redefs [ring.adapter.jetty9/close! (fn [ws] false)
                ring.adapter.jetty9/send! (fn [ws bytes] false)]
    (let [login-message {:id ""
                         :sender "cth://localhost/controller"
                         :message_type "http://puppetlabs.com/login_message"}]
      (testing "It should perform a login"
        (reset! uri-map {})
        (add-connection "localhost" "ws")
        (reset! inventory {:record-client (fn [endpoint])})
        (swap! connection-map assoc-in ["ws" :created-at] "squirrel")
        (process-session-association-message "localhost" "ws" login-message)
        (let [connection (get @connection-map "ws")]
          (is (= (:client connection) "localhost"))
          (is (= (:status connection) "ready"))
          (is (= (:created-at connection) "squirrel"))
          (is (= "cth://localhost/controller" (:uri connection)))))

      (testing "It allows a login to from two locations for the same uri, but disconnects the first"
        (reset! uri-map {})
        (reset! connection-map {})
        (add-connection "localhost" "ws1")
        (is (= ["ws1"] (keys @connection-map)))
        (add-connection "localhost" "ws2")
        (is (= ["ws1" "ws2"] (sort (keys @connection-map))))
        (process-session-association-message "localhost" "ws1" login-message)
        (is (process-session-association-message "localhost" "ws2" login-message))
        (is (= ["ws2"] (keys @connection-map))))

      (testing "It does not allow a login to happen twice on the same websocket"
        (reset! uri-map {})
        (reset! connection-map {})
        (add-connection "localhost" "ws")
        (process-session-association-message "localhost" "ws" login-message)
        (is (not (process-session-association-message "localhost" "ws" login-message)))))))

(deftest process-server-message-test
  (with-redefs [puppetlabs.cthun.connection-states/process-session-association-message (fn [host ws message-body] true)]
    (testing "It should identify a session association message from the data schema"
      (is (= (process-server-message "localhost" "w" {:message_type "http://puppetlabs.com/associate_request"}) true)))
    (testing "It should not process an unkown type of server message"
      (is (= (process-server-message "localhost" "w" {:message_type "http://puppetlabs.com"}) nil)))))

(deftest process-message-test
  (with-redefs [puppetlabs.cthun.message/add-hop (fn [msg stage] msg)]
    (testing "It will ignore messages until the the client is associated"
      (is (= (process-message "localhost" "ws" {}) nil)))
    (testing "It will process an association message if the client is not associated"
      (with-redefs [puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "login")
                    puppetlabs.cthun.connection-states/session-association-message? (fn [message-body] true)]
        (is (= (process-message "localhost" "ws" {}) "login"))))
    (testing "It will process a client message"
      (with-redefs [puppetlabs.cthun.connection-states/session-associated? (fn [ws] true)
                    puppetlabs.cthun.connection-states/process-client-message (fn [host ws message-body] "client")]
        (is (= (process-message "localhost" "ws" {:targets ["cth://client1.com/somerole"]}) "client"))))
    (testing "It will process a server message"
      (with-redefs [puppetlabs.cthun.connection-states/session-associated? (fn [ws] true)
                    puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "server")]
        (is (= (process-message "localhost" "ws" {:targets ["cth:///server"]}) "server"))))
    (testing "It will process an expired message"
      (with-redefs [puppetlabs.cthun.connection-states/message-expired? (constantly true)
                    puppetlabs.cthun.connection-states/process-expired-message (constantly :processed-expired)]
        (is (= (process-message "localhost" "ws" {:id "12347890"}) :processed-expired))))))

(deftest process-expired-message-test
  (with-redefs [puppetlabs.cthun.connection-states/process-client-message (fn [_ _ response] response)]
    (testing "It will create and send a ttl expired message"
       (let [msg (puppetlabs.cthun.connection-states/process-expired-message {:id "12347890"
                                                                              :sender "cth://client2.com/tester"})
             msg_data (puppetlabs.cthun.message/get-json-data msg)]
         (is (= "http://puppetlabs.com/ttl_expired" (:message_type msg)))
         (is (= "12347890" (:id msg_data)))))))
