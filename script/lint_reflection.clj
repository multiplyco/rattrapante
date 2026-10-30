(ns lint-reflection
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.io File PushbackReader]))


(set! *warn-on-reflection* true)


(defn source-namespace
  [file]
  (with-open [reader (PushbackReader/new (io/reader file))]
    (binding [*read-eval* false]
      (loop []
        (let [form (read {:read-cond :allow :features #{:clj} :eof ::eof} reader)]
          (cond
            (= ::eof form) nil
            (and (seq? form) (= 'ns (first form))) (second form)
            :else (recur)))))))


;; bb starts a fresh JVM, so each namespace is compiled with warnings enabled.
;; Requiring an entry point may load its dependencies first; require avoids
;; compiling those again and emitting duplicate warnings.
(doseq [file (sort-by File/.getPath (file-seq (io/file "src")))
        :when (and (File/.isFile file)
                (some #(str/ends-with? (File/.getName file) %) [".clj" ".cljc"]))
        :let [namespace (source-namespace file)]
        :when namespace]
  (require namespace))
