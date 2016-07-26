(ns puppetlabs.pcp.broker.connection
  (:require [clojure.string :as str]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.protocol :as p]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (clojure.lang IFn)))

(def Websocket
  "Schema for a websocket session"
  Object)

(def ConnectionState
  "The states it is possible for a Connection to be in"
  (s/enum :open :associated))

(def Codec
  "Message massaging functions"
  {:decode IFn
   :encode IFn})

(defprotocol ConnectionInterface
  "Operations on the Connection type"
  (summarize [connection]
    "Returns a ConnectionLog suitable for logging."))

(declare -summarize)

(s/defrecord Connection
             [state :- ConnectionState
              websocket :- Websocket
              remote-address :- s/Str
              created-at :- p/ISO8601
              codec :- Codec
              common-name :- (s/maybe s/Str)
              uri :- (s/maybe p/Uri)]
  ConnectionInterface
  (summarize [c] (-summarize c)))

(def ConnectionLog
  "summarize a connection for logging"
  {:commonname (s/maybe s/Str)
   :remoteaddress s/Str})

(s/defn make-connection :- Connection
  "Return the initial state for a websocket"
  [websocket :- Websocket codec :- Codec]
  ;; NB: the 'map->...' constructor comes from schema.core's defrecord
  (map->Connection
    {:state          :open
     :websocket      websocket
     :codec          codec
     ;; http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getHostAddress%28%29
     :remote-address (try+
                       (second
                         (str/split (.. (websockets-client/remote-addr websocket) (toString)) #"/"))
                       (catch Exception _
                         ""))
     :common-name    (try+ (when-let [cert (first (websockets-client/peer-certs websocket))]
                             (ks/cn-for-cert cert))
                           (catch Exception _
                             nil))
     :created-at     (ks/timestamp)}))

(s/defn -summarize :- ConnectionLog
  [connection :- Connection]
  (let [{:keys [common-name remote-address]} connection]
    {:commonname common-name
     :remoteaddress remote-address}))
