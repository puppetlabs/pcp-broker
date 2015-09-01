(ns puppetlabs.pcp.broker.in-memory-inventory-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.in-memory-inventory :refer :all]))

(deftest endpoint-pattern-match?-test
  (testing "direct matches"
    (is (endpoint-pattern-match? "cth://pies/agent" "cth://pies/agent"))
    (is (not (endpoint-pattern-match? "cth://pies/agent" "cth://sheep/agent"))))
  (testing "it can match on a pattern"
    (is (endpoint-pattern-match? "cth://*/agent" "cth://pies/agent"))
    (is (endpoint-pattern-match? "cth://pies/*" "cth://pies/agent")))
  (testing "patterns only on the lhs"
    (is (not (endpoint-pattern-match? "cth://pies/agent" "cth://*/agent")))
    (is (not (endpoint-pattern-match? "cth://pies/agent" "cth://pies/*")))))

(deftest find-clients-test
  (let [clients (atom #{ "cth://bill/agent"
                         "cth://bob/agent"
                         "cth://eric/controller"
                         "cth://bob/controller" })]
    (testing "it finds a single client explictly"
      (is (= '("cth://bill/agent")
             (find-clients clients ["cth://bill/agent"]))))
    (testing "it finds a both agents by a client wildcard"
      (is (= '("cth://bill/agent" "cth://bob/agent")
             (sort (find-clients clients ["cth://*/agent"])))))
    (testing "it finds a both agents by a client wildcard"
      (is (= '("cth://bill/agent" "cth://bob/agent" "cth://bob/controller")
             (sort (find-clients clients ["cth://*/agent" "cth://bob/controller"])))))
    (testing "it finds a both connections by a client by type client"
      (is (= '("cth://bob/agent" "cth://bob/controller")
             (sort (find-clients clients ["cth://bob/*"])))))
    (testing "It returns an empty list when the endpoint cannot be found"
      (is (= '() (find-clients clients ["cth://bob/nonsuch"]))))))
