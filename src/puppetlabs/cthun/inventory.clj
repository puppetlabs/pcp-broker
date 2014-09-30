(ns puppetlabs.cthun.inventory)

(defprotocol InventoryService
  (record-client [this endpoint])
  (find-clients  [this endpoints]))
