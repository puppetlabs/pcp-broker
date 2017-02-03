(ns puppetlabs.pcp.broker.core-test
  (:require [clojure.test :refer :all]
            [metrics.core]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.broker.shared :refer [Broker]]
            [puppetlabs.pcp.broker.core :refer :all]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.broker.websocket :refer [ws->uri]]
            [puppetlabs.pcp.broker.message :as message]
            [puppetlabs.pcp.broker.shared-test :refer [mock-uri mock-ws->uri make-test-broker dummy-connection-from]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-core :as jetty9-core]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-config :as jetty9-config]
            [schema.core :as s]
            [slingshot.test])
  (:import [puppetlabs.pcp.broker.connection Connection]))

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

(deftest add-connection-test
  (testing "It should add a connection to the connection map"
    (with-redefs [ws->uri mock-ws->uri]
      (let [broker (make-test-broker)]
        (add-connection! broker (connection/make-connection :dummy-ws identity-codec mock-uri))
        (is (s/validate Connection (-> broker :database deref :inventory (get mock-uri))))))))

(deftest remove-connecton-test
  (testing "It should remove a connection from the inventory map"
    (with-redefs [ws->uri mock-ws->uri]
      (let [broker (make-test-broker)
            connection (connection/make-connection :dummy-ws identity-codec mock-uri)]
        (swap! (:database broker) update :inventory assoc mock-uri connection)
        (is (not= {} (-> broker :database deref :inventory)))
        (remove-connection! broker mock-uri)
        (is (= {} (-> broker :database deref :inventory)))))))

