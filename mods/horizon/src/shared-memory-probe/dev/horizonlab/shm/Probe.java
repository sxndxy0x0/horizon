package dev.horizonlab.shm;

import io.netty.buffer.Unpooled;
import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * P2: the same vanilla block-storage work, run once on vanilla's own storage and once on storage Rust owns
 * ({@code -javaagent} with this jar), writing what each run produces for the runner to compare.
 *
 * <p>Arguments: {@code <output file>}. The run is "rust" when the agent rewrote the game's storage.
 */
public final class Probe {
    static final int SECTIONS = 96;
    static final int[] DISTINCT = {1, 2, 3, 16, 17, 33, 64, 200, 256, 257, 600, 5000};

    private Probe() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        boolean rust = Agent.REWRITTEN.get() > 0 || isRust();
        List<String> out = new ArrayList<>();
        out.add("mode\t" + (rust ? "rust" : "vanilla"));
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        int registry = Block.BLOCK_STATE_REGISTRY.size();
        int globalBits = 32 - Integer.numberOfLeadingZeros(registry - 1);
        var codec = PalettedContainer.codecRW(BlockState.CODEC, strategy, Blocks.AIR.defaultBlockState());

        Random random = new Random(20261125L);
        int rustChecks = 0;
        int rustMismatches = 0;
        for (int s = 0; s < SECTIONS; s++) {
            int distinct = DISTINCT[s % DISTINCT.length];
            List<BlockState> states = new ArrayList<>();
            var ids = new LinkedHashSet<Integer>();
            while (ids.size() < Math.min(distinct, registry)) {
                ids.add(random.nextInt(registry));
            }
            for (int id : ids) {
                states.add(Block.stateById(id));
            }
            PalettedContainer<BlockState> c = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
            long trace = 0;
            for (int op = 0; op < 9000; op++) {
                int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
                BlockState old = c.getAndSet(x, y, z, states.get(random.nextInt(states.size())));
                trace = trace * 31 + Block.getId(old);
            }
            int[] cells = new int[4096];
            for (int i = 0; i < 4096; i++) {
                cells[i] = Block.getId(c.get(i & 15, i >> 8, i >> 4 & 15));
            }
            String net = sha(network(c));
            PalettedContainer<BlockState> back = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
            back.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(network(c))));
            String saved = sha(codec.encodeStart(NbtOps.INSTANCE, c).getOrThrow().toString()
                    .getBytes(StandardCharsets.UTF_8));
            out.add("section\t" + s + "\tdistinct " + distinct + "\tbits " + c.bitsPerEntry() + "\tsize "
                    + c.getSerializedSize() + "\ttrace " + Long.toHexString(trace) + "\tcells " + sha(ints(cells))
                    + "\tnetwork " + net + "\tread back " + sha(network(back)) + "\tcopy " + sha(network(c.copy()))
                    + "\tsaved " + saved);
            if (rust) {
                int[] r = rustViews(c, cells, globalBits);
                rustChecks += r[0];
                rustMismatches += r[1];
            }
        }
        // Before the checks only the Rust run makes, so both runs have done the same work when they measure.
        out.add("bench container getAndSet+get\t" + benchContainer(strategy) + " ns/op");
        if (rust) {
            out.add("rust checks\t" + rustChecks + "\tmismatches " + rustMismatches);
            out.add("storage equivalence\t" + storageEquivalence());
            out.add("light equivalence\t" + layerEquivalence());
            out.add("threads\t" + threaded(strategy));
            out.add("rewritten sites\t" + Agent.REWRITTEN.get() + "\t" + String.join(",", Agent.SITES));
        }
        if (rust) {
            out.add("bench storage vanilla\t" + benchVanilla(new SimpleBitStorage(6, 4096)) + " ns/op");
            out.add("bench storage rust\t" + benchRust((RustBitStorage) RustBitStorage.create(6, 4096)) + " ns/op");
        }
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) {
            for (String line : out) {
                w.write(line);
                w.write('\n');
            }
        }
        System.out.println("[shm] " + out.get(0) + ", " + SECTIONS + " sections written to " + args[0]);
        System.exit(0);
    }

    static boolean isRust() {
        return "rust".equals(System.getProperty("hz.mode"));
    }

    /**
     * Rust reads the memory Java wrote, with horizon-core's code; Java reads what Rust writes. Returns
     * {checks made, mismatches}.
     */
    static int[] rustViews(PalettedContainer<BlockState> c, int[] cells, int globalBits) throws Exception {
        Object data = field(c, "data");
        BitStorage storage = (BitStorage) call(data, "storage");
        if (!(storage instanceof RustBitStorage rs)) {
            // A single-value section keeps a ZeroBitStorage: no words to share.
            return new int[] {0, 0};
        }
        int checks = 0, bad = 0;
        int[] values = new int[storage.getSize()];
        storage.unpack(values);
        checks++;
        if (Rust.storageHash(rs.words(), rs.wordCount(), rs.getBits(), rs.getSize()) != fnv(values)) {
            bad++;
        }
        @SuppressWarnings("unchecked")
        Palette<BlockState> palette = (Palette<BlockState>) call(data, "palette");
        String kind = palette.getClass().getSimpleName();
        if (!kind.contains("Global")) {
            int[] ids = new int[palette.getSize()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = Block.getId(palette.valueFor(i));
            }
            checks++;
            if (Rust.containerMismatches(rs.words(), rs.wordCount(), globalBits, ids, cells) != 0) {
                bad++;
            }
        }
        // Rust writes a cell in place; Java's storage reads it; Rust puts it back.
        int index = 1234 % rs.getSize();
        int old = rs.get(index);
        int other = old == 0 ? 1 : 0;
        Rust.set(rs.words(), rs.getBits(), index, other);
        checks += 2;
        if (rs.get(index) != other) {
            bad++;
        }
        Rust.set(rs.words(), rs.getBits(), index, old);
        if (Rust.get(rs.words(), rs.getBits(), index) != old || rs.get(index) != old) {
            bad++;
        }
        return new int[] {checks, bad};
    }

    /** Random work on vanilla's SimpleBitStorage and on RustBitStorage side by side: their longs must match. */
    static String storageEquivalence() {
        Random random = new Random(7L);
        int cases = 0;
        for (int bits = 1; bits <= 32; bits++) {
            for (int size : new int[] {1, 37, 64, 4096}) {
                // Values reach the storage as ints: at 32 bits, only the non-negative ones are valid.
                long mask = Math.min((1L << bits) - 1, Integer.MAX_VALUE);
                SimpleBitStorage v = new SimpleBitStorage(bits, size);
                SimpleBitStorage r = RustBitStorage.create(bits, size);
                for (int op = 0; op < 400; op++) {
                    int i = random.nextInt(size);
                    int value = (int) (random.nextLong() & mask);
                    switch (op % 7) {
                        case 0 -> { v.fill(value); r.fill(value); }
                        case 1 -> { if (v.getAndSet(i, value) != r.getAndSet(i, value)) return "getAndSet differs at bits " + bits; }
                        default -> { v.set(i, value); r.set(i, value); }
                    }
                    if (v.get(i) != r.get(i)) {
                        return "get differs at bits " + bits + " size " + size;
                    }
                }
                if (!java.util.Arrays.equals(v.getRaw(), r.getRaw())) {
                    return "longs differ at bits " + bits + " size " + size;
                }
                int[] a = new int[size], b = new int[size];
                v.unpack(a);
                r.unpack(b);
                List<Integer> ga = new ArrayList<>(), gb = new ArrayList<>();
                v.getAll(ga::add);
                r.getAll(gb::add);
                if (!java.util.Arrays.equals(a, b) || !ga.equals(gb)) {
                    return "unpack or getAll differs at bits " + bits + " size " + size;
                }
                int[] values = new int[size];
                for (int k = 0; k < size; k++) {
                    values[k] = (int) (random.nextLong() & mask);
                }
                if (!java.util.Arrays.equals(new SimpleBitStorage(bits, size, values).getRaw(),
                        RustBitStorage.create(bits, size, values).getRaw())
                        || !java.util.Arrays.equals(v.copy().getRaw(), r.copy().getRaw())) {
                    return "packing or copy differs at bits " + bits + " size " + size;
                }
                cases++;
            }
        }
        return "identical in " + cases + " cases";
    }

    /**
     * Random work on vanilla's DataLayer and on RustDataLayer side by side: every level, every flag, the bytes,
     * the text forms and copies must match, and Rust, reading the layer's memory in place, must see the levels.
     */
    static String layerEquivalence() {
        Random random = new Random(11L);
        int cases = 0, rustReads = 0;
        for (int c = 0; c < 96; c++) {
            int start = c % 16;
            byte[] bytes = new byte[2048];
            random.nextBytes(bytes);
            DataLayer v, r;
            switch (c % 3) {
                case 0 -> { v = new DataLayer(); r = RustDataLayer.create(); }
                case 1 -> { v = new DataLayer(start); r = RustDataLayer.create(start); }
                default -> { v = new DataLayer(bytes.clone()); r = RustDataLayer.create(bytes.clone()); }
            }
            for (int op = 0; op < 3000; op++) {
                int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16), level = random.nextInt(16);
                int what = random.nextInt(100);
                if (what == 0) {
                    v.fill(level);
                    r.fill(level);
                } else if (what == 1) {
                    v = v.copy();
                    r = r.copy();
                } else if (what < 60) {
                    v.set(x, y, z, level);
                    r.set(x, y, z, level);
                }
                if (v.get(x, y, z) != r.get(x, y, z) || v.isEmpty() != r.isEmpty()
                        || v.isDefinitelyHomogenous() != r.isDefinitelyHomogenous()
                        || v.isDefinitelyFilledWith(level) != r.isDefinitelyFilledWith(level)) {
                    return "differs in case " + c + " at op " + op;
                }
            }
            if (!v.toString().equals(r.toString()) || !v.layerToString(3).equals(r.layerToString(3))) {
                return "text forms differ in case " + c;
            }
            // getData makes the bytes in both (vanilla's own side effect), so it comes after the flags.
            if (!java.util.Arrays.equals(v.getData(), r.getData())
                    || !java.util.Arrays.equals(v.copy().getData(), r.copy().getData())) {
                return "bytes differ in case " + c;
            }
            int[] levels = new int[4096];
            for (int i = 0; i < 4096; i++) {
                levels[i] = v.get(i & 15, i >> 8, i >> 4 & 15);
            }
            RustDataLayer rl = (RustDataLayer) r;
            if (Rust.lightHash(rl.address()) != fnv(levels)) {
                return "Rust reads other levels in case " + c;
            }
            rustReads++;
            cases++;
        }
        return "identical in " + cases + " cases\tRust read " + rustReads + " layers in place, 0 wrong";
    }

    /**
     * Eight threads at once, each with containers of its own (the game's rule: one thread per container), on
     * Rust's memory: each ends with the cells it wrote, Rust reads them in place, and storages are freed by the
     * Cleaner's thread meanwhile.
     */
    static String threaded(Strategy<BlockState> strategy) throws Exception {
        int threads = 8;
        int[] wrong = new int[threads];
        int[] checks = new int[threads];
        Thread[] running = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int me = t;
            running[t] = new Thread(() -> {
                Random random = new Random(1000L + me);
                int registry = Block.BLOCK_STATE_REGISTRY.size();
                for (int round = 0; round < 60; round++) {
                    PalettedContainer<BlockState> c = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
                    int[] expected = new int[4096];
                    int distinct = DISTINCT[random.nextInt(DISTINCT.length)];
                    int[] ids = new int[Math.min(distinct, 300)];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = random.nextInt(registry);
                    }
                    for (int op = 0; op < 6000; op++) {
                        int i = random.nextInt(4096);
                        int id = ids[random.nextInt(ids.length)];
                        c.set(i & 15, i >> 8, i >> 4 & 15, Block.stateById(id));
                        expected[i] = id;
                    }
                    for (int i = 0; i < 4096; i++) {
                        checks[me]++;
                        if (Block.getId(c.get(i & 15, i >> 8, i >> 4 & 15)) != expected[i]) {
                            wrong[me]++;
                        }
                    }
                    try {
                        BitStorage storage = (BitStorage) call(field(c, "data"), "storage");
                        if (storage instanceof RustBitStorage rs) {
                            int[] values = new int[4096];
                            rs.unpack(values);
                            checks[me]++;
                            if (Rust.storageHash(rs.words(), rs.wordCount(), rs.getBits(), rs.getSize()) != fnv(values)) {
                                wrong[me]++;
                            }
                        }
                    } catch (Exception e) {
                        wrong[me]++;
                    }
                    if (round % 10 == 0) {
                        System.gc();
                    }
                }
            });
            running[t].start();
        }
        int all = 0, bad = 0;
        for (int t = 0; t < threads; t++) {
            running[t].join();
            all += checks[t];
            bad += wrong[t];
        }
        return threads + " threads, " + all + " checks, " + bad + " wrong";
    }

    static double benchContainer(Strategy<BlockState> strategy) {
        PalettedContainer<BlockState> c = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
        BlockState[] states = new BlockState[33];
        for (int i = 0; i < states.length; i++) {
            states[i] = Block.stateById(i * 7 + 1);
        }
        for (int i = 0; i < 4096; i++) {
            c.set(i & 15, i >> 8, i >> 4 & 15, states[i % states.length]);
        }
        long sink = 0;
        int n = 20_000_000;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            int k = 0x1234;
            for (int i = 0; i < n; i++) {
                k = k * 1103515245 + 12345;
                int x = k >>> 4 & 15, y = k >>> 8 & 15, z = k >>> 12 & 15;
                sink += c.getAndSet(x, y, z, states[(k >>> 16) % states.length]).hashCode();
                sink += c.get(z, x, y).hashCode();
            }
            if (round == 2) {
                double ns = (System.nanoTime() - start) / (double) n;
                if (sink == 42) System.out.print("");
                return Math.round(ns * 100) / 100.0;
            }
        }
        return -1;
    }

    // One method per type, so each call site stays monomorphic: a shared method would be profiled with both types
    // and penalise whichever runs second.
    static double benchVanilla(SimpleBitStorage s) {
        long sink = 0;
        int n = 50_000_000;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            int k = 99;
            for (int i = 0; i < n; i++) {
                k = k * 1103515245 + 12345;
                int index = (k >>> 8) & 4095;
                sink += s.getAndSet(index, k >>> 26);
                sink += s.get(index ^ 1);
            }
            if (round == 2) {
                if (sink == 42) System.out.print("");
                return Math.round((System.nanoTime() - start) / (double) n * 100) / 100.0;
            }
        }
        return -1;
    }

    static double benchRust(RustBitStorage s) {
        long sink = 0;
        int n = 50_000_000;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            int k = 99;
            for (int i = 0; i < n; i++) {
                k = k * 1103515245 + 12345;
                int index = (k >>> 8) & 4095;
                sink += s.getAndSet(index, k >>> 26);
                sink += s.get(index ^ 1);
            }
            if (round == 2) {
                if (sink == 42) System.out.print("");
                return Math.round((System.nanoTime() - start) / (double) n * 100) / 100.0;
            }
        }
        return -1;
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    static byte[] network(PalettedContainer<BlockState> c) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        c.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    static byte[] ints(int[] values) {
        byte[] b = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            b[i * 4] = (byte) v;
            b[i * 4 + 1] = (byte) (v >> 8);
            b[i * 4 + 2] = (byte) (v >> 16);
            b[i * 4 + 3] = (byte) (v >> 24);
        }
        return b;
    }

    /** FNV-1a over little-endian u32s, as the Rust side hashes. */
    static long fnv(int[] values) {
        long h = 0xcbf29ce484222325L;
        for (byte b : ints(values)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
