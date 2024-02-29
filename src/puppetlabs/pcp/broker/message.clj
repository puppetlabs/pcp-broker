(ns puppetlabs.pcp.broker.message
  (:require [puppetlabs.pcp.message-v1 :as m1]
            [puppetlabs.pcp.message-v2 :as m2]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.pcp.broker.util :refer [update-when assoc-when ensure-vec]]
            [clojure.set :refer [rename-keys]]
            [schema.core :as s]))

;; Allow an extra multicast-message parameter for flagging multicast messages.
(def Message (assoc m2/Message (s/optional-key :multicast-message) s/Bool))

(def make-message m2/make-message)

(s/defn multicast-message? :- s/Bool
  "Returns a boolean specifying whether the message uses multicast in the target field."
  [message :- Message]
  (boolean (or (:multicast-message message)
               (and (:target message)
                    (p/uri-wildcard? (:target message))))))

(s/defn validate-not-multicast
  [message]
  (let [multicast? (when-let [target (:targets message)]
                     (when-let [first-target (first target)]
                       (or (not= 1 (count target))
                           (p/uri-wildcard? first-target))))]
    (cond-> message
      multicast? (assoc :multicast-message true))))

(s/defn v1->v2 :- Message
  "Transform a v1 Message to a v2 Message."
  [v1-msg :- m1/Message]
  (let [data (m1/get-json-data v1-msg)
        message (-> v1-msg
                    (dissoc :expires :destination_report :_chunks)
                    validate-not-multicast
                    (rename-keys {:in-reply-to :in_reply_to
                                  :targets :target})
                    (update-when [:target] first))
        is-error (= "http://puppetlabs.com/error_message" (:message_type message))
        message' (cond-> message
                   (and is-error (:id data)) (assoc :in_reply_to (:id data)))
        data' (cond-> data
                is-error (:description))]
    (cond-> message'
      data' (assoc :data data'))))

(s/defn v2->v1 :- m1/Message
  "Transform a v2 Message to a v1 Message."
  [{:keys [message_type data in_reply_to] :as v2-msg} :- Message]
  (let [;; transform error_message data
        data (if (= "http://puppetlabs.com/error_message" message_type)
               (cond-> {:description data}
                 in_reply_to (assoc :id in_reply_to))
               data)
        ;; strip in_reply_to from non-inventory_response
        message (if (= "http://puppetlabs.com/inventory_response" message_type)
                  v2-msg
                  (dissoc v2-msg :in_reply_to))]
    (-> message
        (dissoc :data)
        (rename-keys {:in_reply_to :in-reply-to
                      :target :targets})
        (assoc-when :targets []
                    :sender "pcp:///server")
        (update :targets ensure-vec)
        (assoc :expires "1970-01-01T00:00:00.000Z"
               :_chunks {})
        (m1/set-json-data data))))

(s/defn v1-encode :- bytes
  "Encode a clojure map conforming to the latest message scheme to the
   v1 binary wire format."
  [message :- Message]
  (m1/encode (v2->v1 message)))

(s/defn v1-decode :- Message
  "Transform a v1 wire format message to a clojure map conforming
   to the latest message schema."
  [bytes :- bytes]
  (v1->v2 (m1/decode bytes)))

(def v1-codec
  {:encode v1-encode
   :decode v1-decode})

(def v2-encode m2/encode)

(def v2-decode m2/decode)

(def v2-codec
  {:encode m2/encode
   :decode m2/decode})
