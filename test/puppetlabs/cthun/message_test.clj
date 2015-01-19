(ns puppetlabs.cthun.message-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.message :refer :all]))

(deftest make-message-test
  (testing "it makes a message"
    (is (= (make-message)
           {:version ""
            :id ""
            :endpoints []
            :data_schema ""
            :sender ""
            :expires nil
            :hops []
            :data {}
            :_destination ""}))))

(deftest decode-test
  ;; disable validation
  (with-redefs [puppetlabs.cthun.validation/check-schema identity]
    (testing "it decodes an empty message"
      (is (= (decode "{}")
             (make-message))))

    (testing "it decodes an empty message"
      (is (= (decode "{\"id\":\"once-upon-a-time\"}")
             (merge (make-message) {:id "once-upon-a-time"}))))))

(deftest encode-test
  (testing "it encodes a message"
    (is (= (encode {:hops 42}) "{\"hops\":42}"))))

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
