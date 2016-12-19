(ns puppetlabs.pcp.broker.core-test
  (:require [clojure.test :refer :all]
            [metrics.core]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.broker.core :refer :all]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-config :as jetty9-config]
            [schema.core :as s]
            [schema.test :as st]
            [slingshot.test])
  (:import [puppetlabs.pcp.broker.connection Connection]
           [java.util.concurrent ConcurrentHashMap]))

(s/defn make-test-broker :- Broker
  "Return a minimal clean broker state"
  []
  (let [broker {:broker-name         nil
                :record-client       (constantly true)
                :find-clients        (constantly ())
                :authorization-check (constantly true)
                :uri-map             (ConcurrentHashMap.)
                :connections         (ConcurrentHashMap.)
                :metrics-registry    metrics.core/default-registry
                :metrics             {}
                :state               (atom :running)}
        metrics (build-and-register-metrics broker)
        broker (assoc broker :metrics metrics)]
    broker))

(s/def identity-codec :- Codec
  {:encode identity
   :decode identity})

(s/defn make-mock-ssl-context-factory :- org.eclipse.jetty.util.ssl.SslContextFactory
  "Return an instance of the SslContextFactory with only a minimal configuration
  of the key & trust stores. If the specified `certificate-chain` is not nil it is
  included with the PrivateKey entry in the factory's key store."
  [certificate-chain :- (s/maybe s/Str)]
  (let [pem-config {:ssl-key "./test-resources/ssl/private_keys/broker.example.com.pem"
                    :ssl-cert "./test-resources/ssl/certs/broker.example.com.pem"
                    :ssl-ca-cert "./test-resources/ssl/certs/ca.pem"}
        pem-config (if (nil? certificate-chain)
                     pem-config
                     (assoc pem-config :ssl-cert-chain certificate-chain))
        keystore-config (jetty9-config/pem-ssl-config->keystore-ssl-config pem-config)]
    (doto (org.eclipse.jetty.util.ssl.SslContextFactory.)
      (.setKeyStore (:keystore keystore-config))
      (.setKeyStorePassword (:key-password keystore-config))
      (.setTrustStore (:truststore keystore-config)))))

(s/defn make-mock-webserver-context :- jetty9-core/ServerContext
  "Return a mock webserver context including the specfied `ssl-context-factory`."
  [ssl-context-factory :- org.eclipse.jetty.util.ssl.SslContextFactory]
  {:server   nil
   :handlers (org.eclipse.jetty.server.handler.ContextHandlerCollection.)
   :state    (atom {:mbean-container             nil
                    :overrides-read-by-webserver true
                    :overrides                   nil
                    :endpoints                   {}
                    :ssl-context-factory         ssl-context-factory})})

(deftest get-webserver-cn-test
  (testing "It returns the correct cn"
    (let [cn (-> nil
                 make-mock-ssl-context-factory
                 make-mock-webserver-context
                 get-webserver-cn)]
      (is (= "broker.example.com" cn))))
  (testing "It returns the correct cn from a certificate chain"
    (let [cn (-> "./test-resources/ssl/certs/broker-chain.example.com.pem"
                 make-mock-ssl-context-factory
                 make-mock-webserver-context
                 get-webserver-cn)]
      (is (= "broker.example.com" cn))))
  (testing "It returns nil if anything goes wrong"
    (is (nil? (-> (org.eclipse.jetty.util.ssl.SslContextFactory.)
                  make-mock-webserver-context
                  get-webserver-cn)))))

(deftest add-connection!-test
  (testing "It should add a connection to the connection map"
    (let [broker (make-test-broker)]
      (add-connection! broker "ws" identity-codec)
      (is (= (get-in (:connections broker) ["ws" :state]) :open)))))

(deftest remove-connection!-test
  (testing "It should remove a connection from the connection map"
    (let [broker (make-test-broker)]
      (.put (:connections broker) "ws" (connection/make-connection "ws" identity-codec))
      (remove-connection! broker "ws")
      (is (= {} (:connections broker))))))

(deftest get-websocket-test
  (let [broker (make-test-broker)]
    (.put (:uri-map broker) "pcp://bill/agent" "ws1")
    (.put (:uri-map broker) "pcp://bob" "ws2")
    (testing "it finds a single websocket explictly"
      (is (= "ws1" (get-websocket broker "pcp://bill/agent"))))
    (testing "it finds nothing by wildcard"
      (is (not (get-websocket broker "pcp://*/agent"))))
    (testing "it finds nothing when it's not there"
      (is (not (get-websocket broker "pcp://bob/nonsuch"))))))

