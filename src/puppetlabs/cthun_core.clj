(ns puppetlabs.cthun-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.websockets :as websockets]
            [puppetlabs.cthun.connection-states :as cs]
            [compojure.core :as compojure]
            [compojure.route :as route]))

(defn- app
  [conf]
  (log/info "App initiated"))

(defn start
  [get-in-config mesh queueing inventory]
  (let [url-prefix (get-in-config [:cthun :url-prefix])
        host (get-in-config [:cthun :host])
        port (get-in-config [:cthun :port])
        config (get-in-config [:cthun])]
    (cs/use-this-inventory inventory)
    (cs/use-this-mesh mesh)
    (cs/use-this-queueing queueing)
    (websockets/start-jetty app url-prefix host port config)))

(defn state
  "Return the service state"
  [caller]
  (log/info "Not yet implemented"))
