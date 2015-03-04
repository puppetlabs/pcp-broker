(ns puppetlabs.cthun.message
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [cheshire.core :as cheshire]
            [org.clojars.smee.binary.core :as b]
            [puppetlabs.cthun.validation :as validation]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

;; string<->byte-array utilities

(defn string->bytes
  "Returns an array of bytes from a string"
  [s]
  (byte-array (map byte s)))

(defn bytes->string
  "Returns a string given a byte-array"
  [bytes]
  (String. bytes))

;; abstract message manipulation

(defn make-message
  "Returns a new empty message structure"
  []
  {:id ""
   :endpoints []
   :data_schema ""
   :sender ""
   :expires nil
   :_hops []
   :_data_frame (byte-array 0)
   :_data_flags #{}
   :_destination ""})

(defn filter-private
  "Returns the map without any of the known 'private' keys.  Should
  map to an envelope schema."
  [message]
  (-> message
      (dissoc :_destination)
      (dissoc :_data_frame)
      (dissoc :_data_flags)
      (dissoc :_hops)))

(defn add-hop
  "Returns the message with a hop for the specified 'stage' added."
  ([message stage] (add-hop message stage (kitchensink/timestamp)))
  ([message stage timestamp]
   ;; TODO(richardc) this server field should come from the cert of this instance
     (let [hop {:server "cth://fake/server"
                :time   timestamp
                :stage  stage}
           hops (vec (:_hops message))
           new-hops (conj hops hop)]
       (assoc message :_hops new-hops))))

(defn get-data
  "Returns the data from the data frame"
  [message]
  (:_data_frame message))

(defn set-data
  "Sets the data for the data frame"
  ([message data] (set-data message data #{}))
  ([message data flags]
   (-> message
       (assoc :_data_frame data)
       (assoc :_data_flags flags))))

(defn get-json-data
  "Returns the data from the data frame decoded from json"
  [message]
  (let [data (get-data message)
        decoded (cheshire/parse-string (bytes->string data) true)]
    decoded))

(defn set-json-data
  "Sets the data to be the json byte-array version of data"
  [message data]
  (set-data message (string->bytes (cheshire/generate-string data))))

;; message encoding/codecs

(def flag-bits
  {2r1000 :unused1
   2r0100 :unused2
   2r0010 :unused3
   2r0001 :unused4})

(defn encode-descriptor
  "Returns a binary representation of a chunk descriptor"
  [type]
  (let [type-bits (:type type)
        flag-set  (:flags type)
        flags (apply bit-or 0 0 (remove nil? (map (fn [[mask name]] (if (name flag-set) mask)) flag-bits)))
        byte (bit-or type-bits (bit-shift-left flags 4))]
    byte))

(defn decode-descriptor
  "Returns the clojure object for a chunk descriptor from a byte"
  [byte]
  (let [type (bit-and 0x0F byte)
        flags (bit-shift-right (bit-and 0xF0 byte) 4)
        flag-set (into #{} (remove nil? (map (fn [[mask name]] (if (= mask (bit-and mask flags)) name)) flag-bits)))]
    {:flags flag-set
     :type type}))

(def descriptor-codec
  (b/compile-codec :byte encode-descriptor decode-descriptor))

(def chunk-codec
  (b/ordered-map
   :descriptor descriptor-codec
   :data (b/blob :prefix :int-be)))

(def message-codec
  (b/ordered-map
   :version (b/constant :byte 1)
   :chunks (b/repeated chunk-codec)))

(defn encode
  "Returns a byte-array containing the message in network"
  [message]
  (let [stream (java.io.ByteArrayOutputStream.)
        hops (:_hops message)
        envelope (string->bytes (cheshire/generate-string (filter-private message)))
        debug-data (string->bytes (cheshire/generate-string {:hops hops}))
        envelope (string->bytes (cheshire/generate-string (filter-private (dissoc message :hops :data))))
        data-frame (or (:_data_frame message) (byte-array 0))
        data-flags (or (:_data_flags message) #{})]
    (b/encode message-codec stream
              {:chunks (remove nil? [{:descriptor {:type 1}
                                      :data envelope}
                                     {:descriptor {:type 2
                                                   :flags data-flags}
                                      :data data-frame}
                                     (if hops
                                       {:descriptor {:type 3}
                                        :data debug-data})])})
    (.toByteArray stream)))

(defn decode
  "Returns a message object from a network format message"
  [bytes]
  (let [stream (java.io.ByteArrayInputStream. bytes)
        decoded (try+
                 (b/decode message-codec stream)
                 (catch Throwable _
                   (throw+ {:type ::message-malformed
                            :message (:message &throw-context)})))]
    (log/info "decoded as" decoded)
    (log/info "envelope" (bytes->string (:data (first (:chunks decoded)))))
    (log/info "body"     (bytes->string (or (:data (second (:chunks decoded))) (byte-array 0))))
    (if (not (= 1 (get-in (first (:chunks decoded)) [:descriptor :type])))
      (throw+ {:type ::message-invalid
               :message "first chunk should be type 1"}))
    (let [envelope-json (bytes->string (:data (first (:chunks decoded))))
          envelope (try+
                    (cheshire/decode envelope-json true)
                    (catch Exception _
                      (throw+ {:type ::envelope-malformed
                               :message (:message &throw-context)})))
          data-chunk (second (:chunks decoded))
          data-frame (or (:data data-chunk) (byte-array 0))
          data-flags (or (get-in data-chunk [:descriptor :flags]) #{})]
      (try+ (s/validate validation/Envelope envelope)
            (catch Throwable _
              (throw+ {:type ::envelope-invalid
                       :message (:message &throw-context)})))
      (set-data envelope data-frame data-flags))))
