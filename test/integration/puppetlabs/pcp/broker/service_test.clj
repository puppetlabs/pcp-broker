(ns puppetlabs.pcp.broker.service-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.pcp.testutils :refer [dotestseq received? retry-until-true]]
            [puppetlabs.pcp.testutils.service :refer [broker-config protocol-versions broker-services]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.message-v1 :as m1]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.websocket-session :as ws-session]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]))

(deftest it-talks-websockets-test
  (with-app-with-config app broker-services broker-config
    (let [connected (promise)
          handlers {:on-close (fn [ws code reason])
                    :on-text (fn [ws msg last?])
                    :on-error (fn [ws e])
                    :on-connect (fn [ws] (deliver connected true))}]
      (with-open [_client (client/connect :certname "client01.example.com"
                                          :override-handlers handlers)]
        (is (= true (deref connected (* 2 1000) false)) "Connected within 2 seconds")))))

(defn connect-and-close
  "Connect to the broker, wait up to delay ms after connecting
  to see if the broker will close the connection.
  Returns the close code or :refused if the connection was refused"
  [delay]
  (let [close-code (promise)
        connected (promise)]
    (try
      (let [handlers {:on-connect (fn [ws] (deliver connected true))
                      :on-text (fn [ws msg])
                      :on-error (fn [ws e])
                      :on-close (fn [ws code reason]
                                  (deliver connected false)
                                  (deliver close-code code)
                                  (ws-session/close! ws code reason))}]
        (with-open [_client (client/connect :certname "client01.example.com"
                                            :uri "wss://localhost:58142/pcp/v2.0"
                                            :override-handlers handlers)]
          (deref connected)
          ;; We were connected, sleep a while to see if the broker
          ;; disconnects the client.
          (deref close-code delay nil)))
      (catch Exception e
        (deliver close-code :refused)))
    @close-code))

(defn conj-unique
  "append elem if it is distinct from the last element in the sequence.
  When we port to clojure 1.7 we should be able to use `distinct` on the
  resulting sequence instead of using this on insert."
  [seq elem]
  (if (= (last seq) elem) seq (conj seq elem)))

(defn is-error-message
  "Assert that the message is a PCP error message with the specified description"
  [message version expected-description check-in-reply-to]
  (is (= "http://puppetlabs.com/error_message" (:message_type message)))
  ;; NB(ale): in-reply-to is optional, as it won't be included in case of
  ;; deserialization error
  (let [data (client/get-data message version)]
    (if (= "v1.0" version)
      (do
        (is (= nil (:in_reply_to message)))
        (when check-in-reply-to
          (is (:id data)))
        (is (= expected-description (:description data))))
      (do
        (when check-in-reply-to
          (is (:in_reply_to message)))
        (is (= expected-description data))))))

(defn is-association-response
  "Assert that the message is an association_response with the specified success and reason entries."
  [message version success reason]
  (is (= "http://puppetlabs.com/associate_response" (:message_type message)))
  (if (= "v1.0" version)
    (is (= nil (:in_reply_to message)))
    (is (:in_reply_to message)))
  (let [data (client/get-data message version)]
    (is (= success (:success data)))
    (is (= reason (:reason data)))))

(deftest it-closes-connections-when-not-running-test
  ;; NOTE(richardc): This test is racy.  What we do is we start
  ;; and stop a broker in a future so we can try to connect to it
  ;; while the trapperkeeper services are still starting up.
  (let [should-stop (promise)
        close-codes (atom [:refused])
        broker (future (with-app-with-config app broker-services broker-config
                         ;; Keep the broker alive until the test is done with it.
                         (deref should-stop)))
        start (System/currentTimeMillis)]
    (try
      (while (and (not (future-done? broker)) (< (- (System/currentTimeMillis) start) (* 120 1000)))
        (let [code (connect-and-close (* 20 1000))]
          (swap! close-codes conj-unique code)
          (if (= 1000 code)
            ;; we were _probably_ able to connect to the broker (or the broker was
            ;; soooo slow to close the connection even though it was not running
            ;; that the 20 seconds timeout expired) so let's tear it down
            (deliver should-stop true))))
      (swap! close-codes conj-unique :refused)
      ;; We expect the following sequence for close codes:
      ;;    :refused (connection refused)
      ;;    1011     (broker not started)
      ;;    1000     (closed because client initated it)
      ;;    1011     (broker stopping)
      ;;    :refused (connection refused)
      ;; though as some of these states may be missed due to timing we test for
      ;; membership of the set of valid sequences.  If we have more
      ;; tests like this it might be worth using ztellman/automat to
      ;; match with a FSM rather than hand-generation of cases.
      (is (contains? #{[:refused 1011 1000 1011 :refused]
                       [:refused 1000 1011 :refused]
                       [:refused 1011 1000 :refused]
                       [:refused 1000 :refused]
                       [:refused 1011 :refused]
                       [:refused]}
                     @close-codes))
      (finally
        ; security measure to ensure the broker is stopped and wait for it to shutdown
        (deliver should-stop true)
        (deref broker)))))

