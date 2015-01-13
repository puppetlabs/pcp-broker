(ns puppetlabs.cthun.connection-states-test
  (require [clojure.test :refer :all]
           [puppetlabs.cthun.connection-states :refer :all]
           [puppetlabs.kitchensink.core :as ks]))


; private symbols
(def make-endpoint-string #'puppetlabs.cthun.connection-states/make-endpoint-string)
;(def new-socket #'puppetlabs.cthun.connection-states/new-socket)
(def process-login-message #'puppetlabs.cthun.connection-states/process-login-message)
(def process-server-message  #'puppetlabs.cthun.connection-states/process-server-message)
(def logged-in?  #'puppetlabs.cthun.connection-states/logged-in?)
(def login-message?  #'puppetlabs.cthun.connection-states/login-message?)

(def websocket {"localhost" {"test" {"1" "ws1" "2" "ws2"}}})


(deftest make-endpoint-string-test
  (testing "It creates a correct endpoint string"
    (is (= "cth://localhost/controller" (make-endpoint-string "localhost" "controller")))))

(deftest new-socket-test
  (testing "It returns a map that matches represents a new socket"
    (let [socket (new-socket "localhost")]
      (is (= (:client socket) "localhost"))
      (is (= (:type socket) "undefined"))
      (is (= (:status socket) "connected"))
      (is (= nil (:endpoint socket)))
      (is (not= nil (ks/datetime? (:created-at socket)))))))

(deftest process-login-message-test
  (with-redefs [puppetlabs.cthun.validation/validate-login-data (fn [data] true)]
    (testing "It should perform a login"
      (add-connection "localhost" "ws")
      (reset! inventory {:record-client (fn [endpoint])})
      (swap! connection-map assoc-in ["ws" :created-at] "squirrel")
      (process-login-message "localhost"
                             "ws"
                             {:data { :type "controller" }})
      (let [connection (get @connection-map "ws")]
        (is (= (:client connection) "localhost"))
        (is (= (:type connection) "controller"))
        (is (= (:status connection) "ready"))
        (is (= (:created-at connection) "squirrel"))
        (is (= "cth://localhost/controller" (:endpoint connection))))))


  (testing "It does not allow a login to happen twice on the same socket"
    (with-redefs [puppetlabs.cthun.validation/validate-login-data (fn [data] true)]
      (add-connection "localhost" "ws")
      (process-login-message "localhost"
                             "ws"
                             {:data {:type "controller"}}))
      (is (thrown? Exception  (process-login-message "localhost"
                                                     "ws"
                                                     {:data {:type "controller"}})))))

(deftest websockets-for-endpoints-test
  (reset! endpoint-map {"cth://bill/agent" "ws1"
                        "cth://bob/agent" "ws2"
                        "cth://eric/controller" "ws3"
                        "cth://bob/controller" "ws4"})
  (testing "it finds a single websocket explictly"
    (is (= '("ws1")
           (websockets-for-endpoints ["cth://bill/agent"]))))
  (testing "it finds  multiple things when asked"
    (is (= '("ws2" "ws4")
           (sort (websockets-for-endpoints ["cth://bob/agent" "cth://bob/controller"])))))
  (testing "it finds nothing by wildcard"
    (is (= '() (websockets-for-endpoints ["cth://*/agent"]))))
  (testing "It returns an empty list when the endpoint cannot be found"
    (is (= '() (websockets-for-endpoints ["cth://bob/nonsuch"])))))

(deftest process-server-message-test
  (with-redefs [puppetlabs.cthun.connection-states/process-login-message (fn [host ws message-body] true)]
    (testing "It should identify a login message from the data schema"
      (is (= (process-server-message "localhost" "w" {:data_schema "http://puppetlabs.com/loginschema"}) true)))
    (testing "It should not process an unkown type of server message"
      (is (= (process-server-message "localhost" "w" {:data_schema "http://puppetlabs.com"}) nil)))))

(deftest logged-in?-test
    (testing "It returns true if the websocket is logged in"
      (swap! connection-map assoc "ws" {:status "ready"})
      (is (= (logged-in? "localhost" "ws") true)))
    (testing "It returns false if the websocket is not logged in"
      (swap! connection-map assoc "ws" {:status "connected"})
      (is (= false (logged-in? "localhost" "ws")))))

(deftest login-message?-test
  (testing "It returns true when passed a login type messge"
    (is (= true (login-message? {:endpoints ["cth://server"] :data_schema "http://puppetlabs.com/loginschema"}))))
  (testing "It returns false when passed a message of an unknown type"
    (is (= false (login-message? {:endpoints ["cth://server"] :data_schema "http://puppetlabs.com/kennylogginsschema"}))))
  (testing "It returns false when passed a message not aimed to the server target"
    (is (= false (login-message? {:endpoints ["cth://otherserver"] :data_schema "http://puppetlabs.com/loginschema"})))))

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

(deftest process-message-test
  (testing "It will ignore messages until the the client is logged in"
    (is (= (process-message "localhost" "ws" {}) nil)))
  (testing "It will process a login message if the client is not logged in"
    (with-redefs [puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "login")
                  puppetlabs.cthun.connection-states/login-message? (fn [message-body] true)]
      (is (= (process-message "localhost" "ws" {}) "login"))))
  (testing "It will process a client message"
    (with-redefs [puppetlabs.cthun.connection-states/logged-in? (fn [host ws] true)
                  puppetlabs.cthun.connection-states/process-client-message (fn [host ws message-body] "client")]
      (is (= (process-message "localhost" "ws" {:endpoints ["cth://client1.com"]}) "client"))))
  (testing "It will process a server message"
    (with-redefs [puppetlabs.cthun.connection-states/logged-in? (fn [host ws] true)
                  puppetlabs.cthun.connection-states/process-server-message (fn [host ws message-body] "server")]
      (is (= (process-message "localhost" "ws" {:endpoints ["cth://server"]}) "server")))))
