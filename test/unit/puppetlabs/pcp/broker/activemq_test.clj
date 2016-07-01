(ns puppetlabs.pcp.broker.activemq-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.broker.activemq :refer :all]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

;; We're just here to catch simple typos
