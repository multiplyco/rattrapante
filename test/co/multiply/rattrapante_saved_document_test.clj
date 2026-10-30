(ns co.multiply.rattrapante-saved-document-test
  (:require [clojure.test :refer [deftest is testing]]
    [co.multiply.rattrapante :as r])
  (:import [co.multiply.rattrapante SavedDocument]
    [java.util Arrays List Random]
    [java.util.concurrent TimeUnit]))


(defn- document
  []
  (let [doc (r/create)]
    (r/with-change [tx doc]
      (r/put! tx r/root "count" (r/counter 3))
      (let [body (r/put-object! tx r/root "body" :text)]
        (r/splice-text! tx body 0 0 "Hello 😀")
        (r/mark! tx body 0 5 "strong" true)))
    doc))


(deftest saved-document-conversions
  (doseq [doc [(r/create) (document)]]
    (let [saved (r/save doc)
          raw (r/bytes doc)
          wrapped (r/saved-document raw)]
      (is (instance? SavedDocument saved))
      (is (r/saved-document? wrapped))
      (is (not (r/document? wrapped)))
      (is (not (r/saved-document? raw)))
      (is (not (r/saved-document? doc)))
      (is (= (vec raw) (vec (r/bytes saved))))
      (is (= (alength raw) (SavedDocument/.byteSize saved)))
      (is (= saved wrapped (SavedDocument/fromBytes raw)))
      (is (= (hash saved) (hash wrapped)))
      (is (= :found (get {saved :found} wrapped)))
      (let [replicas (mapv r/load [raw saved wrapped])]
        (is (= 4 (count (set (map r/actor-id (cons doc replicas))))))
        (doseq [replica replicas]
          (is (= (r/heads doc) (r/heads replica)))
          (is (= (r/materialize doc) (r/materialize replica)))
          (when-let [body (r/get doc r/root "body")]
            (is (= (r/marks doc body) (r/marks replica (r/get replica r/root "body"))))))))))


(deftest saved-document-owns-its-bytes
  (let [doc (document)
        input (r/bytes doc)
        expected (vec input)
        saved (r/saved-document input)
        captured (r/save doc)
        hash-before (hash saved)
        output (r/bytes saved)]
    (Arrays/fill input (byte 0))
    (Arrays/fill output (byte 0))
    (Arrays/fill (SavedDocument/.toByteArray captured) (byte 0))
    (r/with-change [tx doc] (r/increment! tx r/root "count" 1))
    (is (= expected (vec (r/bytes saved)) (vec (r/bytes captured))))
    (is (= hash-before (hash saved)))
    (is (= (r/counter 3) (r/get (r/load saved) r/root "count")))
    (is (= (r/counter 4) (r/get doc r/root "count")))
    (is (not= saved (r/save doc)))))


(defn- invalid-saved?
  [raw]
  (try
    (r/saved-document raw)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :rattrapante/invalid-saved-document (:type (ex-data e))))))


