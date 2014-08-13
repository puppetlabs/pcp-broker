(def tk-version "0.4.2")
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
                 [puppetlabs/trapperkeeper ~tk-version]

		 ;; we need version 9.2.2
                 [org.eclipse.jetty/jetty-server "9.2.2.v20140723"
                 :exclusions [org.eclipse.jetty.orbit/javax.servlet]]
		 [org.eclipse.jetty/jetty-util "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-servlet "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-servlets "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-webapp "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-proxy "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-jmx "9.2.2.v20140723"]]

  :test-paths ["test" "test-resources"]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 "0.5.2"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [clj-http "0.9.2"]
                                  [ring-mock "0.1.5"]]}}

  :aliases {"tk" ["trampoline" "run" "--config" "test-resources/config.conf"]}

  :main puppetlabs.trapperkeeper.main

  )
