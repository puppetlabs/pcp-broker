(ns puppetlabs.pcp.broker.service-test
  (:require [clojure.test :refer :all]
            [http.async.client :as http]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.testutils.service :refer [broker-config protocol-versions broker-services]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.message-v1 :as m1]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]))

(deftest it-talks-websockets-test
  (with-app-with-config app broker-services broker-config
    (let [connected (promise)]
      (with-open [client (client/http-client-with-cert "client01.example.com")
                  ws     (http/websocket client
                                         "wss://127.0.0.1:58142/pcp/v2.0"
                                         :open (fn [ws] (deliver connected true)))]
        (is (= true (deref connected (* 2 1000) false)) "Connected within 2 seconds")))))

(deftest it-expects-ssl-client-auth-test
  (with-app-with-config app broker-services broker-config
    (let [closed (promise)]
      (with-open [client (http/create-client)
                  ws (http/websocket client
                                     "wss://127.0.0.1:58142/pcp/v2.0"
                                     :close (fn [ws code reason] (deliver closed code)))]
        ;; NOTE(richardc): This test should only check for close-code 4003, but it
        ;; is a little unreliable and so may sometimes yield the close-code 1006 due
        ;; to a race between the client (netty) becoming connected and the server (jetty)
        ;; closing a connected session because we asked it to.
        ;; This failure is more commonly observed when using Linux
        ;; than on OSX, so we suspect the underlying races to be due
        ;; to thread scheduling.
        ;; See the comments of http://tickets.puppetlabs.com/browse/PCP-124 for more.
        (is (contains? #{4003 1006}
                       (deref closed (* 2 1000) false))
            "Disconnected due to no client certificate")))))

(defn connect-and-close
  "Connect to the broker, wait up to delay ms after connecting
  to see if the broker will close the connection.
  Returns the close code or :refused if the connection was refused"
  [delay]
  (let [close-code (promise)
        connected (promise)]
    (try
     (with-open [client (client/http-client-with-cert "client01.example.com")
                 ws (http/websocket client
                                    "wss://127.0.0.1:58142/pcp/v2.0"
                                    :open (fn [ws] (deliver connected true))
                                    :close (fn [ws code reason]
                                             (deliver connected false)
                                             (deliver close-code code)))]
       (deref connected)
       ;; We were connected, sleep a while to see if the broker
       ;; disconnects the client.
       (deref close-code delay nil))
     (catch Exception _
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

(defn is-association_response
  [message version success reason]
  "Assert that the message is an association_response with the specified success and reason entries."
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
          (when-not (= 1006 code) ;; netty 1006 codes are very racy. Filter out
            (swap! close-codes conj-unique code))
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

(deftest poorly-encoded-message-test
  (testing "a 0-length byte array cannot be decoded by the v1.0 API"
    (with-app-with-config app broker-services broker-config
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version "v1.0")]
        ;; At time of writing, it's deemed very unlikely that any valid
        ;; encoding of a pcp message is a 0-length array.  Sorry future people.
        (client/sendbytes! client (byte-array 0))
        (let [response (client/recv! client)]
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
        (let [response (client/recv! client)]
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
                   (let [pcp-response (client/recv! client)
                         close-websocket-msg (client/recv! client)]
                     (is-association_response pcp-response version false "Message not authenticated")
                     (is (= [4002 "association unsuccessful"] close-websocket-msg))))))))

(deftest basic-session-association-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
       ;; NB(ale): client/connect checks associate_response for both clients
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
                   (let [pcp-response (client/recv! client)
                         close-websocket-msg (client/recv! client)]
                     (is-association_response pcp-response version false "Session already associated")
                     (is (= [4002 "association unsuccessful"] close-websocket-msg))))))))

(deftest second-association-new-connection-closes-first-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [first-client (client/connect :certname "client01.example.com"
                                                        :force-association true
                                                        :version version)
                           second-client (client/connect :certname "client01.example.com"
                                                         :force-association true
                                                         :version version)]
                 ;; NB(ale): client/connect checks associate_response for both clients
                 (let [close-websocket-msg1 (client/recv! first-client)]
                   (is (= [4000 "superseded"] close-websocket-msg1)))))))

