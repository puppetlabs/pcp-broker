(ns puppetlabs.cthun.meshing.hazelcast-test
  (require [clojure.test :refer :all]
           [puppetlabs.cthun.meshing.hazelcast :refer :all]))

(deftest broker-for-endpoint-test
  (with-redefs [flatten-location-map (fn [_] [{:broker "bill" :endpoint "cth://localhost/agent"}
                                              {:broker "ted"  :endpoint "cth://localhost/agent"}
                                              {:broker "bill" :endpoint "cth://localhost/controller"}])]
    (testing "it finds the controller"
      (is (= "bill" (broker-for-endpoint nil "cth://localhost/controller"))))
    (testing "it finds the agent"
      (is (= "bill" (broker-for-endpoint nil "cth://localhost/agent"))))
    (testing "it won't find something absent"
      (is (not (broker-for-endpoint nil "cth://localhost/no-suchagent"))))))