(deftest connection-resets-on-crl-change
  (testing "broker closes connection when CRL file is changed"
    (with-app-with-config
      app
      broker-services
      (merge broker-config
             {:pcp-broker {:expired-conn-throttle 1
                           :crl-check-period 10}})
      (let [start (System/currentTimeMillis)
            timeout 10000
            should-disconnect-by (+ start (- timeout 1000))
            closed-connection (future (connect-and-close timeout))
            crl-path (get-in broker-config [:webserver :ssl-crl-path])
            crl-content (slurp crl-path)]
        (Thread/sleep 1000) ;; Wait to ensure the connection is established
        (fs/delete crl-path)
        (spit crl-path crl-content)
        (deref closed-connection)
        (let [disconnected-by (System/currentTimeMillis)]
          (is (> should-disconnect-by disconnected-by)))))))

(deftest poorly-encoded-message-test
  (testing "an empty byte array cannot be decoded by the v1.0 API"
    (with-app-with-config app broker-services broker-config
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version "v1.0")]
        (with-redefs [client/encode-pcp-message (fn [_] (byte-array 250))]
          (.send client (byte-array 250)))
        (let [response (.await-message-received client)]
          (is-error-message response "v1.0" "Could not decode message" false))))))

;; Session association tests

(deftest reply-to-malformed-messages-during-association-test
  (testing "During association on the v1.0 API, broker replies with
            error_message to a deserialization failure"
    (with-app-with-config
      app broker-services broker-config
      (with-open [client (client/connect :certname "client01.example.com"
                                         :modify-association-encoding
                                         (fn [_] (byte-array [0]))
                                         :check-association false
                                         :version "v1.0")]
        (let [response (.await-message-received client)]
          (is-error-message response "v1.0" "Could not decode message" false))))))

(deftest certificate-must-match-for-authentication-test
  (testing "Unsuccessful associate_response and WebSocket closes if client not authenticated"
    (with-app-with-config app broker-services broker-config
      (dotestseq [version protocol-versions]
                 (with-open [client (client/connect :certname "client01.example.com"
                                                    :uri "pcp://client02.example.com/agent"
                                                    :force-association true
                                                    :check-association false
                                                    :version version)]
                   (let [pcp-response (.await-message-received client)
                         close-websocket-msg (.await-close-received client)]
                     (is-association-response pcp-response version false "Message not authenticated.")
                     (is (= [4002 "Association unsuccessful."] close-websocket-msg))))))))

(deftest basic-session-association-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
              ;; client/connect checks associate_response for both clients
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]))))

(deftest client-type-must-match-for-association-test
  (testing "Unsuccessful associate_response and WebSocket closes if client requests new association"
    (with-app-with-config app broker-services broker-config
      (dotestseq [version protocol-versions]
                 (with-open [client (client/connect :certname "client01.example.com"
                                                    :uri "pcp://client01.example.com/test"
                                                    :force-association true
                                                    :check-association false
                                                    :version version)]
                   (let [pcp-response (.await-message-received client)
                         close-websocket-msg (.await-close-received client)]
                     (is-association-response pcp-response version false "Session already associated.")
                     (is (= [4002 "Association unsuccessful."] close-websocket-msg))))))))

