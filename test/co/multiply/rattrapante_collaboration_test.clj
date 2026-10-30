(ns co.multiply.rattrapante-collaboration-test
  (:require [clojure.test :refer [deftest is]]
    [co.multiply.rattrapante :as r]))


(defn- connection
  [a b]
  [(r/sync-state a) (r/sync-state b)])


(defn- exchange!
  [[a b]]
  (loop [round 0]
    (when (>= round 20) (throw (ex-info "Peers did not converge" {})))
    (let [ab (r/generate-sync-message! a)
          _ (when ab (r/receive-sync-message! b ab))
          ba (r/generate-sync-message! b)
          _ (when ba (r/receive-sync-message! a ba))]
      (when (or ab ba) (recur (inc round))))))


(deftest humans-and-model-through-two-server-replicas
  (let [seed (r/create)]
    (r/with-change [tx seed]
      (let [body (r/put-object! tx r/root "body" :text)]
        (r/splice-text! tx body 0 0 "hello world"))
      (r/put! tx r/root "edits" (r/counter 0)))
    (let [baseline (r/heads seed)
          snapshot (r/save seed)
          [alice bob model server-a server-b] (repeatedly 5 #(r/load snapshot))
          alice-connection (connection alice server-a)
          bob-connection (connection bob server-b)
          model-connection (connection model server-a)]
      ;; Everyone edits the version they read, before receiving anyone else's edit.
      (r/with-change [tx alice]
        (r/splice-text! tx (r/get tx r/root "body") 0 0 "Hi: ")
        (r/increment! tx r/root "edits" 1))
      (r/with-change [tx bob]
        (r/put! tx r/root "reviewed" true)
        (r/mark! tx (r/get tx r/root "body") 0 5 "strong" true)
        (r/increment! tx r/root "edits" 1))
      (r/set-author! model (byte-array [1 2 3]))
      (r/with-change [tx model]
        (r/update-text! tx (r/get tx r/root "body") "hello universe")
        (r/increment! tx r/root "edits" 1))
      (run! exchange! [alice-connection bob-connection model-connection])
      (let [persisted (r/load snapshot)
            a-delta (r/capture-delta server-a baseline)
            b-delta (r/capture-delta server-b baseline)
            batch (vec (concat (:changes a-delta) (:changes b-delta)))]
        (r/apply-changes! persisted batch)
        (r/apply-changes! persisted batch)
        ;; The two servers learn about each other only through committed history.
        (let [committed (r/load (r/save persisted))
              feedback (:changes (r/capture-delta committed baseline))]
          (doseq [server [server-a server-b]] (r/apply-changes! server feedback))
          (run! exchange! [alice-connection bob-connection model-connection])
          ;; The concurrent prefix falls inside the mark, as in Automerge JS 3.5.0.
          (is (= [{:start 0 :end 9 :name "strong" :value true}]
                 (r/marks committed (r/get committed r/root "body"))))
          (doseq [replica [alice bob model server-a server-b]]
            (is (= (set (r/heads committed)) (set (r/heads replica))))
            (is (= {"body" "Hi: hello universe" "edits" (r/counter 3) "reviewed" true}
                   (r/materialize replica)))
            (is (= (r/marks committed (r/get committed r/root "body"))
                   (r/marks replica (r/get replica r/root "body")))))
          ;; A replacement server rebuilds from storage and reconnects with fresh sync state.
          (let [replacement (r/load (r/save committed))
                reconnected (connection bob replacement)]
            (r/with-change [tx bob] (r/increment! tx r/root "edits" 1))
            (exchange! reconnected)
            (let [delta (r/capture-delta replacement (r/heads committed))]
              (r/apply-changes! committed (:changes delta))
              (r/apply-changes! committed (:changes delta))
              (is (= (r/counter 4) (r/get committed r/root "edits")))
              (is (= (set (r/heads bob)) (set (r/heads committed)))))))))))
