(ns puppetlabs.pcp.broker.websocket
  (:require [clojure.string :as str]
            [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]))

(def Websocket
  "Schema for a websocket session"
  Object)

(defn ws->remote-address
  "Extract IP address and port out of the string representation
  of the InetAddress instance ('hostname/socket' format), so that we
  don't end up with a remote-address like '/0:0:0:0:0:0:0:1:56824'. See:
  http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getHostAddress%28%29
  http://stackoverflow.com/questions/6932902/apache-mina-how-to-get-the-ip-from-a-connected-client"
  [ws]
  (try+
   (second
    (str/split (.. (websockets-client/remote-addr ws) (toString)) #"/"))
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
