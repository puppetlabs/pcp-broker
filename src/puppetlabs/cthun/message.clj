(ns puppetlabs.cthun.message
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [cheshire.core :as cheshire]
            [puppetlabs.cthun.validation :as validation]))

(defn make-message
  "Returns a new empty message structure"
  []
  {:version ""
   :id ""
   :endpoints []
   :data_schema ""
   :sender ""
   :expires nil
   :hops []
   :data {}
   :_destination ""})

(defn filter-private
  "Returns the map without any of the known 'private' keys."
  [message]
  (-> message
      (dissoc :_destination)))

(defn decode
  "Returns a new message structure decoded from network format"
  [bytes]
  (let [parsed (cheshire/parse-string bytes true)]
    (validation/check-schema parsed) ;; raises
    (merge (make-message) parsed)))

(defn encode
  "Returns the message structure in network format"
  [message]
  (let [sending (filter-private message)]
    (cheshire/generate-string sending)))

(defn add-hop
  "Returns the message with a hop for the specified 'stage' added."
  ([message stage] (add-hop message stage (kitchensink/timestamp)))
  ([message stage timestamp]
   ;; TODO(richardc) this server field should come from the cert of this instance
     (let [hop {:server "cth://fake/server"
                :time   timestamp
                :stage  stage}]
       (update-in message [:hops] conj hop))))
