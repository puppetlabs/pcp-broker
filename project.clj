(def http-async-client-version "1.3.0")

(defproject puppetlabs/pcp-broker "1.7.6-SNAPSHOT"
  :description "PCP fabric messaging broker"
  :url "https://github.com/puppetlabs/pcp-broker"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :min-lein-version "2.7.1"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :parent-project {:coords [puppetlabs/clj-parent "4.9.1"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [puppetlabs/kitchensink]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-authorization]
                 [puppetlabs/trapperkeeper-metrics]
                 [puppetlabs/trapperkeeper-webserver-jetty9]
                 [puppetlabs/trapperkeeper-status]
                 [puppetlabs/trapperkeeper-filesystem-watcher]

                 [puppetlabs/structured-logging]
                 [puppetlabs/ssl-utils]
                 [metrics-clojure]

                 ;; try+/throw+
                 [slingshot]

                 [puppetlabs/pcp-client "1.3.4"]

                 [puppetlabs/i18n]]

  :plugins [[lein-parent "0.3.7"]
            [puppetlabs/lein-ezbake "1.9.0"]
            [puppetlabs/i18n "0.8.0"]
            [lein-release "1.0.5" :exclusions [org.clojure/clojure]]]

  :lein-release {:scm :git
                 :deploy-via :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]
                        ["snapshots" {:url "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"
                                      :username :env/nexus_jenkins_username
                                      :password :env/nexus_jenkins_password
                                      :sign-releases false}]]

  :test-paths ["test/unit" "test/integration" "test/utils" "test-resources"]

  :lein-ezbake {:config-dir "ezbake/config"
                :vars {:docker {:ports [8140]}}}

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[http.async.client ~http-async-client-version]
                                  [puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink :classifier "test" :scope "test"]
                                  [org.bouncycastle/bcpkix-jdk15on]
                                  [org.clojure/tools.namespace]
                                  [org.clojure/tools.nrepl]]
                   :plugins [[lein-cloverage "1.0.6" :excludes [org.clojure/clojure org.clojure/tools.cli]]]}
             :dev-schema-validation [:dev
                                     {:injections [(do
                                                    (require 'schema.core)
                                                    (schema.core/set-fn-validation! true))]}]
             :test-base {:source-paths ["test/utils" "test-resources"]
                         :dependencies [[http.async.client ~http-async-client-version]
                                       [puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                       [puppetlabs/kitchensink :classifier "test" :scope "test"]
                                       [org.bouncycastle/bcpkix-jdk15on]]
                         :test-paths ^:replace ["test/unit" "test/integration"]}
             :test-schema-validation [:test-base
                                      {:injections [(do
                                                     (require 'schema.core)
                                                     (schema.core/set-fn-validation! true))]}]
             :uberjar {:dependencies [[org.bouncycastle/bcpkix-jdk15on]]
                       :aot [puppetlabs.pcp.broker.service
                             puppetlabs.trapperkeeper.services.authorization.authorization-service
                             puppetlabs.trapperkeeper.services.metrics.metrics-service
                             puppetlabs.trapperkeeper.services.scheduler.scheduler-service
                             puppetlabs.trapperkeeper.services.status.status-service
                             puppetlabs.trapperkeeper.services.webrouting.webrouting-service
                             puppetlabs.trapperkeeper.services.webserver.jetty9-service]}
             :unit [:test-base
                    {:test-paths ^:replace ["test/unit"]}]
             :integration [:test-base
                           {:test-paths ^:replace ["test/integration"]}]
             :internal-mirrors {:mirrors [["releases" {:name "internal-releases"
                                                       :url "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"}]
                                          ["central" {:name "internal-central-mirror"
                                                      :url "https://artifactory.delivery.puppetlabs.net/artifactory/maven/" }]
                                          ["clojars" {:name "internal-clojars-mirror"
                                                      :url "https://artifactory.delivery.puppetlabs.net/artifactory/maven/" }]
                                          ["snapshots" {:name "internal-snapshots"
                                                        :url "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/" }]]}
             :internal-integration [:integration :internal-mirrors]}

  :repl-options {:init-ns user}

  ;; Enable occasionally to check we have no interop hotspots that need better type hinting
  ; :global-vars {*warn-on-reflection* true}

  :aliases {"tk" ["trampoline" "run" "--config" "test-resources/conf.d"]
            ;; runs trapperkeeper with schema validations enabled
            "tkv" ["with-profile" "dev-schema-validation" "tk"]
            "certs" ["trampoline" "run" "-m" "puppetlabs.pcp.testutils.certs" "--config" "test-resources/conf.d" "--"]
            "coverage" ["cloverage" "-e" "puppetlabs.puppetdb.*" "-e" "user"]
            "test-all" ["with-profile" "test-base:test-schema-validation" "test"]}

  :main puppetlabs.trapperkeeper.main)
