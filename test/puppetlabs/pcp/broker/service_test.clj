(ns puppetlabs.pcp.broker.service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [http.async.client :as http]
            [me.raynes.fs :as fs]
            [puppetlabs.pcp.broker.service :refer [broker-service]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-test-logging with-test-logging-debug]]))

(def broker-config
  "A broker with ssl and own spool"
  {:authorization {:version 1
                   :rules [{:name "allow all"
                            :match-request {:type "regex"
                                            :path "^/.*$"}
                            :allow-unauthenticated true
                            :sort-order 1}]}

   :webserver {:ssl-host "127.0.0.1"
               ;; usual port is 8142.  Here we use 8143 so if we're developing
               ;; we can run a long-running instance and this one for the
               ;; tests.
               :ssl-port 8143
               :client-auth "want"
               :ssl-key "./test-resources/ssl/private_keys/broker.example.com.pem"
               :ssl-cert "./test-resources/ssl/certs/broker.example.com.pem"
               :ssl-ca-cert "./test-resources/ssl/ca/ca_crt.pem"
               :ssl-crl-path "./test-resources/ssl/ca/ca_crl.pem"}

   :web-router-service
   {:puppetlabs.pcp.broker.service/broker-service {:websocket "/pcp"
                                                   :metrics "/"}}

   :metrics {:enabled true}

   :pcp-broker {:broker-spool "test-resources/tmp/spool"
                :accept-consumers 2
                :delivery-consumers 2}})

(defn cleanup-spool-fixture
  "Deletes the broker-spool before each test"
  [f]
  (fs/delete-dir (get-in broker-config [:pcp-broker :broker-spool]))
  (f))

(use-fixtures :each cleanup-spool-fixture)

(deftest it-talks-websockets-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (let [connected (promise)]
      (with-open [client (client/http-client-with-cert "client01.example.com")
                  ws     (http/websocket client
                                         "wss://127.0.0.1:8143/pcp"
                                         :open (fn [ws] (deliver connected true)))]
        (is (= true (deref connected (* 2 1000) false)) "Connected within 2 seconds")))))

(deftest it-expects-ssl-client-auth-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (let [closed (promise)]
      (with-open [client (http/create-client)
                  ws (http/websocket client
                                     "wss://127.0.0.1:8143/pcp"
                                     :close (fn [ws code reason] (deliver closed code)))]
        ;; NOTE(richardc): This test should only check for close-code 4003, but it
        ;; is a little unreliable and so may sometimes yield the close-code 1006 due
        ;; to a race between the client (netty) becoming connected and the server (jetty)
        ;; closing a connected session because we asked it to.
        ;; This failure is more commonly observed when using Linux
        ;; than on OSX, so we suspect the underlying races to be due
        ;; to thread scheduling.
        ;; See the comments of http://tickets.puppetlabs.com/browse/PCP-124 for more.
        (is (contains? #{ 4003 1006 }
                       (deref closed (* 2 1000) false))
            "Disconnected due to no client certificate")))))

(deftest certificate-must-match-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client02.example.com/test" false)]
      (let [response (client/recv! client)]
        (is (= "http://puppetlabs.com/error_message" (:message_type response)))
        (is (re-matches #"Error .*?/identity-invalid.*" (:description (message/get-json-data response))))))))

;; Session association tests
(deftest basic-session-association-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)])))

(deftest second-association-new-connection-closes-first-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [first-client  (client/connect "client01.example.com" "pcp://client01.example.com/test" true)
                second-client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [response (client/recv! first-client)]
        (is (= [4000 "superceded"] response))))))

(deftest second-association-same-connection-should-fail-and-disconnect-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [request (client/make-association-request "pcp://client01.example.com/test")]
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
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["pcp:///server"]
                               :sender "pcp://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["pcp://client01.example.com/test"]}))]
        (client/send! client request)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
          (is (= {:uris ["pcp://client01.example.com/test"]} (message/get-json-data response))))))))

