(ns puppetlabs.pcp.broker.subscription-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.testutils.service :refer [broker-config protocol-versions broker-services get-broker]]
            [puppetlabs.pcp.testutils.client :as client]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]))

(deftest inventory-node-recieves-updates-when-inventory-changes-when-subscribed-to-updates
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client (client/connect :certname "client01.example.com"
                                                  :version version)]
                 (let [request (client/make-message
                                 version
                                 {:message_type "http://puppetlabs.com/inventory_request"
                                  :target "pcp:///server"
                                  :sender "pcp://client01.example.com/agent"
                                  :data {:query ["pcp://client02.example.com/agent"]
                                         :subscribe true}})]
                   (client/send! client request))
                 (let [response (client/recv! client)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                   (is (= [] (:uris data))))
                 (with-open [client2 (client/connect :certname "client02.example.com"
                                                     :version version)]
                   (let [response (client/recv! client)
                         data (client/get-data response version)]
                     (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                     (is (= [{:client "pcp://client02.example.com/agent" :change 1}] (:changes data)))))
                 (let [response (client/recv! client)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                   (is (= [{:client "pcp://client02.example.com/agent" :change -1}] (:changes data))))))))

(deftest inventory-updates-are-properly-filtered-and-batched
  (with-app-with-config app broker-services broker-config
    (dotestseq [version protocol-versions]
               (with-open [client1 (client/connect :certname "client01.example.com"
                                                   :version version)
                           client2 (client/connect :certname "client02.example.com"
                                                   :version version)]
                 ;; subscribe client1 for updates with a specific filter
                 (let [request (client/make-message
                                 version
                                 {:message_type "http://puppetlabs.com/inventory_request"
                                  :target "pcp:///server"
                                  :sender "pcp://client01.example.com/agent"
                                  :data {:query ["pcp://client04.example.com/*"]
                                         :subscribe true}})]
                   (client/send! client1 request))
                 (let [response (client/recv! client1)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                   (is (= [] (:uris data))))

                 ;; subscribe client2 for updates with a match all filter
                 (let [request (client/make-message
                                 version
                                 {:message_type "http://puppetlabs.com/inventory_request"
                                  :target "pcp:///server"
                                  :sender "pcp://client02.example.com/agent"
                                  :data {:query ["pcp://*/agent"]
                                         :subscribe true}})]
                   (client/send! client2 request))
                 (let [response (client/recv! client2)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
                   (is (= ["pcp://client01.example.com/agent" "pcp://client02.example.com/agent"] (:uris data))))

                 ;; now connect a client matching only the filter client2 used for subscribing
                 (with-open [client3 (client/connect :certname "client03.example.com"
                                                     :version version)]
                   (let [response (client/recv! client2)
                         data (client/get-data response version)]
                     (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                     (is (= [{:client "pcp://client03.example.com/agent" :change 1}] (:changes data))))
                   ;; client1 doesn't receive an update at all, becuase the change doesn't match its filter
                   (let [response (client/recv! client1 3000)]
                     (is (nil? response)))
                   (with-open [client4 (client/connect :certname "client04.example.com"
                                                       :version version)]
                     (let [response (client/recv! client2)
                           data (client/get-data response version)]
                       (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                       (is (= [{:client "pcp://client04.example.com/agent" :change 1}] (:changes data))))
                     (let [response (client/recv! client1)
                           data (client/get-data response version)]
                       (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                       (is (= [{:client "pcp://client04.example.com/agent" :change 1}] (:changes data))))))
                 ;; client4 & client3 have disconnected (in that order)
                 (let [response (client/recv! client2)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                   (is (= [{:client "pcp://client04.example.com/agent" :change -1}
                           {:client "pcp://client03.example.com/agent" :change -1}] (:changes data))))
                 (let [response (client/recv! client1)
                       data (client/get-data response version)]
                   (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
                   (is (= [{:client "pcp://client04.example.com/agent" :change -1}] (:changes data))))))))

(def inventory-subscribe-agents
  (client/make-message
    {:message_type "http://puppetlabs.com/inventory_request"
     :data {:query ["pcp://*/agent"] :subscribe true}}))

(deftest inventory-update-before-response
  (let [subscribe-client! puppetlabs.pcp.broker.inventory/subscribe-client!
        subscription-finished (promise)
        finish-subscription (promise)]
  (with-redefs [puppetlabs.pcp.broker.inventory/start-inventory-updates! (fn [_] nil)
                puppetlabs.pcp.broker.inventory/subscribe-client!
                (fn [broker client connection pattern-sets]
                  (let [result (subscribe-client! broker client connection pattern-sets)]
                    (deliver subscription-finished true)
                    (deref finish-subscription)
                    result))]
    (with-app-with-config app broker-services broker-config
      (with-open [controller (client/connect :certname "controller01.example.com")]
        ;; subscribe to updates, but ensure message isn't delivered before sending updates
        (client/send! controller inventory-subscribe-agents)
        (deref subscription-finished)
        (with-open [client (client/connect :certname "client01.example.com" :force-association true)]
          (#'puppetlabs.pcp.broker.inventory/send-updates (get-broker app))
          (deliver finish-subscription true)
          (let [response (client/recv! controller)]
            (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
            (is (= ["pcp://controller01.example.com/agent"] (get-in response [:data :uris]))))
          (#'puppetlabs.pcp.broker.inventory/send-updates (get-broker app))
          (let [response (client/recv! controller)]
            (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
            (is (= [{:client "pcp://client01.example.com/agent" :change 1}] (get-in response [:data :changes]))))))
      ;; ensure send updates doesn't error after subscriber has disconnected
      (with-open [client (client/connect :certname "client01.example.com" :force-association true)]
        (#'puppetlabs.pcp.broker.inventory/send-updates (get-broker app)))))))

(deftest inventory-update-after-reconnect
  (let [subscribe-client! puppetlabs.pcp.broker.inventory/subscribe-client!
        subscription-finished (atom (promise))
        finish-subscription (promise)]
  (with-redefs [puppetlabs.pcp.broker.inventory/batch-update-interval-ms 10
                puppetlabs.pcp.broker.inventory/subscribe-client!
                (fn [broker client connection pattern-sets]
                  (let [result (subscribe-client! broker client connection pattern-sets)]
                    (deliver @subscription-finished true)
                    (deref finish-subscription)
                    result))]
    (with-app-with-config app broker-services broker-config
      ;; Connect, subscribe, wait for subscription to be registered and disconnect
      (with-open [controller (client/connect :certname "controller01.example.com")]
        (client/send! controller inventory-subscribe-agents)
        (deref @subscription-finished))
      (reset! subscription-finished (promise))
      ;; Reconnect, attempt to subscribe and expect updates
      (with-open [controller (client/connect :certname "controller01.example.com")]
        (client/send! controller inventory-subscribe-agents)
        (deref @subscription-finished)
        (with-open [client (client/connect :certname "client01.example.com" :force-association true)]
          (deliver finish-subscription true)
          (let [response (client/recv! controller)]
            (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
            (is (= ["pcp://controller01.example.com/agent"] (get-in response [:data :uris]))))
          (let [response (client/recv! controller)]
            (is (= "http://puppetlabs.com/inventory_update" (:message_type response)))
            (is (= [{:client "pcp://client01.example.com/agent" :change 1}] (get-in response [:data :changes]))))))))))

(def inventory-subscribe
  (client/make-message
    {:message_type "http://puppetlabs.com/inventory_request"
     :data {:query ["pcp://client01.example.com/*"] :subscribe true}}))

(defn initial-inventory
  [controller]
  (client/send! controller inventory-subscribe)
  (let [response (client/recv! controller)
        data (client/get-data response)]
    (is (= "http://puppetlabs.com/inventory_response" (:message_type response)))
    (is (vector? (:uris data)))
    (:uris data)))

(defn process-update
  [controller initial-connections]
  (let [update (client/recv! controller)
        data (client/get-data update)]
    (is (= "http://puppetlabs.com/inventory_update" (:message_type update)))
    (loop [changes (:changes data)
           connections initial-connections]
      (if-let [{:keys [change client]} (first changes)]
        (if (pos? change)
          (do (is (not (contains? connections client)))
              (recur (rest changes) (conj connections client)))
          (do (is (contains? connections client))
              (recur (rest changes) (disj connections client))))
        connections))))

(defn process-updates-until
  ([goal controller] (process-updates-until goal controller (set (initial-inventory controller))))
  ([goal controller initial-connections]
   (loop [connections initial-connections]
     (if (not= (count connections) goal)
       (recur (process-update controller connections))
       connections))))

;; Going much above this triggers errors creating new native threads on my laptop.
(def client-count 30)

(deftest inventory-race-detector
  (with-redefs [puppetlabs.pcp.broker.inventory/batch-update-interval-ms 10]
    (with-app-with-config app broker-services broker-config
      ;; Start connecting agents, and at the same time connect a controller and subscribe to updates.
      ;; Ensure final inventory includes all connections.
      (let [uris (set (map #(str "pcp://client01.example.com/" %) (range client-count)))
            agents (doall (map (fn [i] (future (Thread/sleep (+ 200 (rand-int 1000)))  ;; use a sleep to try to let controllers connect
                                               (client/connect :certname "client01.example.com" :type (str i))))
                               (range client-count)))]
        (with-open [controller1 (client/connect :certname "controller01.example.com")
                    controller2 (client/connect :certname "controller02.example.com")]
          (let [controllers [controller1 controller2]
                connections (pmap (partial process-updates-until client-count) controllers)]
            (is (every? #(= uris %) connections))

            ;; Disconnect clients and ensure inventory empties.
            (doseq [c agents] (.close @c))
            (let [final-connections (pmap (partial process-updates-until 0) controllers connections)]
              (is (every? empty? final-connections)))))))))
