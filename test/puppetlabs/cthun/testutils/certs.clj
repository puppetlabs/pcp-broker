(ns puppetlabs.cthun.testutils.certs
  (:require [me.raynes.fs :as fs]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [clj-time.core :as time]
            [schema.core :as schema])
  (:import (java.util Date)
           (java.security PublicKey PrivateKey)
           (java.security.cert X509Certificate X509CRL)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: I'm filing a PR to add all of this utility code to the ssl-utils
;;  library; should be able to remove it and update to the newere version of
;;  that lib soon.

;; TODO: this fn came out of puppet server and should probably be
;;  available from ssl-utils instead
(schema/defn cert-validity-dates :- {:not-before Date :not-after Date}
  "Calculate the not-before & not-after dates that define a certificate's
   period of validity. The value of `ca-ttl` is expected to be in seconds,
   and the dates will be based on the current time. Returns a map in the
   form {:not-before Date :not-after Date}."
  [ca-ttl :- schema/Int]
  (let [now        (time/now)
        not-before (time/minus now (time/days 1))
        not-after  (time/plus now (time/seconds ca-ttl))]
    {:not-before (.toDate not-before)
     :not-after  (.toDate not-after)}))

(def SslKeys
  {:public-key PublicKey
   :private-key PrivateKey
   :x500-name schema/Str
   :certname schema/Str})

(def SslCert
  (assoc SslKeys :cert X509Certificate))

(def key-length 4096)

(schema/defn ^:always-validate gen-keys :- SslKeys
  [certname :- schema/Str]
  (let [keypair     (ssl-utils/generate-key-pair key-length)]
    {:public-key (ssl-utils/get-public-key keypair)
     :private-key (ssl-utils/get-private-key keypair)
     :x500-name (ssl-utils/cn certname)
     :certname certname}))

(schema/defn ^:always-validate gen-cert* :- X509Certificate
  [ca-keys :- SslKeys
   host-keys :- SslKeys
   serial :- schema/Int]
  (let [validity (cert-validity-dates (* 5 60 60 24 365))]
    (ssl-utils/sign-certificate
      (:x500-name ca-keys)
      (:private-key ca-keys)
      serial
      (:not-before validity)
      (:not-after validity)
      (:x500-name host-keys)
      (:public-key host-keys)
      [])))

(schema/defn ^:always-validate gen-cert :- SslCert
  [certname :- schema/Str
   ca-cert :- SslCert
   serial :- schema/Int]
  (let [cert-keys (gen-keys certname)]
    (assoc cert-keys :cert (gen-cert*
                             (dissoc ca-cert :cert)
                             cert-keys
                             serial))))

(schema/defn ^:always-validate gen-self-signed-cert :- SslCert
  [certname :- schema/Str
   serial :- schema/Int]
  (let [cert-keys (gen-keys certname)]
    (assoc cert-keys :cert (gen-cert* cert-keys cert-keys serial))))

(schema/defn ^:always-validate gen-crl :- X509CRL
  [ca-cert :- SslCert]
  (ssl-utils/generate-crl
    (.getIssuerX500Principal (:cert ca-cert))
    (:private-key ca-cert)
    (:public-key ca-cert)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cthun-specific code that would stay in place after the ssl-utils patches
;; described above.

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
  (let [cacert (gen-self-signed-cert "ca" (swap! cert-serial-num inc))
        cthun-cert (gen-cert "cthun-server" cacert (swap! cert-serial-num inc))
        crl (gen-crl cacert)]
    (save-pems ssl-dir cacert)
    (save-pems ssl-dir cthun-cert)
    (fs/mkdirs (fs/file ssl-dir "ca"))
    (fs/copy (fs/file ssl-dir "certs/ca.pem") (fs/file ssl-dir "ca/ca_crt.pem"))
    (ssl-utils/crl->pem! crl (fs/file ssl-dir "ca" "ca_crl.pem"))))

(defn -main
  [& args]
  (gen-cthun-certs "./test-resources/ssl"))
