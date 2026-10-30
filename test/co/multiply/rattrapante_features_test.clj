(ns co.multiply.rattrapante-features-test
  (:require [clojure.test :refer [deftest is]]
    [co.multiply.rattrapante :as r])
  (:import [java.time Instant]))


(defn- plain-text
  [value]
  (let [doc (r/create)
        text (r/with-change [tx doc]
               (let [text (r/put-object! tx r/root "body" :text)]
                 (r/splice-text! tx text 0 0 value)
                 text))]
    [doc text]))


(deftest strict-change-batches
  (let [source (r/create)
        _ (r/with-change [tx source] (r/put! tx r/root "valid" true))
        change (first (r/changes-since source []))
        corrupt (aclone ^bytes change)
        garbage (byte-array [1 2 3 4])]
    (aset-byte corrupt 4 (unchecked-byte (bit-xor 1 (aget corrupt 4))))
    (doseq [existing? [false true]
            bad [garbage corrupt (byte-array (butlast change)) (byte-array (concat change [0]))]]
      (let [target (r/create)]
        (when existing? (r/with-change [tx target] (r/put! tx r/root "existing" true)))
        (let [before (r/heads target)
              value (r/materialize target)]
          (is (thrown? Exception (r/apply-changes! target [change bad])))
          (is (= before (r/heads target)))
          (is (= value (r/materialize target)))
          (r/apply-changes! target [change change])
          (is (true? (r/get target r/root "valid"))))))
    (is (thrown? Exception (r/change-info (byte-array (concat change [0])))))))


(deftest checkpoint-capture-and-retry
  (let [source (r/create) target (r/create)]
    (r/with-change [tx source {:message "first" :time 123}]
      (r/put! tx r/root "count" (r/counter 1)))
    (let [batch (r/capture-delta source [])
          info (r/change-info (first (:changes batch)))
          retry (r/capture-delta source [])]
      (is (= (:heads batch) (:heads retry)))
      (is (= (mapv vec (:changes batch)) (mapv vec (:changes retry))))
      (is (= {:actor (r/actor-id source) :seq 1N :time 123 :message "first" :deps []}
             (select-keys info [:actor :seq :time :message :deps])))
      (is (= (:hash info) (first (:heads batch))))
      (is (= (vec (first (:changes batch))) (vec (r/change-by-hash source (:hash info)))))
      (r/with-change [tx source] (r/increment! tx r/root "count" 2))
      (r/apply-changes! target (:changes batch))
      (r/apply-changes! target (:changes batch))
      (is (= (r/counter 1) (r/get target r/root "count")))
      (let [next-batch (r/capture-delta source (:heads batch))]
        (is (= 1 (count (:changes next-batch))))
        (r/apply-changes! target (:changes next-batch))
        (is (= (r/materialize source) (r/materialize target)))
        (is (= [] (:changes (r/capture-delta source (:heads next-batch)))))))
    (let [unknown (apply str (repeat 64 "0"))]
      (is (nil? (r/change-by-hash source unknown)))
      (is (thrown? Exception (r/capture-delta source [unknown]))))))


(deftest dependencies-survive-persistence
  (let [source (r/create) target (r/create)]
    (r/with-change [tx source] (r/put! tx r/root "one" 1))
    (r/with-change [tx source] (r/put! tx r/root "two" 2))
    (let [[parent child] (r/changes-since source [])]
      (r/apply-changes! target [child])
      (is (= [(:hash (r/change-info parent))] (r/missing-deps target)))
      (is (= {} (r/materialize target)))
      (is (thrown? Exception (r/capture-delta target [])))
      (let [restored (r/load (r/save target))]
        (is (= (r/missing-deps target) (r/missing-deps restored)))
        (r/apply-changes! restored [parent child])
        (is (= [] (r/missing-deps restored)))
        (is (= (r/heads source) (r/heads restored)))))))


