# Changelog

## 0.1.1 — 2026-10-30

- The release JAR carries Linux x86-64 and ARM64 native libraries beside macOS
  ARM64. 0.1.0 shipped only the macOS library, so loading it on Linux failed.
  The Linux libraries are built from the same pinned source in
  `rust:1.90.0-slim-bookworm` (pinned by digest) and need glibc 2.34 or newer.
- `bb native:linux` builds both Linux libraries in Docker; `bb build` builds
  every platform's library and refuses to produce a JAR missing one.

## 0.1.0 — 2026-10-30

First release. Requires JDK 25+ and Clojure 1.12+. The release JAR supports
macOS ARM64; other native targets are not yet verified.

- Explicit mutable Automerge replicas with GC-managed native ownership,
  synchronous changes with rollback, and document-owned object handles.
- Maps, lists, collaborative text, counters and scalar codecs, with explicit
  materialization into persistent Clojure data.
- Fork/merge, conflicts, history heads, change metadata, author attribution,
  missing-dependency inspection and native history diffs.
- Rich-text blocks, spans, marks, stable cursors and UTF-16 text positions with
  Unicode boundary validation.
- Immutable `SavedDocument` values with encoded-byte equality, defensive byte
  exports, full persistence and incremental save/load streams.
- Strict encoded-change batch ingestion with duplicate and out-of-order delivery
  support, plus repeatable delta capture using caller-owned acknowledgement
  checkpoints. Failed native applications require reloading the working replica.
- Peer synchronization with persistent per-document/per-peer state.
- Pinned Automerge JS 3.5.0 / Rust core 0.12.0 source, a reproducible Rust C-API
  extension, generated typed Java FFM bindings and native ABI checks.
- Correctness, rollback, concurrency, Unicode, GC reachability, collaboration and
  JavaScript file/rich-text/sync interoperability checks; local timing probes.
- Build/install/deploy tasks, exported clj-kondo hooks and a versioned usage skill
  for coding agents, also packaged as a Claude Code plugin.
