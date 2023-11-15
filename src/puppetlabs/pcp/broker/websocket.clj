(ns puppetlabs.pcp.broker.websocket
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.websocket-session :as websocket-session]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pcp.client :as pcp-client])
  (:import (puppetlabs.pcp.client Client)
           (java.net InetSocketAddress InetAddress)
           (org.eclipse.jetty.websocket.api WebSocketAdapter)))

(def Websocket
  "Schema for a websocket session"
  Object)

(extend-protocol websocket-session/WebSocketProtocol
  Client
  (send!         [c msg]      (pcp-client/send! c msg))
  (close!        [c code msg] (pcp-client/close c))
  (remote-addr   [c]          (-> c :websocket-client (.getOpenSessions) first (.getRemoteAddress)))
  (ssl?          [c]          true)
  (peer-certs    [c]          nil)
  (request-path  [c]          "/server")
  (idle-timeout! [c timeout]  nil)
  (connected?    [c]          (pcp-client/connected? c)))

(defprotocol WebsocketInterface
  "Operations on an underlying Websocket connection"
  (ws->common-name [ws]
    "Returns the common name of an endpoint using SSL"))

(extend-protocol WebsocketInterface
  WebSocketAdapter
  (ws->common-name [ws]
    (try
      (when-let [cert (first (websocket-session/peer-certs ws))]
        (puppetlabs.ssl-utils.core/get-cn-from-x509-certificate cert))
      (catch Exception _
        nil)))

  Client
  (ws->common-name [c]
    (let [uri (-> c :websocket-client (.getOpenSessions) first (.getUpgradeRequest) (.getRequestURI))]
      (.getAuthority uri))))

(defn ws->remote-address
  "Get the IP address (or hostname if the IP address is not resolved) and port
  out of the InetSocketAddress object."
  [ws]
  (try
    (if-let [^InetSocketAddress socket-address (websocket-session/remote-addr ws)]
      (let [^InetAddress inet-address (.getAddress socket-address)]
        (str (if (nil? inet-address)
               (.getHostName socket-address)
               (.getHostAddress inet-address))
             \:
             (.getPort socket-address)))
      "")
    (catch Exception e
      (log/trace e "Failure trying to get remote address")
      "")))

(defn ws->client-path
  [ws]
  (let [path (websocket-session/request-path ws)]
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
