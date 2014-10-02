(ns puppetlabs.cthun.meshing.hazelcast
  (:require [cheshire.core :as cheshire]
            [clojure.tools.logging :as log]
            [puppetlabs.cthun.meshing :refer [MeshingService]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context]])
  (:import com.hazelcast.core.Hazelcast)
  (:import com.hazelcast.core.MessageListener))

(defn make-hazelcast-service
  [context]
  (let [hazelcast (Hazelcast/newHazelcastInstance)
        location-map (.getMultiMap hazelcast "location-map")
        broker-id (str (java.util.UUID/randomUUID))]
    (merge context
           {:hazelcast hazelcast
            :broker-id broker-id
            :location-map location-map})))

(defn record-client-location
  [service client]
  (let [{:keys [location-map broker-id]} (service-context service)]
    (.put location-map broker-id client)))

(defn forget-client-location
  [service client]
  (let [{:keys [location-map broker-id]} (service-context service)]
    (.remove location-map broker-id client)))

(defn flatten-location-map
  [location-map]
  (flatten (for [key (.keySet location-map)]
             (for [value (.get location-map key)]
               {:broker key
                :endpoint value}))))

(defn broker-for-endpoint
  "look up the endpoint in the location-map, return broker names"
  [location-map endpoint]
  ;; This is not terribly efficient as we flatten the entire map
  (:broker (first (filter (fn [record]
                              (= endpoint (:endpoint record)))
                          (flatten-location-map location-map)))))

(defn deliver-to-client
  [service client message]
  (let [{:keys [location-map broker-id hazelcast]} (service-context service)]
    (log/info "queueing for " client)
    (if-let [broker (broker-for-endpoint location-map client)]
      (let [topic (.getTopic hazelcast broker-id)]
        (log/info "publishing to broker" broker-id)
        (.publish topic (cheshire/generate-string message)))
      (log/warn "didn't find broker for" client))))

(defn register-local-delivery
  [service callback]
  (let [{:keys [hazelcast broker-id]} (service-context service)
        topic (.getTopic hazelcast broker-id)]
    (log/info "subscribing to" topic)
    (.addMessageListener topic
                         (reify MessageListener
                           (onMessage [this message]
                             (log/info "got message from hazelcast" message)
                             (let [message (cheshire/parse-string (.getMessageObject message) true)]
                               (log/info "decoded message from hazelcast" message)
                               (callback message)))))))

(defservice meshing-service
  "Hazelcast implementation of the meshing service"
  MeshingService
  []
  (init [this context] (make-hazelcast-service context))
  (record-client-location [this client] (record-client-location this client))
  (forget-client-location [this client] (forget-client-location this client))
  (deliver-to-client [this client message] (deliver-to-client this client message))
  (register-local-delivery [this delivery-fn] (register-local-delivery this delivery-fn)))
