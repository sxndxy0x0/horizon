package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A render pipeline: the game's SPIR-V and state, handed to Rust as words. A pipeline Rust refuses (a shader it
 * cannot take yet) stays here with no handle: its draws are skipped and the reason is printed once.
 */
final class WgpuPipeline implements BackendRenderPipeline {
    final String name;
    final MemorySegment handle;
    /** A triangle fan: wgpu has none, so its draws go as triangle lists. */
    boolean fan;
    private boolean closed;
    boolean warned;

    private WgpuPipeline(String name, MemorySegment handle) {
        this.name = name;
        this.handle = handle;
    }

    boolean usable() {
        return !handle.equals(MemorySegment.NULL);
    }

    static WgpuPipeline create(WgpuDevice device, BackendRenderPipeline.CreateInfo info) {
        ByteBuffer vs = null;
        ByteBuffer fs = null;
        for (BackendRenderPipeline.CreateInfo.Shader s : info.shaders()) {
            ByteBuffer spv = s.module().spv().duplicate();
            if (s.module().type() == ShaderType.VERTEX) {
                vs = spv;
            } else if (s.module().type() == ShaderType.FRAGMENT) {
                fs = spv;
            }
        }
        if (vs == null || fs == null) {
            System.out.println("[horizon-wgpu] " + info.name() + ": needs a vertex and a fragment shader");
            return new WgpuPipeline(info.name(), MemorySegment.NULL);
        }
        List<Integer> d = new ArrayList<>();
        DepthStencilState depth = info.depthStencilState();
        d.add(info.pushConstantsSize());
        d.add(depth == null ? CompareOp.ALWAYS_PASS.ordinal() : depth.depthTest().ordinal());
        d.add(depth != null && depth.writeDepth() ? 1 : 0);
        d.add(info.cull() ? 1 : 0);
        d.add(info.primitiveTopology().ordinal());
        d.add(info.polygonMode().ordinal());
        d.add(info.vertexBuffers().size());
        for (BackendRenderPipeline.CreateInfo.VertexBuffer b : info.vertexBuffers()) {
            d.add(b.bufferSlot());
            d.add(b.stride());
            d.add(b.stepRate());
        }
        d.add(info.attribBindings().size());
        for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
            d.add(a.bufferSlot());
            d.add(a.location());
            d.add(a.offset());
            d.add(a.format().ordinal());
        }
        d.add(info.colorTargetStates().size());
        for (ColorTargetState t : info.colorTargetStates()) {
            d.add(t == null ? 0 : 1);
            BlendFunction blend = t == null ? null : t.blendFunction().orElse(null);
            d.add(blend == null ? 0 : 1);
            for (BlendEquation eq : blend == null ? new BlendEquation[] {null, null}
                    : new BlendEquation[] {blend.color(), blend.alpha()}) {
                d.add(eq == null ? 0 : eq.sourceFactor().ordinal());
                d.add(eq == null ? 0 : eq.destFactor().ordinal());
                d.add(eq == null ? 0 : eq.op().ordinal());
            }
            d.add(t == null ? 0 : t.writeMask());
        }
        // Texel buffers become storage buffers (crates/horizon-wgpu-backend/src/texel.rs), read by their format.
        List<int[]> texels = new ArrayList<>();
        for (int i = 0; i < info.uniforms().size(); i++) {
            BindGroupLayout.UniformDescription u = info.uniforms().get(i);
            if (u.type() == UniformType.TEXEL_BUFFER && u.gpuFormat() != null) {
                String c = u.gpuFormat().componentType().name();
                texels.add(new int[] {i, u.gpuFormat().blockSize(), c.startsWith("SINT") || c.startsWith("SNORM") ? 1 : 0});
            }
        }
        d.add(texels.size());
        for (int[] t : texels) {
            d.add(t[0]);
            d.add(t[1]);
            d.add(t[2]);
        }
        int[] desc = d.stream().mapToInt(Integer::intValue).toArray();
        MemorySegment handle = Native.pipelineCreate(device.device, info.name(), vs, fs, desc,
                depth == null ? 0f : depth.depthBiasScaleFactor(), depth == null ? 0f : depth.depthBiasConstant());
        if (handle.equals(MemorySegment.NULL)) {
            System.out.println("[horizon-wgpu] pipeline " + info.name() + " refused: " + Native.pipelineError());
        }
        WgpuPipeline p = new WgpuPipeline(info.name(), handle);
        p.fan = info.primitiveTopology() == com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLE_FAN;
        return p;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (usable()) {
                Native.pipelineFree(handle);
            }
        }
    }
}
