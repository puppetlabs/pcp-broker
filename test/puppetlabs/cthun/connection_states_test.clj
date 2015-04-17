(ns puppetlabs.cthun.connection-states-test
  (require [clojure.test :refer :all]
           [puppetlabs.cthun.message :as message]
           [puppetlabs.cthun.connection-states :refer :all]
           [puppetlabs.kitchensink.core :as ks]))


; private symbols
(def make-uri #'puppetlabs.cthun.connection-states/make-uri)
(def process-login-message #'puppetlabs.cthun.connection-states/process-login-message)
(def process-server-message  #'puppetlabs.cthun.connection-states/process-server-message)
(def logged-in?  #'puppetlabs.cthun.connection-states/logged-in?)
(def login-message?  #'puppetlabs.cthun.connection-states/login-message?)

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
      (is (= (:type socket) "undefined"))
      (is (= (:status socket) "connected"))
      (is (= nil (:endpoint socket)))
      (is (not= nil (ks/datetime? (:created-at socket)))))))

(deftest logged-in?-test
    (testing "It returns true if the websocket is logged in"
      (swap! connection-map assoc "ws" {:status "ready"})
      (is (= (logged-in? "localhost" "ws") true)))
    (testing "It returns false if the websocket is not logged in"
      (swap! connection-map assoc "ws" {:status "connected"})
      (is (= false (logged-in? "localhost" "ws")))))


(deftest login-message?-test
  (testing "It returns true when passed a login type messge"
    (is (= true (login-message? {:targets ["cth:///server"] :message_type "http://puppetlabs.com/loginschema"}))))
  (testing "It returns false when passed a message of an unknown type"
    (is (= false (login-message? {:targets ["cth:///server"] :message_type "http://puppetlabs.com/kennylogginsschema"}))))
  (testing "It returns false when passed a message not aimed to the server target"
    (is (= false (login-message? {:targets ["cth://other/server"] :message_type "http://puppetlabs.com/loginschema"})))))

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

(deftest process-login-message-test
  (with-redefs [puppetlabs.cthun.validation/validate-login-data (fn [data] true)
                ring.adapter.jetty9/close! (fn [ws] false)]
    (let [login-message (message/set-json-data (message/make-message) {:type "controller"})]
      (testing "It should perform a login"
        (reset! uri-map {})
        (add-connection "localhost" "ws")
        (reset! inventory {:record-client (fn [endpoint])})
        (swap! connection-map assoc-in ["ws" :created-at] "squirrel")
        (process-login-message "localhost" "ws" login-message)
        (let [connection (get @connection-map "ws")]
          (is (= (:client connection) "localhost"))
          (is (= (:type connection) "controller"))
          (is (= (:status connection) "ready"))
          (is (= (:created-at connection) "squirrel"))
          (is (= "cth://localhost/controller" (:uri connection)))))

      (testing "It does not allow a login to happen from two locations for the same uri"
        (reset! uri-map {})
        (reset! connection-map {})
        (add-connection "localhost" "ws1")
        (add-connection "localhost" "ws2")
        (process-login-message "localhost" "ws1" login-message)
        (is (not (process-login-message "localhost" "ws2" login-message))))

      (testing "It does not allow a login to happen twice on the same websocket"
        (reset! uri-map {})
        (reset! connection-map {})
        (add-connection "localhost" "ws")
        (process-login-message "localhost" "ws" login-message))
      (is (not (process-login-message "localhost" "ws" login-message))))))

(deftest process-server-message-test
  (with-redefs [puppetlabs.cthun.connection-states/process-login-message (fn [host ws message-body] true)]
    (testing "It should identify a login message from the data schema"
      (is (= (process-server-message "localhost" "w" {:message_type "http://puppetlabs.com/loginschema"}) true)))
    (testing "It should not process an unkown type of server message"
      (is (= (process-server-message "localhost" "w" {:message_type "http://puppetlabs.com"}) nil)))))

(deftest process-message-test
  (with-redefs [puppetlabs.cthun.message/add-hop (fn [msg stage] msg)]
    (testing "It will ignore messages until the the client is logged in"
      (is (= (process-message "localhost" "ws" {}) nil)))
    (testing "It will process a login message if the client is not logged in"
      (with-redefs [puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "login")
                    puppetlabs.cthun.connection-states/login-message? (fn [message-body] true)]
        (is (= (process-message "localhost" "ws" {}) "login"))))
    (testing "It will process a client message"
      (with-redefs [puppetlabs.cthun.connection-states/logged-in? (fn [host ws] true)
                    puppetlabs.cthun.connection-states/process-client-message (fn [host ws message-body] "client")]
        (is (= (process-message "localhost" "ws" {:targets ["cth://client1.com/somerole"]}) "client"))))
    (testing "It will process a server message"
      (with-redefs [puppetlabs.cthun.connection-states/logged-in? (fn [host ws] true)
                    puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "server")]
        (is (= (process-message "localhost" "ws" {:targets ["cth:///server"]}) "server"))))))
