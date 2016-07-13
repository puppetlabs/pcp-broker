(ns puppetlabs.pcp.broker.service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [http.async.client :as http]
            [me.raynes.fs :as fs]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.broker.service :refer [broker-service]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.pcp.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-test-logging with-test-logging-debug]]
            [slingshot.slingshot :refer [throw+ try+]]
            [schema.test :as st]))

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
   {:puppetlabs.pcp.broker.service/broker-service {:v1 "/pcp/v1.0"
                                                   :vNext "/pcp/vNext"}
    :puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}

   :metrics {:enabled true
             :server-id "localhost"}

   :pcp-broker {:broker-spool "test-resources/tmp/spool"
                :accept-consumers 2
                :delivery-consumers 2}})

(def protocol-versions
  "The short names of protocol versions"
  ["v1.0" "vNext"])

(def broker-services
  "The trapperkeeper services the broker needs"
  [authorization-service broker-service jetty9-service webrouting-service metrics-service status-service])

(defn cleanup-spool-fixture
  "Deletes the broker-spool before each test"
  [f]
  (fs/delete-dir (get-in broker-config [:pcp-broker :broker-spool]))
  (f))

(use-fixtures :once st/validate-schemas)
(use-fixtures :each cleanup-spool-fixture)

(deftest it-talks-websockets-test
  (with-app-with-config app broker-services broker-config
    (let [connected (promise)]
      (with-open [client (client/http-client-with-cert "client01.example.com")
                  ws     (http/websocket client
                                         "wss://127.0.0.1:8143/pcp/vNext"
                                         :open (fn [ws] (deliver connected true)))]
        (is (= true (deref connected (* 2 1000) false)) "Connected within 2 seconds")))))

(deftest it-expects-ssl-client-auth-test
  (with-app-with-config app broker-services broker-config
    (let [closed (promise)]
      (with-open [client (http/create-client)
                  ws (http/websocket client
                                     "wss://127.0.0.1:8143/pcp/vNext"
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

(defn connect-and-close
  "Connect to the broker, wait up to delay ms after connecting
  to see if the broker will close the connection.
  Returns the close code or :refused if the connection was refused"
  [delay]
  (let [close-code (promise)
        connected (promise)]
    (try+
     (with-open [client (client/http-client-with-cert "client01.example.com")
                 ws (http/websocket client
                                    "wss://127.0.0.1:8143/pcp/vNext"
                                    :open (fn [ws] (deliver connected true))
                                    :close (fn [ws code reason]
                                             (deliver connected false)
                                             (deliver close-code code)))]
       (deref connected)
       ;; We were connected, sleep a while to see if the broker
       ;; disconnects the client.
       (deref close-code delay nil))
     (catch Object _
       (deliver close-code :refused)))
    @close-code))

(defn is-message-not-authorized
  "Assert that the message is an error denying authorization"
  [response version]
  (is (= "http://puppetlabs.com/error_message" (:message_type response)))
  (if (= "v1.0" version)
    (is (= nil (:in-reply-to response)))
    (is (:in-reply-to response)))
  (is (= "Message not authorized" (:description (message/get-json-data response)))))

(defn conj-unique
  "append elem if it is distinct from the last element in the sequence.
  When we port to clojure 1.7 we should be able to use `distinct` on the
  resulting sequence instead of using this on insert."
  [seq elem]
  (if (= (last seq) elem) seq (conj seq elem)))

(deftest it-closes-connections-when-not-running-test
  ;; NOTE(richardc): This test is racy.  What we do is we start
  ;; and stop a broker in an future so we can try to connect to it
  ;; while the trapperkeeper services are still starting up.
  (let [broker (future (Thread/sleep 200)
                       (with-app-with-config app broker-services broker-config
                         ;; Sleep here so the client can fully connect to the broker.
                         (Thread/sleep 1000)))
        close-codes (atom [])]
    (while (not (future-done? broker))
      (let [code (connect-and-close 40)]
        (if-not (= 1006 code) ;; netty 1006 codes are very racy. Filter out
          (swap! close-codes conj-unique code))))
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
                     [:refused 1011 1000 :refused]
                     [:refused 1000 1011 :refused]
                     [:refused 1000 :refused]
                     [:refused]}
                   @close-codes))))

(deftest poorly-encoded-message-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        ;; At time of writing, it's deemed very unlikely that any valid
        ;; encoding of a pcp message is a 0-length array.  Sorry future people.
        (client/sendbytes! client (byte-array 0))
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/error_message" (:message_type response)))
          (is (= nil (:in-reply-to response)))
          (is (= "Could not decode message" (:description (message/get-json-data response)))))))))