(deftest second-association-new-connection-closes-first-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [first-client (client/connect :certname "client01.example.com"
                                                        :force-association true
                                                        :version version)
                           _second-client (client/connect :certname "client01.example.com"
                                                          :force-association true
                                                          :version version)]
                 ;; client/connect will check associate_response for both v1 and v2 clients
                 (let [close-websocket-msg1 (.await-close-received first-client)]
                   (is (= [1006 "Session Closed"] close-websocket-msg1)))))))

(deftest second-association-same-connection-is-accepted-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :force-association true
                                                  :version version)]
                 (let [request (client/make-association-request "pcp://client01.example.com/agent" version)]
                   (.send client request)
                   (let [response (.await-message-received client)]
                     (is-association-response response version true nil)))))))

(def no-assoc-broker-config
  "A broker that allows no association requests"
  (assoc-in broker-config [:authorization :rules]
            [{:name "deny association"
              :sort-order 400
              :match-request {:type "path"
                              :path "/pcp-broker/send"
                              :query-params {:message_type
                                             "http://puppetlabs.com/associate_request"}}
              :allow []}]))

(deftest authorization-stops-connections-test
  (with-app-with-config app broker-services no-assoc-broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version
                                                  :check-association false)]
                 (testing "cannot associate - closes connection"
                   (let [response (.await-close-received client)]
                     (is (or (= [4002 "Association unsuccessful."] response)
                             ;; the response can also be 1006; this may happen because we close the connection during the on-connect callback
                             ;; since this isn't an issue when things are set up correctly, just accept the 1006
                             (= [1006 "Session Closed"] response)))))
                 (testing "cannot request inventory"
                   (let [request (client/make-message
                                  version
                                  {:message_type "http://puppetlabs.com/inventory_request"
                                   :target "pcp:///server"
                                   :sender "pcp://client01.example.com/test"
                                   :data {:query ["pcp://client01.example.com/test"]}})]
                     (.send client request)
                     ;; CODEREVIEW: sendString in pcp-client throws an IOException here, apparently 
                     ;; this didn't used to happen? We're using the blocking send so 
                     ;; perhaps this needs to be addressed in pcp-client.
                     (let [response (.await-message-received client)]
                       (is (= nil response)))))
                 (testing "cannot send messages"
                   (let [message (client/make-message
                                  version
                                  {:sender "pcp://client01.example.com/test"
                                   :target "pcp://client01.example.com/test"
                                   :message_type "greeting"
                                   :data "Hello"})]
                     (.send client message)
                     (let [message (.await-message-received client)]
                       (is (= nil message)))))))))

;; Inventory service

(deftest inventory-node-can-find-itself-explicit-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [request (client/make-message
                                version
                                {:message_type "http://puppetlabs.com/inventory_request"
                                 :target "pcp:///server"
                                 :sender "pcp://client01.example.com/agent"
                                 :data {:query ["pcp://client01.example.com/agent"]}})]
                   (.send client request)
                   (let [response (.await-message-received client)
                         data (client/get-data response version)]
                     (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                     (if (= version "v1.0")
                       (is (= (:id request) (:in-reply-to response)))
                       (is (= (:id request) (:in_reply_to response))))
                     (is (= ["pcp://client01.example.com/agent"] (:uris data)))))))))

(deftest inventory-node-can-find-itself-wildcard-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [request (client/make-message
                                version
                                {:message_type "http://puppetlabs.com/inventory_request"
                                 :target "pcp:///server"
                                 :sender "pcp://client01.example.com/agent"
                                 :data {:query ["pcp://*/agent"]}})]
                   (.send client request)
                   (let [response (.await-message-received client)
                         data (client/get-data response version)]
                     (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                     (is (= ["pcp://client01.example.com/agent"] (:uris data)))))))))

(deftest inventory-node-cannot-find-previously-connected-node-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client02.example.com"
                                                  :version version)])
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [request (client/make-message
                                version
                                {:message_type "http://puppetlabs.com/inventory_request"
                                 :target "pcp:///server"
                                 :sender "pcp://client01.example.com/agent"
                                 :data {:query ["pcp://client02.example.com/agent"]}})]
                   (.send client request))
                 (let [response (.await-message-received client)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                   (is (= [] (:uris data))))))))

