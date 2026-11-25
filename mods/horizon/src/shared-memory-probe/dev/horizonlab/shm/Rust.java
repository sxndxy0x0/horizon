package dev.horizonlab.shm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** The Rust library's functions (crates/horizon-shm-probe), through FFM. Its path is the {@code hz.lib} property. */
final class Rust {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB =
            SymbolLookup.libraryLookup(Path.of(System.getProperty("hz.lib")), Arena.global());

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout PTR = ValueLayout.ADDRESS;

    private static final MethodHandle ALLOC = handle("hz_alloc", FunctionDescriptor.of(PTR, U32));
    private static final MethodHandle FREE = handle("hz_free", FunctionDescriptor.ofVoid(PTR, U32));
    private static final MethodHandle STORAGE_HASH = handle("hz_storage_hash", FunctionDescriptor.of(U64, PTR, U32, U32, U32));
    private static final MethodHandle CONTAINER_MISMATCHES =
            handle("hz_container_mismatches", FunctionDescriptor.of(U32, PTR, U32, U32, PTR, U32, PTR));
    private static final MethodHandle GET = handle("hz_get", FunctionDescriptor.of(U32, PTR, U32, U32));
    private static final MethodHandle SET = handle("hz_set", FunctionDescriptor.ofVoid(PTR, U32, U32, U32));

    private Rust() {}

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    /** {@code words} zeroed longs Rust owns, as a segment Java reads and writes in place. */
    static MemorySegment alloc(int words) {
        try {
            MemorySegment raw = (MemorySegment) ALLOC.invokeExact(words);
            return raw.reinterpret((long) words * Long.BYTES);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void free(MemorySegment words, int count) {
        try {
            FREE.invokeExact(words, count);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static long storageHash(MemorySegment words, int count, int bits, int size) {
        try {
            return (long) STORAGE_HASH.invokeExact(words, count, bits, size);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int containerMismatches(MemorySegment words, int count, int globalBits, int[] palette, int[] expected) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_INT, palette);
            MemorySegment e = arena.allocateFrom(ValueLayout.JAVA_INT, expected);
            return (int) CONTAINER_MISMATCHES.invokeExact(words, count, globalBits, p, palette.length, e);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int get(MemorySegment words, int bits, int index) {
        try {
            return (int) GET.invokeExact(words, bits, index);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void set(MemorySegment words, int bits, int index, int value) {
        try {
            SET.invokeExact(words, bits, index, value);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