(deftest checkpoint-capture-is-atomic
  (let [doc (r/create)
        writer (future (dotimes [i 40] (r/with-change [tx doc] (r/put! tx r/root "n" i))))]
    (dotimes [_ 20]
      (let [{:keys [heads changes]} (r/capture-delta doc [])
            replica (r/create)]
        (r/apply-changes! replica changes)
        (is (= (set heads) (set (r/heads replica))))))
    (is (not= :timeout (deref writer 10000 :timeout)))))


(deftest scalar-boundaries-and-cursor-affinity
  (doseq [operation [#(r/splice-text! %1 %2 2 0 "X")
                     #(r/splice-text! %1 %2 1 1 "")
                     #(r/mark! %1 %2 2 3 "bold" true)
                     #(r/unmark! %1 %2 1 2 "bold")]]
    (let [[doc text] (plain-text "A😀Z")
          before (r/heads doc)]
      (is (thrown? Exception (r/with-change [tx doc] (operation tx text))))
      (is (= before (r/heads doc)))
      (is (= "A😀Z" (r/text doc text)))
      (is (thrown? Exception (r/cursor doc text 2)))))
  (let [[doc text] (plain-text "")
        start (r/cursor doc text :start)
        end (r/cursor doc text :end)]
    (r/with-change [tx doc] (r/splice-text! tx text 0 0 "abc"))
    (is (= 0 (r/cursor-position doc text start)))
    (is (= 3 (r/cursor-position doc text end)))
    (let [before (r/cursor doc text 1 {:move :before})
          after (r/cursor doc text 1 {:move :after})]
      (r/with-change [tx doc] (r/splice-text! tx text 1 1 ""))
      (is (= 0 (r/cursor-position doc text before)))
      (is (= 1 (r/cursor-position doc text after))))))


(deftest rich-text-blocks-and-object-addresses
  (let [[doc text] (plain-text "Hello 😀")
        block (r/with-change [tx doc]
                (let [block (r/split-block! tx text 0)]
                  (r/put! tx block "type" "paragraph")
                  (r/put-object! tx block "parents" :list)
                  (r/mark! tx text 1 6 "bold" true)
                  block))
        id (r/object-id block)
        copied (r/load (r/save doc))
        body (r/get copied r/root "body")]
    (is (= [{:type :block :index 0 :object-id id}
            {:type :text :index 1 :text "Hello" :marks {"bold" true}}
            {:type :text :index 6 :text " 😀" :marks {}}]
           (r/spans doc text) (r/spans copied body)))
    (is (= id (r/object-id (r/block-at copied body 0))))
    (is (nil? (r/block-at copied body 1)))
    (is (= {"type" "paragraph" "parents" []} (r/materialize copied (r/resolve-object copied id))))
    (r/with-change [tx copied] (r/put! tx (r/resolve-object tx id) "type" "heading"))
    (r/merge! doc copied)
    (is (= "heading" (r/get doc block "type")))
    (is (thrown? Exception (r/with-change [tx doc] (r/update-text! tx text "flattened"))))
    (r/with-change [tx doc] (r/join-block! tx text 0))
    (is (= "Hello 😀" (r/text doc text)))
    (is (thrown? Exception (r/with-change [tx doc] (r/join-block! tx text 0))))
    (is (= id (r/object-id (r/resolve-object doc id))))))


(deftest block-rollback-and-guards
  (let [[doc text] (plain-text "x")
        escaped (atom nil)
        before (r/heads doc)]
    (is (thrown? Exception
          (r/with-change [tx doc]
            (reset! escaped (r/split-block! tx text 0))
            (throw (ex-info "abort" {})))))
    (is (= before (r/heads doc)))
    (is (thrown? Exception (r/get doc @escaped "type")))
    (is (thrown? Exception (r/resolve-object doc (r/object-id @escaped)))))
  (let [[doc text] (plain-text "abc")]
    (r/with-change [_ doc]
      (is (thrown? Exception (r/diff doc [])))
      (is (thrown? Exception (r/capture-delta doc [])))
      (is (thrown? Exception (r/spans (r/create) text))))))


(deftest ai-edits-against-historical-context
  (let [[doc text] (plain-text "hello world")
        base (r/heads doc)
        ai (r/fork doc base)
        ai-text (r/resolve-object ai (r/object-id text))]
    (r/with-change [tx doc] (r/splice-text! tx text 0 0 "Human: "))
    (r/with-change [tx ai] (r/update-text! tx ai-text "hello universe"))
    (r/apply-changes! doc (:changes (r/capture-delta ai base)))
    (is (= "Human: hello universe" (r/text doc text)))
    (let [historical (r/fork doc base)]
      (is (= "hello world" (r/text historical (r/resolve-object historical (r/object-id text))))))))


(deftest patches-preserve-types-and-identities
  (let [doc (r/create)]
    (r/with-change [tx doc]
      (r/put! tx r/root "counter" (r/counter 3))
      (r/put! tx r/root "uint" (r/uint 18446744073709551615N))
      (r/put! tx r/root "time" (Instant/ofEpochMilli 123))
      (r/put! tx r/root "bytes" (byte-array [0 -1]))
      (r/put! tx r/root "float" 1.0)
      (r/put! tx r/root "escaped\"\n" "\u0000😀\\")
      (let [list (r/put-object! tx r/root "list" :list)]
        (r/insert! tx list 0 true))
      (let [text (r/put-object! tx r/root "body" :text)]
        (r/splice-text! tx text 0 0 "a😀b")
        (r/mark! tx text 0 1 "strong" true)))
    (let [patches (r/diff doc [])
          root-values (into {} (keep #(when (= :put-map (:action %)) [(:key %) (:value %)])) patches)
          before (r/heads doc)]
      (is (= (r/counter 3) (get root-values "counter")))
      (is (= (r/uint 18446744073709551615N) (get root-values "uint")))
      (is (= (Instant/ofEpochMilli 123) (get root-values "time")))
      (is (= [0 -1] (vec (get root-values "bytes"))))
      (is (instance? Double (get root-values "float")))
      (is (= "\u0000😀\\" (get root-values "escaped\"\n")))
      (is (= :list (:object-type (get root-values "list"))))
      (is (some #(= :splice-text (:action %)) patches))
      (r/with-change [tx doc]
        (r/increment! tx r/root "counter" 2)
        (r/delete! tx r/root "uint")
        (r/put! tx (r/get tx r/root "list") 0 false)
        (r/splice-text! tx (r/get tx r/root "body") 1 2 "Z"))
      (is (= #{:increment :delete-map :put-seq :delete-seq :splice-text}
             (set (map :action (r/diff doc before)))))
      (is (= [] (r/diff doc before before)))
      (is (seq (r/diff doc (r/heads doc) before)))
      (is (thrown? Exception (r/diff doc [(apply str (repeat 64 "0"))]))))))


(deftest zero-width-marks-and-block-replacement
  (let [[doc text] (plain-text "")]
    (is (= {} (r/marks-at doc text 0)))
    (r/with-change [tx doc] (r/mark! tx text 0 0 "bold" true :both))
    (is (= [] (r/marks doc text)))
    (r/with-change [tx doc]
      (r/splice-text! tx text 0 0 "abc")
      (r/mark! tx text 0 3 "bold" true :both))
    (is (= {"bold" true} (r/marks-at doc text 1)))
    (is (map? (r/marks-at doc text 3)))
    (is (= [{:start 0 :end 3 :name "bold" :value true}] (r/marks doc text)))
    (let [old (r/with-change [tx doc] (r/split-block! tx text 0))
          fresh (r/with-change [tx doc] (r/replace-block! tx text 0))]
      (is (not= (r/object-id old) (r/object-id fresh)))
      (is (= (r/object-id fresh) (r/object-id (r/block-at doc text 0))))
      (is (= "\uFFFCabc" (r/text doc text))))))


(deftest invalid-utf16-rolls-back
  (let [[doc text] (plain-text "safe")
        before (r/heads doc)
        invalid (String/new (char-array [(char 0xD800)]))]
    (is (thrown? Exception
          (r/with-change [tx doc]
            (r/split-block! tx text 0)
            (r/splice-text! tx text 0 0 invalid))))
    (is (= before (r/heads doc)))
    (is (= "safe" (r/text doc text)))))


(deftest retained-handles-and-peer-state-survive-batch-application
  (let [[doc text] (plain-text "abc")
        peer (r/fork doc)
        state (r/sync-state doc)
        peer-state (r/sync-state peer)
        baseline (r/heads doc)]
    (r/with-change [tx peer]
      (r/split-block! tx (r/resolve-object tx (r/object-id text)) 0))
    (r/apply-changes! doc (:changes (r/capture-delta peer baseline)))
    (is (= "\uFFFCabc" (r/text doc text)))
    (r/with-change [tx doc] (r/put! tx (r/block-at tx text 0) "type" "paragraph"))
    (loop [round 0]
      (is (< round 15))
      (when (< round 15)
        (let [ab (r/generate-sync-message! state)
              _ (when ab (r/receive-sync-message! peer-state ab))
              ba (r/generate-sync-message! peer-state)
              _ (when ba (r/receive-sync-message! state ba))]
          (when (or ab ba) (recur (inc round))))))
    (is (= (r/heads doc) (r/heads peer)))
    (is (= "paragraph" (r/get peer (r/block-at peer (r/get peer r/root "body") 0) "type")))))


(deftest microbatch-replay-from-persisted-state
  (let [[seed _] (plain-text "")]
    (r/with-change [tx seed] (r/put! tx r/root "count" (r/counter 0)))
    (let [base (r/heads seed)
          persisted (atom (r/save seed))
          alice (r/load @persisted)
          bob (r/load @persisted)]
      (doseq [[client label increment] [[alice "A" 1] [bob "B" 2]]]
        (dotimes [_ 5]
          (r/with-change [tx client]
            (r/increment! tx r/root "count" increment)
            (let [body (r/get tx r/root "body")]
              (r/splice-text! tx body (r/size tx body) 0 label)))))
      (let [alice-delta (r/capture-delta alice base)
            bob-delta (r/capture-delta bob base)
            batch (vec (concat (:changes alice-delta) (:changes bob-delta) (:changes alice-delta)))]
        ;; An attempt can fail after application but before the result is stored.
        ;; Retrying uses the unchanged persisted bytes and identical depot batch.
        (is (thrown? Exception
              (let [working (r/load @persisted)]
                (r/apply-changes! working batch)
                (is (= (r/counter 15) (r/get working r/root "count")))
                (throw (ex-info "Persistence attempt failed" {})))))
        (is (= (r/counter 0) (r/get (r/load @persisted) r/root "count")))
        (let [working (r/load @persisted)]
          (r/apply-changes! working batch)
          (reset! persisted (r/save working)))
        (r/merge! alice bob)
        (let [restored (r/load @persisted)
              before (r/heads restored)]
          (is (= (r/counter 15) (r/get restored r/root "count")))
          (is (= (r/materialize alice) (r/materialize restored)))
          (is (= (set (r/heads alice)) (set before)))
          (r/apply-changes! restored batch)
          (is (= before (r/heads restored)))
          (is (= (r/counter 15) (r/get restored r/root "count"))))))))


(deftest author-attribution-is-distinct-from-actor-identity
  (let [doc (r/create)
        value (byte-array [1 2 3])]
    (is (nil? (r/author doc)))
    (r/set-author! doc value)
    (aset-byte value 0 (byte 9))
    (is (= [1 2 3] (vec (r/author doc))))
    (let [actor (r/actor-id doc)]
      (r/set-author! doc (byte-array [1 2 3]))
      (is (= actor (r/actor-id doc)))
      (r/with-change [tx doc]
        (is (thrown? Exception (r/set-author! doc nil)))
        (r/put! tx r/root "by" "model"))
      (let [batch (r/capture-delta doc [])
            hash (first (:heads batch))
            restored (r/load (r/save doc))]
        (is (= [1 2 3] (vec (:author (r/change-info (r/change-by-hash restored hash)))))))
      (r/set-author! doc (byte-array [4]))
      (is (not= actor (r/actor-id doc)))
      (r/set-author! doc nil)
      (is (nil? (r/author doc))))))
