(ns puppetlabs.pcp.broker.subscription-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.testutils :refer [dotestseq]]
            [puppetlabs.pcp.testutils.service :refer [broker-config protocol-versions broker-services]]
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

(deftest inventory-updates-are-properly-filtered
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
