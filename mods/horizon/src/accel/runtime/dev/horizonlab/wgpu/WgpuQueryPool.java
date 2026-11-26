package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.OptionalLong;

/**
 * A timestamp query pool on wgpu's timestamp queries, in nanoseconds (as the game's OpenGL backend has them; the
 * device reports a timestamp period of 1). With no timestamp queries on the device ({@code handle} NULL) every
 * value stays empty, as one not done yet.
 */
final class WgpuQueryPool implements GpuQueryPool {
    final WgpuDevice device;
    final MemorySegment handle;
    private final int size;
    private boolean closed;

    WgpuQueryPool(WgpuDevice device, int size) {
        this.device = device;
        this.size = size;
        this.handle = Native.timestampsCreate(device.ctx, size);
    }

    /** Writes the GPU's time into {@code index} where the commands have got to (in the open pass, or between). */
    void write(int index) {
        if (!handle.equals(MemorySegment.NULL) && !closed) {
            Native.timestampWrite(device.ctx, handle, index);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public OptionalLong getValue(int index) {
        return getValues(index, 1)[0];
    }

    @Override
    public OptionalLong[] getValues(int index, int count) {
        if (index < 0 || count < 0 || index + count > size) {
            throw new IndexOutOfBoundsException(index + "+" + count + " of " + size);
        }
        OptionalLong[] out = new OptionalLong[count];
        Arrays.fill(out, OptionalLong.empty());
        if (handle.equals(MemorySegment.NULL) || closed || count == 0) {
            return out;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment values = arena.allocate(8L * count, 8);
            Native.timestampsRead(device.ctx, handle, index, count, values);
            for (int i = 0; i < count; i++) {
                long v = values.getAtIndex(ValueLayout.JAVA_LONG, i);
                if (v >= 0) {
                    out[i] = OptionalLong.of(v);
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (!handle.equals(MemorySegment.NULL)) {
                device.encoder.freeLater(() -> Native.timestampsFree(handle));
            }
        }
    }
}
