(ns puppetlabs.cthun-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.websockets :as websockets]
            [compojure.core :as compojure]
            [compojure.route :as route]))

(defn- app
  [conf]
  (log/info "App initiated"))

(defn start
  [get-in-config]
  (let [url-prefix (get-in-config [:cthun :url-prefix])
        host (get-in-config [:cthun :host])
        port (get-in-config [:cthun :port])]
    (websockets/start-jetty app url-prefix host port)))

(defn state
  "Return the service state"
  [caller]
  (log/info "Not yet implemented"))
