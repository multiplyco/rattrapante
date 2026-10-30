(ns ^:no-doc co.multiply.rattrapante.ffi
  (:refer-clojure :exclude [bytes])
  (:import
    [co.multiply.rattrapante SavedDocument SavedDocumentSupport]
    [co.multiply.rattrapante.internal Bindings FFM]
    [java.lang.foreign Arena MemorySegment SegmentAllocator ValueLayout]
    [java.lang.ref Reference]
    [java.nio ByteBuffer CharBuffer]
    [java.nio.charset Charset CharsetEncoder StandardCharsets]
    [java.util HexFormat]))


(set! *warn-on-reflection* true)

(def null MemorySegment/NULL)
(def ^:private hex (HexFormat/of))


(defn alloc
  ^SegmentAllocator [^Arena arena]
  (FFM/allocator arena))


(defn utf8
  ^bytes [^String value]
  (let [buffer (CharsetEncoder/.encode (Charset/.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap value))
        output (byte-array (ByteBuffer/.remaining buffer))]
    (ByteBuffer/.get buffer output)
    output))


(defn buffer
  ^MemorySegment [^Arena arena value]
  (if (instance? SavedDocument value)
    (SavedDocumentSupport/buffer value arena)
    (let [^bytes value value
          segment (^[long long] SegmentAllocator/.allocate arena (max 1 (alength value)) 1)]
      (MemorySegment/copy value 0 segment ValueLayout/JAVA_BYTE 0 (alength value))
      segment)))


(defn byte-size
  ^long [value]
  (if (instance? SavedDocument value)
    (SavedDocument/.byteSize value)
    (alength ^bytes value)))


(defn span
  ^MemorySegment [^Arena arena value]
  (let [out (SegmentAllocator/.allocate arena FFM/SPAN)]
    (when (some? value)
      (let [^bytes data (if (string? value) (utf8 value) value)]
        (MemorySegment/.set out ValueLayout/ADDRESS 0 (buffer arena data))
        (MemorySegment/.set out ValueLayout/JAVA_LONG 8 (long (alength data)))))
    out))


(defn bytes
  ^bytes [^MemorySegment span]
  (let [n (MemorySegment/.get span ValueLayout/JAVA_LONG 8)]
    (if (zero? n)
      (byte-array 0)
      (MemorySegment/.toArray (MemorySegment/.reinterpret (MemorySegment/.get span ValueLayout/ADDRESS 0) n) ValueLayout/JAVA_BYTE))))


(defn string-value
  [^MemorySegment span]
  (String/new (bytes span) StandardCharsets/UTF_8))


(defn hex-string
  [^bytes value]
  (HexFormat/.formatHex hex value))


(defn unhex
  ^bytes [^String value]
  (HexFormat/.parseHex hex value))


(defn check!
  [^Arena arena ^MemorySegment raw]
  (when-not (zero? (Bindings/AMresultStatus raw))
    (throw (ex-info (string-value (Bindings/AMresultError (alloc arena) raw))
             {:type :rattrapante/native-error})))
  raw)


(defn result
  "Consume a native result. Pins prevent retained Rc items from being finalized
  concurrently with the original result's release, including exceptional exits."
  [^MemorySegment raw f]
  (let [arena (Arena/ofConfined)
        pins (volatile! [])]
    (try
      (let [owned (FFM/own raw arena)]
        (check! arena owned)
        (f arena owned pins))
      (finally
        (Arena/.close arena)
        (Reference/reachabilityFence @pins)))))


(defn discard!
  [raw]
  (result raw (fn [_ _ _] nil)))


(defn owning-pointer
  ^MemorySegment [^MemorySegment raw type]
  (try
    (with-open [arena (Arena/ofConfined)]
      (check! arena raw))
    (catch Throwable t
      (Bindings/AMresultFree raw)
      (throw t)))
  (FFM/ownedPointer raw (int type)))


(defn items
  "Eagerly visit items while their owning result is alive."
  [^Arena arena ^MemorySegment result f]
  (let [iter (Bindings/AMresultItems (alloc arena) result)]
    (loop [out (transient [])]
      (let [item (Bindings/AMitemsNext iter 1)]
        (if (zero? (MemorySegment/.address item))
          (persistent! out)
          (recur (conj! out (f item))))))))


(defn item-bytes
  [^Arena arena item convert]
  (let [out (SegmentAllocator/.allocate arena FFM/SPAN)]
    (when-not (convert item out)
      (throw (ex-info "Unexpected native value type" {:type :rattrapante/native-type})))
    (bytes out)))


(defn item-long
  [^Arena arena item convert]
  (let [out (SegmentAllocator/.allocate arena ValueLayout/JAVA_LONG)]
    (when-not (convert item out)
      (throw (ex-info "Unexpected native value type" {:type :rattrapante/native-type})))
    (MemorySegment/.get out ValueLayout/JAVA_LONG 0)))


(defn item-pointer
  ^MemorySegment [^Arena arena item convert]
  (let [out (SegmentAllocator/.allocate arena ValueLayout/ADDRESS)]
    (when-not (convert item out)
      (throw (ex-info "Unexpected native value type" {:type :rattrapante/native-type})))
    (MemorySegment/.get out ValueLayout/ADDRESS 0)))


(defn item-string
  [arena item]
  (String/new ^bytes (item-bytes arena item Bindings/AMitemToStr) StandardCharsets/UTF_8))


(defn result-bytes
  [raw]
  (result raw
    (fn [arena owned _]
      (item-bytes arena (Bindings/AMresultItem owned) Bindings/AMitemToBytes))))


(defn result-saved-document
  [raw]
  (SavedDocumentSupport/takeOwnership (result-bytes raw)))


(defn object-id
  [^Arena arena ^MemorySegment obj]
  (if (zero? (MemorySegment/.address obj))
    "_root"
    (str (Long/toUnsignedString (Bindings/AMobjIdCounter obj)) "@"
      (string-value (Bindings/AMactorIdStr (alloc arena) (Bindings/AMobjIdActorId obj))))))


(defn with-items
  "Decode and combine a batch of owned items before invoking f. All Rc items
  remain on this thread and are freed together, including on decoding failure."
  [values make-item f]
  ;; Balanced concatenation avoids quadratic copying for wide head sets. All
  ;; temporary Rc references are released on this thread in the same arena.
  (with-open [arena (Arena/ofConfined)]
    (let [leaves (mapv
                   (fn [value] (check! arena (FFM/own (make-item arena value) arena)))
                   values)
          combined (loop [level leaves]
                     (if (<= (count level) 1)
                       (first level)
                       (recur
                         (mapv (fn [[left right]]
                                 (if right
                                   (check! arena (FFM/own (Bindings/AMresultCat left right) arena))
                                   left))
                           (partition-all 2 level)))))
          iter (if combined
                 (Bindings/AMresultItems (alloc arena) combined)
                 null)]
      (f iter))))


(defn hash-bytes
  ^bytes [hash]
  (let [data (unhex hash)]
    (when-not (= 32 (alength data))
      (throw (ex-info "A change hash must contain 32 bytes" {:hash hash})))
    data))


(defn with-heads
  [heads f]
  (with-items heads
    (fn [arena hash] (Bindings/AMitemFromChangeHash (span arena (hash-bytes hash)))) f))


(defn change-result
  "Strictly decode one complete encoded change."
  [arena data]
  (when-not (bytes? data)
    (throw (ex-info "An encoded change must be a byte array" {:type :rattrapante/invalid-operation})))
  (Bindings/AMrChangeFromBytes (buffer arena data) (alength ^bytes data)))
