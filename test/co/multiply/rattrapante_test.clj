(ns co.multiply.rattrapante-test
  (:require [clojure.test :refer [deftest is]]
    [co.multiply.rattrapante :as r])
  (:import [java.time Instant]))


(deftest scalar-roundtrip
  (let [doc (r/create)
        values {"nil" nil "true" true "false" false "int" Long/MIN_VALUE
                "max" Long/MAX_VALUE "double" 1.25 "empty" "" "str" "hi\u0000😀"
                "counter" (r/counter 7) "uint" (r/uint 18446744073709551615N)
                "timestamp" (Instant/ofEpochMilli -12345)}]
    (r/with-change [tx doc {:message "scalars" :time 123}]
      (doseq [[k v] values] (r/put! tx r/root k v)))
    (is (= values (r/materialize doc)))
    (is (= values (r/materialize (r/load (r/save doc)))))
    (is (= (count values) (r/size doc r/root)))
    (is (= (set (keys values)) (set (r/keys doc r/root))))))


(deftest binary-and-null-vs-missing
  (let [doc (r/create)
        data (byte-array [0 127 -128 -1])]
    (r/with-change [tx doc]
      (r/put! tx r/root "blob" data)
      (r/put! tx r/root "nil" nil))
    (aset data 0 (byte 99))
    (is (= [0 127 -128 -1] (vec (r/get doc r/root "blob"))))
    (is (nil? (r/get doc r/root "nil" :absent)))
    (is (= :absent (r/get doc r/root "no" :absent)))
    (is (= [] (r/get-all doc r/root "no")))))


(deftest nested-objects-and-lists
  (let [doc (r/create)
        obj (r/with-change [tx doc]
              (let [m (r/put-object! tx r/root "map" :map)
                    l (r/put-object! tx m "list" :list)]
                (r/insert! tx l 0 "a")
                (r/insert! tx l 1 "b")
                (let [nested (r/insert-object! tx l 1 :map)]
                  (r/put! tx nested "n" 42))
                (r/put! tx l 0 "A")
                (r/delete! tx l 2)
                m))]
    (is (= :map (r/object-type obj)))
    (is (= {"map" {"list" ["A" {"n" 42}]}} (r/materialize doc)))
    (is (= 42 (r/get-in doc ["map" "list" 1 "n"])))
    (is (= :absent (r/get-in doc ["map" "list" 0 "n"] :absent)))
    (is (= r/root (r/get-in doc [])))
    (is (= (r/object-id obj) (r/object-id (r/get doc r/root "map"))))))


(deftest rollback-and-change-lifetime
  (let [doc (r/create) saved-tx (atom nil) escaped (atom nil)]
    (is (thrown? Exception
          (r/with-change [tx doc]
            (reset! saved-tx tx)
            (reset! escaped (r/put-object! tx r/root "bad" :map))
            (r/put! tx @escaped "nested" 1)
            (throw (ex-info "abort" {})))))
    (is (= {} (r/materialize doc)))
    (is (= [] (r/heads doc)))
    (is (thrown? Exception (r/get doc @escaped "nested")))
    (is (thrown? Exception (r/put! @saved-tx r/root "bad" 2)))
    (is (= :return-value
           (r/with-change [tx doc]
             (reset! saved-tx tx)
             (r/put! tx r/root "good" 1)
             :return-value)))
    (is (thrown? Exception (r/get @saved-tx r/root "good")))
    (is (= {"good" 1} (r/materialize doc)))))


(deftest guardrails
  (let [doc (r/create) other (r/create)]
    (is (thrown? Exception (r/put! doc r/root "x" 1)))
    (r/with-change [tx doc]
      (let [l (r/put-object! tx r/root "list" :list)]
        (is (thrown? Exception (r/put! tx l 0 1)))
        (is (thrown? Exception (r/insert! tx l -1 1)))
        (is (thrown? Exception (r/insert! tx l 2 1)))
        (is (thrown? Exception (r/put! tx r/root :keyword 1)))
        (is (thrown? Exception (r/put! tx r/root "map" {})))
        (is (thrown? Exception (r/put! tx r/root "big" 9223372036854775808N)))
        (is (thrown? Exception (r/with-change [_ doc] nil)))
        (is (thrown? Exception (r/save doc)))
        (is (thrown? Exception (r/heads doc)))
        (is (thrown? Exception (r/get other r/root "x")))
        (is (= :rejected (deref (future (try (r/get doc r/root "x") :bad
                                          (catch Exception _ :rejected))) 2000 :timeout)))
        (r/put! tx r/root "valid" true)))
    (let [obj (r/get doc r/root "list")]
      (is (thrown? Exception (r/get other obj 0))))))


