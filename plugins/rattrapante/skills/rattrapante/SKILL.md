---
name: rattrapante
description: >-
  Use when writing, reviewing or testing Clojure code that uses Rattrapante
  (co.multiply.rattrapante), the JVM interface to Automerge. Covers document
  edits, collaborative text, persistence and synchronization. Not for the
  JavaScript Automerge API alone.
---

# Rattrapante

**Repository:** https://github.com/multiplyco/rattrapante —
`co.multiply/rattrapante {:mvn/version "0.1.0"}`.
Requires JDK 25+, Clojure 1.12+ and `--enable-native-access=ALL-UNNAMED` on the JVM.
The release JAR supports macOS ARM64. Linux and Windows build recipes exist but
are not verified release targets. Native resources are GC-managed.

Use `[co.multiply.rattrapante :as r]`. Read public docstrings for exact arities and
options, for example `(clojure.repl/doc co.multiply.rattrapante/capture-delta)`.
The [README](https://github.com/multiplyco/rattrapante/blob/main/README.md) covers
the full API model; [design notes](https://github.com/multiplyco/rattrapante/blob/main/doc/design.md)
explain native ownership and costs. Check the consuming project's dependency
version before relying on an API from this skill.

## Documents, changes and handles

- A `Document` is a mutable replica. It is not a persistent map, a derefable value
  or an `assoc` target. Reads of nested objects return `ObjectRef` handles.
  `materialize` explicitly copies visible data; it loses object identity, history,
  marks and hidden conflict values. Do not materialize and rebuild to edit a subtree.
- Perform edits synchronously in `with-change` or `change!`. A successful callback
  commits once and returns its result. An exception escaping the callback rolls
  back pending operations. Catching an exception inside it allows earlier
  successful operations to commit. A no-op need not produce a history entry.
- A change handle is valid only inside that callback on its invoking thread.
  Never retain it, use it in a future, nest changes, or access another document
  during the callback. Reads and writes serialize on the document lock. Do not
  hold that lock through network calls or model inference, or wait for work that
  needs the same document.
- Object handles belong to one document. Handles obtained during a failed change
  are invalidated. After `fork` or `load`, read the path again or use
  `(r/resolve-object replica (r/object-id original-handle))`; never pass the original
  handle into the other replica. Resolve by ID for objects that may move in lists.
- Documents, object handles and sync states retain their native owners. There is
  no user-facing close/free operation. Reclamation is eventual; keep the live
  document working set bounded when native memory matters.

```clojure
(require '[co.multiply.rattrapante :as r])

(def doc (r/create))
(def body
  (r/with-change [tx doc {:message "Create note"}]
    (r/put! tx r/root "views" (r/counter 0))
    (let [body (r/put-object! tx r/root "body" :text)]
      (r/splice-text! tx body 0 0 "Hello 😀")
      body)))

(r/with-change [tx doc]
  (r/increment! tx r/root "views" 1)
  (r/splice-text! tx body 5 0 ", world"))

(r/text doc body) ;=> "Hello, world 😀"
```

## Values and text

- Map keys are strings; keywords are not coerced. Scalars include nil, booleans,
  signed 64-bit integers, doubles, strings, byte arrays, `java.time.Instant`,
  `Counter` and `UInt`. Build nested maps/lists/text explicitly with object
  operations; `put!` does not recursively import Clojure collections.
- A string scalar is immutable text (`ImmutableString` in Automerge JS).
  Collaborative text is a `:text` object edited with `splice-text!`. Increment
  counters with `increment!`, preserving concurrent increments.
- Sequence insertion and replacement differ: use `insert!`/`insert-object!` to
  insert, and `put!`/`put-object!` to replace. The list size is its append index.
- Text offsets and lengths are UTF-16 code units, matching JVM and JS strings.
  An emoji such as `😀` takes two. Do not split surrogate pairs; invalid boundaries
  and unpaired surrogates are rejected. Change option `:time` uses epoch seconds;
  `Instant` scalar values use epoch milliseconds.
- Use stable `cursor` values and `cursor-position` across edits. Cursors accept
  `:start`, `:end` and element indices; `{:move :before}` changes deletion affinity
  from the default `:after`. Marks annotate text; they are not a formatting schema.
- Rich-text block markers are maps embedded in a text sequence. Use `spans`,
  `block-at`, `split-block!`, `join-block!` and `replace-block!`. Updating a block's
  map preserves its ID; replacing creates a new one; joining removes the marker.
  `update-text!` uses Automerge's native diff and rejects text with blocks.

## Forks, history and conflicts

- `fork`, `load` and `create` use fresh actor IDs. `(r/fork doc heads)` selects a
  historical version; `[]` selects empty history. For a model editing an earlier
  view, retain its heads, fork there, resolve IDs in the fork and submit that
  fork's changes when ready.
- `merge!` imports another replica. `get` returns the deterministic winning value;
  `get-all` returns all contenders with operation IDs; `conflicts` returns them
  only when there is more than one. A new write resolves observed conflicts.
- `diff` returns visible-state patches between heads; it does not replicate
  history or advance a cursor. Use encoded changes or the peer protocol to sync.
- `set-author!` sets opaque author bytes for subsequent local changes, independently
  of actor IDs; Automerge handles actor rotation. Do not use actor identity as
  application-level human/model attribution.

## Persistence

| Need | API and constraint |
| --- | --- |
| Immutable at-rest value | `(r/save doc)` returns `SavedDocument`, including history and queued changes |
| Bytes for storage or transport | `(r/bytes saved)` or `(r/bytes doc)`; exports are defensive copies |
| Import an at-rest value | `(r/saved-document data)` checks framing, not checksums or content validity |
| Independent live replica | `(r/load saved-or-bytes)` performs native loading and content validation |
| Incremental save stream | `save-incremental!` produces bytes to append to a full save; `load-incremental!` ingests that stream |

`SavedDocument` equality and hashing compare encoded bytes, not semantic document
equivalence. It is distinct from a mutable `Document` and is not a map of contents.
`saved-document` requires full-snapshot framing; individual encoded changes and
sync messages are not saved documents.

Both `save` and `bytes` on a live document advance the incremental-save cursor.
`bytes` on a saved value only copies its encoding. Keep one incremental writer
per document, and retain emitted bytes through storage failures: advancing that
cursor does not mean storage acknowledged the write. Prefer `capture-delta` when
the application needs explicit acknowledgement checkpoints.

## Encoded changes and acknowledged delivery

`apply-changes!` strictly decodes the whole batch before applying it in place.
Decode errors leave the document unchanged. A native application error may change
internal state: discard that working replica, reload the last persisted save and
replay the same encoded changes. Do not retry on a possibly damaged replica or
re-execute editing callbacks to recreate the changes.

Duplicates are harmless. Out-of-order changes can remain queued for dependencies;
`missing-deps` reports missing hashes and a full save preserves queued changes.
`load-incremental!` is permissive stream ingestion, not a substitute for strict
individual-change validation.

```clojure
(def acknowledged-heads (atom []))
(def batch (r/capture-delta doc @acknowledged-heads))
;; Send (:changes batch). Retain this exact batch for retries.
;; Only after the destination acknowledges successful delivery:
(reset! acknowledged-heads (:heads batch))
```

Capture returns `{:from ... :heads ... :changes ...}` under one document lock
without advancing a cursor. Keep one ordered checkpoint owner per destination;
never advance it merely because a send was attempted. Later local edits belong
in the next capture. Unknown heads and missing dependencies cause capture to
fail; retain incoming batches or a full save until dependencies arrive.

## Peer sync

Use one `sync-state` per document/peer connection. Exchange
`generate-sync-message!` and `receive-sync-message!` in both directions until
neither side generates a message; a single request/response is not convergence.
Deliver messages reliably in order. States are document-bound; do not reuse one
for a different peer or replica. `encode-sync-state` and the second arity of
`sync-state` support state persistence. Transport, reconnection policy and durable
storage remain application responsibilities.

For integration tests, use independent replicas and round-trip through real
saves or encoded changes. Exercise the workflow's failure boundary: rollback
for editing, duplicate/reordered changes and acknowledgement retries for delivery,
or repeated bidirectional exchanges for peer sync. Compare history heads as well
as visible values when checking convergence.
