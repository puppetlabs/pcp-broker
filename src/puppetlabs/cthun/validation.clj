(ns puppetlabs.cthun.validation
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [cheshire.core :as cheshire]
            [schema.core :as s]))

(def ISO8601
  "Schema validates if string conforms to ISO8601"
  (s/pred ks/datetime? 'datetime?))

(def Endpoint
  "Pattern that matches valid endpoints"
  (s/pred (partial re-matches #"cth://(server|.*/.*)") 'endpoint?))

(def MessageHop
  "Map that describes a step in message delivery"
  {(s/required-key :server) Endpoint
   (s/optional-key :stage) s/Str
   (s/required-key :time) ISO8601})

; Message types
(def ClientMessage
  "Defines the message format expected from a client"
  {(s/required-key :version) s/Str
   (s/required-key :id) s/Str ;; TODO(richardc) check it looks like a UUID maybe?
   (s/required-key :endpoints) [Endpoint]
   (s/required-key :data_schema) s/Str
   (s/required-key :sender) Endpoint
   (s/required-key :expires) ISO8601
   (s/required-key :hops) [MessageHop]
   (s/optional-key :data) {s/Keyword s/Any}})

; Server message data types
(def LoginMessageData
  "Defines the data field in a login message body"
  {(s/required-key :type) s/Str})

(def InventoryMessageData
  "Defines the data field for an inventory message body"
  {(s/required-key :query) [s/Str]})

(s/defn ^:always-validate
  explode-endpoint :- [s/Str]
  "Parse an endpoint string into its component parts.  Raises if incomplete"
  [endpoint :- Endpoint]
  (str/split (subs endpoint 6) #"/"))

(defn check-schema
  "Check if the JSON matches the ClientMessage schema.
  Returns message on success.
  Throws on failure."
  [json]
  (s/validate ClientMessage json))

(defn- check-certname
  "Validate that the cert name advertised by the client matches the cert name in the certificate"
  [endpoint certname]
  (if-not (re-matches (re-pattern (str "cth://" certname "/.*")) endpoint)
    (log/warn "Certifcate name used in sender " endpoint " doesn't match the certname in certificate "certname)
    true))

(defn validate-message
  "Validates the structure of a message.
  Returns message on success.
  Returns false on invalid json.
  Throws on valid json with an invalid schema
  Returns false if certname used in endpoint doesn't match certname in certificate"
  [message certname]
  (let [json (try (cheshire/parse-string message true)
                  (catch Exception e (log/error (.getMessage e)) false))]
    (when json
      (check-schema json)
      (when (check-certname (:sender json) certname)
        json))))

(defn validate-login-data
  "Validate the structure of a login message data field"
  [data]
  (s/validate LoginMessageData data))

(defn validate-inventory-data
  "Validate the structure of a inventory message data field"
  [data]
  (s/validate InventoryMessageData data))
