(ns puppetlabs.cthun-web-core
  (:require [puppetlabs.cthun-service :as cthun-svc]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [compojure.route :as route]))

(defn app
  [cthun-service]
  (compojure/routes
    (compojure/GET "/:caller" [caller]
      (fn [req]
        (log/info "Handling request for caller:" caller)
        {:status  200
         :headers {"Content-Type" "text/plain"}
         :body    (cthun-svc/state cthun-service caller)}))
    (route/not-found "Not Found")))
