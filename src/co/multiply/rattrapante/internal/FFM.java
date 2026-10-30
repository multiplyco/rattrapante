package co.multiply.rattrapante.internal;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Internal ABI and ownership support. No public pointer API is promised. */
@SuppressWarnings("restricted")
public final class FFM {
    public static final MemoryLayout SPAN = MemoryLayout.structLayout(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    public static final MemoryLayout ITEMS = MemoryLayout.structLayout(MemoryLayout.sequenceLayout(24, ValueLayout.JAVA_BYTE));
    private static final Linker LINKER;
    private static final SymbolLookup LIBRARY;
    private static final boolean TRACK = Boolean.getBoolean("rattrapante.track-native");
    private static final AtomicLong ALLOCATED = new AtomicLong();
    private static final AtomicLong RELEASED = new AtomicLong();
    private static final Consumer<MemorySegment> RELEASE = pointer -> {
        Bindings.AMresultFree(pointer);
        if (TRACK) RELEASED.incrementAndGet();
    };
    static {
        if (!FFM.class.getModule().isNativeAccessEnabled()) {
            throw new IllegalStateException("Rattrapante requires --enable-native-access=ALL-UNNAMED");
        }
        LINKER = Linker.nativeLinker();
        if (ValueLayout.ADDRESS.byteSize() != 8 || LINKER.canonicalLayouts().get("size_t").byteSize() != 8) {
            throw new UnsupportedOperationException("Rattrapante requires a 64-bit native ABI");
        }
        LIBRARY = SymbolLookup.libraryLookup(libraryPath(), Arena.ofAuto());
    }

    private static Path libraryPath() {
        String override = System.getProperty("rattrapante.native.path");
        if (override != null) return Path.of(override).toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        os = os.contains("mac") ? "darwin" : os.contains("linux") ? "linux" : os.contains("windows") ? "windows" : os;
        String arch = System.getProperty("os.arch");
        arch = arch.equals("arm64") ? "aarch64" : arch.equals("amd64") ? "x86_64" : arch;
        String filename = System.mapLibraryName("automerge_core");
        String resource = "/co/multiply/rattrapante/native/" + os + "-" + arch + "/" + filename;
        try (var input = FFM.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing native library " + resource + ". Run bb native, or set -Drattrapante.native.path=/absolute/path/to/library");
            Path dir = Files.createTempDirectory("rattrapante-");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve(filename);
            Files.copy(input, file);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract Rattrapante's native library", e);
        }
    }

    static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    static RuntimeException failure(Throwable t) {
        if (t instanceof Error e) throw e;
        return t instanceof RuntimeException r ? r : new IllegalStateException("Native call failed", t);
    }

    public static MemorySegment own(MemorySegment result, Arena arena) {
        if (result.address() == 0) throw new IllegalStateException("Automerge returned a null result");
        try {
            MemorySegment owned = result.reinterpret(0, arena, RELEASE);
            if (TRACK) ALLOCATED.incrementAndGet();
            return owned;
        } catch (Throwable t) {
            Bindings.AMresultFree(result);
            throw t;
        }
    }

    /** Opt-in test diagnostics; no accounting overhead in normal operation. */
    public static long liveResults() {
        if (!TRACK) throw new IllegalStateException("Enable -Drattrapante.track-native=true before loading Rattrapante");
        return ALLOCATED.get() - RELEASED.get();
    }

    /** Retain an item and attach its native allocation to an automatic scope. */
    public static MemorySegment retainItem(MemorySegment item) {
        Arena arena = Arena.ofAuto();
        MemorySegment owner = own(Bindings.AMitemResult(item), arena);
        try {
            return Bindings.AMresultItem(owner).reinterpret(0, arena, null);
        } finally {
            Reference.reachabilityFence(owner);
        }
    }

    /** Borrowed pointers inherit their retained item's automatic lifetime. */
    public static MemorySegment retainObject(MemorySegment item) {
        Arena arena = Arena.ofAuto();
        MemorySegment owner = own(Bindings.AMitemResult(item), arena);
        try {
            return Bindings.AMitemObjId(Bindings.AMresultItem(owner)).reinterpret(0, arena, null);
        } finally {
            Reference.reachabilityFence(owner);
        }
    }

    public static MemorySegment ownedPointer(MemorySegment result, int type) {
        Arena arena = Arena.ofAuto();
        MemorySegment owner = own(result, arena);
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment output = temporary.allocate(ValueLayout.ADDRESS);
            MemorySegment item = Bindings.AMresultItem(owner);
            boolean ok = switch (type) {
                case 256 -> Bindings.AMitemToDoc(item, output);
                case 131072 -> Bindings.AMitemToSyncState(item, output);
                default -> throw new IllegalArgumentException("Unsupported owning pointer type");
            };
            if (!ok) throw new IllegalStateException("Unexpected native result type");
            return output.get(ValueLayout.ADDRESS, 0).reinterpret(0, arena, null);
        } finally {
            Reference.reachabilityFence(owner);
        }
    }

    /** Ensure upstream's opaque iterator storage is sufficiently aligned. */
    public static SegmentAllocator allocator(Arena arena) {
        return (size, alignment) -> arena.allocate(size, Math.max(8, alignment));
    }
}
