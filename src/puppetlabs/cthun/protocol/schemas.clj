(ns puppetlabs.cthun.protocol.schemas
  (:require [puppetlabs.cthun.message :as message]
            [schema.core :as s]))

;; TODO(richardc): move these core pcp schemas to
;; clj-cthun-message (or cthun-protocol) repo

(def AssociateResponse
  "Schema for http://puppetlabs.com/associate_response"
  {:id message/MessageId
   :success s/Bool
   (s/optional-key :reason) s/Str})

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
