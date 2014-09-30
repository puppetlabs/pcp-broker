(ns puppetlabs.cthun.inventory.in-memory-test
  (require [clojure.test :refer :all]
           [puppetlabs.cthun.inventory.in-memory :refer :all]))

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
