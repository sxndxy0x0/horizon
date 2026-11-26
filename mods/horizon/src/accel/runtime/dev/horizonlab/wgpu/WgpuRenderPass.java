package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/** A render pass, recorded into the frame's wgpu command encoder as the game calls it. */
final class WgpuRenderPass implements RenderPassBackend {
    private final MemorySegment ctx;
    /** False while the pipeline set is one wgpu refused: its draws are skipped. */
    private boolean drawable;
    private WgpuPipeline pipeline;
    /** The index buffer bound, and whether its indices are 32-bit: an indexed fan is drawn from its CPU copy. */
    private WgpuBuffer index;
    private boolean int32;
    private static boolean fanWarned;

    WgpuRenderPass(MemorySegment ctx) {
        this.ctx = ctx;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {}

    @Override
    public void popDebugGroup() {}

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        WgpuPipeline p = (WgpuPipeline) pipeline;
        this.pipeline = p;
        drawable = p.usable();
        if (drawable) {
            Native.passPipeline(ctx, p.handle);
        } else if (!p.warned) {
            p.warned = true;
            System.out.println("[horizon-wgpu] skipping draws of pipeline " + p.name);
        }
    }

    @Override
    public void setUniform(int index, Object value) {
        if (value instanceof GpuBufferSlice slice) {
            Native.passBuffer(ctx, index, ((WgpuBuffer) slice.buffer()).handle, slice.offset(), slice.length());
        } else if (value instanceof TextureViewAndSampler ts) {
            Native.passTexture(ctx, index, ((WgpuTexture.View) ts.view()).handle,
                    ((WgpuTexture.Sampler) ts.sampler()).handle);
        } else if (value instanceof GpuBuffer buffer) {
            Native.passBuffer(ctx, index, ((WgpuBuffer) buffer).handle, 0, buffer.size());
        } else {
            throw new IllegalArgumentException("uniform of " + (value == null ? "null" : value.getClass()));
        }
    }

    @Override
    public void pushConstants(ByteBuffer data) {
        Native.passImmediates(ctx, data);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        Native.passScissor(ctx, x, y, width, height, true);
    }

