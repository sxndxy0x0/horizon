package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/** The renderpearl device over a wgpu device (crates/horizon-wgpu-backend). */
final class WgpuDevice implements GpuDeviceBackend {
    /** {@code GpuFormat}'s constants in the order the Rust side maps them by. */
    private static final String[] FORMATS = {"R8_UNORM", "R8_SNORM", "RG8_UNORM", "RG8_SNORM", "RGB8_UNORM",
        "RGB8_SNORM", "RGBA8_UNORM", "RGBA8_SNORM", "R16_UNORM", "R16_SNORM", "RG16_UNORM", "RG16_SNORM",
        "RGB16_UNORM", "RGB16_SNORM", "RGBA16_UNORM", "RGBA16_SNORM", "R8_UINT", "R8_SINT", "RG8_UINT", "RG8_SINT",
        "RGB8_UINT", "RGB8_SINT", "RGBA8_UINT", "RGBA8_SINT", "R16_UINT", "R16_SINT", "RG16_UINT", "RG16_SINT",
        "RGB16_UINT", "RGB16_SINT", "RGBA16_UINT", "RGBA16_SINT", "R32_UINT", "R32_SINT", "RG32_UINT", "RG32_SINT",
        "RGB32_UINT", "RGB32_SINT", "RGBA32_UINT", "RGBA32_SINT", "R16_FLOAT", "RG16_FLOAT", "RGB16_FLOAT",
        "RGBA16_FLOAT", "R32_FLOAT", "RG32_FLOAT", "RGB32_FLOAT", "RGBA32_FLOAT", "RGB10A2_UNORM", "RGB10A2_UINT",
        "RG11B10_FLOAT", "D32_FLOAT", "D32_FLOAT_S8_UINT", "D24_UNORM_S8_UINT", "D16_UNORM", "S8_UINT"};

    final MemorySegment ctx;
    /** The device alone, for compiling pipelines off the render thread (see Native). */
    final MemorySegment device;
    final WgpuEncoder encoder;
    private final Map<String, String> info = new HashMap<>();

    WgpuDevice() {
        String[] names = Arrays.stream(GpuFormat.values()).map(Enum::name).toArray(String[]::new);
        if (!Arrays.equals(names, FORMATS)) {
            throw new IllegalStateException("GpuFormat changed: " + Arrays.toString(names));
        }
        this.ctx = Native.create();
        if (ctx.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("no wgpu device");
        }
        for (String line : Native.info(ctx).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                info.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        System.out.println("[horizon-wgpu] device: " + info);
        this.device = Native.device(ctx);
        this.encoder = new WgpuEncoder(this);
    }

    private int intInfo(String key, int fallback) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Long.parseLong(info.getOrDefault(key, "")));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public GpuSurfaceBackend createSurface(long window, BooleanSupplier isIconified) {
        int props = SDLVideo.SDL_GetWindowProperties(window);
        long hwnd = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        long instance = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_INSTANCE_POINTER,
                0L);
        System.out.println("[horizon-wgpu] window " + Long.toHexString(window) + ": hwnd " + Long.toHexString(hwnd)
                + ", hinstance " + Long.toHexString(instance));
        if (hwnd == 0L) {
            throw new IllegalStateException("the window has no Win32 handle (this backend runs on Windows for now)");
        }
        MemorySegment s = Native.surfaceCreate(ctx, hwnd, instance);
        if (s.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("surface: " + Native.error(ctx));
        }
        return new WgpuSurface(this, s);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy,
            OptionalDouble maxLod) {
        return new WgpuTexture.Sampler(this, u, v, min, mag, anisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int layers,
            int mips) {
        return new WgpuTexture(this, label, usage, format, width, height, layers, mips);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
        return new WgpuTexture.View(this, texture, baseMip, mips);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return new WgpuBuffer(this, usage, size, false);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        WgpuBuffer b = new WgpuBuffer(this, usage, data.remaining(), false);
        Native.bufferWrite(ctx, b.handle, 0, data);
        return b;
    }

    @Override
    public List<String> getLastDebugMessages() {
        String m = Native.messages(ctx);
        return m.isEmpty() ? List.of() : List.of(m.split("\n"));
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
        WgpuPipeline p = WgpuPipeline.create(this, info);
        return () -> p;
    }

    @Override
    public void close() {
        Native.destroy(ctx);
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] out = new OptionalLong[count];
                Arrays.fill(out, OptionalLong.empty());
                return out;
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        String type = info.getOrDefault("type", "");
        DeviceType deviceType = switch (type) {
            case "DiscreteGpu" -> DeviceType.DISCRETE;
            case "IntegratedGpu" -> DeviceType.INTEGRATED;
            case "VirtualGpu" -> DeviceType.VIRTUAL;
            case "Cpu" -> DeviceType.CPU;
            default -> DeviceType.OTHER;
        };
        long maxBuffer;
        try {
            maxBuffer = Long.parseLong(info.getOrDefault("max_buffer", "268435456"));
        } catch (NumberFormatException e) {
            maxBuffer = 1L << 28;
        }
        DeviceLimits limits = new DeviceLimits(16, intInfo("min_uniform_align", 256), intInfo("max_texture", 8192),
                maxBuffer, 0, intInfo("max_colors", 8), 0);
        // No multi-draw or indirect draws: the frontend draws one by one. Instances may start past zero. Wireframe
        // when the GPU draws lines from triangles (wgpu's POLYGON_MODE_LINE).
        boolean wireframe = "true".equals(info.get("wireframe"));
        DeviceFeatures features = new DeviceFeatures(wireframe, false, false, false, false, false, true, false);
        return new DeviceInfo(info.getOrDefault("name", "wgpu"), info.getOrDefault("vendor", ""),
                info.getOrDefault("driver", ""), true, "Horizon wgpu (" + info.getOrDefault("backend", "?") + ")",
                1.0f, limits, features, Set.of(), new HintsAndWorkarounds(false, false, false, true), deviceType);
    }
}
