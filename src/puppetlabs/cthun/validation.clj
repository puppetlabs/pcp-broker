(ns puppetlabs.cthun.validation
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [schema.core :as s]))

(def ISO8601
  "Schema validates if string conforms to ISO8601"
  (s/pred ks/datetime? 'datetime?))

; Message types
(def ClientMessage
  "Defines the message format expected from a client"
  {(s/required-key :version) s/Str
   (s/required-key :id) s/Int
   (s/required-key :endpoints) [s/Str]
   (s/required-key :data_schema) s/Str
   (s/required-key :sender) s/Str
   (s/required-key :expires) ISO8601
   (s/required-key :hops) [{s/Keyword ISO8601}]
   (s/optional-key :data) {s/Keyword s/Str}})

; Server message data types
(def LoginMessageData
  "Defines the data field in a login message body"
  {(s/required-key :type) s/Str
   (s/required-key :user) s/Str})

(defn check-schema
  "Check if the JSON matches the schema"
  [json]
  (s/validate ClientMessage json))

(defn validate-message
  "Validate the structure of a message"
  [message]
  (let [json (try (cheshire/parse-string message true)
                  (catch Exception e false))]
    (when json
      (check-schema json))))


(defn validate-login-data
  "Validate the structure of a login message data field"
  [data]
  (s/validate LoginMessageData data))
