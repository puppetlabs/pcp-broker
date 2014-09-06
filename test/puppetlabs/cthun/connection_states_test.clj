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
(def find-websockets #'puppetlabs.cthun.connection-states/find-websockets)
(def parse-endpoints #'puppetlabs.cthun.connection-states/parse-endpoints)
(def insert-endpoint! #'puppetlabs.cthun.connection-states/insert-endpoint!)

(def websocket {"localhost" {"test" {"1" "ws1" "2" "ws2"}}})

(deftest find-websockets-test
  (testing "It returns a lazy sequence of websockets on a wildcard search"
    (is (= '("ws1" "ws2")
           (find-websockets ["*" "*" "*"] websocket))))
  (testing "It returns the websocket when passed a full qualified endpoint"
    (is (= "ws1"
           (find-websockets ["localhost" "test" "1"] websocket))))
  (testing "It returns nil when the endpoint cannot be found"
    (is (nil? (find-websockets ["localhost" "not-a-type" "1"] websocket)))))

(deftest parse-endpoints-test
  (testing "It throws an exception on an invalid protocol"
    (is (thrown? Exception (parse-endpoints ["test://*/*/*"] websocket))))
  (testing "It should return a flat sequence"
    (is (= '("ws1" "ws2")
           (parse-endpoints ["cth://*/*/*"] websocket))))
  (testing "It should remove all nil's from the sequence"
    (is (= '()
           (parse-endpoints ["cth://localhost/not-a-type/*"] websocket)))))

(deftest insert-endpoint!-test
  (swap! endpoint-map {})
  (testing "It correctly inserts a new host"
    (insert-endpoint! "cth://localhost/type1/1" "ws1")
    (is (= @endpoint-map
           {"localhost" {"type1" {"1" "ws1"}}})))
  (testing "It correctly inserts a new type"
    (insert-endpoint! "cth://localhost/type2/1" "ws2")
    (is (= @endpoint-map
           {"localhost" {"type1" {"1" "ws1"}
                         "type2" {"1" "ws2"}}})))
  (testing "It correctly inserts a new uid"
    (insert-endpoint! "cth://localhost/type2/2" "ws3")
    (is (= @endpoint-map
           {"localhost" {"type1" {"1" "ws1"}
                         "type2" {"1" "ws2"
                                  "2" "ws3"}}})))
  (testing "It raises an exception if the endpoint already exists"
    (is (thrown? Exception (insert-endpoint! "cth://localhost/type2/2" "ws3"))))
  (swap! endpoint-map {}))

(deftest make-endpoint-string-test
  (testing "It creates a correct endpoint string"
    (is (re-matches #"cth://localhost/controller/.*" (make-endpoint-string "localhost" "controller")))))

(deftest explode-endpoint-test
  (testing "It raises on invalid endpoints"
    (is (thrown? Exception (explode-endpoint "")))
    (is (thrown? Exception (explode-endpoint "cth://not/enough_parts")))
    (is (thrown? Exception (explode-endpoint "cth://just/too/many/parts")))
    (is (thrown? Exception (explode-endpoint "cth://too/many/parts/by/far"))))
  (testing "It returns component chunks"
    (is (= [ "localhost" "agent" "1" ] (explode-endpoint "cth://localhost/agent/1")))
    (is (= [ "localhost" "*" "*" ] (explode-endpoint "cth://localhost/*/*")))
    (is (= [ "*" "agent" "*" ] (explode-endpoint "cth://*/agent/*")))))

(deftest new-socket-test
  (testing "It returns a map that matches represents a new socket"
    (let [socket (new-socket)]
      (is (= (:socket-type socket) "undefined"))
      (is (= (:status socket) "connected"))
      (is (= (:user socket) "undefined"))
      (is (= nil (:endpoint socket)))
      (is (not= nil (ks/datetime? (:created-at socket)))))))

(deftest process-login-message-test
  (with-redefs [puppetlabs.cthun.validation/validate-login-data (fn [data] true)]
    (testing "It should perform a login"
      (add-connection "localhost" "ws")
      (swap! connection-map assoc-in ["localhost" "ws" :created-at] "squirrel")
      (process-login-message "localhost"
                             "ws"
                             {:data {
                                     :type "controller"
                                     :user "testing"
                                     }})
      (let [connection (get-in @connection-map ["localhost" "ws"])]
        (is (= (:socket-type connection) "controller"))
        (is (= (:status connection) "ready"))
        (is (= (:user connection) "testing"))
        (is (= (:created-at connection) "squirrel"))
        (is (re-matches #"cth://localhost/controller/.*" (:endpoint connection))))))


  (testing "It does not allow a login to happen twice on the same socket"
    (with-redefs [puppetlabs.cthun.validation/validate-login-data (fn [data] true)]
      (add-connection "localhost" "ws")
      (process-login-message "localhost"
                             "ws"
                             {:data {
                                     :type "controller"
                                     :user "testing"
                                     }}))
      (is (thrown? Exception  (process-login-message "localhost"
                                                     "ws"
                                                     {:data {
                                                             :type "controller"
                                                             :user "testing"
                                                             }})))))

(deftest process-server-message-test
  (with-redefs [puppetlabs.cthun.connection-states/process-login-message (fn [host ws message-body] true)]
    (testing "It should identify a login message from the data schema"
      (is (= (process-server-message "localhost" "w" {:data_schema "http://puppetlabs.com/loginschema"}) true)))
    (testing "It should not process an unkown type of server message"
      (is (= (process-server-message "localhost" "w" {:data_schema "http://puppetlabs.com"}) nil)))))

(deftest logged-in?-test
    (testing "It returns true if the websocket is logged in"
      (swap! connection-map assoc-in ["localhost" "ws"] {:status "ready"})
      (is (= (logged-in? "localhost" "ws") true)))
    (testing "It returns false if the websocket is not logged in"
      (swap! connection-map assoc-in ["localhost" "ws"] {:status "connected"})))

(deftest login-message?-test
  (testing "It returns true when passed a login type messge"
    (is (= (login-message? {:endpoints ["cth://server"] :data_schema "http://puppetlabs.com/loginschema"}))))
  (testing "It returns false when passed a message of an unknown type"
    (is (= (login-message? {:endpoints ["cth://otherserver"] :data_schema "http://puppetlabs.com/loginschema"})))))

(deftest add-connection-test
  (testing "It should add a connection to the connection map"
    (add-connection "localhost" "ws")
    (is (= (get-in @connection-map ["localhost" "ws" :status]) "connected"))))

(deftest remove-connection-test
  (reset! connection-map {})
  (reset! endpoint-map {})
  (testing "It should remove a connection from the connection map"
    (add-connection "localhost" "ws")
    (remove-connection "localhost" "ws")
    (is (= {} @connection-map)))

  (reset! connection-map {})
  (reset! endpoint-map {})
  (testing "It should remove the connector from the connection map"
    (add-connection "localhost" "ws")
    (insert-endpoint! "cth://localhost/agent/1" "ws")
    ;; should this swap be in insert-endpoint?  It's done as part of
    ;; processs-login-message currently, am only doing it here to
    ;; allow remove-connection to find the endpoint
    (swap! connection-map assoc-in ["localhost" "ws" :endpoint] "cth://localhost/agent/1")
    (remove-connection "localhost" "ws")
    (is (= {} @endpoint-map))))

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
