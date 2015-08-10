(ns puppetlabs.cthun.authorization)

(defprotocol AuthorizationService
  (authorized [this message]))
