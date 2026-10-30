# Rattrapante

An explicit Clojure interface to Automerge. JVM first, JDK 25+.

Documents are mutable Automerge replicas. Changes, object identities, text edits,
counter increments, forks, conflicts and peer sync remain visible in the API.
Native resources follow JVM reachability; callers do not allocate or free them.
An explicit `materialize` operation copies a subtree into persistent Clojure data.

Dependency (JDK 25+, Clojure 1.12+, macOS ARM64):

```clojure
co.multiply/rattrapante {:mvn/version "0.1.1"}
```

## Build and use locally

Prerequisites: JDK 25+, Clojure CLI, Babashka, Rustup, a C compiler (`cc`), curl
and tar. `bb bindings` regenerates the checked-in Java bindings using Babashka;
Node/npm are needed only for the optional JavaScript interoperability check.

```sh
rustup toolchain install 1.90.0 --profile minimal
bb native
bb java
bb test
bb build
```

`JAVA_HOME` must select JDK 25 or newer. Downstream JVMs must enable native access:

```sh
clojure -J--enable-native-access=ALL-UNNAMED -M
```

The `:dev` and `:test` aliases already set this flag. There are no preview flags.
For a consuming project, use `{:local/root "/path/to/rattrapante"}` after the
native build, or run `bb install` to install the library into local Maven.

The native source is **Automerge JS 3.5.0 / Rust core 0.12.0**, commit
`4d2a8f6bfecfb7f1e4fca126e6d2122ac526f903`. See [native.edn](native.edn).
`bb native` verifies the source archive checksum, uses our pinned Cargo lockfile,
builds the official C API as a shared library and checks its ABI layouts.
The build adds the checked-in `native/bridge.rs` module for Rust capabilities
missing from that C API. Its entry points share upstream's result ownership and
are included in the generated exact Java bindings.
Source and compiler outputs stay under `target/`; generated native resources
are ignored by Git. Meld is not a dependency.

The release JAR supports **macOS ARM64** and **Linux x86-64/ARM64** (glibc 2.34
or newer), and extracts its native binary when loaded. `bb native` builds this
machine's binary; `bb native:linux` builds both Linux binaries in Docker, in
pinned `rust:1.90.0-slim-bookworm` images, with Cargo's registry and target
directories kept in Docker volumes. `bb build` builds all three and refuses to
produce a JAR missing any of them, so releases are built on an Apple silicon Mac
with Docker. The build recipe also recognizes Windows x86-64/ARM64, untested.

## Coding-agent skill

The repository includes a [Rattrapante skill](plugins/rattrapante/skills/rattrapante/SKILL.md)
for agents writing, reviewing or testing code that uses the library. It covers
transaction scope, document-owned handles, collaborative text, persistence,
acknowledged deltas and peer sync.

For Codex, copy `plugins/rattrapante/skills/rattrapante` into your consuming
project's `.agents/skills/` directory; see the [skill documentation](https://developers.openai.com/codex/skills/).
The same `SKILL.md` can be used by other agents that support Agent Skills.

