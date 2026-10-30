(ns co.multiply.rattrapante-gc-check
  (:require [co.multiply.rattrapante :as r])
  (:import [co.multiply.rattrapante.internal FFM]
    [java.lang.ref WeakReference Reference]))


(defn await-empty
  []
  (loop [attempt 0]
    (System/gc)
    (Thread/sleep 30)
    (cond
      (zero? (FFM/liveResults)) true
      (< attempt 200) (recur (inc attempt))
      :else (throw (ex-info "Native results were not reclaimed" {:live (FFM/liveResults)})))))


(defn abandon
  []
  (dotimes [_ 200]
    (let [doc (r/create)]
      (r/with-change [tx doc]
        (let [m (r/put-object! tx r/root "map" :map)] (r/put! tx m "n" 42)))
      (r/materialize doc))))


(defn retain-child
  []
  (let [[obj weak]
        (let [doc (r/create)
              obj (r/with-change [tx doc]
                    (let [obj (r/put-object! tx r/root "child" :map)]
                      (r/put! tx obj "n" 42) obj))]
          [obj (WeakReference/new doc)])]
    (System/gc)
    (assert (some? (WeakReference/.get weak)))
    (assert (= 42 (r/get (WeakReference/.get weak) obj "n")))
    (Reference/reachabilityFence obj)))


(defn saved-without-owner
  []
  (let [doc (r/create)]
    (r/with-change [tx doc] (r/put! tx r/root "n" 42))
    [(r/save doc) (WeakReference/new doc)]))


(defn retain-saved
  []
  (let [[saved weak] (saved-without-owner)]
    (await-empty)
    (assert (nil? (WeakReference/.get weak)))
    (assert (= 42 (r/get (r/load saved) r/root "n")))
    (Reference/reachabilityFence saved)))


(defn churn
  []
  (let [doc (r/create)
        running (atom true)
        gc-thread (doto (Thread/new ^Runnable #(while @running (System/gc) (Thread/sleep 5)))
                    (Thread/.setDaemon true) (Thread/.start))]
    (try
      (r/with-change [tx doc]
        (let [obj (r/put-object! tx r/root "child" :map)] (r/put! tx obj "n" 42)))
      (dotimes [i 3000]
        (let [obj (r/get doc r/root "child")]
          (assert (= 42 (r/get doc obj "n"))))
        (when (zero? (mod i 100))
          (assert (= {"child" {"n" 42}} (r/materialize (r/load (r/save doc)))))))
      (finally (reset! running false) (Thread/.join gc-thread)))))


(defn bridge-churn
  []
  (dotimes [_ 50]
    (let [doc (r/create)
          body (r/with-change [tx doc]
                 (let [body (r/put-object! tx r/root "body" :text)
                       block (r/split-block! tx body 0)]
                   (r/put! tx block "type" "paragraph")
                   (r/splice-text! tx body 1 0 "hello 😀")
                   body))
          replica (r/create)
          {:keys [changes]} (r/capture-delta doc [])]
      (r/apply-changes! replica changes)
      (assert (seq (r/diff doc [])))
      (assert (seq (:hash (r/change-info (first changes)))))
      (let [block (r/resolve-object replica (:object-id (first (r/spans doc body))))]
        (System/gc)
        (assert (= "paragraph" (r/get replica block "type"))))
      (r/generate-sync-message! (r/sync-state replica))
      (try (r/apply-changes! replica [(byte-array [0])]) (catch Exception _)))))


(defn -main
  [& _]
  (abandon)
  (await-empty)
  (retain-child)
  (await-empty)
  (retain-saved)
  (await-empty)
  (churn)
  (await-empty)
  (bridge-churn)
  (await-empty)
  (println "Native GC ownership and concurrent collection checks passed."))
