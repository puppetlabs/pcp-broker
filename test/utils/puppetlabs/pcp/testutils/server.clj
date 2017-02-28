(ns puppetlabs.pcp.testutils.server
  (:require [puppetlabs.experimental.websockets.client :as websockets-client]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]))

;; These handlers exist to be redefined.
(defn on-connect [server ws])
(defn on-error [server ws e])
(defn on-close [server ws status-code reason])
(defn on-text [server ws text])
(defn on-bytes [server ws bytes offset len])

(defprotocol MockServer)

(trapperkeeper/defservice mock-server
  MockServer
  [[:WebroutingService add-websocket-handler]]
  (init [this context]
        (let [inventory (atom [])]
          (doseq [server [:mock-server-1
                          :mock-server-2
                          :mock-server-3]]
            (add-websocket-handler this
              {:on-connect (fn [ws]
                (swap! inventory conj ws)
                (on-connect server ws))
               :on-error   (partial on-error server)
               :on-close   (partial on-close server)
               :on-text    (partial on-text server)
               :on-bytes   (partial on-bytes server)}
              {:route-id  server
               :server-id server}))
          (assoc context :inventory inventory)))
  (stop [this context]
        (doseq [ws @(:inventory context)]
          (try
            ;; Close may encounter a null pointer if the underlying session has already closed.
            (websockets-client/close! ws)
          (catch NullPointerException _)))
        (dissoc context :inventory)))