For Claude Code, the repository also provides a
[plugin marketplace](https://code.claude.com/docs/en/plugin-marketplaces):

```text
/plugin marketplace add multiplyco/rattrapante
/plugin install rattrapante@rattrapante
```

## Documents and changes

```clojure
(require '[co.multiply.rattrapante :as r])

(def doc (r/create))

(r/with-change [tx doc {:message "Create a note"}]
  (let [note (r/put-object! tx r/root "note" :map)
        body (r/put-object! tx note "body" :text)]
    (r/put! tx note "title" "First note")
    (r/put! tx note "views" (r/counter 0))
    (r/splice-text! tx body 0 0 "Hello 😀")))

(def note (r/get doc r/root "note"))
(def body (r/get doc note "body"))

(r/with-change [tx doc]
  (r/increment! tx note "views" 1)
  (r/splice-text! tx body 5 0 ", world"))

(r/text doc body) ;=> "Hello, world 😀"
(r/materialize doc)
;=> {"note" {"title" "First note"
;            "views" #co.multiply.rattrapante.Counter{:value 1}
;            "body" "Hello, world 😀"}}
```

`change!` takes a document, optional options, and a callback. `with-change` is its
macro form. A successful callback commits one change and returns the callback's
result. An escaping exception rolls back its pending operations. A no-op callback
does not force an empty history entry. Options are `:message` and `:time` (epoch
seconds; timestamps stored as values instead use epoch milliseconds via `Instant`).

The change handle works only synchronously on the invoking thread. Escaped change
handles are rejected. Object handles obtained during a failed change are also
invalidated, so a rolled-back object ID cannot accidentally address a later object.
An exception caught *inside* the callback does not roll back earlier successful
operations: the callback can continue and commit them.

Access is serialized per document, including reads; different documents can be
used concurrently. Changes cannot nest or access another document. Use `fork`
outside the callback for independent work, then `merge!`. Two-document merges
acquire locks in a fixed order. Calling a future and waiting for it to access the
same locked document is not a supported way to parallelize a change.

## Values and operations

Map keys are strings. There is no implicit keyword conversion. Supported scalar
values are nil, booleans, signed 64-bit integers, doubles, strings, byte arrays,
`java.time.Instant`, `Counter` and `UInt`. Unsupported or out-of-range values fail
instead of silently changing representation. A JVM string becomes an immutable
Automerge string scalar; a collaborative string is a distinct `:text` object.
In the JS API these scalar strings are `ImmutableString` values.

| Operation | Meaning |
| --- | --- |
| `put! tx object key value` | Set a scalar property or replace a list element |
| `put-object! tx object key kind` | Create/replace with a new `:map`, `:list` or `:text` |
| `insert! tx list index value` | Insert a scalar; use the list size to append |
| `insert-object! tx list index kind` | Insert a new nested object |
| `delete! tx object key` | Delete a map property or list element |
| `increment! tx object key delta` | Increment a counter without overwriting concurrent increments |
| `splice-text! tx text index delete-count string` | Edit collaborative text |
| `mark!`, `unmark!`, `marks` | Write and read named scalar text annotations |
| `cursor`, `cursor-position` | Create and resolve stable sequence positions |
| `spans`, `block-at` | Read rich text and address its block maps |
| `split-block!`, `join-block!`, `replace-block!` | Insert, remove or replace block markers |
| `marks-at` | Read the active annotations at a text position |
| `update-text!` | Explicitly diff and update text without block markers |

Text positions and lengths are **UTF-16 code units**, matching JVM and JS string
indices. `😀` occupies two units. Splice endpoints and mark boundaries must fall
on Unicode scalar boundaries; strings containing unpaired surrogates are rejected
at encoding. Invalid boundaries fail before mutation. Cursor positions can be
an existing element index, `:start`, or `:end`, including in empty sequences.
`(cursor doc text index {:move :before})` controls where a cursor moves when its
element is deleted; the default is `:after`. Mark ranges may be empty.

`get` reads a property or list element; nested objects remain handles. The optional
not-found value distinguishes absent map properties from stored nil. Invalid list
positions raise native errors. `get-in` is a path convenience: each step is a native
lookup. Retain an object handle when repeatedly operating on the same object.
Handles belong to one document and are rejected when used with another replica;
resolve the corresponding path again after a fork. `object-id` exposes the stable
Automerge identifier; `resolve-object` resolves that ID in another replica or
after a list element has moved. It also addresses objects no longer reachable
from root. Unknown IDs fail. Handle equality itself is JVM identity.

`entries` performs one native range query and eagerly returns map key/value or
list index/value pairs. `keys`, `size` and `text` expose narrower reads.
`materialize` recursively copies the visible tree and intentionally omits history,
object identity, annotations and hidden conflict values. It is an explicit read
boundary, not a data structure you can write back while preserving those features.

## Rich text and AI editing

Block markers are ordinary Automerge maps embedded in collaborative text. A
marker occupies one UTF-16 unit. Read and edit its properties using the same
map/list/text operations as any other object:

```clojure
(r/with-change [tx doc]
  (let [paragraph (r/split-block! tx body 0)]
    (r/put! tx paragraph "type" "paragraph")
    (r/put-object! tx paragraph "parents" :list)))

(r/spans doc body)
;=> [{:type :block :index 0 :object-id "..."}
;    {:type :text :index 1 :text "Hello, world 😀" :marks {}}]
```

`spans` copies text, mark values and block IDs. `block-at` returns a handle to the
marker at an exact index; `resolve-object` accepts its serialized ID. Editing that
map preserves its identity; `replace-block!` creates a new map. `join-block!`
removes only the marker. Block schemas and the meaning of marks belong to the
editor. Strings inside JavaScript-created block attributes can themselves be
collaborative text objects; use explicit reads or `materialize` as appropriate.

For a model working from an earlier document version, retain its heads and edit
a `(fork doc heads)` throughout that work. Resolve object IDs in the fork and
submit its changes through the same path as other replicas. Never hold a
`with-change` callback open during model inference. `update-text!` invokes
Automerge's native text diff when an operation-level edit is unavailable; its
cost and merge behavior depend on that diff. It rejects text with block markers
to avoid flattening rich-text structure.

## Fork, merge and conflicts

```clojure
(def remote (r/fork doc))
(r/with-change [tx remote]
  (r/put! tx (r/get tx r/root "note") "title" "Remote title"))
(r/with-change [tx doc]
  (r/put! tx note "title" "Local title"))
(r/merge! doc remote)
(r/conflicts doc note "title")
;=> [{:id "..." :value "Remote title"}
;    {:id "..." :value "Local title"}] ; order is not promised
```

`get` returns Automerge's deterministic winning value. `get-all` returns all values
with operation IDs; `conflicts` returns them only when there is more than one.
Writing that property in a new change resolves the observed conflict.

`heads` returns history hashes. `(fork doc heads)` makes a historical fork with a
new actor ID; `[]` selects the empty history. `changes-since` returns encoded
changes, and `apply-changes!` imports them. These are explicit synchronization
primitives for applications that do not want the peer sync protocol.

`apply-changes!` validates the complete encoded batch and then applies it directly
to the document, without cloning its state. Decode errors leave the document
unchanged. A native application error may alter internal state: discard that
working replica and reload it before retrying. Duplicate delivery is harmless;
changes with missing dependencies remain queued. `missing-deps` reports the
missing hashes, and full saves preserve queued changes across reloads.

For a persisted-document pipeline, load one working replica per affected document,
apply its batch, and persist the result after success. If an attempt fails before
persistence, rebuild from the previous persisted state and replay the same encoded
changes. Normal imports preserve the changes' original identities; they do not
re-execute editing callbacks or create replacement history.
`change-info` reads copied metadata from encoded bytes, including hash, actor,
author, sequence, dependencies, message and time; `change-by-hash` retrieves encoded
history or returns nil for an unknown hash.

`set-author!` sets opaque author bytes for subsequent local changes; `author`
returns a copy or nil. This supports human and model attribution independently
of actor IDs. Automerge rotates actors as necessary when authors change.

`diff` returns copied native patches between explicit sets of heads. Its
two-argument form compares to the current heads under the document lock:
`(r/diff doc previous-heads)`. Patches have `:object-id`, a `:path` of
`[parent-object-id property]` pairs, and an `:action`. Actions include `:put-map`,
`:put-seq`, `:insert`, `:splice-text`, `:increment`, `:conflict`, `:delete-map`,
`:delete-seq` and `:mark`. Object values carry `:object-id` and `:object-type`;
scalar values retain their Clojure types. Patches describe visible state, while
encoded changes replicate history. Diffing does not advance an implicit cursor.

## Persistence and sync

`save` returns an immutable `co.multiply.rattrapante.SavedDocument`, suitable as
the at-rest type in a persistence schema. It contains the complete Automerge
save, including history and queued changes with missing dependencies. The live
`co.multiply.rattrapante.Document` remains a separate, mutable native handle.

| Conversion | API |
| --- | --- |
| bytes → `SavedDocument` | `(r/saved-document data)` |
| bytes → `Document` | `(r/load data)` |
| `SavedDocument` → bytes | `(r/bytes saved)` |
| `Document` → bytes | `(r/bytes doc)` |
| `Document` → `SavedDocument` | `(r/save doc)` |
| `SavedDocument` → `Document` | `(r/load saved)` |

`saved-document?` identifies the at-rest type. It owns its bytes: importing and
exporting arrays makes defensive copies, and later edits to the live document do
not change an earlier save. Equality and hashing compare encoded bytes, not
semantic document equivalence. `(SavedDocument/.byteSize saved)` reports the
encoded size without copying; import `co.multiply.rattrapante.SavedDocument` to
use that Java method. Persistence adapters can encode with `r/bytes` and decode
with `r/saved-document`, without creating a native document.

The `saved-document` constructor checks the format signature, an initial document
snapshot chunk, known chunk types and complete chunk lengths/boundaries. It accepts
snapshots with appended changes and rejects PNGs, individual changes, sync messages,
truncated chunks and trailing garbage. It does not checksum, decompress or validate
the encoded contents. Malformed framing throws `ExceptionInfo` with
`:type :rattrapante/invalid-saved-document`; `load` performs Automerge's content
validation. Raw `load` retains upstream's accepted inputs, including change streams
and an empty byte array; those are not full snapshots accepted by `saved-document`.

`load` creates an independent document with a fresh actor ID from either form.
`create` and `fork` also use fresh actor IDs. Both `save` and `bytes` on a live
document advance the native incremental-save cursor; `bytes` on a saved value
only copies its encoding. `save-incremental!` returns subsequent changes as bytes;
append those to `(r/bytes saved)` for persistence. `load-incremental!` applies
either bytes or a `SavedDocument` to an existing document. Maintain one incremental
persistence writer per document; advancing the cursor is independent of whether
your storage write succeeds.

The bytes remain standard Automerge encoding, including its existing column
compression. No additional compression envelope or serialization dependency is
introduced. Use `(r/bytes doc)` when a byte array is needed directly.

For an acknowledged delivery workflow, use `capture-delta` instead:

```clojure
(def acknowledged-heads (atom []))
(def batch (r/capture-delta doc @acknowledged-heads))
;=> {:from [...] :heads [...] :changes [byte-array ...]}

;; Submit (:changes batch), retaining batch for retries.
;; Only after successful delivery acknowledgement:
(reset! acknowledged-heads (:heads batch))
```

The capture holds one document lock across the bytes and their corresponding
heads. It does not advance a persistence cursor. A later edit is included in the
next capture, even when it happened while this batch was being delivered. Use
one ordered checkpoint owner per delivery destination; the bridge does not
interpret network or storage acknowledgements.

A checkpoint must refer to known history. Capture also rejects documents with
missing dependencies, with `:missing-deps` in the exception data, because history
queries would omit their queued changes. Retain incoming batches or a full save
until dependencies arrive. `load-incremental!` follows upstream's permissive
partial-load behavior and is intended for incremental save streams; use
`apply-changes!` for strict ingestion of individual encoded changes.

An application's resource manager can own loading, periodic submissions and a
final flush on teardown. It must retain an unacknowledged batch through retries.
The same mechanism works for browser and background model participants. Transport,
resource sharing and topology integration remain application responsibilities.

```clojure
(def alice (r/sync-state doc))
(def bob   (r/sync-state remote))

(when-let [message (r/generate-sync-message! alice)]
  (r/receive-sync-message! bob message))
```

Continue exchanging in both directions until neither generates a message. Keep
one state per document/peer connection and deliver messages reliably in order.
State is bound to its document and GC-managed. `encode-sync-state` and the second
arity of `sync-state` allow persistence. Networking, reconnection policy and
durable storage belong to the application.

## Native ownership

Long-lived documents, object references and sync states use automatic FFM arenas
with native cleanup actions. Borrowed pointers share their owner's lifetime.
Temporary results use confined arenas and are released on both success and failure.
No finalizer commits a change. Reclamation is eventual, not deterministic, and the
JVM does not account for all of Rust's memory as Java heap pressure. Applications
that retain many large live documents must still manage their working set.

The native boundary uses generated, exact Java method-handle calls. The library
does not implement Clojure persistent collection protocols, fork on ordinary
reads/writes, or convert full documents implicitly. See [design notes](doc/design.md)
for costs and remaining scope.

`bb bench hello` runs a local synthetic load/apply/save probe. Other scenarios are
`note`, `history` and `large`; optional trailing arguments set warmups and samples
(defaults: 10 and 30). Each invocation runs in a fresh JVM with a 128 MiB initial
heap and 512 MiB maximum heap. It reports nearest-rank p50/p95 and maximum times
for each stage and the combined cycle, verifies resulting history, and records
GC counts. Text, blocks and incoming Unicode edits are generated mechanically;
the history scenarios repeatedly replace one character. Explicit GC is requested
before warmup and before measurement, never inside a measured sample.

These probes exclude disk, networking, Rama and sustained native-memory pressure.
They are illustrative measurements, not a production capacity guarantee. The
local observations and exact settings are recorded in [verification](doc/verification.md).

## Development and release

Clojure and Java sources share the `src/` tree. The Java FFM support lives in
`src/co/multiply/rattrapante/internal/`; compiled classes go into `target/classes/`.

`bb test` checks scalar codecs, object ownership, rollback, concurrency, merges,
conflicts, rich text, marks, cursors, history, strict delta ingestion, checkpoint
capture, authorship, persistence and peer synchronization.
Its GC check runs in a fresh JVM and exercises native reads while GC runs.
`bb interop` additionally downloads the official JS 3.5.0 package and tests
bidirectional file, rich-text and sync interoperability. `bb format:check` checks formatting.
`bb lint` runs clj-kondo; a `with-change` analysis hook is included in the JAR for
consumers' editor tooling.
`bb lint:reflection` compiles Java support and loads all library namespaces in a
fresh JVM with reflection warnings enabled. Warnings are written to stderr;
compilation errors fail the command.

Keep the version in `build.clj`, this README, `CHANGELOG.md`, the plugin manifest
and the usage skill aligned. Before release, run:

```sh
bb version:check
bb format:check
bb lint
bb lint:reflection
bb test
bb interop
bb build
```

Verify the resulting JAR on every claimed native target. `bb install` installs
locally. After committing the release changes, Henrik runs `bb deploy`: it checks
version consistency, creates and pushes `v<version>` to
`git@github.com:multiplyco/rattrapante.git`, then builds and uploads to Clojars
using `CLOJARS_DEPLOY_MAVEN_USERNAME` and `CLOJARS_DEPLOY_MAVEN_PASSWORD`.
`bb tag` remains available for tagging separately; it is not a prerequisite for
`bb deploy`. Preparation and verification do not publish anything.

EPL-2.0 for Rattrapante. Automerge is MIT-licensed; its notice is included in the JAR.
