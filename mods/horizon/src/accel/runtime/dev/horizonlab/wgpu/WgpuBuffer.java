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
    /** An index buffer's CPU copy holds what the GPU has (no GPU copy wrote into it since): a triangle fan drawn
     * through it is turned into a triangle list from it (see {@link WgpuRenderPass#drawIndexed}). */
    private boolean indexCopy;
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
        indexCopy = (usage & GpuBuffer.USAGE_INDEX) != 0;
        if (mappable || indexCopy) {
            shadow = Arena.ofAuto().allocate(Math.max(16, (size + 15) & ~15L), 16);
        }
    }

    /** The game wrote {@code data} at {@code offset} (it goes to the GPU too): the CPU copy follows. */
    void written(long offset, ByteBuffer data) {
        if (shadow != null) {
            MemorySegment.copy(MemorySegment.ofBuffer(data), 0, shadow, offset, data.remaining());
        }
    }

    /** A GPU copy wrote into the buffer: its CPU copy no longer follows (until a read mapping fills it). */
    void gpuWritten() {
        indexCopy = false;
    }

    /** The CPU copy as it is, or null when there is none (for a fan's indirect draws: see WgpuRenderPass). */
    MemorySegment cpuCopy() {
        return shadow;
    }

    /** This index buffer's indices as the GPU has them, or null when there is no such copy. */
    MemorySegment indexCopy() {
        return indexCopy ? shadow : null;
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
