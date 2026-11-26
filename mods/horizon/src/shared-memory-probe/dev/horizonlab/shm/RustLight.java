package dev.horizonlab.shm;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * P4: the light engine's propagation run by Rust ({@code crates/horizon-shm-probe/src/light.rs}) on the game's light
 * data in place. {@link Agent} makes {@code LightEngine.runLightUpdates} call {@link #propagate} where it called
 * {@code propagateDecreases}, and {@link #zero} where it called {@code propagateIncreases}; everything else in the
 * engine stays Java.
 *
 * <p>With {@code -Dhz.light=accel} the propagation runs in Rust; with {@code hz.light=time} vanilla's own runs, timed
 * the same way (for the speed comparison). Rust runs only when every light class the game defined is vanilla's own
 * bytes ({@link #VANILLA}, filled by the agent): a loader or mod that changed one keeps Java's.
 *
 * <p>What Rust needs of the world it asks for through upcalls, once per section per run: a section's light layer
 * (its bytes are Rust's memory, {@link RustDataLayer}), the light class of each of a section's blocks (a snapshot
 * {@link #snapshot} keeps per {@code LevelChunkSection}, updated by the game's own {@code setBlockState}), whether
 * light is on in a column, and whether two occlusion faces occlude.
 */
public final class RustLight {
    static final String MODE = System.getProperty("hz.light", "");
    static final boolean ACCEL = MODE.equals("accel");
    /** The classes the agent checked, and whether each was defined with vanilla's bytes (see {@link LightNames}). */
    static final Map<String, Boolean> VANILLA = LightNames.VANILLA_BYTES;
    static final String[] GUARDED = LightNames.GUARDED;

    static final AtomicLong RUNS = new AtomicLong();
    static final AtomicLong NANOS = new AtomicLong();
    static final AtomicLong ENTRIES = new AtomicLong();
    static final AtomicLong SNAPSHOTS = new AtomicLong();
    static final AtomicLong CONVERTED = new AtomicLong();
    static final AtomicLong JAVA_RUNS = new AtomicLong();
    /** Time in the upcalls, by kind: layers, blocks (snapshots made). */
    static final AtomicLong LAYER_NANOS = new AtomicLong();
    static final AtomicLong BLOCK_NANOS = new AtomicLong();
    static final AtomicLong UPCALLS = new AtomicLong();

    private static final Cleaner CLEANER = Cleaner.create();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    // The engine's and storage's own members (protected and private: the game's classes are on the class path).
    private static final VarHandle DECREASE;
    private static final VarHandle INCREASE;
    private static final VarHandle STORAGE;
    private static final VarHandle CHUNK_SOURCE;
    private static final VarHandle CHANGED;
    private static final VarHandle UPDATING;
    private static final VarHandle AFFECTED;
    private static final VarHandle LOWEST_Y;
    private static final MethodHandle GET_LAYER;
    private static final MethodHandle LIGHT_ON;
    private static final MethodHandle COPY_LAYER;
    private static final MethodHandle PROPAGATE_DECREASES;
    private static final MethodHandle PROPAGATE_INCREASES;
    private static final Class<?> SKY_STORAGE;
    // Fields the agent adds to the game's classes.
    private static final VarHandle SECTION_LIGHT;
    private static final VarHandle SECTION_VERSION;
    private static final VarHandle STATE_CLASS;

    private static final VarHandle CONTAINER_DATA;
    private static final MethodHandle DATA_STORAGE;
    private static final MethodHandle DATA_PALETTE;
    private static final MethodHandle CLASS;
    private static final MethodHandle RUN;
    private static final MethodHandle FREE_LIST;
    private static final MethodHandle STATS;
    private static final MemorySegment CALLBACKS;

    static {
        try {
            Class<?> engine = LightEngine.class;
            Class<?> storage = Class.forName("net.minecraft.world.level.lighting.LayerLightSectionStorage");
            Class<?> map = Class.forName("net.minecraft.world.level.lighting.DataLayerStorageMap");
            Class<?> skyMap = Class.forName("net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap");
            SKY_STORAGE = Class.forName("net.minecraft.world.level.lighting.SkyLightSectionStorage");
            MethodHandles.Lookup e = MethodHandles.privateLookupIn(engine, LOOKUP);
            MethodHandles.Lookup s = MethodHandles.privateLookupIn(storage, LOOKUP);
            DECREASE = e.findVarHandle(engine, "decreaseQueue", LongArrayFIFOQueue.class);
            INCREASE = e.findVarHandle(engine, "increaseQueue", LongArrayFIFOQueue.class);
            STORAGE = e.findVarHandle(engine, "storage", storage);
            CHUNK_SOURCE = e.findVarHandle(engine, "chunkSource", LightChunkGetter.class);
            PROPAGATE_DECREASES = e.findVirtual(engine, "propagateDecreases", MethodType.methodType(int.class));
            PROPAGATE_INCREASES = e.findVirtual(engine, "propagateIncreases", MethodType.methodType(int.class));
            CHANGED = s.findVarHandle(storage, "changedSections", LongSet.class);
            UPDATING = s.findVarHandle(storage, "updatingSectionData", map);
            AFFECTED = s.findVarHandle(storage, "sectionsAffectedByLightUpdates", LongSet.class);
            GET_LAYER = s.findVirtual(storage, "getDataLayer", MethodType.methodType(DataLayer.class, long.class, boolean.class));
            LIGHT_ON = s.findVirtual(storage, "lightOnInSection", MethodType.methodType(boolean.class, long.class));
            COPY_LAYER = MethodHandles.privateLookupIn(map, LOOKUP)
                    .findVirtual(map, "copyDataLayer", MethodType.methodType(DataLayer.class, long.class));
            LOWEST_Y = MethodHandles.privateLookupIn(skyMap, LOOKUP).findVarHandle(skyMap, "currentLowestY", int.class);
            SECTION_LIGHT = MethodHandles.privateLookupIn(LevelChunkSection.class, LOOKUP)
                    .findVarHandle(LevelChunkSection.class, Agent.SECTION_LIGHT, long.class);
            SECTION_VERSION = MethodHandles.privateLookupIn(LevelChunkSection.class, LOOKUP)
                    .findVarHandle(LevelChunkSection.class, Agent.SECTION_VERSION, int.class);
            Class<?> base = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            STATE_CLASS = MethodHandles.privateLookupIn(base, LOOKUP).findVarHandle(base, Agent.STATE_CLASS, int.class);
            Class<?> data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            CONTAINER_DATA = MethodHandles.privateLookupIn(PalettedContainer.class, LOOKUP)
                    .findVarHandle(PalettedContainer.class, "data", data);
            MethodHandles.Lookup d = MethodHandles.privateLookupIn(data, LOOKUP);
            DATA_STORAGE = d.findVirtual(data, "storage", MethodType.methodType(BitStorage.class));
            DATA_PALETTE = d.findVirtual(data, "palette", MethodType.methodType(Palette.class));

            SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(System.getProperty("hz.lib")), Arena.global());
            ValueLayout.OfInt i32 = ValueLayout.JAVA_INT;
            ValueLayout.OfLong i64 = ValueLayout.JAVA_LONG;
            ValueLayout ptr = ValueLayout.ADDRESS;
            CLASS = LINKER.downcallHandle(lib.find("hz_light_class").orElseThrow(),
                    FunctionDescriptor.ofVoid(i32, i32, i32, i32, ptr));
            RUN = LINKER.downcallHandle(lib.find("hz_light_run").orElseThrow(),
                    FunctionDescriptor.of(i32, ptr, i32, i32, i32, i32, ptr, i32, ptr, i32, ptr, ptr));
            FREE_LIST = LINKER.downcallHandle(lib.find("hz_light_free").orElseThrow(), FunctionDescriptor.ofVoid(ptr, i32));
            STATS = LINKER.downcallHandle(lib.find("hz_light_nanos").orElseThrow(), FunctionDescriptor.of(i64));
            CALLBACKS = Arena.global().allocate(ValueLayout.ADDRESS, 4);
            CALLBACKS.setAtIndex(ValueLayout.ADDRESS, 0, upcall("layer", FunctionDescriptor.of(i64, i64, i32),
                    MethodType.methodType(long.class, long.class, int.class)));
            CALLBACKS.setAtIndex(ValueLayout.ADDRESS, 1, upcall("blocks", FunctionDescriptor.of(i64, i64),
                    MethodType.methodType(long.class, long.class)));
            CALLBACKS.setAtIndex(ValueLayout.ADDRESS, 2, upcall("lightOn", FunctionDescriptor.of(i32, i64),
                    MethodType.methodType(int.class, long.class)));
            CALLBACKS.setAtIndex(ValueLayout.ADDRESS, 3, upcall("occludes", FunctionDescriptor.of(i32, i32, i32),
                    MethodType.methodType(int.class, int.class, int.class)));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RustLight() {}

    private static MemorySegment upcall(String name, FunctionDescriptor d, MethodType t)
            throws ReflectiveOperationException {
        return LINKER.upcallStub(LOOKUP.findStatic(RustLight.class, name, t), d, Arena.global());
    }

    /**
     * Whether Rust may run the propagation: accelerating, every guarded class vanilla's own, and every block state's
     * light class fitting the snapshots' byte ({@link #allClassesFit}).
     */
    static boolean rustRuns() {
        if (!ACCEL) {
            return false;
        }
        for (String c : GUARDED) {
            if (!Boolean.TRUE.equals(VANILLA.get(c))) {
                return false;
            }
        }
        return allClassesFit();
    }

    private static volatile Boolean fit;

    /**
     * Names the light class of every block state the game has (its states are all registered by the time light
     * runs), once: no class is named after this, so a snapshot's byte a block can hold 256 classes. A game with
     * more keeps Java's propagation.
     */
    static boolean allClassesFit() {
        Boolean f = fit;
        if (f == null) {
            globalClasses();
            synchronized (CLASS_IDS) {
                f = CLASS_IDS.size() <= 256;
            }
            if (!f) {
                System.out.println("[shm] " + CLASS_IDS.size() + " light classes: more than a byte holds, light stays Java");
            }
            fit = f;
        }
        return f;
    }

    // -- The engine ----------------------------------------------------------------------------------------------

    /** One run's state, for the upcalls (on the same thread). */
    private static final class Run {
        final Object storage;
        final LightChunkGetter chunks;
        final LongSet changed;
        final Object updating;
        Run(Object storage, LightChunkGetter chunks) {
            this.storage = storage;
            this.chunks = chunks;
            this.changed = (LongSet) CHANGED.get(storage);
            this.updating = UPDATING.get(storage);
        }
    }

    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    /** In place of {@code propagateDecreases()}: both propagations, the entries dequeued. */
    public static int propagate(LightEngine<?, ?> engine) throws Throwable {
        long start = System.nanoTime();
        int count;
        if (!rustRuns()) {
            count = (int) PROPAGATE_DECREASES.invoke(engine) + (int) PROPAGATE_INCREASES.invoke(engine);
            JAVA_RUNS.incrementAndGet();
        } else {
            count = rust(engine);
        }
        NANOS.addAndGet(System.nanoTime() - start);
        RUNS.incrementAndGet();
        ENTRIES.addAndGet(count);
        return count;
    }

    /** In place of {@code propagateIncreases()}: {@link #propagate} did it. */
    public static int zero(LightEngine<?, ?> engine) {
        return 0;
    }

    private static final java.util.Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private static int rust(LightEngine<?, ?> engine) throws Throwable {
        if (ANNOUNCED.add(Thread.currentThread().getName())) {
            System.out.println("[shm] light: Rust propagates on " + Thread.currentThread().getName());
        }
        LongArrayFIFOQueue decrease = (LongArrayFIFOQueue) DECREASE.get(engine);
        LongArrayFIFOQueue increase = (LongArrayFIFOQueue) INCREASE.get(engine);
        if (decrease.isEmpty() && increase.isEmpty()) {
            return 0;
        }
        Object storage = STORAGE.get(engine);
        Run run = new Run(storage, (LightChunkGetter) CHUNK_SOURCE.get(engine));
        boolean sky = SKY_STORAGE.isInstance(storage);
        int lowestY = sky ? (int) LOWEST_Y.get(run.updating) : 0;
        Run before = CURRENT.get();
        CURRENT.set(run);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dec = drain(decrease, arena);
            MemorySegment inc = drain(increase, arena);
            MemorySegment list = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment listLen = arena.allocate(ValueLayout.JAVA_INT);
            int count = (int) RUN.invokeExact(CALLBACKS, sky ? 1 : 0, lowestY, AIR, BEDROCK,
                    dec, (int) (dec.byteSize() / 8), inc, (int) (inc.byteSize() / 8), list, listLen);
            int n = listLen.get(ValueLayout.JAVA_INT, 0);
            MemorySegment affected = list.get(ValueLayout.ADDRESS, 0).reinterpret(8L * n);
            LongSet into = (LongSet) AFFECTED.get(storage);
            for (int i = 0; i < n; i++) {
                into.add(affected.getAtIndex(ValueLayout.JAVA_LONG, i));
            }
            FREE_LIST.invokeExact(list.get(ValueLayout.ADDRESS, 0), n);
            return count;
        } finally {
            CURRENT.set(before);
        }
    }

    private static MemorySegment drain(LongArrayFIFOQueue queue, Arena arena) {
        MemorySegment out = arena.allocate(8L * queue.size(), 8);
        int i = 0;
        while (!queue.isEmpty()) {
            out.setAtIndex(ValueLayout.JAVA_LONG, i++, queue.dequeueLong());
        }
        return out;
    }

    // -- Upcalls -------------------------------------------------------------------------------------------------

    /** See {@code Callbacks.layer} in light.rs. */
    static long layer(long section, int write) {
        long t0 = System.nanoTime();
        UPCALLS.incrementAndGet();
        try {
            Run run = CURRENT.get();
            DataLayer layer;
            if (write != 0) {
                // LayerLightSectionStorage.setStoredLevel's copy-on-write.
                layer = run.changed.add(section) ? (DataLayer) COPY_LAYER.invoke(run.updating, section)
                        : (DataLayer) GET_LAYER.invoke(run.storage, section, true);
                return rustLayer(run, section, layer).writable();
            }
            layer = (DataLayer) GET_LAYER.invoke(run.storage, section, true);
            if (layer == null) {
                return 0;
            }
            RustDataLayer r = rustLayer(run, section, layer);
            long at = r.address();
            return at != 0 ? at : -(r.defaultLevel() + 1L);
        } catch (Throwable t) {
            throw fail(t);
        } finally {
            LAYER_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    /** The layer as one in Rust's memory: every layer is, under the agent; one that is not is replaced by a copy. */
    private static RustDataLayer rustLayer(Run run, long section, DataLayer layer) throws Throwable {
        if (layer instanceof RustDataLayer r) {
            return r;
        }
        CONVERTED.incrementAndGet();
        RustDataLayer r = (RustDataLayer) (layer.isDefinitelyHomogenous() ? RustDataLayer.create(layer.get(0, 0, 0))
                : RustDataLayer.create(layer.getData()));
        MethodHandles.privateLookupIn(run.updating.getClass().getSuperclass(), LOOKUP)
                .findVirtual(run.updating.getClass().getSuperclass(), "setLayer",
                        MethodType.methodType(void.class, long.class, DataLayer.class))
                .invoke(run.updating, section, (DataLayer) r);
        return r;
    }

    /** See {@code Callbacks.blocks} in light.rs. */
    static long blocks(long section) {
        long t0 = System.nanoTime();
        UPCALLS.incrementAndGet();
        try {
            Run run = CURRENT.get();
            int sx = SectionPos.x(section);
            int sy = SectionPos.y(section);
            int sz = SectionPos.z(section);
            LightChunk chunk = run.chunks.getChunkForLighting(sx, sz);
            if (chunk == null) {
                return -1;
            }
            if (!(chunk instanceof ChunkAccess access)) {
                return copy(chunk, sx, sy, sz);
            }
            int index = access.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= access.getSectionsCount()) {
                return 0;
            }
            LevelChunkSection s = access.getSection(index);
            return s.hasOnlyAir() ? 0 : snapshot(s);
        } catch (Throwable t) {
            throw fail(t);
        } finally {
            BLOCK_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    static int lightOn(long section) {
        try {
            return (boolean) LIGHT_ON.invoke(CURRENT.get().storage, section) ? 1 : 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static int occludes(int a, int b) {
        return Shapes.faceShapeOccludes(FACES.get(a), FACES.get(b)) ? 1 : 0;
    }

    private static RuntimeException fail(Throwable t) {
        // An upcall must not throw into Rust: the JVM would end. Print and halt, as a crash would.
        System.out.println("[shm] light upcall failed");
        t.printStackTrace(System.out);
        System.out.flush();
        Runtime.getRuntime().halt(5);
        return new IllegalStateException(t);
    }

    // -- Block snapshots -----------------------------------------------------------------------------------------

    private static final int CELLS = 4096;
    private static final int WORDS = CELLS / 8;
    /** Snapshots of chunks that are not {@code ChunkAccess} (none in vanilla), kept for the life of the game. */
    private static final List<MemorySegment> COPIES = new ArrayList<>();

    /**
     * The section's light classes, a byte per block, made on first use and then kept up to date by
     * {@link #blockSet}. The section's version guards the making against a block set at the same time: it is
     * filled again until no set came in while it was being filled, and a set after it is published writes the
     * snapshot itself.
     */
    static long snapshot(LevelChunkSection s) {
        long at = (long) SECTION_LIGHT.getVolatile(s);
        if (at != 0) {
            return at;
        }
        MemorySegment cells = Rust.alloc(WORDS);
        long address = cells.address();
        if (!SECTION_LIGHT.compareAndSet(s, 0L, address)) {
            Rust.free(cells, WORDS);
            return (long) SECTION_LIGHT.getVolatile(s);
        }
        CLEANER.register(s, () -> Rust.free(cells, WORDS));
        SNAPSHOTS.incrementAndGet();
        int version;
        do {
            version = (int) SECTION_VERSION.getVolatile(s);
            fill(s, address);
        } while ((int) SECTION_VERSION.getVolatile(s) != version);
        return address;
    }

    /**
     * The section's classes, in its storage's order (the container's index is {@code y << 8 | z << 4 | x}, as the
     * snapshot's): each palette entry's class worked out once, then every cell read off the storage in turn.
     */
    private static void fill(LevelChunkSection s, long address) {
        try {
            Object data = CONTAINER_DATA.getVolatile(s.getStates());
            BitStorage storage = (BitStorage) DATA_STORAGE.invoke(data);
            @SuppressWarnings("unchecked")
            Palette<BlockState> palette = (Palette<BlockState>) DATA_PALETTE.invoke(data);
            short[] map = palette instanceof GlobalPalette ? globalClasses() : new short[palette.getSize()];
            if (!(palette instanceof GlobalPalette)) {
                for (int i = 0; i < map.length; i++) {
                    map[i] = (short) classOf(palette.valueFor(i));
                }
            }
            if (storage.getSize() != CELLS) {
                throw new IllegalStateException("a block storage of " + storage.getSize() + " cells");
            }
            int[] at = {0};
            // The server thread may add to the palette while this reads (the version loop in snapshot() fills
            // again then): an entry past the size read above is looked up as it comes.
            storage.getAll(id -> Rust.ALL.set(ValueLayout.JAVA_BYTE, address + at[0]++,
                    (byte) (id < map.length ? map[id] : classOf(palette.valueFor(id)))));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static volatile short[] globalClasses;

    /** Every block state's class by its id in {@code Block.BLOCK_STATE_REGISTRY} (the global palette's ids). */
    private static short[] globalClasses() {
        short[] g = globalClasses;
        if (g == null) {
            g = new short[Block.BLOCK_STATE_REGISTRY.size()];
            for (int i = 0; i < g.length; i++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
                g[i] = (short) (state == null ? AIR : classOf(state));
            }
            globalClasses = g;
        }
        return g;
    }

    private static long copy(LightChunk chunk, int sx, int sy, int sz) {
        MemorySegment cells = Arena.global().allocate(CELLS, 8);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);
                    cells.set(ValueLayout.JAVA_BYTE, y << 8 | z << 4 | x, (byte) classOf(chunk.getBlockState(pos)));
                }
            }
        }
        synchronized (COPIES) {
            COPIES.add(cells);
        }
        return cells.address();
    }

    /** After {@code LevelChunkSection.setBlockState} (the agent's hook): the snapshot follows the block. */
    public static void blockSet(LevelChunkSection s, int x, int y, int z, BlockState state) {
        SECTION_VERSION.getAndAdd(s, 1);
        long at = (long) SECTION_LIGHT.getVolatile(s);
        if (at != 0) {
            Rust.ALL.set(ValueLayout.JAVA_BYTE, at + (y << 8 | z << 4 | x), (byte) classOf(state));
        }
    }

    /** After {@code LevelChunkSection.read} (the client, from the network): every block may have changed. */
    public static void sectionRead(LevelChunkSection s) {
        SECTION_VERSION.getAndAdd(s, 1);
        long at = (long) SECTION_LIGHT.getVolatile(s);
        if (at != 0) {
            fill(s, at);
        }
    }

    // -- Light classes -------------------------------------------------------------------------------------------

    /** Occlusion faces by id: 0 {@code Shapes.empty()}, 1 {@code Shapes.block()}, then each other face met. */
    private static final List<VoxelShape> FACES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<VoxelShape, Integer> FACE_IDS = new IdentityHashMap<>();
    private static final Map<List<Integer>, Integer> CLASS_IDS = new HashMap<>();
    static final int AIR;
    static final int BEDROCK;

    static {
        FACES.add(Shapes.empty());
        FACES.add(Shapes.block());
        FACE_IDS.put(Shapes.block(), 1);
        AIR = classOf(Blocks.AIR.defaultBlockState());
        BEDROCK = classOf(Blocks.BEDROCK.defaultBlockState());
    }

    private static int faceId(VoxelShape shape) {
        if (shape.isEmpty()) {
            return 0;
        }
        return FACE_IDS.computeIfAbsent(shape, k -> {
            FACES.add(k);
            return FACES.size() - 1;
        });
    }

    /**
     * The state's light class (what {@code LightEngine} asks of a block: emission, dampening, whether its shape
     * counts, its occlusion face each way), named the first time a state is met and kept in the field the agent
     * adds to {@code BlockStateBase} (the class plus one; 0 while unnamed).
     */
    static int classOf(BlockState state) {
        int c = (int) STATE_CLASS.get(state);
        if (c != 0) {
            return c - 1;
        }
        synchronized (CLASS_IDS) {
            c = (int) STATE_CLASS.get(state);
            if (c != 0) {
                return c - 1;
            }
            boolean empty = !state.canOcclude() || !state.useShapeForLightOcclusion();
            int[] faces = new int[6];
            if (!empty) {
                for (Direction d : Direction.values()) {
                    faces[d.ordinal()] = faceId(state.getFaceOcclusionShape(d));
                }
            }
            List<Integer> key = List.of(state.getLightEmission(), state.getLightDampening(), empty ? 1 : 0,
                    faces[0], faces[1], faces[2], faces[3], faces[4], faces[5]);
            Integer id = CLASS_IDS.get(key);
            if (id == null) {
                id = CLASS_IDS.size();
                CLASS_IDS.put(key, id);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment f = arena.allocateFrom(ValueLayout.JAVA_INT, faces);
                    CLASS.invokeExact((int) id, state.getLightEmission(), state.getLightDampening(), empty ? 1 : 0, f);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }
            STATE_CLASS.set(state, id + 1);
            return id;
        }
    }

    private static long rustMillis() {
        try {
            return (long) STATS.invokeExact() / 1_000_000;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** For the world probe's output. */
    static String report() {
        return "light\t" + MODE + "\truns " + RUNS.get() + "\tjava runs " + JAVA_RUNS.get() + "\tentries " + ENTRIES.get()
                + "\tms " + NANOS.get() / 1_000_000 + "\tlayer upcalls ms " + LAYER_NANOS.get() / 1_000_000
                + "\tblock upcalls ms " + BLOCK_NANOS.get() / 1_000_000 + "\trust ms " + rustMillis() + "\tupcalls " + UPCALLS.get() + "\tsnapshots " + SNAPSHOTS.get() + "\tclasses " + CLASS_IDS.size()
                + "\tfaces " + FACES.size() + "\tconverted " + CONVERTED.get() + "\tvanilla classes " + VANILLA;
    }
}
