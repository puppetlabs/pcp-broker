(ns puppetlabs.cthun.validation
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]))

; Server message data types
(def InventoryRequest
  "Data schema for http://puppetlabs.com/inventory_request"
  {:query [s/Str]})

(def InventoryResponse
  "Data schema for http://puppetlabs.com/inventory_response"
  {:uris [message/Uri]})

(def DestinationReport
  "Defines the data field for a destination report body"
  {:id message/MessageId
   :targets [message/Uri]})

(def ErrorMessage
  "Data schema for http://puppetlabs.com/error_message"
  {(s/optional-key :id) message/MessageId
   :description s/Str})

(def TTLExpiredMessage
  "Data schema for http://puppetlabs.com/ttl_expired"
  {:id message/MessageId})

(s/defn ^:always-validate
  explode-uri :- [s/Str]
  "Parse an Uri string into its component parts.  Raises if incomplete"
  [endpoint :- message/Uri]
  (str/split (subs endpoint 6) #"/"))

(defn validate-certname
  "Validate that the cert name advertised by the client matches the cert name in the certificate"
  [endpoint certname]
  (let [[client] (explode-uri endpoint)]
    (if-not (= client certname)
      (throw+ {:type ::identity-invalid
               :message (str "Certificate name used in sender " endpoint " doesn't match the certname in certificate " certname)})
      true)))
