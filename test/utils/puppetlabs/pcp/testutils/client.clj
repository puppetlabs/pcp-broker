(ns puppetlabs.pcp.testutils.client
  (:refer-clojure :exclude [send])
  (:require [clojure.tools.logging :as log]
            [clojure.test :refer :all]
            [hato.client :as http]
            [puppetlabs.trapperkeeper.services.webserver.jetty10-websockets :as jetty10-websockets]
            [puppetlabs.pcp.broker.message :as message]
            [puppetlabs.pcp.client :as pcp-client]
            [puppetlabs.pcp.message-v1 :as m1]
            [puppetlabs.pcp.message-v2 :as m2]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.trapperkeeper.services.websocket-session :as websocket-session])
  (:import  (java.net URI)
            (java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit)
            (org.eclipse.jetty.util.component LifeCycle)))

(defn await-message-from-client
  "Waits on a message to be present in the queue before <timeout> milliseconds.
   Returns nil if timeout is reached."
  [message-received-queue timeout]
  (.poll message-received-queue timeout TimeUnit/MILLISECONDS))

(defn await-close-from-client
  [promise timeout]
  (deref promise timeout :timeout))

(defn encode-pcp-message
  [message]
  (let [version (if (:_chunks message) :v1 :v2)]
    (if (= version :v1)
      (m1/encode message)
      (m2/encode message))))

(defprotocol WebSocketAdapterWrapper
  (close [_this])
  (send [_this data])
  (connected? [_this])
  (idle-timeout! [_this timeout])
  (disconnect [_this])
  (await-message-received [_this] [_this timeout])
  (await-close-received [_this] [_this timeout]))

;; Jetty websocket client wrapped in blocking mechanisms for messages and closes received
;; for test usage - for non-testing uses use pcp-client.
(def default-timeout 5000)

(defrecord JettyWebSocketAdapterWrapper [client-endpoint websocket-client-with-cert message-received-queue close-promise]
  WebSocketAdapterWrapper
  java.io.Closeable
  (close [_this]
    (log/debug "JettyWebsocketAdapterWrapper close")
    (websocket-session/close! client-endpoint)
    ;; Stop websocket client on a new thread it is not running on
    (try
      @(future (LifeCycle/stop websocket-client-with-cert))
      (catch Exception e
        (println "Exception caught when stopping test WebSocketClient: " e))))
  (send [_this data]
    (log/debug "JettyWebsocketAdapterWrapper send")
    (try
      (websocket-session/send! client-endpoint (encode-pcp-message data))
      (catch Exception e ;; Doing a blocking send in Jetty 10 (i.e. sendString) can result in an IOException 
                         ;; with underlying java.nio.channels.ClosedChannelException.
                         ;; Needs more testing whether we should be catching these in production code in pcp-client.
        (println "Exception caught when sending message from test WebSocketClient: " e))))
  (connected? [_this] (websocket-session/connected? client-endpoint))
  (idle-timeout! [_this timeout] (websocket-session/idle-timeout! client-endpoint timeout))
  (disconnect [_this]
              (log/debug "JettyWebsocketAdapterWrapper disconnect")
              (websocket-session/disconnect client-endpoint))
  (await-message-received [_this] (await-message-from-client message-received-queue default-timeout))
  (await-message-received [_this timeout] (await-message-from-client message-received-queue timeout))
  (await-close-received [_this] (await-close-from-client close-promise default-timeout))
  (await-close-received [_this timeout] (await-close-from-client close-promise timeout)))

(defn http-client-with-cert
  [certname]
  (let [cert        (format "./test-resources/ssl/certs/%s.pem" certname)
        private-key (format "./test-resources/ssl/private_keys/%s.pem" certname)
        ca-cert     "./test-resources/ssl/ca/ca_crt.pem"
        ssl-context (ssl-utils/pems->ssl-context cert private-key ca-cert)]
  (http/build-http-client {:ssl-context ssl-context})))

(defn websocket-client-with-cert
  [certname]
  (let [cert        (format "./test-resources/ssl/certs/%s.pem" certname)
        private-key (format "./test-resources/ssl/private_keys/%s.pem" certname)
        ca-cert     "./test-resources/ssl/ca/ca_crt.pem"
        ssl-context (ssl-utils/pems->ssl-context cert private-key ca-cert)
        ssl-context-factory (pcp-client/make-ssl-context {:ssl-context ssl-context})]
    (pcp-client/make-websocket-client ssl-context-factory nil)))

(defn make-message
  ([options] (make-message "v2.0" options))
  ([version options]
   (let [msg (m2/make-message options)]
     (if (= version "v1.0")
       (message/v2->v1 msg)
       msg))))

(defn get-data
  ([message] (get-data message "v2.0"))
  ([message version]
   (if (= version "v1.0")
     (m1/get-json-data message)
     (m2/get-data message))))

(defn make-association-request
  [uri version]
  (make-message
   version
   {:message_type "http://puppetlabs.com/associate_request"
    :target "pcp:///server"
    :sender uri}))

