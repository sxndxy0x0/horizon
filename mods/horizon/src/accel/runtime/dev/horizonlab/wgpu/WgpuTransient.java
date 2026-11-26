package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A frame's short-lived GPU memory: regions bumped out of large blocks, each a wgpu buffer with a CPU copy. A region
 * is written once a frame, so what the game writes into it goes up at submit, ahead of the frame's commands. A
 * block is reused once the GPU has finished the frame that used it.
 */
final class WgpuTransient implements TransientMemory {
    private static final long BLOCK = 4L << 20;
    private static final int USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    private final WgpuDevice device;
    private final List<WgpuBuffer> free = new ArrayList<>();
    private final List<WgpuBuffer> used = new ArrayList<>();
    /** Blocks waiting on a submission: {serial, blocks}. */
    private final ArrayDeque<Object[]> inFlight = new ArrayDeque<>();
    private WgpuBuffer current;
    private long at;
    /** CPU memory for {@code allocateCpu}: frame blocks too, held until the frame is done (the game may keep only
     * an address into it, as the other backends allow). */
    private final List<java.lang.foreign.MemorySegment> cpuFree = new ArrayList<>();
    private final List<java.lang.foreign.MemorySegment> cpuUsed = new ArrayList<>();
    private java.lang.foreign.MemorySegment cpuCurrent;
    private long cpuAt;

    WgpuTransient(WgpuDevice device) {
        this.device = device;
    }

    private static long alignUp(long v, long a) {
        return a <= 1 ? v : (v + a - 1) / a * a;
    }

    private GpuBufferSlice allocate(long size, long alignment, long minimum, long elementSize) {
        long align = Math.max(4, Math.max(alignment, elementSize > 0 ? elementSize : 1));
        // Element data (a texel buffer, read as a storage buffer) starts where WebGPU allows a storage binding.
        if (elementSize > 0 && elementSize < 16) {
            align = Math.max(align, 256);
        }
        long length = Math.max(size, minimum);
        if (current == null || alignUp(at, align) + length > current.size()) {
            long need = Math.max(BLOCK, alignUp(length, 256));
            WgpuBuffer block = null;
            for (int i = 0; i < free.size(); i++) {
                if (free.get(i).size() >= need) {
                    block = free.remove(i);
                    break;
                }
            }
            if (block == null) {
                block = new WgpuBuffer(device, USAGE, need, true);
            }
            used.add(block);
            current = block;
            at = 0;
        }
        long offset = alignUp(at, align);
        at = offset + length;
        return current.slice(offset, length);
    }

    /** The blocks used since the last submit wait on this one. */
    void rotate(long serial) {
        if (!used.isEmpty() || !cpuUsed.isEmpty()) {
            inFlight.add(new Object[] {serial, new ArrayList<>(used), new ArrayList<>(cpuUsed)});
            used.clear();
            cpuUsed.clear();
        }
        current = null;
        at = 0;
        cpuCurrent = null;
        cpuAt = 0;
    }

    @SuppressWarnings("unchecked")
    void release(long completed) {
        while (!inFlight.isEmpty() && (long) inFlight.peek()[0] <= completed) {
            Object[] done = inFlight.poll();
            free.addAll((List<WgpuBuffer>) done[1]);
            cpuFree.addAll((List<java.lang.foreign.MemorySegment>) done[2]);
        }
    }

    private GpuBufferSlice.MappedView mapped(GpuBufferSlice slice) {
        WgpuBuffer block = (WgpuBuffer) slice.buffer();
        ByteBuffer data = block.shadow(slice.offset(), slice.length());
        return new GpuBufferSlice.MappedView(slice, data, () -> {
            Trace.count("transient close");
            block.dirty(slice.offset(), slice.length());
        });
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, long minimum, long elementSize) {
        long total = 0;
        for (ByteBuffer b : data) {
            total += b.remaining();
        }
        GpuBufferSlice slice = allocate(total, alignment, minimum, elementSize);
        WgpuBuffer block = (WgpuBuffer) slice.buffer();
        ByteBuffer out = block.shadow(slice.offset(), slice.length());
        for (ByteBuffer b : data) {
            out.put(b.duplicate());
        }
        block.dirty(slice.offset(), slice.length());
        return slice.slice(0, total);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        Trace.count("transient allocateCpu");
        long length = Math.max(1, Math.max(size, minimumAllocation));
        long align = Math.max(16, Math.max(alignment, elementSize > 0 ? elementSize : 1));
        if (cpuCurrent == null || alignUp(cpuAt, align) + length > cpuCurrent.byteSize()) {
            long need = Math.max(BLOCK, alignUp(length + align, 4096));
            java.lang.foreign.MemorySegment block = null;
            for (int i = 0; i < cpuFree.size(); i++) {
                if (cpuFree.get(i).byteSize() >= need) {
                    block = cpuFree.remove(i);
                    break;
                }
            }
            if (block == null) {
                block = Arena.ofAuto().allocate(need, 4096);
            }
            cpuUsed.add(block);
            cpuCurrent = block;
            cpuAt = 0;
        }
        long offset = alignUp(cpuAt, align);
        cpuAt = offset + length;
        return cpuCurrent.asSlice(offset, size).asByteBuffer().order(ByteOrder.nativeOrder());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation,
            long elementSize) {
        Trace.count("transient allocateStaging");
        return mapped(allocate(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        Trace.count("transient allocateGpu");
        return allocate(size, alignment, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation,
            long elementSize) {
        Trace.count("transient allocateGpuMapped");
        return mapped(allocate(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation,
            long elementSize) {
        Trace.count("transient uploadStaging");
        return upload(data, alignment, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation,
            long elementSize) {
        Trace.count("transient uploadGpu");
        return upload(data, alignment, minimumAllocation, elementSize);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        Trace.count("transient multiUploadStaging");
        List<GpuBufferSlice> out = new ArrayList<>(data.size());
        for (ByteBuffer b : data) {
            out.add(upload(List.of(b), alignment, 0, 0));
        }
        return out;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        Trace.count("transient multiUploadGpu");
        return multiUploadStaging(data, alignment, usage);
    }
}