(deftest certificate-must-match-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :uri "pcp://client02.example.com/test"
                                         :check-association false
                                         :version version)]
        (let [response (client/recv! client)]
          (is-message-not-authorized response version))))))

;; Session association tests
(deftest basic-session-association-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]))))

(deftest expired-session-association-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :modify-association #(message/set-expiry % -1 :seconds)
                                         :check-association false
                                         :version version)]
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/ttl_expired" (:message_type response))))))))

(deftest second-association-new-connection-closes-first-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [first-client (client/connect :certname "client01.example.com"
                                               :version version)
                  second-client (client/connect :certname "client01.example.com"
                                                :version version)]
        (let [response (client/recv! first-client)]
          (is (= [4000 "superceded"] response)))))))

(deftest second-association-same-connection-should-fail-and-disconnect-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [request (client/make-association-request "pcp://client01.example.com/test")]
          (client/send! client request)
          (let [response (client/recv! client)]
            (is (= "http://puppetlabs.com/associate_response" (:message_type response)))
            (is (= {:success false
                    :reason "session already associated"
                    :id (:id request)}
                   (message/get-json-data response))))
          (let [response (client/recv! client)]
            (is (= [4002 "association unsuccessful"] response))))))))

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
        (testing "cannot associate"
          (let [response (client/recv! client)]
            (is-message-not-authorized response version))
          (let [response (client/recv! client)]
            (is (= [4002 "association unsuccessful"] response))))
        (testing "cannot request inventory"
          (let [request (-> (message/make-message)
                            (assoc :message_type "http://puppetlabs.com/inventory_request"
                                   :targets ["pcp:///server"]
                                   :sender "pcp://client01.example.com/test")
                            (message/set-expiry 3 :seconds)
                            (message/set-json-data {:query ["pcp://client01.example.com/test"]}))]
            (client/send! client request)
            (let [response (client/recv! client 1000)]
              (is (= nil response)))))
        (testing "cannot send messages"
          (let [message (-> (message/make-message)
                            (assoc :sender "pcp://client01.example.com/test"
                                   :targets ["pcp://client01.example.com/test"]
                                   :message_type "greeting")
                            (message/set-expiry 3 :seconds)
                            (message/set-json-data "Hello"))]
            (client/send! client message)
            (let [message (client/recv! client 1000)]
              (is (= nil message)))))))))

;; Inventory service
(deftest inventory-node-can-find-itself-explicit-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [request (-> (message/make-message)
                          (assoc :message_type "http://puppetlabs.com/inventory_request"
                                 :targets ["pcp:///server"]
                                 :sender "pcp://client01.example.com/test")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data {:query ["pcp://client01.example.com/test"]}))]
          (client/send! client request)
          (let [response (client/recv! client)]
            (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
            (is (= (:id request) (:in-reply-to response)))
            (is (= {:uris ["pcp://client01.example.com/test"]} (message/get-json-data response)))))))))

(deftest inventory-node-can-find-itself-wildcard-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [request (-> (message/make-message)
                          (assoc :message_type "http://puppetlabs.com/inventory_request"
                                 :targets ["pcp:///server"]
                                 :sender "pcp://client01.example.com/test")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data {:query ["pcp://*/test"]}))]
          (client/send! client request)
          (let [response (client/recv! client)]
            (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
            (is (= {:uris ["pcp://client01.example.com/test"]} (message/get-json-data response)))))))))

(deftest inventory-node-cannot-find-previously-connected-node-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client02.example.com"
                                         :version version)])
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [request (-> (message/make-message)
                          (assoc :message_type "http://puppetlabs.com/inventory_request"
                                 :targets ["pcp:///server"]
                                 :sender "pcp://client01.example.com/test")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data {:query ["pcp://client02.example.com/test"]}))]
          (client/send! client request))
        (let [response (client/recv! client)]
          (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
          (is (= {:uris []} (message/get-json-data response))))))))

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
          (let [request (-> (message/make-message)
                            (assoc :message_type "http://puppetlabs.com/inventory_request"
                                   :targets ["pcp:///server"]
                                   :sender "pcp://client01.example.com/test")
                            (message/set-expiry 3 :seconds)
                            (message/set-json-data {:query ["pcp://client01.example.com/test"]}))]
            (client/send! client request)
            (let [response (client/recv! client)]
              (is-message-not-authorized response version))))))))

