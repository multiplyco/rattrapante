(ns build
  (:require [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]))


(def lib 'co.multiply/rattrapante)
(def version "0.1.1")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def scm-url "https://github.com/multiplyco/rattrapante")


(defn clean
  [_]
  ;; Native source and Cargo cache are deliberately separate from JVM outputs.
  (b/delete {:path class-dir})
  (b/delete {:path jar-file}))


(defn compile-java
  [_]
  (b/javac {:src-dirs ["src"] :class-dir class-dir :basis @basis
            :javac-opts ["--release" "25" "-Xlint:all"]}))


(def native-libraries
  "The native libraries a release JAR must carry, one per supported platform."
  ["darwin-aarch64/libautomerge_core.dylib"
   "linux-x86_64/libautomerge_core.so"
   "linux-aarch64/libautomerge_core.so"])


(defn native
  "Build the native libraries of every supported platform: this machine's, and Linux's in Docker."
  [_]
  (let [result (b/process {:command-args ["bb" "script/native.clj" "all"]})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Native build failed" result))))
  (let [missing (remove #(.exists (java.io.File. (str "resources/co/multiply/rattrapante/native/" %)))
                  native-libraries)]
    (when (seq missing)
      (throw (ex-info "A release JAR needs every platform's native library; build it on an Apple silicon Mac with Docker"
               {:missing (vec missing)})))))


(defn jar
  [_]
  (native nil)
  (clean nil)
  (compile-java nil)
  (b/write-pom {:class-dir class-dir :lib lib :version version :basis @basis :src-dirs ["src"]
                :scm {:url scm-url :connection (str "scm:git:" scm-url)
                      :developerConnection (str "scm:git:" scm-url)
                      :tag (str "v" version)}
                :pom-data [[:licenses [:license [:name "Eclipse Public License 2.0"]
                                       [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
  (b/copy-file {:src "native.edn" :target (str class-dir "/META-INF/rattrapante-native.edn")})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built" jar-file))


(defn install
  [_]
  (jar nil)
  (b/install {:basis @basis :lib lib :version version :jar-file jar-file :class-dir class-dir}))


(defn deploy
  [_]
  (jar nil)
  (deploy/deploy {:installer :remote :artifact jar-file
                  :pom-file (str class-dir "/META-INF/maven/co.multiply/rattrapante/pom.xml")
                  :repository {"clojars" {:url "https://clojars.org/repo"
                                          :username (System/getenv "CLOJARS_DEPLOY_MAVEN_USERNAME")
                                          :password (System/getenv "CLOJARS_DEPLOY_MAVEN_PASSWORD")}}}))


(defn version-str
  [_]
  (println version))


(defn tag
  [_]
  (let [tag (str "v" version)]
    (b/git-process {:git-args ["tag" tag]})
    (b/git-process {:git-args ["push" "origin" tag]})))
