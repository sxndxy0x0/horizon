package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

/**
 * The device's one command encoder. Commands go into wgpu's encoder for the frame in the order the game makes
 * them; a submit sends the frame. Callbacks (a texture read back) run once the GPU has finished the submission
 * that held them.
 */
final class WgpuEncoder implements CommandEncoderBackend {
    private final WgpuDevice device;
    final WgpuTransient transientMemory;
    /** Callbacks for the commands recorded since the last submit. */
    private final List<Runnable> recorded = new ArrayList<>();
    /** Callbacks waiting on a submission: {serial, callback}. */
    private final ArrayDeque<Object[]> waiting = new ArrayDeque<>();
    /** Native objects the game closed, freed once the frames that may use them are done: {serial, free}. */
    private final ArrayDeque<Object[]> closing = new ArrayDeque<>();
    private long lastSerial;

    /** Frees {@code free} once everything recorded so far (submitted or not) has finished on the GPU. */
    void freeLater(Runnable free) {
        closing.add(new Object[] {lastSerial + 1, free});
    }

    WgpuEncoder(WgpuDevice device) {
        this.device = device;
        this.transientMemory = new WgpuTransient(device);
    }

    private MemorySegment ctx() {
        return device.ctx;
    }

    /** What a submit with this serial leaves to do: callbacks, transient blocks. */
    void submitted(long serial) {
        Trace.submit();
        lastSerial = Math.max(lastSerial, serial);
        for (Runnable r : recorded) {
            waiting.add(new Object[] {serial, r});
        }
        recorded.clear();
        transientMemory.rotate(serial);
        poll();
    }

    /** Runs what the GPU has finished. */
    void poll() {
        long done = Native.completed(ctx());
        while (!waiting.isEmpty() && (long) waiting.peek()[0] <= done) {
            ((Runnable) waiting.poll()[1]).run();
        }
        while (!closing.isEmpty() && (long) closing.peek()[0] <= done) {
            ((Runnable) closing.poll()[1]).run();
        }
        transientMemory.release(done);
    }

    @Override
    public void submit() {
        submitted(Native.submit(ctx()));
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor desc) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = desc.colorAttachments();
        MemorySegment[] views = new MemorySegment[colors.size()];
        boolean[] clear = new boolean[colors.size()];
        float[] rgba = new float[4 * colors.size()];
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
            views[i] = a == null || a.textureView() == null ? MemorySegment.NULL
                    : ((WgpuTexture.View) a.textureView()).handle;
            Optional<Vector4fc> c = a == null ? Optional.empty() : a.clearValue();
            if (c != null && c.isPresent()) {
                clear[i] = true;
                rgba[4 * i] = c.get().x();
                rgba[4 * i + 1] = c.get().y();
                rgba[4 * i + 2] = c.get().z();
                rgba[4 * i + 3] = c.get().w();
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depth = desc.depthAttachment();
        MemorySegment depthView = depth == null || depth.textureView() == null ? MemorySegment.NULL
                : ((WgpuTexture.View) depth.textureView()).handle;
        OptionalDouble depthClear = depth == null ? OptionalDouble.empty() : depth.clearValue();
        var area = desc.renderArea();
        if (!Native.passBegin(ctx(), views, clear, rgba, depthView, depthClear != null && depthClear.isPresent(),
                depthClear != null && depthClear.isPresent() ? depthClear.getAsDouble() : 1.0, area.x(), area.y(),
                area.width(), area.height())) {
            throw new IllegalStateException("render pass: " + Native.error(ctx()));
        }
        return new WgpuRenderPass(ctx());
    }

    @Override
    public void submitRenderPass() {
        Native.passEnd(ctx());
    }

    private static MemorySegment tex(GpuTexture t) {
        return t == null ? MemorySegment.NULL : ((WgpuTexture) t).handle;
    }

    private static float[] rgba(Vector4fc c) {
        return c == null ? new float[4] : new float[] {c.x(), c.y(), c.z(), c.w()};
    }

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        Native.clear(ctx(), tex(texture), rgba(color), MemorySegment.NULL, 1.0, 0, 0, 0, 0, 0);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth) {
        Native.clear(ctx(), tex(color), rgba(clearColor), tex(depth), clearDepth, 0, 0, 0, 0, 0);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth,
            int x, int y, int width, int height, int mipLevel) {
        boolean whole = x == 0 && y == 0 && width == color.getWidth(mipLevel) && height == color.getHeight(mipLevel);
        Native.clear(ctx(), tex(color), rgba(clearColor), tex(depth), clearDepth, mipLevel, x, y, whole ? 0 : width,
                whole ? 0 : height);
    }

    @Override
    public void clearDepthTexture(GpuTexture depth, double clearDepth) {
        Native.clear(ctx(), MemorySegment.NULL, new float[4], tex(depth), clearDepth, 0, 0, 0, 0, 0);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        Trace.count("writeToBuffer " + data.remaining());
        WgpuBuffer b = (WgpuBuffer) slice.buffer();
        b.written(slice.offset(), data);
        Native.bufferWrite(ctx(), b.handle, slice.offset(), data);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
        Trace.count("copyToBuffer");
        ((WgpuBuffer) destination.buffer()).gpuWritten();
        Native.bufferCopy(ctx(), ((WgpuBuffer) source.buffer()).handle, source.offset(),
                ((WgpuBuffer) destination.buffer()).handle, destination.offset(),
                Math.min(source.length(), destination.length()));
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
            int destY, int width, int height) {
        Native.textureWrite(ctx(), tex(destination), source, mipLevel, depthOrLayer, destX, destY, width, height);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth,
            int sourceHeight, GpuTexture destination, int destinationX, int destinationY, int copyWidth,
            int copyHeight, int mipLevel, int arrayLayer) {
        Native.bufferToTexture(ctx(), ((WgpuBuffer) source.buffer()).handle, source.offset(), sourceX, sourceY,
                sourceWidth, tex(destination), destinationX, destinationY, copyWidth, copyHeight, mipLevel,
                arrayLayer);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel),
                source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel, int x, int y, int width, int height) {
        ((WgpuBuffer) destination).gpuWritten();
        Native.textureToBuffer(ctx(), tex(source), ((WgpuBuffer) destination).handle, offset, mipLevel, x, y, width,
                height);
        if (callback != null) {
            recorded.add(callback);
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        Native.textureCopy(ctx(), tex(source), tex(destination), mipLevel, destX, destY, sourceX, sourceY, width,
                height);
    }

    @Override
    public GpuFence createFence() {
        long serial = Native.fence(ctx());
        if (!recorded.isEmpty()) {
            submitted(serial);
        }
        return new Fence(serial);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        ((WgpuQueryPool) pool).write(index);
    }

    /** Done once the GPU has finished the submission with its serial. */
    final class Fence implements GpuFence {
        private final long serial;

        Fence(long serial) {
            this.serial = serial;
        }

        @Override
        public boolean awaitCompletion(long timeout) {
            boolean done = Native.await(ctx(), serial, timeout == GpuFence.NO_TIMEOUT ? -1 : timeout * 1_000_000L);
            poll();
            return done;
        }

        @Override
        public void close() {}
    }
}
