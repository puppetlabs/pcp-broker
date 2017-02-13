(ns puppetlabs.pcp.broker.controllers-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.broker.service :refer [broker-service]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.testutils.server :as server]
            [puppetlabs.pcp.message-v2 :as message]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-level]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]))

(def default-webserver
  {:ssl-host "127.0.0.1"
   ;; usual port is 8142.  Here we use 58142 so if we're developing
   ;; we can run a long-running instance and this one for the
   ;; tests.
   :ssl-port 58142
   :client-auth "want"
   :ssl-key "./test-resources/ssl/private_keys/broker.example.com.pem"
   :ssl-cert "./test-resources/ssl/certs/broker.example.com.pem"
   :ssl-ca-cert "./test-resources/ssl/ca/ca_crt.pem"
   :ssl-crl-path "./test-resources/ssl/ca/ca_crl.pem"})

(def broker-config
  "A broker with ssl"
  {:authorization {:version 1
                   :rules [{:name "allow all"
                            :match-request {:type "regex"
                                            :path "^/.*$"}
                            :allow-unauthenticated true
                            :sort-order 1}]}

   :webserver {
     :pcp-broker (assoc default-webserver :default-server true)
     :mock-server-1 (assoc default-webserver :ssl-port 58143)
     :mock-server-2 (assoc default-webserver :ssl-port 58144
                                             :ssl-key "./test-resources/ssl/private_keys/controller01.example.com.pem"
                                             :ssl-cert "./test-resources/ssl/certs/controller01.example.com.pem")
     :mock-server-3 (assoc default-webserver :ssl-port 58145
                                             :ssl-key "./test-resources/ssl/private_keys/controller02.example.com.pem"
                                             :ssl-cert "./test-resources/ssl/certs/controller02.example.com.pem")}

   :web-router-service
   {:puppetlabs.pcp.broker.service/broker-service {:v1 "/pcp/v1.0"
                                                   :v2 "/pcp/v2.0"}
    :puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"
    :puppetlabs.pcp.testutils.server/mock-server {:mock-server-1 "/server"
                                                  :mock-server-2 "/server"
                                                  :mock-server-3 "/server"}}

   :pcp-broker {:controller-uris ["wss://localhost:58143/server"]
                :controller-whitelist ["http://puppetlabs.com/inventory_request"
                                       "greeting"]}

   :metrics {:enabled true
             :server-id "localhost"}})

(def broker-services
  "The trapperkeeper services the broker needs"
  [authorization-service broker-service jetty9-service webrouting-service metrics-service status-service scheduler-service])

(deftest controller-connection-test
  (let [connected (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (deliver connected true))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (is (deref connected 3000 nil))))))

(def inventory-request (message/make-message
                         {:message_type "http://puppetlabs.com/inventory_request"
                          :data {:query ["pcp://*/*"]}}))

(def agent-cert "client01.example.com")
(def agent-uri (str "pcp://" agent-cert "/agent"))

(def agent-request (message/make-message
                     {:message_type "greeting"
                      :target agent-uri
                      :data "Hello"}))

(deftest controller-no-agent-test
  (let [response1 (promise)
        response2 (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (websockets-client/send! ws (message/encode inventory-request)))
                  server/on-text (fn [_ ws text]
                    (if-not (realized? response1)
                      (do
                        (deliver response1 (message/decode text))
                        (websockets-client/send! ws (message/encode agent-request)))
                      (deliver response2 (message/decode text))))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (is (deref response1 3000 nil))
        (is (= "http://puppetlabs.com/inventory_response" (:message_type @response1)))
        (is (= (:id inventory-request) (:in_reply_to @response1)))
        (is (= [] (get-in @response1 [:data :uris])))

        (is (deref response2 3000 nil))
        (is (= "http://puppetlabs.com/error_message" (:message_type @response2)))
        (is (= (:id agent-request) (:in_reply_to @response2)))
        (is (= "not connected" (:data @response2)))))))

