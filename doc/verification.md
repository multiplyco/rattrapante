# Initial verification — 2026-09-28

Host: macOS ARM64. Native source: commit
`4d2a8f6bfecfb7f1e4fca126e6d2122ac526f903` (JS 3.5.0, Rust 0.12.0), built with
Rust 1.90.0, `--release --locked --features automerge/utf16-indexing --crate-type cdylib`.

- `bb test` with Corretto 25.0.2: 16 tests, 83 assertions, zero failures/errors.
- The final packaged JAR, with source/resource/class output directories removed
  from the classpath, passed the same 16 tests and 83 assertions on Corretto 27.
  Resource and class code-source URLs were checked to confirm JAR loading.
- The isolated GC subprocess reclaimed all tracked results after abandoned
  documents, kept an owner alive through a child reference, and completed 3,000
  object reads while another thread requested GC. It ended with zero live results.
- Official `@automerge/automerge` 3.5.0 read and edited a JVM-generated document.
  The JVM then read and merged it, preserving text, counters, lists and scalar
  string distinctions. Independent JVM and JS replicas also converged through
  the binary peer sync protocol and ended with identical history heads.
- `clj-kondo --lint src test script build.clj`: zero errors/warnings.
- `cljstyle check src test script build.clj resources/clj-kondo.exports`: passed.
- `bb version:check`: passed.
- Native ABI static assertions and JDK 25 Java compilation passed. The JAR
  contains the platform native library, Java stubs, Clojure source, clj-kondo
  exports and the Automerge license notice.

Cargo emitted upstream workspace-profile/unused bench-key warnings; the release
build succeeded. Babashka subprocess cleanup required an unsandboxed run in the
agent environment because macOS process enumeration was restricted in the sandbox.
The ordinary build and test commands passed outside that restriction.

No performance benchmark, Linux/Windows run or artifact publication was performed.
This record describes the tested initial implementation, not a general guarantee
of native-memory or cross-platform correctness under all workloads.

## Library extensions — 2026-10-19

Host: macOS ARM64. The upstream pin and Rust toolchain are unchanged. The native
build now includes `native/bridge.rs`; Java bindings are generated from the
upstream declarations and this extension together.

- `bb test` on Corretto 27: 30 tests, 231 assertions, zero failures/errors.
- The packaged JAR passed the same suite on both Corretto 25.0.2 and Corretto 27.
  Source, resources and compiled-class directories were excluded from the
  classpath; resource URLs confirmed that library source and bindings came from
  the JAR. Each suite included the isolated native-GC subprocess.
- Strict ingestion rejected truncated bytes, trailing garbage and checksum
  corruption. A separately decoded, valid change with an actor/sequence collision
  failed during application without retaining an earlier change in that batch.
- Checkpoint capture was exercised alongside concurrent writers and through
  duplicate delivery. Queued dependencies survived a full save/reload; incomplete
  delta capture failed explicitly rather than dropping queued changes.
- Rich-text block handles, serialized object IDs, rollback invalidation, cursor
  endpoints/affinity, Unicode boundaries, author metadata and native patches passed.
- The existing JS 3.5.0 interoperability script passed file and peer synchronization
  checks, plus bidirectional block/attribute editing, mark preservation and endpoint
  cursors. JavaScript-created collaborative text inside block attributes remained
  explicit object handles in Clojure.
- Java compilation for release 25, native ABI checks, clj-kondo, cljstyle, Rust
  formatting, the reflection check and version consistency passed. Regenerating
  all 184 Java downcalls was byte-for-byte stable.

That implementation cloned native document state to provide failure atomicity;
the subsequent in-place application change is recorded below. The spans/patches bridge
copies its structured results through a private EDN encoding. No Linux/Windows
verification, deployment or publication was performed.

## In-place delta application — 2026-10-19

`apply-changes!` now calls upstream `AMapplyChanges` after complete batch decoding
and checksum validation. The clone wrapper and its generated downcall were
removed. Decode failures still precede mutation; native application failures
require the caller to discard and reload the working replica.

- `bb test` on Corretto 27: 30 tests, 236 assertions, zero failures/errors,
  including the isolated GC subprocess.
