(ns build
  (:require [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]))


(def lib 'co.multiply/rattrapante)
(def version "0.1.0")
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


(defn native
  [_]
  (let [result (b/process {:command-args ["bb" "script/native.clj"]})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Native build failed" result)))))


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
