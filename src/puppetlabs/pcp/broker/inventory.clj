(ns puppetlabs.pcp.broker.inventory
  (:require [puppetlabs.pcp.protocol :as p]
            [puppetlabs.pcp.broker.shared :refer [Broker get-connection deliver-server-message]]
            [puppetlabs.pcp.broker.message :as message]
            [clojure.set :refer [intersection union]]
            [schema.core :as s])
  (:import [puppetlabs.pcp.broker.connection Connection]
           [java.util HashSet HashMap LinkedList]
           [java.util.concurrent ConcurrentLinkedDeque]))

(def PatternSets
  {:explicit #{p/Uri} :wildcard #{p/ExplodedUri}})

(s/defn init :- Broker
  [incomplete-broker]                                       ;; the passed broker isn't fully initialized yet
  (assoc incomplete-broker
    :inventory     (HashSet.)                               ;; Set of known clients (Uris) used for inventory reports
    :changes       (ConcurrentLinkedDeque.)                 ;; Queue of pending updates of the :inventory set
    :updates       (LinkedList.)                            ;; Queue of updates to be sent to the clients subscribed to inventory updates
    :subscriptions (HashMap.)                               ;; Mapping of subscribed client Uri to subscription data
    :should-stop   (promise)))

(s/defn ^:private get-inventory :- HashSet
  [broker :- Broker]
  (:inventory broker))

(s/defn ^:private get-changes :- ConcurrentLinkedDeque
  [broker :- Broker]
  (:changes broker))

(s/defn ^:private get-updates :- LinkedList
  [broker :- Broker]
  (:updates broker))

(s/defn ^:private get-subscriptions :- HashMap
  [broker :- Broker]
  (:subscriptions broker))

(s/defn add-client
  [broker :- Broker client :- p/Uri]
  (let [changes (get-changes broker)]
    (.offer changes {:client client :change 1})))

(s/defn remove-client
  [broker :- Broker client :- p/Uri]
  (let [changes (get-changes broker)]
    (.offer changes {:client client :change -1})))

(s/defn build-pattern-sets :- PatternSets
  "Parse the passed patterns and split them into explicit and wildcard sets for faster matching."
  [patterns :- [p/Uri]]
  (loop [explicit (transient #{}) wildcard (transient #{}) patterns (seq patterns)]
    (if patterns
      (let [pattern (first patterns)]
        (if-let [exploded-pattern (p/uri-wildcard? pattern)]
          (recur explicit (conj! wildcard exploded-pattern) (next patterns))
          (recur (conj! explicit pattern) wildcard (next patterns))))
      {:explicit (persistent! explicit) :wildcard (persistent! wildcard)})))

(s/defn ^:private exploded-uri-pattern-match? :- s/Bool
  "Does an exploded subject uri value match the exploded pattern? Here is where wildcards happen."
  [exploded-pattern :- p/ExplodedUri exploded-subject :- p/ExplodedUri]
  (let [[pattern-client pattern-type] exploded-pattern
        [subject-client subject-type] exploded-subject]
    (and (or (= "*" pattern-client) (= subject-client pattern-client))
         (or (= "*" pattern-type)   (= subject-type pattern-type)))))

(s/defn uri-pattern-sets-match? :- s/Bool
  "Does a subject uri match the pattern sets?"
  [{:keys [explicit wildcard]} :- PatternSets subject :- p/Uri]
  (or (contains? explicit subject)                          ;; the set of explicit patterns contains the subject
      (let [exploded-subject (p/explode-uri subject)]
        (some #(exploded-uri-pattern-match? % exploded-subject) wildcard))
      false))                                               ;; to satisfy the s/Bool schema for the return value

(s/defn build-inventory-data :- p/InventoryResponse
  "Build the payload of the inventory response message given the inventory snapshot and
  a set of patterns to filter the snapshot."
  [inventory :- #{p/Uri} pattern-sets :- PatternSets]
  (let [matched (->> inventory
                     (reduce
                       (fn [matched-clients client]
                         (if (uri-pattern-sets-match? pattern-sets client)
                           (conj! matched-clients client)
                           matched-clients))
                       (transient #{}))
                     persistent!)]
    {:uris (sort matched)}))

(s/defn ^:private shift-subscription-next-update :- (s/pred nil?)
  "Change the :next-update key in the subscription HashMap by the specified shift."
  [subscription :- HashMap next-update-shift :- s/Int]
  (->> (.get subscription :next-update)
       (+ next-update-shift)
       (.put subscription :next-update))
  nil)                                                      ;; we rely on nil being returned from this function

(s/defn build-update-data :- (s/maybe p/InventoryUpdate)
  "Build the payload of the inventory update message given the inventory updates snapshot and
  a set of patterns to filter the snapshot. Return nil if there are no updates matching the
  patterns."
  [updates :- [p/InventoryChange] subscription :- HashMap]
  (let [updates-count (count updates)
        next-update (:next-update subscription)]
    (if (< next-update updates-count)                       ;; do we have any new updates for this subscription?
      (let [pattern-sets (:pattern-sets subscription)
            filtered (->> (subvec updates next-update)      ;; skip updates which have already been sent to this subscription
                          (reduce
                            (fn [filtered-updates update]
                              (if (uri-pattern-sets-match? pattern-sets (:client update))
                                ;; FIXME there should be a limit on the maximum number of elements
                                ;; in the filtered vector to ensure the update message can't grow
                                ;; too long
                                (conj! filtered-updates update)
                                filtered-updates))
                            (transient []))
                          persistent!)
            next-update-shift (- updates-count next-update)]
        (if (seq filtered)
          (with-meta {:changes filtered} {:next-update-shift next-update-shift})
          (shift-subscription-next-update subscription next-update-shift)))))) ;; this returns nil

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn get-snapshot :- (s/either #{p/Uri} [p/InventoryChange])
  "Process the pending inventory changes to update the inventory and the updates queue
  and return a copy of one or the other depending on the value of the inventory-snapshot?
  argument."
  [broker :- Broker inventory-snapshot? :- s/Bool]
  (let [changes (get-changes broker)
        inventory (get-inventory broker)
        updates (get-updates broker)]
    (locking inventory
      (locking updates
        (dotimes [_ (.size changes)]
          (let [change (.remove changes)]
            (if (pos? (:change change))
              (.add inventory (:client change))
              (.remove inventory (:client change)))
            (.add updates change)))
        (if inventory-snapshot?
          (with-meta (into #{} inventory) {:next-update (.size updates)})
          (into [] updates))))))

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn subscribe-client :- #{p/Uri}
  "Subscribe the specified client for inventory updates. Return the inventory snapshot
  at the time of subscribing."
  [broker :- Broker client :- p/Uri connection :- Connection pattern-sets :- PatternSets]
  (let [subscriptions (get-subscriptions broker)]
    (locking subscriptions
      (let [inventory (get-snapshot broker true)]
        (.put subscriptions client
              (HashMap. {:connection   connection
                         :pattern-sets pattern-sets
                         :next-update  (-> inventory meta :next-update)}))
        inventory))))

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn ^:private get-subscribers :- [p/Uri]
  "Get a list of clients subscribed for inventory updates."
  [broker :- Broker]
  (let [subscriptions (get-subscriptions broker)]
    (locking subscriptions
      (into () (.keySet subscriptions)))))

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn ^:private get-subscription :- HashMap
  "Get a subscription HashMap given a subscriber Uri."
  [broker :- Broker subscriber :- p/Uri]
  (let [subscriptions (get-subscriptions broker)]
    (locking subscriptions
      (if-let [^HashMap subscription (.get subscriptions subscriber)]
        (let [connection (.get subscription :connection)]
          (if (identical? connection (get-connection broker subscriber))
            subscription
            (do (.remove subscriptions subscriber) nil))))))) ;; the client has disconnected or reconnected

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn ^:private remove-processed-updates
  "Remove the specified number of update records from the updates queue and update the :next-update
  offsets in all subscriptions accordingly."
  [broker :- Broker processed-updates-count :- s/Int]
  (let [subscriptions (get-subscriptions broker)
        updates (get-updates broker)]
    (locking subscriptions
      (locking updates
        (-> updates (.subList 0 processed-updates-count) .clear))
      (let [processed-updates-count (- processed-updates-count)] ;; negate the processed-updates-count
        (doseq [^HashMap subscription (.values subscriptions)]
          (shift-subscription-next-update subscription processed-updates-count)))))) ;; adding negation i.e. subtracting

(s/defn ^:private send-updates
  [broker :- Broker]
  "Send inventory update messages to subscribed clients. Subsequently remove the processed inventory
  change records from the updates queue."
  (let [updates (get-snapshot broker false)]
    (->> (reduce                                            ;; this reduce returns the number of updates which can be removed from the updates queue
           (fn [processed-updates-count subscriber]
             (or (if-let [subscription (get-subscription broker subscriber)]
                   (if-let [data (build-update-data updates subscription)]
                     (let [message (message/make-message
                                     {:message_type "http://puppetlabs.com/inventory_update"
                                      :target subscriber
                                      :data data})
                           next-update (.get subscription :next-update)]
                       (if (deliver-server-message broker message (.get subscription :connection))
                         (shift-subscription-next-update subscription (-> data meta :next-update-shift)) ;; this returns nil
                         ;; since we failed to send the update to the client we can't remove the update entries
                         ;; we failed to send from the update queue so that we can re-try during the next iteration;
                         ;; so we only allow removal of the entries before the :next-update value of this subscription
                         ;; (actually the lesser of that value and a value accumulated from previous iterations in
                         ;; case there were other similarly affected subscriptions)
                         (min processed-updates-count next-update)))))
                 processed-updates-count))
           (count updates)
           (get-subscribers broker))
         (remove-processed-updates broker))))

(s/defn start-inventory-updates!
  "Start periodic sending of the inventory updates."
  [broker :- Broker]
  (future
    (let [should-stop (:should-stop broker)
          timeout (Object.)]
      (loop []
        (send-updates broker)
        (if (identical? (deref should-stop 1000 timeout) timeout)
          (recur))))))

;; N.B. locking order:
;;   subscriptions
;;   inventory
;;   updates
(s/defn stop-inventory-updates!
  "Stop the periodic sending of the inventory updates."
  [broker :- Broker]
  (let [subscriptions (get-subscriptions broker)]
    (locking subscriptions
      (.clear subscriptions)))
  (deliver (:should-stop broker) nil))