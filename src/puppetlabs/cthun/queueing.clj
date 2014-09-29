(ns puppetlabs.cthun.queueing)

(defprotocol QueueingService
  (queue-message [this topic message])
  (subscribe-to-topic [this topic callback-fn]))
