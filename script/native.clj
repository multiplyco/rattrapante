(require
  '[babashka.fs :as fs]
  '[babashka.process :refer [shell]]
  '[clojure.edn :as edn]
  '[clojure.string :as str])


(import
  '[java.security MessageDigest]
  '[java.util HexFormat])


(let [{:keys [archive-url archive-sha256 rust-toolchain features]} (edn/read-string (slurp "native.edn"))
      archive     "target/automerge.tar.gz"
      source      "target/automerge"
      marker      (str source "/.rattrapante-source")
      os          (case (System/getProperty "os.name")
                    "Mac OS X" "darwin"
                    "Linux" "linux"
                    (if (str/starts-with? (System/getProperty "os.name") "Windows") "windows"
                        (throw (ex-info "Unsupported native build OS" {}))))
      arch        (or (get {"aarch64" "aarch64"
                            "arm64"   "aarch64"
                            "amd64"   "x86_64"
                            "x86_64"  "x86_64"}
                        (System/getProperty "os.arch"))
                    (throw (ex-info "Unsupported native architecture" {})))
      library     (case os "darwin" "libautomerge_core.dylib" "linux" "libautomerge_core.so" "windows" "automerge_core.dll")
      destination (str "resources/co/multiply/rattrapante/native/" os "-" arch)]
  (fs/create-dirs "target/native")
  (when-not (fs/exists? archive)
    (shell "curl" "-L" "--fail" "--silent" "--show-error" archive-url "-o" (str archive ".part"))
    (fs/move (str archive ".part") archive))
  (let [actual (HexFormat/.formatHex (HexFormat/of)
                 (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes archive)))]
    (when-not (= archive-sha256 actual)
      (throw (ex-info "Automerge source archive checksum mismatch" {:expected archive-sha256 :actual actual}))))
  (when-not (and (fs/exists? marker) (= archive-sha256 (slurp marker)))
    (when (fs/exists? source) (fs/delete-tree source))
    (fs/create-dirs source)
    (shell "tar" "-xzf" archive "--strip-components=1" "-C" source)
    (spit marker archive-sha256))
  (fs/copy "native/Cargo.lock" (str source "/rust/Cargo.lock") {:replace-existing true})
  ;; Keep the pinned upstream source intact apart from this explicit extension
  ;; module. No Rust layout crosses the ABI: it uses upstream AMresult owners.
  (let [bridge (str source "/rust/automerge-c/src/rattrapante.rs")]
    (when-not (and (fs/exists? bridge) (= (slurp "native/bridge.rs") (slurp bridge)))
      (fs/copy "native/bridge.rs" bridge {:replace-existing true})))
  (let [lib (str source "/rust/automerge-c/src/lib.rs")
        declaration "mod rattrapante;"]
    (when-not (str/includes? (slurp lib) declaration)
      (spit lib (str "\n" declaration "\n") :append true)))
  (shell {:extra-env {"CBINDGEN_TARGET_DIR" (str (fs/absolutize "target/native"))}}
    "cargo" (str "+" rust-toolchain) "rustc"
    "--manifest-path" (str source "/rust/Cargo.toml") "--package" "automerge-c"
    "--release" "--locked" "--features" features "--crate-type" "cdylib")
  (shell "cc" "-std=c11" "-fsyntax-only" "-I" "target/native" "script/abi.c")
  (fs/create-dirs destination)
  (fs/copy (str source "/rust/target/release/" library) (str destination "/" library) {:replace-existing true})
  (println "Built" (str destination "/" library)))
