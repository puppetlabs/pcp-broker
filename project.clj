(def tk-version "1.4.0")
(def ks-version "1.3.0")
(def i18n-version "0.4.1")

(defproject puppetlabs/pcp-broker "0.7.2-SNAPSHOT"
  :description "PCP fabric messaging broker"
  :url "https://github.com/puppetlabs/pcp-broker"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]

                 ;; Transitive dependency for:
                 ;;   puppetlabs/trapperkeeper-metrics
                 ;;   puppetlabs/structured-logging
                 ;;   org.slf4j/jcl-over-slf4
                 [org.slf4j/slf4j-api "1.7.12"]

                 ;; Transitive dependency for:
                 ;;   ch.qos.logback/logback-classic via puppetlabs/trapperkeeper
                 ;;   net.logstash.logback/logstash-logback-encoder via puppetlabs/structured-logging
                 [ch.qos.logback/logback-core "1.1.3"]

                 ;; Transitive dependency for puppetlabs/trapperkeeper-authorization, and a direct dependency
                 [clj-time "0.10.0"]

                 ;; Transitive dependency for puppetlabs/trapperkeeper and puppetlabs/trapperkeeper-authorization
                 [puppetlabs/typesafe-config "0.1.5"]

                 ;; Transitive dependency for:
                 ;;   puppetlabs/trapperkeeper
                 ;;   compojure via puppetlabs/comidi via puppetlabs/trapperkeeper-status
                 [org.clojure/tools.macro "0.1.5"]

                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-authorization "0.1.5"]
                 [puppetlabs/trapperkeeper-metrics "0.1.1"]
                 ;; Exclude clojure dep for now as that will force a ripple up to clojure 1.7.0
                 [puppetlabs/trapperkeeper-webserver-jetty9 "1.5.7" :exclusions [org.clojure/clojure]]
                 [puppetlabs/trapperkeeper-status "0.2.1"]

                 ;; Exclude clojure dep for now as that will force a ripple up to clojure 1.7.0
                 [puppetlabs/structured-logging "0.1.0" :exclusions [org.clojure/clojure]]

                 [cheshire "5.5.0"]

                 ;; Transitive dependency for:
                 ;;   nippy
                 ;;   org.clojure/tools.analyzer.jvm via org.clojure/core.async via puppetlabs/trapperkeeper
                 [org.clojure/tools.reader "1.0.0-alpha1"]

                 [com.taoensso/nippy "2.9.0"]

                 [org.clojure/java.jmx "0.3.0"]
                 [metrics-clojure "2.5.1"]

                 ;; try+/throw+
                 [slingshot "0.12.2"]

                 [puppetlabs/pcp-common "0.5.1"]

                 ;; MQ - activemq
                 [clamq/clamq-activemq "0.4"]
                 [org.apache.activemq/activemq-core "5.6.0"
                  :exclusions [org.fusesource.fuse-extra/fusemq-leveldb]]
                 ;; bridge to allow some spring/activemq stuff to log over slf4j
                 [org.slf4j/jcl-over-slf4j "1.7.10"]

                 [puppetlabs/i18n ~i18n-version]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [puppetlabs/i18n ~i18n-version]]

  :lein-release {:scm :git
                 :deploy-via :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :test-paths ["test/unit" "test/integration" "test/utils" "test-resources"]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [;; Transient dependency of http.async.client
                                  ;; - it actually brings in netty 3.9.2.Final, but we
                                  ;; want some fixes to websocket handling that are in later .x releases
                                  [io.netty/netty "3.9.9.Final"]
                                  [http.async.client "0.6.1" :exclusions [org.clojure/clojure]]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [puppetlabs/ssl-utils "0.8.1"]
                                  [org.clojure/tools.namespace "0.2.4"]
                                  ;; Transitive dependency for lein-cloverage and puppetlabs/kitchensink
                                  [org.clojure/tools.cli "0.3.0"]]
                   :plugins [[lein-cloverage "1.0.6" :excludes [org.clojure/clojure org.clojure/tools.cli]]]}
             :integration {:test-paths ^:replace ["test/integration" "test/utils" "test-resources"]}
             :unit {:test-paths ^:replace ["test/unit" "test/utils" "test-resources"]}
             :cljfmt {:plugins [[lein-cljfmt "0.3.0"]
                                [lein-parent "0.2.1"]]
                      :parent-project {:path "../pl-clojure-style/project.clj"
                                       :inherit [:cljfmt]}}}

  :repl-options {:init-ns user}

  ;; Enable occasionally to check we have no interop hotspots that need better type hinting
  ; :global-vars {*warn-on-reflection* true}

  :aliases {"tk" ["trampoline" "run" "--config" "test-resources/conf.d"]
            ;; runs trapperkeeper with schema validations enabled
            "tkv" ["trampoline" "run" "-m" "user" "--config" "test-resources/conf.d"]
            "certs" ["trampoline" "run" "-m" "puppetlabs.pcp.testutils.certs" "--config" "test-resources/conf.d" "--"]
            "cljfmt" ["with-profile" "+cljfmt" "cljfmt"]
            "coverage" ["cloverage" "-e" "puppetlabs.puppetdb.*" "-e" "user"]}

  :main puppetlabs.trapperkeeper.main)
