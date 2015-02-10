(ns puppetlabs.cthun.activemq
  (:require [clojure.edn :as edn]
            [taoensso.nippy :as nippy]
            [puppetlabs.puppetdb.mq :as mq]
            [clamq.protocol.consumer :as mq-cons]
            [clamq.protocol.connection :as mq-conn]
            [clojure.tools.logging :as log]))

;; This is a bit rude/lazy, reaching right into puppetdb sources we've
;; copied into our tree.  If this proves out we should talk to
;; puppetdb about extracting puppetlabs.puppetdb.mq into a common library.

(defn queue-message
  "Queue a message on a middleware"
  [topic message & args]
  (let [mq-spec "vm://localhost?create=false"
        mq-endpoint topic]
    (log/info "queueing message" message)
    (with-open [conn (mq/activemq-connection mq-spec)]
      (apply mq/connect-and-publish! conn mq-endpoint (nippy/freeze message) args))))

(defn subscribe-to-topic
  [topic callback-fn consumer-count]
  (let [mq-spec "vm://localhost?create=false"]
    (with-open [conn (mq/activemq-connection mq-spec)]
      (dotimes [i consumer-count]
        (let [consumer (mq-conn/consumer conn
                                         {:endpoint   topic
                                          :on-message (fn [message]
                                                        (let [body (:body message)
                                                              thawed (nippy/thaw body)]
                                                          (log/info "consuming message" thawed)
                                                          (callback-fn thawed)))
                                          :transacted true
                                          :on-failure #(log/error "error consuming message" (:exception %))})]
          (mq-cons/start consumer))))))
