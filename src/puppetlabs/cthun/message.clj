(ns puppetlabs.cthun.message
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]))

(defn add-hop
  "Returns the message with a hop for the specified 'stage' added."
  ([message stage] (add-hop message stage (kitchensink/timestamp)))
  ([message stage timestamp]
     (let [hop {:server "cth://fake/server"
                :time   timestamp
                :stage  stage}]
       (update-in message [:hops] conj hop))))
