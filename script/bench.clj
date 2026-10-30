(ns bench
  "Local synthetic load/apply/save probe. Excludes storage, network and Rama.
  Run one scenario per JVM: clojure -J--enable-native-access=ALL-UNNAMED
  -J-Xmx512m -M script/bench.clj hello|note|history|large [warmups samples]."
  (:require [clojure.string :as str]
    [co.multiply.rattrapante :as r])
  (:import [co.multiply.rattrapante SavedDocument]
    [java.util Random]
    [java.lang.management GarbageCollectorMXBean ManagementFactory]))


(set! *warn-on-reflection* true)


(def scenarios
  {"hello"   {:chars 11 :history 0 :batch 6 :peers 3 :block-every 1000}
   "note"    {:chars 20000 :history 1000 :batch 20 :peers 4 :block-every 1000}
   "history" {:chars 20000 :history 10000 :batch 20 :peers 4 :block-every 1000}
   "large"   {:chars 100000 :history 10000 :batch 50 :peers 4 :block-every 1000}})


(defn text-content
  [n]
  (if (= n 11)
    "hello world"
    (let [random (Random/new 42)
          words (str/split "document collaboration editor history change replica field model task local server message paragraph version context text object shared state storage record topology reading writing people work discussion question answer example reference project library data" #" ")
          out (StringBuilder/new)]
      (while (< (StringBuilder/.length out) n)
        (StringBuilder/.append out ^String (nth words (Random/.nextInt random (count words))))
        (StringBuilder/.append out " "))
      (subs (StringBuilder/.toString out) 0 n))))


(defn fixture
  [{:keys [chars history batch peers block-every]}]
  (let [doc (r/create)
        body (r/with-change [tx doc]
               (let [body (r/put-object! tx r/root "body" :text)]
                 (r/splice-text! tx body 0 0 (text-content chars))
                 (doseq [[i position] (map-indexed vector (range 0 chars block-every))]
                   (let [block (r/split-block! tx body (+ position i))]
                     (r/put! tx block "type" "paragraph")
                     (r/put-object! tx block "parents" :list)))
                 body))]
    (dotimes [i history]
      (r/with-change [tx doc]
        (r/splice-text! tx body (dec (r/size tx body)) 1 (str (char (+ 65 (mod i 26)))))))
    (let [baseline (r/heads doc)
          snapshot (r/save doc)
          clients (vec (repeatedly peers #(r/load snapshot)))]
      (dotimes [i batch]
        (let [client (nth clients (mod i peers))]
          (r/with-change [tx client]
            (let [text (r/get tx r/root "body")]
              (r/splice-text! tx text (r/size tx text) 0 (if (zero? (mod i 5)) "😀" "x"))))))
      (let [changes (into [] (mapcat #(:changes (r/capture-delta % baseline))) clients)
            expected (r/load snapshot)]
        (r/apply-changes! expected changes)
        {:snapshot snapshot :changes changes :heads (set (r/heads expected))
         :initial-utf16 (r/size doc body)
         :final-utf16 (r/size expected (r/get expected r/root "body"))}))))


(defn sample
  [{:keys [snapshot changes heads]}]
  (let [t0 (System/nanoTime)
        doc (r/load snapshot)
        t1 (System/nanoTime)
        _ (r/apply-changes! doc changes)
        t2 (System/nanoTime)
        saved (r/save doc)
        t3 (System/nanoTime)]
    (assert (= heads (set (r/heads doc))))
    {:load-ms (/ (- t1 t0) 1e6)
     :apply-ms (/ (- t2 t1) 1e6)
     :save-ms (/ (- t3 t2) 1e6)
     :total-ms (/ (- t3 t0) 1e6)
     :saved-bytes (SavedDocument/.byteSize saved)}))


(defn stats
  [values]
  (let [values (vec (sort values))
        quantile #(nth values (min (dec (count values)) (dec (long (Math/ceil (* % (count values)))))))]
    {:median (quantile 0.5) :p95 (quantile 0.95) :max (peek values)}))


(defn gc-state
  []
  (mapv (fn [^GarbageCollectorMXBean bean]
          {:name (GarbageCollectorMXBean/.getName bean)
           :count (GarbageCollectorMXBean/.getCollectionCount bean)
           :ms (GarbageCollectorMXBean/.getCollectionTime bean)})
    (ManagementFactory/getGarbageCollectorMXBeans)))


(let [[scenario warmups samples] *command-line-args*
      scenario (or scenario "hello")
      config (or (get scenarios scenario)
               (throw (ex-info "Expected hello, note, history or large" {:scenario scenario})))
      warmups (if warmups (Long/parseLong warmups) 10)
      samples (if samples (Long/parseLong samples) 30)
      _ (when-not (and (<= 0 warmups) (pos? samples))
          (throw (ex-info "Expected nonnegative warmups and positive samples" {})))
      data (fixture config)]
  (System/gc)
  (dotimes [_ warmups] (sample data))
  (System/gc)
  (let [before (gc-state)
        measurements (vec (repeatedly samples #(sample data)))]
    (prn {:scenario scenario :config config :warmups warmups :samples samples
          :java (System/getProperty "java.version")
          :os (System/getProperty "os.name") :arch (System/getProperty "os.arch")
          :max-heap-bytes (Runtime/.maxMemory (Runtime/getRuntime))
          :snapshot-bytes (SavedDocument/.byteSize (:snapshot data))
          :delta-bytes (reduce + (map #(alength ^bytes %) (:changes data)))
          :delta-changes (count (:changes data))
          :saved-bytes (:saved-bytes (first measurements))
          :initial-utf16 (:initial-utf16 data) :final-utf16 (:final-utf16 data)
          :timings-ms (into {} (map (fn [k] [k (stats (map k measurements))]))
                        [:load-ms :apply-ms :save-ms :total-ms])
          :gc-before before :gc-after (gc-state)})))