(deftest saved-document-framing
  (let [doc (document)
        raw (r/bytes doc)
        header (vec (take 9 raw))
        change (first (r/changes-since doc []))
        message (r/generate-sync-message! (r/sync-state doc))]
    (doseq [[description data]
            [["PNG signature" (byte-array [-119 80 78 71 13 10 26 10 0 0])]
             ["empty input" (byte-array 0)]
             ["individual change" change]
             ["sync message" message]
             ["wrong magic" (byte-array (assoc (vec raw) 0 0))]
             ["unknown initial type" (byte-array (assoc (vec raw) 8 127))]
             ["trailing garbage" (byte-array (concat raw [0]))]
             ["truncated length" (byte-array (concat header [-128]))]
             ["overlong length" (byte-array (concat header [-128 0]))]
             ["u64 overflow" (byte-array (concat header (repeat 9 -128) [2]))]
             ["unterminated length" (byte-array (concat header (repeat 10 -128) [0]))]
             ["unsigned u64 too large" (byte-array (concat header (repeat 9 -1) [1]))]
             ["length beyond JVM array" (byte-array (concat header [-1 -1 -1 -1 15]))]
             ["truncated appended change" (byte-array (concat raw (butlast change)))]]]
      (testing description (is (invalid-saved? data))))
    ;; Every prefix of this single-snapshot save is incomplete, including headers.
    (is (every? #(invalid-saved? (Arrays/copyOf raw (int %))) (range (alength raw))))
    (let [bad-type (aclone ^bytes change)]
      (aset-byte bad-type 8 (byte 127))
      (is (invalid-saved? (byte-array (concat raw bad-type)))))
    (is (thrown? IllegalArgumentException (SavedDocument/fromBytes (byte-array [1 2 3]))))))


(deftest framing-is-not-content-validation
  (let [raw (r/bytes (document))]
    ;; Checksum damage preserves framing; only a full load should reject it.
    (aset-byte raw 4 (unchecked-byte (bit-xor 1 (aget raw 4))))
    (let [saved (r/saved-document raw)]
      (is (= (vec raw) (vec (r/bytes saved))))
      (is (thrown? Exception (r/load saved)))
      (is (thrown? Exception (r/load raw))))))


(deftest saved-document-with-appended-changes
  (let [doc (document)
        baseline (r/bytes doc)]
    (r/with-change [tx doc] (r/put! tx r/root "later" true))
    (let [increment (r/save-incremental! doc)
          combined (r/saved-document (byte-array (concat baseline increment)))
          target (r/create)]
      (is (= (r/heads doc) (r/heads (r/load combined))))
      (is (= (r/materialize doc) (r/materialize (r/load combined))))
      (is (identical? target (r/load-incremental! target combined)))
      (is (= (r/heads doc) (r/heads target)))
      ;; A stream may also contain subsequent complete snapshot chunks.
      (let [concatenated (r/saved-document (byte-array (concat baseline (r/bytes doc))))]
        (is (= (r/heads doc) (r/heads (r/load concatenated))))))))


(deftest saved-document-preserves-queued-changes
  (let [source (document)
        _ (r/with-change [tx source] (r/put! tx r/root "dependent" true))
        [parent child] (r/changes-since source [])
        target (r/create)]
    (r/apply-changes! target [child])
    (let [saved (r/save target)
          checked (r/saved-document (r/bytes saved))
          restored (r/load checked)]
      (is (seq (r/missing-deps restored)))
      (is (= (r/missing-deps target) (r/missing-deps restored)))
      (r/apply-changes! restored [parent])
      (is (= (r/heads source) (r/heads restored)))
      (is (= (r/materialize source) (r/materialize restored))))))


(deftest saved-document-guards-and-save-cursors
  (let [doc (document)
        saved (r/save doc)]
    (is (zero? (alength ^bytes (r/save-incremental! doc))))
    (r/with-change [tx doc]
      (r/put! tx r/root "later" true)
      (is (thrown? Exception (r/save doc)))
      (is (thrown? Exception (r/bytes doc)))
      (is (bytes? (r/bytes saved))))
    (r/bytes doc)
    (is (zero? (alength ^bytes (r/save-incremental! doc))))
    (doseq [value [nil "not bytes" [1 2 3] doc]]
      (is (thrown? Exception (r/saved-document value)))
      (is (thrown? Exception (r/load value))))
    (doseq [value [nil "not a document" (r/bytes doc)]]
      (is (thrown? Exception (r/bytes value))))))


(deftest saved-document-concurrent-capture
  (let [doc (r/create)
        _ (r/with-change [tx doc] (r/put! tx r/root "n" 0))
        writer (future (dotimes [i 30]
                         (r/with-change [tx doc]
                           (r/put! tx r/root "n" (inc i))
                           (r/put! tx r/root "twice" (* 2 (inc i))))))
        snapshots (vec (repeatedly 15 #(r/save doc)))]
    (is (not= :timeout (deref writer 10000 :timeout)))
    (doseq [saved snapshots]
      (let [replica (r/load (r/saved-document (r/bytes saved)))
            n (r/get replica r/root "n")]
        (is (= (* 2 n) (r/get replica r/root "twice" 0)))))))


(deftest saved-document-large-payloads
  (let [doc (r/create)
        blob (byte-array 20000)
        text (apply str (repeat 1000 "Hello 😀 "))]
    (Random/.nextBytes (Random/new 42) blob)
    (r/with-change [tx doc]
      (r/put! tx r/root "blob" blob)
      (r/put! tx r/root "text" text))
    (let [saved (r/saved-document (r/bytes doc))
          restored (r/load saved)]
      ;; Exercise multi-byte chunk lengths and native compressed columns.
      (is (> (SavedDocument/.byteSize saved) 16384))
      (is (= (vec blob) (vec (r/get restored r/root "blob"))))
      (is (= text (r/get restored r/root "text")))
      (is (= (r/heads doc) (r/heads restored))))))


(deftest saved-document-without-native-runtime
  (let [raw (vec (r/bytes (document)))
        expression (str "(require '[co.multiply.rattrapante :as r]) "
                     "(let [raw (byte-array " (pr-str raw) ") "
                     "saved (r/saved-document raw)] "
                     "(assert (r/saved-document? saved)) "
                     "(assert (= (vec raw) (vec (r/bytes saved)))))")
        process (-> (ProcessBuilder/new
                      ^List [(str (System/getProperty "java.home") "/bin/java")
                             "-Drattrapante.native.path=/no-native-library-for-saved-document"
                             "-cp" (System/getProperty "java.class.path")
                             "clojure.main" "-e" expression])
                  (ProcessBuilder/.redirectErrorStream true)
                  (ProcessBuilder/.start))]
    (try
      (let [finished? (Process/.waitFor process 30 TimeUnit/SECONDS)]
        (is finished? "SavedDocument conversion should not start a native runtime")
        (when finished?
          (is (zero? (Process/.exitValue process)) (slurp (Process/.getInputStream process)))))
      (finally (Process/.destroyForcibly process)))))
