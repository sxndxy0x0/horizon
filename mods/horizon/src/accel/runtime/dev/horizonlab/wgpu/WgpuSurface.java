package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The window's wgpu surface. The game's frame is drawn into it flipped back to the screen's row order. */
final class WgpuSurface implements GpuSurfaceBackend {
    private final WgpuDevice device;
    private final MemorySegment handle;
    private boolean closed;

    WgpuSurface(WgpuDevice device, MemorySegment handle) {
        this.device = device;
        this.handle = handle;
    }

    @Override
    public void configure(GpuSurface.Configuration c) throws SurfaceException {
        if (!Native.surfaceConfigure(device.ctx, handle, c.width(), c.height(), c.presentMode().ordinal())) {
            throw new SurfaceException("configure: " + Native.error(device.ctx));
        }
    }

    @Override
    public boolean isSuboptimal() {
        return Native.surfaceSuboptimal(handle);
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        for (int attempt = 0; ; attempt++) {
            int r = Native.surfaceAcquire(device.ctx, handle);
            if (r == 1) {
                return;
            }
            if (r == 0 && attempt < 3) {
                continue;
            }
            throw new SurfaceException(r == -1 ? "the surface is out of date" : r == 0 ? "no surface texture"
                    : "the surface was lost");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
        Native.surfaceBlit(device.ctx, handle, ((WgpuTexture.View) view).handle);
    }

    @Override
    public void present() {
        device.encoder.submitted(Native.surfacePresent(device.ctx, handle));
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        int bits = Native.surfaceModes(device.ctx, handle);
        List<GpuSurface.PresentMode> out = new ArrayList<>();
        for (GpuSurface.PresentMode m : GpuSurface.PresentMode.values()) {
            if ((bits & 1 << m.ordinal()) != 0) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Native.surfaceFree(handle);
        }
    }
}
