package dev.horizonlab.accel;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

/** The Rust library's functions (crates/horizon-shm-probe), through FFM. Its path is the {@code hz.lib} property. */
public final class Rust {
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
    private static final MethodHandle LIGHT_HASH = handle("hz_light_hash", FunctionDescriptor.of(U64, PTR));
    private static final MethodHandle LEVEL_NEW = handle("hz_level_new", FunctionDescriptor.of(PTR));
    private static final MethodHandle LEVEL_FREE = handle("hz_level_free", FunctionDescriptor.ofVoid(PTR));
    private static final MethodHandle LEVEL_BLOCKS = handle("hz_level_blocks",
            FunctionDescriptor.ofVoid(PTR, U32, U32, U32, PTR, U32, U32, PTR, U32, U32));
    private static final MethodHandle LEVEL_LIGHT = handle("hz_level_light",
            FunctionDescriptor.ofVoid(PTR, U32, U32, U32, U32, PTR, U32));
    private static final MethodHandle LEVEL_HEIGHTS = handle("hz_level_heights",
            FunctionDescriptor.ofVoid(PTR, U32, U32, U32, PTR, U32, U32));
    private static final MethodHandle LEVEL_HASH = handle("hz_level_hash",
            FunctionDescriptor.ofVoid(PTR, PTR, U32, U32, U32, U32, U32, U32, PTR));
    private static final MethodHandle LEVEL_STATE = handle("hz_level_state", FunctionDescriptor.of(U64, PTR, U32, U32, U32));
    private static final MethodHandle LEVEL_SET = handle("hz_level_set", FunctionDescriptor.of(U64, PTR, U32, U32, U32, U32));
    private static final MethodHandle REGISTRY_CHECK = handle("hz_registry_check",
            FunctionDescriptor.of(U64, PTR, U32, PTR, U32));

    /**
     * Every address, as one segment. It is a constant, so the JIT folds its bounds and liveness checks away and a
     * read through it costs what a heap array read does; each storage checks its own index first (as vanilla's do)
     * and reads only its own words.
     */
    public static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private Rust() {}

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    /** {@code words} zeroed longs Rust owns, as a segment Java reads and writes in place. */
    public static MemorySegment alloc(int words) {
        try {
            MemorySegment raw = (MemorySegment) ALLOC.invokeExact(words);
            return raw.reinterpret((long) words * Long.BYTES);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void free(MemorySegment words, int count) {
        try {
            FREE.invokeExact(words, count);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static long storageHash(MemorySegment words, int count, int bits, int size) {
        try {
            return (long) STORAGE_HASH.invokeExact(words, count, bits, size);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static int containerMismatches(MemorySegment words, int count, int globalBits, int[] palette, int[] expected) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_INT, palette);
            MemorySegment e = arena.allocateFrom(ValueLayout.JAVA_INT, expected);
            return (int) CONTAINER_MISMATCHES.invokeExact(words, count, globalBits, p, palette.length, e);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static int get(MemorySegment words, int bits, int index) {
        try {
            return (int) GET.invokeExact(words, bits, index);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void set(MemorySegment words, int bits, int index, int value) {
        try {
            SET.invokeExact(words, bits, index, value);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static long lightHash(long address) {
        try {
            return (long) LIGHT_HASH.invokeExact(MemorySegment.ofAddress(address));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // Horizon's view of the live world (crates/horizon-shm-probe/src/level.rs).

    public static MemorySegment levelNew() {
        try {
            return (MemorySegment) LEVEL_NEW.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void levelFree(MemorySegment level) {
        try {
            LEVEL_FREE.invokeExact(level);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void levelBlocks(MemorySegment level, int x, int y, int z, MemorySegment words, int count, int bits,
            MemorySegment palette, int paletteLen, int global) {
        try {
            LEVEL_BLOCKS.invokeExact(level, x, y, z, words, count, bits, palette, paletteLen, global);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void levelLight(MemorySegment level, int x, int y, int z, int sky, MemorySegment bytes, int dflt) {
        try {
            LEVEL_LIGHT.invokeExact(level, x, y, z, sky, bytes, dflt);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void levelHeights(MemorySegment level, int x, int z, int kind, MemorySegment words, int count, int bits) {
        try {
            LEVEL_HEIGHTS.invokeExact(level, x, z, kind, words, count, bits);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Each chunk's {blocks, block light, sky light, heightmaps} hashes, as Horizon reads them. */
    public static long[] levelHash(MemorySegment level, List<ChunkPos> chunks, int minY, int maxY, int minLight,
            int maxLight, int threads) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pairs = arena.allocate(ValueLayout.JAVA_INT, 2L * chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                pairs.setAtIndex(ValueLayout.JAVA_INT, 2L * i, chunks.get(i).x());
                pairs.setAtIndex(ValueLayout.JAVA_INT, 2L * i + 1, chunks.get(i).z());
            }
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, 4L * chunks.size());
            LEVEL_HASH.invokeExact(level, pairs, chunks.size(), minY, maxY, minLight, maxLight, threads, out);
            return out.toArray(ValueLayout.JAVA_LONG);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static long levelState(MemorySegment level, int x, int y, int z) {
        try {
            return (long) LEVEL_STATE.invokeExact(level, x, y, z);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static long levelSet(MemorySegment level, int x, int y, int z, int state) {
        try {
            return (long) LEVEL_SET.invokeExact(level, x, y, z, state);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** How many states Horizon's registry (facts.json in {@code root}) names differently from the game. */
    public static long registryCheck(String root, String states) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] r = root.getBytes(StandardCharsets.UTF_8);
            byte[] s = states.getBytes(StandardCharsets.UTF_8);
            return (long) REGISTRY_CHECK.invokeExact(arena.allocateFrom(ValueLayout.JAVA_BYTE, r), r.length,
                    arena.allocateFrom(ValueLayout.JAVA_BYTE, s), s.length);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
