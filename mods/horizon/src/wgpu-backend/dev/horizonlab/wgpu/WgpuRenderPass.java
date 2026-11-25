package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/** A render pass, recorded into the frame's wgpu command encoder as the game calls it. */
final class WgpuRenderPass implements RenderPassBackend {
    private final MemorySegment ctx;
    /** False while the pipeline set is one wgpu refused: its draws are skipped. */
    private boolean drawable;

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
        Native.passVertex(ctx, slot, ((WgpuBuffer) slice.buffer()).handle, slice.offset(), slice.length());
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        Native.passIndex(ctx, ((WgpuBuffer) buffer).handle, type == IndexType.INT);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (drawable) {
            Native.passDrawIndexed(ctx, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (drawable) {
            Native.passDraw(ctx, vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    // The device reports no multi-draw or indirect draws, so the frontend makes plain draws instead.

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("multi-draw is not reported");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets,
            int drawCount) {
        throw new UnsupportedOperationException("multi-draw is not reported");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("indirect draws are not reported");
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("multi-draw is not reported");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("multi-draw is not reported");
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("indirect draws are not reported");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {}
}
