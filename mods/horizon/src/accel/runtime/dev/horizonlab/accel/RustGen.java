package dev.horizonlab.accel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * P6 ({@code docs/design/java-mods-everything.md}): {@code NoiseBasedChunkGenerator.buildTerrain} (noise and aquifers,
 * material surface, carvers) by Horizon's Rust port, {@code crates/horizon-shm-probe/src/worldgen.rs}.
 *
 * <p>The agent renames the game's method to {@code hz$javaBuildTerrain} and gives {@code buildTerrain} to
 * {@link #buildTerrain}, which hands a chunk to Rust only when the game would make the same terrain:
 * <ul>
 *   <li>the generator is vanilla's own class, with vanilla's settings and biome source for its dimension;</li>
 *   <li>every registry the terrain reads (noise settings, density functions, noises, carvers, material rules and
 *   conditions, biome parameter lists, and the carvers and climate of biomes) holds vanilla's entries as the jar has
 *   them: no data pack or mod changed them ({@link #registriesVanilla});</li>
 *   <li>the classes that make terrain are the jar's ({@code hz.gen} guards, recorded by the agent);</li>
 *   <li>no old chunk to blend with, no below-zero retrogen, and no structure that adapts the terrain near the chunk
 *   (the Rust generator runs without structures).</li>
 * </ul>
 * Any other chunk is the game's own. With {@code hz.gen=check} every chunk Rust could make is made by both, and they
 * are compared block by block, with their postprocessing and heightmaps.
 */
public final class RustGen {
    private static final String MODE = System.getProperty("hz.gen", "");
    private static final String RESOURCES = System.getProperty("hz.gen.resources", "");
    private static final MemorySegment RESOURCES_UTF8;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout PTR = ValueLayout.ADDRESS;
    private static final ValueLayout I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout I64 = ValueLayout.JAVA_LONG;
    private static final MethodHandle OPEN;
    private static final MethodHandle STATE_COUNT;
    private static final MethodHandle STATE_NAMES;
    private static final MethodHandle LEVEL;
    private static final MethodHandle TERRAIN;
    private static final MethodHandle ERROR;
    private static final MethodHandle NANOS;
    private static final MethodHandle BIOME_NAMES;

    static {
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(System.getProperty("hz.lib")), Arena.global());
        OPEN = handle(lib, "hz_gen_open", FunctionDescriptor.of(PTR, PTR, I64, PTR, I64, PTR, I64, I64));
        STATE_COUNT = handle(lib, "hz_gen_state_count", FunctionDescriptor.of(I32, PTR, I64));
        STATE_NAMES = handle(lib, "hz_gen_state_names", FunctionDescriptor.of(I64, PTR, I64, PTR, I64));
        LEVEL = handle(lib, "hz_gen_level", FunctionDescriptor.ofVoid(PTR, PTR));
        TERRAIN = handle(lib, "hz_gen_terrain", FunctionDescriptor.of(I32, PTR, I32, I32, PTR, PTR, I64, PTR, PTR));
        BIOME_NAMES = handle(lib, "hz_gen_biome_names", FunctionDescriptor.of(I64, PTR, PTR, I64));
        ERROR = handle(lib, "hz_gen_error", FunctionDescriptor.of(I32, PTR, I64));
        NANOS = handle(lib, "hz_gen_nanos", FunctionDescriptor.of(I64, PTR));
        RESOURCES_UTF8 = Arena.global().allocateFrom(RESOURCES, StandardCharsets.UTF_8);
    }

    private static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(), d);
    }

    /** The game's own {@code buildTerrain}, renamed by the agent. */
    private static final MethodHandle JAVA;

    static {
        try {
            JAVA = MethodHandles.publicLookup().findVirtual(NoiseBasedChunkGenerator.class, "hz$javaBuildTerrain",
                    MethodType.methodType(CompletableFuture.class, ChunkAccess.class, Blender.class, RandomState.class,
                            StructureManager.class, BiomeManager.class, WorldGenRegion.class, Set.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The classes and methods the terrain runs (the agent's {@code Rewrites.GEN_GUARDED}). */
    public static final String[] GUARDED = {
        "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.buildTerrain(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/server/level/WorldGenRegion;Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;",
        "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.lambda$buildTerrain$0(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/NoiseSettings;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/server/level/WorldGenRegion;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.doFill(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        "net/minecraft/world/level/levelgen/NoiseChunk",
        "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer",
        "net/minecraft/world/level/levelgen/material/MaterialSystem",
        "net/minecraft/world/level/levelgen/Beardifier",
        // Not createClimateSampler: biomes are the game's, and each chunk's are compared with Rust's (Fabric API
        // keeps the seed on the sampler there for its biome API).
        "net/minecraft/world/level/levelgen/RandomState!createClimateSampler",
        "net/minecraft/world/level/levelgen/carver/WorldCarver",
        "net/minecraft/world/level/levelgen/carver/CaveWorldCarver",
        "net/minecraft/world/level/levelgen/carver/CanyonWorldCarver",
    };

    /** Rust's state ids to the game's states; null until checked, empty when they disagree. */
    private static volatile BlockState[] states;
    /** Why Rust makes nothing at all, once known; null while it may. */
    private static volatile String off;
    /** The Rust generator of each level (null: the level is the game's own), and why not. */
    private static final Map<ServerLevel, MemorySegment> GENERATORS = new ConcurrentHashMap<>();
    /** Each Rust generator's biome ids as the level's biomes. */
    private static final Map<MemorySegment, Object[]> BIOMES = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, String> JAVA_LEVELS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> JAVA_WHY = new ConcurrentHashMap<>();
    public static final AtomicLong RUST = new AtomicLong();
    public static final AtomicLong CHECKED = new AtomicLong();
    public static final AtomicLong DIFFERENT = new AtomicLong();
    private static final AtomicLong JAVA_NANOS = new AtomicLong();
    private static final AtomicLong JAVA_CHUNKS = new AtomicLong();
    private static final AtomicLong REPORTED = new AtomicLong();

    private RustGen() {}

    /** {@code NoiseBasedChunkGenerator.buildTerrain}: Rust's terrain when it is the game's, else the game's own. */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<ChunkAccess> buildTerrain(NoiseBasedChunkGenerator generator, ChunkAccess chunk,
            Blender blender, RandomState randomState, StructureManager structures, BiomeManager biomes,
            WorldGenRegion region, Set<?> possibleBiomes) {
        MemorySegment rust = eligible(generator, chunk, blender, structures, region);
        if (rust != null && !MODE.equals("check")) {
            return CompletableFuture.supplyAsync(() -> {
                ChunkAccess made = rust(rust, chunk);
                if (made != null) {
                    return made;
                }
                return java(generator, chunk, blender, randomState, structures, biomes, region, possibleBiomes).join();
            }, Util.backgroundExecutor().forName("buildTerrain"));
        }
        long start = System.nanoTime();
        CompletableFuture<ChunkAccess> game =
                java(generator, chunk, blender, randomState, structures, biomes, region, possibleBiomes);
        if (rust == null) {
            return game;
        }
        return game.thenApply(c -> {
            JAVA_NANOS.addAndGet(System.nanoTime() - start);
            JAVA_CHUNKS.incrementAndGet();
            compare(rust, c);
            return c;
        });
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ChunkAccess> java(NoiseBasedChunkGenerator generator, ChunkAccess chunk,
            Blender blender, RandomState randomState, StructureManager structures, BiomeManager biomes,
            WorldGenRegion region, Set<?> possibleBiomes) {
        try {
            return (CompletableFuture<ChunkAccess>) JAVA.invokeExact(generator, chunk, blender, randomState, structures,
                    biomes, region, possibleBiomes);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void javaBecause(String why) {
        JAVA_WHY.computeIfAbsent(why, k -> new AtomicLong()).incrementAndGet();
    }

    /** The level's Rust generator when this chunk's terrain may be Rust's, else null. */
    static MemorySegment eligible(NoiseBasedChunkGenerator generator, ChunkAccess chunk, Blender blender,
            StructureManager structures, WorldGenRegion region) {
        if (off != null || region == null) {
            return null;
        }
        if (blender != Blender.empty()) {
            javaBecause("blending with old chunks");
            return null;
        }
        if (!(chunk instanceof ProtoChunk proto) || proto.getBelowZeroRetrogen() != null) {
            javaBecause("an upgraded chunk");
            return null;
        }
        if (Beardifier.forStructuresInChunk(structures, chunk.getPos()) != Beardifier.EMPTY) {
            javaBecause("a structure adapts the terrain");
            return null;
        }
        ServerLevel level = region.getLevel();
        MemorySegment g = GENERATORS.get(level);
        if (g == null) {
            if (JAVA_LEVELS.containsKey(level)) {
                return null;
            }
            g = open(level, generator, region);
            if (g == null) {
                return null;
            }
        }
        return g;
    }

    /** The level's Rust generator, or null (and why, once) when its terrain is not vanilla's. */
    private static synchronized MemorySegment open(ServerLevel level, NoiseBasedChunkGenerator generator,
            WorldGenRegion region) {
        MemorySegment known = GENERATORS.get(level);
        if (known != null || JAVA_LEVELS.containsKey(level)) {
            return known;
        }
        String why = null;
        String preset = null;
        String dimension = level.dimension().identifier().toString();
        long t0 = System.nanoTime();
        long tStates = 0;
        long tGuards = 0;
        long tRegistries = 0;
        try {
            if (states == null) {
                states = checkStates();
            }
            tStates = System.nanoTime();
            if (states.length == 0) {
                why = "Rust's block states are not the game's";
            } else if (generator.getClass() != NoiseBasedChunkGenerator.class) {
                why = "the generator is " + generator.getClass().getName();
            } else {
                preset = preset(level, generator);
                if (preset == null) {
                    why = "not vanilla's settings or biomes for " + dimension;
                }
            }
            if (why == null) {
                why = unguarded();
            }
            tGuards = System.nanoTime();
            // Rust builds the generator (seeded noises and caches) on another thread while the registries are
            // compared here: neither waits for the other.
            CompletableFuture<MemorySegment> opened = null;
            if (why == null) {
                String presetName = preset;
                long seed = level.getSeed();
                opened = CompletableFuture.supplyAsync(() -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment p = arena.allocateFrom(presetName, StandardCharsets.UTF_8);
                        MemorySegment d = arena.allocateFrom(dimension, StandardCharsets.UTF_8);
                        return (MemorySegment) OPEN.invokeExact(RESOURCES_UTF8, RESOURCES_UTF8.byteSize() - 1,
                                p, p.byteSize() - 1, d, d.byteSize() - 1, seed);
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                }, Util.backgroundExecutor());
                why = registriesVanilla(region.registryAccess());
            }
            tRegistries = System.nanoTime();
            if (why == null) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment g = opened.join();
                    if (g.address() == 0) {
                        why = "Rust could not open it: " + error();
                    } else {
                        MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, 2);
                        LEVEL.invokeExact(g, out);
                        int minY = out.getAtIndex(ValueLayout.JAVA_INT, 0);
                        int height = out.getAtIndex(ValueLayout.JAVA_INT, 1);
                        if (minY != level.getMinY() || height != level.getHeight()) {
                            why = "its height is not the generator's (" + level.getMinY() + ", " + level.getHeight()
                                    + " vs " + minY + ", " + height + ")";
                        } else {
                            BIOMES.put(g, biomes(g, level));
                            GENERATORS.put(level, g);
                            System.out.println("[horizon-gen] " + dimension + " (" + preset + "): terrain in Rust"
                                    + (MODE.equals("check") ? ", checked against the game's" : "")
                                    + String.format(" (ready in %d ms: states %d, guards %d, registries %d, open %d)",
                                            (System.nanoTime() - t0) / 1_000_000, (tStates - t0) / 1_000_000,
                                            (tGuards - tStates) / 1_000_000, (tRegistries - tGuards) / 1_000_000,
                                            (System.nanoTime() - tRegistries) / 1_000_000));
                            return g;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            why = "could not check: " + t;
        }
        JAVA_LEVELS.put(level, why);
        System.out.println("[horizon-gen] " + dimension + " stays Java: " + why);
        return null;
    }

    /** The Rust world preset of a vanilla dimension with vanilla's settings and biome source, else null. */
    private static String preset(ServerLevel level, NoiseBasedChunkGenerator generator) {
        var dimension = level.dimension();
        var biomes = generator.getBiomeSource();
        if (dimension == Level.OVERWORLD) {
            if (!(biomes instanceof MultiNoiseBiomeSource m && m.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD))) {
                return null;
            }
            if (generator.stable(NoiseGeneratorSettings.OVERWORLD)) {
                return "minecraft:normal";
            }
            if (generator.stable(NoiseGeneratorSettings.LARGE_BIOMES)) {
                return "minecraft:large_biomes";
            }
            if (generator.stable(NoiseGeneratorSettings.AMPLIFIED)) {
                return "minecraft:amplified";
            }
            return null;
        }
        if (dimension == Level.NETHER) {
            return biomes instanceof MultiNoiseBiomeSource m && m.stable(MultiNoiseBiomeSourceParameterLists.NETHER)
                    && generator.stable(NoiseGeneratorSettings.NETHER) ? "minecraft:normal" : null;
        }
        if (dimension == Level.END) {
            return biomes instanceof TheEndBiomeSource && generator.stable(NoiseGeneratorSettings.END)
                    ? "minecraft:normal" : null;
        }
        return null;
    }

    /** What a loader or mod changed of {@link #GUARDED}, or null. */
    private static String unguarded() {
        // The agent records a class as it is defined: those the game has not needed yet (the terrain's own, before
        // its first chunk) are loaded here first.
        for (String g : GUARDED) {
            String name = (g.contains(".") ? g.substring(0, g.indexOf('.')) : g).replace('/', '.');
            try {
                Class.forName(name, false, NoiseBasedChunkGenerator.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                // Not there: its verdict stays missing, and the terrain the game's.
            }
        }
        Map<String, Boolean> verdicts = verdicts();
        List<String> changed = new ArrayList<>();
        for (String g : GUARDED) {
            if (!Boolean.TRUE.equals(verdicts.get(g))) {
                changed.add(g.substring(g.lastIndexOf('/') + 1, g.contains("(") ? g.indexOf('(') : g.length()));
            }
        }
        return changed.isEmpty() ? null : "changed by a loader or mod: " + changed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> verdicts() {
        Object m = System.getProperties().get("horizon.accel.vanilla");
        return m instanceof Map<?, ?> map ? (Map<String, Boolean>) map : Map.of();
    }

    /** The registries terrain reads, and for biomes the fields of theirs it reads. */
    private static final Set<ResourceKey<?>> TERRAIN_REGISTRIES = Set.of(Registries.NOISE_SETTINGS,
            Registries.DENSITY_FUNCTION, Registries.NOISE, Registries.CARVER, Registries.MATERIAL_RULE,
            Registries.MATERIAL_CONDITION, Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Registries.BIOME);
    private static final Set<String> BIOME_TERRAIN_FIELDS =
            Set.of("carvers", "temperature", "downfall", "has_precipitation", "temperature_modifier");

    /**
     * Null when every vanilla entry of {@link #TERRAIN_REGISTRIES} is as the jar has it (both through the game's own
     * codec, so that only content counts), else the entries that differ.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static String registriesVanilla(RegistryAccess access) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        ClassLoader jar = NoiseBasedChunkGenerator.class.getClassLoader();
        List<String> changed = new ArrayList<>();
        int compared = 0;
        for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLD_REGISTRIES) {
            if (!TERRAIN_REGISTRIES.contains(data.key())) {
                continue;
            }
            Codec codec = data.elementCodec();
            Registry<?> registry = access.lookupOrThrow((ResourceKey) data.key());
            String dir = Registries.elementsDirPath(data.key());
            for (var entry : registry.entrySet()) {
                var id = ((ResourceKey<?>) entry.getKey()).identifier();
                if (!id.getNamespace().equals("minecraft")) {
                    continue;
                }
                String path = "data/minecraft/" + dir + "/" + id.getPath() + ".json";
                try (InputStream in = jar.getResourceAsStream(path)) {
                    if (in == null) {
                        changed.add(dir + "/" + id.getPath() + " (not vanilla's)");
                        continue;
                    }
                    JsonElement file = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    Object vanilla = codec.parse(ops, file).getOrThrow();
                    JsonElement want = (JsonElement) codec.encodeStart(ops, vanilla).getOrThrow();
                    JsonElement live = (JsonElement) codec.encodeStart(ops, entry.getValue()).getOrThrow();
                    if (data.key() == Registries.BIOME) {
                        want = only(want, BIOME_TERRAIN_FIELDS);
                        live = only(live, BIOME_TERRAIN_FIELDS);
                    }
                    compared++;
                    if (!want.equals(live)) {
                        changed.add(dir + "/" + id.getPath());
                    }
                } catch (Exception e) {
                    changed.add(dir + "/" + id.getPath() + " (" + e.getMessage() + ")");
                }
            }
        }
        System.out.println("[horizon-gen] compared " + compared + " terrain registry entries with the jar's");
        return changed.isEmpty() ? null
                : "a data pack or mod changed " + (changed.size() > 8 ? changed.subList(0, 8) + " and " + (changed.size() - 8) + " more" : changed);
    }

    private static JsonElement only(JsonElement e, Set<String> fields) {
        if (!(e instanceof JsonObject o)) {
            return e;
        }
        JsonObject out = new JsonObject();
        for (String f : fields) {
            if (o.has(f)) {
                out.add(f, o.get(f));
            }
        }
        return out;
    }

    /** Rust's ids to the game's states, or an empty array when any name differs. */
    private static BlockState[] checkStates() throws Throwable {
        int count = (int) STATE_COUNT.invokeExact(RESOURCES_UTF8, RESOURCES_UTF8.byteSize() - 1);
        if (count <= 0) {
            System.out.println("[horizon-gen] off: Rust has no block states: " + error());
            return new BlockState[0];
        }
        BlockState[] out = new BlockState[count];
        String[] names;
        try (Arena arena = Arena.ofConfined()) {
            long need = (long) STATE_NAMES.invokeExact(RESOURCES_UTF8, RESOURCES_UTF8.byteSize() - 1,
                    MemorySegment.NULL, 0L);
            long size = need < 0 && need != Long.MIN_VALUE ? -need : 0;
            MemorySegment buf = arena.allocate(Math.max(1, size));
            long n = (long) STATE_NAMES.invokeExact(RESOURCES_UTF8, RESOURCES_UTF8.byteSize() - 1, buf, size);
            if (n < 0) {
                System.out.println("[horizon-gen] off: Rust has no block state names: " + error());
                return new BlockState[0];
            }
            names = new String(buf.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
                    .split("\n", -1);
        }
        if (names.length != count) {
            System.out.println("[horizon-gen] off: " + names.length + " state names for " + count + " states");
            return new BlockState[0];
        }
        {
            for (int i = 0; i < count; i++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
                String name = names[i];
                if (state == null || !state.toString().equals(name)) {
                    System.out.println("[horizon-gen] off: state " + i + " is " + state + " in the game, " + name
                            + " in Rust");
                    return new BlockState[0];
                }
                out[i] = state;
            }
        }
        return out;
    }

    private static String error() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(1024);
            int n = (int) ERROR.invokeExact(buf, 1024L);
            return new String(buf.asSlice(0, Math.max(0, n)).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** Rust's biome ids as the level's biomes (a name the level has not: null, which no chunk's biome is). */
    private static Object[] biomes(MemorySegment g, ServerLevel level) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            long need = (long) BIOME_NAMES.invokeExact(g, MemorySegment.NULL, 0L);
            long size = need < 0 ? -need : 0;
            MemorySegment buf = arena.allocate(Math.max(1, size));
            long n = (long) BIOME_NAMES.invokeExact(g, buf, size);
            String[] names = new String(buf.asSlice(0, Math.max(0, n)).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8).split("\n", -1);
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            Object[] out = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                var id = net.minecraft.resources.Identifier.tryParse(names[i]);
                out[i] = id == null ? null
                        : registry.get(ResourceKey.create(Registries.BIOME, id)).map(h -> (Object) h.value()).orElse(null);
            }
            return out;
        }
    }

    /** Whether the chunk's biomes (the game's own, made before terrain) are the ones Rust made its terrain with. */
    private static boolean sameBiomes(MemorySegment g, ChunkAccess chunk, short[] biomes) {
        Object[] byId = BIOMES.get(g);
        int sections = chunk.getSectionsCount();
        for (int s = 0; s < sections; s++) {
            var container = chunk.getSection(s).getBiomes();
            for (int q = 0; q < 64; q++) {
                int id = biomes[s * 64 + q] & 0xFFFF;
                Object rust = id < byId.length ? byId[id] : null;
                if (rust == null || container.get(q & 3, q >> 4, (q >> 2) & 3).value() != rust) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The terrain as Rust makes it: states, postprocessing offsets and their counts per section, quart biomes. */
    private record Terrain(int[] states, short[] post, int[] counts, short[] biomes) {}

    private static Terrain terrain(MemorySegment g, ChunkAccess chunk) {
        int sections = chunk.getSectionsCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment st = arena.allocate(ValueLayout.JAVA_INT, (long) sections * 4096);
            int cap = sections * 4096;
            MemorySegment post = arena.allocate(ValueLayout.JAVA_SHORT, cap);
            MemorySegment counts = arena.allocate(ValueLayout.JAVA_INT, sections);
            MemorySegment biomes = arena.allocate(ValueLayout.JAVA_SHORT, (long) sections * 64);
            var pos = chunk.getPos();
            int n = (int) TERRAIN.invokeExact(g, pos.x(), pos.z(), st, post, (long) cap, counts, biomes);
            if (n < 0) {
                javaBecause("Rust failed: " + error());
                return null;
            }
            return new Terrain(st.toArray(ValueLayout.JAVA_INT), post.asSlice(0, (long) n * 2).toArray(ValueLayout.JAVA_SHORT),
                    counts.toArray(ValueLayout.JAVA_INT), biomes.toArray(ValueLayout.JAVA_SHORT));
        } catch (Throwable t) {
            javaBecause("Rust failed: " + t);
            return null;
        }
    }

    /** Rust's terrain written into the chunk as vanilla's fill leaves it; null (chunk untouched) on error. */
    private static ChunkAccess rust(MemorySegment g, ChunkAccess chunk) {
        Terrain t = terrain(g, chunk);
        if (t == null) {
            return null;
        }
        if (!sameBiomes(g, chunk, t.biomes)) {
            javaBecause("its biomes are not Rust's");
            return null;
        }
        BlockState[] byId = states;
        int sections = chunk.getSectionsCount();
        for (int s = 0; s < sections; s++) {
            LevelChunkSection section = chunk.getSection(s);
            section.acquire();
            try {
                int base = s * 4096;
                for (int i = 0; i < 4096; i++) {
                    int id = t.states[base + i];
                    if (id != 0) {
                        section.setBlockState(i & 15, i >> 8, (i >> 4) & 15, byId[id], false);
                    }
                }
            } finally {
                section.release();
            }
        }
        int k = 0;
        for (int s = 0; s < sections; s++) {
            int n = t.counts[s];
            if (n > 0) {
                ShortArrayList list = new ShortArrayList(n);
                for (int j = 0; j < n; j++) {
                    list.add(t.post[k++]);
                }
                chunk.addPackedPostProcess(list, s);
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        RUST.incrementAndGet();
        report();
        return chunk;
    }

    /** Check mode: the game's terrain against Rust's for the same chunk. */
    private static void compare(MemorySegment g, ChunkAccess chunk) {
        Terrain t = terrain(g, chunk);
        if (t == null) {
            return;
        }
        if (!sameBiomes(g, chunk, t.biomes)) {
            javaBecause("its biomes are not Rust's");
            return;
        }
        CHECKED.incrementAndGet();
        BlockState[] byId = states;
        int sections = chunk.getSectionsCount();
        int blocks = 0;
        String first = null;
        for (int s = 0; s < sections; s++) {
            LevelChunkSection section = chunk.getSection(s);
            for (int i = 0; i < 4096; i++) {
                BlockState want = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                BlockState got = byId[t.states[s * 4096 + i]];
                if (want != got) {
                    if (first == null) {
                        first = "(" + (i & 15) + ", " + (chunk.getMinY() + s * 16 + (i >> 8)) + ", " + ((i >> 4) & 15)
                                + ") game " + want + ", Rust " + got;
                    }
                    blocks++;
                }
            }
        }
        int k = 0;
        int post = 0;
        var lists = chunk.getPostProcessing();
        for (int s = 0; s < sections; s++) {
            ShortArrayList mine = new ShortArrayList();
            for (int j = 0; j < t.counts[s]; j++) {
                mine.add(t.post[k++]);
            }
            var theirs = lists[s] == null ? new ShortArrayList() : new ShortArrayList(lists[s]);
            mine.sort(null);
            theirs.sort(null);
            if (!mine.equals(theirs)) {
                post++;
            }
        }
        // Rust's chunks get their heightmaps primed from their blocks: the same as the game's own fill leaves them?
        int heightmaps = 0;
        for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
            long[] game = chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone();
            Heightmap.primeHeightmaps(chunk, EnumSet.of(type));
            if (!java.util.Arrays.equals(game, chunk.getOrCreateHeightmapUnprimed(type).getRawData())) {
                heightmaps++;
            }
            chunk.getOrCreateHeightmapUnprimed(type).setRawData(chunk, type, game);
        }
        if (blocks > 0 || post > 0 || heightmaps > 0) {
            DIFFERENT.incrementAndGet();
            if (DIFFERENT.get() <= 20) {
                System.out.println("[horizon-gen] chunk " + chunk.getPos() + " differs: " + blocks + " blocks"
                        + (first == null ? "" : ", first " + first) + ", " + post + " sections' postprocessing, "
                        + heightmaps + " heightmaps");
            }
        }
        report();
    }

    private static void report() {
        long done = RUST.get() + CHECKED.get();
        if (done < REPORTED.get() + 200) {
            return;
        }
        REPORTED.set(done);
        long rustNanos;
        long rustChunks;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
            rustNanos = (long) NANOS.invokeExact(out);
            rustChunks = out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            return;
        }
        String java = JAVA_CHUNKS.get() == 0 ? ""
                : String.format(", the game's %.1f ms", JAVA_NANOS.get() / 1e6 / JAVA_CHUNKS.get());
        System.out.println("[horizon-gen] " + RUST.get() + " chunks by Rust, " + CHECKED.get() + " checked ("
                + DIFFERENT.get() + " different); Rust " + String.format("%.1f", rustNanos / 1e6 / Math.max(1, rustChunks))
                + " ms a chunk" + java + "; the game's own: " + JAVA_WHY);
    }
}