- The rebuilt packaged JAR passed the same suite on Corretto 25.0.2, with library
  source, resources and compiled-class directories excluded from the classpath.
- A replay test uses unchanged encoded deltas from two ordinary replicas. It
  abandons a working document after application and before persistence, reloads
  the prior persisted state, and replays the batch. Values and history converge;
  duplicate delivery does not repeat counter increments.
- JS 3.5.0 document, rich-text and peer-sync interoperability, reflection checks,
  clj-kondo, cljstyle, Rust formatting, native compilation and ABI checks passed.
  The generated binding surface now contains 183 downcalls.

This removes the bridge's whole-document copy on every import. End-to-end
load/apply/save latency has not been benchmarked. Native platform verification
remains macOS ARM64.

## Collaboration spike and local timing probes — 2026-10-19

`bb test` on Corretto 27 passed 31 tests and 254 assertions. The additional
collaboration scenario has two human replicas, one simulated model participant,
two server replicas and persisted state. Participants edit their own versions;
the servers exchange updates only through the persisted document. Text, marks,
counters and history converge. A replacement server reloads committed state,
reconnects with fresh sync state and persists further edits without counting
duplicate deliveries twice. A concurrent prefix/mark boundary result was also
reproduced using the official Automerge JS 3.5.0 package.

The local timing probe is `script/bench.clj`, available as `bb bench`. Runs used
Corretto 25.0.2 on macOS ARM64, `-Xms128m -Xmx512m`, the existing release native
build, 10 warmups and 30 measured samples per fresh JVM. Scenarios ran sequentially.
Each sample loads the same saved baseline, applies one mechanically extracted
batch, and saves the resulting document to bytes. History heads are checked after
timing. Incoming changes come from three peers in `hello` and four in other cases.

All times are milliseconds. Quantiles use nearest ranks; these short synthetic
runs are observations, not latency guarantees.

| Scenario | Text characters | Prior edits | Incoming changes | Load p50 | Apply p50 | Save p50 | Cycle p50 | Cycle p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hello | 11 | 0 | 6 | 0.053 | 0.162 | 0.027 | 0.271 | 0.878 |
| note | 20,000 | 1,000 | 20 | 5.968 | 3.356 | 0.352 | 9.747 | 11.463 |
| history | 20,000 | 10,000 | 20 | 17.392 | 4.311 | 0.519 | 22.269 | 23.717 |
| large | 100,000 | 10,000 | 50 | 36.486 | 16.092 | 2.243 | 54.572 | 56.943 |

The text is generated from a seeded word vocabulary with a block marker every
1,000 characters (one marker in `hello`). Prior edits repeatedly replace the last
character; incoming changes append ASCII and emoji. Metadata and this history
compress well, so saved sizes are small and do not represent typical user files.
Explicit GC is requested before warmup and before measurement; no GC is forced
inside samples. No additional JVM collections were reported during the measured
samples. Raw local outputs are under ignored `target/bench/`.

These timings exclude PState access, disk writes, replication, network transport,
commit coordination, editor rendering and model inference. They also exclude
sustained native-memory churn and contention between active documents. Since
these are per-document costs, several affected documents on one topology task
consume the same batch's time budget. Loading retained history dominates these
examples; visible text length alone is insufficient for capacity planning.

The primitive API and observed small-document cost support starting a local
collaboration spike. Actual Tropical/Rama lifecycle integration, Linux native
verification, and sustained memory/capacity testing remain subsequent checks.

### Native-call attribution

A follow-up diagnostic instrumented the existing wrapper boundaries in the
`large` scenario, using the same JDK/heap settings, 10 warmups and 30 samples.
It timed the intervals around `AMload`, `AMapplyChanges` and `AMsave` without
changing their implementation. This is coarse call-boundary timing, not a native
CPU profile; the intervals include small dispatch and instrumentation costs.

| Stage | Native call p50 (ms) | Other stage work p50 (ms) |
| --- | ---: | ---: |
| Load | 35.753 | 0.068 |
| Apply | 16.100 | 0.277 |
| Save | 2.224 | 0.052 |

