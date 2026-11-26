package dev.horizonlab.accel;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.function.IntConsumer;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.apache.commons.lang3.Validate;

/**
 * A {@code SimpleBitStorage} whose words live in memory Rust owns: every read and write goes straight to that
 * memory, so vanilla's {@code PalettedContainer} runs on it and Rust sees each change as it is made.
 *
 * <p>The layout is vanilla's (values never span longs; {@code fill} repeats the value through every bit of a
 * word, padding included), so {@link #getRaw()} hands the saved and network forms the same longs. It extends
 * {@code SimpleBitStorage} only so it can stand where the game's code expects one; the parent holds no data.
 */
public final class RustBitStorage extends SimpleBitStorage {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final ValueLayout.OfLong WORD = ValueLayout.JAVA_LONG;
    /**
     * A word read in place: Rust's words are always 8-byte aligned, so the access skips the alignment check the
     * aligned layout makes on every read (the address is not a constant the JIT could check once).
     */
    private static final ValueLayout.OfLong AT = ValueLayout.JAVA_LONG_UNALIGNED;

    /** This storage's words, for bulk copies. */
    private final MemorySegment words;
    /** Their address in {@link Rust#ALL}: a word is one load from a constant segment, as from a heap array. */
    private final long base;
    private final int count;
    private final int bits;
    private final long mask;
    private final int size;
    private final int valuesPerLong;
    /** ceil(2^32 / valuesPerLong): index / valuesPerLong as a multiply, as vanilla divides (exact below 2^26). */
    private final long divide;

    // The game constructs storages through these, in place of SimpleBitStorage's constructors (Agent).
    public static SimpleBitStorage create(int bits, int size) {
        return new RustBitStorage(bits, size, null);
    }

    public static SimpleBitStorage create(int bits, int size, long[] data) {
        return new RustBitStorage(bits, size, data);
    }

    public static SimpleBitStorage create(int bits, int size, int[] values) {
        RustBitStorage s = new RustBitStorage(bits, size, null);
        s.pack(values);
        return s;
    }

    private RustBitStorage(int bits, int size, long[] data) {
        super(1, 0, new long[0]);
        Validate.inclusiveBetween(1L, 32L, bits);
        this.bits = bits;
        this.size = size;
        this.mask = (1L << bits) - 1L;
        this.valuesPerLong = (char) (64 / bits);
        this.count = (size + valuesPerLong - 1) / valuesPerLong;
        this.divide = ((1L << 32) + valuesPerLong - 1) / valuesPerLong;
        if (data != null && data.length != count) {
            throw invalidLength(data.length, count);
        }
        this.words = Rust.alloc(count);
        this.base = words.address();
        if (data != null) {
            MemorySegment.copy(data, 0, words, WORD, 0, count);
        }
        MemorySegment freed = words;
        int n = count;
        CLEANER.register(this, () -> Rust.free(freed, n));
    }

    /** The exception vanilla throws for words of the wrong length (its constructor is private: reflection). */
    private static RuntimeException invalidLength(int got, int expected) {
        String message = "Invalid length given for storage, got: " + got + " but expected: " + expected;
        try {
            var ctor = SimpleBitStorage.InitializationException.class.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(message);
        } catch (ReflectiveOperationException e) {
            return new IllegalArgumentException(message);
        }
    }

    /** As SimpleBitStorage(int, int, int[]): each long's values from the top one down, the tail last. */
    private void pack(int[] values) {
        int cell = 0;
        int i = 0;
        for (; i <= size - valuesPerLong; i += valuesPerLong) {
            long w = 0L;
            for (int j = valuesPerLong - 1; j >= 0; j--) {
                w = w << bits | values[i + j] & mask;
            }
            words.setAtIndex(WORD, cell++, w);
        }
        int rest = size - i;
        if (rest > 0) {
            long w = 0L;
            for (int j = rest - 1; j >= 0; j--) {
                w = w << bits | values[i + j] & mask;
            }
            words.setAtIndex(WORD, cell, w);
        }
    }

    private int cellIndex(int index) {
        return (int) ((index & 0xFFFFFFFFL) * divide >>> 32);
    }

