# Rattrapante

JVM Clojure first, JDK 25+, Clojure 1.12+. Keep Automerge's model explicit.
Do not implement persistent collection interfaces on live document handles.
Meld is a discarded reference and must not be modified.

Use qualified Java method values throughout Clojure source, tests and scripts:
`(CharsetEncoder/.encode encoder input)`, not `(.encode encoder input)`.
Use `Class/new` for constructors and `Class/staticMethod` for static calls.
Field access such as `(.-pointer doc)` remains field access. Keep type hints
where needed to disambiguate overloaded methods or constructors.

Use the codebase graph when indexed, checking coverage for evidence paths;
fall back to source for this new project until it is indexed.

Native bindings are generated from the pinned upstream C API by
`script/generate_bindings.clj`, run with `bb bindings`. Edit the generator rather
than Bindings.java.
Use exact typed Java downcalls; do not introduce invokeWithArguments.
Keep ownership and foreign pointer access inside the internal implementation.
Every retained native value needs an owner. Test rollback, escaped handles,
concurrency, Unicode and GC reachability when changing that implementation.

`bb native` fetches and builds the pinned Automerge source. `bb test` compiles
Java and runs Kaocha. `bb build` produces a JAR for the current native platform.
`bb format:check` checks Clojure formatting. See README for prerequisites.
`bb lint:reflection` compiles Java support, then loads every library namespace
under `src/` in a fresh JVM with `*warn-on-reflection*` enabled. Keep it warning-free.
Native binaries must be tested on each claimed target platform.

The project version is in build.clj, README.md, CHANGELOG.md,
plugins/rattrapante/.claude-plugin/plugin.json and
plugins/rattrapante/skills/rattrapante/SKILL.md. Run `bb version:check` before
release. Keep the usage skill aligned with the public API. Henrik runs deployment;
release preparation does not authorize publication. The remote is
`git@github.com:multiplyco/rattrapante.git`.
