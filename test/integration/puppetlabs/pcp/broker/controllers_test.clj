(ns puppetlabs.pcp.broker.controllers-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.testutils :refer [dotestseq received?]]
            [puppetlabs.pcp.client :as pcp-client]
            [puppetlabs.pcp.testutils.service :refer [protocol-versions broker-services get-broker get-context]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.testutils.server :as server]
            [puppetlabs.pcp.broker.core :as core]
            [puppetlabs.pcp.broker.inventory :as inventory]
            [puppetlabs.pcp.message-v2 :as message]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]))

(def default-webserver (:webserver puppetlabs.pcp.testutils.service/broker-config))

(def mock-server-config
  {:webserver {:mock-server-1 (assoc default-webserver :ssl-port 58143)
               :mock-server-2 (assoc default-webserver :ssl-port 58144
                                     :ssl-key "./test-resources/ssl/private_keys/controller01.example.com.pem"
                                     :ssl-cert "./test-resources/ssl/certs/controller01.example.com.pem")
               :mock-server-3 (assoc default-webserver :ssl-port 58145
                                     :ssl-key "./test-resources/ssl/private_keys/controller02.example.com.pem"
                                     :ssl-cert "./test-resources/ssl/certs/controller02.example.com.pem")}
   :web-router-service
   {:puppetlabs.pcp.testutils.server/mock-server {:mock-server-1 "/server"
                                                  :mock-server-2 "/server"
                                                  :mock-server-3 "/server"}}})

(def only-broker-config
  (-> puppetlabs.pcp.testutils.service/broker-config
      (assoc
        :webserver {:pcp-broker (assoc default-webserver :default-server true)}
        :pcp-broker {:controller-uris ["wss://localhost:58143/server"]
                     :controller-whitelist ["http://puppetlabs.com/inventory_request"
                                            "greeting"]
                     :controller-disconnection-graceperiod "1s"})))

(def broker-config
  (merge-with merge mock-server-config only-broker-config))

(deftest controller-connection-test
  (let [connected (promise)]
    (with-redefs [server/on-connect (fn [_ ws] (deliver connected true))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (is (deref connected 3000 nil))))))

(def inventory-request (message/make-message
                         {:message_type "http://puppetlabs.com/inventory_request"
                          :data {:query ["pcp://*/*"]}}))

(def agent-cert "client01.example.com")
(def agent2-cert "client02.example.com")
(def agent-uri (str "pcp://" agent-cert "/agent"))

(def agent-request (message/make-message
                     {:message_type "greeting"
                      :target agent-uri
                      :data "Hello"}))

(deftest controller-no-agent-test
  (let [response1 (promise)
        response2 (promise)]
    (with-redefs [server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode inventory-request))
                      (catch Exception e (println "controller-no-agent-test exception:" (.getMessage e)))))
                  server/on-text (fn [_ ws text]
                    (if-not (realized? response1)
                      (do
                        (deliver response1 (message/decode text))
                        (websockets-client/send! ws (message/encode agent-request)))
                      (deliver response2 (message/decode text))))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (let [answer1 (deref response1 3000 nil)]
          (is answer1)
          (is (= "http://puppetlabs.com/inventory_response" (:message_type answer1)))
          (is (= (:id inventory-request) (:in_reply_to answer1)))
          (is (= [] (get-in answer1 [:data :uris]))))

        (let [answer2 (deref response2 3000 nil)]
          (is answer2)
          (is (= "http://puppetlabs.com/error_message" (:message_type answer2)))
          (is (= (:id agent-request) (:in_reply_to answer2)))
          (is (= "Not connected." (:data answer2))))))))

(deftest controller-agent-connected-test
  (let [inventory-response (promise)
        agent-response (promise)]
    (with-redefs [server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode inventory-request))
                      (catch Exception e (println "controller-agent-connected-test exception:" (.getMessage e)))))
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
        (server/wait-for-inbound-connection (get-context app :MockServer))
        (with-open [client (client/connect :certname agent-cert)]
          ;; Verify we get an inventory including the client
          (let [inventory-answer (deref inventory-response 3000 nil)]
            (is inventory-answer)
            (is (= "http://puppetlabs.com/inventory_response" (:message_type inventory-answer)))
            (is (= (:id inventory-request) (:in_reply_to inventory-answer)))
            (is (= [agent-uri] (get-in inventory-answer [:data :uris]))))

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
            (let [agent-answer (deref agent-response 1000 nil)]
              (is agent-answer)
              (is (= "greeting" (:message_type agent-answer))))))))))

(def self-request (message/make-message
                    {:message_type "loopy"
                     :target "pcp://localhost:58143/server"}))

(deftest controller-whitelist-test
  (let [response (promise)]
    (with-redefs [server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode self-request))
                      (catch Exception e (println "controller-whitelist-test exception:" (.getMessage e)))))
                  server/on-text (fn [_ ws text] (deliver response (message/decode text)))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (let [answer (deref response 3000 nil)]
          (is answer)
          (is (= "http://puppetlabs.com/error_message" (:message_type answer)))
          (is (= (:id self-request) (:in_reply_to answer)))
          (is (= "Message not authorized." (:data answer))))))))