(deftest text-unicode-and-cursors
  (let [doc (r/create)
        txt (r/with-change [tx doc]
              (let [txt (r/put-object! tx r/root "body" :text)]
                (r/splice-text! tx txt 0 0 "A😀éZ") txt))
        token (r/cursor doc txt 3)]
    (is (= 5 (r/size doc txt)))
    (is (= "A😀éZ" (r/text doc txt)))
    (r/with-change [tx doc]
      (r/splice-text! tx txt 0 1 "Hello ")
      (is (thrown? Exception (r/splice-text! tx txt 100 0 "bad"))))
    (is (= "Hello 😀éZ" (r/text doc txt)))
    (is (= 8 (r/cursor-position doc txt token)))
    (r/with-change [tx doc] (r/splice-text! tx txt 6 2 ""))
    (is (= "Hello éZ" (r/text doc txt)))
    (is (= {"body" "Hello éZ"} (r/materialize (r/load (r/save doc)))))))


(deftest branches-conflicts-and-counters
  (let [a (r/create)
        _ (r/with-change [tx a]
            (r/put! tx r/root "count" (r/counter 0))
            (r/put! tx r/root "value" "initial"))
        initial (r/heads a)
        b (r/fork a)]
    (is (not= (r/actor-id a) (r/actor-id b)))
    (is (not= (r/actor-id a) (r/actor-id (r/load (r/save a)))))
    (r/with-change [tx a] (r/increment! tx r/root "count" 3) (r/put! tx r/root "value" "A"))
    (r/with-change [tx b] (r/increment! tx r/root "count" 5) (r/put! tx r/root "value" "B"))
    (r/merge! a b)
    (r/merge! b a)
    (is (= (r/materialize a) (r/materialize b)))
    (is (= (r/counter 8) (r/get a r/root "count")))
    (is (= #{"A" "B"} (set (map :value (r/conflicts a r/root "value")))))
    (is (= 2 (count (set (map :id (r/conflicts a r/root "value"))))))
    (is (= "initial" (r/get (r/fork a initial) r/root "value")))
    (is (= {} (r/materialize (r/fork a []))))
    (r/with-change [tx a] (r/put! tx r/root "value" "resolved"))
    (is (= [] (r/conflicts a r/root "value")))))


(deftest text-marks
  (let [doc (r/create)
        obj (r/with-change [tx doc]
              (let [obj (r/put-object! tx r/root "body" :text)]
                (r/splice-text! tx obj 0 0 "hello")
                (r/mark! tx obj 0 5 "bold" true :both)
                obj))]
    (is (= [{:start 0 :end 5 :name "bold" :value true}] (r/marks doc obj)))
    (r/with-change [tx doc] (r/splice-text! tx obj 5 0 "!"))
    (is (= 6 (:end (first (r/marks doc obj)))))
    (let [other (r/fork doc)]
      (is (= (r/marks doc obj) (r/marks other (r/get other r/root "body")))))
    (r/with-change [tx doc] (r/unmark! tx obj 0 6 "bold"))
    (is (= [] (r/marks doc obj)))))


(deftest concurrent-sequence-edits
  (let [a (r/create)]
    (r/with-change [tx a]
      (r/put-object! tx r/root "list" :list)
      (r/put-object! tx r/root "text" :text))
    (let [b (r/fork a)]
      (doseq [[d s] [[a "A"] [b "B"]]]
        (r/with-change [tx d]
          (r/insert! tx (r/get tx r/root "list") 0 s)
          (r/splice-text! tx (r/get tx r/root "text") 0 0 s)))
      (r/merge! a b)
      (r/merge! b a)
      (is (= (r/materialize a) (r/materialize b)))
      (is (= #{"A" "B"} (set (get (r/materialize a) "list"))))
      (is (= #{\A \B} (set (get (r/materialize a) "text")))))))


(deftest object-identity-is-not-a-path
  (let [doc (r/create)
        old (r/with-change [tx doc]
              (let [old (r/put-object! tx r/root "map" :map)]
                (r/put! tx old "old" true) old))
        fresh (r/with-change [tx doc] (r/put-object! tx r/root "map" :map))]
    (is (not= (r/object-id old) (r/object-id fresh)))
    (r/with-change [tx doc] (r/put! tx old "orphan-edit" true))
    (is (= {"map" {}} (r/materialize doc)))
    (r/with-change [tx doc]
      (let [list (r/put-object! tx r/root "list" :list)
            child (r/insert-object! tx list 0 :map)]
        (r/insert! tx list 0 "before")
        (r/put! tx child "moved" true)))
    (is (= ["before" {"moved" true}] (get (r/materialize doc) "list")))))


(deftest wide-history-heads
  (let [base (r/create)]
    (doseq [i (range 7)]
      (let [branch (r/fork base [])]
        (r/with-change [tx branch] (r/put! tx r/root (str i) i))
        (r/merge! base branch)))
    (let [heads (r/heads base)]
      (is (= 7 (count heads)))
      (is (= (r/materialize base) (r/materialize (r/fork base heads))))
      (is (= [] (r/changes-since base heads))))))


(deftest incremental-persistence-and-changes
  (let [doc (r/create)]
    (r/with-change [tx doc] (r/put! tx r/root "a" 1))
    (let [base (r/save doc) replica (r/load base) before (r/heads doc)]
      (r/with-change [tx doc] (r/put! tx r/root "b" 2))
      (r/load-incremental! replica (r/save-incremental! doc))
      (is (= (r/materialize doc) (r/materialize replica)))
      (let [another (r/load base)]
        (r/apply-changes! another (r/changes-since doc before))
        (is (= (r/materialize doc) (r/materialize another)))))
    (let [another (r/create) changes (r/changes-since doc [])]
      (r/apply-changes! another (reverse changes))
      (r/apply-changes! another changes)
      (is (= (r/heads doc) (r/heads another))))))


(defn exchange!
  [a b]
  (loop [round 0]
    (when (> round 20) (throw (ex-info "Sync did not converge" {})))
    (let [ab (r/generate-sync-message! a)
          _ (when ab (r/receive-sync-message! b ab))
          ba (r/generate-sync-message! b)
          _ (when ba (r/receive-sync-message! a ba))]
      (when (or ab ba) (recur (inc round))))))


(deftest sync-protocol
  (let [a (r/create) b (r/create) sa (r/sync-state a) sb (r/sync-state b)]
    (is (thrown? Exception (r/receive-sync-message! sa (byte-array [0 1 2]))))
    (r/with-change [tx a] (r/put! tx r/root "one" 1))
    (r/with-change [tx b] (r/put! tx r/root "two" 2))
    (exchange! sa sb)
    (is (= {"one" 1 "two" 2} (r/materialize a) (r/materialize b)))
    (is (= (r/heads a) (r/heads b)))
    (is (bytes? (r/encode-sync-state sa)))
    (let [sa2 (r/sync-state a (r/encode-sync-state sa))]
      (r/with-change [tx b] (r/put! tx r/root "three" 3))
      (exchange! sa2 sb)
      (is (= (r/materialize a) (r/materialize b))))))


(deftest concurrent-access
  (let [doc (r/create)]
    (r/with-change [tx doc] (r/put! tx r/root "n" (r/counter 0)))
    (let [workers (doall (repeatedly 4 #(future (dotimes [_ 50]
                                                  (r/with-change [tx doc]
                                                    (r/increment! tx r/root "n" 1))))))]
      (doseq [worker workers] (is (not= :timeout (deref worker 10000 :timeout))))
      (is (= (r/counter 200) (r/get doc r/root "n"))))))


(deftest native-error-recovery
  (is (thrown? Exception (r/load (byte-array [1 2 3 4]))))
  (let [doc (r/create)]
    (r/with-change [tx doc] (r/put! tx r/root "n" 1))
    (let [before (r/heads doc)]
      (is (thrown? Exception
            (r/with-change [tx doc]
              (r/put! tx r/root "would-leak" 2)
              (r/increment! tx r/root "n" 1))))
      (is (= before (r/heads doc)))
      (is (= {"n" 1} (r/materialize doc))))))


(deftest gc-ownership
  ;; A fresh process prevents test reporters and completed futures from keeping
  ;; earlier test documents alive and invalidating the zero-live-results check.
  (let [java (str (System/getProperty "java.home") "/bin/java")
        process (-> (ProcessBuilder/new
                      ^java.util.List
                      [java "--enable-native-access=ALL-UNNAMED"
                       "-Drattrapante.track-native=true" "-cp"
                       (System/getProperty "java.class.path") "clojure.main"
                       "-m" "co.multiply.rattrapante-gc-check"])
                  (ProcessBuilder/.redirectErrorStream true)
                  (ProcessBuilder/.start))]
    (try
      (is (Process/.waitFor process 30 java.util.concurrent.TimeUnit/SECONDS))
      (when (Process/.isAlive process) (Process/.destroyForcibly process))
      (is (= 0 (Process/.waitFor process)) (slurp (Process/.getInputStream process)))
      (finally (Process/.destroy process)))))
