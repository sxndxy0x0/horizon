package dev.horizonlab.wgpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * crates/horizon-wgpu-backend through FFM. Every call but pipeline creation holds this class's lock: the game
 * renders on one thread and the Rust side keeps one frame's state. Pipelines are compiled on the game's other
 * threads with the device alone, without the lock, so the render thread never waits behind a compile.
 */
final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static SymbolLookup lib;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    static final ValueLayout P = ValueLayout.ADDRESS;

    private static MethodHandle CREATE, DESTROY, INFO, ERROR, MESSAGES, SUBMIT, COMPLETED, WAIT, FENCE;
    private static MethodHandle BUF_CREATE, BUF_FREE, BUF_WRITE, BUF_DIRTY, BUF_READ, BUF_COPY;
    private static MethodHandle TEX_CREATE, TEX_FREE, VIEW_CREATE, VIEW_FREE, TEX_WRITE, BUF_TO_TEX, TEX_TO_BUF, TEX_COPY,
            CLEAR;
    private static MethodHandle SAMPLER_CREATE, SAMPLER_FREE, PIPELINE_CREATE, PIPELINE_FREE, DEVICE,
            PIPELINE_ERROR;
    private static MethodHandle PASS_BEGIN, PASS_END, PASS_PIPELINE, PASS_BUFFER, PASS_TEXTURE, PASS_IMMEDIATES,
            PASS_SCISSOR, PASS_VERTEX, PASS_INDEX, PASS_DRAW, PASS_DRAW_INDEXED;
    private static MethodHandle SURF_CREATE, SURF_FREE, SURF_CONFIGURE, SURF_MODES, SURF_ACQUIRE, SURF_SUBOPTIMAL,
            SURF_BLIT, SURF_PRESENT;

    private Native() {}

    private static MethodHandle h(String name, MemoryLayout ret, MemoryLayout... args) {
        MemorySegment symbol = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return LINKER.downcallHandle(symbol, ret == null ? FunctionDescriptor.ofVoid(args)
                : FunctionDescriptor.of(ret, args));
    }

    /** Loads the library at {@code hz.wgpu.lib}. */
    static synchronized void load() {
        if (lib != null) {
            return;
        }
        String path = System.getProperty("hz.wgpu.lib");
        if (path == null) {
            throw new IllegalStateException("hz.wgpu.lib is not set");
        }
        lib = SymbolLookup.libraryLookup(Path.of(path), Arena.global());
        CREATE = h("hz_create", P);
        DESTROY = h("hz_destroy", null, P);
        INFO = h("hz_info", I32, P, P, I32);
        ERROR = h("hz_error", I32, P, P, I32);
        MESSAGES = h("hz_messages", I32, P, P, I32);
        SUBMIT = h("hz_submit", I64, P);
        COMPLETED = h("hz_completed", I64, P);
        WAIT = h("hz_wait", I32, P, I64, I64);
        FENCE = h("hz_fence", I64, P);
        BUF_CREATE = h("hz_buffer_create", P, P, I64, I32);
        BUF_FREE = h("hz_buffer_free", null, P);
        BUF_WRITE = h("hz_buffer_write", null, P, P, I64, P, I64);
        BUF_DIRTY = h("hz_buffer_dirty", null, P, P, I64, P, I64);
        BUF_READ = h("hz_buffer_read", I32, P, P, I64, I64, P);
        BUF_COPY = h("hz_buffer_copy", null, P, P, I64, P, I64, I64);
        TEX_CREATE = h("hz_texture_create", P, P, I32, I32, I32, I32, I32, I32);
        TEX_FREE = h("hz_texture_free", null, P);
        VIEW_CREATE = h("hz_view_create", P, P, P, I32, I32);
        VIEW_FREE = h("hz_view_free", null, P);
        TEX_WRITE = h("hz_texture_write", null, P, P, P, I64, I32, I32, I32, I32, I32, I32);
        BUF_TO_TEX = h("hz_buffer_to_texture", null, P, P, I64, I32, I32, I32, P, I32, I32, I32, I32, I32, I32);
        TEX_TO_BUF = h("hz_texture_to_buffer", null, P, P, P, I64, I32, I32, I32, I32, I32);
        TEX_COPY = h("hz_texture_copy", null, P, P, P, I32, I32, I32, I32, I32, I32, I32);
        CLEAR = h("hz_clear", null, P, P, P, P, F64, I32, I32, I32, I32, I32);
        SAMPLER_CREATE = h("hz_sampler_create", P, P, I32, I32, I32, I32, I32, F64);
        SAMPLER_FREE = h("hz_sampler_free", null, P);
        PIPELINE_CREATE = h("hz_pipeline_create", P, P, P, I32, P, I64, P, I64, P, I32, F32, F32);
        DEVICE = h("hz_device", P, P);
        PIPELINE_ERROR = h("hz_pipeline_error", I32, P, I32);
        PIPELINE_FREE = h("hz_pipeline_free", null, P);
        PASS_BEGIN = h("hz_pass_begin", I32, P, P, P, P, I32, P, I32, F64, I32, I32, I32, I32);
        PASS_END = h("hz_pass_end", null, P);
        PASS_PIPELINE = h("hz_pass_pipeline", null, P, P);
        PASS_BUFFER = h("hz_pass_buffer", null, P, I32, P, I64, I64);
        PASS_TEXTURE = h("hz_pass_texture", null, P, I32, P, P);
        PASS_IMMEDIATES = h("hz_pass_immediates", null, P, P, I32);
        PASS_SCISSOR = h("hz_pass_scissor", null, P, I32, I32, I32, I32, I32);
        PASS_VERTEX = h("hz_pass_vertex", null, P, I32, P, I64, I64);
        PASS_INDEX = h("hz_pass_index", null, P, P, I32);
        PASS_DRAW = h("hz_pass_draw", null, P, I32, I32, I32, I32);
        PASS_DRAW_INDEXED = h("hz_pass_draw_indexed", null, P, I32, I32, I32, I32, I32);
        SURF_CREATE = h("hz_surface_create", P, P, P, P);
        SURF_FREE = h("hz_surface_free", null, P);
        SURF_CONFIGURE = h("hz_surface_configure", I32, P, P, I32, I32, I32);
        SURF_MODES = h("hz_surface_modes", I32, P, P);
        SURF_ACQUIRE = h("hz_surface_acquire", I32, P, P);
        SURF_SUBOPTIMAL = h("hz_surface_suboptimal", I32, P);
        SURF_BLIT = h("hz_surface_blit", null, P, P, P);
        SURF_PRESENT = h("hz_surface_present", I64, P, P);
    }

    static RuntimeException fail(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    /** A direct view of a ByteBuffer's remaining bytes; a heap buffer is copied into {@code arena}. */
    static MemorySegment bytes(ByteBuffer b, Arena arena) {
        if (b.isDirect()) {
            return MemorySegment.ofBuffer(b);
        }
        MemorySegment copy = arena.allocate(Math.max(1, b.remaining()));
        MemorySegment.copy(MemorySegment.ofBuffer(b), 0, copy, 0, b.remaining());
        return copy;
    }

    private static String text(MethodHandle h, MemorySegment ctx) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(65536);
            int n = (int) h.invokeExact(ctx, out, 65536);
            byte[] b = out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Device

    static synchronized MemorySegment create() {
        try {
            return (MemorySegment) CREATE.invokeExact();
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void destroy(MemorySegment ctx) {
        try {
            DESTROY.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized String info(MemorySegment ctx) {
        return text(INFO, ctx);
    }

    static synchronized String error(MemorySegment ctx) {
        return text(ERROR, ctx);
    }

    static synchronized String messages(MemorySegment ctx) {
        return text(MESSAGES, ctx);
    }

    static synchronized long submit(MemorySegment ctx) {
        try {
            return (long) SUBMIT.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized long completed(MemorySegment ctx) {
        try {
            return (long) COMPLETED.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized boolean await(MemorySegment ctx, long serial, long timeoutNs) {
        try {
            return (int) WAIT.invokeExact(ctx, serial, timeoutNs) != 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized long fence(MemorySegment ctx) {
        try {
            return (long) FENCE.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Buffers

    static synchronized MemorySegment bufferCreate(MemorySegment ctx, long size, int usage) {
        try {
            return (MemorySegment) BUF_CREATE.invokeExact(ctx, size, usage);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void bufferFree(MemorySegment buf) {
        try {
            BUF_FREE.invokeExact(buf);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void bufferWrite(MemorySegment ctx, MemorySegment buf, long offset, ByteBuffer data) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = bytes(data, arena);
            BUF_WRITE.invokeExact(ctx, buf, offset, src, (long) data.remaining());
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    /** Bytes from Java's memory into the buffer, in command order (copied during the call). */
    static synchronized void bufferWrite(MemorySegment ctx, MemorySegment buf, long offset, MemorySegment data) {
        try {
            BUF_WRITE.invokeExact(ctx, buf, offset, data, data.byteSize());
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    /** A transient region's bytes, copied now and sent at the next submit. */
    static synchronized void bufferDirty(MemorySegment ctx, MemorySegment buf, long offset, MemorySegment data) {
        try {
            BUF_DIRTY.invokeExact(ctx, buf, offset, data, data.byteSize());
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    /** The buffer's bytes from {@code offset} into {@code dst} (its size), once the GPU has written them. */
    static synchronized boolean bufferRead(MemorySegment ctx, MemorySegment buf, long offset, MemorySegment dst) {
        try {
            return (int) BUF_READ.invokeExact(ctx, buf, offset, dst.byteSize(), dst) != 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void bufferCopy(MemorySegment ctx, MemorySegment src, long so, MemorySegment dst, long d,
            long len) {
        try {
            BUF_COPY.invokeExact(ctx, src, so, dst, d, len);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Textures

    static synchronized MemorySegment textureCreate(MemorySegment ctx, int format, int usage, int w, int h,
            int layers, int mips) {
        try {
            return (MemorySegment) TEX_CREATE.invokeExact(ctx, format, usage, w, h, layers, mips);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void textureFree(MemorySegment tex) {
        try {
            TEX_FREE.invokeExact(tex);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized MemorySegment viewCreate(MemorySegment ctx, MemorySegment tex, int base, int mips) {
        try {
            return (MemorySegment) VIEW_CREATE.invokeExact(ctx, tex, base, mips);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void viewFree(MemorySegment view) {
        try {
            VIEW_FREE.invokeExact(view);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void textureWrite(MemorySegment ctx, MemorySegment tex, ByteBuffer data, int mip, int layer,
            int x, int y, int w, int h) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = bytes(data, arena);
            TEX_WRITE.invokeExact(ctx, tex, src, (long) data.remaining(), mip, layer, x, y, w, h);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void bufferToTexture(MemorySegment ctx, MemorySegment buf, long offset, int sx, int sy,
            int sw, MemorySegment tex, int x, int y, int w, int h, int mip, int layer) {
        try {
            BUF_TO_TEX.invokeExact(ctx, buf, offset, sx, sy, sw, tex, x, y, w, h, mip, layer);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void textureToBuffer(MemorySegment ctx, MemorySegment tex, MemorySegment buf, long offset,
            int mip, int x, int y, int w, int h) {
        try {
            TEX_TO_BUF.invokeExact(ctx, tex, buf, offset, mip, x, y, w, h);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void textureCopy(MemorySegment ctx, MemorySegment src, MemorySegment dst, int mip, int dx,
            int dy, int sx, int sy, int w, int h) {
        try {
            TEX_COPY.invokeExact(ctx, src, dst, mip, dx, dy, sx, sy, w, h);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void clear(MemorySegment ctx, MemorySegment color, float[] rgba, MemorySegment depth,
            double depthValue, int mip, int rx, int ry, int rw, int rh) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocateFrom(ValueLayout.JAVA_FLOAT, rgba);
            CLEAR.invokeExact(ctx, color, c, depth, depthValue, mip, rx, ry, rw, rh);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Samplers and pipelines

    static synchronized MemorySegment samplerCreate(MemorySegment ctx, int u, int v, int min, int mag, int aniso,
            double maxLod) {
        try {
            return (MemorySegment) SAMPLER_CREATE.invokeExact(ctx, u, v, min, mag, aniso, maxLod);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void samplerFree(MemorySegment s) {
        try {
            SAMPLER_FREE.invokeExact(s);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized MemorySegment device(MemorySegment ctx) {
        try {
            return (MemorySegment) DEVICE.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    /** Why this thread's last pipeline was refused. Not under the lock (see the class docs). */
    static String pipelineError() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(65536);
            int n = (int) PIPELINE_ERROR.invokeExact(out, 65536);
            return new String(out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    /** Not under the lock (see the class docs): {@code device} is {@link #device}'s handle. */
    static MemorySegment pipelineCreate(MemorySegment device, String name, ByteBuffer vs, ByteBuffer fs,
            int[] desc, float biasScale, float biasConstant) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            MemorySegment v = bytes(vs, arena);
            MemorySegment f = bytes(fs, arena);
            return (MemorySegment) PIPELINE_CREATE.invokeExact(device, arena.allocateFrom(ValueLayout.JAVA_BYTE, n),
                    n.length, v, (long) vs.remaining(), f, (long) fs.remaining(),
                    arena.allocateFrom(ValueLayout.JAVA_INT, desc), desc.length, biasScale, biasConstant);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void pipelineFree(MemorySegment p) {
        try {
            PIPELINE_FREE.invokeExact(p);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Render passes

    static synchronized boolean passBegin(MemorySegment ctx, MemorySegment[] colors, boolean[] clear, float[] rgba,
            MemorySegment depth, boolean clearDepth, double depthValue, int x, int y, int w, int h) {
        try (Arena arena = Arena.ofConfined()) {
            int n = colors.length;
            MemorySegment views = arena.allocate(ValueLayout.ADDRESS, Math.max(1, n));
            MemorySegment flags = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
            for (int i = 0; i < n; i++) {
                views.setAtIndex(ValueLayout.ADDRESS, i, colors[i]);
                flags.setAtIndex(ValueLayout.JAVA_INT, i, clear[i] ? 1 : 0);
            }
            MemorySegment c = arena.allocateFrom(ValueLayout.JAVA_FLOAT, rgba.length == 0 ? new float[4] : rgba);
            return (int) PASS_BEGIN.invokeExact(ctx, views, flags, c, n, depth, clearDepth ? 1 : 0, depthValue, x, y,
                    w, h) != 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passEnd(MemorySegment ctx) {
        try {
            PASS_END.invokeExact(ctx);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passPipeline(MemorySegment ctx, MemorySegment p) {
        try {
            PASS_PIPELINE.invokeExact(ctx, p);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passBuffer(MemorySegment ctx, int index, MemorySegment buf, long offset, long len) {
        try {
            PASS_BUFFER.invokeExact(ctx, index, buf, offset, len);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passTexture(MemorySegment ctx, int index, MemorySegment view, MemorySegment sampler) {
        try {
            PASS_TEXTURE.invokeExact(ctx, index, view, sampler);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passImmediates(MemorySegment ctx, ByteBuffer data) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment d = bytes(data, arena);
            PASS_IMMEDIATES.invokeExact(ctx, d, data.remaining());
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passScissor(MemorySegment ctx, int x, int y, int w, int h, boolean enable) {
        try {
            PASS_SCISSOR.invokeExact(ctx, x, y, w, h, enable ? 1 : 0);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passVertex(MemorySegment ctx, int slot, MemorySegment buf, long offset, long len) {
        try {
            PASS_VERTEX.invokeExact(ctx, slot, buf, offset, len);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passIndex(MemorySegment ctx, MemorySegment buf, boolean int32) {
        try {
            PASS_INDEX.invokeExact(ctx, buf, int32 ? 1 : 0);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passDraw(MemorySegment ctx, int vertices, int instances, int first, int firstInstance) {
        try {
            PASS_DRAW.invokeExact(ctx, vertices, instances, first, firstInstance);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void passDrawIndexed(MemorySegment ctx, int indices, int instances, int first,
            int baseVertex, int firstInstance) {
        try {
            PASS_DRAW_INDEXED.invokeExact(ctx, indices, instances, first, baseVertex, firstInstance);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    // Surface

    static synchronized MemorySegment surfaceCreate(MemorySegment ctx, long hwnd, long hinstance) {
        try {
            return (MemorySegment) SURF_CREATE.invokeExact(ctx, MemorySegment.ofAddress(hwnd),
                    MemorySegment.ofAddress(hinstance));
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void surfaceFree(MemorySegment s) {
        try {
            SURF_FREE.invokeExact(s);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized boolean surfaceConfigure(MemorySegment ctx, MemorySegment s, int w, int h, int mode) {
        try {
            return (int) SURF_CONFIGURE.invokeExact(ctx, s, w, h, mode) != 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized int surfaceModes(MemorySegment ctx, MemorySegment s) {
        try {
            return (int) SURF_MODES.invokeExact(ctx, s);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized int surfaceAcquire(MemorySegment ctx, MemorySegment s) {
        try {
            return (int) SURF_ACQUIRE.invokeExact(ctx, s);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized boolean surfaceSuboptimal(MemorySegment s) {
        try {
            return (int) SURF_SUBOPTIMAL.invokeExact(s) != 0;
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized void surfaceBlit(MemorySegment ctx, MemorySegment s, MemorySegment view) {
        try {
            SURF_BLIT.invokeExact(ctx, s, view);
        } catch (Throwable t) {
            throw fail(t);
        }
    }

    static synchronized long surfacePresent(MemorySegment ctx, MemorySegment s) {
        try {
            return (long) SURF_PRESENT.invokeExact(ctx, s);
        } catch (Throwable t) {
            throw fail(t);
        }
    }
}