(def spoof-sender-request (message/make-message
                            {:message_type "greeting"
                             :sender "pcp://other/server"
                             :target "pcp://localhost:58143/server"}))

(deftest controller-prevent-spoofed-sender-test
  (let [response (promise)]
    (with-redefs [server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode spoof-sender-request))
                      (catch Exception e (println "controller-prevent-spoofed-sender-test exception:" (.getMessage e)))))
                  server/on-text (fn [_ ws text] (deliver response (message/decode text)))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (let [answer (deref response 3000 nil)]
          (is answer)
          (is (= "http://puppetlabs.com/error_message" (:message_type answer)))
          (is (= (:id spoof-sender-request) (:in_reply_to answer)))
          (is (= "Message not authenticated." (:data answer))))))))

(deftest multiple-controllers-test
  (let [responses {:mock-server-1 [(promise) (promise)]
                   :mock-server-2 [(promise) (promise)]
                   :mock-server-3 [(promise) (promise)]}]
    (with-redefs [server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode inventory-request))
                      (catch Exception e (println "multiple-controllers-test exception:" (.getMessage e)))))
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
          (testing (str "connected to " server ", inventory response received, and agent unreachable")
            (let [[response1 response2] (get responses server)]
              (let [answer (deref response1 3000 nil)]
                (is answer)
                (is (= "http://puppetlabs.com/inventory_response" (:message_type answer)))
                (is (= (:id inventory-request) (:in_reply_to answer)))
                (is (= [] (get-in answer [:data :uris]))))

              (let [answer (deref response2 3000 nil)]
                (is answer)
                (is (= "http://puppetlabs.com/error_message" (:message_type answer)))
                (is (= (:id agent-request) (:in_reply_to answer)))
                (is (= "Not connected." (:data answer)))))))))))

(def inventory-subscribe
  (client/make-message
    {:message_type "http://puppetlabs.com/inventory_request"
     :data {:query ["pcp://*/agent"]
            :subscribe true}}))

(deftest controllers-subscribe
  (let [inventory-response (promise)
        inventory-update (atom (promise))]
    (with-redefs [puppetlabs.pcp.broker.inventory/batch-update-interval-ms 10
                  server/on-connect (fn [_ ws]
                    (try
                      (websockets-client/send! ws (message/encode inventory-subscribe))
                      (catch Exception e (println "controllers-subscribe exception:" (.getMessage e)))))
                  server/on-text (fn [_ ws text]
                    (let [msg (message/decode text)]
                      (case (:message_type msg)
                        "http://puppetlabs.com/inventory_response" (deliver inventory-response msg)
                        "http://puppetlabs.com/inventory_update" (deliver @inventory-update msg))))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (let [answer (deref inventory-response 3000 nil)]
          (is answer)
          (is (= (:id inventory-subscribe) (:in_reply_to answer)))
          (is (= [] (get-in answer [:data :uris]))))

        (with-open [client (client/connect :certname agent-cert)]
          (let [update (deref @inventory-update 3000 nil)]
            (is update)
            (reset! inventory-update (promise))
            (is (= [{:client agent-uri :change 1}] (:changes (client/get-data update))))))

        (let [update (deref @inventory-update 3000 nil)]
          (is update)
          (is (= [{:client agent-uri :change -1}] (:changes (client/get-data update)))))))))

(deftest controllers-unsubscribe
  (let [server-message-sent (atom 0)
        connect pcp-client/connect
        server-messages-at-unsubscribe (promise)
        clients-purged? (promise)
        controller-timeout? (promise)
        forget-controller-subscription core/forget-controller-subscription
        removed-subscription (promise)
        deliver-server-message puppetlabs.pcp.broker.shared/deliver-server-message
        maybe-purge-clients! core/maybe-purge-clients!
        send-updates inventory/send-updates
        first-update-sent? (promise)
        inventory-response (promise)]
    (with-redefs
      [pcp-client/connect #(connect (assoc %1 :retry? false) %2)
       core/maybe-purge-clients! (fn [b t]
                                   @controller-timeout?
                                   (maybe-purge-clients! b t)
                                   (deliver clients-purged? true))
       inventory/send-updates (fn [broker]
                                (when (or (not (empty? (:inventory @(:database broker))))
                                          (realized? first-update-sent?))
                                  (send-updates broker)
                                  (deliver first-update-sent? true)))
       core/forget-controller-subscription (fn [b u p c]
                                             (forget-controller-subscription b u p c)
                                             (deliver removed-subscription true)
                                             (deliver server-messages-at-unsubscribe
                                                      @server-message-sent))
       puppetlabs.pcp.broker.shared/deliver-server-message (fn [b m c]
                                                             (swap! server-message-sent inc)
                                                             (deliver-server-message b m c))
       server/on-connect (fn [_ ws]
                           ;; Only send subscribe the first time we connect
                           (when (zero? @server-message-sent)
                             (try
                               (websockets-client/send! ws (message/encode inventory-subscribe))
                               (catch Exception e (println "controllers-unsubscribe exception:" (.getMessage e))))))
       server/on-text (fn [_ ws text] (deliver inventory-response (message/decode text)))]
      (with-app-with-config app (conj broker-services server/mock-server) broker-config
        (let [broker (:broker (get-context app :BrokerService))]
          (server/wait-for-inbound-connection (get-context app :MockServer))
          (with-open [client (client/connect :certname agent-cert)]
            (while (empty? (:inventory @(:database broker)))
              (Thread/sleep 100))
            (let [answer (deref inventory-response 3000 nil)]
              (is answer)
              (is (= (:id inventory-subscribe) (:in_reply_to answer))))
            (is (deref first-update-sent? 3000 nil))
            ;; Disconnect the mock server
            (doseq [ws @(:inventory (get-context app :MockServer))]
              (websockets-client/close! ws))
            (is (deref removed-subscription 3000 nil))
            (deliver controller-timeout? true)
            (testing "updates for disconnected controllers are discarded, not sent"
              (is @clients-purged?)
              (is (not (empty? (:updates @(:database broker)))))
              (inventory/send-updates (get-broker app))
              (is (empty? (:updates @(:database broker))))
              (is (= @server-messages-at-unsubscribe @server-message-sent)))))))))