(def no-inventory-broker-config
  "A broker that allows connections but no inventory requests"
  (assoc-in broker-config [:authorization :rules]
            [{:name "allow all"
              :sort-order 401
              :match-request {:type "regex"
                              :path "^/.*$"}
              :allow-unauthenticated true}
             {:name "deny inventory"
              :sort-order 400
              :match-request {:type "path"
                              :path "/pcp-broker/send"
                              :query-params {:message_type
                                             "http://puppetlabs.com/inventory_request"}}
              :allow []}]))

(deftest authorization-stops-inventory-test
  (with-app-with-config app broker-services no-inventory-broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (testing "cannot request inventory"
                   (let [request (client/make-message
                                  version
                                  {:message_type "http://puppetlabs.com/inventory_request"
                                   :target "pcp:///server"
                                   :sender "pcp://client01.example.com/agent"
                                   :data {:query ["pcp://client01.example.com/agent"]}})]
                     (.send client request)
                     (let [response (.await-message-received client)]
                       (is (is-error-message response version "Message not authorized." true)))))))))

(deftest invalid-message-types-not-authorized
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (testing "cannot submit a message with an invalid message_type"
                   (with-test-logging
                     (let [request (client/make-message
                                    version
                                    {:message_type "http://puppetlabs.com/inventory_request\u0000"
                                     :target "pcp:///server"
                                     :sender "pcp://client01.example.com/agent"})]
                       (.send client request)
                       (let [response (.await-message-received client)]
                         (is (is-error-message response version "Message not authorized." true))
                         (is (logged? #"Illegal message type: 'http://puppetlabs.com/inventory_request" :warn))))))))))

(deftest invalid-targets-not-authorized
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (testing "cannot submit a message with an invalid target"
                   (with-test-logging
                     (let [request (client/make-message
                                    version
                                    {:message_type "http://puppetlabs.com/inventory_request"
                                     :target "pcp:///server\u0000"
                                     :sender "pcp://client01.example.com/agent"})]
                       (.send client request)
                       (let [response (.await-message-received client)]
                         (is (is-error-message response version "Message not authorized." true))
                         (is (logged? #"Illegal message target: 'pcp:///server" :warn))))))))))

;; Message sending

(deftest send-to-self-explicit-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version ["v2.0"]]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [message (client/make-message
                                version
                                {:sender "pcp://client01.example.com/agent"
                                 :target "pcp://client01.example.com/agent"
                                 :message_type "greeting"
                                 :data "Hello"})]
                   (.send client message)
                   (let [message (.await-message-received client)]
                     (is (= "greeting" (:message_type message)))
                     (is (= "Hello" (client/get-data message version)))))))))

(deftest send-to-self-wildcard-denied-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [message (client/make-message
                                version
                                {:sender "pcp://client01.example.com/agent"
                                 :target "pcp://*/agent"
                                 :message_type "greeting"
                                 :data "Hello"})]
                   (.send client message)
                   (let [response (.await-message-received client)]
                     (is-error-message response version "Multiple recipients no longer supported." false)))))))

(deftest send-with-destination-report-ignored-test
  (with-app-with-config app broker-services broker-config
    (with-open [sender   (client/connect :certname "client01.example.com"
                                         :version "v1.0")
                receiver (client/connect :certname "client02.example.com"
                                         :version "v1.0")]
      (let [message (-> (m1/make-message
                         {:sender "pcp://client01.example.com/agent"
                          :targets ["pcp://client02.example.com/agent"]
                          :destination_report true
                          :message_type "greeting"})
                        (m1/set-json-data "Hello"))]
        (.send sender message)
        (let [received (.await-message-received receiver)]
          (is (= "greeting" (:message_type received)))
          (is (= "Hello" (m1/get-json-data received))))))))

(deftest send-to-never-connected-will-get-not-connected-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [message (client/make-message
                                version
                                {:sender "pcp://client01.example.com/agent"
                                 :target "pcp://client02.example.com/agent"
                                 :message_type "greeting"
                                 :data "Hello"})]
                   (.send client message)
                   (let [response (.await-message-received client)]
                     (is-error-message response version "Not connected." false)))))))

(deftest send-disconnect-connect-not-delivered-test
  (with-app-with-config app broker-services broker-config
    (dotestseq
     [version protocol-versions]
     (with-open [client1 (client/connect :certname "client01.example.com" :version version)]
       (let [message (client/make-message
                      version
                      {:sender "pcp://client01.example.com/agent"
                       :target "pcp://client02.example.com/agent"
                       :message_type "greeting"
                       :data "Hello"})]
         (.send client1 message))
       (with-open [client2 (client/connect :certname "client02.example.com")]
         (let [response (.await-message-received client1)]
           (is-error-message response version "Not connected." false))
         (let [response (.await-message-received client2 1000)]
           (is (= nil response))))))))

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
  (with-app-with-config app broker-services strict-broker-config
    (dotestseq [version protocol-versions]
               (with-open [client01 (client/connect :certname "client01.example.com"
                                                    :version version)
                           client02 (client/connect :certname "client02.example.com"
                                                    :force-association true
                                                    :version version)]
                 (testing "client01 -> client02 should work"
                   (let [message (client/make-message
                                  version
                                  {:sender "pcp://client01.example.com/agent"
                                   :message_type "test/sensitive"
                                   :target "pcp://client02.example.com/agent"})]
                     (.send client01 message)
                     (let [received (.await-message-received client02)]
                       (is (= (:id message) (:id received))))))
                 (testing "client02 -> client01 should not work"
                   (let [message (-> (client/make-message
                                      version
                                      {:sender "pcp://client02.example.com/agent"
                                       :message_type "test/sensitive"
                                       :target "pcp://client01.example.com/agent"}))]
                     (.send client02 message)
                     (let [received (.await-message-received client01 1000)]
                       (is (= nil received)))))))))

