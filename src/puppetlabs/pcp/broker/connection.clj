(ns puppetlabs.pcp.broker.connection
  (:require [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.broker.websocket :refer [Websocket ws->remote-address ws->common-name]]
            [puppetlabs.pcp.protocol :as p]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (clojure.lang IFn)))

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
             [websocket :- Websocket
              remote-address :- s/Str
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
  [websocket :- Websocket
   codec :- Codec]
  ;; NOTE(ale): the 'map->...' constructor comes from schema.core's defrecord
  (map->Connection
   {:websocket      websocket
    :codec          codec
    :remote-address (ws->remote-address websocket)
    :common-name    (ws->common-name websocket)}))

(s/defn -summarize :- ConnectionLog
  [connection :- Connection]
  (let [{:keys [common-name remote-address]} connection]
    {:commonname common-name
     :remoteaddress remote-address}))