(deftest inventory-node-can-find-itself-wildcard-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["pcp:///server"]
                               :sender "pcp://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["pcp://*/test"]}))]
        (client/send! client request)
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
          (is (= {:uris ["pcp://client01.example.com/test"]} (message/get-json-data response))))))))

(deftest inventory-node-cannot-find-previously-connected-node-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client02.example.com" "pcp://client02.example.com/test" true)])
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [request (-> (message/make-message)
                        (assoc :message_type "http://puppetlabs.com/inventory_request"
                               :targets ["pcp:///server"]
                               :sender "pcp://client01.example.com/test")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data {:query ["pcp://client02.example.com/test"]}))]
        (client/send! client request))
      (let [response (client/recv! client)]
        (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
        (is (= {:uris []} (message/get-json-data response)))))))

;; Message sending
(deftest send-to-self-explicit-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://client01.example.com/test"]
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
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://*/test"]
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
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [sender   (client/connect "client01.example.com" "pcp://client01.example.com/test" true)
                receiver (client/connect "client02.example.com" "pcp://client02.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://client02.example.com/test"]
                               :destination_report true
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! sender message)
        (let [report  (client/recv! sender)
              message (client/recv! receiver)]
          (is (= "http://puppetlabs.com/destination_report" (:message_type report)))
          (is (= {:id (:id message)
                  :targets ["pcp://client02.example.com/test"]}
                 (message/get-json-data report)))
          (is (= "greeting" (:message_type message)))
          (is (= "Hello" (message/get-json-data message))))))))

(deftest send-expired-wildcard-gets-no-expiry-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://client02.example.com/*"]
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! client message)
        (let [response (client/recv! client)]
          ;; Should get no message
          (is (= nil response)))))))

(deftest send-expired-explicit-gets-expiry-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://client02.example.com/test"]
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

(deftest send-disconnect-connect-receive-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    broker-config
    (with-open [client (client/connect "client01.example.com" "pcp://client01.example.com/test" true)]
      (let [message (-> (message/make-message)
                        (assoc :sender "pcp://client01.example.com/test"
                               :targets ["pcp://client02.example.com/test"]
                               :message_type "greeting")
                        (message/set-expiry 3 :seconds)
                        (message/set-json-data "Hello"))]
        (client/send! client message)))
    (with-open [client (client/connect "client02.example.com" "pcp://client02.example.com/test" true)]
      (let [message (client/recv! client)]
        (is (= "Hello" (message/get-json-data message)))))))

(def strict-broker-config
  "A broker that only allows test/sensitive message types from client01"
  (assoc-in broker-config [:authorization :rules]
            [{:name "must be client01 to send test/sensitive"
              :sort-order 400
              :match-request {:type "path"
                              :path "/pcp-broker/send"
                              :query-params {:message_type "test/sensitive"}}
              :allow ["client01.example.com"]}
             {:name "allow all"
              :sort-order 420
              :match-request {:type "path"
                              :path "/pcp-broker/send"}
              :allow-unauthenticated true}]))

(deftest authorization-will-stop-some-fun-test
  (with-app-with-config
    app
    [authorization-service broker-service jetty9-service webrouting-service metrics-service]
    strict-broker-config
    (with-open [client01 (client/connect "client01.example.com" "pcp://client01.example.com/test" true)
                client02 (client/connect "client02.example.com" "pcp://client02.example.com/test" true)]
      (testing "client01 -> client02 should work"
        (let [message (message/make-message :sender "pcp://client01.example.com/test"
                                            :message_type "test/sensitive"
                                            :targets ["pcp://client02.example.com/test"])]
          (client/send! client01 message)
          (let [received (client/recv! client02)]
            (is (= (:id message) (:id received))))))
      (testing "client02 -> client01 should not work"
        (let [message (message/make-message :sender "pcp://client02.example.com/test"
                                            :message_type "test/sensitive"
                                            :targets ["pcp://client01.example.com/test"])]
          (client/send! client02 message)
          (let [received (client/recv! client01)]
            (is (= nil received))))))))
