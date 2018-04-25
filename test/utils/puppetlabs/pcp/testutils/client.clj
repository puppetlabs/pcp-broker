(ns puppetlabs.pcp.testutils.client
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.message :as message]
            [clojure.core.async :as async :refer [timeout alts!! chan >!! <!! put!]]
            [http.async.client :as http]
            [puppetlabs.pcp.message-v1 :as m1]
            [puppetlabs.pcp.message-v2 :as m2]
            [puppetlabs.ssl-utils.core :as ssl-utils]))

;; A simple websockets client with some assertions - for non-testing uses use pcp-client.

(defprotocol WsClient
  (close [_])
  (sendbytes! [_ bytes])
  (send! [_ message])
  (recv! [_] [_ timeout]
    "Returns nil on timeout, [code reason] on close, message/Message on message")
  (connected? [_]))

(defrecord ChanClient [http-client ws-client message-channel]
  WsClient
  (close [_]
    (async/close! message-channel)
    (.close ws-client)
    (.close http-client))
  (sendbytes! [_ bytes]
    (http/send ws-client :byte bytes))
  (send! [_ message]
    (let [version (if (:_chunks message) :v1 :v2)]
      (if (= version :v1)
        (http/send ws-client :byte (m1/encode message))
        (http/send ws-client :text (m2/encode message)))))
  (recv! [this] (recv! this (* 10 5 1000)))
  (recv! [_ timeout-ms]
    (let [[message channel] (alts!! [message-channel (timeout timeout-ms)])]
      message))
  (connected? [_]
    (.isOpen ws-client)))

(defn http-client-with-cert
  [certname]
  (let [cert        (format "./test-resources/ssl/certs/%s.pem" certname)
        private-key (format "./test-resources/ssl/private_keys/%s.pem" certname)
        ca-cert     "./test-resources/ssl/ca/ca_crt.pem"
        ssl-context (ssl-utils/pems->ssl-context cert private-key ca-cert)]
    (http/create-client :ssl-context ssl-context)))

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

(defn connect
  "Makes a client for testing"
  [& {:keys [certname type uri version modify-association check-association force-association modify-association-encoding]
      :or {modify-association identity
           modify-association-encoding identity
           check-association true
           force-association false
           type "agent"
           version "v2.0"}}]
  (let [uri                 (or uri (str "pcp://" certname "/" type))
        association-request (modify-association (make-association-request uri version))
        client              (http-client-with-cert certname)
        message-chan        (chan)
        ws                  (http/websocket client (str "wss://127.0.0.1:58142/pcp/" version "/"
                                                        (when (= version "v2.0") type))
                                            :open  (fn [ws]
                                                     (case version
                                                       "v1.0" (http/send
                                                               ws :byte
                                                               (modify-association-encoding
                                                                (m1/encode association-request)))
                                                       "v2.0" (when force-association
                                                                (http/send
                                                                 ws :text
                                                                 (modify-association-encoding
                                                                  (m2/encode association-request))))))
                                            :text (fn [ws msg]
                                                    (put! message-chan (m2/decode msg)))
                                            ; :byte  (fn [ws msg]
                                            ;         (put! message-chan (m1/decode msg)))
                                            :close (fn [ws code reason]
                                                     (put! message-chan [code reason])))
        wrapper             (when ws (ChanClient. client ws message-chan))]

    ;; session association messages are normally only used in PCP v1
    (when (and wrapper (or (= "v1.0" version) force-association) check-association)
      (let [response (recv! wrapper)]
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