(deftest handle-delivery-failure-test
  (let [broker (make-test-broker)
        msg (message/make-message)
        delivered (atom [])]
    (with-redefs [puppetlabs.pcp.broker.core/send-error-message
                  (fn [mg reason sender] (swap! delivered conj mg))]
      (testing "redelivery always"
        (handle-delivery-failure broker msg nil "Out of cheese")
        (is (= 1 (count @delivered)))))))

(deftest multicast-message?-test
  (testing "returns false if targets specifies a single host with no wildcards"
    (let [message (message/make-message :targets ["pcp://example01.example.com/foo"])]
      (is (not (multicast-message? message)))))
  (testing "returns true if targets includes wildcard hostname"
    (let [message (message/make-message :targets ["pcp://*/foo"])]
      (is (multicast-message? message))))
  (testing "returns true if targets includes wildcard client type"
    (let [message (message/make-message :targets ["pcp://example01.example.com/*"])]
      (is (multicast-message? message))))
  (testing "returns true if more than one target specified"
    (let [message (message/make-message :targets ["pcp://example01.example.com/foo" "pcp://example02.example.com/foo"])]
      (is (multicast-message? message)))))

(deftest deliver-message-test
  (let [broker (make-test-broker)
        failure (atom nil)]
    (testing "delivers messages"
      (let [message (message/make-message :targets ["pcp://example01.example.com/foo"])]
        (with-redefs [puppetlabs.pcp.broker.core/handle-delivery-failure
                      (fn [broker message sender err] (reset! failure err))]
          (deliver-message broker message nil)
          (is (= "not connected" @failure)))))
    (testing "errors if multicast message"
      (let [message (message/make-message)]
        (with-redefs [puppetlabs.pcp.broker.core/multicast-message?  (fn [message] true)]
          (is (thrown? java.lang.AssertionError (deliver-message broker message nil))))))))

(deftest make-ring-request-test
  (testing "it should return a ring request - one target"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"])]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" false}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" false}}
             (make-ring-request message nil)))))
  (testing "it should return a ring request - two targets"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"
                                                  "pcp://example03.example.com/agent"])]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            ["pcp://example02.example.com/agent"
                                                     "pcp://example03.example.com/agent"]
                               "message_type"       "example1"
                               "destination_report" false}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            ["pcp://example02.example.com/agent"
                                                     "pcp://example03.example.com/agent"]
                               "message_type"       "example1"
                               "destination_report" false}}
             (make-ring-request message nil)))))
  (testing "it should return a ring request - destination report"
    (let [message (message/make-message :message_type "example1"
                                        :sender "pcp://example01.example.com/agent"
                                        :targets ["pcp://example02.example.com/agent"]
                                        :destination_report true)]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" true}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "targets"            "pcp://example02.example.com/agent"
                               "message_type"       "example1"
                               "destination_report" true}}
             (make-ring-request message nil))))))

(defn yes-authorization-check [r] {:authorized true
                                   :message ""
                                   :request r})

(defn no-authorization-check [r] {:authorized false
                                  :message "Danger Zone"
                                  :request r})

(deftest authorized?-test
  (let [yes-broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
        no-broker (assoc (make-test-broker) :authorization-check no-authorization-check)
        message (message/make-message :message_type "example1"
                                      :sender "pcp://example01.example.com/agent"
                                      :targets ["pcp://example02.example.com/agent"])]
    (is (= true (authorized? yes-broker message nil)))
    (is (= false (authorized? yes-broker (assoc message :message_type "no\u0000good") nil)))
    (is (= false (authorized? yes-broker (assoc message :targets ["pcp://bad/\u0000target"]) nil)))
    (is (= false (authorized? no-broker message nil)))))

(deftest session-association-message?-test
  (testing "It returns true when passed a sessions association messge"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp:///server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= true (session-association-request? message)))))
  (testing "It returns false when passed a message of an unknown type"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp:///server"]
                             ;; OLDJOKE(richardc): we used to call association_request the loginschema
                             :message_type "http://puppetlabs.com/kennylogginsschema"))]
      (is (= false (session-association-request? message)))))
  (testing "It returns false when passed a message not aimed to the server target"
    (let [message (-> (message/make-message)
                      (assoc :targets ["pcp://other/server"]
                             :message_type "http://puppetlabs.com/associate_request"))]
      (is (= false (session-association-request? message))))))

