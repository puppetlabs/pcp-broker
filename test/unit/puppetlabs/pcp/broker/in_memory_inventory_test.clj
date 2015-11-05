(ns puppetlabs.pcp.broker.in-memory-inventory-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.in-memory-inventory :refer :all]))

(deftest endpoint-pattern-match?-test
  (testing "direct matches"
    (is (endpoint-pattern-match? "pcp://pies/agent" "pcp://pies/agent"))
    (is (not (endpoint-pattern-match? "pcp://pies/agent" "pcp://sheep/agent"))))
  (testing "it can match on a pattern"
    (is (endpoint-pattern-match? "pcp://*/agent" "pcp://pies/agent"))
    (is (endpoint-pattern-match? "pcp://pies/*" "pcp://pies/agent")))
  (testing "patterns only on the lhs"
    (is (not (endpoint-pattern-match? "pcp://pies/agent" "pcp://*/agent")))
    (is (not (endpoint-pattern-match? "pcp://pies/agent" "pcp://pies/*")))))

(deftest find-clients-test
  (let [clients (atom #{"pcp://bill/agent"
                        "pcp://bob/agent"
                        "pcp://eric/controller"
                        "pcp://bob/controller"})]
    (testing "it finds a single client explictly"
      (is (= '("pcp://bill/agent")
             (find-clients clients ["pcp://bill/agent"]))))
    (testing "it finds a both agents by a client wildcard"
      (is (= '("pcp://bill/agent" "pcp://bob/agent")
             (sort (find-clients clients ["pcp://*/agent"])))))
    (testing "it finds a both agents by a client wildcard"
      (is (= '("pcp://bill/agent" "pcp://bob/agent" "pcp://bob/controller")
             (sort (find-clients clients ["pcp://*/agent" "pcp://bob/controller"])))))
    (testing "it finds a both connections by a client by type client"
      (is (= '("pcp://bob/agent" "pcp://bob/controller")
             (sort (find-clients clients ["pcp://bob/*"])))))
    (testing "It returns an empty list when the endpoint cannot be found"
      (is (= '() (find-clients clients ["pcp://bob/nonsuch"]))))))
