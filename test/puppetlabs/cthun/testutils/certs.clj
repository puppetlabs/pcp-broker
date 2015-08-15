(ns puppetlabs.cthun.testutils.certs
  (:require [me.raynes.fs :as fs]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.ssl-utils.simple :as ssl-simple]))

(def cert-serial-num (atom 0))

(defn save-pems
  [ssl-dir cert]
  (let [pub-key-dir (fs/file ssl-dir "public_keys")
        priv-key-dir (fs/file ssl-dir "private_keys")
        cert-dir (fs/file ssl-dir "certs")]
    (fs/mkdirs pub-key-dir)
    (fs/mkdirs priv-key-dir)
    (fs/mkdirs cert-dir)
    (ssl-utils/key->pem! (:public-key cert) (fs/file pub-key-dir (str (:certname cert) ".pem")))
    (ssl-utils/key->pem! (:private-key cert) (fs/file priv-key-dir (str (:certname cert) ".pem")))
    (ssl-utils/cert->pem! (:cert cert) (fs/file cert-dir (str (:certname cert) ".pem")))
    (println "saved pems for" (:certname cert))))

(defn gen-cthun-certs
  [ssl-dir]
  (let [cacert (ssl-simple/gen-self-signed-cert "ca" (swap! cert-serial-num inc))
        cthun-cert (ssl-simple/gen-cert "cthun-server" cacert (swap! cert-serial-num inc))
        crl (ssl-simple/gen-crl cacert)]
    (save-pems ssl-dir cacert)
    (save-pems ssl-dir cthun-cert)
    (fs/mkdirs (fs/file ssl-dir "ca"))
    (fs/copy (fs/file ssl-dir "certs/ca.pem") (fs/file ssl-dir "ca/ca_crt.pem"))
    (ssl-utils/crl->pem! crl (fs/file ssl-dir "ca" "ca_crl.pem"))))

(defn -main
  [& args]
  (gen-cthun-certs "./test-resources/ssl"))
