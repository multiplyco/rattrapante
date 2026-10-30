(require
  '[clojure.java.io :as io]
  '[clojure.java.shell :as shell]
  '[co.multiply.rattrapante :as r])


(import
  '[java.io BufferedReader File InputStreamReader OutputStream PrintWriter]
  '[java.lang ProcessBuilder$Redirect]
  '[java.nio.file Files]
  '[java.util Base64 Base64$Decoder Base64$Encoder]
  '[java.util List])


(let [doc (r/create)]
  (r/with-change [tx doc]
    (r/put! tx r/root "title" "literal")
    (r/put! tx r/root "count" (r/counter 1))
    (let [text  (r/put-object! tx r/root "body" :text)
          items (r/put-object! tx r/root "items" :list)]
      (r/splice-text! tx text 0 0 "😀Hello")
      (r/insert! tx items 0 1)
      (r/insert! tx items 1 2)))
  (with-open [out (io/output-stream "target/interop/clojure.automerge")]
    (OutputStream/.write out ^bytes (r/bytes doc)))
  (let [{:keys [exit out err]} (shell/sh "node" "script/interop.cjs" "document")]
    (assert (zero? exit) (str out err))
    (print out))
  (let [saved (r/saved-document (Files/readAllBytes (File/.toPath (io/file "target/interop/javascript.automerge"))))
        loaded (r/load saved)]
    (r/merge! doc loaded)
    (assert (= {"title" "literal" "count" (r/counter 3) "items" [1 2 3]
                "body"  "😀 from JS Hello" "fromJS" "collaborative"}
               (r/materialize doc)))))


(let [doc     (r/create)
      _       (r/with-change [tx doc] (r/put! tx r/root "fromClj" 42))
      state   (r/sync-state doc)
      process (ProcessBuilder/.start (doto (ProcessBuilder/new ^List ["node" "script/interop.cjs" "sync"])
                                       (ProcessBuilder/.redirectError ProcessBuilder$Redirect/INHERIT)))
      encoder (Base64/getEncoder)
      decoder (Base64/getDecoder)]
  (try
    (with-open [reader (BufferedReader/new (InputStreamReader/new (Process/.getInputStream process)))
                writer (PrintWriter/new (Process/.getOutputStream process) true)]
      (loop [round 0]
        (assert (< round 20) "Cross-runtime sync did not converge")
        (let [message (r/generate-sync-message! state)]
          (PrintWriter/.println writer (if message (Base64$Encoder/.encodeToString encoder message) "-"))
          (let [response (BufferedReader/.readLine reader)]
            (assert response "JS sync process exited unexpectedly")
            (when-not (= "-" response) (r/receive-sync-message! state (Base64$Decoder/.decode decoder response)))
            (when (or message (not= "-" response)) (recur (inc round))))))
      (PrintWriter/.println writer "save")
      (let [other (r/load (Base64$Decoder/.decode decoder (BufferedReader/.readLine reader)))]
        (assert (= (set (r/heads doc)) (set (r/heads other))))))
    (assert (= {"fromClj" 42 "fromJS" "over sync"} (r/materialize doc)))
    (finally (Process/.destroy process))))


(let [doc (r/create)
      body (r/with-change [tx doc]
             (let [body (r/put-object! tx r/root "body" :text)]
               (r/splice-text! tx body 0 0 "Hello😀")
               (let [block (r/split-block! tx body 0)]
                 (r/put! tx block "type" "paragraph")
                 (r/put-object! tx block "parents" :list))
               (r/mark! tx body 1 6 "strong" true)
               body))]
  (r/with-change [tx doc] (r/put! tx r/root "endCursor" (r/cursor tx body :end)))
  (with-open [out (io/output-stream "target/interop/rich-clojure.automerge")]
    (OutputStream/.write out ^bytes (r/bytes (r/save doc))))
  (let [{:keys [exit out err]} (shell/sh "node" "script/interop.cjs" "rich-text")]
    (assert (zero? exit) (str out err))
    (print out))
  (let [loaded (r/load (r/saved-document (Files/readAllBytes (File/.toPath (io/file "target/interop/rich-javascript.automerge")))))
        base (r/heads doc)]
    (r/apply-changes! doc (:changes (r/capture-delta loaded base)))
    (assert (= "heading" (get (r/materialize doc (r/block-at doc body 0)) "type")))
    (assert (= 2 (count (filter #(= :block (:type %)) (r/spans doc body)))))
    (assert (seq (r/diff doc base)))
    (assert (= (r/size doc body) (r/cursor-position doc body (r/get doc r/root "jsEndCursor"))))
    (r/with-change [tx doc]
      (r/put! tx (r/get tx (r/block-at tx body 0) "attrs") "level" 3)
      (r/join-block! tx body (:index (last (filter #(= :block (:type %)) (r/spans tx body))))))
    (with-open [out (io/output-stream "target/interop/rich-final.automerge")]
      (OutputStream/.write out ^bytes (r/bytes doc)))
    (let [{:keys [exit out err]} (shell/sh "node" "script/interop.cjs" "rich-check")]
      (assert (zero? exit) (str out err)))))


(println "Clojure ↔ Automerge 3.5.0 document, rich-text and sync interoperability passed.")
(shutdown-agents)
