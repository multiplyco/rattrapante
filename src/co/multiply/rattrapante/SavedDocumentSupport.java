package co.multiply.rattrapante;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/**
 * Internal copy/ownership bridge; no public API is promised. Lives in this
 * package to access SavedDocument's owned array without exposing it to callers.
 */
public final class SavedDocumentSupport {
    private SavedDocumentSupport() {}

    /** Transfer a fresh native-save copy which has never escaped the implementation. */
    public static SavedDocument takeOwnership(byte[] freshSave) {
        return new SavedDocument(freshSave);
    }

    /** Copy directly to a caller-owned native arena, avoiding an intermediate JVM array. */
    public static MemorySegment buffer(SavedDocument saved, SegmentAllocator allocator) {
        MemorySegment buffer = allocator.allocate(Math.max(1, saved.data.length), 1);
        MemorySegment.copy(saved.data, 0, buffer, ValueLayout.JAVA_BYTE, 0, saved.data.length);
        return buffer;
    }
}
