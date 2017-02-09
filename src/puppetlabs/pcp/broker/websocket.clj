(ns puppetlabs.pcp.broker.websocket
  (:require [clojure.string :as str]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (java.net InetSocketAddress InetAddress)))

(def Websocket
  "Schema for a websocket session"
  Object)

(defn ws->remote-address
  "Get the IP address (or hostname if the IP address is not resolved) and port
  out of the InetSocketAddress object."
  [ws]
  (try
    (let [^InetSocketAddress socket-address (websockets-client/remote-addr ws)
          ^InetAddress inet-address (.getAddress socket-address)]
      (str (if (nil? inet-address)
             (.getHostName socket-address)
             (.getHostAddress inet-address))
           \:
           (.getPort socket-address)))
    (catch Exception _
      "")))

(defn ws->common-name
  [ws]
  (try+
   (when-let [cert (first (websockets-client/peer-certs ws))]
     (ks/cn-for-cert cert))
   (catch Exception _
     nil)))

(defn ws->client-path
  [ws]
  (let [path (websockets-client/request-path ws)]
    (if (or (empty? path) (= "/" path))
      "/agent"
      path)))

(defn ws->client-type
  [ws]
  (subs (ws->client-path ws) 1))

(defn ws->uri
  "Construct a URI based on properties of the websocket session. Defaults to
  `agent` client type if none can be discerned."
  [ws]
  (str "pcp://" (ws->common-name ws) (ws->client-path ws)))
