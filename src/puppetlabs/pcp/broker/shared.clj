(ns puppetlabs.pcp.broker.shared
  (:require [metrics.gauges :as gauges]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.pcp.broker.connection :as connection :refer [Codec]]
            [puppetlabs.pcp.broker.websocket]
            [puppetlabs.pcp.broker.message :as message :refer [Message multicast-message?]]
            [puppetlabs.pcp.client :as pcp-client]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.metrics :refer [time!]]
            [puppetlabs.structured-logging.core :as sl]
            [schema.core :as s]
            [puppetlabs.i18n.core :as i18n])
  (:import [puppetlabs.pcp.broker.connection Connection]
           [org.joda.time DateTime]
           [clojure.lang IFn]))

(def BrokerState
  (s/enum :starting :running :stopping))

(def PatternSets
  {:explicit #{p/Uri} :wildcard #{p/ExplodedUri}})

(def Subscription
  {;; Promise that resolves to the Connection after inventory response has been
   ;; sent. This ensures no updates are sent before the initial response.
   :connection        Object
   :pattern-sets      PatternSets
   ;; the index of the next update to process (note that to get the offset
   ;; of the corresponding InventoryChange record in the :updates vector, you
   ;; must substract the broker database's :first-update-index from this value)
   :next-update-index s/Int})

(def Inventory
  {p/Uri Connection})

(def BrokerDatabase
  {:inventory          Inventory
   ;; the index of the first InventoryChange record in the :updates vector
   ;; (note that this index can overflow)
   :first-update-index s/Int
   :warning-bin        {p/Uri DateTime}
   :updates            [p/InventoryChange]
   :subscriptions      {p/Uri Subscription}})

(def Broker
  {:broker-name         (s/maybe s/Str)
   :authorization-check IFn
   :max-connections     s/Int
   :max-message-size    s/Int
   :database            (s/atom BrokerDatabase)
   :controllers         (s/atom {p/Uri Connection})
   :should-stop         Object                              ;; Promise used to signal the inventory updates should stop
   :metrics-registry    Object
   :metrics             {s/Keyword Object}
   :state               (s/atom BrokerState)})

(s/defn get-connection :- (s/maybe Connection)
  [broker :- Broker uri :- p/Uri]
  (-> broker :database deref :inventory (get uri)))

(s/defn get-controller :- (s/maybe Connection)
  ([broker :- Broker uri :- p/Uri]
   (get-controller broker uri 0))
  ([broker :- Broker uri :- p/Uri timeout :- s/Int]
   (when-let [controller (get @(:controllers broker) uri)]
     (pcp-client/wait-for-connection (:websocket controller) timeout)
     (if (pcp-client/connected? (:websocket controller))
       controller))))

(s/defn build-and-register-metrics :- {s/Keyword Object}
  [broker :- Broker]
  (let [registry (:metrics-registry broker)]
    (gauges/gauge-fn registry ["puppetlabs.pcp.connections"]
                     (fn [] (-> broker :database deref :inventory count)))
    {:on-connect       (.timer registry "puppetlabs.pcp.on-connect")
     :on-close         (.timer registry "puppetlabs.pcp.on-close")
     :on-message       (.timer registry "puppetlabs.pcp.on-message")
     :on-send          (.timer registry "puppetlabs.pcp.on-send")}))

;;
;; Message sending
;;

(def MessageLog
  "Schema for a loggable summary of a message"
  {:messageid p/MessageId
   :source s/Str
   :messagetype s/Str
   :destination p/Uri})

(s/defn summarize :- MessageLog
  [message :- Message]
  {:messageid (:id message)
   :messagetype (:message_type message)
   :source (or (:sender message) "pcp:///server")
   :destination (or (:target message) "pcp:///server")})

(s/defn send-message
  [connection :- Connection
   message :- Message]
  (sl/maplog :trace {:type :outgoing-message-trace
                     :uri (:uri connection)
                     :rawmsg message}
             ;; 0 : connection uri
             ;; 1 : raw message
             #(i18n/trs "Sending PCP message to {0}: {1}" (:uri %) (:rawmsg %)))
  (websockets-client/send! (:websocket connection)
                           ((get-in connection [:codec :encode]) message))
  nil)

(s/defn send-error-message
  [in-reply-to-message :- (s/maybe Message)
   description :- s/Str
   connection :- Connection]
  (let [error-msg (cond-> (message/make-message
                            {:message_type "http://puppetlabs.com/error_message"
                             :data description})
                          in-reply-to-message (assoc :in_reply_to (:id in-reply-to-message)))]
    (try
      (locking (:websocket connection)
        (send-message connection error-msg))
      (catch Exception e
        (sl/maplog :warn e
                   {:target (:uri connection)
                    :type :message-delivery-error}
                   #(i18n/trs "Attempted error message delivery to {0} failed." (:target %)))))))

(s/defn log-delivery-failure
  "Log message delivery failure given the message and failure reason."
  [message :- Message reason :- s/Str]
  (sl/maplog :trace (assoc (summarize message)
                           :type :message-delivery-failure
                           :reason reason)
             ;; 0 : message id (uuid)
             ;; 1 : destination uri
             ;; 2 : reason for failure
             #(i18n/trs "Failed to deliver {0} for {1}: {2}"
               (:messageid %) (:destination %) (:reason %)))
  nil)                                                      ;; ensure nil is returned

(s/defn handle-delivery-failure
  "Send an error message with the specified description."
  [message :- Message sender :- (s/maybe Connection) reason :- s/Str]
  (log-delivery-failure message reason)
  (send-error-message message reason sender))

(s/defn deliver-message
  "Message consumer. Delivers a message to the websocket indicated by the :target field"
  [broker :- Broker
   message :- Message
   sender :- (s/maybe Connection)]
  (assert (not (multicast-message? message)))
  (if-let [connection (or (get-connection broker (:target message))
                          (get-controller broker (:target message)))]
    (try
      (sl/maplog
        :debug (merge (summarize message)
                      (connection/summarize connection)
                      {:type :message-delivery})
        ;; 0 : message id (uuid)
        ;; 1 : destination uri
        ;; 2 : remote address
        #(i18n/trs "Delivering {0} to {1} at {2}."
          (:messageid %) (:destination %) (:remoteaddress %)))
      (locking (:websocket connection)
        (time! (:on-send (:metrics broker))
               (send-message connection message)))
      (catch Exception e
        (sl/maplog :warn e
                   (merge (summarize message)
                          {:type :message-delivery-error})
                   #(i18n/trs "Attempted message delivery to {0} failed." (:destination %)))
        (handle-delivery-failure message sender (str e))))
    (handle-delivery-failure message sender (i18n/trs "Not connected."))))

(s/defn deliver-server-message
  "Message consumer. Delivers a message to the websocket indicated by the :target field but only if it still
  routed to the connection specified by the client argument"
  [broker :- Broker
   message :- Message
   client :- Connection]
  (assert (not (multicast-message? message)))
  (let [connection (or (get-connection broker (:target message))
                       (get-controller broker (:target message)))]
    (if (identical? connection client)
      (try
        (sl/maplog
          :debug (merge (summarize message)
                        (connection/summarize connection)
                        {:type :message-delivery})
          ;; 0 : message id (uuid)
          ;; 1 : destination uri
          ;; 2 : remote address
          #(i18n/trs "Delivering {0} to {1} at {2}."
            (:messageid %) (:destination %) (:remoteaddress %)))
        (locking (:websocket connection)
          (time! (:on-send (:metrics broker))
                 (send-message connection message))
          true)
        (catch Exception e
          (sl/maplog :warn e
                     (merge (summarize message)
                            {:type :message-delivery-error})
                     #(i18n/trs "Attempted message delivery to {0} failed." (:destination %)))
          (log-delivery-failure message (str e))))
      (log-delivery-failure message (i18n/trs "Client no longer connected.")))))