(deftest make-ring-request-test
  (testing "it should return a ring request - one target"
    (let [message (message/make-message
                   {:message_type "example1"
                    :sender "pcp://example01.example.com/agent"
                    :target "pcp://example02.example.com/agent"})]
      (is (= {:uri            "/pcp-broker/send"
              :request-method :post
              :remote-addr    ""
              :form-params    {}
              :query-params   {"sender"             "pcp://example01.example.com/agent"
                               "target"             "pcp://example02.example.com/agent"
                               "message_type"       "example1"}
              :params         {"sender"             "pcp://example01.example.com/agent"
                               "target"             "pcp://example02.example.com/agent"
                               "message_type"       "example1"}}
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
        message (message/make-message
                 {:message_type "example1"
                  :sender "pcp://example01.example.com/agent"
                  :target "pcp://example02.example.com/agent"})]
    (is (= true (authorized? yes-broker message nil)))
    (is (= false (authorized? yes-broker (assoc message :message_type "no\u0000good") nil)))
    (is (= false (authorized? yes-broker (assoc message :target "pcp://bad/\u0000target") nil)))
    (is (= false (authorized? no-broker message nil)))))

(deftest session-association-message?-test
  (testing "It returns true when passed a sessions association message"
    (let [message (message/make-message
                   {:target "pcp:///server"
                    :message_type "http://puppetlabs.com/associate_request"})]
      (is (= true (session-association-request? message)))))
  (testing "It returns false when passed a message of an unknown type"
    (let [message (-> (message/make-message
                       {:target "pcp:///server"
                        :message_type "http://puppetlabs.com/kennylogginsschema"}))]
      (is (= false (session-association-request? message)))))
  (testing "It returns false when passed a message not aimed to the server target"
    (let [message (-> (message/make-message
                       {:target "pcp://other/server"
                        :message_type "http://puppetlabs.com/associate_request"}))]
      (is (= false (session-association-request? message))))))

(deftest process-associate-request!-test
  (let [closed (atom (promise))]
    (with-redefs [puppetlabs.experimental.websockets.client/close! (fn [& args] (deliver @closed args))
                  puppetlabs.experimental.websockets.client/send! (constantly false)
                  puppetlabs.pcp.broker.websocket/ws->client-type (fn [_] "controller")
                  ws->uri (fn [_] "pcp://localhost/controller")]
      (let [message (-> (message/make-message
                         {:sender "pcp://localhost/controller"
                          :message_type "http://puppetlabs.com/login_message"}))]
        (testing "It should return an associated Connection if there's no reason to deny association"
          (reset! closed (promise))
          (let [broker     (make-test-broker)
                connection (connection/make-connection :dummy-ws identity-codec mock-uri)
                _ (add-connection! broker connection)
                connection-uri (:uri (process-associate-request! broker message connection))]
            (is (not (realized? @closed)))
            (is (= "pcp://localhost/controller" connection-uri))))))))

(deftest process-inventory-request-test
  (with-redefs [ws->uri mock-ws->uri])
  (let [broker (make-test-broker)
        message (message/make-message
                 {:sender "pcp://test.example.com/test"
                  :data {:query ["pcp://*/*"]}})
        connection (connection/make-connection :dummy-ws identity-codec mock-uri)
        accepted (atom nil)]
    (with-redefs
     [puppetlabs.pcp.broker.shared/deliver-server-message (fn [_ message _]
                                                            (reset! accepted message))]
      (let [outcome (process-inventory-request broker message connection)]
        (is (nil? outcome))
        (is (= [] (:uris (:data @accepted))))))))

(deftest process-server-message!-test
  (with-redefs [ws->uri mock-ws->uri]
    (let [broker (make-test-broker)
          message (message/make-message
                   {:message_type "http://puppetlabs.com/associate_request"})
          connection (connection/make-connection :dummy-ws identity-codec mock-uri)
          associate-request (atom nil)]
      (with-redefs
       [puppetlabs.pcp.broker.core/process-associate-request! (fn [_ message connection]
                                                                (reset! associate-request message)
                                                                connection)]
        (process-server-message! broker message connection)
        (is (not= nil @associate-request))))))

(deftest authenticated?-test
  (with-redefs [ws->uri mock-ws->uri]
    (let [message (message/make-message
                   {:sender "pcp://lolcathost/agent"})]
      (testing "simple match"
        (is (authenticated? message (dummy-connection-from "lolcathost"))))
      (testing "simple mismatch"
        (is (not (authenticated? message (dummy-connection-from "remotecat")))))
      (testing "accidental regex collisions"
        (is (not (authenticated? message (dummy-connection-from "lol.athost"))))))))

(defn make-valid-ring-request
  [message _]
  (let [{:keys [sender target message_type]} message
        params {"sender"             sender
                "target"            target
                "message_type"       message_type}]
    {:uri            "/pcp-broker/send"
     :request-method :post
     :remote-addr    ""
     :form-params    {}
     :query-params   params
     :params         params}))

(deftest validate-message-test
  (with-redefs [ws->uri mock-ws->uri]
    (testing "correctly marks not authenticated messages"

      (let [broker (make-test-broker)
            msg (message/make-message
                 {:sender "pcp://localpost/office"
                  :message_type "http://puppetlabs.com/associate_request"})
            connection (dummy-connection-from "groceryshop")
            is-association-request true]
        (is (= :not-authenticated
               (validate-message broker msg connection is-association-request)))))
    (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request]
      (testing "correctly marks not authorized messages"
        (let [no-broker (assoc (make-test-broker) :authorization-check no-authorization-check)
              msg (message/make-message
                   {:sender "pcp://greyhacker/exploit"
                    :message_type "http://puppetlabs.com/associate_request"})
              connection (dummy-connection-from "greyhacker")
              is-association-request true]
          (is (= :not-authorized
                 (validate-message no-broker msg connection is-association-request)))))
      (testing "marks multicast messages as unsupported"
        (let [yes-broker (assoc (make-test-broker)
                                :authorization-check yes-authorization-check)
              msg (message/make-message
                   {:sender "pcp://localcost/gbp"
                    :message_type "http://puppetlabs.com/associate_request"})
              connection (dummy-connection-from "localcost")
              is-association-request true]
          (with-redefs [puppetlabs.pcp.broker.message/multicast-message?  (fn [_] true)]
            (is (= :multicast-unsupported
                   (validate-message yes-broker msg connection is-association-request))))))
      (testing "marks expired messages as to be processed"
        (let [yes-broker (assoc (make-test-broker)
                                :authorization-check yes-authorization-check)
              msg (message/make-message
                   {:sender "pcp://localcost/gbp"
                    :message_type "http://puppetlabs.com/associate_request"})
              connection (dummy-connection-from "localcost")
              is-association-request true]
          (is (= :to-be-processed
                 (validate-message yes-broker msg connection is-association-request)))))
      (testing "correctly marks messages to be processed"
        (let [yes-broker (assoc (make-test-broker) :authorization-check yes-authorization-check)
              msg (message/make-message
                   {:sender "pcp://localghost/opera"
                    :message_type "http://puppetlabs.com/associate_request"})
              connection (dummy-connection-from "localghost")
              is-association-request true]
          (is (= :to-be-processed
                 (validate-message yes-broker msg connection is-association-request))))))))


(deftest process-message-test
  (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request
                ws->uri mock-ws->uri]
    (testing "delivers message in case of expired msg (not associate_session)"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            called-accept-message (atom false)
            msg (-> (message/make-message
                     {:sender "pcp://host_a/entity"
                      :message_type "some_kinda_love"
                      :target "pcp://host_b/entity"}))
            connection (dummy-connection-from "host_a")]
        (swap! (:database broker) update :inventory assoc "pcp://host_a/entity" connection)
        (with-redefs [puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.shared/deliver-message
                      (fn [_ _ _]
                        (reset! called-accept-message true) nil)
                      ws->uri mock-ws->uri]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is @called-accept-message)
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of authentication failure"
      (let [broker (make-test-broker)
            error-message-description (atom nil)
            msg (-> (message/make-message
                     {:sender "pcp://popgroup/entity"
                      :message_type "some_kinda_hate"
                      :target "pcp://gangoffour/entity"}))
            connection (dummy-connection-from "wire")]
        (with-redefs [ws->uri mock-ws->uri
                      puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.shared/send-error-message
                      (fn [_ description _]
                        (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is (= "Message not authenticated" @error-message-description))
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of authorization failure"
      (let [broker (assoc (make-test-broker)
                          :authorization-check no-authorization-check)
            error-message-description (atom nil)
            msg (message/make-message
                 {:sender "pcp://thegunclub/entity"
                  :message_type "sexbeat"
                  :target "pcp://fourtet/entity"})
            connection (dummy-connection-from "thegunclub")]
        (with-redefs [ws->uri mock-ws->uri
                      puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.shared/send-error-message
                      (fn [_ description _]
                        (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is (= "Message not authorized" @error-message-description))
            (is (nil? outcome))))))
    (testing "process an authorized message sent to broker"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            processed-server-message (atom false)
            msg (message/make-message
                 {:sender "pcp://thegunclub/entity"
                  :message_type "jackonfire"
                  :target "pcp:///server"})
            connection (dummy-connection-from "thegunclub")]
        (with-redefs [puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.core/process-server-message!
                      (fn [_ _ _]
                        (reset! processed-server-message true) nil)
                      ws->uri mock-ws->uri]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is @processed-server-message)
            (is (nil? outcome))))))
    (testing "sends an error message and returns nil in case of a multicast message"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            error-message-description (atom nil)
            msg (message/make-message
                 {:sender "pcp://thegunclub/entity"
                  :message_type "ether"
                  :target "pcp://wire/*"})
            connection (dummy-connection-from "thegunclub")]
        (with-redefs [ws->uri mock-ws->uri
                      puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.shared/send-error-message
                      (fn [_ description _]
                        (reset! error-message-description description) nil)]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is (= "Multiple recipients no longer supported" @error-message-description))
            (is (nil? outcome))))))
    (testing "delivers an authorized message"
      (let [broker (assoc (make-test-broker)
                          :authorization-check yes-authorization-check)
            accepted-message-for-delivery (atom false)
            msg (message/make-message
                 {:sender "pcp://gangoffour/entity"
                  :message_type "ether"
                  :target "pcp://wire/entity"})
            connection (dummy-connection-from "gangoffour")]
        (with-redefs [ws->uri mock-ws->uri
                      puppetlabs.pcp.broker.shared/get-connection
                      (fn [_ _] connection)
                      puppetlabs.pcp.broker.shared/deliver-message
                      (fn [_ _ _]
                        (reset! accepted-message-for-delivery true) nil)]
          (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
            (is @accepted-message-for-delivery)
            (is (nil? outcome))))))))

