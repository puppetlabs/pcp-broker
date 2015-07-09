(ns puppetlabs.cthun.websockets-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.websockets :refer :all]))

; Private websocket event handler tests

;; TODO(richardc): This isn't the correct place to do this testing.
;; We're testing at a distance that the callbacks for the websocket
;; handles called over into connection-states and then returned
;; something that looked like an updated view of the world.
;; Remove all these once we're sure that are covered by the tests of
;; connection-states.

;; (deftest on-connect!-test
;;   (with-redefs [get-hostname (fn [ws] "localhost")
;;                 ring.adapter.jetty9/idle-timeout! (fn [ws timeout] true)]
;;     (testing "It adds the fresh connection to the connection-map"
;;       (let [connection-map (#'puppetlabs.cthun.websockets/on-connect! "ws")
;;             socket-map (get connection-map "ws")]
;;         (is (= (socket-map :client) "localhost"))
;;         (is (= (socket-map :type) "undefined"))
;;         (is (= (socket-map :status) "connected"))
;;         (is (= nil (socket-map :endpoint)))))))

;; (deftest on-text!-test
;;   (with-redefs [puppetlabs.cthun.validation/validate-message (fn [message] true)
;;                 puppetlabs.cthun.connection-states/process-message (fn [hostname ws message] true)
;;                 get-hostname (fn [ws] "localhost")]
;;     (testing "It processes a client message if the message body is valid"
;;       (is (= (#'puppetlabs.cthun.websockets/on-text! "ws" "message") true))))
;;   (with-redefs [puppetlabs.cthun.validation/validate-message (fn [message] false)
;;                 get-hostname (fn [ws] "localhost")]
;;     (testing "It does not process a client message if the message body is invalid"
;;       (is (= (#'puppetlabs.cthun.websockets/on-text! "ws" "message") nil)))))

;; (deftest on-bytes!-test
;;   (println "on-bytes!-test *** Pending ***"))

;; (deftest on-error-test
;;   (println "on-error-test *** Pending ***"))

;; (deftest on-close!-test
;;   (with-redefs [get-hostname (fn [ws] "localhost")
;;                 ring.adapter.jetty9/idle-timeout! (fn [ws timeout] true)]
;;     (testing "It removes the closed connection from the connection-map and endpoint-map"
;;       (swap! puppetlabs.cthun.connection-states/connection-map {})
;;       (#'puppetlabs.cthun.websockets/on-connect! "ws")
;;       (#'puppetlabs.cthun.websockets/on-close! "ws" 1001 "Remove")
;;       (is (= @puppetlabs.cthun.connection-states/connection-map {})))))

; Public interface tests

(deftest websocket-handlers-test
  (testing "All the handler functions are defined"
    (let [handlers (websocket-handlers)]
      (is (fn? (handlers :on-connect)))
      (is (fn? (handlers :on-error)))
      (is (fn? (handlers :on-close)))
      (is (fn? (handlers :on-text)))
      (is (fn? (handlers :on-bytes))))))

(deftest start-jetty-test
  (with-redefs [puppetlabs.trapperkeeper.services.webserver.jetty9-config/pem-ssl-config->keystore-ssl-config (fn [config] {})
                ring.adapter.jetty9/run-jetty (fn [app arg-map] true)]
    (testing "It starts Jetty"
      (is (= (start-jetty "app" "/cthun" "localhost" 8080 {}) true)))))