(def client-count (atom 0))
(defn connect
  "Makes a client for testing"
  [& {:keys [certname type uri version modify-association check-association force-association modify-association-encoding
             override-handlers]
      :or {modify-association identity
           modify-association-encoding identity
           check-association true
           force-association false
           type "agent"
           version "v2.0"}}]
  (let [uri                 (or uri (str "pcp://" certname "/" type))
        client-id (swap! client-count inc)
        _ (log/tracef "pcp-broker test client %d connect certname:%s type:%s uri:%s version:%s" client-id certname type uri version)
        association-request (modify-association (make-association-request uri version))
        close-promise (promise)
        message-received-queue (LinkedBlockingQueue. 100)
        handlers (if (= "v1.0" version)
                   {:on-connect  (fn [ws]
                                   (log/tracef "%d test client v1/on-connect certname:%s uri:%s" client-id certname uri)
                                   (websocket-session/send! ws (modify-association-encoding
                                                                (m1/encode association-request)))
                                   (log/tracef "%d test client v1/on-connect exiting" client-id))
                    :on-error (fn [_ws e]
                                (log/tracef "%d test client v1/on-error certname:%s uri:%s" client-id certname uri)
                                (throw (Exception. e))
                                (log/tracef "%d test client v1/on-error exiting" client-id))
                    :on-bytes (fn [_ws bytes _offset _len]
                                (log/tracef "%d test client v1/on-bytes certname:%s uri:%s" client-id certname uri)
                                (.add message-received-queue (m1/decode bytes))
                                (log/tracef "%d test client v1/on-bytes exiting" client-id))
                    :on-text  (fn [_ws msg]
                                (log/tracef "%d test client v1/on-text certname:%s uri:%s" client-id certname uri)
                                (.add message-received-queue (m1/decode msg))
                                 (log/tracef "%d test client v1/on-text exiting" client-id))
                    :on-close (fn [_ws code reason]
                                (log/tracef "%d test client v1/on-close certname:%s uri:%s" client-id certname uri)
                                (deliver close-promise [code reason])
                                  (log/tracef "%d test client v1/on-close exiting" client-id))}
                   {:on-connect  (fn [ws]
                                   (log/tracef "%d test client v2/on-connect certname:%s uri:%s" client-id certname uri)
                                   (when force-association
                                     (websocket-session/send! ws
                                                              (modify-association-encoding
                                                               (m2/encode association-request))))
                                   (log/tracef "%d test client v2/on-connect exiting" client-id))
                    :on-error (fn [_ws e]
                                (log/tracef "%d test client v2/on-error certname:%s uri:%s" client-id certname uri)
                                (throw (Exception. e))
                                (log/tracef "%d test client v2/on-error exiting" client-id))
                    :on-bytes (fn [_ws bytes _offset _len]
                                (log/tracef "%d test client v2/on-bytes certname:%s uri:%s" client-id certname uri)
                                (.add message-received-queue (m2/decode bytes))
                                (log/tracef "%d test client v2/on-bytes exiting" client-id))
                    :on-text (fn [_ws msg]
                               (log/tracef "%d test client v2/on-text certname:%s uri:%s" client-id certname uri)
                               (.add message-received-queue (m2/decode (str msg)))
                               (log/tracef "%d test client v2/on-text exiting" client-id))
                    :on-close (fn [_ws code reason]
                                (log/tracef "%d test client v2/on-close certname:%s uri:%s" client-id certname uri)
                                (deliver close-promise [code reason])
                                (log/tracef "%d test client v2/on-close exiting" client-id))})
        ;; If override handlers are included, replace the above with them.
        handlers (if override-handlers
                   override-handlers
                   handlers)
        ;; Components to create Jetty client Websocket
        server-request-path (if (= "v1.0" version)
                              (str "wss://localhost:58142/pcp/" version "/")
                              (str "wss://localhost:58142/pcp/" version "/" type))
        server-request-path-uri (URI/create server-request-path)
        closureLatch (CountDownLatch. 1)
        certs []
        client-endpoint (jetty10-websockets/proxy-ws-adapter handlers certs server-request-path closureLatch)
        websocket-client-with-cert (websocket-client-with-cert certname)
        websocket-session (pcp-client/create-websocket-session websocket-client-with-cert client-endpoint server-request-path-uri)
        wrapper (when websocket-session
                  (JettyWebSocketAdapterWrapper. client-endpoint websocket-client-with-cert message-received-queue close-promise))]
      ;; The reason this is here is session association messages are normally only used in PCP v1.
      ;; This verifies the session association message is there and awaits it so we can await
      ;; the message after that.
    (when (and wrapper (or (= "v1.0" version) force-association) check-association)
      (let [response (.await-message-received wrapper)]
        (is (= "http://puppetlabs.com/associate_response" (:message_type response)))
        (is (= (case version
                 "v1.0" nil
                 (:id association-request))
               (:in_reply_to response)))
        (is (= {:id (:id association-request) :success true}
               (case version
                 "v1.0" (m1/get-json-data response)
                 (m2/get-data response))))))
    wrapper))
