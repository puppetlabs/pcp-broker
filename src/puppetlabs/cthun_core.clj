(ns puppetlabs.cthun-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.websockets :as websockets]
            [puppetlabs.cthun.connection-states :as cs]
            [puppetlabs.cthun.metrics :as metrics]
            [compojure.core :as compojure]
            [cheshire.core :as cheshire]
            [compojure.route :as route]))

(defn- websocket-app
  [conf]
  (log/info "Websocket App starting"))

(defn- metrics-app
  [conf]
  (log/info "Metrics App initiated")
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (metrics/get-metrics-string)})


(defn start
  [get-in-config mesh queueing inventory]
  (let [url-prefix (get-in-config [:cthun :url-prefix])
        host (get-in-config [:cthun :host])
        port (get-in-config [:cthun :port])
        config (get-in-config [:cthun])]
    (cs/use-this-inventory inventory)
    (cs/use-this-mesh mesh)
    (cs/use-this-queueing queueing)
    (metrics/enable-cthun-metrics)
    (websockets/start-metrics metrics-app)
    (websockets/start-jetty websocket-app url-prefix host port config)))

(defn state
  "Return the service state"
  [caller]
  (log/info "Not yet implemented"))
