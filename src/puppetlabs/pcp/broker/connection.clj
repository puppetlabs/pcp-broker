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
              codec :- Codec
              uri :- p/Uri]
  ConnectionInterface
  (summarize [c] (-summarize c)))

(def ConnectionLog
  "summarize a connection for logging"
  {:commonname (s/maybe s/Str)
   :remoteaddress s/Str})

(s/defn make-connection :- Connection
  "Return the initial state for a websocket"
  [websocket :- Websocket
   codec :- Codec
   uri :- p/Uri]
  ;; NOTE(ale): the 'map->...' constructor comes from schema.core's defrecord
  (map->Connection
   {:websocket      websocket
    :codec          codec
    :uri            uri}))

(s/defn -summarize :- ConnectionLog
  [connection :- Connection]
  (let [websocket (:websocket connection)]
    {:commonname    (ws->common-name websocket)
     :remoteaddress (ws->remote-address websocket)}))
