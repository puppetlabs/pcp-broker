(ns puppetlabs.pcp.testutils.server
  (:require [puppetlabs.trapperkeeper.services.websocket-session :as websocket-session]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty10-service :refer [jetty10-service]])
  (:import (java.nio.channels AsynchronousCloseException)))

;; These handlers exist to be redefined.
(defn on-connect [_server _ws])
(defn on-error [_server _ws _e])
(defn on-close [_server _ws _status-code _reason])
(defn on-text [_server _ws _text])
(defn on-bytes [_server _ws _bytes _offset _len])

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
            ;; Close may encounter an async exception if the underlying session has already closed.
            (websocket-session/close! ws)
            (catch AsynchronousCloseException _
              (println "Caught async stopping MockServer!"))))
        ;; TODO: this pause is necessary to allow a successful reconnection
        ;; from the broker. We need to understand why and eliminate the
        ;; problem. This is covered in PCP-720.
        (Thread/sleep 200)
        (dissoc context :inventory)))

(def mock-server-services
  [mock-server webrouting-service jetty10-service])

(defn wait-for-inbound-connection
  [svc-context]
  (loop [i 0]
    (when (empty? @(:inventory svc-context))
      (if (> i 50)
        (throw (Exception. "Test timed out waiting for inbound connection"))
        (do
         (Thread/sleep 100)
         (recur (inc i)))))))
