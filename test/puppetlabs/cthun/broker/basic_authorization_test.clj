(ns puppetlabs.cthun.broker.basic-authorization-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.broker.basic-authorization :refer :all]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]))

(deftest rule-matches?-test
  (testing "simplest matcher"
    (let [message (-> (message/make-message)
                      (assoc :sender "cth://maverick/pilot"
                             :message_type "cheque"))
          rule-matches? (fn [rule message] (rule-matches? (assoc rule :action :allow) message))]
      (is (= true  (rule-matches? {} message)))
      (is (= true  (rule-matches? {:sender "maverick"} message)))
      (is (= true  (rule-matches? {:message_type "cheque"} message)))
      (is (= false (rule-matches? {:sender "goose"} message)))
      (is (= false (rule-matches? {:message_type "cashable"} message)))
      (is (= true  (rule-matches? {:sender "maverick" :message_type "cheque"} message)))
      (is (= false (rule-matches? {:sender "maverick" :message_type "cashable"} message)))
      (is (= false (rule-matches? {:sender "goose"    :message_type "cheque"} message)))
      (is (= false (rule-matches? {:sender "goose"    :message_type "cashable"} message))))))

(deftest authorized-test
  (testing "no rules, just table defaults"
    (let [message (message/make-message)]
      (is (= true  (authorized {:accept {:default :allow}} message)))
      (is (= false (authorized {:accept {:default :deny}}  message)))))
  (testing "loop detection"
    (with-test-logging
      (let [message (-> (message/make-message)
                        (assoc :sender "cth://zz/top"))
            rules   {:accept {:default :allow
                              :rules [{:action {:target :accept}}]}}]
        (is (= false (authorized rules message)))
        (is (logged? #"^Loop detected in rules " :error)))))
  (testing "cnc style"
    (let [rules           {:accept {:default :allow
                                    :rules [{:message_type "cnc_request"
                                             :action {:target :cnc_commands}}]}
                           :cnc_commands {:default :deny
                                          :rules [{:sender "admin"
                                                   :action :allow}]}}
          allow-unrelated (-> (message/make-message)
                              (assoc :sender "cth://anybody/agent"
                                     :message_type "random_noise"))
          allowed-command (-> (message/make-message)
                              (assoc :sender "cth://admin/run"
                                     :message_type "cnc_request"))
          denied-command  (-> (message/make-message)
                              (assoc :sender "cth://anybody/run"
                                     :message_type "cnc_request"))]
      (is (= true  (authorized rules allow-unrelated)))
      (is (= true  (authorized rules allowed-command)))
      (is (= false (authorized rules denied-command))))))
