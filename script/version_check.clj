(require
  '[cheshire.core :as json])


(let [version (second (re-find #"\(def version \"([^\"]+)\"\)" (slurp "build.clj")))
      coordinate #"co\.multiply/rattrapante \{:mvn/version \"([^\"]+)\"\}"
      sources {"README.md" (second (re-find coordinate (slurp "README.md")))
               "CHANGELOG.md" (second (re-find #"(?m)^## ([^\s]+) — " (slurp "CHANGELOG.md")))
               "plugins/rattrapante/.claude-plugin/plugin.json"
               (:version (json/parse-string (slurp "plugins/rattrapante/.claude-plugin/plugin.json") true))
               "plugins/rattrapante/skills/rattrapante/SKILL.md"
               (second (re-find coordinate (slurp "plugins/rattrapante/skills/rattrapante/SKILL.md")))}
      stale (into {} (remove (fn [[_ actual]] (= version actual))) sources)]
  (when-not (and version (empty? stale))
    (throw (ex-info "Project version is inconsistent" {:version version :stale stale})))
  (println "Version" version "in sync."))
