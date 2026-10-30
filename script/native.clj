;; Builds the native library from the pinned Automerge source: `bb script/native.clj`
;; for this machine's platform, `linux` for x86_64 and aarch64 Linux in Docker, `all`
;; for both. A release JAR carries all three of darwin-aarch64, linux-x86_64 and
;; linux-aarch64, so it is built on an Apple silicon Mac with Docker.
(require
  '[babashka.fs :as fs]
  '[babashka.process :refer [shell]]
  '[clojure.edn :as edn]
  '[clojure.string :as str])


(import
  '[java.security MessageDigest]
  '[java.util HexFormat])


(def native (edn/read-string (slurp "native.edn")))


(def source-dir "target/automerge")


(def linux-images
  "rust:1.90.0-slim-bookworm, each architecture's image: Debian 12, so the library
   needs no glibc newer than its 2.36 (its symbols currently need 2.34)."
  {"x86_64"  {:platform "linux/amd64"
              :image    "rust@sha256:4f572135209e89d488fadb81d8a07309857f9b9c5280b54c85a44b71629e8cca"}
   "aarch64" {:platform "linux/arm64"
              :image    "rust@sha256:13c63e00aecdc716d04866b340403995b9ddb0e0c7fcc097dea9c62119191715"}})


(defn- destination
  [platform]
  (str "resources/co/multiply/rattrapante/native/" platform))


(defn- prepare-source!
  "Download, verify and unpack the pinned Automerge source, with Rattrapante's bridge."
  []
  (let [{:keys [archive-url archive-sha256]} native
        archive "target/automerge.tar.gz"
        marker  (str source-dir "/.rattrapante-source")]
    (fs/create-dirs "target/native")
    (when-not (fs/exists? archive)
      (shell "curl" "-L" "--fail" "--silent" "--show-error" archive-url "-o" (str archive ".part"))
      (fs/move (str archive ".part") archive))
    (let [actual (HexFormat/.formatHex (HexFormat/of)
                   (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes archive)))]
      (when-not (= archive-sha256 actual)
        (throw (ex-info "Automerge source archive checksum mismatch" {:expected archive-sha256 :actual actual}))))
    (when-not (and (fs/exists? marker) (= archive-sha256 (slurp marker)))
      (when (fs/exists? source-dir) (fs/delete-tree source-dir))
      (fs/create-dirs source-dir)
      (shell "tar" "-xzf" archive "--strip-components=1" "-C" source-dir)
      (spit marker archive-sha256))
    (fs/copy "native/Cargo.lock" (str source-dir "/rust/Cargo.lock") {:replace-existing true})
    ;; Keep the pinned upstream source intact apart from this explicit extension
    ;; module. No Rust layout crosses the ABI: it uses upstream AMresult owners.
    (let [bridge (str source-dir "/rust/automerge-c/src/rattrapante.rs")]
      (when-not (and (fs/exists? bridge) (= (slurp "native/bridge.rs") (slurp bridge)))
        (fs/copy "native/bridge.rs" bridge {:replace-existing true})))
    (let [lib         (str source-dir "/rust/automerge-c/src/lib.rs")
          declaration "mod rattrapante;"]
      (when-not (str/includes? (slurp lib) declaration)
        (spit lib (str "\n" declaration "\n") :append true)))))


(defn- build-host!
  "Build the library for this machine's platform with the local toolchain."
  []
  (let [{:keys [rust-toolchain features]} native
        os       (case (System/getProperty "os.name")
                   "Mac OS X" "darwin"
                   "Linux" "linux"
                   (if (str/starts-with? (System/getProperty "os.name") "Windows") "windows"
                       (throw (ex-info "Unsupported native build OS" {}))))
        arch     (or (get {"aarch64" "aarch64"
                           "arm64"   "aarch64"
                           "amd64"   "x86_64"
                           "x86_64"  "x86_64"}
                       (System/getProperty "os.arch"))
                   (throw (ex-info "Unsupported native architecture" {})))
        library  (case os "darwin" "libautomerge_core.dylib" "linux" "libautomerge_core.so" "windows" "automerge_core.dll")
        platform (str os "-" arch)]
    (shell {:extra-env {"CBINDGEN_TARGET_DIR" (str (fs/absolutize "target/native"))}}
      "cargo" (str "+" rust-toolchain) "rustc"
      "--manifest-path" (str source-dir "/rust/Cargo.toml") "--package" "automerge-c"
      "--release" "--locked" "--features" features "--crate-type" "cdylib")
    (shell "cc" "-std=c11" "-fsyntax-only" "-I" "target/native" "script/abi.c")
    (fs/create-dirs (destination platform))
    (fs/copy (str source-dir "/rust/target/release/" library) (str (destination platform) "/" library) {:replace-existing true})
    (println "Built" (str (destination platform) "/" library))))


(defn- build-linux!
  "Build the library for `arch` Linux in Docker, from the source mounted read-only.
   Cargo's registry and each architecture's target directory are kept in volumes
   of their own, so a rebuild is incremental."
  [arch]
  (let [{:keys [rust-toolchain features]} native
        {:keys [platform image]} (linux-images arch)
        platform-dir (str "linux-" arch)
        out          (str "target/native/" platform-dir)]
    (fs/create-dirs out)
    (shell "docker" "run" "--rm" "--platform" platform
      "-v" (str (fs/absolutize source-dir) ":/src:ro")
      "-v" (str (fs/absolutize "script/abi.c") ":/abi.c:ro")
      "-v" (str (fs/absolutize out) ":/out")
      "-v" "rattrapante-cargo-registry:/usr/local/cargo/registry"
      "-v" (str "rattrapante-cargo-target-" arch ":/target")
      "-e" "CARGO_TARGET_DIR=/target"
      "-e" "CBINDGEN_TARGET_DIR=/out"
      image "sh" "-euc"
      (str "cargo +" rust-toolchain " rustc --manifest-path /src/rust/Cargo.toml --package automerge-c"
        " --release --locked --features " features " --crate-type cdylib\n"
        "cc -std=c11 -fsyntax-only -I /out /abi.c\n"
        "cp /target/release/libautomerge_core.so /out/\n"
        "objdump -T /out/libautomerge_core.so | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1 > /out/glibc"))
    (fs/create-dirs (destination platform-dir))
    (fs/copy (str out "/libautomerge_core.so") (str (destination platform-dir) "/libautomerge_core.so")
      {:replace-existing true})
    (println "Built" (str (destination platform-dir) "/libautomerge_core.so") "needs" (str/trim (slurp (str out "/glibc"))))))


(let [target (or (first *command-line-args*) "host")]
  (when-not (#{"host" "linux" "all"} target)
    (throw (ex-info "Unknown native target: one of host, linux or all" {:target target})))
  (prepare-source!)
  (when (#{"host" "all"} target)
    (build-host!))
  (when (#{"linux" "all"} target)
    (run! build-linux! (keys linux-images))))
