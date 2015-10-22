(ns puppetlabs.pcp.broker.activemq
  (:require [clamq.protocol.connection :as mq-conn]
            [clamq.protocol.consumer :as mq-cons]
            [puppetlabs.pcp.broker.capsule :as capsule :refer [Capsule CapsuleLog]]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.structured-logging.core :as sl]
            [schema.core :as s]
            [taoensso.nippy :as nippy]))

;; This is a bit rude/lazy, reaching right into puppetdb sources we've
;; copied into our tree.  If this proves out we should talk to
;; puppetdb about extracting puppetlabs.puppetdb.mq into a common library.

(s/defn ^:always-validate queue-message
  "Queue a message on a middleware"
  [queue :- s/Str capsule :- Capsule & args]
  (let [mq-spec "vm://localhost?create=false"
        mq-endpoint queue]
    (sl/maplog :trace (assoc (capsule/summarize capsule)
                             :queue queue
                             :type :queue-enque)
               "Delivering message {messageid} for {destination} to {queue} queue")
    (with-open [conn (mq/activemq-connection mq-spec)]
      (apply mq/connect-and-publish! conn mq-endpoint (nippy/freeze capsule) args))))

(defn subscribe-to-queue
  [queue callback-fn consumer-count]
  (let [mq-spec "vm://localhost?create=false"]
    (let [conn (mq/activemq-connection mq-spec)]
      (doall (for [i (range consumer-count)]
               (let [consumer (mq-conn/consumer conn
                                                {:endpoint   queue
                                                 :on-message (fn [message]
                                                               (let [body (:body message)
                                                                     capsule (nippy/thaw body)]
                                                                 (sl/maplog :trace (assoc (capsule/summarize capsule)
                                                                                          :queue queue
                                                                                          :type :queue-dequeue)
                                                                            "Consuming message {messageid} for {destination} from {queue}")
                                                                 (callback-fn capsule)))
                                                 :transacted true
                                                 :on-failure (fn [error]
                                                               (sl/maplog :error (:exception error)
                                                                          {:type :queue-dequeue-error
                                                                           :queue queue}
                                                                          "Error consuming message from {queue}"))})]
                 (mq-cons/start consumer)
                 consumer))))))