(deftest controller-disconnection
    (let [timed-out? (promise)]
      (with-redefs [core/schedule-client-purge! (fn [b t p]
                                                  @timed-out?
                                                  (core/maybe-purge-clients! b t))
                    puppetlabs.pcp.broker.inventory/start-inventory-updates! (fn [_] nil)]
        (with-app-with-config app broker-services only-broker-config
          (let [{:keys [broker]} (get-context app :BrokerService)
                database (:database broker)]
            (testing "client connections disallowed when no controller is connected"
              (with-open [client (client/connect :certname agent-cert)]
                (is (received? (client/recv! client) [1011 "All controllers disconnected."]))))
            (testing "broker state is error on start (no controllers connected)"
              (is (= :error (:state (core/status broker :info)))))
            (testing "controller disconnection"
              (with-app-with-config mock-server server/mock-server-services mock-server-config
                (server/wait-for-inbound-connection (get-context mock-server :MockServer))
                (testing "broker in running state"
                  (= :running (:state (core/status broker :info))))
                (with-open [client (client/connect :certname agent-cert)]
                  (while (empty? (:inventory @database))
                    (Thread/sleep 100))
                  (let [controller-inventory (:inventory (get-context mock-server :MockServer))
                        controller-ws (first @controller-inventory)
                        [_ client-connection] (first (:inventory @database))]
                    (is (= 0 (count (:warning-bin @database))))
                    (testing "closing a controller connection puts a controller in the warning bin"
                      (websockets-client/close! controller-ws)
                      (Thread/sleep 100)
                      (is (= 1 (count (:warning-bin @database)))))
                    (testing "clients connected until timeout is reached"
                      (is (websockets-client/connected? (:websocket client-connection)))
                      (testing "new connections are rejected while controllers in the bin"
                        (with-open [client' (client/connect :certname agent2-cert)]
                          (is (received? (client/recv! client')
                                         [1011 "All controllers disconnected."]))))
                      (deliver timed-out? true)
                      (is (received? (client/recv! client)
                                     [1011 "All controllers disconnected."]))
                      (is (not (websockets-client/connected? (:websocket client-connection)))))
                    (testing "warning bin contains one element"
                      (is (= 1 (count (:warning-bin @database)))))))))


            (testing "returns error state when brokers are disconnected"
              (= :error (:state (core/status broker :info))))

            (testing "controller reconnection"
              (with-app-with-config mock-server server/mock-server-services mock-server-config
                (server/wait-for-inbound-connection (get-context mock-server :MockServer))
                (with-open [client (client/connect :certname agent-cert)]
                  (testing "client connections now successful"
                    (while (empty? (:inventory @database))
                      (Thread/sleep 100))
                    (testing "returns running state when brokers are disconnected"
                      (= :running (:state (core/status broker :info))))
                    (is (not (empty? (:inventory @database)))))))))))))

(deftest status-with-controller
  (let [start-fn core/start
        stop-fn core/stop]
    (with-redefs [core/start (fn [b]
                               (is (= :starting (:state (core/status b :info))))
                               (start-fn b))
                  core/stop (fn [b]
                              (let [r (stop-fn b)]
                                (is (= :stopping (:state (core/status b :info))))
                                r))]
      (with-app-with-config app broker-services only-broker-config
        (let [{:keys [broker]} (get-context app :BrokerService)]
          (is (= :error (:state (core/status broker :info))))
          (with-app-with-config mock-server server/mock-server-services mock-server-config
            (server/wait-for-inbound-connection (get-context mock-server :MockServer))
            (is (= :running (:state (core/status broker :info))))))))))
