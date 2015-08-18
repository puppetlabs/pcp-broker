(ns puppetlabs.cthun.authorization.basic-core
  (:require [clojure.tools.logging :as log]
            [puppetlabs.cthun.message :as message]
            ;; TODO(richardc): explode-uri should probably move to
            ;; puppetlabs.cthun.protocol.helpers
            [puppetlabs.cthun.broker-core :refer [explode-uri]]
            [schema.core :as s]))

(def Decision
  (s/enum :allow :deny))

(def Jump
  {:target s/Keyword})

(def Action
  (s/if map? Jump Decision))

(def Rule
  {(s/optional-key :sender) s/Str
   (s/optional-key :message_type) s/Str
   :action Action})

(def Table
  {:default Decision
   (s/optional-key :rules) [Rule]})

(def Rules
  {s/Keyword Table})

;; TODO(richardc) - un-fugly this
(s/defn ^:always-validate rule-matches? :- s/Bool
  [rule :- Rule message :- message/Message]
  (let [rule-message_type    (:message_type rule)
        rule-sender          (:sender rule)
        message-message_type (:message_type message)
        [message-sender]     (explode-uri (:sender message))]
    (and (or (= rule-sender nil) (= rule-sender message-sender))
         (or (= rule-message_type nil) (= rule-message_type message-message_type)))))

(s/defn ^:always-validate authorized :- s/Bool
  [rules :- Rules message :- message/Message]
  (loop [table   :accept
         been    #{}
         default (get-in rules [table :default] :deny)]
    (let [rule (first (filter (fn [r] (rule-matches? r message)) (get-in rules [table :rules])))]
      (if (not rule)
        (= default :allow) ;; no rule matched, use table default
        (let [action (:action rule)]
          (if (not (map? action))
            (= action :allow) ;; rule matched, not a jump, do that
            (let [target  (:target action)
                  been    (conj been table)
                  default (get-in rules [target :default] :deny)]
              (if (contains? been target)
                (do
                  (log/error "Loop detected in rules " been " -> " target ". Denying message")
                  false)
                (recur target been default)))))))))
