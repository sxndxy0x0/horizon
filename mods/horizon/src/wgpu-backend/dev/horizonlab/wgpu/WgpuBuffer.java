package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A wgpu buffer. A buffer the game maps has a CPU copy in Java's memory (Rust never keeps a pointer into it): a
 * write mapping is that copy, sent to the GPU in command order when the mapping closes (for a transient block,
 * ahead of the frame's commands); a read mapping fills it from the GPU first.
 */
final class WgpuBuffer extends BaseGpuBuffer {
    final WgpuDevice device;
    final MemorySegment handle;
    private final boolean transientBlock;
    private MemorySegment shadow;
    private boolean closed;

    WgpuBuffer(WgpuDevice device, int usage, long size, boolean transientBlock) {
        super(usage, size);
        this.device = device;
        this.transientBlock = transientBlock;
        boolean mappable = transientBlock
                || (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0;
        this.handle = Native.bufferCreate(device.ctx, size, usage);
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("buffer: " + Native.error(device.ctx));
        }
        if (mappable) {
            shadow = Arena.ofAuto().allocate(Math.max(16, (size + 15) & ~15L), 16);
        }
    }

    /** The CPU copy from {@code offset} for {@code length} bytes, as a little-endian buffer the game writes. */
    ByteBuffer shadow(long offset, long length) {
        if (shadow == null) {
            throw new IllegalStateException("buffer has no CPU copy");
        }
        return shadow.asSlice(offset, length).asByteBuffer().order(ByteOrder.nativeOrder());
    }

    /** A region of a transient block was written: it goes up at the next submit. */
    void dirty(long offset, long length) {
        Native.bufferDirty(device.ctx, handle, offset, shadow.asSlice(offset, length));
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        Trace.count("map " + (read ? "r" : "") + (write ? "w" : "") + (transientBlock ? " transient" : "") + " usage "
                + usage());
        if (read && !Native.bufferRead(device.ctx, handle, offset, shadow.asSlice(offset, length))) {
            throw new IllegalStateException("read mapping: " + Native.error(device.ctx));
        }
        ByteBuffer data = shadow(offset, length);
        Runnable onClose = !write ? () -> { }
                : transientBlock ? () -> dirty(offset, length)
                : () -> {
                    Trace.count("unmap upload");
                    Native.bufferWrite(device.ctx, handle, offset, shadow.asSlice(offset, length));
                };
        return new GpuBufferSlice.MappedView(slice(offset, length), data, onClose);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.encoder.freeLater(() -> Native.bufferFree(handle));
        }
    }
}
