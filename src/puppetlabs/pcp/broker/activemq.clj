(ns puppetlabs.pcp.broker.activemq
  (:require [clamq.protocol.connection :as mq-conn]
            [clamq.protocol.consumer :as mq-cons]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.mq :as mq]
            [taoensso.nippy :as nippy]))

;; This is a bit rude/lazy, reaching right into puppetdb sources we've
;; copied into our tree.  If this proves out we should talk to
;; puppetdb about extracting puppetlabs.puppetdb.mq into a common library.

(defn queue-message
  "Queue a message on a middleware"
  [queue message & args]
  (let [mq-spec "vm://localhost?create=false"
        mq-endpoint queue]
    (log/infof "enqueuing message on %s: %s" queue message)
    (with-open [conn (mq/activemq-connection mq-spec)]
      (apply mq/connect-and-publish! conn mq-endpoint (nippy/freeze message) args))))

(defn subscribe-to-queue
  [queue callback-fn consumer-count]
  (let [mq-spec "vm://localhost?create=false"]
    (with-open [conn (mq/activemq-connection mq-spec)]
      (doall (for [i (range consumer-count)]
               (let [consumer (mq-conn/consumer conn
                                                {:endpoint   queue
                                                 :on-message (fn [message]
                                                               (let [body (:body message)
                                                                     thawed (nippy/thaw body)]
                                                                 (log/infof "consuming message from %s: %s" queue thawed)
                                                                 (callback-fn thawed)))
                                                 :transacted true
                                                 :on-failure (fn [error]
                                                               (log/errorf "error consuming message from %s: %s" queue (:exception error)))})]
                 (mq-cons/start consumer)
                 consumer))))))