(deftest reason-to-deny-association-test
  (let [broker     (make-test-broker)
        connection (connection/make-connection "websocket" identity-codec)
        associated (assoc connection :state :associated :uri "pcp://test/foo")]
    (is (= nil (reason-to-deny-association broker connection "pcp://test/foo")))
    (is (= "'server' type connections not accepted"
           (reason-to-deny-association broker connection "pcp://test/server")))
    (is (= "Session already associated"
           (reason-to-deny-association broker associated "pcp://test/foo")))
    (is (= "Session already associated"
           (reason-to-deny-association broker associated "pcp://test/bar")))))

(deftest process-associate-request!-test
  (let [closed (atom (promise))]
    (with-redefs [puppetlabs.experimental.websockets.client/close! (fn [& args] (deliver @closed args))
                  puppetlabs.experimental.websockets.client/send! (constantly false)]
      (let [message (-> (message/make-message :sender "pcp://localhost/controller"
                                              :message_type "http://puppetlabs.com/login_message")
                        (message/set-expiry 3 :seconds))]
        (testing "It should return an associated Connection if there's no reason to deny association"
          (reset! closed (promise))
          (let [broker     (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-request! broker message connection)]
            (is (not (realized? @closed)))
            (is (= :associated (:state connection)))
            (is (= "pcp://localhost/controller" (:uri connection)))))
        (testing "Associates a client already associated on a different session, but disconnects the first"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection1 (add-connection! broker "ws1" identity-codec)
                connection2 (add-connection! broker "ws2" identity-codec)]
            (process-associate-request! broker message connection1)
            (is (process-associate-request! broker message connection2))
            (is (= ["ws1" 4000 "superseded"] @@closed))
            (is (= ["ws2"] (keys (:connections broker))))))
        ;; TODO(ale): change this behaviour (Association Request idempotent - PCP-521)
        (testing "No association for the same WebSocket session; closes and returns nil"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                connection (process-associate-request! broker message connection)
                outcome1 (process-associate-request! broker message connection)
                outcome2 (process-associate-request! broker message connection)]
            ;; NB(ale): in this case, the Connection object is removed from the
            ;; broker's connections map by the onClose handler once triggered
            (is (= :associated (:state connection)))
            (is (nil? outcome1))
            (is (nil? outcome2))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))
        (testing "No association if a reason to deny is provided; closes the session and return nil"
          (reset! closed (promise))
          (let [broker (make-test-broker)
                connection (add-connection! broker "ws" identity-codec)
                outcome (process-associate-request! broker message connection "because I said so!")]
            ;; NB(ale): as above, the connections map is updated after onClose
            (is (nil? outcome))
            (is (= ["ws" 4002 "association unsuccessful"] @@closed))))))))

(deftest process-inventory-request-test
  (let [broker (make-test-broker)
        message (-> (message/make-message :sender "pcp://test.example.com/test")
                    (message/set-json-data  {:query ["pcp://*/*"]}))
        connection (connection/make-connection "ws1" identity-codec)
        connection (assoc connection :state :associated)
        accepted (atom nil)]
    (with-redefs
     [puppetlabs.pcp.broker.core/deliver-message (fn [broker message connection]
                                                   (reset! accepted message))]
      (let [outcome (process-inventory-request broker message connection)]
        (is (nil? outcome))
        (is (= [] (:uris (message/get-json-data @accepted))))))))

(deftest process-server-message!-test
  (let [broker (make-test-broker)
        message (message/make-message :message_type "http://puppetlabs.com/associate_request")
        connection (connection/make-connection "ws1" identity-codec)
        associate-request (atom nil)]
    (with-redefs
     [puppetlabs.pcp.broker.core/process-associate-request! (fn [broker message connection]
                                                              (reset! associate-request message)
                                                              connection)]
      (process-server-message! broker message connection)
      (is (not= nil @associate-request)))))

(s/defn dummy-connection-from :- Connection
  [common-name]
  (assoc (connection/make-connection "ws1" identity-codec)
         :common-name common-name))

