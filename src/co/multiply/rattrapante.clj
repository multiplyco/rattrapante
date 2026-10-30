(ns co.multiply.rattrapante
  "Explicit Automerge documents, changes and synchronization for JVM Clojure."
  (:refer-clojure :exclude [bytes get get-in keys load])
  (:require [clojure.edn :as edn]
    [co.multiply.rattrapante.ffi :as ffi])
  (:import
    [co.multiply.rattrapante SavedDocument]
    [co.multiply.rattrapante.internal Bindings FFM]
    [java.lang.foreign Arena MemorySegment SegmentAllocator ValueLayout]
    [java.time Instant]
    [java.util.concurrent.atomic AtomicLong]))


(set! *warn-on-reflection* true)

(def ^:private ^AtomicLong sequence-number (AtomicLong/new))
(def ^:dynamic ^:private *changing* nil)
(def ^:dynamic ^:private *change-thread* nil)
(def ^:private missing (Object/new))

(deftype Document [^MemorySegment pointer ^long order active])
(deftype Change [^Document document ^Thread thread status])
(deftype ObjectRef [^Document document ^MemorySegment pointer kind id birth])
(deftype SyncState [^Document document ^MemorySegment pointer])


(doseq [constructor [#'->Document #'->Change #'->ObjectRef #'->SyncState]]
  (alter-meta! constructor assoc :private true :no-doc true))


(defrecord Counter [value])
(defrecord UInt [value])
(defrecord UnknownScalar [type-code bytes])

(def root "The root map identifier, used with an explicit document or change." ::root)


(defn document?
  "True for a live Automerge document handle."
  [x]
  (instance? Document x))


(defn saved-document?
  "True for an immutable saved Automerge document."
  [x]
  (instance? SavedDocument x))


(defn object?
  "True for a nested object handle. The root constant is not a handle."
  [x]
  (instance? ObjectRef x))


(defn counter
  "Construct a counter scalar. Use increment! to change its value collaboratively."
  [n]
  (when-not (integer? n) (throw (ex-info "Counter requires an integer" {:value n})))
  (->Counter (long n)))


(defn uint
  "Construct an unsigned 64-bit scalar from a non-negative Clojure integer."
  [n]
  (when-not (and (integer? n) (<= 0 n 18446744073709551615N))
    (throw (ex-info "Unsigned integer is outside uint64 range" {:value n})))
  (->UInt (bigint n)))


(defn- fail
  [message data]
  (throw (ex-info message (assoc data :type :rattrapante/invalid-operation))))


(defn- context-doc
  ^Document [context]
  (cond
    (document? context) context
    (instance? Change context)
    (let [^Change tx context]
      (when-not (and (identical? (Thread/currentThread) (.-thread tx))
                  (= :active @(.-status tx)))
        (fail "Change handle is inactive or belongs to another thread" {}))
      (.-document tx))
    :else (fail "Expected a document or active change" {})))


(defn- on-doc
  [context f]
  (let [doc (context-doc context)]
    (when (and *changing* (not (identical? (Thread/currentThread) *change-thread*)))
      (fail "A change callback cannot access its document from another thread" {}))
    (when (and *changing* (not (identical? doc *changing*)))
      (fail "A change callback may only access its own document" {}))
    (locking doc (f doc))))


(defn- outside-change!
  []
  (when *changing* (fail "This operation cannot run inside a change" {})))


(defn- document-op
  [doc f]
  (outside-change!)
  (when-not (document? doc) (fail "Expected a document" {}))
  (on-doc doc f))


(defn- pointer
  ^MemorySegment [^Document doc object]
  (if (= root object)
    ffi/null
    (do
      (when-not (object? object) (fail "Expected root or an object handle" {:object object}))
      (let [^ObjectRef object object
            birth (.-birth object)]
        (when-not (identical? doc (.-document object))
          (fail "Object belongs to another document; resolve it in this document" {}))
        (when (and birth (= :rolled-back @birth))
          (fail "Object handle was obtained in a rolled-back change" {}))
        (.-pointer object)))))


(defn- kind-of
  [^Document doc object]
  (pointer doc object)
  (if (= root object) :map (.-kind ^ObjectRef object)))


(defn object-type
  "Return :map, :list or :text for an object handle."
  [object]
  (if (= root object) :map (.-kind ^ObjectRef object)))


(defn object-id
  "Return an object's Automerge identifier as a string."
  [object]
  (if (= root object) "_root" (.-id ^ObjectRef object)))


(defn- new-doc
  [raw]
  (Document/new (ffi/owning-pointer raw 256) (AtomicLong/.incrementAndGet sequence-number) (atom nil)))


(defn create
  "Create an empty, mutable, GC-managed document with a fresh actor ID."
  []
  (new-doc (Bindings/AMcreate ffi/null)))


(defn saved-document
  "Copy full-save bytes into an immutable SavedDocument. Checks the Automerge
  signature, initial snapshot chunk and all chunk lengths/boundaries. Does not
  check checksums, decompress or validate document contents; use load for that."
  ^SavedDocument [data]
  (when-not (bytes? data) (fail "Expected saved document bytes" {}))
  (try
    (SavedDocument/fromBytes data)
    (catch IllegalArgumentException e
      (throw (ex-info (Throwable/.getMessage e)
               {:type :rattrapante/invalid-saved-document} e)))))


(defn- document-data!
  [data]
  (when-not (or (bytes? data) (saved-document? data))
    (fail "Expected Automerge bytes or a SavedDocument" {})))


(defn load
  "Load Automerge bytes or a SavedDocument into an independent live document
  with a fresh actor ID. Automerge validates the encoded contents when loading."
  [data]
  (document-data! data)
  (with-open [arena (Arena/ofConfined)]
    (new-doc (Bindings/AMload (ffi/buffer arena data) (ffi/byte-size data)))))


(defn save
  "Capture a complete immutable SavedDocument, retaining history and queued
  changes. Advances the native incremental-save cursor."
  ^SavedDocument [doc]
  (document-op doc #(ffi/result-saved-document (Bindings/AMsave (.-pointer ^Document %)))))


(defn bytes
  "Return a fresh byte array in Automerge's full-save format. For a SavedDocument,
  copy its encoding; for a live Document, save directly without a wrapper and
  advance its native incremental-save cursor."
  ^bytes [value]
  (if (saved-document? value)
    (SavedDocument/.toByteArray value)
    (document-op value #(ffi/result-bytes (Bindings/AMsave (.-pointer ^Document %))))))


(defn save-incremental!
  "Serialize changes since the previous save, bytes or save-incremental! on this
  live document. Reading bytes from a SavedDocument does not advance any cursor.
  Advances its save cursor; use one persistence writer per document."
  [doc]
  (document-op doc #(ffi/result-bytes (Bindings/AMsaveIncremental (.-pointer ^Document %)))))


(defn load-incremental!
  "Apply a SavedDocument, full-save bytes, incremental save, or concatenated
  change bytes. Returns doc.
  Follows upstream's permissive partial-load behavior; use apply-changes! for
  strict ingestion of individual encoded changes."
  [doc data]
  (document-data! data)
  (document-op doc
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (ffi/discard! (Bindings/AMloadIncremental (.-pointer doc) (ffi/buffer arena data) (ffi/byte-size data))))))
  doc)


(defn actor-id
  "Return this replica's actor ID as hexadecimal."
  [doc]
  (on-doc doc
    (fn [^Document doc]
      (ffi/result (Bindings/AMgetActorId (.-pointer doc))
        (fn [arena result _]
          (ffi/string-value
            (Bindings/AMactorIdStr (ffi/alloc arena)
              (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToActorId))))))))


(defn heads
  "Return the current change hashes as a vector of hexadecimal strings."
  [doc]
  (document-op doc
    (fn [^Document doc]
      (ffi/result (Bindings/AMgetHeads (.-pointer doc))
        (fn [arena result _]
          (ffi/items arena result
            #(ffi/hex-string (ffi/item-bytes arena % Bindings/AMitemToChangeHash))))))))


(defn fork
  "Fork into an independent document with a fresh actor ID. Optionally fork at
  a vector of heads. An empty vector selects the empty document history."
  ([doc]
   (document-op doc #(new-doc (Bindings/AMfork (.-pointer ^Document %) ffi/null))))
  ([doc at-heads]
   (document-op doc
     (fn [^Document doc]
       (if (empty? at-heads)
         (create)
         (ffi/with-heads at-heads #(new-doc (Bindings/AMfork (.-pointer doc) %))))))))


(defn merge!
  "Merge source into destination in place. Returns destination."
  [^Document destination ^Document source]
  (outside-change!)
  (when-not (and (document? destination) (document? source))
    (fail "merge! requires two documents" {}))
  (when-not (identical? destination source)
    (let [[a b] (if (< (.-order destination) (.-order source))
                  [destination source] [source destination])]
      (locking a
        (locking b
          (ffi/discard! (Bindings/AMmerge (.-pointer destination) (.-pointer source)))))))
  destination)


(defn change!
  "Run f with an exclusive change handle. Commit once on success, roll back on
  exception, and return f's result. The handle is valid only in this callback,
  on its invoking thread. Options: :message string and :time epoch seconds.
  Nested changes and cross-document calls in callbacks are rejected."
  ([doc f] (change! doc {} f))
  ([doc {:keys [message time]} f]
   (outside-change!)
   (when (and message (not (string? message))) (fail "Change message must be a string" {}))
   (when (and (some? time) (not (integer? time))) (fail "Change time must be epoch seconds" {}))
   (document-op doc
     (fn [^Document doc]
       (let [status (atom :active)
             tx (Change/new doc (Thread/currentThread) status)]
         (reset! (.-active doc) status)
         (binding [*changing* doc *change-thread* (Thread/currentThread)]
           (try
             (let [value (f tx)]
               (with-open [arena (Arena/ofConfined)]
                 (ffi/discard!
                   (Bindings/AMcommit (.-pointer doc) (ffi/span arena message)
                     (if (some? time)
                       (doto (SegmentAllocator/.allocate arena ValueLayout/JAVA_LONG)
                         (MemorySegment/.set ValueLayout/JAVA_LONG 0 (long time)))
                       ffi/null))))
               (reset! status :committed)
               value)
             (catch Throwable t
               (Bindings/AMrollback (.-pointer doc))
               (reset! status :rolled-back)
               (throw t))
             (finally (reset! (.-active doc) nil)))))))))


(defmacro with-change
  "Evaluate body in a change. Binding is [tx doc] or [tx doc options]."
  [[tx doc options] & body]
  `(change! ~doc ~(or options {}) (fn [~tx] ~@body)))


(defn- mutate
  [tx f]
  (when-not (instance? Change tx) (fail "Writes require an active change handle" {}))
  (on-doc tx f))


(defn- read-value
  [^Document doc ^Arena arena pins ^MemorySegment item]
  (case (Bindings/AMitemValType item)
    1 missing
    4096 nil
    4 (let [out (SegmentAllocator/.allocate arena ValueLayout/JAVA_BOOLEAN)]
        (Bindings/AMitemToBool item out)
        (MemorySegment/.get out ValueLayout/JAVA_BOOLEAN 0))
    8 (ffi/item-bytes arena item Bindings/AMitemToBytes)
    64 (->Counter (ffi/item-long arena item Bindings/AMitemToCounter))
    512 (let [out (SegmentAllocator/.allocate arena ValueLayout/JAVA_DOUBLE)]
          (Bindings/AMitemToF64 item out)
          (MemorySegment/.get out ValueLayout/JAVA_DOUBLE 0))
    1024 (ffi/item-long arena item Bindings/AMitemToInt)
    16384 (ffi/item-string arena item)
    262144 (Instant/ofEpochMilli (ffi/item-long arena item Bindings/AMitemToTimestamp))
    524288 (->UInt (bigint (Long/toUnsignedString (ffi/item-long arena item Bindings/AMitemToUint))))
    8192 (let [ptr (FFM/retainObject item)]
           (vswap! pins conj ptr)
           (ObjectRef/new doc ptr
             (case (Bindings/AMobjObjType (.-pointer doc) ptr) 1 :list 2 :map 3 :text)
             (ffi/object-id arena ptr) @(.-active doc)))
    (fail "Unsupported Automerge value type" {:native-type (Bindings/AMitemValType item)})))


(defn- map-key
  [key]
  (when-not (string? key) (fail "Automerge map keys must be strings" {:key key}))
  key)


(defn- index
  [i]
  (when-not (and (integer? i) (<= 0 i Long/MAX_VALUE))
    (fail "Index must be a non-negative signed 64-bit integer" {:index i}))
  (long i))


(defn get
  "Read one map property or list element. Nested objects remain handles.
  Missing map properties return not-found (nil by default); stored null is
  always nil. Invalid list indices raise native errors."
  ([context object key] (get context object key nil))
  ([context object key not-found]
   (on-doc context
     (fn [^Document doc]
       (with-open [arena (Arena/ofConfined)]
         (let [ptr (pointer doc object)
               raw (case (kind-of doc object)
                     :map (Bindings/AMmapGet (.-pointer doc) ptr (ffi/span arena (map-key key)) ffi/null)
                     :list (Bindings/AMlistGet (.-pointer doc) ptr (index key) ffi/null)
                     (fail "Use text to read a text object" {}))]
           (ffi/result raw
             (fn [arena result pins]
               (let [value (read-value doc arena pins (Bindings/AMresultItem result))]
                 (if (identical? missing value) not-found value))))))))))


(defn get-in
  "Resolve a path from root. Each step performs a native lookup. For repeated
  access, keep the returned object handle. This does not materialize subtrees."
  ([context path] (get-in context path nil))
  ([context path not-found]
   (on-doc context
     (fn [_]
       (loop [object root path (seq path)]
         (if-not path
           object
           (let [v (get context object (first path) missing)]
             (cond
               (identical? v missing) not-found
               (nil? (next path)) v
               (object? v) (recur v (next path))
               :else not-found))))))))


(defn size
  "Map property count, list length, or text length in UTF-16 units."
  [context object]
  (on-doc context #(Bindings/AMobjSize (.-pointer ^Document %) (pointer % object) ffi/null)))


(defn keys
  "Eager vector of a map's string keys."
  [context object]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :map (kind-of doc object)) (fail "keys requires a map" {}))
      (ffi/result (Bindings/AMkeys (.-pointer doc) (pointer doc object) ffi/null)
        (fn [arena result _] (ffi/items arena result #(ffi/item-string arena %)))))))


(defn text
  "Copy a collaborative text object's current content into a JVM string."
  [context object]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "text requires a text object" {}))
      (ffi/result (Bindings/AMtext (.-pointer doc) (pointer doc object) ffi/null)
        (fn [arena result _] (ffi/item-string arena (Bindings/AMresultItem result)))))))


(defn get-all
  "Return all concurrent values as [{:id operation-id :value value} ...].
  Includes the ordinary winning value. Missing entries return []."
  [context object key]
  (on-doc context
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [ptr (pointer doc object)
              raw (case (kind-of doc object)
                    :map (Bindings/AMmapGetAll (.-pointer doc) ptr (ffi/span arena (map-key key)) ffi/null)
                    :list (Bindings/AMlistGetAll (.-pointer doc) ptr (index key) ffi/null)
                    (fail "get-all requires a map or list" {}))]
          (ffi/result raw
            (fn [arena result pins]
              (into [] (remove nil?)
                (ffi/items arena result
                  (fn [item]
                    (let [v (read-value doc arena pins item)]
                      (when-not (identical? v missing)
                        {:id (ffi/object-id arena (Bindings/AMitemObjId item)) :value v}))))))))))))


(defn conflicts
  "Return concurrent values when more than one exists, otherwise []."
  [context object key]
  (let [all (get-all context object key)] (if (< 1 (count all)) all [])))


(defn entries
  "Eager vector of [key value] pairs for a map or [index value] for a list.
  Uses one native range query. Nested objects remain handles."
  [context object]
  (on-doc context
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [ptr (pointer doc object)
              kind (kind-of doc object)
              raw (case kind
                    :map (Bindings/AMmapRange (.-pointer doc) ptr (ffi/span arena nil) (ffi/span arena nil) ffi/null)
                    :list (Bindings/AMlistRange (.-pointer doc) ptr 0 Long/MAX_VALUE ffi/null)
                    (fail "entries requires a map or list" {}))]
          (ffi/result raw
            (fn [arena result pins]
              (ffi/items arena result
                (fn [item]
                  [(if (= kind :map)
                     (String/new ^bytes (ffi/item-bytes arena item Bindings/AMitemKey) java.nio.charset.StandardCharsets/UTF_8)
                     (ffi/item-long arena item Bindings/AMitemPos))
                   (read-value doc arena pins item)])))))))))


(defn materialize
  "Copy the visible subtree into persistent Clojure data. This walks and copies
  the entire subtree. History, object identity and hidden conflicts are omitted;
  Counter and UInt scalars retain their explicit types."
  ([context] (materialize context root))
  ([context object]
   (on-doc context
     (fn [doc]
       (case (kind-of doc object)
         :text (text context object)
         :map (into {} (map (fn [[k v]] [k (if (object? v) (materialize context v) v)])) (entries context object))
         :list (mapv (fn [[_ v]] (if (object? v) (materialize context v) v)) (entries context object)))))))


(defn- scalar
  [value]
  (cond
    (nil? value) [:null nil]
    (boolean? value) [:bool value]
    (string? value) [:string value]
    (bytes? value) [:bytes value]
    (integer? value) [:int (long value)]
    (or (instance? Double value) (instance? Float value)) [:double (double value)]
    (instance? Counter value) [:counter (:value (counter (:value value)))]
    (instance? UInt value) [:uint (Number/.longValue (:value (uint (:value value))))]
    (instance? Instant value) [:timestamp (Instant/.toEpochMilli value)]
    :else (fail "Unsupported scalar; use put-object!/insert-object! for containers" {:value value})))


(defn- structured-result
  [raw]
  (ffi/result raw
    (fn [arena result _]
      (edn/read-string
        {:readers {'rattrapante/counter counter
                   'rattrapante/uint uint
                   'rattrapante/timestamp Instant/ofEpochMilli
                   'rattrapante/bytes ffi/unhex
                   'rattrapante/unknown (fn [[code data]] (->UnknownScalar code (ffi/unhex data)))}}
        (ffi/item-string arena (Bindings/AMresultItem result))))))


(defn resolve-object
  "Resolve a serialized object ID in this replica. This preserves identity even
  after an object moves or becomes unreachable from root. Unknown IDs fail."
  [context id]
  (on-doc context
    (fn [^Document doc]
      (if (= "_root" id)
        root
        (with-open [arena (Arena/ofConfined)]
          (ffi/result (Bindings/AMrResolveObject (.-pointer doc) (ffi/span arena (map-key id)))
            (fn [arena result pins] (read-value doc arena pins (Bindings/AMresultItem result)))))))))


(defn- text-boundary!
  [^Document doc object i]
  (ffi/discard! (Bindings/AMrTextBoundary (.-pointer doc) (pointer doc object) (long i))))


(defn- map-put
  [^MemorySegment doc ^MemorySegment obj ^MemorySegment key arena value]
  (let [[tag v] (scalar value)]
    (case tag
      :null (Bindings/AMmapPutNull doc obj key)
      :bool (Bindings/AMmapPutBool doc obj key (boolean v))
      :string (Bindings/AMmapPutStr doc obj key (ffi/span arena v))
      :bytes (Bindings/AMmapPutBytes doc obj key (ffi/span arena v))
      :int (Bindings/AMmapPutInt doc obj key (long v))
      :double (Bindings/AMmapPutF64 doc obj key (double v))
      :counter (Bindings/AMmapPutCounter doc obj key (long v))
      :uint (Bindings/AMmapPutUint doc obj key (long v))
      :timestamp (Bindings/AMmapPutTimestamp doc obj key (long v)))))


(defn- list-put
  [^MemorySegment doc ^MemorySegment obj pos insert? arena value]
  (let [[tag v] (scalar value)
        pos (long pos)
        insert? (boolean insert?)]
    (case tag
      :null (Bindings/AMlistPutNull doc obj pos insert?)
      :bool (Bindings/AMlistPutBool doc obj pos insert? (boolean v))
      :string (Bindings/AMlistPutStr doc obj pos insert? (ffi/span arena v))
      :bytes (Bindings/AMlistPutBytes doc obj pos insert? (ffi/span arena v))
      :int (Bindings/AMlistPutInt doc obj pos insert? (long v))
      :double (Bindings/AMlistPutF64 doc obj pos insert? (double v))
      :counter (Bindings/AMlistPutCounter doc obj pos insert? (long v))
      :uint (Bindings/AMlistPutUint doc obj pos insert? (long v))
      :timestamp (Bindings/AMlistPutTimestamp doc obj pos insert? (long v)))))


(defn- list-index
  [doc obj key insert?]
  (let [i (index key)
        n (size doc obj)]
    (when-not (if insert? (<= i n) (< i n))
      (fail "List index is out of bounds" {:index i :size n :insert? insert?}))
    i))


(defn put!
  "Set a scalar map property or replace an existing list element. Strings are
  immutable scalars; use a :text object and splice-text! for collaborative text.
  Setting a counter replaces it; increment! preserves concurrent increments."
  [tx object key value]
  (mutate tx
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [ptr (pointer doc object)]
          (ffi/discard!
            (case (kind-of doc object)
              :map (map-put (.-pointer doc) ptr (ffi/span arena (map-key key)) arena value)
              :list (list-put (.-pointer doc) ptr (list-index doc object key false) false arena value)
              (fail "Use splice-text! to edit text" {})))))))
  nil)


(defn insert!
  "Insert a scalar at a list index (size appends). Requires an active change."
  [tx object i value]
  (mutate tx
    (fn [^Document doc]
      (when-not (= :list (kind-of doc object)) (fail "insert! requires a list" {}))
      (with-open [arena (Arena/ofConfined)]
        (ffi/discard!
          (list-put (.-pointer doc) (pointer doc object) (list-index doc object i true) true arena value)))))
  nil)


(defn- write-object
  [tx object key kind insert?]
  (mutate tx
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [type (case kind :map 2 :list 1 :text 3 (fail "Unknown object type" {:kind kind}))
              ptr (pointer doc object)
              raw (case (kind-of doc object)
                    :map (do
                           (when insert? (fail "insert-object! requires a list" {}))
                           (Bindings/AMmapPutObject (.-pointer doc) ptr (ffi/span arena (map-key key)) type))
                    :list (Bindings/AMlistPutObject (.-pointer doc) ptr (list-index doc object key insert?) (boolean insert?) type)
                    (fail "Objects cannot be inserted into text through this API" {}))]
          (ffi/result raw
            (fn [arena result pins] (read-value doc arena pins (Bindings/AMresultItem result)))))))))


(defn put-object!
  "Create a new :map, :list or :text at a map key or existing list position.
  Returns its handle. Replacing an object creates a new identity."
  [tx parent key kind]
  (write-object tx parent key kind false))


(defn insert-object!
  "Insert a new :map, :list or :text into a list; return its handle."
  [tx parent index kind]
  (write-object tx parent index kind true))


(defn delete!
  "Delete a map property or list element. Does not delete the root object."
  [tx object key]
  (mutate tx
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [ptr (pointer doc object)]
          (ffi/discard!
            (case (kind-of doc object)
              :map (Bindings/AMmapDelete (.-pointer doc) ptr (ffi/span arena (map-key key)))
              :list (Bindings/AMlistDelete (.-pointer doc) ptr (list-index doc object key false))
              (fail "Use splice-text! to delete text" {})))))))
  nil)


(defn increment!
  "Add a signed integer delta to a counter in a map or list."
  [tx object key delta]
  (when-not (integer? delta) (fail "Counter delta must be an integer" {:delta delta}))
  (mutate tx
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [ptr (pointer doc object)]
          (ffi/discard!
            (case (kind-of doc object)
              :map (Bindings/AMmapIncrement (.-pointer doc) ptr (ffi/span arena (map-key key)) (long delta))
              :list (Bindings/AMlistIncrement (.-pointer doc) ptr (list-index doc object key false) (long delta))
              (fail "increment! requires a map or list" {})))))))
  nil)


(defn splice-text!
  "Replace delete-count UTF-16 units at index with a string. Positions must fall
  on Unicode scalar boundaries; use cursors for positions that survive edits."
  [tx object index-value delete-count value]
  (when-not (string? value) (fail "Text insertion requires a string" {}))
  (mutate tx
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "splice-text! requires text" {}))
      (let [pos (index index-value) del (index delete-count) n (size doc object)]
        (when-not (<= (+ (bigint pos) del) n)
          (fail "Text splice is out of bounds" {:index pos :delete-count del :size n}))
        (text-boundary! doc object pos)
        (text-boundary! doc object (+ pos del))
        (with-open [arena (Arena/ofConfined)]
          (ffi/discard!
            (Bindings/AMspliceText (.-pointer doc) (pointer doc object) pos del (ffi/span arena value)))))))
  nil)


(defn cursor
  "Return a serializable, stable position token for a list or text object.
  Position is :start, :end, or an existing element index (UTF-16 for text).
  Options: :move :before/:after (default :after) when that element is deleted."
  ([context object position] (cursor context object position {}))
  ([context object position {:keys [move] :or {move :after}}]
   (on-doc context
     (fn [^Document doc]
       (let [kind (kind-of doc object)
             endpoint (case position :start -1 :end 1 0)
             i (if (zero? endpoint) (list-index doc object position false) 0)]
         (when-not (#{:list :text} kind) (fail "cursor requires a sequence" {}))
         (when-not (#{:before :after} move) (fail "Cursor movement must be :before or :after" {:move move}))
         (when (and (= :text kind) (zero? endpoint)) (text-boundary! doc object i))
         (ffi/result (Bindings/AMrCursor (.-pointer doc) (pointer doc object) i endpoint (= move :before))
           (fn [arena result _]
             (ffi/string-value
               (Bindings/AMcursorStr (ffi/alloc arena)
                 (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToCursor))))))))))


(defn- scalar-item
  [arena value]
  (let [[tag v] (scalar value)]
    (case tag
      :null (Bindings/AMitemFromNull)
      :bool (Bindings/AMitemFromBool (boolean v))
      :string (Bindings/AMitemFromStr (ffi/span arena v))
      :bytes (Bindings/AMitemFromBytes (ffi/buffer arena v) (alength ^bytes v))
      :int (Bindings/AMitemFromInt (long v))
      :double (Bindings/AMitemFromF64 (double v))
      :counter (Bindings/AMitemFromCounter (long v))
      :uint (Bindings/AMitemFromUint (long v))
      :timestamp (Bindings/AMitemFromTimestamp (long v)))))


(defn- mark-range
  [doc object start end]
  (when-not (= :text (kind-of doc object)) (fail "Marks require a text object" {}))
  (let [start (index start) end (index end)]
    (when-not (<= start end (bigint (size doc object)))
      (fail "Mark range must satisfy 0 <= start <= end <= text size" {:start start :end end}))
    (text-boundary! doc object start)
    (text-boundary! doc object end)
    [start end]))


(defn- expansion
  [expand]
  (case expand :none 1 :before 2 :after 3 :both 4 (fail "Unknown mark expansion mode" {:expand expand})))


(defn mark!
  "Set a named scalar mark on [start,end) in a text object. Indices use UTF-16.
  Expansion controls adjacent insertions: :none (default), :before, :after, :both.
  Empty ranges are allowed, including empty text."
  ([tx object start end name value] (mark! tx object start end name value :none))
  ([tx object start end name value expand]
   (mutate tx
     (fn [^Document doc]
       (let [[start end] (mark-range doc object start end)]
         (with-open [arena (Arena/ofConfined)]
           (ffi/result (scalar-item arena value)
             (fn [_ result _]
               (ffi/discard!
                 (Bindings/AMmarkCreate (.-pointer doc) (pointer doc object) start end
                   (expansion expand) (ffi/span arena (map-key name)) (Bindings/AMresultItem result)))))))))
   nil))


(defn unmark!
  "Remove a named mark from [start,end). Expansion defaults to :none."
  ([tx object start end name] (unmark! tx object start end name :none))
  ([tx object start end name expand]
   (mutate tx
     (fn [^Document doc]
       (let [[start end] (mark-range doc object start end)]
         (with-open [arena (Arena/ofConfined)]
           (ffi/discard!
             (Bindings/AMmarkClear (.-pointer doc) (pointer doc object) start end
               (expansion expand) (ffi/span arena (map-key name))))))))
   nil))


(defn marks
  "Copy text annotations into [{:start n :end n :name string :value scalar} ...]."
  [context object]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "marks requires text" {}))
      (ffi/result (Bindings/AMmarks (.-pointer doc) (pointer doc object) ffi/null)
        (fn [arena result _]
          (ffi/items arena result
            (fn [item]
              (let [mark (ffi/item-pointer arena item Bindings/AMitemToMark)]
                {:start (Bindings/AMmarkStart mark)
                 :end (Bindings/AMmarkEnd mark)
                 :name (ffi/string-value (Bindings/AMmarkName (ffi/alloc arena) mark))
                 :value (ffi/result (Bindings/AMmarkValue mark)
                          (fn [arena result pins]
                            (read-value doc arena pins (Bindings/AMresultItem result))))}))))))))


(defn cursor-position
  "Resolve a cursor token against the current object state."
  [context object token]
  (on-doc context
    (fn [^Document doc]
      (when-not (#{:list :text} (kind-of doc object)) (fail "cursor-position requires a sequence" {}))
      (with-open [arena (Arena/ofConfined)]
        (ffi/result (Bindings/AMcursorFromStr (ffi/span arena token))
          (fn [arena result _]
            (ffi/result
              (Bindings/AMgetCursorPosition (.-pointer doc) (pointer doc object)
                (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToCursor) ffi/null)
              (fn [arena result _]
                (ffi/item-long arena (Bindings/AMresultItem result) Bindings/AMitemToUint)))))))))


(defn changes-since
  "Return encoded changes missing from a set of heads. [] requests all changes."
  [doc have-heads]
  (document-op doc
    (fn [^Document doc]
      (ffi/with-heads have-heads
        (fn [native-heads]
          (ffi/result (Bindings/AMgetChanges (.-pointer doc) native-heads)
            (fn [arena result _]
              (ffi/items arena result
                #(ffi/bytes
                   (Bindings/AMchangeRawBytes (ffi/alloc arena)
                     (ffi/item-pointer arena % Bindings/AMitemToChange)))))))))))


(defn apply-changes!
  "Decode and validate the complete batch, then apply it in place. Duplicate
  delivery is harmless; missing dependencies remain queued until received.
  Decode errors leave doc unchanged. Native application errors may change its
  internal state: discard and reload that working replica before retrying.
  Does not clone the document or roll back imported changes. Returns doc."
  [doc changes]
  (document-op doc
    (fn [^Document doc]
      (when (seq changes)
        (ffi/with-items changes ffi/change-result
          #(ffi/discard! (Bindings/AMapplyChanges (.-pointer doc) %))))))
  doc)


(defn sync-state
  "Create GC-managed sync state for one document/peer connection. Keep a separate
  state for each peer. Optionally restore bytes from encode-sync-state."
  ([doc]
   (document-op doc
     #(SyncState/new % (ffi/owning-pointer (Bindings/AMsyncStateInit) 131072))))
  ([doc ^bytes data]
   (document-op doc
     (fn [doc]
       (with-open [arena (Arena/ofConfined)]
         (SyncState/new doc
           (ffi/owning-pointer (Bindings/AMsyncStateDecode (ffi/buffer arena data) (alength data)) 131072)))))))


(defn encode-sync-state
  "Encode a peer's sync state for persistence."
  [^SyncState state]
  (document-op (.-document state)
    (fn [_] (ffi/result-bytes (Bindings/AMsyncStateEncode (.-pointer state))))))


(defn generate-sync-message!
  "Generate the next message and advance peer state. Returns bytes, or nil if
  there is nothing to send. Deliver messages reliably and in order per peer."
  [^SyncState state]
  (document-op (.-document state)
    (fn [^Document doc]
      (ffi/result (Bindings/AMgenerateSyncMessage (.-pointer doc) (.-pointer state))
        (fn [arena result _]
          (let [item (Bindings/AMresultItem result)]
            (when-not (= 1 (Bindings/AMitemValType item))
              (ffi/result-bytes
                (Bindings/AMsyncMessageEncode (ffi/item-pointer arena item Bindings/AMitemToSyncMessage))))))))))


(defn receive-sync-message!
  "Apply an incoming peer message and update its state. Returns the document."
  [^SyncState state ^bytes message]
  (let [doc (.-document state)]
    (document-op doc
      (fn [^Document doc]
        (with-open [arena (Arena/ofConfined)]
          (ffi/result (Bindings/AMsyncMessageDecode (ffi/buffer arena message) (alength message))
            (fn [arena result _]
              (ffi/discard!
                (Bindings/AMreceiveSyncMessage (.-pointer doc) (.-pointer state)
                  (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToSyncMessage))))))))
    doc))


(defn- result-hashes
  [raw]
  (ffi/result raw
    (fn [arena result _]
      (ffi/items arena result
        #(ffi/hex-string (ffi/item-bytes arena % Bindings/AMitemToChangeHash))))))


(defn- optional-bytes
  [raw]
  (ffi/result raw
    (fn [arena result _]
      (let [item (Bindings/AMresultItem result)]
        (when-not (= 1 (Bindings/AMitemValType item))
          (ffi/item-bytes arena item Bindings/AMitemToBytes))))))


(defn author
  "Return this replica's author identifier as copied bytes, or nil. Author IDs
  provide application attribution; actor IDs identify sequential execution."
  [doc]
  (on-doc doc #(optional-bytes (Bindings/AMrAuthor (.-pointer ^Document %)))))


(defn set-author!
  "Set the author bytes for subsequent local changes, or nil to clear them.
  Automerge manages actor rotation when the author changes. Returns doc."
  [doc value]
  (when-not (or (nil? value) (bytes? value)) (fail "Author must be bytes or nil" {}))
  (document-op doc
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (ffi/discard! (Bindings/AMrSetAuthor (.-pointer doc) (ffi/span arena value))))))
  doc)


(defn change-info
  "Decode an encoded change into copied metadata. :time is epoch seconds;
  :actor, :hash and :deps are hexadecimal. Does not retain native state."
  [data]
  (with-open [arena (Arena/ofConfined)]
    (ffi/result (ffi/change-result arena data)
      (fn [arena result _]
        (let [change (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToChange)
              message (Bindings/AMchangeMessage (ffi/alloc arena) change)]
          {:hash (ffi/hex-string (ffi/bytes (Bindings/AMchangeHash (ffi/alloc arena) change)))
           :author (optional-bytes (Bindings/AMrChangeAuthor change))
           :actor (ffi/result (Bindings/AMchangeActorId change)
                    (fn [arena result _]
                      (ffi/string-value
                        (Bindings/AMactorIdStr (ffi/alloc arena)
                          (ffi/item-pointer arena (Bindings/AMresultItem result) Bindings/AMitemToActorId)))))
           :seq (bigint (Long/toUnsignedString (Bindings/AMchangeSeq change)))
           :start-op (bigint (Long/toUnsignedString (Bindings/AMchangeStartOp change)))
           :max-op (bigint (Long/toUnsignedString (Bindings/AMchangeMaxOp change)))
           :time (Bindings/AMchangeTime change)
           :message (when-not (zero? (MemorySegment/.address (MemorySegment/.get message ValueLayout/ADDRESS 0)))
                      (ffi/string-value message))
           :deps (result-hashes (Bindings/AMchangeDeps change))
           :extra-bytes (ffi/bytes (Bindings/AMchangeExtraBytes (ffi/alloc arena) change))})))))


(defn change-by-hash
  "Return a copied encoded change by hash, or nil when unknown."
  [doc hash]
  (document-op doc
    (fn [^Document doc]
      (with-open [arena (Arena/ofConfined)]
        (let [data (ffi/hash-bytes hash)]
          (ffi/result (Bindings/AMgetChangeByHash (.-pointer doc) (ffi/buffer arena data) (alength data))
            (fn [arena result _]
              (let [item (Bindings/AMresultItem result)]
                (when-not (= 1 (Bindings/AMitemValType item))
                  (ffi/bytes (Bindings/AMchangeRawBytes (ffi/alloc arena)
                               (ffi/item-pointer arena item Bindings/AMitemToChange))))))))))))


(defn missing-deps
  "Hashes needed by queued changes or by the optional advertised remote heads."
  ([doc] (missing-deps doc []))
  ([doc remote-heads]
   (document-op doc
     (fn [^Document doc]
       (ffi/with-heads remote-heads
         #(result-hashes (Bindings/AMgetMissingDeps (.-pointer doc) %)))))))


(defn capture-delta
  "Atomically capture {:from heads :heads heads :changes [bytes ...]} since
  acknowledged-heads. Does not advance a cursor. Retain this batch until delivery
  is acknowledged, then use its :heads as the next checkpoint. Unknown heads fail.
  Causally incomplete input fails with :missing-deps rather than silently omitting
  queued changes. Retain incoming bytes or a full save until dependencies arrive."
  [doc acknowledged-heads]
  (document-op doc
    (fn [_]
      (let [from (vec acknowledged-heads)]
        (let [missing (missing-deps doc)]
          (when (seq missing)
            (fail "Cannot checkpoint changes with missing dependencies" {:missing-deps missing})))
        (doseq [hash from]
          (when-not (change-by-hash doc hash)
            (fail "Unknown checkpoint head" {:hash hash})))
        {:from from :heads (heads doc) :changes (changes-since doc from)}))))


(defn diff
  "Copy native patches between two explicit sets of heads. The two-argument
  form compares before-heads to current heads under the document lock. [] denotes
  empty history. Patches describe visible edits; transport :changes from
  capture-delta to replicate history. Object references in patches are ID maps."
  ([doc before-heads]
   (document-op doc (fn [_] (diff doc before-heads (heads doc)))))
  ([doc before-heads after-heads]
   (document-op doc
     (fn [^Document doc]
       (ffi/with-heads before-heads
         (fn [before]
           (ffi/with-heads after-heads
             #(structured-result (Bindings/AMrDiff (.-pointer doc) before %)))))))))


(defn spans
  "Read rich text as text spans and block markers. Every span includes its UTF-16
  :index. Text spans have :text and :marks; blocks have :object-id. Resolve a
  block ID to read/edit its map, including arbitrary nested attributes."
  [context object]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "spans requires text" {}))
      (structured-result (Bindings/AMrSpans (.-pointer doc) (pointer doc object))))))


(defn block-at
  "Return the block map at an exact text index, or nil for a text character.
  Use spans to enumerate markers. The index must identify an existing element."
  [context object i]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "block-at requires text" {}))
      (let [i (list-index doc object i false)]
        (text-boundary! doc object i)
        (ffi/result (Bindings/AMrBlock (.-pointer doc) (pointer doc object) i)
          (fn [arena result pins]
            (let [value (read-value doc arena pins (Bindings/AMresultItem result))]
              (when-not (identical? missing value) value))))))))


(defn split-block!
  "Insert an empty block marker at a text boundary and return its map handle.
  Set its type, parents and attributes with ordinary map/list operations.
  The marker occupies one UTF-16 unit."
  [tx object i]
  (mutate tx
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "split-block! requires text" {}))
      (let [i (list-index doc object i true)]
        (text-boundary! doc object i)
        (ffi/result (Bindings/AMrSplitBlock (.-pointer doc) (pointer doc object) i)
          (fn [arena result pins] (read-value doc arena pins (Bindings/AMresultItem result))))))))


(defn join-block!
  "Remove the block marker at an exact text index, preserving surrounding text."
  [tx object i]
  (mutate tx
    (fn [^Document doc]
      (let [i (index i)]
        (when-not (block-at tx object i) (fail "No block marker at index" {:index i}))
        (ffi/discard! (Bindings/AMrJoinBlock (.-pointer doc) (pointer doc object) i)))))
  nil)


(defn update-text!
  "Diff the current plain text against value using Automerge's text diff and
  apply the edits. Cost depends on both texts. For rich text use block, mark and
  splice operations; this helper rejects text containing block markers."
  [tx object value]
  (when-not (string? value) (fail "Text update requires a string" {}))
  (mutate tx
    (fn [^Document doc]
      (when (some #(= :block (:type %)) (spans tx object))
        (fail "update-text! requires text without block markers" {}))
      (with-open [arena (Arena/ofConfined)]
        (ffi/discard! (Bindings/AMrUpdateText (.-pointer doc) (pointer doc object) (ffi/span arena value))))))
  nil)


(defn replace-block!
  "Replace the block marker at an exact text index with a new empty map.
  Returns its handle. To preserve identity, edit the existing block map instead."
  [tx object i]
  (mutate tx
    (fn [^Document doc]
      (let [i (index i)]
        (when-not (block-at tx object i) (fail "No block marker at index" {:index i}))
        (ffi/result (Bindings/AMrReplaceBlock (.-pointer doc) (pointer doc object) i)
          (fn [arena result pins] (read-value doc arena pins (Bindings/AMresultItem result))))))))


(defn marks-at
  "Read active marks as a name/value map at a text boundary, including its end."
  [context object i]
  (on-doc context
    (fn [^Document doc]
      (when-not (= :text (kind-of doc object)) (fail "marks-at requires text" {}))
      (let [i (list-index doc object i true)]
        (text-boundary! doc object i)
        (structured-result (Bindings/AMrMarksAt (.-pointer doc) (pointer doc object) i))))))
