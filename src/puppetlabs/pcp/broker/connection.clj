(ns puppetlabs.pcp.broker.connection
  (:require [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.protocol :as p]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]))

(def Websocket
  "Schema for a websocket session"
  Object)

(def ConnectionState
  "The states it is possible for a Connection to be in"
  (s/enum :open :associated))

(def Connection
  "The state of a connection as managed by the broker in the connections map"
  {:state ConnectionState
   :websocket Websocket
   :remote-address s/Str
   (s/optional-key :uri) p/Uri
   :created-at p/ISO8601})

(def ConnectionLog
  "summarize a connection for logging"
  {:commonname s/Str
   :remoteaddress s/Str})

(s/defn ^:always-validate make-connection :- Connection
  "Return the initial state for a websocket"
  [ws :- Websocket]
  {:state :open
   :websocket ws
   :remote-address (try+ (.. ws (getSession) (getRemoteAddress) (toString))
                         (catch Object _
                           "[UNKNOWN ADDRESS]"))
   :created-at (ks/timestamp)})

(s/defn ^:always-validate get-cn :- s/Str
  "Get the client certificate name from a websocket"
  [connection :- Connection]
  (let [{:keys [websocket]} connection]
    (when-let [cert (first (websockets-client/peer-certs websocket))]
      (ks/cn-for-cert cert))))

(s/defn ^:always-validate summarize :- ConnectionLog
  [connection :- Connection]
  {:commonname (get-cn connection)
   :remoteaddress (:remote-address connection)})
