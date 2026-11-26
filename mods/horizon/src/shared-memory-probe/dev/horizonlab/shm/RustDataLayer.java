package dev.horizonlab.shm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * A {@code DataLayer} (a section's 4,096 light levels, a nibble each) whose bytes live in memory Rust owns: the
 * light engine reads and writes them there in place, so Rust sees each light level as it is set.
 *
 * <p>It keeps vanilla's layout (index {@code y << 8 | z << 4 | x}, two levels a byte, the even index in the low
 * nibble) and vanilla's lazy form: a layer that was never written holds no bytes, only its default level, until
 * {@link #getData()} or a {@code set} asks for them. It extends {@code DataLayer} only so it can stand where the
 * game expects one; the parent's own fields stay empty.
 */
public final class RustDataLayer extends DataLayer {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final int BYTES = 2048;
    private static final int WORDS = BYTES / Long.BYTES;

    /** The bytes Rust owns, freed with the object that holds them (a {@code fill} drops them, as vanilla does). */
    private static final class Bytes {
        final MemorySegment segment;
        final long base;

        Bytes() {
            this.segment = Rust.alloc(WORDS);
            this.base = segment.address();
            MemorySegment freed = segment;
            CLEANER.register(this, () -> Rust.free(freed, WORDS));
        }
    }

    private Bytes bytes;
    private int defaultValue;

    // The game makes its layers through these, in place of DataLayer's constructors (Agent).
    public static DataLayer create() {
        return new RustDataLayer(0);
    }

    public static DataLayer create(int defaultValue) {
        return new RustDataLayer(defaultValue);
    }

    public static DataLayer create(byte[] data) {
        if (data.length != BYTES) {
            // Vanilla's own constructor throws its own message for this.
            return new DataLayer(data);
        }
        RustDataLayer layer = new RustDataLayer(0);
        layer.bytes = new Bytes();
        MemorySegment.copy(data, 0, layer.bytes.segment, ValueLayout.JAVA_BYTE, 0, BYTES);
        return layer;
    }

    private RustDataLayer(int defaultValue) {
        super(defaultValue);
        this.defaultValue = defaultValue;
    }

    private static int index(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    private int get(int index) {
        Bytes b = bytes;
        if (b == null) {
            return defaultValue;
        }
        return Rust.ALL.get(ValueLayout.JAVA_BYTE, b.base + (index >> 1)) >> 4 * (index & 1) & 15;
    }

    @Override
    public int get(int x, int y, int z) {
        return get(index(x, y, z));
    }

    @Override
    public void set(int x, int y, int z, int value) {
        int index = index(x, y, z);
        long at = materialize().base + (index >> 1);
        int shift = 4 * (index & 1);
        byte old = Rust.ALL.get(ValueLayout.JAVA_BYTE, at);
        Rust.ALL.set(ValueLayout.JAVA_BYTE, at, (byte) (old & ~(15 << shift) | (value & 15) << shift));
    }

    @Override
    public void fill(int value) {
        defaultValue = value;
        bytes = null;
    }

    /** As vanilla's {@code getData}: the bytes come into being, filled with the default level. */
    private Bytes materialize() {
        Bytes b = bytes;
        if (b == null) {
            b = new Bytes();
            if (defaultValue != 0) {
                b.segment.fill(packFilled(defaultValue));
            }
            bytes = b;
        }
        return b;
    }

    private static byte packFilled(int value) {
        byte b = (byte) value;
        for (int shift = 4; shift < 8; shift += 4) {
            b = (byte) (b | value << shift);
        }
        return b;
    }

    /**
     * The levels as bytes, for the saved and network forms: a copy, as heap arrays cannot point at Rust's memory.
     * Nothing in the game writes into the array it gets back (checked over every caller in the jar: the packet,
     * the chunk save and the sky storage's layer copy only read it).
     */
    @Override
    public byte[] getData() {
        return materialize().segment.toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public DataLayer copy() {
        Bytes b = bytes;
        return b == null ? create(defaultValue) : create(b.segment.toArray(ValueLayout.JAVA_BYTE));
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            out.append(Integer.toHexString(get(i)));
            if ((i & 15) == 15) {
                out.append("\n");
            }
            if ((i & 255) == 255) {
                out.append("\n");
            }
        }
        return out.toString();
    }

    @Override
    public String layerToString(int layer) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            out.append(Integer.toHexString(get(i)));
            if ((i & 15) == 15) {
                out.append("\n");
            }
        }
        return out.toString();
    }

    @Override
    public boolean isDefinitelyHomogenous() {
        return bytes == null;
    }

    @Override
    public boolean isDefinitelyFilledWith(int value) {
        return bytes == null && defaultValue == value;
    }

    @Override
    public boolean isEmpty() {
        return bytes == null && defaultValue == 0;
    }

    // For the probes: the memory Rust owns, 0 while the layer holds only its default level.
    long address() {
        Bytes b = bytes;
        return b == null ? 0L : b.base;
    }

    int defaultLevel() {
        return defaultValue;
    }

    /** The address of the bytes, made now if the layer held only its default level (as a {@code set} would). */
    long writable() {
        return materialize().base;
    }

    /** Keeps the bytes reachable while Rust reads them. */
    Object holder() {
        return bytes;
    }
}
