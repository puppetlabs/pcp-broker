(ns puppetlabs.cthun.broker.capsule
  (:require [clj-time.coerce :as time-coerce]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [puppetlabs.cthun.message :as message :refer [Message]]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s])
  (:import (org.joda.time DateTime)))


;; A Capsule is a message as it moves across the broker from queue to
;; queue.  Currently it contains an actual Message, but in future it
;; might make sense to just contain a message-id with the message
;; itself in some other persistent storage.

(def Capsule
  "Schema for a message moving through the broker"
  {:expires                 DateTime
   :message                 Message
   :hops                    [message/MessageHop]
   (s/optional-key :target) message/Uri})

(s/defn ^:always-validate add-hop :- Capsule
  "Adds a debug hop to the message state"
  ([capsule :- Capsule server :- message/Uri stage :- s/Str]
   (add-hop capsule server stage (ks/timestamp)))
  ([state :- Capsule server :- message/Uri stage :- s/Str timestamp :- message/ISO8601]
   (let [hop {:server server
              :time   timestamp
              :stage  stage}]
     (assoc state :hops (conj (vec (:hops state)) hop)))))

(s/defn ^:always-validate expired? :- s/Bool
  "Check whether a message has expired or not"
  [message :- Capsule]
  (let [expires (:expires message)
        now     (time/now)]
    (time/after? now expires)))

(s/defn ^:always-validate encode :- message/ByteArray
  "Return the bytes we should send when sending this Capsule.  Adds
  the debug chunk to the message"
  [capsule :- Capsule]
  (let [message (:message capsule)
        hops    (:hops capsule)]
    (message/encode (message/set-json-debug message {:hops hops}))))

(s/defn ^:always-validate wrap :- Capsule
  "Wrap a Message producing a Capsule"
  [message :- Message]
  {:expires  (time-coerce/to-date-time (:expires message))
   :message  message
   :hops     []})
