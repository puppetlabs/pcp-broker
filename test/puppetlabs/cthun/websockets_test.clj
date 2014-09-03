(ns puppetlabs.cthun.websockets-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.websockets :refer :all]))

; Private websocket event handler tests

(deftest on-connect!-test
  (with-redefs [get-hostname (fn [ws] "localhost")
                ring.adapter.jetty9/idle-timeout! (fn [ws timeout] true)]
    (testing "It adds the fresh connection to the connection-map"
      (let [connection-map (#'puppetlabs.cthun.websockets/on-connect! "ws")
            host-map (connection-map "localhost")
            socket-map (host-map "ws")]
        (is (= (socket-map :socket-type) "undefined"))
        (is (= (socket-map :status) "connected"))
        (is (= (socket-map :user) "undefined"))
        (is (= (socket-map :endpoint) "undefined"))))))

(deftest on-text!-test
  (with-redefs [puppetlabs.cthun.validation/validate-message (fn [message] true)
                puppetlabs.cthun.connection-states/process-message (fn [hostname ws message] true)
                get-hostname (fn [ws] "localhost")]
    (testing "It processes a client message if the message body is valid"
      (is (= (#'puppetlabs.cthun.websockets/on-text! "ws" "message") true))))
  (with-redefs [puppetlabs.cthun.validation/validate-message (fn [message] false)
                get-hostname (fn [ws] "localhost")]
    (testing "It does not process a client message if the message body is invalid"
      (is (= (#'puppetlabs.cthun.websockets/on-text! "ws" "message") nil)))))

(deftest on-bytes!-test
  (println "on-bytes!-test *** Pending ***"))

(deftest on-error-test
  (println "on-error-test *** Pending ***"))

(deftest on-close!-test
  (with-redefs [get-hostname (fn [ws] "localhost")
                ring.adapter.jetty9/idle-timeout! (fn [ws timeout] true)]
    (testing "It removes the closed connection from the connection-map and endpoint-map"
      (swap! puppetlabs.cthun.connection-states/connection-map {})
      (#'puppetlabs.cthun.websockets/on-connect! "ws")
      (#'puppetlabs.cthun.websockets/on-close! "ws" 1001 "Remove")
      (is (= @puppetlabs.cthun.connection-states/connection-map {})))))

; Public interface tests

(deftest websocket-handlers-test
  (testing "All the handler functions are defined"
    (let [handlers (websocket-handlers)]
      (is (= (type (handlers :on-connect)) puppetlabs.cthun.websockets$on_connect_BANG_))
      (is (= (type (handlers :on-error)) puppetlabs.cthun.websockets$on_error))
      (is (= (type (handlers :on-close)) puppetlabs.cthun.websockets$on_close_BANG_))
      (is (= (type (handlers :on-text)) puppetlabs.cthun.websockets$on_text_BANG_))
      (is (= (type (handlers :on-bytes)) puppetlabs.cthun.websockets$on_bytes_BANG_)))))

(deftest start-jetty-test
  (with-redefs [ring.adapter.jetty9/run-jetty (fn [app arg-map] true)]
    (testing "It starts Jetty"
      (is (= (start-jetty "app" "/cthun" "localhost" 8080 {}) true)))))