Other apply work includes native change decoding, checksum checking, temporary
result assembly/release and JVM/native copying, so it is not purely Clojure
overhead. In this case the three bulk native calls account for almost all elapsed
time. Avoiding repeated reconstruction through an appropriately invalidated
working-document cache could therefore matter much more than reducing wrapper
allocations. Such a cache would need to follow committed state and be discarded
on retry/recovery. No cached topology implementation was measured here.

The diagnostic and raw output remain in ignored `target/bench/boundary.clj` and
`target/bench/boundary-large.edn`.

## SavedDocument conversions — 2026-10-21

`save` now returns an immutable Java `SavedDocument`; `bytes` exports either a
live or saved document, and `load` accepts saved documents or raw bytes. External
wrapping checks only the binary framing. The native-save path transfers its
fresh JVM byte array into the value, and loading copies directly from the value
to the native arena. No native ABI changes were needed.

- The complete suite passed on Corretto 27: 41 tests, 363 assertions, zero
  failures/errors. The rebuilt packaged JAR passed the same suite on Corretto
  25.0.2, with library source/resources/classes excluded from the classpath.
- Conversion tests cover empty and rich-text documents, fresh replica actor IDs,
  byte ownership, encoded equality/hash behavior, concurrent capture, large
  payloads, appended snapshots/changes, queued dependencies and save cursors.
- Framing checks reject PNG input, standalone changes/sync messages, truncation,
  trailing garbage, unknown chunk types and invalid/overflowing lengths. A
  checksum-only corruption passes framing and fails native loading, establishing
  the intended validation boundary.
- An isolated JVM without native access enabled and with an unavailable native
  library successfully imports and exports a saved value. GC checks confirm a
  retained saved value does not retain its original document or native results;
  save/load also runs during concurrent collection.
- JVM ↔ official Automerge JS 3.5.0 document, rich-text and sync interoperability
  passed, including checked wrapping of saves received from JavaScript.
- Java compilation, reflection lint, clj-kondo, cljstyle, version consistency,
  native build and ABI checks passed. Platform verification remains macOS ARM64.

The benchmark uses saved values and reads their encoded size without exporting
an extra byte array. A `hello` smoke run (one warmup, three samples) completed;
this checks the updated script, not performance equivalence. Historical timings
above predate this API change; they are not measurements of the new wrapper.

## First release preparation — 2026-10-30

Prepared version `0.1.0` on macOS ARM64 with the existing Automerge pin and
Rust 1.90.0 toolchain. This release claims macOS ARM64 support only.

- `bb test` passed on Corretto 27: 41 tests, 363 assertions, zero failures/errors.
- `bb build` produced `target/rattrapante-0.1.0.jar`. Its POM contains version
  `0.1.0` and SCM tag `v0.1.0`; Java support targets JDK 25. The native library,
  Clojure source, Java classes, native pin, both license notices and clj-kondo
  exports are present.
- The packaged JAR passed all 41 tests and 363 assertions on Corretto 25.0.2,
  including the native-GC subprocess. Library source, resources and compiled-class
  directories were excluded from the classpath. Resource URLs confirmed JAR loading.
- The usage skill's Clojure examples ran against that JAR on Corretto 25.0.2,
  with checks for text/counter results, delta delivery, history convergence and
  saved-document round-tripping.
- Official Automerge JS 3.5.0 document, rich-text and sync interoperability passed.
  The `bb interop` npm refresh was blocked by sandbox DNS restrictions; the
  installed package's version was verified as `3.5.0`, then the same check ran
  directly with `clojure -J--enable-native-access=ALL-UNNAMED -M script/interop.clj`.
- Formatting, clj-kondo and reflection lint passed. `bb version:check` passed;
  temporary fixtures confirmed rejection of stale README, changelog, plugin and
  skill versions, including a stale `-SNAPSHOT` suffix.
- The skill validator and Claude Code marketplace/plugin validators passed.
  The latter reported only an optional directory-listing icon warning.

Babashka emitted sandbox process-enumeration errors during shutdown of some
successful commands; native build/ABI checks and the JVM checks completed.
No new benchmarks, other-platform verification, tagging or publication were run.
