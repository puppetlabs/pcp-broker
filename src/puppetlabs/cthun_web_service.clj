(ns puppetlabs.cthun-web-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.cthun-web-core :as core]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(trapperkeeper/defservice cthun-web-service
  [[:ConfigService get-in-config]
   [:WebserverService add-ring-handler]
   CthunService]
  (init [this context]
        (log/info "Initializing cthun webservice")
        (let [url-prefix (get-in-config [:cthun :debug-url-prefix])]
          (compojure/context url-prefix []
                             (core/app (get-service :CthunService))
                             url-prefix)
          (assoc context :url-prefix url-prefix))))
