# Design decisions

## Contract

Rattrapante targets JVM Clojure on JDK 25+. ClojureScript is deferred. The public
contract follows Automerge objects, changes and history. `Document` and
`ObjectRef` have identity, not persistent Clojure collection semantics. Mutation
is explicit. Forking is explicit. Converting to a persistent map/vector is explicit.

## Native boundary

Use upstream's C API over the Rust core. The selected published release is JS
3.5.0, whose source contains Rust 0.12.0 and C API 0.2.2. `native.edn` pins the
archive hash, exact commit, toolchain and text-indexing feature. The first source
build required dependency resolution; the resulting lockfile lives
in `native/Cargo.lock` and subsequent builds use `--locked`.

Java FFM is stable in this JDK range. Java stubs invoke typed `MethodHandle`s
with `invokeExact`, avoiding a generic boxed argument-array dispatch layer.
The generator reads upstream's exported Rust C-ABI declarations and rejects
unknown types. Struct layouts and enum representations are checked against the
header produced by that source build. The native library is loaded lazily when
bindings are first used, and a JAR resource is extracted to a platform-specific
temporary file. The override property `rattrapante.native.path` is for development
against an ABI-compatible library with the same UTF-16 indexing feature and
Rattrapante extension exports.

`native/bridge.rs` is copied into the pinned C crate as an explicit extension
module during the build. It exposes rich-text blocks/spans, cursor endpoints and
affinity, stable object resolution and patches through
the same `AMresult` ownership model. No Rust layout crosses the boundary. The
generator discovers these exported declarations alongside upstream's.

Spans and patches are explicit copied reads. The extension encodes these variable
structures as private EDN in an owned string result, parsed with a fixed set of
readers in Clojure. Strings are escaped; integers, floats, bytes, counters and
timestamps preserve their types. This representation is an internal wire format,
not a persistence format. Unknown scalar values in patches use a read-only
`UnknownScalar` record. Ordinary edits retain their typed downcalls.

## Ownership

An Automerge C `AMresult` owns its items, including document and object pointers.
Every result is registered once for release. Long-lived results use
`MemorySegment.reinterpret` with an automatic arena and a cleanup action invoking
`AMresultFree`. The cleanup action is static and cannot retain its own arena.
The exposed internal pointer is rebound to the same arena. FFM keeps its scope
alive for native calls. Object handles additionally retain their document.

Read results are normally temporary, with deterministic confined-arena cleanup.
When returning a nested object, `AMitemResult` retains that particular item and
its result gets an automatic arena. Upstream uses non-atomic reference counts for
items. All newly retained items are therefore pinned until their original result
has been freed, even when decoding fails. A `reachabilityFence` after closing the
temporary arena prevents cleanup from racing that release. Object-ID access is
serialized with the document because upstream may lazily cache actor metadata.

There is no user-facing close requirement. Automatic cleanup is eventual and
does not establish a bound on native memory pressure. This first implementation
does not expose eager disposal. The diagnostic property
`rattrapante.track-native=true` enables native-result accounting for GC tests;
the normal path does not perform per-result accounting.

Reference: [JDK 25 MemorySegment documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/MemorySegment.html).

## Changes and locking

The C API wraps AutoCommit; the wrapper controls its pending operations. A change
holds the document monitor across the callback and explicit commit. An exception
rolls pending operations back. Functions which might implicitly commit (save,
heads, fork, merge, synchronization, applying changes) are unavailable inside a
callback. All document access is serialized; unrelated documents can run in parallel.

The change handle validates its active status and invoking thread. Each object
handle obtained in a change remembers that change's status. Rollback invalidates
these handles, including handles to previously existing objects read in that
failed callback. This conservative rule prevents rolled-back IDs being reused
as if they still designated the old object. Successful callbacks leave handles usable.

Nested changes and cross-document access in callbacks are rejected. Merge locks
two documents in a deterministic order. Sync state belongs to one document, so
the document monitor protects both. Transport reliability and peer identity are
application concerns.

## Conversion and costs

