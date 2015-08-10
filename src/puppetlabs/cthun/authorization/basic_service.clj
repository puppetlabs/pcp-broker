(ns puppetlabs.cthun.authorization.basic-service
 (:require [puppetlabs.cthun.authorization :refer [AuthorizationService]]
           [puppetlabs.cthun.authorization.basic-core :as core]
           [puppetlabs.trapperkeeper.core :refer [defservice]]
           [puppetlabs.trapperkeeper.services :refer [service-context]]))

(defservice authorization-service
  AuthorizationService
  [[:ConfigService get-in-config]]
  (authorized [this message]
              (let [rules (get-in-config [:cthun-basic-authz] {:accept {:default :allow}})]
                (core/authorized rules message))))
