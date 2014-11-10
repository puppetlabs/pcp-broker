(ns puppetlabs.cthun.message-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.message :refer :all]))


(deftest add-hop-test
  (with-redefs [puppetlabs.kitchensink.core/timestamp (fn [] "Tomorrow")]
    (testing "it adds a hop"
      (is (= {:hops [{:server "cth://fake/server"
                      :time   "Another day"
                      :stage  "potato"}]}
             (add-hop {:hops []} "potato" "Another day"))))

    (testing "it allows timestamp to be optional"
      (is (= {:hops [{:server "cth://fake/server"
                      :time   "Tomorrow"
                      :stage  "potato"}]}
             (add-hop {:hops []} "potato"))))

    (testing "it adds hops in the expected order"
      (is (= {:hops [{:server "cth://fake/server"
                      :time   "Tomorrow"
                      :stage  "potato"}
                     {:server "cth://fake/server"
                      :time   "Tomorrow"
                      :stage  "mash"}]}
             (-> {:hops []}
                 (add-hop "potato")
                 (add-hop "mash")))))))
