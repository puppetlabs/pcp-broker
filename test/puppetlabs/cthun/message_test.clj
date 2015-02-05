(ns puppetlabs.cthun.message-test
  (:require [clojure.test :refer :all]
            [puppetlabs.cthun.message :refer :all]))

(deftest make-message-test
  (testing "it makes a message"
    (is (= (assoc (make-message) :_data_frame [])
           {:id ""
            :sender ""
            :endpoints []
            :expires nil
            :data_schema ""
            :_hops []
            :_data_frame []
            :_data_flags #{}
            :_destination ""}))))

(deftest add-hop-test
  (with-redefs [puppetlabs.kitchensink.core/timestamp (fn [] "Tomorrow")]
    (testing "it adds a hop"
      (is (= {:_hops [{:server "cth://fake/server"
                       :time   "Another day"
                       :stage  "potato"}]}
             (add-hop {:_hops []} "potato" "Another day"))))

    (testing "it allows timestamp to be optional"
      (is (= {:_hops [{:server "cth://fake/server"
                       :time   "Tomorrow"
                       :stage  "potato"}]}
             (add-hop {:_hops []} "potato"))))

    (testing "it adds hops in the expected order"
      (is (= {:_hops [{:server "cth://fake/server"
                       :time   "Tomorrow"
                       :stage  "potato"}
                      {:server "cth://fake/server"
                       :time   "Tomorrow"
                       :stage  "mash"}]}
             (-> {:_hops []}
                 (add-hop "potato")
                 (add-hop "mash")))))))

(deftest encode-descriptor-test
  (testing "it encodes"
    (is (= 1
           (encode-descriptor {:type 1})))
    (is (= 2r10000001
           (encode-descriptor {:type 1 :flags #{:unused1}})))
    (is (= 2r10010001
           (encode-descriptor {:type 1 :flags #{:unused1 :unused4}})))))

(deftest decode-descriptor-test
  (testing "it decodes"
    (is (= {:type 1 :flags #{}}
           (decode-descriptor 1)))
    (is (= {:type 1 :flags #{:unused1}}
           (decode-descriptor 2r10000001)))
    (is (= {:type 1 :flags #{:unused1 :unused4}}
           (decode-descriptor 2r10010001)))))

(deftest encode-test
  (testing "it returns a byte array"
    ;; subsequent tests will use vec to ignore this
    (is (= (class (encode {}))
           (class (byte-array 0)))))
  (testing "it encodes a message"
    (is (= (vec (encode {}))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 0])))
  (testing "it adds :hops as an optional final chunk"
    (is (= (vec (encode {:_hops "some"}))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 0,
            3, 0 0 0 15, 123 34 104 111 112 115 34 58 34 115 111 109 101 34 125])))
  (testing "it encodes the data chunk"
    (is (= (vec (encode (set-data {} (byte-array (map byte "haha")))))
           [1,
            1, 0 0 0 2, 123 125,
            2, 0 0 0 4, 104 97 104 97]))))

(deftest decode-test
  (with-redefs [schema.core/validate (fn [s d] d)]
    (testing "it only handles version 1 messages"
      (is (thrown? AssertionError
                   (decode (byte-array [2])))))
    (testing "it insists on envelope chunk first"
      (is (thrown? Exception
                   (decode (byte-array [1,
                                        2, 0 0 0 2, 123 125])))))
    (testing "it decodes the null message"
      (is (= (filter-private (decode (byte-array [1, 1, 0 0 0 2, 123 125])))
             {})))
    (testing "data is accessible"
      (let [message (decode (byte-array [1,
                                         1, 0 0 0 2, 123 125,
                                         2, 0 0 0 3, 108 111 108]))]
        (is (= (String. (get-data message)) "lol"))))))

(deftest encoder-roundtrip-test
  (with-redefs [schema.core/validate (fn [s d] d)]
    (testing "it can roundtrip data"
      (let [data (byte-array (map byte "hola"))
            encoded (encode (set-data {} data))
            decoded (decode encoded)]
        (is (= (vec (get-data decoded))
               (vec data)))))))
