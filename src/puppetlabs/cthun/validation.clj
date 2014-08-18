(ns puppetlabs.cthun.validation
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [cheshire.core :as cheshire]
            [schema.core :as s]))

(def ISO8601
  "Schema validates if string conforms to ISO8601"
  (s/pred kitchensink/datetime? 'datetime?))

(def ClientMessage
  "Defines the message format expected from a client"
  {(s/required-key "version") s/Str
   (s/required-key "id") s/Int
   (s/required-key "endpoints") [s/Str]
   (s/required-key "data_schema") s/Str
   (s/required-key "sender") s/Str
   (s/required-key "expires") ISO8601
   (s/required-key "hops") [{s/Str ISO8601}]
   (s/optional-key "data") {s/Str s/Str}})

(defn check-schema
  "Check if the JSON matches the schema"
  [json]
  (s/validate ClientMessage json))

(defn validate-message
  "Validate the structure of a message"
  [message]
  (let [json (try (cheshire/parse-string message)
                  (catch Exception e false))]
    (when json
      (check-schema json))))