(deftest controller-agent-connected-test
  (let [inventory-response (promise)
        agent-response (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (websockets-client/send! ws (message/encode inventory-request)))
                  server/on-text (fn [_ ws text]
                    (let [msg (message/decode text)]
                      (if (= (:message_type msg) "http://puppetlabs.com/inventory_response")
                        ;; Wait for the client to appear in inventory
                        (if (empty? (get-in msg [:data :uris]))
                          (do
                            (Thread/sleep 100)
                            (websockets-client/send! ws (message/encode inventory-request)))
                          (do
                            (deliver inventory-response msg)
                            (websockets-client/send! ws (message/encode agent-request))))
                        (deliver agent-response msg))))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (with-open [client (client/connect :certname agent-cert)]
          ;; Verify we get an inventory including the client
          (is (deref inventory-response 3000 nil))
          (is (= "http://puppetlabs.com/inventory_response" (:message_type @inventory-response)))
          (is (= (:id inventory-request) (:in_reply_to @inventory-response)))
          (is (= [agent-uri] (get-in @inventory-response [:data :uris])))

          (let [response (client/recv! client)
                target (:target response)
                sender (:sender response)]
            ;; Verify message from controller reaches client
            (is (= "greeting" (:message_type response)))
            (is (= (:id agent-request) (:id response)))
            (is (= "Hello" (:data response)))
            (is (= "pcp://localhost:58143/server" sender))
            (is (= agent-uri target))

            ;; Verify message from client reaches controller
            (client/send! client (assoc agent-request :target sender :sender target))
            (is (deref agent-response 1000 nil))
            (is (= "greeting" (:message_type @agent-response)))))))))

(def self-request (message/make-message
                    {:message_type "loopy"
                     :target "pcp://localhost:58143/server"}))

(deftest controller-whitelist-test
  (let [response (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (websockets-client/send! ws (message/encode self-request)))
                  server/on-text (fn [_ ws text] (deliver response (message/decode text)))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (is (deref response 3000 nil))
        (is (= "http://puppetlabs.com/error_message" (:message_type @response)))
        (is (= (:id self-request) (:in_reply_to @response)))
        (is (= "Message not authorized" (:data @response)))))))

(def spoof-sender-request (message/make-message
                            {:message_type "greeting"
                             :sender "pcp://other/server"
                             :target "pcp://localhost:58143/server"}))

(deftest controller-prevent-spoofed-sender-test
  (let [response (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (websockets-client/send! ws (message/encode spoof-sender-request)))
                  server/on-text (fn [_ ws text] (deliver response (message/decode text)))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (is (deref response 3000 nil))
        (is (= "http://puppetlabs.com/error_message" (:message_type @response)))
        (is (= (:id spoof-sender-request) (:in_reply_to @response)))
        (is (= "Message not authenticated" (:data @response)))))))

(deftest multiple-controllers-test
  (let [responses {:mock-server-1 [(promise) (promise)]
                   :mock-server-2 [(promise) (promise)]
                   :mock-server-3 [(promise) (promise)]}]
    (with-redefs [server/on-connect (fn [server ws]
                    (websockets-client/send! ws (message/encode inventory-request)))
                  server/on-text (fn [server ws text]
                    (let [[response1 response2] (get responses server)]
                      (if-not (realized? response1)
                        (do
                          (deliver response1 (message/decode text))
                          (websockets-client/send! ws (message/encode agent-request)))
                        (deliver response2 (message/decode text)))))]
      (with-app-with-config app (conj broker-services server/mock-server)
        (assoc-in broker-config [:pcp-broker :controller-uris] ["wss://localhost:58143/server"
                                                                "wss://localhost:58144/server"
                                                                "wss://localhost:58145/server"])
        (doseq [server [:mock-server-1 :mock-server-2 :mock-server-3]]
          (let [[response1 response2] (get responses server)]
            (is (deref response1 3000 nil))
            (is (= "http://puppetlabs.com/inventory_response" (:message_type @response1)))
            (is (= (:id inventory-request) (:in_reply_to @response1)))
            (is (= [] (get-in @response1 [:data :uris])))

            (is (deref response2 3000 nil))
            (is (= "http://puppetlabs.com/error_message" (:message_type @response2)))
            (is (= (:id agent-request) (:in_reply_to @response2)))
            (is (= "not connected" (:data @response2)))))))))