`SavedDocument` is a final Java value class containing an owned full-save byte
array, with byte-content equality and hashing. It retains no native handles.
External construction copies before checking the signature, snapshot prefix,
known chunk types and canonical unsigned-LEB128 chunk lengths/boundaries. The
framing parser follows `storage/chunk.rs` and `storage/parse/leb128.rs` in the
pinned upstream source. It skips chunk bodies and does not checksum, decompress
or reconstruct them; successful construction is not a full validity guarantee.
Loading delegates content validation to Automerge.

`save` copies the owned native result into a fresh array and transfers that array
to `SavedDocument` without copying or checking it again. The package-local
constructor and array are accessed only through the internal Java
`SavedDocumentSupport` bridge. Public byte access always copies. `load` copies
the saved value directly into a confined native arena, avoiding an intermediate
JVM array. `bytes` on a live document saves directly to an array without creating
a wrapper. Both full-save operations retain upstream's incremental-cursor side
effect. Bytes from a saved value are a pure copy. Storage/transport codecs remain
external; the library emits standard Automerge bytes with upstream compression.

Strings used as scalars and collaborative text are deliberately separate. Keys
are strings, with no lossy keyword coercion. Counters and unsigned integers retain
explicit wrappers; timestamps use Instant. Null and absence remain distinct.
Object references are local to a replica. Their printable IDs identify upstream
objects, but references from one replica cannot be passed directly to another.

Text indices use UTF-16 units in the native build, consistently with JVM and JS
string indices. Text edits, mark endpoints and indexed cursors validate scalar
boundaries with native cursor queries, avoiding a whole-text copy. `update-text!`
explicitly invokes the native text diff; it rejects block markers. Direct splices
remain available for operation-level edits.

Each path step crosses the FFI boundary. Reusing an object reference avoids path
re-resolution. `entries` performs one native range query, with decoding calls per
item; `materialize` recursively reads and allocates the complete visible tree.
It does not preserve conflicts, history or annotations. Head sets and encoded
change batches use balanced `AMresultCat` concatenations, with O(n log n) item
copying across temporary results. Strict batch application decodes everything
and verifies each header checksum against its decoded hash before mutating,
then calls upstream's in-place `AMapplyChanges` directly. The bridge allocates
for the incoming batch but does not clone document state. Existing object handles
and peer states remain valid after successful application. These are cost
descriptions, not benchmark claims.

Deltas are mechanically extracted and transported as encoded Automerge changes.
Expected delivery conditions include duplicate batches, delayed dependencies,
reconnection and retries. Their original change identities are preserved.
Decode errors happen before document mutation. Native application errors have no
rollback guarantee; callers discard and reload the working replica. A topology's
load/apply/persist cycle supplies the recovery boundary without an extra snapshot
of every loaded document. Local `with-change` rollback is unaffected.

## Acknowledged deltas

`capture-delta` obtains the changes and ending heads under a single document lock.
It has no mutable save cursor. A caller retains the bytes until its destination
acknowledges them, advances its own checkpoint, and retries identical bytes on
failure. Checkpoint ownership and acknowledgement ordering are external concerns.

Automerge's history queries omit causally blocked changes. Capture therefore
rejects a document with missing dependencies instead of silently losing those
changes at a delivery boundary. Full saves retain the native queue; callers may
also retain or forward incoming encoded batches themselves. The permissive
incremental loader remains separate from strict batch application.

Resource teardown belongs to the application: a shared live replica may submit
its final outstanding batch before releasing its references. The bridge carries
no networking or task-system dependency. Historical forks let background editors
work against the version they read while other replicas continue editing.

## Remaining scope

The API covers document operations, scalar and collection edits, rich-text
primitives, native patches, history inspection, acknowledged-delta capture,
persistence and sync. Historical reads use explicit forks. Applications can poll
patches from explicit heads; the library does not install callback subscriptions.
Bulk hydration/reconciliation, configurable actor IDs, eager disposal and a
networking or repository layer remain outside this API. Avoid building
substitutes for upstream capabilities merely to present a broader surface.

The source recipe and ABI checks are present for future platform builds; only
macOS ARM64 has been exercised so far. A portable release needs verified native
artifacts and their dependency notices on each supported target. Local synthetic
load/apply/save probes are available through `bb bench`; production capacity,
sustained native-memory pressure and cross-platform performance remain unverified.
