(ns puppetlabs.cthun-web-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [clj-http.client :as client]
            [puppetlabs.cthun-service :as svc]
            [puppetlabs.cthun-web-service :as web-svc]))

; TODO(ploubser): Implement
(deftest Cthun-web-service-test
  (testing "Not yet implemented"
    (is (= 1 1))))