(deftest codec-roundtrip-test
  (testing "v1-codec survives roundtrip"
    (let [broker (assoc (make-test-broker)
                        :authorization-check yes-authorization-check)
          sent-message (atom nil)
          msg (message/make-message
               {:sender "pcp://gangoffour/entity"
                :message_type "ether"
                :target "pcp://gangoffour/entity"})
          connection (assoc (connection/make-connection :dummy-ws message/v1-codec mock-uri)
                            :common-name "gangoffour")]
      (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request
                    ws->uri mock-ws->uri
                    puppetlabs.pcp.broker.shared/get-connection
                    (fn [_ _] connection)
                    puppetlabs.experimental.websockets.client/send!
                    (fn [_ message] (reset! sent-message message))]
        (let [outcome (process-message! broker (message/v1-encode msg) :dummy-ws)]
          (is (= msg (message/v1-decode @sent-message)))
          (is (nil? outcome))))))
  (testing "v2-codec survives roundtrip"
    (let [broker (assoc (make-test-broker)
                        :authorization-check yes-authorization-check)
          sent-message (atom nil)
          msg (message/make-message
               {:sender "pcp://gangoffour/entity"
                :message_type "ether"
                :target "pcp://gangoffour/entity"})
          connection (assoc (connection/make-connection :dummy-ws message/v2-codec mock-uri)
                            :common-name "gangoffour")]
      (with-redefs [puppetlabs.pcp.broker.core/make-ring-request make-valid-ring-request
                    ws->uri mock-ws->uri
                    puppetlabs.pcp.broker.shared/get-connection
                    (fn [_ _] connection)
                    puppetlabs.experimental.websockets.client/send!
                    (fn [_ message] (reset! sent-message message))]
        (let [outcome (process-message! broker (message/v2-encode msg) :dummy-ws)]
          (is (= msg (message/v2-decode @sent-message)))
          (is (nil? outcome)))))))