(deftest authenticated?-test
  (let [message (message/make-message :sender "pcp://lolcathost/agent")]
    (testing "simple match"
      (is (authenticated? message (dummy-connection-from "lolcathost"))))
    (testing "simple mismatch"
      (is (not (authenticated? message (dummy-connection-from "remotecat")))))
    (testing "accidental regex collisions"
      (is (not (authenticated? message (dummy-connection-from "lol.athost")))))))

(defn make-valid-ring-request
  [message _]
  (let [{:keys [sender targets message_type destination_report]} message
        params {"sender"             sender
                "targets"            targets
                "message_type"       message_type
                "destination_report" destination_report}]
    {:uri            "/pcp-broker/send"
     :request-method :post
     :remote-addr    ""
     :form-params    {}
     :query-params   params
     :params         params}))

(deftest validate-message-test
  (testing "ignores messages other than associate_request if connection not associated"
    (let [broker (make-test-broker)
          message (message/make-message)
          connection (dummy-connection-from "localpost")
          is-association-request false]
      (is (= :to-be-ignored-during-association
             (validate-message broker message connection is-association-request)))))
  (testing "correctly marks not authenticated messages"
    (let [broker (make-test-broker)
          msg (message/make-message :sender "pcp://localpost/office"
                                    :message_type "http://puppetlabs.com/associate_request")
          connection (dummy-connection-from "groceryshop")
          is-association-request true]
      (is (= :not-authenticated
             (validate-message broker msg connection is-association-request)))))
  (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request]
    (testing "correctly marks not authorized messages"
      (let [no-broker (assoc (make-test-broker) :authorization-check no-authorization-check)
            msg (message/make-message :sender "pcp://greyhacker/exploit"
                                      :message_type "http://puppetlabs.com/associate_request")
            connection (dummy-connection-from "greyhacker")
            is-association-request true]
        (is (= :not-authorized
               (validate-message no-broker msg connection is-association-request)))))
    (testing "marks multicast messages as unsupported"
      (let [yes-broker (assoc (make-test-broker)
                              :authorization-check yes-authorization-check)
            msg (message/make-message :sender "pcp://localcost/gbp"
                                      :message_type "http://puppetlabs.com/associate_request")
            connection (dummy-connection-from "localcost")
            is-association-request true]
        (with-redefs [puppetlabs.pcp.broker.core/multicast-message?  (fn [message] true)]
          (is (= :multicast-unsupported
                 (validate-message yes-broker msg connection is-association-request))))))
    (testing "marks expired messages as to be processed"
      (let [yes-broker (assoc (make-test-broker)
                              :authorization-check yes-authorization-check)
            msg (-> (message/make-message :sender "pcp://localcost/gbp"
                                          :message_type "http://puppetlabs.com/associate_request")
                    (message/set-expiry -3 :seconds))
            connection (dummy-connection-from "localcost")
            is-association-request true]
        (is (= :to-be-processed
               (validate-message yes-broker msg connection is-association-request)))))
    (testing "correctly marks messages to be processed"
      (let [yes-broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
            msg (-> (message/make-message :sender "pcp://localghost/opera"
                                          :message_type "http://puppetlabs.com/associate_request")
                    (message/set-expiry 5 :seconds))
            connection (dummy-connection-from "localghost")
            is-association-request true]
        (is (= :to-be-processed
               (validate-message yes-broker msg connection is-association-request)))))))

(deftest send-error-message-test
  (let [error-msg (atom nil)
        connection (merge (dummy-connection-from "host_x")
                          {:codec v1-codec})]
    (with-redefs [puppetlabs.experimental.websockets.client/send!
                  (fn [websocket raw-message] (reset! error-msg (message/decode raw-message)))]
      (testing "sends errror_message correctly and returns nil if received msg is NOT given"
        (let [received-msg nil
              error-description "something wrong!"
              outcome (send-error-message received-msg error-description connection)
              msg-data (message/get-json-data @error-msg)]
          (is (nil? outcome))
          (is (not (contains? msg-data :id)))
          (is (= error-description (:description msg-data)))))
      (testing "sends error_message correctly and returns nil if received msg is given"
        (reset! error-msg nil)
        (let [the-uuid (ks/uuid)
              received-msg (message/make-message :sender "pcp://test_example/pcp_client_alpha"
                                                 :message_type "gossip"
                                                 :targets ["pcp://test_broker/server"])
              received-msg (assoc received-msg :id the-uuid)
              error-description "something really wrong :o"
              outcome (send-error-message received-msg error-description connection)
              msg-data (message/get-json-data @error-msg)]
          (is (nil? outcome))
          (is (= the-uuid (:id msg-data)))
          (is (= error-description (:description msg-data))))))))

