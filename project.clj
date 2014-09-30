(def tk-version "0.5.1")
(def ks-version "0.7.2")

(defproject puppetlabs/cthun "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
		 [info.sunng/ring-jetty9-adapter "0.7.1"]
                 [compojure "1.1.8"]
                 [org.clojure/tools.logging "0.3.0"]
                 [org.clojure/core.incubator "0.1.3"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [cheshire "5.3.1"]
                 [prismatic/schema "0.2.6"]

                 [com.hazelcast/hazelcast "3.3-EA2"]

                 ;; MQ - durable-queue
                 [factual/durable-queue "0.1.2"]

                 ;; MQ - activemq
                 [clamq/clamq-activemq "0.4" :exclusions [org.slf4j/slf4j-api]]
                 [org.apache.activemq/activemq-core "5.6.0" :exclusions [org.slf4j/slf4j-api org.fusesource.fuse-extra/fusemq-leveldb]]
                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.5"]

		 ;; we need version 9.2.2 for ring-jetty9-adapter
                 [org.eclipse.jetty/jetty-server "9.2.2.v20140723"
                  :exclusions [org.eclipse.jetty.orbit/javax.servlet]]
		 [org.eclipse.jetty/jetty-util "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-servlet "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-servlets "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-webapp "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-proxy "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-jmx "9.2.2.v20140723"]]

  :test-paths ["test" "test-resources"]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 "0.7.3"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [clj-http "1.0.0"]
                                  [ring-mock "0.1.5"]]}}

  :aliases {"tk" ["trampoline" "run" "--config" "test-resources/config.conf"]}

  :main puppetlabs.trapperkeeper.main

  )
