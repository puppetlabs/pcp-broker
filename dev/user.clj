(ns user
  (:require [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [clojure.tools.namespace.repl :as repl]))

;; NOTE: in some other projects, where we need to support per-developer config
;;  variations, we'll put this code into a namespace called 'user-repl', and
;;  allow devs to build out their own 'user.clj' that wraps it.  See Puppet Server
;;  for an example, but YMMV and this simpler approach may be fine.

(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (let [services (tk-bootstrap/parse-bootstrap-config! "./test-resources/bootstrap.cfg")
                  config (tk-config/load-config "./test-resources/conf.d")]
              (tk/build-app services config))))
  (alter-var-root #'system tk-app/init)
  (tk-app/check-for-errors! system))

(defn start []
  (alter-var-root #'system
    (fn [s] (if s (tk-app/start s))))
  (tk-app/check-for-errors! system))

(defn stop []
  (alter-var-root #'system
    (fn [s] (when s (tk-app/stop s)))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (repl/refresh :after 'user/go))
