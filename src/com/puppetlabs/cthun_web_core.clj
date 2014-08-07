(ns com.puppetlabs.cthun-web-core
  (:require [com.puppetlabs.cthun-service :as hello-svc]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [compojure.route :as route]))

(defn app
  [hello-service]
  (compojure/routes
    (compojure/GET "/:caller" [caller]
      (fn [req]
        (log/info "Handling request for caller:" caller)
        {:status  200
         :headers {"Content-Type" "text/plain"}
         :body    (hello-svc/hello hello-service caller)}))
    (route/not-found "Not Found")))