(deftest process-message!-test
  (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request]
    (testing "sends an error message and returns nil, in case it fails to deserialize"
      (let [broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
            error-message-description (atom nil)]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] (add-connection! broker "ws" v1-codec))
                      puppetlabs.pcp.broker.core/send-error-message
                      (fn [msg description connection] (reset! error-message-description description) nil)]
          (dotestseq
           [raw-message [(byte-array [])                   ; empty message
                         (byte-array [0])                  ; bad message
                         (byte-array [3 3 3 3 3])          ; bad message
                         (byte-array [1,                   ; first chunk not envelope
                                      2, 0 0 0 2, 123 125])
                         (byte-array [1,                   ; bad envelope
                                      1, 0 0 0 1, 12 12 12 12])
                         (byte-array [1,                   ; bad (incomplete) envelope
                                      1, 0 0 0 2, 123])]]
           (let [outcome (process-message! broker (byte-array raw-message) nil)]
             (is (= "Could not decode message" @error-message-description))
             (is (nil? outcome)))))))
    (testing "delivers message in case of expired msg (not associate_session)"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            called-accept-message (atom false)
            msg (-> (message/make-message :sender "pcp://host_a/entity"
                                          :message_type "some_kinda_love"
                                          :targets ["pcp://host_b/entity"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "host_a")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (.put (:connections broker) "ws1" connection)
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/deliver-message
                      (fn [broker message connection] (reset! called-accept-message true))]
          (let [outcome (process-message! broker raw-msg nil)]
            (is @called-accept-message)
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of authentication failure"
      (let [broker (make-test-broker)
            error-message-description (atom nil)
            msg (-> (message/make-message :sender "pcp://popgroup/entity"
                                          :message_type "some_kinda_hate"
                                          :targets ["pcp://gangoffour/entity"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "wire")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/send-error-message
                      (fn [msg description connection] (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (byte-array raw-msg) nil)]
            (is (= "Message not authenticated" @error-message-description))
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of authorization failure"
      (let [broker (assoc (make-test-broker)
                          :authorization-check no-authorization-check)
            error-message-description (atom nil)
            msg (-> (message/make-message :sender "pcp://thegunclub/entity"
                                          :message_type "sexbeat"
                                          :targets ["pcp://fourtet/entity"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "thegunclub")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/send-error-message
                      (fn [msg description connection] (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (byte-array raw-msg) nil)]
            (is (= "Message not authorized" @error-message-description))
            (is (nil? outcome))))))
    (testing "process an authorized message sent to broker"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            processed-server-message (atom false)
            msg (-> (message/make-message :sender "pcp://thegunclub/entity"
                                          :message_type "jackonfire"
                                          :targets ["pcp:///server"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "thegunclub")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/process-server-message!
                      (fn [broker message connection] (reset! processed-server-message true) nil)]
          (let [outcome (process-message! broker (byte-array raw-msg) nil)]
            (is @processed-server-message)
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of a multicast message"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            error-message-description (atom nil)
            msg (-> (message/make-message :sender "pcp://thegunclub/entity"
                                          :message_type "ether"
                                          :targets ["pcp://wire/*"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "thegunclub")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/send-error-message
                      (fn [msg description connection] (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (byte-array raw-msg) nil)]
            (is (= "Multiple recipients no longer supported" @error-message-description))
            (is (nil? outcome))))))
    (testing "delivers an authorized message"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            accepted-message-for-delivery (atom false)
            msg (-> (message/make-message :sender "pcp://gangoffour/entity"
                                          :message_type "ether"
                                          :targets ["pcp://wire/entity"])
                    (message/set-expiry 5 :seconds))
            raw-msg (message/encode msg)
            connection (merge (dummy-connection-from "gangoffour")
                              {:state :associated
                               :codec {:decode (fn [bytes] msg)
                                       :encode (fn [msg] raw-msg)}})]
        (with-redefs [puppetlabs.pcp.broker.core/get-connection
                      (fn [broker ws] connection)
                      puppetlabs.pcp.broker.core/deliver-message
                      (fn [broker message connection] (reset! accepted-message-for-delivery true))]
          (let [outcome (process-message! broker (byte-array raw-msg) nil)]
            (is @accepted-message-for-delivery)
            (is (nil? outcome))))))))