;; Message sending
(deftest send-to-self-explicit-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://client01.example.com/test"]
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! client message)
          (let [message (client/recv! client)]
            (is (= "greeting" (:message_type message)))
            (is (= "Hello" (message/get-json-data message)))))))))

(deftest send-to-self-wildcard-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://*/test"]
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! client message)
          (let [message (client/recv! client)]
            (is (= "greeting" (:message_type message)))
            (is (= "Hello" (message/get-json-data message)))))))))

(deftest send-with-destination-report-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [sender   (client/connect :certname "client01.example.com"
                                           :version version)
                  receiver (client/connect :certname "client02.example.com"
                                           :version version)]
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
            (is (= (case version
                     "v1.0" nil
                     (:id message))
                   (:in-reply-to report)))
            (is (= {:id (:id message)
                    :targets ["pcp://client02.example.com/test"]}
                   (message/get-json-data report)))
            (is (= "greeting" (:message_type message)))
            (is (= "Hello" (message/get-json-data message)))))))))

(deftest send-expired-wildcard-gets-no-expiry-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://client02.example.com/*"]
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! client message)
          (let [response (client/recv! client 1000)]
            ;; Should get no message
            (is (= nil response))))))))

(deftest send-expired-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message :sender "pcp://client01.example.com/test"
                                                :targets ["pcp://client01.example.com/test"]
                                                :message_type "greeting")
                          (message/set-expiry -1 :seconds))]
          (client/send! client message)
          (let [response (client/recv! client)]
            (is (= "http://puppetlabs.com/ttl_expired" (:message_type response)))))))))

(deftest send-to-never-connected-will-get-expired-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://client02.example.com/test"]
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! client message)
          (let [response (client/recv! client)]
            (is (= "http://puppetlabs.com/ttl_expired" (:message_type response)))
            (is (= (case version
                     "v1.0" nil
                     (:id message))
                   (:in-reply-to response)))
            ;; TODO(richardc): should we say for whom we expired,
            ;; in case we only expire for 1 of the expanded
            ;; destinations
            (is (= {:id (:id message)} (message/get-json-data response)))))))))

(deftest send-disconnect-connect-receive-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
      (with-open [client (client/connect :certname "client01.example.com"
                                         :version version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://client02.example.com/test"]
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! client message)))
      (with-open [client (client/connect :certname "client02.example.com")]
        (let [message (client/recv! client)]
          (is (= "Hello" (message/get-json-data message))))))))

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
                                           :version version)]
        (testing "client01 -> client02 should work"
          (let [message (-> (message/make-message :sender "pcp://client01.example.com/test"
                                                  :message_type "test/sensitive"
                                                  :targets ["pcp://client02.example.com/test"])
                            (message/set-expiry 3 :seconds))]
            (client/send! client01 message)
            (let [received (client/recv! client02)]
              (is (= (:id message) (:id received))))))
        (testing "client02 -> client01 should not work"
          (let [message (-> (message/make-message :sender "pcp://client02.example.com/test"
                                                  :message_type "test/sensitive"
                                                  :targets ["pcp://client01.example.com/test"])
                            (message/set-expiry 3 :seconds))]
            (client/send! client02 message)
            (let [response (client/recv! client02)]
              (is-message-not-authorized response version))
            (let [received (client/recv! client01 1000)]
              (is (= nil received)))))))))

(deftest interversion-send-test
  (with-app-with-config app broker-services broker-config
    (dotestseq [sender-version   ["v1.0" "vNext" "v1.0" "vNext"]
                receiver-version ["v1.0" "v1.0" "vNext" "vNext"]]
      (with-open [sender   (client/connect :certname "client01.example.com"
                                           :version sender-version)
                  receiver (client/connect :certname "client02.example.com"
                                           :version receiver-version)]
        (let [message (-> (message/make-message)
                          (assoc :sender "pcp://client01.example.com/test"
                                 :targets ["pcp://client02.example.com/test"]
                                 :in-reply-to (ks/uuid)
                                 :message_type "greeting")
                          (message/set-expiry 3 :seconds)
                          (message/set-json-data "Hello"))]
          (client/send! sender message)
          (let [recieved (client/recv! receiver)]
            (is (= (case receiver-version
                     "v1.0" nil
                     (:in-reply-to message))
                   (:in-reply-to recieved)))
            (is (= "greeting" (:message_type recieved)))
            (is (= "Hello" (message/get-json-data recieved)))))))))