    // The index is checked against the size first, as vanilla checks it, so a cell is always one of this
    // storage's words.
    private long word(int cell) {
        return Rust.ALL.get(AT, base + ((long) cell << 3));
    }

    private void word(int cell, long value) {
        Rust.ALL.set(AT, base + ((long) cell << 3), value);
    }

    @Override
    public int getAndSet(int index, int value) {
        Validate.inclusiveBetween(0L, size - 1, index);
        Validate.inclusiveBetween(0L, mask, value);
        int cell = cellIndex(index);
        long w = word(cell);
        int shift = (index - cell * valuesPerLong) * bits;
        int old = (int) (w >> shift & mask);
        word(cell, w & ~(mask << shift) | (value & mask) << shift);
        return old;
    }

    @Override
    public void set(int index, int value) {
        Validate.inclusiveBetween(0L, size - 1, index);
        Validate.inclusiveBetween(0L, mask, value);
        int cell = cellIndex(index);
        long w = word(cell);
        int shift = (index - cell * valuesPerLong) * bits;
        word(cell, w & ~(mask << shift) | (value & mask) << shift);
    }

    @Override
    public void fill(int value) {
        Validate.inclusiveBetween(0L, mask, value);
        long w = value & mask;
        for (int width = bits; width < 64; width <<= 1) {
            w |= w << width;
        }
        words.fill((byte) 0);
        for (int i = 0; i < count; i++) {
            words.setAtIndex(WORD, i, w);
        }
    }

    @Override
    public int get(int index) {
        Validate.inclusiveBetween(0L, size - 1, index);
        int cell = cellIndex(index);
        long w = word(cell);
        int shift = (index - cell * valuesPerLong) * bits;
        return (int) (w >> shift & mask);
    }

    /** A copy of the words, as the saved and network forms take them. */
    @Override
    public long[] getRaw() {
        return words.toArray(WORD);
    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public int getBits() {
        return bits;
    }

    @Override
    public void getAll(IntConsumer output) {
        int done = 0;
        for (int cell = 0; cell < count; cell++) {
            long w = word(cell);
            for (int j = 0; j < valuesPerLong; j++) {
                output.accept((int) (w & mask));
                w >>= bits;
                if (++done >= size) {
                    return;
                }
            }
        }
    }

    @Override
    public void unpack(int[] output) {
        int i = 0;
        for (int cell = 0; cell < count && i < size; cell++) {
            long w = word(cell);
            for (int j = 0; j < valuesPerLong && i < size; j++) {
                output[i++] = (int) (w & mask);
                w >>= bits;
            }
        }
    }

    @Override
    public BitStorage copy() {
        return create(bits, size, getRaw());
    }

    private record Lent(RustBitStorage storage, long[] array) {}

    private static final ThreadLocal<java.util.ArrayList<Lent>> LENT = ThreadLocal.withInitial(java.util.ArrayList::new);

    /**
     * The game's {@code storage.getRaw()} (Agent): a heap copy of the words, remembered so that {@link #flush()}
     * writes back what the calling method wrote into it. Vanilla writes into that array in place in two places,
     * {@code PalettedContainer.read} (a section from the network) and {@code Heightmap.setRawData}.
     */
    public static long[] raw(BitStorage storage) {
        long[] array = storage.getRaw();
        if (storage instanceof RustBitStorage rust) {
            LENT.get().add(new Lent(rust, array));
        }
        return array;
    }

    /** At the end of a method that took raw arrays: each one it changed goes back into Rust's memory. */
    public static void flush() {
        var lent = LENT.get();
        for (Lent l : lent) {
            if (l.array().length == l.storage().count
                    && MemorySegment.ofArray(l.array()).mismatch(l.storage().words) != -1) {
                MemorySegment.copy(l.array(), 0, l.storage().words, WORD, 0, l.storage().count);
            }
        }
        lent.clear();
    }

    // For the probe: the memory Rust owns.
    public MemorySegment words() {
        return words;
    }

    public int wordCount() {
        return count;
    }
}