    @Override
    public void disableScissor() {
        Native.passScissor(ctx, 0, 0, 0, 0, false);
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slice == null) {
            // Unbound (the clouds make their vertices in the shader): wgpu needs nothing for that.
            return;
        }
        Native.passVertex(ctx, slot, ((WgpuBuffer) slice.buffer()).handle, slice.offset(), slice.length());
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        index = (WgpuBuffer) buffer;
        int32 = type == IndexType.INT;
        Native.passIndex(ctx, index.handle, int32);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (!drawable) {
            return;
        }
        if (pipeline.fan) {
            // wgpu has no fans: Rust makes the triangles from the indices. Without a CPU copy of them (a GPU copy
            // wrote the buffer), they are taken to be the game's sequential ones.
            MemorySegment indices = index == null ? null : index.indexCopy();
            if (indices == null && !fanWarned) {
                fanWarned = true;
                System.out.println("[horizon-wgpu] an indexed fan without a CPU copy of its indices: drawn as sequential");
            }
            Native.passDrawFanIndexed(ctx, indices, int32, indexCount, instanceCount, firstIndex, vertexOffset,
                    firstInstance);
        } else {
            Native.passDrawIndexed(ctx, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (drawable) {
            Native.passDraw(ctx, vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    // Multi-draw: the draws' parameters go to Rust in one call. A fan's draws go one by one (each becomes a list).

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (!drawable || drawCount <= 0) {
            return;
        }
        int at = drawParameters.position();
        if (pipeline.fan) {
            for (int i = 0; i < drawCount; i++) {
                drawIndexed(drawParameters.get(at + 3 * i + 1), instanceCount, drawParameters.get(at + 3 * i),
                        drawParameters.get(at + 3 * i + 2), firstInstance);
            }
            return;
        }
        multi(drawParameters, 3 * drawCount, drawCount, instanceCount, firstInstance, true);
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets,
            int drawCount) {
        // Separate arrays (OpenGL's shape: byte offsets into the index buffer), interleaved here.
        int size = int32 ? 4 : 2;
        IntBuffer p = IntBuffer.allocate(3 * drawCount);
        for (int i = 0; i < drawCount; i++) {
            p.put((int) (firstIndexOffsets.get(firstIndexOffsets.position() + i) / size));
            p.put(indexCounts.get(indexCounts.position() + i));
            p.put(vertexOffsets.get(vertexOffsets.position() + i));
        }
        multiDrawIndexed(p.flip(), 1, 0, drawCount);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (!drawable || drawCount <= 0) {
            return;
        }
        int at = drawParameters.position();
        if (pipeline.fan) {
            for (int i = 0; i < drawCount; i++) {
                draw(drawParameters.get(at + 2 * i + 1), instanceCount, drawParameters.get(at + 2 * i), firstInstance);
            }
            return;
        }
        multi(drawParameters, 2 * drawCount, drawCount, instanceCount, firstInstance, false);
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        IntBuffer p = IntBuffer.allocate(2 * drawCount);
        for (int i = 0; i < drawCount; i++) {
            p.put(firstVertices.get(firstVertices.position() + i));
            p.put(vertexCounts.get(vertexCounts.position() + i));
        }
        multiDraw(p.flip(), 1, 0, drawCount);
    }

    /** {@code ints} ints of draw parameters from the buffer's position, to Rust (copied when not in native memory). */
    private void multi(IntBuffer params, int ints, int drawCount, int instances, int firstInstance, boolean indexed) {
        if (params.isDirect()) {
            Native.passMultiDraw(ctx, MemorySegment.ofBuffer(params), drawCount, instances, firstInstance, indexed);
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment copy = arena.allocate(4L * ints, 4);
            for (int i = 0; i < ints; i++) {
                copy.setAtIndex(ValueLayout.JAVA_INT, i, params.get(params.position() + i));
            }
            Native.passMultiDraw(ctx, copy, drawCount, instances, firstInstance, indexed);
        }
    }

    // Indirect draws: the GPU reads the parameters. A fan's are read from the buffer's CPU copy instead, one draw at
    // a time; without one they are drawn as triangle lists.

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        if (drawable && !(pipeline.fan && fanIndirect(commands, drawCount, true))) {
            Native.passDrawIndirect(ctx, ((WgpuBuffer) commands.buffer()).handle, commands.offset(), drawCount, true);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        if (drawable && !(pipeline.fan && fanIndirect(commands, drawCount, false))) {
            Native.passDrawIndirect(ctx, ((WgpuBuffer) commands.buffer()).handle, commands.offset(), drawCount, false);
        }
    }

    /** A fan's indirect draws from the commands' CPU copy; false when there is none. */
    private boolean fanIndirect(GpuBufferSlice commands, int drawCount, boolean indexed) {
        MemorySegment cpu = ((WgpuBuffer) commands.buffer()).cpuCopy();
        if (cpu == null) {
            if (!fanWarned) {
                fanWarned = true;
                System.out.println("[horizon-wgpu] indirect fan draws without a CPU copy: drawn as triangle lists");
            }
            return false;
        }
        int stride = indexed ? 20 : 16;
        for (int i = 0; i < drawCount; i++) {
            long at = commands.offset() + (long) stride * i;
            int count = cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at);
            int instances = cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at + 4);
            int first = cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at + 8);
            if (indexed) {
                drawIndexed(count, instances, first, cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at + 12),
                        cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at + 16));
            } else {
                draw(count, instances, first, cpu.get(ValueLayout.JAVA_INT_UNALIGNED, at + 12));
            }
        }
        return true;
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        ((WgpuQueryPool) pool).write(index);
    }

    /** NeoForge's addition to {@code RenderPassBackend} (vanilla's has none: no {@code @Override}). */
    public void setViewport(int x, int y, int width, int height) {
        Native.passViewport(ctx, x, y, width, height);
    }
}