(deftest second-association-same-connection-is-accepted-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :force-association true
                                                  :version version)]
                 (let [request (client/make-association-request "pcp://client01.example.com/agent" version)]
                   (client/send! client request)
                   (let [response (client/recv! client)]
                     (is-association_response response version true nil)))))))

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
                   (let [response (client/recv! client)]
                     (is (or (= [4002 "association unsuccessful"] response)
                             ;; the response can also be 1006; this may happen because we close the connection during the on-connect callback
                             ;; since this isn't an issue when things are set up correctly, just accept the 1006
                             (= [1006 "Connection was closed abnormally (that is, with no close frame being sent)."] response)))))
                 (testing "cannot request inventory"
                   (let [request (client/make-message
                                  version
                                  {:message_type "http://puppetlabs.com/inventory_request"
                                   :target "pcp:///server"
                                   :sender "pcp://client01.example.com/test"
                                   :data {:query ["pcp://client01.example.com/test"]}})]
                     (client/send! client request)
                     (let [response (client/recv! client 1000)]
                       (is (= nil response)))))
                 (testing "cannot send messages"
                   (let [message (client/make-message
                                  version
                                  {:sender "pcp://client01.example.com/test"
                                   :target "pcp://client01.example.com/test"
                                   :message_type "greeting"
                                   :data "Hello"})]
                     (client/send! client message)
                     (let [message (client/recv! client 1000)]
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
                   (client/send! client request)
                   (let [response (client/recv! client)
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
                   (client/send! client request)
                   (let [response (client/recv! client)
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
                   (client/send! client request))
                 (let [response (client/recv! client)
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
                     (client/send! client request)
                     (let [response (client/recv! client)]
                       (is (is-error-message response version "Message not authorized" true)))))))))

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
                       (client/send! client request)
                       (let [response (client/recv! client)]
                         (is (is-error-message response version "Message not authorized" true))
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
                       (client/send! client request)
                       (let [response (client/recv! client)]
                         (is (is-error-message response version "Message not authorized" true))
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
                   (client/send! client message)
                   (let [message (client/recv! client)]
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
                   (client/send! client message)
                   (let [response (client/recv! client)]
                     (is-error-message response version "Multiple recipients no longer supported" false)))))))

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
        (client/send! sender message)
        (let [received (client/recv! receiver)]
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
                   (client/send! client message)
                   (let [response (client/recv! client)]
                     (is-error-message response version "not connected" false)))))))

(deftest send-disconnect-connect-not-delivered-test
  (with-app-with-config app broker-services broker-config
    (dotestseq
     [version protocol-versions]
     (with-open [client1 (client/connect :certname "client01.example.com"
                                         :version version)]
       (let [message (client/make-message
                      version
                      {:sender "pcp://client01.example.com/agent"
                       :target "pcp://client02.example.com/agent"
                       :message_type "greeting"
                       :data "Hello"})]
         (client/send! client1 message))
       (with-open [client2 (client/connect :certname "client02.example.com")]
         (let [response (client/recv! client1)]
           (is-error-message response version "not connected" false))
         (let [response (client/recv! client2 1000)]
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
                     (client/send! client01 message)
                     (let [received (client/recv! client02)]
                       (is (= (:id message) (:id received))))))
                 (testing "client02 -> client01 should not work"
                   (let [message (-> (client/make-message
                                      version
                                      {:sender "pcp://client02.example.com/agent"
                                       :message_type "test/sensitive"
                                       :target "pcp://client01.example.com/agent"}))]
                     (client/send! client02 message)
                     (let [received (client/recv! client01 1000)]
                       (is (= nil received)))))))))

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
                   (client/send! sender message)
                   (let [received-msg (client/recv! receiver)]
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
