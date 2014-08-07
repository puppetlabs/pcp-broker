(ns com.puppetlabs.cthun-web-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [clj-http.client :as client]
            [com.puppetlabs.cthun-service :as svc]
            [com.puppetlabs.cthun-web-service :as web-svc]))

(deftest hello-web-service-test
  (testing "says hello to caller"
    (with-app-with-config app
      [svc/hello-service
       web-svc/hello-web-service
       jetty9-service]
      {:webserver {:host "localhost"
                   :port 8080}
       :hello-web {:url-prefix "/hello"}}
      (let [resp (client/get "http://localhost:8080/hello/foo")]
        (is (= "Hello, foo!" (:body resp)))))))
