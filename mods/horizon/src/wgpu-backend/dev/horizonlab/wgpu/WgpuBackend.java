package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.lwjgl.sdl.SDLVideo;

/**
 * The game's third graphics backend, beside its OpenGL and Vulkan ones: renderpearl's frontend (validation, shader
 * compiling to SPIR-V) over a device that draws with wgpu. {@link Agent} puts it first in the backends the game
 * tries.
 */
public final class WgpuBackend implements GpuBackend {
    @Override
    public String getName() {
        return "Horizon wgpu";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        try {
            Native.load();
        } catch (RuntimeException | LinkageError e) {
            throw new BackendCreationException("horizon-wgpu-backend: " + e.getMessage(),
                    BackendCreationException.Reason.OTHER);
        }
    }

    @Override
    public void unloadLibrary() {}

    /** A plain window: wgpu makes its own surface on it (no OpenGL context, no Vulkan flag). */
    @Override
    public long createWindow(String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        // The game makes its window after the device. A small hidden window of the same kind lets the device pick
        // an adapter, and a graphics API, that can present to it.
        long probe = SDLVideo.SDL_CreateWindow("", 16, 16, SDLVideo.SDL_WINDOW_HIDDEN);
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment target = probe == 0L ? java.lang.foreign.MemorySegment.NULL
                    : WindowTarget.of(probe, arena);
            return new FrontendGpuDevice(new WgpuDevice(target));
        } catch (RuntimeException e) {
            throw new BackendCreationException("horizon-wgpu-backend: " + e.getMessage(),
                    BackendCreationException.Reason.OTHER);
        } finally {
            if (probe != 0L) {
                SDLVideo.SDL_DestroyWindow(probe);
            }
        }
    }
}
