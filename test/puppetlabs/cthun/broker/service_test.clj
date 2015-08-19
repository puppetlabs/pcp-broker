(ns puppetlabs.cthun.broker.service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [http.async.client :as http]
            [me.raynes.fs :as fs]
            [puppetlabs.cthun.broker.service :refer [broker-service]]
            [puppetlabs.cthun.testutils.client :as client]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-test-logging with-test-logging-debug]]))

(def broker-config
  "A broker with ssl and own spool"
  {:webserver {:ssl-host "127.0.0.1"
               :ssl-port 8081
               :client-auth "want"
               :ssl-key "./test-resources/ssl/private_keys/broker.example.com.pem"
               :ssl-cert "./test-resources/ssl/certs/broker.example.com.pem"
               :ssl-ca-cert "./test-resources/ssl/ca/ca_crt.pem"
               :ssl-crl-path "./test-resources/ssl/ca/ca_crl.pem"}

   :web-router-service
   {:puppetlabs.cthun.broker.service/broker-service {:websocket "/cthun"
                                                     :metrics "/"}}

   :metrics {:enabled true}

   :cthun {:broker-spool "test-resources/tmp/spool"
           :accept-consumers 2
           :delivery-consumers 2}})

(defn cleanup-spool-fixture
  "Deletes the broker-spool before each test"
  [f]
  (fs/delete-dir (get-in broker-config [:cthun :broker-spool]))
  (f))

(use-fixtures :each cleanup-spool-fixture)

(deftest it-talks-websockets-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (let [connected (promise)]
      (with-open [client (client/http-client-with-cert "client01.example.com")
                  ws     (http/websocket client
                                         "wss://127.0.0.1:8081/cthun"
                                         :open (fn [ws] (deliver connected true)))]
        (is (= true (deref connected (* 2 1000) false)) "Connected within 2 seconds")))))

(deftest certificate-must-match-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client02.example.com/test" false)]
      (let [response (client/recv! client)]
        (is (= "http://puppetlabs.com/error_message" (:message_type response)))
        (is (re-matches #"Error .*?/identity-invalid.*" (:description (message/get-json-data response))))))))

;; Session association tests
(deftest basic-session-association-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)])))

(deftest second-association-new-connection-closes-first-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [first-client  (client/connect "client01.example.com" "cth://client01.example.com/test" true)
                second-client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [response (client/recv! first-client)]
        (is (= [4000 "superceded"] response))))))

(deftest second-association-same-connection-should-fail-and-disconnect-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [request (client/make-association-request "cth://client01.example.com/test")]
        (client/send! client request)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/associate_response" (:message_type response)))
          (is (= {:success false
                  :reason "session already associated"
                  :id (:id request)}
                 (message/get-json-data response))))
        (let [response (client/recv! client)]
          (is (= [4002 "association unsuccessful"] response)))))))

;; Inventory service
(deftest inventory-node-can-find-itself-explicit-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["cth:///server"]
                               :sender "cth://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["cth://client01.example.com/test"]}))]
        (client/send! client request)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
          (is (= {:uris ["cth://client01.example.com/test"]} (message/get-json-data response))))))))

(deftest inventory-node-can-find-itself-wildcard-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["cth:///server"]
                               :sender "cth://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["cth://*/test"]}))]
        (client/send! client request)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
          (is (= {:uris ["cth://client01.example.com/test"]} (message/get-json-data response))))))))

(deftest inventory-node-can-find-previously-connected-node-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client02.example.com" "cth://client02.example.com/test" true)])
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["cth:///server"]
                               :sender "cth://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["cth://client02.example.com/test"]}))]
        (client/send! client request))
      (let [response (client/recv! client)]
        (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
        (is (= {:uris ["cth://client02.example.com/test"]} (message/get-json-data response)))))))

;; Message sending
(deftest send-to-self-explicit-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :sender "cth://client01.example.com/test"
                               :targets ["cth://client01.example.com/test"]
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! client message)
        (let [message (client/recv! client)]
          (is (= "greeting" (:message_type message)))
          (is (= "Hello" (message/get-json-data message))))))))

(deftest send-to-self-wildcard-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :sender "cth://client01.example.com/test"
                               :targets ["cth://*/test"]
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! client message)
        (let [message (client/recv! client)]
          (is (= "greeting" (:message_type message)))
          (is (= "Hello" (message/get-json-data message))))))))

(deftest send-with-destination-report-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [sender   (client/connect "client01.example.com" "cth://client01.example.com/test" true)
                receiver (client/connect "client02.example.com" "cth://client02.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :sender "cth://client01.example.com/test"
                               :targets ["cth://client02.example.com/test"]
                               :destination_report true
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! sender message)
        (let [report  (client/recv! sender)
              message (client/recv! receiver)]
          (is (= "http://puppetlabs.com/destination_report" (:message_type report)))
          (is (= {:id (:id message)
                  :targets ["cth://client02.example.com/test"]}
                 (message/get-json-data report)))
          (is (= "greeting" (:message_type message)))
          (is (= "Hello" (message/get-json-data message))))))))

(deftest send-expired-gets-expiry-test
  (with-app-with-config
    app
    [broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    ;; TODO(richardc) - check in with the specs.  Currently as
    ;; implemented if you send a message, non-wildcarded, to an
    ;; identity that has never existed, we drop the message, so
    ;; here we fluff the inventory with a connect
    (with-open [client (client/connect "client02.example.com" "cth://client02.example.com/test" true)])

    (with-open [client (client/connect "client01.example.com" "cth://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :id (ks/uuid)
                               :sender "cth://client01.example.com/test"
                               :targets ["cth://client02.example.com/test"]
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! client message)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/ttl_expired" (:message_type response)))
          ;; TODO(richardc): should we say for whom we expired,
          ;; in case we only expire for 1 of the expanded
          ;; destinations
          (is (= {:id (:id message)} (message/get-json-data response))))))))
