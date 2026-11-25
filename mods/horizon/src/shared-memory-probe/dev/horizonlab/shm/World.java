package dev.horizonlab.shm;

import java.io.BufferedWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * The live-world run: the real game server (vanilla's game-test server, which needs no player) generates a world
 * on its worker threads, the probe edits it, the game lights it and keeps its heightmaps, saves it; then the run
 * writes what each chunk holds. Run once on vanilla's memory and once on Rust's, the two must match.
 *
 * <p>On Rust's memory it also has Horizon read the live world in place, through Horizon's own {@code LevelAccess}
 * ({@code crates/horizon-shm-probe/src/level.rs}), on several Rust threads, and write blocks into it for the game
 * to see.
 *
 * <p>Properties: {@code hz.out} (the output file), {@code hz.world} ({@code normal} for a normal world, else the
 * test server's flat one), {@code hz.radius} (chunks each way from 0, 0), {@code hz.edits}, {@code hz.facts} (a
 * folder with Horizon's {@code facts.json}, for the registry check), {@code hz.keep} (load the world already in the
 * server's folder instead of a new one; see {@link Agent#keepWorld}).
 */
public final class World {
    static final int RADIUS = Integer.getInteger("hz.radius", 6);
    static final int EDITS = Integer.getInteger("hz.edits", 40_000);
    /** Edits change the block and its light and heightmaps, but run no neighbour or shape updates. */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    static int phase;
    static int ticks;
    static int quiet;
    static long start;
    static final List<CompletableFuture<?>> LIGHT = new ArrayList<>();
    /** The chunks, as the server thread holds them (the game hands chunks out on that thread only). */
    static final Map<ChunkPos, LevelChunk> LOADED = new java.util.HashMap<>();

    private World() {}

    public static boolean tick(MinecraftServer server) {
        try {
            step(server);
        } catch (Throwable t) {
            System.out.println("[shm] the world run failed");
            t.printStackTrace(System.out);
            Runtime.getRuntime().halt(3);
        }
        return true;
    }

    static List<ChunkPos> chunks() {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                out.add(new ChunkPos(x, z));
            }
        }
        return out;
    }

    static void step(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        ticks++;
        switch (phase) {
            case 0 -> {
                // Nothing but the chunk system runs: no block, fluid or random ticks and no mobs, so what the
                // world holds is its generation, the probe's edits and their light and heightmaps.
                server.tickRateManager().setFrozen(true);
                GameRules rules = level.getGameRules();
                rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
                rules.set(GameRules.SPAWN_MOBS, false, server);
                rules.set(GameRules.MOB_GRIEFING, false, server);
                for (ChunkPos c : chunks()) {
                    level.setChunkForced(c.x(), c.z(), true);
                }
                start = System.nanoTime();
                phase = 1;
            }
            case 1 -> {
                if (chunks().stream().allMatch(c -> level.getChunkSource().getChunkNow(c.x(), c.z()) != null)) {
                    System.out.println("[shm] " + chunks().size() + " chunks generated in "
                            + (System.nanoTime() - start) / 1_000_000 + " ms");
                    phase = 2;
                } else if (System.nanoTime() - start > 20L * 60 * 1_000_000_000L) {
                    throw new IllegalStateException("chunks did not generate in 20 minutes");
                }
            }
            case 2 -> {
                edit(level);
                ticks = 0;
                phase = 3;
            }
            case 3 -> {
                // Light runs on its own thread: wait for every task queued so far, then for quiet ticks.
                if (LIGHT.isEmpty()) {
                    for (ChunkPos c : chunks()) {
                        LIGHT.add(level.getChunkSource().getLightEngine().waitForPendingTasks(c.x(), c.z()));
                    }
                }
                boolean idle = LIGHT.stream().allMatch(CompletableFuture::isDone)
                        && !level.getChunkSource().getLightEngine().hasLightWork();
                quiet = idle ? quiet + 1 : 0;
                if (ticks >= 60 && quiet >= 40) {
                    phase = 4;
                }
            }
            case 4 -> {
                finish(server, level);
            }
            default -> { }
        }
    }

    static void edit(ServerLevel level) {
        BlockState[] states = {
            Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
            Blocks.SEA_LANTERN.defaultBlockState(), Blocks.AIR.defaultBlockState(),
            Blocks.OAK_PLANKS.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(),
            Blocks.OBSIDIAN.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.JACK_O_LANTERN.defaultBlockState(), Blocks.TINTED_GLASS.defaultBlockState()
        };
        Random random = new Random(20261125L);
        int minY = level.getMinY();
        int height = level.getHeight();
        for (int i = 0; i < EDITS; i++) {
            int x = random.nextInt(RADIUS * 32) - RADIUS * 16;
            int z = random.nextInt(RADIUS * 32) - RADIUS * 16;
            // Most edits near the surface, where light and heightmaps change most.
            int y = random.nextInt(4) == 0 ? minY + random.nextInt(height) : 40 + random.nextInt(60);
            level.setBlock(new BlockPos(x, y, z), states[random.nextInt(states.length)], FLAGS);
        }
    }

    /** FNV-1a over ints, as Rust hashes them. */
    static final class Fnv {
        long h = 0xcbf29ce484222325L;

        void add(int v) {
            for (int k = 0; k < 4; k++) {
                h ^= v >>> (8 * k) & 0xff;
                h *= 0x100000001b3L;
            }
        }
    }

    static void finish(MinecraftServer server, ServerLevel level) throws Exception {
        boolean rust = !"vanilla".equals(System.getProperty("hz.mode"));
        List<String> out = new ArrayList<>();
        out.add("mode\t" + (rust ? "rust" : "vanilla"));
        out.add("world\t" + System.getProperty("hz.world") + "\tchunks " + chunks().size() + "\tedits " + EDITS);
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        int minY = level.getMinY();
        int maxY = minY + level.getHeight();
        int minLight = light.getMinLightSection();
        int maxLight = light.getMaxLightSection();
        List<ChunkPos> chunks = chunks();
        // The snapshot, and Horizon's reads of the same memory, run as one task on the game's own light thread:
        // nothing writes light while it runs (the rule for threads), and the server thread waits for it.
        List<String> live = new ArrayList<>();
        CompletableFuture<long[][]> taken = new CompletableFuture<>();
        for (ChunkPos c : chunks) {
            LOADED.put(c, level.getChunkSource().getChunkNow(c.x(), c.z()));
        }
        Object executor = Probe.field(light, "consecutiveExecutor");
        ((net.minecraft.util.thread.ConsecutiveExecutor) executor).schedule(() -> {
            try {
                long[][] hashes = snapshot(level, chunks, minY, maxY, minLight, maxLight);
                if (rust) {
                    live.addAll(horizon(level, chunks, hashes, minY, maxY, minLight, maxLight));
                }
                taken.complete(hashes);
            } catch (Throwable t) {
                taken.completeExceptionally(t);
            }
        });
        long[][] java = taken.join();

        // The save, as the game writes it and reads it back (times taken out: they count wall-clock ticks).
        server.saveEverything(true, true, true);
        String[] saved = new String[chunks.size()];
        for (int n = 0; n < chunks.size(); n++) {
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(chunks.get(n)).join();
            if (tag.isEmpty()) {
                saved[n] = "missing";
                continue;
            }
            CompoundTag t = tag.get().copy();
            t.remove("LastUpdate");
            t.remove("InhabitedTime");
            saved[n] = Probe.sha(t.toString().getBytes(StandardCharsets.UTF_8));
        }
        for (int n = 0; n < chunks.size(); n++) {
            long[] h = java[n];
            out.add("chunk\t" + chunks.get(n).x() + " " + chunks.get(n).z() + "\tblocks " + Long.toHexString(h[0])
                    + "\tbiomes " + Long.toHexString(h[4]) + "\tblock light " + Long.toHexString(h[1])
                    + "\tsky light " + Long.toHexString(h[2]) + "\theightmaps " + Long.toHexString(h[3])
                    + "\tsaved " + saved[n]);
        }
        out.addAll(live);
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(System.getProperty("hz.out")),
                StandardCharsets.UTF_8)) {
            for (String line : out) {
                w.write(line);
                w.write('\n');
            }
        }
        System.out.println("[shm] world run written: " + chunks.size() + " chunks");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    /** Each chunk's {blocks, block light, sky light, heightmaps, biomes} hashes, through the game's own API. */
    static long[][] snapshot(ServerLevel level, List<ChunkPos> chunks, int minY, int maxY, int minLight,
            int maxLight) {
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        long[][] java = new long[chunks.size()][];
        for (int n = 0; n < chunks.size(); n++) {
            ChunkPos c = chunks.get(n);
            LevelChunk chunk = LOADED.get(c);
            Fnv blocks = new Fnv();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        blocks.add(Block.getId(chunk.getBlockState(pos.set(c.getMinBlockX() + x, y,
                                c.getMinBlockZ() + z))));
                    }
                }
            }
            Fnv biome = new Fnv();
            for (int qy = minY >> 2; qy < maxY >> 2; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> b = chunk.getNoiseBiome((c.getMinBlockX() >> 2) + qx, qy,
                                (c.getMinBlockZ() >> 2) + qz);
                        biome.add(biomes.getId(b.value()));
                    }
                }
            }
            long[] layers = new long[2];
            for (LightLayer layer : new LightLayer[] {LightLayer.BLOCK, LightLayer.SKY}) {
                Fnv f = new Fnv();
                for (int sy = minLight; sy < maxLight; sy++) {
                    DataLayer d = light.getLayerListener(layer).getDataLayerData(SectionPos.of(c.x(), sy, c.z()));
                    for (int i = 0; i < 4096; i++) {
                        f.add(d == null ? 16 : d.get(i & 15, i >> 8, i >> 4 & 15));
                    }
                }
                layers[layer == LightLayer.BLOCK ? 0 : 1] = f.h;
            }
            Fnv heights = new Fnv();
            Map<Heightmap.Types, Heightmap> maps = new java.util.EnumMap<>(Heightmap.Types.class);
            for (Map.Entry<Heightmap.Types, Heightmap> e : chunk.getHeightmaps()) {
                maps.put(e.getKey(), e.getValue());
            }
            for (Heightmap.Types type : Heightmap.Types.values()) {
                Heightmap h = maps.get(type);
                if (h == null) {
                    heights.add(-1);
                    continue;
                }
                for (int i = 0; i < 256; i++) {
                    heights.add(h.getFirstAvailable(i & 15, i >> 4) - minY);
                }
            }
            java[n] = new long[] {blocks.h, layers[0], layers[1], heights.h, biome.h};
        }
        return java;
    }

    /** Horizon reads the live world in place, on several Rust threads, and writes into it. */
    static List<String> horizon(ServerLevel level, List<ChunkPos> chunks, long[][] java, int minY, int maxY,
            int minLight, int maxLight) throws Exception {
        List<String> out = new ArrayList<>();
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        MemorySegment view = Rust.levelNew();
        // What Rust reads stays reachable (and so allocated) until it is done.
        List<Object> held = new ArrayList<>();
        int rustSections = 0, oneState = 0, other = 0, rustLayers = 0, absent = 0, otherLayers = 0, heightmaps = 0;
        int globalPalettes = 0;
        try (Arena arena = Arena.ofConfined()) {
            for (ChunkPos c : chunks) {
                LevelChunk chunk = LOADED.get(c);
                LevelChunkSection[] sections = chunk.getSections();
                for (int s = 0; s < sections.length; s++) {
                    int sy = chunk.getSectionYFromSectionIndex(s);
                    Object data = Probe.field(sections[s].getStates(), "data");
                    BitStorage storage = (BitStorage) Probe.call(data, "storage");
                    @SuppressWarnings("unchecked")
                    Palette<BlockState> palette = (Palette<BlockState>) Probe.call(data, "palette");
                    boolean global = palette.getClass().getSimpleName().contains("Global");
                    int[] ids = new int[global ? 0 : palette.getSize()];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = Block.getId(palette.valueFor(i));
                    }
                    MemorySegment words = MemorySegment.NULL;
                    int count = 0, bits = 0;
                    if (storage instanceof RustBitStorage rs) {
                        words = rs.words();
                        count = rs.wordCount();
                        bits = rs.getBits();
                        rustSections++;
                        globalPalettes += global ? 1 : 0;
                        held.add(rs);
                    } else if (storage.getSize() == 0 || storage.getBits() == 0) {
                        oneState++;
                    } else {
                        other++;
                    }
                    Rust.levelBlocks(view, c.x(), sy, c.z(), words, count, bits,
                            arena.allocateFrom(ValueLayout.JAVA_INT, ids.length == 0 ? new int[1] : ids),
                            ids.length, global ? 1 : 0);
                }
                for (int sy = minLight; sy < maxLight; sy++) {
                    for (LightLayer layer : new LightLayer[] {LightLayer.BLOCK, LightLayer.SKY}) {
                        DataLayer d = light.getLayerListener(layer).getDataLayerData(SectionPos.of(c.x(), sy, c.z()));
                        if (d == null) {
                            absent++;
                        } else if (d instanceof RustDataLayer r) {
                            rustLayers++;
                            held.add(r);
                            held.add(r.holder());
                            Rust.levelLight(view, c.x(), sy, c.z(), layer == LightLayer.SKY ? 1 : 0,
                                    MemorySegment.ofAddress(r.address()), r.defaultLevel());
                        } else {
                            otherLayers++;
                        }
                    }
                }
                for (Map.Entry<Heightmap.Types, Heightmap> e : chunk.getHeightmaps()) {
                    if (Probe.field(e.getValue(), "data") instanceof RustBitStorage rs) {
                        heightmaps++;
                        held.add(rs);
                        Rust.levelHeights(view, c.x(), c.z(), e.getKey().ordinal(), rs.words(), rs.wordCount(),
                                rs.getBits());
                    }
                }
            }
            out.add("sections\trust " + rustSections + "\tone state " + oneState + "\tother " + other
                    + "\tglobal palettes " + globalPalettes);
            out.add("light layers\trust " + rustLayers + "\tabsent " + absent + "\tother " + otherLayers);
            out.add("heightmaps\trust " + heightmaps);

            int threads = 8;
            long[] rustHashes = Rust.levelHash(view, chunks, minY, maxY, minLight, maxLight, threads);
            int[] wrong = new int[4];
            for (int n = 0; n < chunks.size(); n++) {
                for (int k = 0; k < 4; k++) {
                    if (rustHashes[4 * n + k] != java[n][k]) {
                        wrong[k]++;
                    }
                }
            }
            out.add("horizon reads\t" + chunks.size() + " chunks on " + threads + " threads\tblocks wrong "
                    + wrong[0] + "\tblock light wrong " + wrong[1] + "\tsky light wrong " + wrong[2]
                    + "\theightmaps wrong " + wrong[3]);

            // Horizon writes a block the section's palette holds; the game reads it; Horizon puts it back.
            Random random = new Random(5L);
            int writes = 0, writeWrong = 0;
            for (int i = 0; i < 4000 && writes < 500; i++) {
                ChunkPos c = chunks.get(random.nextInt(chunks.size()));
                BlockPos pos = new BlockPos(c.getMinBlockX() + random.nextInt(16), minY + random.nextInt(maxY - minY),
                        c.getMinBlockZ() + random.nextInt(16));
                LevelChunk chunk = LOADED.get(c);
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
                Object data = Probe.field(section.getStates(), "data");
                @SuppressWarnings("unchecked")
                Palette<BlockState> palette = (Palette<BlockState>) Probe.call(data, "palette");
                if (palette.getSize() < 2 || palette.getClass().getSimpleName().contains("Global")) {
                    continue;
                }
                BlockState before = chunk.getBlockState(pos);
                BlockState next = palette.valueFor(random.nextInt(palette.getSize()));
                if (next == before) {
                    continue;
                }
                writes++;
                long old = Rust.levelSet(view, pos.getX(), pos.getY(), pos.getZ(), Block.getId(next));
                if (old != Block.getId(before) || chunk.getBlockState(pos) != next
                        || Rust.levelState(view, pos.getX(), pos.getY(), pos.getZ()) != Block.getId(next)) {
                    writeWrong++;
                }
                Rust.levelSet(view, pos.getX(), pos.getY(), pos.getZ(), Block.getId(before));
                if (chunk.getBlockState(pos) != before) {
                    writeWrong++;
                }
            }
            out.add("horizon writes\t" + writes + "\twrong " + writeWrong);
        } finally {
            Rust.levelFree(view);
            held.clear();
        }

        String facts = System.getProperty("hz.facts");
        if (facts != null) {
            StringBuilder states = new StringBuilder();
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                List<String> pairs = new ArrayList<>();
                for (Property<?> p : state.getProperties()) {
                    pairs.add(p.getName() + "=" + value(state, p));
                }
                pairs.sort(Comparator.naturalOrder());
                states.append(Block.getId(state)).append('\t').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                        .append('\t').append(String.join(",", pairs)).append('\n');
            }
            out.add("registry\t" + Block.BLOCK_STATE_REGISTRY.size() + " states\twrong "
                    + Rust.registryCheck(facts, states.toString()));
        }
        return out;
    }

    static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
