(ns puppetlabs.pcp.testutils.server
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]))

;; These handlers exist to be redefined.
(defn on-connect [ws])
(defn on-error [ws e])
(defn on-close [ws status-code reason])
(defn on-text [ws text])
(defn on-bytes [ws bytes offset len])

(trapperkeeper/defservice mock-server
  [[:WebroutingService add-websocket-handler]]
  (init [this context]
        (add-websocket-handler this {:on-connect on-connect
                                     :on-error   on-error
                                     :on-close   on-close
                                     :on-text    on-text
                                     :on-bytes   on-bytes})
        context))
