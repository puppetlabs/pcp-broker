(ns puppetlabs.cthun.validation-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.validation :refer :all]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(deftest explode-endpoint-test
  (testing "It raises on invalid endpoints"
    (is (thrown? Exception (explode-endpoint ""))))
  (testing "It returns component chunks"
    (is (= [ "localhost" "agent"] (explode-endpoint "cth://localhost/agent")))
    (is (= [ "localhost" "*" ] (explode-endpoint "cth://localhost/*")))
    (is (= [ "*" "agent" ] (explode-endpoint "cth://*/agent")))))

(deftest validate-certname-test
  (testing "simple match, no exception"
    (try+
     (validate-certname "cth://lolcathost/agent" "lolcathost")
     (catch Object _
       (is (not true) "No exception should be raised"))
     (else (is true "No exception raised"))))
  (testing "simple mismatch"
    (try+
     (validate-certname "cth://lolcathost/agent" "remotecat")
     (catch map? m
       (is (= :puppet.cthun.validation/identity-invalid) (:type m)))
     (else (is (not true) "Expected an exception for remotecat"))))
  (testing "accidental regex collisions"
    (try+
     (validate-certname "cth://lolcathost/agent" "remotecat")
     (catch map? m
       (is (= :puppet.cthun.validation/identity-invalid) (:type m)))
     (else (is (not true) "Expected an exception for lol.athost")))))
