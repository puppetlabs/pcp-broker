(ns puppetlabs.pcp.broker.inventory
  (:require [puppetlabs.pcp.protocol :as p]
            [puppetlabs.pcp.broker.shared :refer [Broker deliver-server-message] :as shared]
            [puppetlabs.pcp.broker.message :as message]
            [clojure.set :refer [intersection union]]
            [schema.core :as s])
  (:import [clojure.lang Numbers]
           [puppetlabs.pcp.broker.connection Connection]))

(s/defn init-database :- shared/BrokerDatabase
  []
  {:inventory          {}
   :updates            []
   :first-update-index 0
   :subscriptions      {}})

(s/defn unchecked+ :- s/Int
  "An addition which can overflow"
  [a :- s/Int b :- s/Int]
  (Numbers/unchecked_add (long a) (long b)))

(s/defn unchecked- :- s/Int
  "A substraction which can overflow"
  [a :- s/Int b :- s/Int]
  (Numbers/unchecked_minus (long a) (long b)))

(s/defn build-pattern-sets :- shared/PatternSets
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
  [{:keys [explicit wildcard]} :- shared/PatternSets subject :- p/Uri]
  (or (contains? explicit subject)                          ;; the set of explicit patterns contains the subject
      (let [exploded-subject (p/explode-uri subject)]
        (some #(exploded-uri-pattern-match? % exploded-subject) wildcard))
      false))                                               ;; to satisfy the s/Bool schema for the return value

(s/defn build-inventory-data :- p/InventoryResponse
  "Build the payload of the inventory response message given the inventory snapshot and
  a set of patterns to filter the snapshot."
  [inventory :- shared/Inventory pattern-sets :- shared/PatternSets]
  (let [matched (->> inventory
                     (reduce
                       (fn [matched-clients [client _]]
                         (if (uri-pattern-sets-match? pattern-sets client)
                           (conj! matched-clients client)
                           matched-clients))
                       (transient #{}))
                     persistent!)]
    {:uris (sort matched)}))

(s/defn build-update-data :- (s/maybe p/InventoryUpdate)
  "Build the payload of the inventory update message given the inventory updates snapshot and
  a set of patterns to filter the snapshot. Return nil if there are no updates matching the
  patterns."
  [updates :- [p/InventoryChange] pattern-sets :- shared/PatternSets]
      (let [filtered (->> updates
                          (reduce
                            (fn [filtered-updates update]
                              (if (uri-pattern-sets-match? pattern-sets (:client update))
                                ;; FIXME there should be a limit on the maximum number of elements
                                ;; in the filtered vector to ensure the update message can't grow
                                ;; too long
                                (conj! filtered-updates update)
                                filtered-updates))
                            (transient []))
                          persistent!)]
        (if (seq filtered)
          {:changes filtered})))

(s/defn subscribe-client! :- shared/BrokerDatabase
  "Subscribe the specified client for inventory updates. Return the broker database snapshot
  at the time of subscribing."
  [broker :- Broker client :- p/Uri connection :- Connection pattern-sets :- shared/PatternSets]
  (let [database (:database broker)]
    (swap! database
           #(update % :subscriptions assoc client {:connection        connection
                                                   :pattern-sets      pattern-sets
                                                   :next-update-index (unchecked+ (-> % :first-update-index) (-> % :updates count))}))))

(s/defn unsubscribe-client! :- shared/BrokerDatabase
  "Unsubscribe the specified client from inventory updates. Return the broker database snapshot
  at the time of unsubscribing."
  [broker :- Broker client :- p/Uri]
  (let [database (:database broker)]
    (swap! database
           update :subscriptions dissoc client)))

(s/defn ^:private remove-processed-updates
  "Remove the `processed-updates-count` updates from the :updates vector and increase
  :first-update-index accordingly."
  [broker :- Broker processed-updates-count :- s/Int]
  (let [database (:database broker)]
    (swap! database #(-> %
                         ;; need to call vec as the subvec keeps a reference to the original vector
                         (update :updates (fn [updates] (vec (subvec updates processed-updates-count))))
                         (update :first-update-index unchecked+ processed-updates-count)))))

(s/defn ^:private send-updates
  [broker :- Broker]
  "Send inventory update messages to subscribed clients. Subsequently remove the processed inventory
  change records from the :updates vector."
  (let [database (:database broker)
        database-snapshot @database
        updates (:updates database-snapshot)
        updates-count (count updates)
        first-update-index (:first-update-index database-snapshot)]
    (->> (reduce                                            ;; this reduce returns the number of updates which can be removed from the :updates vector
           (fn [processed-updates-count subscriber]
             (if-let [subscription (-> @database :subscriptions (get subscriber))]
               (let [next-update-offset (unchecked- (:next-update-index subscription) first-update-index)
                     processed-count-atom (atom nil)]       ;; how many records from the updates vector were processed
                 (swap! database
                        (fn [database]
                          (if (identical? (-> database :subscriptions (get subscriber)) subscription) ;; is the subscription in the live database still the same?
                            (do
                              (if (nil? @processed-count-atom) ;; have we not sent the update to this subscriber yet?
                                (let [data (-> (subvec updates next-update-offset) ;; skip updates which have already been sent to this subscriber
                                               (build-update-data (:pattern-sets subscription)))]
                                  (if (or (nil? data) ;; there are no updates for this subscriber
                                          (deliver-server-message broker
                                                                  (message/make-message
                                                                    {:message_type "http://puppetlabs.com/inventory_update"
                                                                     :target subscriber
                                                                     :data data})
                                                                  (:connection subscription)))
                                    (reset! processed-count-atom updates-count)
                                    (reset! processed-count-atom next-update-offset))))
                              (let [processed-count @processed-count-atom]
                                (if (> processed-count next-update-offset)
                                  ;; add the count of the InventoryChange records which were processed when building the
                                  ;; inventory update for this subscriber to the :next-update-index in the subscription
                                  (update database :subscriptions assoc subscriber
                                          (update subscription :next-update-index unchecked+ (- processed-count next-update-offset)))
                                  database))) ;; no need to update the subscription if nothing past the next-update-offset was processed
                            (do
                              ;; if the subscription has been changed, ignore it but pretend the entire updates vector was processed
                              (reset! processed-count-atom updates-count)
                              database))))
                 (min @processed-count-atom processed-updates-count))
               processed-updates-count))
           updates-count
           (-> database-snapshot :subscriptions keys))
         (remove-processed-updates broker))))

(s/defn start-inventory-updates!
  "Start periodic sending of the inventory updates."
  [broker :- Broker]
  (future
    (let [should-stop (:should-stop broker)]
      (loop []
        (send-updates broker)
        (if (nil? (deref should-stop 1000 nil))
          (recur))))))

(s/defn stop-inventory-updates!
  "Stop the periodic sending of the inventory updates."
  [broker :- Broker]
  (let [database (:database broker)]
    (swap! database assoc :subscriptions {}))         ;; clear all subscriptions
  (deliver (:should-stop broker) true))
