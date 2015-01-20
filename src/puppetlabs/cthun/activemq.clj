(ns puppetlabs.cthun.activemq
  (:require [clojure.edn :as edn]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.cheshire :as json]
            [clamq.protocol.consumer :as mq-cons]
            [clamq.protocol.connection :as mq-conn]
            [clojure.tools.logging :as log]))

;; This is a bit rude/lazy, reaching right into puppetdb sources we've
;; copied into our tree.  If this proves out we should talk to
;; puppetdb about extracting puppetlabs.puppetdb.mq into a common library.

(defn queue-message
  [topic message]
  (let [mq-spec "vm://localhost?create=false"
        mq-endpoint topic]
    (log/info "queueing message" message)
    (with-open [conn (mq/activemq-connection mq-spec)]
      (mq/connect-and-publish! conn mq-endpoint (json/generate-string message)))))

(defn subscribe-to-topic
  [topic callback-fn consumer-count]
  (let [mq-spec "vm://localhost?create=false"]
    (with-open [conn (mq/activemq-connection mq-spec)]
      (dotimes [i consumer-count]
        (let [consumer (mq-conn/consumer conn
                                         {:endpoint   topic
                                          :on-message (fn [message]
                                                        (log/info "consuming message" (:body message))
                                                        ; TODO(ploubser): Take another look at using edn instead
                                                        (callback-fn (json/parse-string (:body message) true)))
                                          :transacted true
                                          :on-failure #(log/error "error consuming message" (:exception %))})]
          (mq-cons/start consumer))))))
