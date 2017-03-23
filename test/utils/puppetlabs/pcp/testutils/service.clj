(ns puppetlabs.pcp.testutils.service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.pcp.broker.service :refer [broker-service]]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]))

(def broker-config
  "A broker with ssl"
  {:authorization {:version 1
                   :rules [{:name "allow all"
                            :match-request {:type "regex"
                                            :path "^/.*$"}
                            :allow-unauthenticated true
                            :sort-order 1}]}

   :webserver {:ssl-host "127.0.0.1"
               ;; usual port is 8142.  Here we use 58142 so if we're developing
               ;; we can run a long-running instance and this one for the
               ;; tests.
               :ssl-port 58142
               :client-auth "want"
               :ssl-key "./test-resources/ssl/private_keys/broker.example.com.pem"
               :ssl-cert "./test-resources/ssl/certs/broker.example.com.pem"
               :ssl-ca-cert "./test-resources/ssl/ca/ca_crt.pem"
               :ssl-crl-path "./test-resources/ssl/ca/ca_crl.pem"}

   :web-router-service
   {:puppetlabs.pcp.broker.service/broker-service {:v1 "/pcp/v1.0"
                                                   :v2 "/pcp/v2.0"}
    :puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}

   :metrics {:server-id "localhost"}})

(def protocol-versions
  "The short names of versioned endpoints"
  ["v1.0" "v2.0"])

(def broker-services
  "The trapperkeeper services the broker needs"
  [authorization-service broker-service jetty9-service webrouting-service metrics-service status-service scheduler-service])

(defn get-context
  [app svc]
  (-> @(tka/app-context app)
      :service-contexts
      (get svc)))

(defn get-broker
  [app]
  (-> app
      (get-context :BrokerService)
      :broker))