(deftest max-connections-is-respected
  (with-app-with-config app broker-services broker-config
    (testing "defaults to 0, understood as unlimited"
      (with-open [client1 (client/connect :certname "client01.example.com")
                  client2 (client/connect :certname "client02.example.com")]
        (is (client/connected? client1))
        (is (client/connected? client2)))))
  (with-app-with-config app broker-services (-> broker-config
                                                (assoc-in [:pcp-broker :max-connections] 1))
    (testing "positive limit is enforced"
      (with-open [client1 (client/connect :certname "client01.example.com")
                  client2 (client/connect :certname "client02.example.com")]
        (is (client/connected? client1))
        (is (received? (.await-close-received client2)
                       [1011 "Connection limit exceeded."]))
        ;; Allow time for the websocket object to be closed.
        (is (retry-until-true 10 #(not (client/connected? client2))))))))

(deftest interversion-send-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [sender-version   ["v1.0" "v2.0" "v1.0" "v2.0"]
                receiver-version ["v1.0" "v1.0" "v2.0" "v2.0"]]
               (with-open [sender   (client/connect :certname "client01.example.com"
                                                    :version sender-version)
                           receiver (client/connect :certname "client02.example.com"
                                                    :force-association true
                                                    :version receiver-version)]
                 (let [message (client/make-message
                                sender-version
                                {:sender "pcp://client01.example.com/agent"
                                 :target "pcp://client02.example.com/agent"
                                 :in_reply_to (ks/uuid)
                                 :message_type "greeting"
                                 :data "Hello"})]
                   (.send sender message)
                   (let [received-msg (.await-message-received receiver)]
                     (is (= (case receiver-version
                              "v1.0" nil
                              (:in_reply_to message))
                            (:in_reply_to received-msg)))
                     (is (= "greeting" (:message_type received-msg)))
                     (is (= "Hello" (client/get-data received-msg receiver-version)))))))))

(def no-v2-config
  "A broker with v2.0 unconfigured"
  (assoc-in broker-config [:web-router-service :puppetlabs.pcp.broker.service/broker-service]
            {:v1 "/pcp/v1.0"}))

(deftest no-v2-test
  (with-app-with-config app broker-services no-v2-config
    (is true)))
