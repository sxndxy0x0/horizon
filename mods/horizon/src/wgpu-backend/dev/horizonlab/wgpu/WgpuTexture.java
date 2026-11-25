package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

/** A wgpu texture; its views and samplers are in this file too. */
final class WgpuTexture extends BaseGpuTexture {
    private final WgpuDevice device;
    final MemorySegment handle;
    private boolean closed;

    WgpuTexture(WgpuDevice device, String label, int usage, GpuFormat format, int width, int height, int layers,
            int mips) {
        super(usage, label, format, width, height, layers, mips);
        this.device = device;
        this.handle = Native.textureCreate(device.ctx, format.ordinal(), usage, width, height, layers, mips);
        if (Trace.ON) {
            System.out.println("[horizon-wgpu] texture " + label + " " + format + " " + width + "x" + height + " layers "
                    + layers + " mips " + mips + " usage " + usage);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("texture " + label + ": " + Native.error(device.ctx));
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.encoder.freeLater(() -> Native.textureFree(handle));
        }
    }

    static final class View extends BaseGpuTextureView {
        private final WgpuDevice device;
        final MemorySegment handle;
        private boolean closed;

        View(WgpuDevice device, GpuTexture texture, int baseMip, int mips) {
            super(texture, baseMip, mips);
            this.device = device;
            this.handle = Native.viewCreate(device.ctx, ((WgpuTexture) texture).handle, baseMip, mips);
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("texture view: " + Native.error(device.ctx));
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                device.encoder.freeLater(() -> Native.viewFree(handle));
            }
        }
    }

    static final class Sampler implements GpuSampler {
        private final WgpuDevice device;
        final MemorySegment handle;
        private final AddressMode u;
        private final AddressMode v;
        private final FilterMode min;
        private final FilterMode mag;
        private final int anisotropy;
        private final OptionalDouble maxLod;
        private boolean closed;

        Sampler(WgpuDevice device, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy,
                OptionalDouble maxLod) {
            this.device = device;
            this.u = u;
            this.v = v;
            this.min = min;
            this.mag = mag;
            this.anisotropy = anisotropy;
            this.maxLod = maxLod;
            if (Trace.ON) {
                System.out.println("[horizon-wgpu] sampler " + u + " " + v + " min " + min + " mag " + mag + " aniso "
                        + anisotropy + " maxLod " + maxLod);
            }
            this.handle = Native.samplerCreate(device.ctx, u == AddressMode.REPEAT ? 0 : 1,
                    v == AddressMode.REPEAT ? 0 : 1, min == FilterMode.NEAREST ? 0 : 1,
                    mag == FilterMode.NEAREST ? 0 : 1, anisotropy, maxLod.orElse(-1.0));
        }

        @Override
        public AddressMode getAddressModeU() {
            return u;
        }

        @Override
        public AddressMode getAddressModeV() {
            return v;
        }

        @Override
        public FilterMode getMinFilter() {
            return min;
        }

        @Override
        public FilterMode getMagFilter() {
            return mag;
        }

        @Override
        public int getMaxAnisotropy() {
            return anisotropy;
        }

        @Override
        public OptionalDouble getMaxLod() {
            return maxLod;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                device.encoder.freeLater(() -> Native.samplerFree(handle));
            }
        }
    }
}
