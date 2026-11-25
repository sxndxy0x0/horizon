//! P3 of `docs/design/java-mods-everything.md`: the Java game draws through wgpu.
//!
//! 26.3 renders through renderpearl, whose backends (OpenGL, Vulkan) implement a small set of interfaces
//! (`GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`, `GpuSurfaceBackend`). The Java side of this
//! backend (`mods/horizon/src/wgpu-backend/`) implements them and calls these functions through FFM; everything
//! here runs on the game's render thread.
//!
//! The game's conventions are its Vulkan backend's, kept exactly:
//! - textures hold row 0 at clip-space `y = -1` ([`shader`] negates `y`; the surface blit flips back);
//! - depth runs 0 to 1;
//! - resources bind in set 0 at their uniform's index, a split sampler at that index plus
//!   [`spirv::SAMPLER_BINDING_OFFSET`].
//!
//! Commands keep their order: writes and copies are recorded into the frame's command encoder, so a buffer
//! written twice in a frame is seen with each content by the draws between. Transient memory (each region
//! written once a frame) goes up with `queue.write_buffer` at submit, before the frame's commands.
//!
//! Rust keeps no pointer into Java's memory and hands Java none of its own: the CPU copies the game maps are
//! Java's, and each call copies what it needs while it runs.

pub mod format;
pub mod shader;
pub mod spirv;
pub mod texel;

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, c_void};
use std::num::NonZeroIsize;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use shader::Resource;
use wgpu::TextureFormat;

/// The device and the frame being recorded.
pub struct Ctx {
    instance: wgpu::Instance,
    adapter: wgpu::Adapter,
    device: wgpu::Device,
    queue: wgpu::Queue,
    encoder: Option<wgpu::CommandEncoder>,
    /// Whether the encoder holds commands not yet submitted.
    recorded: bool,
    pass: Option<Pass>,
    /// Transient regions written since the last submit: (buffer, offset, bytes).
    dirty: Vec<(wgpu::Buffer, u64, Vec<u8>)>,
    serial: u64,
    completed: Arc<AtomicU64>,
    messages: Arc<Mutex<Vec<String>>>,
    error: String,
    blits: HashMap<TextureFormat, wgpu::RenderPipeline>,
    blit_layout: Option<wgpu::BindGroupLayout>,
    clears: HashMap<(TextureFormat, Option<TextureFormat>), wgpu::RenderPipeline>,
    clear_layout: Option<wgpu::BindGroupLayout>,
    /// `HZ_WGPU_TRACE`: per-submit counts, passes and read-backs on stderr.
    trace: bool,
    passes: u32,
    draws: u32,
    writes: u32,
    texture_writes: u32,
    presented: u64,
}

pub struct Buf {
    buffer: wgpu::Buffer,
    size: u64,
    readback: bool,
}

pub struct Tex {
    texture: wgpu::Texture,
    format: TextureFormat,
    /// Bytes per texel the game hands over (3 for an RGB format stored as RGBA).
    game_bpp: u32,
    layers: u32,
}

pub struct View {
    texture: wgpu::Texture,
    format: TextureFormat,
    base_mip: u32,
    mips: u32,
    layers: u32,
    /// The view for each dimension a shader samples it as.
    sampled: RefCell<HashMap<wgpu::TextureViewDimension, wgpu::TextureView>>,
    /// Its first mip and layer, as a render target.
    attachment: wgpu::TextureView,
    width: u32,
    height: u32,
}

pub struct Sampler {
    sampler: wgpu::Sampler,
    /// The same with nearest filtering, for a texture that cannot be filtered (depth, 32-bit float).
    nearest: wgpu::Sampler,
}

pub struct Pipeline {
    name: String,
    vertex: wgpu::ShaderModule,
    fragment: wgpu::ShaderModule,
    resources: Vec<(u32, u32, Resource)>,
    buffers: Vec<(u64, wgpu::VertexStepMode, Vec<wgpu::VertexAttribute>)>,
    immediates: u32,
    depth: (wgpu::CompareFunction, bool, f32, i32),
    cull: bool,
    wireframe: bool,
    topology: wgpu::PrimitiveTopology,
    targets: Vec<Option<(Option<wgpu::BlendState>, wgpu::ColorWrites)>>,
    variants: RefCell<HashMap<VariantKey, Arc<Variant>>>,
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct VariantKey {
    colors: Vec<Option<TextureFormat>>,
    depth: Option<TextureFormat>,
    /// Uniform indices whose bound texture cannot be filtered.
    unfilterable: Vec<u32>,
}

struct Variant {
    pipeline: wgpu::RenderPipeline,
    groups: Vec<(u32, wgpu::BindGroupLayout)>,
}

/// What a pass has bound at a uniform index. It holds its own references: the game may close a view, sampler
/// or pipeline while a pass still has it bound, and wgpu keeps what was recorded alive by itself.
#[derive(Clone)]
enum Bound {
    Buffer(wgpu::Buffer, u64, u64),
    Texture(Rc<View>, wgpu::Sampler, wgpu::Sampler),
}

struct Pass {
    pass: wgpu::RenderPass<'static>,
    colors: Vec<Option<TextureFormat>>,
    depth: Option<TextureFormat>,
    area: (u32, u32, u32, u32),
    pipeline: Option<Rc<Pipeline>>,
    bound: HashMap<u32, Bound>,
    dirty: bool,
    variant: Option<Arc<Variant>>,
    index_format: wgpu::IndexFormat,
}

pub struct Surface {
    surface: wgpu::Surface<'static>,
    config: Option<wgpu::SurfaceConfiguration>,
    current: Option<wgpu::SurfaceTexture>,
    suboptimal: bool,
}

fn guard<T>(ctx: *mut Ctx, fallback: T, f: impl FnOnce(&mut Ctx) -> Result<T, String>) -> T {
    // SAFETY: Java hands back the context `hz_create` made, on the one render thread.
    let ctx = unsafe { &mut *ctx };
    match catch_unwind(AssertUnwindSafe(|| f(ctx))) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            eprintln!("[horizon-wgpu] {e}");
            ctx.error = e;
            fallback
        }
        Err(p) => {
            let e = p
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| p.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "panic".into());
            eprintln!("[horizon-wgpu] panic: {e}");
            ctx.error = e;
            fallback
        }
    }
}

/// # Safety
/// `ptr` points at `len` bytes, or `len` is 0.
unsafe fn bytes<'a>(ptr: *const u8, len: u64) -> &'a [u8] {
    if len == 0 {
        &[]
    } else {
        // SAFETY: as the caller promises.
        unsafe { std::slice::from_raw_parts(ptr, len as usize) }
    }
}

/// Runs `f` on a thread with a large stack and waits for it. Shader work (naga's parsing and validation, the
/// driver's shader compiler behind pipeline creation) recurses deeply, and the game calls in from its own threads,
/// whose stacks are the JVM's (about 1 MB): past their end, the process dies without even a JVM crash report.
fn on_big_stack<T>(f: impl FnOnce() -> T) -> T {
    struct Sendable<F>(F);
    // SAFETY: the closure runs on the new thread while this one waits for it; nothing it borrows is used meanwhile.
    unsafe impl<F> Send for Sendable<F> {}
    let job = Sendable(f);
    let mut out = None;
    std::thread::scope(|scope| {
        let slot = Sendable(&mut out);
        std::thread::Builder::new()
            .name("horizon-wgpu shaders".into())
            .stack_size(64 << 20)
            .spawn_scoped(scope, move || {
                let (job, slot) = (job, slot);
                *slot.0 = Some((job.0)());
            })
            .expect("a shader thread")
            .join()
            .map_err(std::panic::resume_unwind)
            .ok();
    });
    out.expect("the shader thread finished")
}

fn align4(n: u64) -> u64 {
    n.div_ceil(4) * 4
}

impl Ctx {
    fn encoder(&mut self) -> &mut wgpu::CommandEncoder {
        self.recorded = true;
        self.encoder
            .get_or_insert_with(|| self.device.create_command_encoder(&Default::default()))
    }

    fn no_pass(&self, what: &str) -> Result<(), String> {
        if self.pass.is_some() {
            Err(format!("{what} while a render pass is open"))
        } else {
            Ok(())
        }
    }

    /// Bytes into a buffer, in command order: a staging copy recorded into the encoder, or (while a pass is
    /// open, where wgpu records no copy) ahead of the frame's commands.
    fn write(&mut self, buffer: &wgpu::Buffer, offset: u64, data: &[u8]) {
        if data.is_empty() {
            return;
        }
        self.writes += 1;
        let start = offset & !3;
        let end = align4(offset + data.len() as u64);
        let mut padded;
        let data = if start != offset || end != offset + data.len() as u64 {
            padded = vec![0u8; (end - start) as usize];
            let at = (offset - start) as usize;
            padded[at..at + data.len()].copy_from_slice(data);
            padded.as_slice()
        } else {
            data
        };
        if self.pass.is_some() {
            self.queue.write_buffer(buffer, start, data);
            return;
        }
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("horizon write"),
            size: data.len() as u64,
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::MAP_WRITE,
            mapped_at_creation: true,
        });
        staging
            .slice(..)
            .get_mapped_range_mut()
            .expect("mapped at creation")
            .copy_from_slice(data);
        staging.unmap();
        self.encoder()
            .copy_buffer_to_buffer(&staging, 0, buffer, start, data.len() as u64);
    }

    fn submit(&mut self) -> u64 {
        for (buffer, offset, data) in std::mem::take(&mut self.dirty) {
            self.queue.write_buffer(&buffer, offset, &data);
        }
        if self.trace && self.serial < 400 {
            eprintln!(
                "[horizon-wgpu] submit {}: {} passes, {} draws, {} transient writes, {} buffer writes, {} texture writes",
                self.serial + 1,
                self.passes,
                self.draws,
                self.dirty.len(),
                self.writes,
                self.texture_writes
            );
        }
        self.passes = 0;
        self.draws = 0;
        self.writes = 0;
        self.texture_writes = 0;
        let commands = self.encoder.take().map(|e| e.finish());
        self.queue.submit(commands);
        self.recorded = false;
        self.serial += 1;
        let serial = self.serial;
        let completed = self.completed.clone();
        self.queue.on_submitted_work_done(move || {
            completed.fetch_max(serial, Ordering::AcqRel);
        });
        serial
    }

    fn wait(&mut self, serial: u64, timeout: Option<Duration>) -> bool {
        let start = std::time::Instant::now();
        while self.completed.load(Ordering::Acquire) < serial {
            let left = timeout.map(|t| t.saturating_sub(start.elapsed()));
            if left == Some(Duration::ZERO) {
                return false;
            }
            let _ = self.device.poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: left.or(Some(Duration::from_secs(5))),
            });
        }
        true
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Device

/// A device on the best adapter: DX12 on Windows (`HZ_WGPU_BACKEND=vulkan` or `primary` to choose otherwise).
/// Null when there is none.
#[unsafe(no_mangle)]
pub extern "C" fn hz_create() -> *mut Ctx {
    let backends = match std::env::var("HZ_WGPU_BACKEND").as_deref() {
        Ok("vulkan") => wgpu::Backends::VULKAN,
        Ok("primary") => wgpu::Backends::PRIMARY,
        // DX12 on Windows: wgpu's Vulkan reports Win32 window surfaces unsupported on some drivers.
        _ if cfg!(windows) => wgpu::Backends::DX12,
        _ => wgpu::Backends::PRIMARY,
    };
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends,
        ..wgpu::InstanceDescriptor::new_without_display_handle()
    });
    let Ok(adapter) = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
        power_preference: wgpu::PowerPreference::HighPerformance,
        ..Default::default()
    })) else {
        eprintln!("[horizon-wgpu] no adapter");
        return std::ptr::null_mut();
    };
    let wanted = wgpu::Features::IMMEDIATES
        | wgpu::Features::DEPTH32FLOAT_STENCIL8
        | wgpu::Features::TEXTURE_FORMAT_16BIT_NORM
        | wgpu::Features::CLEAR_TEXTURE
        | wgpu::Features::POLYGON_MODE_LINE;
    let features = adapter.features() & wanted;
    let mut limits = adapter.limits();
    limits.max_immediate_size = limits.max_immediate_size.min(256);
    let Ok((device, queue)) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("horizon renderpearl"),
        required_features: features,
        required_limits: limits,
        ..Default::default()
    })) else {
        eprintln!("[horizon-wgpu] no device");
        return std::ptr::null_mut();
    };
    let messages: Arc<Mutex<Vec<String>>> = Arc::default();
    let sink = messages.clone();
    device.on_uncaptured_error(Arc::new(move |e: wgpu::Error| {
        let text = e.to_string();
        eprintln!("[horizon-wgpu] {text}");
        if let Ok(mut m) = sink.lock() {
            m.push(text);
            let n = m.len();
            if n > 64 {
                m.drain(..n - 64);
            }
        }
    }));
    Box::into_raw(Box::new(Ctx {
        instance,
        adapter,
        device,
        queue,
        encoder: None,
        recorded: false,
        pass: None,
        dirty: Vec::new(),
        serial: 0,
        completed: Arc::new(AtomicU64::new(0)),
        messages,
        error: String::new(),
        blits: HashMap::new(),
        blit_layout: None,
        clears: HashMap::new(),
        clear_layout: None,
        trace: std::env::var_os("HZ_WGPU_TRACE").is_some(),
        passes: 0,
        draws: 0,
        writes: 0,
        texture_writes: 0,
        presented: 0,
    }))
}

/// # Safety
/// `ctx` came from [`hz_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_destroy(ctx: *mut Ctx) {
    if !ctx.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(ctx) });
    }
}

/// Writes UTF-8 text about the device into `out` (at most `cap` bytes) and returns its length: lines of
/// `key=value` (name, vendor, driver, backend, type, limits).
///
/// # Safety
/// `ctx` is live; `out` points at `cap` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_info(ctx: *mut Ctx, out: *mut u8, cap: u32) -> u32 {
    guard(ctx, 0, |ctx| {
        let i = ctx.adapter.get_info();
        let l = ctx.device.limits();
        let text = format!(
            "name={}\nvendor={:#x}\ndriver={} {}\nbackend={:?}\ntype={:?}\nmax_texture={}\nmin_uniform_align={}\nmax_buffer={}\nmax_colors={}\nimmediates={}\nwireframe={}\n",
            i.name,
            i.vendor,
            i.driver,
            i.driver_info,
            i.backend,
            i.device_type,
            l.max_texture_dimension_2d,
            l.min_uniform_buffer_offset_alignment,
            l.max_buffer_size,
            l.max_color_attachments,
            ctx.device.features().contains(wgpu::Features::IMMEDIATES),
            ctx.device
                .features()
                .contains(wgpu::Features::POLYGON_MODE_LINE),
        );
        let n = text.len().min(cap as usize);
        // SAFETY: as the caller promises.
        unsafe { std::ptr::copy_nonoverlapping(text.as_ptr(), out, n) };
        Ok(n as u32)
    })
}

/// The last error of a call, as UTF-8 into `out`; its length. Validation messages from wgpu go to
/// [`hz_messages`].
///
/// # Safety
/// `ctx` is live; `out` points at `cap` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_error(ctx: *mut Ctx, out: *mut u8, cap: u32) -> u32 {
    // SAFETY: as the caller promises.
    let ctx = unsafe { &mut *ctx };
    let e = std::mem::take(&mut ctx.error);
    let n = e.len().min(cap as usize);
    // SAFETY: as the caller promises.
    unsafe { std::ptr::copy_nonoverlapping(e.as_ptr(), out, n) };
    n as u32
}

/// wgpu's validation messages since the last call, one a line.
///
/// # Safety
/// `ctx` is live; `out` points at `cap` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_messages(ctx: *mut Ctx, out: *mut u8, cap: u32) -> u32 {
    // SAFETY: as the caller promises.
    let ctx = unsafe { &mut *ctx };
    let text = ctx
        .messages
        .lock()
        .map(|mut m| m.drain(..).collect::<Vec<_>>().join("\n"))
        .unwrap_or_default();
    let n = text.len().min(cap as usize);
    // SAFETY: as the caller promises.
    unsafe { std::ptr::copy_nonoverlapping(text.as_ptr(), out, n) };
    n as u32
}

/// Submits what was recorded; the submission's serial.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_submit(ctx: *mut Ctx) -> u64 {
    guard(ctx, 0, |ctx| {
        ctx.no_pass("submit")?;
        Ok(ctx.submit())
    })
}

/// The newest serial the GPU has finished.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_completed(ctx: *mut Ctx) -> u64 {
    guard(ctx, 0, |ctx| {
        let _ = ctx.device.poll(wgpu::PollType::Poll);
        Ok(ctx.completed.load(Ordering::Acquire))
    })
}

/// Waits until `serial` is done (`timeout_ns` < 0: no limit); whether it is.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_wait(ctx: *mut Ctx, serial: u64, timeout_ns: i64) -> u32 {
    guard(ctx, 0, |ctx| {
        let timeout = (timeout_ns >= 0).then(|| Duration::from_nanos(timeout_ns as u64));
        Ok(u32::from(ctx.wait(serial, timeout)))
    })
}

/// The serial of everything recorded so far: submits it if there is anything unsubmitted.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_fence(ctx: *mut Ctx) -> u64 {
    guard(ctx, 0, |ctx| {
        if ctx.pass.is_some() {
            return Ok(ctx.serial + 1);
        }
        Ok(if ctx.recorded || !ctx.dirty.is_empty() {
            ctx.submit()
        } else {
            ctx.serial
        })
    })
}

// ---------------------------------------------------------------------------------------------------------------
// Buffers

/// A buffer of `size` bytes for renderpearl `usage` bits.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_create(ctx: *mut Ctx, size: u64, usage: u32) -> *mut Buf {
    guard(ctx, std::ptr::null_mut(), |ctx| {
        use wgpu::BufferUsages as U;
        let readback = usage & 1 != 0;
        let usages = if readback {
            U::MAP_READ | U::COPY_DST
        } else {
            let mut u = U::COPY_DST | U::COPY_SRC;
            if usage & 32 != 0 {
                u |= U::VERTEX;
            }
            if usage & 64 != 0 {
                u |= U::INDEX;
            }
            if usage & 128 != 0 {
                u |= U::UNIFORM;
            }
            if usage & 256 != 0 {
                u |= U::STORAGE;
            }
            if usage & 512 != 0 {
                u |= U::INDIRECT;
            }
            u
        };
        // Rounded to 16 bytes, so a uniform block bound to its end can be bound whole (see hz_pass_buffer).
        let size = size.max(16).div_ceil(16) * 16;
        let buffer = ctx.device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size,
            usage: usages,
            mapped_at_creation: false,
        });
        Ok(Box::into_raw(Box::new(Buf {
            buffer,
            size,
            readback,
        })))
    })
}

/// # Safety
/// `buf` came from [`hz_buffer_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_free(buf: *mut Buf) {
    if !buf.is_null() {
        // Dropped, not destroyed: a frame not yet finished may still use it, and wgpu keeps it until then.
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(buf) });
    }
}

/// `len` bytes at `src` into the buffer at `offset`, in command order.
///
/// # Safety
/// `ctx` and `buf` are live; `src` points at `len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_write(
    ctx: *mut Ctx,
    buf: *mut Buf,
    offset: u64,
    src: *const u8,
    len: u64,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let (buf, data) = unsafe { (&mut *buf, bytes(src, len)) };
        if offset + len > buf.size {
            return Err("write past the end of a buffer".into());
        }
        let buffer = buf.buffer.clone();
        ctx.write(&buffer, offset, data);
        Ok(())
    })
}

/// A transient region's bytes (`len` at `src`) go up at the next submit, ahead of the frame's commands. They are
/// copied now: what the game writes there later is its next frame's.
///
/// # Safety
/// `ctx` and `buf` are live; `src` points at `len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_dirty(
    ctx: *mut Ctx,
    buf: *mut Buf,
    offset: u64,
    src: *const u8,
    len: u64,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let (buf, data) = unsafe { (&*buf, bytes(src, len)) };
        if offset + len > buf.size {
            return Err("transient write past the end of its block".into());
        }
        let start = offset & !3;
        let end = align4(offset + len);
        let mut padded = vec![0u8; (end - start) as usize];
        let at = (offset - start) as usize;
        padded[at..at + data.len()].copy_from_slice(data);
        ctx.dirty.push((buf.buffer.clone(), start, padded));
        Ok(())
    })
}

/// Reads the buffer from `offset` for `len` bytes into `dst` (after submitting what writes it and waiting for
/// the GPU); 1 when it did.
///
/// # Safety
/// `ctx` and `buf` are live; `dst` points at `len` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_read(
    ctx: *mut Ctx,
    buf: *mut Buf,
    offset: u64,
    len: u64,
    dst: *mut u8,
) -> u32 {
    guard(ctx, 0, |ctx| {
        // SAFETY: as the caller promises.
        let buf = unsafe { &*buf };
        if !buf.readback {
            return Err("read of a buffer that is not readable".into());
        }
        if offset + len > buf.size {
            return Err("read past the end of a buffer".into());
        }
        if ctx.recorded {
            ctx.no_pass("read")?;
            ctx.submit();
        }
        let start = offset & !3;
        let end = align4(offset + len).min(buf.size);
        let slice = buf.buffer.slice(start..end);
        let done = Arc::new(Mutex::new(None));
        let set = done.clone();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            if let Ok(mut d) = set.lock() {
                *d = Some(r.is_ok());
            }
        });
        let _ = ctx.device.poll(wgpu::PollType::wait_indefinitely());
        if done.lock().ok().and_then(|d| *d) != Some(true) {
            return Err("mapping a buffer for reading failed".into());
        }
        {
            let range = slice.get_mapped_range().map_err(|e| e.to_string())?;
            let at = (offset - start) as usize;
            // SAFETY: as the caller promises.
            let out = unsafe { std::slice::from_raw_parts_mut(dst, len as usize) };
            out.copy_from_slice(&range[at..at + len as usize]);
            if ctx.trace {
                let sum: u64 = out.iter().map(|&b| u64::from(b)).sum();
                eprintln!("[horizon-wgpu] read back {len} bytes, byte sum {sum}");
            }
        }
        buf.buffer.unmap();
        Ok(1)
    })
}

/// A copy between buffers, in command order.
///
/// # Safety
/// `ctx`, `src` and `dst` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_buffer_copy(
    ctx: *mut Ctx,
    src: *mut Buf,
    src_offset: u64,
    dst: *mut Buf,
    dst_offset: u64,
    len: u64,
) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("copyToBuffer")?;
        // SAFETY: as the caller promises.
        let (src, dst) = unsafe { (&*src, &*dst) };
        if !(src_offset | dst_offset | len).is_multiple_of(4) {
            return Err("buffer copy not 4-byte aligned".into());
        }
        let (a, b) = (src.buffer.clone(), dst.buffer.clone());
        ctx.encoder()
            .copy_buffer_to_buffer(&a, src_offset, &b, dst_offset, len);
        Ok(())
    })
}

// ---------------------------------------------------------------------------------------------------------------
// Textures

/// A texture of renderpearl format `format` (ordinal), `usage` bits, size, layers and mips.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_texture_create(
    ctx: *mut Ctx,
    format: u32,
    usage: u32,
    width: u32,
    height: u32,
    layers: u32,
    mips: u32,
) -> *mut Tex {
    guard(ctx, std::ptr::null_mut(), |ctx| {
        use wgpu::TextureUsages as U;
        let (fmt, game_bpp) = format::texture(format).ok_or_else(|| format!("format {format}"))?;
        let mut usages = U::COPY_DST | U::COPY_SRC | U::TEXTURE_BINDING;
        let renderable = fmt
            .guaranteed_format_features(ctx.device.features())
            .allowed_usages
            .contains(U::RENDER_ATTACHMENT);
        if usage & 8 != 0 || renderable {
            usages |= U::RENDER_ATTACHMENT;
        }
        let texture = ctx.device.create_texture(&wgpu::TextureDescriptor {
            label: None,
            size: wgpu::Extent3d {
                width: width.max(1),
                height: height.max(1),
                depth_or_array_layers: layers.max(1),
            },
            mip_level_count: mips.max(1),
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: fmt,
            usage: usages,
            view_formats: &[],
        });
        Ok(Box::into_raw(Box::new(Tex {
            texture,
            format: fmt,
            game_bpp,
            layers: layers.max(1),
        })))
    })
}

/// # Safety
/// `tex` came from [`hz_texture_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_texture_free(tex: *mut Tex) {
    if !tex.is_null() {
        // Dropped, not destroyed: see hz_buffer_free.
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(tex) });
    }
}

/// A view of `mips` mip levels from `base_mip`.
///
/// # Safety
/// `ctx` and `tex` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_view_create(
    ctx: *mut Ctx,
    tex: *mut Tex,
    base_mip: u32,
    mips: u32,
) -> *mut Rc<View> {
    guard(ctx, std::ptr::null_mut(), |_| {
        // SAFETY: as the caller promises.
        let tex = unsafe { &*tex };
        let attachment = tex.texture.create_view(&wgpu::TextureViewDescriptor {
            dimension: Some(wgpu::TextureViewDimension::D2),
            base_mip_level: base_mip,
            mip_level_count: Some(1),
            base_array_layer: 0,
            array_layer_count: Some(1),
            ..Default::default()
        });
        let size = tex
            .texture
            .size()
            .mip_level_size(base_mip, wgpu::TextureDimension::D2);
        Ok(Box::into_raw(Box::new(Rc::new(View {
            texture: tex.texture.clone(),
            format: tex.format,
            base_mip,
            mips: mips.max(1),
            layers: tex.layers,
            sampled: RefCell::new(HashMap::new()),
            attachment,
            width: size.width,
            height: size.height,
        }))))
    })
}

/// # Safety
/// `view` came from [`hz_view_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_view_free(view: *mut Rc<View>) {
    if !view.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(view) });
    }
}

impl View {
    fn sampled(&self, dim: wgpu::TextureViewDimension) -> wgpu::TextureView {
        self.sampled
            .borrow_mut()
            .entry(dim)
            .or_insert_with(|| {
                let layers = match dim {
                    wgpu::TextureViewDimension::Cube => Some(6),
                    wgpu::TextureViewDimension::D2 => Some(1),
                    _ => Some(self.layers),
                };
                self.texture.create_view(&wgpu::TextureViewDescriptor {
                    dimension: Some(dim),
                    base_mip_level: self.base_mip,
                    mip_level_count: Some(self.mips),
                    base_array_layer: 0,
                    array_layer_count: layers,
                    ..Default::default()
                })
            })
            .clone()
    }
}

/// Texels the game hands over in its own layout (tight rows of `width`, its bytes per texel), widened to the
/// stored layout.
fn widen(tex: &Tex, data: &[u8], width: u32, height: u32) -> (Vec<u8>, u32) {
    let stored = tex.format.block_copy_size(None).unwrap_or(tex.game_bpp);
    if stored == tex.game_bpp {
        return (data.to_vec(), stored);
    }
    let mut out = Vec::with_capacity((width * height * stored) as usize);
    for texel in data.chunks_exact(tex.game_bpp as usize) {
        out.extend_from_slice(texel);
        out.extend(std::iter::repeat_n(
            0xffu8,
            (stored - tex.game_bpp) as usize,
        ));
    }
    (out, stored)
}

/// Texels at `src` (tight rows of `width`) into a mip and layer at (`x`, `y`), in command order.
///
/// # Safety
/// `ctx` and `tex` are live; `src` points at `len` bytes.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_texture_write(
    ctx: *mut Ctx,
    tex: *mut Tex,
    src: *const u8,
    len: u64,
    mip: u32,
    layer: u32,
    x: u32,
    y: u32,
    width: u32,
    height: u32,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let (tex, data) = unsafe { (&*tex, bytes(src, len)) };
        let need = (width * height * tex.game_bpp) as usize;
        if data.len() < need {
            return Err(format!(
                "texture write of {} bytes, {need} needed",
                data.len()
            ));
        }
        let (data, bpp) = widen(tex, &data[..need], width, height);
        ctx.texture_writes += 1;
        let target = wgpu::TexelCopyTextureInfo {
            texture: &tex.texture,
            mip_level: mip,
            origin: wgpu::Origin3d { x, y, z: layer },
            aspect: wgpu::TextureAspect::All,
        };
        let extent = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        };
        let row = width * bpp;
        if ctx.pass.is_some() {
            ctx.queue.write_texture(
                target,
                &data,
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(row),
                    rows_per_image: Some(height),
                },
                extent,
            );
            return Ok(());
        }
        let padded = row.div_ceil(256) * 256;
        let staging = ctx.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("horizon texture write"),
            size: u64::from(padded) * u64::from(height.max(1)),
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::MAP_WRITE,
            mapped_at_creation: true,
        });
        {
            let mut rows = vec![0u8; padded as usize * height.max(1) as usize];
            for r in 0..height as usize {
                let from = r * row as usize;
                rows[r * padded as usize..r * padded as usize + row as usize]
                    .copy_from_slice(&data[from..from + row as usize]);
            }
            staging
                .slice(..)
                .get_mapped_range_mut()
                .expect("mapped at creation")
                .copy_from_slice(&rows);
        }
        staging.unmap();
        ctx.encoder().copy_buffer_to_texture(
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(height),
                },
            },
            target,
            extent,
        );
        Ok(())
    })
}

/// A region of a buffer (rows of `src_width` texels from `src_offset`, starting at (`src_x`, `src_y`)) into a
/// texture, in command order.
///
/// # Safety
/// `ctx`, `buf` and `tex` are live.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_buffer_to_texture(
    ctx: *mut Ctx,
    buf: *mut Buf,
    src_offset: u64,
    src_x: u32,
    src_y: u32,
    src_width: u32,
    tex: *mut Tex,
    x: u32,
    y: u32,
    width: u32,
    height: u32,
    mip: u32,
    layer: u32,
) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("copyBufferToTexture")?;
        // SAFETY: as the caller promises.
        let (buf, tex) = unsafe { (&*buf, &*tex) };
        let bpp = tex.game_bpp;
        let src_row = u64::from(src_width * bpp);
        let start = src_offset
            + (u64::from(src_y) * u64::from(src_width) + u64::from(src_x)) * u64::from(bpp);
        let row = width * bpp;
        let stored = tex.format.block_copy_size(None).unwrap_or(bpp);
        let target = wgpu::TexelCopyTextureInfo {
            texture: &tex.texture,
            mip_level: mip,
            origin: wgpu::Origin3d { x, y, z: layer },
            aspect: wgpu::TextureAspect::All,
        };
        let extent = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        };
        if stored != bpp
            || !row.is_multiple_of(4)
            || !start.is_multiple_of(4)
            || !src_row.is_multiple_of(4)
        {
            return Err(format!(
                "buffer-to-texture copy the GPU cannot make ({} bytes a texel, rows of {row} bytes from {start})",
                bpp
            ));
        }
        let padded = row.div_ceil(256) * 256;
        let src_buffer = buf.buffer.clone();
        let device = ctx.device.clone();
        let encoder = ctx.encoder();
        if u64::from(padded) == src_row && start.is_multiple_of(u64::from(bpp)) {
            encoder.copy_buffer_to_texture(
                wgpu::TexelCopyBufferInfo {
                    buffer: &src_buffer,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset: start,
                        bytes_per_row: Some(padded),
                        rows_per_image: Some(height),
                    },
                },
                target,
                extent,
            );
            return Ok(());
        }
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("horizon rows"),
            size: u64::from(padded) * u64::from(height.max(1)),
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        for r in 0..u64::from(height) {
            encoder.copy_buffer_to_buffer(
                &src_buffer,
                start + r * src_row,
                &staging,
                r * u64::from(padded),
                u64::from(row),
            );
        }
        encoder.copy_buffer_to_texture(
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(height),
                },
            },
            target,
            extent,
        );
        Ok(())
    })
}

/// A region of a texture's mip into a buffer at `offset`, as tight rows, in command order.
///
/// # Safety
/// `ctx`, `tex` and `buf` are live.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_texture_to_buffer(
    ctx: *mut Ctx,
    tex: *mut Tex,
    buf: *mut Buf,
    offset: u64,
    mip: u32,
    x: u32,
    y: u32,
    width: u32,
    height: u32,
) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("copyTextureToBuffer")?;
        // SAFETY: as the caller promises.
        let (tex, buf) = unsafe { (&*tex, &*buf) };
        let bpp = tex
            .format
            .block_copy_size(None)
            .ok_or("copy of a depth-stencil texture")?;
        if bpp != tex.game_bpp {
            return Err("read-back of a three-component texture".into());
        }
        let row = width * bpp;
        let padded = row.div_ceil(256) * 256;
        let source = wgpu::TexelCopyTextureInfo {
            texture: &tex.texture,
            mip_level: mip,
            origin: wgpu::Origin3d { x, y, z: 0 },
            aspect: wgpu::TextureAspect::All,
        };
        let extent = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        };
        let dst = buf.buffer.clone();
        let device = ctx.device.clone();
        let encoder = ctx.encoder();
        if padded == row && offset.is_multiple_of(4) {
            encoder.copy_texture_to_buffer(
                source,
                wgpu::TexelCopyBufferInfo {
                    buffer: &dst,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset,
                        bytes_per_row: Some(padded),
                        rows_per_image: Some(height),
                    },
                },
                extent,
            );
            return Ok(());
        }
        if !row.is_multiple_of(4) || !offset.is_multiple_of(4) {
            return Err("texture read-back with rows not 4-byte aligned".into());
        }
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("horizon read rows"),
            size: u64::from(padded) * u64::from(height.max(1)),
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        encoder.copy_texture_to_buffer(
            source,
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(height),
                },
            },
            extent,
        );
        for r in 0..u64::from(height) {
            encoder.copy_buffer_to_buffer(
                &staging,
                r * u64::from(padded),
                &dst,
                offset + r * u64::from(row),
                u64::from(row),
            );
        }
        Ok(())
    })
}

/// A region between textures, in command order.
///
/// # Safety
/// `ctx`, `src` and `dst` are live.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_texture_copy(
    ctx: *mut Ctx,
    src: *mut Tex,
    dst: *mut Tex,
    mip: u32,
    dst_x: u32,
    dst_y: u32,
    src_x: u32,
    src_y: u32,
    width: u32,
    height: u32,
) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("copyTextureToTexture")?;
        // SAFETY: as the caller promises.
        let (src, dst) = unsafe { (&*src, &*dst) };
        let (a, b) = (src.texture.clone(), dst.texture.clone());
        ctx.encoder().copy_texture_to_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &a,
                mip_level: mip,
                origin: wgpu::Origin3d {
                    x: src_x,
                    y: src_y,
                    z: 0,
                },
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::TexelCopyTextureInfo {
                texture: &b,
                mip_level: mip,
                origin: wgpu::Origin3d {
                    x: dst_x,
                    y: dst_y,
                    z: 0,
                },
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        Ok(())
    })
}

/// Clears a color texture's mip, a depth texture's, or both (`color`/`depth` null for none), optionally only in
/// a region (`region_w` 0: all of it).
///
/// # Safety
/// `ctx` is live; `color` and `depth` are null or live; `rgba` points at 4 floats.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_clear(
    ctx: *mut Ctx,
    color: *mut Tex,
    rgba: *const f32,
    depth: *mut Tex,
    depth_value: f64,
    mip: u32,
    region_x: u32,
    region_y: u32,
    region_w: u32,
    region_h: u32,
) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("clear")?;
        // SAFETY: as the caller promises.
        let (color, depth) = unsafe { (color.as_ref(), depth.as_ref()) };
        // SAFETY: as the caller promises.
        let rgba = unsafe { std::slice::from_raw_parts(rgba, 4) };
        let view = |t: &Tex| {
            t.texture.create_view(&wgpu::TextureViewDescriptor {
                dimension: Some(wgpu::TextureViewDimension::D2),
                base_mip_level: mip,
                mip_level_count: Some(1),
                base_array_layer: 0,
                array_layer_count: Some(1),
                ..Default::default()
            })
        };
        let cv = color.map(view);
        let dv = depth.map(view);
        let whole = region_w == 0;
        let clear_color = wgpu::Color {
            r: f64::from(rgba[0]),
            g: f64::from(rgba[1]),
            b: f64::from(rgba[2]),
            a: f64::from(rgba[3]),
        };
        let region_pipeline = if whole {
            None
        } else {
            Some(clear_pipeline(
                ctx,
                color.map(|t| t.format),
                depth.map(|t| t.format),
            )?)
        };
        let uniform = (!whole).then(|| {
            let data: Vec<u8> = rgba
                .iter()
                .copied()
                .chain([depth_value as f32, 0.0, 0.0, 0.0])
                .flat_map(f32::to_le_bytes)
                .collect();
            let b = ctx.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("horizon clear"),
                size: 32,
                usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: true,
            });
            b.slice(..)
                .get_mapped_range_mut()
                .expect("mapped at creation")
                .copy_from_slice(&data);
            b.unmap();
            b
        });
        let bind = uniform.as_ref().map(|u| {
            ctx.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: None,
                layout: ctx.clear_layout.as_ref().expect("clear layout"),
                entries: &[wgpu::BindGroupEntry {
                    binding: 0,
                    resource: u.as_entire_binding(),
                }],
            })
        });
        let encoder = ctx.encoder();
        let colors = [cv.as_ref().map(|v| wgpu::RenderPassColorAttachment {
            view: v,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations {
                load: if whole {
                    wgpu::LoadOp::Clear(clear_color)
                } else {
                    wgpu::LoadOp::Load
                },
                store: wgpu::StoreOp::Store,
            },
        })];
        let depth_format = depth.map(|t| t.format);
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("horizon clear"),
            color_attachments: if cv.is_some() { &colors } else { &[] },
            depth_stencil_attachment: dv.as_ref().map(|v| wgpu::RenderPassDepthStencilAttachment {
                view: v,
                depth_ops: Some(wgpu::Operations {
                    load: if whole {
                        wgpu::LoadOp::Clear(depth_value as f32)
                    } else {
                        wgpu::LoadOp::Load
                    },
                    store: wgpu::StoreOp::Store,
                }),
                stencil_ops: depth_format.filter(|f| f.has_stencil_aspect()).map(|_| {
                    wgpu::Operations {
                        load: wgpu::LoadOp::Clear(0),
                        store: wgpu::StoreOp::Store,
                    }
                }),
            }),
            ..Default::default()
        });
        if let (Some(p), Some(b)) = (region_pipeline, bind) {
            pass.set_pipeline(&p);
            pass.set_bind_group(0, &b, &[]);
            pass.set_scissor_rect(region_x, region_y, region_w, region_h);
            pass.draw(0..3, 0..1);
        }
        Ok(())
    })
}

fn clear_pipeline(
    ctx: &mut Ctx,
    color: Option<TextureFormat>,
    depth: Option<TextureFormat>,
) -> Result<wgpu::RenderPipeline, String> {
    if let Some(p) = ctx
        .clears
        .get(&(color.unwrap_or(TextureFormat::R8Unorm), depth))
    {
        return Ok(p.clone());
    }
    let layout = ctx
        .clear_layout
        .get_or_insert_with(|| {
            ctx.device
                .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                    label: Some("horizon clear"),
                    entries: &[wgpu::BindGroupLayoutEntry {
                        binding: 0,
                        visibility: wgpu::ShaderStages::FRAGMENT,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: false,
                            min_binding_size: None,
                        },
                        count: None,
                    }],
                })
        })
        .clone();
    let module = ctx
        .device
        .create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("horizon clear"),
            source: wgpu::ShaderSource::Wgsl(
                "struct C { color: vec4<f32>, depth: vec4<f32> }
             @group(0) @binding(0) var<uniform> c: C;
             struct O { @location(0) color: vec4<f32>, @builtin(frag_depth) depth: f32 }
             struct D { @builtin(frag_depth) depth: f32 }
             @vertex fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
                 let p = array(vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
                 return vec4(p[i], 0.0, 1.0);
             }
             @fragment fn both() -> O { return O(c.color, c.depth.x); }
             @fragment fn color_only() -> @location(0) vec4<f32> { return c.color; }
             @fragment fn depth_only() -> D { return D(c.depth.x); }"
                    .into(),
            ),
        });
    let pl = ctx
        .device
        .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
    let target = color.map(|f| wgpu::ColorTargetState {
        format: f,
        blend: None,
        write_mask: wgpu::ColorWrites::ALL,
    });
    let entry = match (color.is_some(), depth.is_some()) {
        (true, true) => "both",
        (true, false) => "color_only",
        _ => "depth_only",
    };
    let pipeline = ctx
        .device
        .create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("horizon clear"),
            layout: Some(&pl),
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            primitive: Default::default(),
            depth_stencil: depth.map(|f| wgpu::DepthStencilState {
                format: f,
                depth_write_enabled: Some(true),
                depth_compare: Some(wgpu::CompareFunction::Always),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                targets: &[target],
            }),
            multiview_mask: None,
            cache: None,
        });
    ctx.clears.insert(
        (color.unwrap_or(TextureFormat::R8Unorm), depth),
        pipeline.clone(),
    );
    Ok(pipeline)
}

// ---------------------------------------------------------------------------------------------------------------
// Samplers

/// A sampler: address modes (0 repeat, 1 clamp), filters (0 nearest, 1 linear), anisotropy, max LOD (< 0:
/// none given).
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_sampler_create(
    ctx: *mut Ctx,
    u: u32,
    v: u32,
    min: u32,
    mag: u32,
    anisotropy: u32,
    max_lod: f64,
) -> *mut Sampler {
    guard(ctx, std::ptr::null_mut(), |ctx| {
        let address = |m| {
            if m == 0 {
                wgpu::AddressMode::Repeat
            } else {
                wgpu::AddressMode::ClampToEdge
            }
        };
        let filter = |f| {
            if f == 0 {
                wgpu::FilterMode::Nearest
            } else {
                wgpu::FilterMode::Linear
            }
        };
        // As the Vulkan backend: a max LOD of 1000 when none is given, at least 0.25, and mipmaps filtered
        // linearly when the max LOD allows more than the first level.
        let lod = if max_lod < 0.0 {
            1000.0
        } else {
            max_lod.max(0.25)
        } as f32;
        let mip = if lod > 0.25 {
            wgpu::MipmapFilterMode::Linear
        } else {
            wgpu::MipmapFilterMode::Nearest
        };
        let all_linear = min == 1 && mag == 1;
        let desc = wgpu::SamplerDescriptor {
            label: None,
            address_mode_u: address(u),
            address_mode_v: address(v),
            address_mode_w: address(u),
            mag_filter: filter(mag),
            min_filter: filter(min),
            mipmap_filter: mip,
            lod_min_clamp: 0.0,
            lod_max_clamp: lod,
            compare: None,
            anisotropy_clamp: if all_linear
                && anisotropy > 1
                && mip == wgpu::MipmapFilterMode::Linear
            {
                anisotropy.min(16) as u16
            } else {
                1
            },
            border_color: None,
        };
        let sampler = ctx.device.create_sampler(&desc);
        let nearest = ctx.device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Nearest,
            mipmap_filter: wgpu::MipmapFilterMode::Nearest,
            anisotropy_clamp: 1,
            ..desc
        });
        Ok(Box::into_raw(Box::new(Sampler { sampler, nearest })))
    })
}

/// # Safety
/// `s` came from [`hz_sampler_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_sampler_free(s: *mut Sampler) {
    if !s.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(s) });
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Pipelines

/// The device alone, for pipeline creation: the game compiles pipelines on its worker threads while its render
/// thread draws, so pipeline creation must not wait for (or touch) the frame's state. Freed with
/// [`hz_device_free`].
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_device(ctx: *mut Ctx) -> *mut wgpu::Device {
    // SAFETY: as the caller promises (called once, before any other thread uses the context).
    Box::into_raw(Box::new(unsafe { &*ctx }.device.clone()))
}

/// # Safety
/// `device` came from [`hz_device`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_device_free(device: *mut wgpu::Device) {
    if !device.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(device) });
    }
}

thread_local! {
    /// Why the last pipeline this thread created was refused.
    static PIPELINE_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

/// Why this thread's last [`hz_pipeline_create`] returned null, as UTF-8 into `out`; its length.
///
/// # Safety
/// `out` points at `cap` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pipeline_error(out: *mut u8, cap: u32) -> u32 {
    PIPELINE_ERROR.with(|e| {
        let e = e.borrow();
        let n = e.len().min(cap as usize);
        // SAFETY: as the caller promises.
        unsafe { std::ptr::copy_nonoverlapping(e.as_ptr(), out, n) };
        n as u32
    })
}

/// What Java describes a pipeline with: plain words, one array (see `WgpuPipeline.create` on the Java side).
///
/// `desc`: [immediates, depth compare, depth write, cull, topology, polygon mode,
///          buffer count, then per buffer (slot, stride, step rate),
///          attribute count, then per attribute (slot, location, offset, format),
///          target count, then per target (used, blend?, color src, color dst, color op, alpha src, alpha dst,
///          alpha op, write mask),
///          texel buffer count, then per texel buffer (binding, bytes per texel, signed)]
/// and `bias` the depth bias (scale, constant).
///
/// # Safety
/// `ctx` is live; the pointers point at what their lengths say; `name` at `name_len` UTF-8 bytes.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_pipeline_create(
    device: *const wgpu::Device,
    name: *const c_char,
    name_len: u32,
    vs: *const u8,
    vs_len: u64,
    fs: *const u8,
    fs_len: u64,
    desc: *const i32,
    desc_len: u32,
    bias_scale: f32,
    bias_constant: f32,
) -> *mut Rc<Pipeline> {
    // SAFETY: as the caller promises.
    let device = unsafe { &*device };
    let made = catch_unwind(AssertUnwindSafe(|| -> Result<*mut Rc<Pipeline>, String> {
        // SAFETY: as the caller promises.
        let (name, vs, fs, d) = unsafe {
            (
                String::from_utf8_lossy(bytes(name.cast(), u64::from(name_len))).into_owned(),
                bytes(vs, vs_len),
                bytes(fs, fs_len),
                std::slice::from_raw_parts(desc, desc_len as usize),
            )
        };
        let texels = texel_formats(d);
        let (v, f) = on_big_stack(|| -> Result<_, String> {
            let v = shader::parse(vs, true, &texels).map_err(|e| format!("{name} vertex: {e}"))?;
            let f =
                shader::parse(fs, false, &texels).map_err(|e| format!("{name} fragment: {e}"))?;
            let validate = |m: &naga::Module, what: &str| {
                naga::valid::Validator::new(
                    naga::valid::ValidationFlags::all(),
                    naga::valid::Capabilities::all(),
                )
                .validate(m)
                .map(|_| ())
                .map_err(|e| {
                    // Name the entry point's inputs and outputs: what a refusal is usually about.
                    let io: Vec<String> = m
                        .entry_points
                        .iter()
                        .flat_map(|ep| {
                            let args = ep
                                .function
                                .arguments
                                .iter()
                                .map(|a| (a.ty, a.binding.clone()));
                            let result =
                                ep.function.result.iter().map(|r| (r.ty, r.binding.clone()));
                            args.chain(result).collect::<Vec<_>>()
                        })
                        .map(|(ty, b)| format!("{b:?} {:?}", m.types[ty].inner))
                        .collect();
                    format!("{name} {what}: {e:?}; entry io: {io:?}")
                })
            };
            validate(&v.module, "vertex")?;
            validate(&f.module, "fragment")?;
            Ok((v, f))
        })?;
        let mut resources = v.resources.clone();
        for r in &f.resources {
            if !resources.iter().any(|(g, b, _)| (*g, *b) == (r.0, r.1)) {
                resources.push(*r);
            }
        }
        let (vertex, fragment) = on_big_stack(|| {
            (
                device.create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some(&name),
                    source: wgpu::ShaderSource::Naga(std::borrow::Cow::Owned(v.module)),
                }),
                device.create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some(&name),
                    source: wgpu::ShaderSource::Naga(std::borrow::Cow::Owned(f.module)),
                }),
            )
        });
        let mut at = 0;
        let mut next = || {
            let x = d.get(at).copied().unwrap_or(0);
            at += 1;
            x
        };
        let immediates = next() as u32;
        let compare = compare_op(next());
        let depth_write = next() != 0;
        let cull = next() != 0;
        let topology = match next() {
            1 => wgpu::PrimitiveTopology::LineList,
            2 => wgpu::PrimitiveTopology::LineStrip,
            3 => wgpu::PrimitiveTopology::PointList,
            5 => wgpu::PrimitiveTopology::TriangleStrip,
            // LINES and QUADS are drawn as triangles (as the Vulkan backend draws them); fans have no wgpu
            // topology and are drawn as lists.
            _ => wgpu::PrimitiveTopology::TriangleList,
        };
        let wireframe = next() == 1;
        let nbuf = next();
        let mut buffers: Vec<(u32, u64, wgpu::VertexStepMode, Vec<wgpu::VertexAttribute>)> =
            Vec::new();
        for _ in 0..nbuf {
            let (slot, stride, rate) = (next() as u32, next() as u64, next());
            buffers.push((
                slot,
                stride,
                if rate == 0 {
                    wgpu::VertexStepMode::Vertex
                } else {
                    wgpu::VertexStepMode::Instance
                },
                Vec::new(),
            ));
        }
        let nattr = next();
        for _ in 0..nattr {
            let (slot, location, offset, fmt) =
                (next() as u32, next() as u32, next() as u64, next() as u32);
            let format =
                format::vertex(fmt).ok_or_else(|| format!("{name}: vertex format {fmt}"))?;
            let b = buffers
                .iter_mut()
                .find(|b| b.0 == slot)
                .ok_or_else(|| format!("{name}: attribute in buffer slot {slot} with no buffer"))?;
            b.3.push(wgpu::VertexAttribute {
                format,
                offset,
                shader_location: location,
            });
        }
        buffers.sort_by_key(|b| b.0);
        let ntarget = next();
        let mut targets = Vec::new();
        for _ in 0..ntarget {
            let used = next() != 0;
            let has_blend = next() != 0;
            let mut eq = || wgpu::BlendComponent {
                src_factor: blend_factor(next()),
                dst_factor: blend_factor(next()),
                operation: blend_op(next()),
            };
            let (color, alpha) = (eq(), eq());
            let mask = next() as u32;
            targets.push(used.then(|| {
                (
                    has_blend.then_some(wgpu::BlendState { color, alpha }),
                    wgpu::ColorWrites::from_bits_truncate(mask),
                )
            }));
        }
        Ok(Box::into_raw(Box::new(Rc::new(Pipeline {
            name,
            vertex,
            fragment,
            resources,
            buffers: buffers.into_iter().map(|b| (b.1, b.2, b.3)).collect(),
            immediates,
            depth: (compare, depth_write, bias_scale, bias_constant as i32),
            cull,
            wireframe,
            topology,
            targets,
            variants: RefCell::new(HashMap::new()),
        }))))
    }));
    let error = match made {
        Ok(Ok(p)) => return p,
        Ok(Err(e)) => e,
        Err(_) => "panic while creating a pipeline".to_string(),
    };
    eprintln!("[horizon-wgpu] {error}");
    PIPELINE_ERROR.with(|e| *e.borrow_mut() = error);
    std::ptr::null_mut()
}

/// # Safety
/// `p` came from [`hz_pipeline_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pipeline_free(p: *mut Rc<Pipeline>) {
    if !p.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(p) });
    }
}

/// The texel buffers at the end of a pipeline description (see [`hz_pipeline_create`]).
fn texel_formats(d: &[i32]) -> texel::TexelFormats {
    let get = |i: usize| d.get(i).copied().unwrap_or(0);
    let mut at = 6;
    at += 1 + 3 * get(at) as usize;
    at += 1 + 4 * get(at) as usize;
    at += 1 + 9 * get(at) as usize;
    let count = get(at) as usize;
    at += 1;
    (0..count)
        .map(|k| {
            let base = at + 3 * k;
            (get(base) as u32, (get(base + 1) as u32, get(base + 2) != 0))
        })
        .collect()
}

fn compare_op(n: i32) -> wgpu::CompareFunction {
    use wgpu::CompareFunction as C;
    match n {
        1 => C::Less,
        2 => C::LessEqual,
        3 => C::Equal,
        4 => C::NotEqual,
        5 => C::GreaterEqual,
        6 => C::Greater,
        7 => C::Never,
        _ => C::Always,
    }
}

fn blend_factor(n: i32) -> wgpu::BlendFactor {
    use wgpu::BlendFactor as F;
    // renderpearl's BlendFactor order.
    match n {
        0 => F::Constant,
        1 => F::Constant,
        2 => F::DstAlpha,
        3 => F::Dst,
        4 => F::One,
        5 => F::OneMinusConstant,
        6 => F::OneMinusConstant,
        7 => F::OneMinusDstAlpha,
        8 => F::OneMinusDst,
        9 => F::OneMinusSrcAlpha,
        10 => F::OneMinusSrc,
        11 => F::SrcAlpha,
        12 => F::SrcAlphaSaturated,
        13 => F::Src,
        _ => F::Zero,
    }
}

fn blend_op(n: i32) -> wgpu::BlendOperation {
    use wgpu::BlendOperation as O;
    match n {
        1 => O::Subtract,
        2 => O::ReverseSubtract,
        3 => O::Min,
        4 => O::Max,
        _ => O::Add,
    }
}

fn view_dimension(dim: naga::ImageDimension, arrayed: bool) -> wgpu::TextureViewDimension {
    use wgpu::TextureViewDimension as D;
    match (dim, arrayed) {
        (naga::ImageDimension::D1, _) => D::D1,
        (naga::ImageDimension::D2, false) => D::D2,
        (naga::ImageDimension::D2, true) => D::D2Array,
        (naga::ImageDimension::D3, _) => D::D3,
        (naga::ImageDimension::Cube, false) => D::Cube,
        (naga::ImageDimension::Cube, true) => D::CubeArray,
    }
}

impl Pipeline {
    fn variant(&self, device: &wgpu::Device, key: &VariantKey) -> Arc<Variant> {
        if let Some(v) = self.variants.borrow().get(key) {
            return v.clone();
        }
        let mut groups: Vec<(u32, Vec<wgpu::BindGroupLayoutEntry>)> = Vec::new();
        for &(group, binding, kind) in &self.resources {
            let index = binding % spirv::SAMPLER_BINDING_OFFSET;
            let filterable = !key.unfilterable.contains(&index);
            let ty = match kind {
                Resource::Uniform => wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                Resource::Storage => wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Storage { read_only: true },
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                Resource::Texture {
                    dim,
                    arrayed,
                    integer,
                    depth,
                    multi,
                } => wgpu::BindingType::Texture {
                    sample_type: match (integer, depth) {
                        (Some(true), _) => wgpu::TextureSampleType::Sint,
                        (Some(false), _) => wgpu::TextureSampleType::Uint,
                        (None, true) => wgpu::TextureSampleType::Depth,
                        (None, false) => wgpu::TextureSampleType::Float { filterable },
                    },
                    view_dimension: view_dimension(dim, arrayed),
                    multisampled: multi,
                },
                Resource::Sampler { comparison } => wgpu::BindingType::Sampler(if comparison {
                    wgpu::SamplerBindingType::Comparison
                } else if filterable {
                    wgpu::SamplerBindingType::Filtering
                } else {
                    wgpu::SamplerBindingType::NonFiltering
                }),
            };
            let entry = wgpu::BindGroupLayoutEntry {
                binding,
                visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                ty,
                count: None,
            };
            match groups.iter_mut().find(|g| g.0 == group) {
                Some(g) => g.1.push(entry),
                None => groups.push((group, vec![entry])),
            }
        }
        groups.sort_by_key(|g| g.0);
        let layouts: Vec<(u32, wgpu::BindGroupLayout)> = groups
            .iter()
            .map(|(g, entries)| {
                (
                    *g,
                    device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                        label: Some(&self.name),
                        entries,
                    }),
                )
            })
            .collect();
        let top = layouts.iter().map(|l| l.0 + 1).max().unwrap_or(0);
        let slots: Vec<Option<&wgpu::BindGroupLayout>> = (0..top)
            .map(|g| layouts.iter().find(|l| l.0 == g).map(|l| &l.1))
            .collect();
        let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some(&self.name),
            bind_group_layouts: &slots,
            immediate_size: self.immediates,
        });
        let buffers: Vec<Option<wgpu::VertexBufferLayout>> = self
            .buffers
            .iter()
            .map(|(stride, step, attrs)| {
                Some(wgpu::VertexBufferLayout {
                    array_stride: *stride,
                    step_mode: *step,
                    attributes: attrs,
                })
            })
            .collect();
        let targets: Vec<Option<wgpu::ColorTargetState>> = key
            .colors
            .iter()
            .enumerate()
            .map(|(i, format)| {
                let format = (*format)?;
                let (blend, write_mask) = self.targets.get(i).cloned().flatten()?;
                Some(wgpu::ColorTargetState {
                    format,
                    blend: blend.filter(|_| !is_integer(format)),
                    write_mask,
                })
            })
            .collect();
        let (compare, write, scale, constant) = self.depth;
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some(&self.name),
            layout: Some(&layout),
            vertex: wgpu::VertexState {
                module: &self.vertex,
                entry_point: Some("main"),
                compilation_options: Default::default(),
                buffers: &buffers,
            },
            primitive: wgpu::PrimitiveState {
                topology: self.topology,
                strip_index_format: None,
                // The Vulkan backend's clockwise front face, in the rows it keeps (tests/draw.rs checks it).
                front_face: wgpu::FrontFace::Cw,
                cull_mode: self.cull.then_some(wgpu::Face::Back),
                unclipped_depth: false,
                polygon_mode: if self.wireframe {
                    wgpu::PolygonMode::Line
                } else {
                    wgpu::PolygonMode::Fill
                },
                conservative: false,
            },
            depth_stencil: key.depth.map(|format| wgpu::DepthStencilState {
                format,
                depth_write_enabled: Some(write),
                depth_compare: Some(compare),
                stencil: Default::default(),
                bias: wgpu::DepthBiasState {
                    constant,
                    slope_scale: scale,
                    clamp: 0.0,
                },
            }),
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &self.fragment,
                entry_point: Some("main"),
                compilation_options: Default::default(),
                targets: &targets,
            }),
            multiview_mask: None,
            cache: None,
        });
        let v = Arc::new(Variant {
            pipeline,
            groups: layouts,
        });
        self.variants.borrow_mut().insert(key.clone(), v.clone());
        v
    }
}

fn is_integer(f: TextureFormat) -> bool {
    matches!(
        f.sample_type(None, None),
        Some(wgpu::TextureSampleType::Sint | wgpu::TextureSampleType::Uint)
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Render passes

/// Opens a render pass: `colors` views (null for an unused slot) with `clear` flags and colors (4 floats each),
/// a depth view or null (cleared to `depth_clear` if `clear_depth`), and the render area.
///
/// # Safety
/// `ctx` is live; `colors` points at `n` view pointers, `clears` at `n` flags and `rgba` at `4 * n` floats.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_pass_begin(
    ctx: *mut Ctx,
    colors: *const *const Rc<View>,
    clears: *const u32,
    rgba: *const f32,
    n: u32,
    depth: *const Rc<View>,
    clear_depth: u32,
    depth_clear: f64,
    area_x: u32,
    area_y: u32,
    area_w: u32,
    area_h: u32,
) -> u32 {
    guard(ctx, 0, |ctx| {
        ctx.no_pass("createRenderPass")?;
        // SAFETY: as the caller promises.
        let (colors, clears, rgba, depth) = unsafe {
            (
                std::slice::from_raw_parts(colors, n as usize),
                std::slice::from_raw_parts(clears, n as usize),
                std::slice::from_raw_parts(rgba, 4 * n as usize),
                depth.as_ref().map(|d| &**d),
            )
        };
        let views: Vec<Option<&View>> = colors
            .iter()
            // SAFETY: Java passes live views or null.
            .map(|&p| unsafe { p.as_ref() }.map(|v| &**v))
            .collect();
        let attachments: Vec<Option<wgpu::RenderPassColorAttachment>> = views
            .iter()
            .enumerate()
            .map(|(i, v)| {
                v.map(|v| wgpu::RenderPassColorAttachment {
                    view: &v.attachment,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: if clears[i] != 0 {
                            wgpu::LoadOp::Clear(wgpu::Color {
                                r: f64::from(rgba[4 * i]),
                                g: f64::from(rgba[4 * i + 1]),
                                b: f64::from(rgba[4 * i + 2]),
                                a: f64::from(rgba[4 * i + 3]),
                            })
                        } else {
                            wgpu::LoadOp::Load
                        },
                        store: wgpu::StoreOp::Store,
                    },
                })
            })
            .collect();
        let size = views
            .iter()
            .flatten()
            .next()
            .map(|v| (v.width, v.height))
            .or(depth.map(|d| (d.width, d.height)))
            .unwrap_or((1, 1));
        let encoder = ctx.encoder();
        let mut pass = encoder
            .begin_render_pass(&wgpu::RenderPassDescriptor {
                label: None,
                color_attachments: &attachments,
                depth_stencil_attachment: depth.map(|d| wgpu::RenderPassDepthStencilAttachment {
                    view: &d.attachment,
                    depth_ops: Some(wgpu::Operations {
                        load: if clear_depth != 0 {
                            wgpu::LoadOp::Clear(depth_clear as f32)
                        } else {
                            wgpu::LoadOp::Load
                        },
                        store: wgpu::StoreOp::Store,
                    }),
                    stencil_ops: d.format.has_stencil_aspect().then_some(wgpu::Operations {
                        load: wgpu::LoadOp::Load,
                        store: wgpu::StoreOp::Store,
                    }),
                }),
                ..Default::default()
            })
            .forget_lifetime();
        let area = (
            area_x.min(size.0),
            area_y.min(size.1),
            area_w.min(size.0 - area_x.min(size.0)),
            area_h.min(size.1 - area_y.min(size.1)),
        );
        ctx.passes += 1;
        if ctx.trace && ctx.serial < 40 {
            eprintln!(
                "[horizon-wgpu] pass {}x{} area {:?} colors {:?} depth {:?}",
                size.0,
                size.1,
                (area_x, area_y, area_w, area_h),
                views
                    .iter()
                    .map(|v| v.map(|v| v.format))
                    .collect::<Vec<_>>(),
                depth.map(|d| d.format)
            );
        }
        pass.set_viewport(0.0, 0.0, size.0 as f32, size.1 as f32, 0.0, 1.0);
        pass.set_scissor_rect(
            area.0,
            area.1,
            area.2.max(1).min(size.0),
            area.3.max(1).min(size.1),
        );
        ctx.pass = Some(Pass {
            pass,
            colors: views.iter().map(|v| v.map(|v| v.format)).collect(),
            depth: depth.map(|d| d.format),
            area,
            pipeline: None,
            bound: HashMap::new(),
            dirty: true,
            variant: None,
            index_format: wgpu::IndexFormat::Uint16,
        });
        Ok(1)
    })
}

/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_end(ctx: *mut Ctx) {
    guard(ctx, (), |ctx| {
        ctx.pass = None;
        Ok(())
    })
}

fn pass(ctx: &mut Ctx) -> Result<&mut Pass, String> {
    ctx.pass
        .as_mut()
        .ok_or_else(|| "no render pass is open".to_string())
}

/// # Safety
/// `ctx` and `p` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_pipeline(ctx: *mut Ctx, p: *const Rc<Pipeline>) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let p = unsafe { &*p }.clone();
        let pass = pass(ctx)?;
        if pass.pipeline.as_ref().is_none_or(|q| !Rc::ptr_eq(q, &p)) {
            pass.pipeline = Some(p);
            pass.variant = None;
            pass.dirty = true;
        }
        Ok(())
    })
}

/// Binds a uniform buffer region at the uniform's index.
///
/// # Safety
/// `ctx` and `buf` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_buffer(
    ctx: *mut Ctx,
    index: u32,
    buf: *const Buf,
    offset: u64,
    len: u64,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let buffer = unsafe { &*buf }.buffer.clone();
        // A uniform block is a whole number of 16-byte rows (std140); the game may bind just the bytes it uses
        // (140 of a 144-byte block), which Vulkan takes and wgpu does not. Bind the whole rows.
        let len = (len.div_ceil(16) * 16).min(buffer.size().saturating_sub(offset));
        let pass = pass(ctx)?;
        pass.bound.insert(index, Bound::Buffer(buffer, offset, len));
        pass.dirty = true;
        Ok(())
    })
}

/// Binds a texture view and sampler at the uniform's index.
///
/// # Safety
/// `ctx`, `view` and `sampler` are live until the pass ends.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_texture(
    ctx: *mut Ctx,
    index: u32,
    view: *const Rc<View>,
    sampler: *const Sampler,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let (view, sampler) = unsafe { ((*view).clone(), &*sampler) };
        let pass = pass(ctx)?;
        pass.bound.insert(
            index,
            Bound::Texture(view, sampler.sampler.clone(), sampler.nearest.clone()),
        );
        pass.dirty = true;
        Ok(())
    })
}

/// # Safety
/// `ctx` is live; `data` points at `len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_immediates(ctx: *mut Ctx, data: *const u8, len: u32) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let data = unsafe { bytes(data, u64::from(len)) }.to_vec();
        let pass = pass(ctx)?;
        pass.pass.set_immediates(0, &data);
        Ok(())
    })
}

/// A scissor rectangle within the render area; `w` 0 (and `h` 0): the render area itself.
///
/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_scissor(
    ctx: *mut Ctx,
    x: u32,
    y: u32,
    w: u32,
    h: u32,
    enable: u32,
) {
    guard(ctx, (), |ctx| {
        let pass = pass(ctx)?;
        let (ax, ay, aw, ah) = pass.area;
        let (x0, y0, x1, y1) = if enable != 0 {
            (
                x.max(ax),
                y.max(ay),
                (x + w).min(ax + aw),
                (y + h).min(ay + ah),
            )
        } else {
            (ax, ay, ax + aw, ay + ah)
        };
        pass.pass
            .set_scissor_rect(x0, y0, x1.saturating_sub(x0), y1.saturating_sub(y0));
        Ok(())
    })
}

/// # Safety
/// `ctx` and `buf` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_vertex(
    ctx: *mut Ctx,
    slot: u32,
    buf: *const Buf,
    offset: u64,
    len: u64,
) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let buffer = unsafe { &*buf }.buffer.clone();
        let pass = pass(ctx)?;
        let end = if len == 0 {
            buffer.size()
        } else {
            offset + len
        };
        pass.pass.set_vertex_buffer(slot, buffer.slice(offset..end));
        Ok(())
    })
}

/// `int32` 1 for 32-bit indices.
///
/// # Safety
/// `ctx` and `buf` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_index(ctx: *mut Ctx, buf: *const Buf, int32: u32) {
    guard(ctx, (), |ctx| {
        // SAFETY: as the caller promises.
        let buffer = unsafe { &*buf }.buffer.clone();
        let pass = pass(ctx)?;
        pass.index_format = if int32 != 0 {
            wgpu::IndexFormat::Uint32
        } else {
            wgpu::IndexFormat::Uint16
        };
        pass.pass
            .set_index_buffer(buffer.slice(..), pass.index_format);
        Ok(())
    })
}

/// Sets the pipeline variant and bind groups the next draw needs.
fn prepare(ctx: &mut Ctx) -> Result<(), String> {
    let device = ctx.device.clone();
    let pass = pass(ctx)?;
    let pipeline = pass.pipeline.clone().ok_or("draw without a pipeline")?;
    if !pass.dirty && pass.variant.is_some() {
        return Ok(());
    }
    let mut unfilterable: Vec<u32> = pass
        .bound
        .iter()
        .filter_map(|(&i, b)| match b {
            Bound::Texture(v, ..) => (!format::filterable(v.format)).then_some(i),
            Bound::Buffer(..) => None,
        })
        .collect();
    unfilterable.sort_unstable();
    let key = VariantKey {
        colors: pass.colors.clone(),
        depth: pass.depth,
        unfilterable,
    };
    // Creating a variant compiles shaders (deep stacks: see on_big_stack); finding one made before does not.
    let cached = pipeline.variants.borrow().get(&key).cloned();
    let variant = match cached {
        Some(v) => v,
        None => on_big_stack(|| pipeline.variant(&device, &key)),
    };
    if std::env::var_os("HZ_WGPU_TRACE").is_some()
        && pipeline.variants.borrow().len() == 1
        && pass.variant.is_none()
    {
        eprintln!("[horizon-wgpu] draw with {} bound {:?}", pipeline.name, {
            let mut k: Vec<_> = pass.bound.keys().copied().collect();
            k.sort_unstable();
            k
        });
    }
    if pass
        .variant
        .as_ref()
        .is_none_or(|v| !Arc::ptr_eq(v, &variant))
    {
        pass.pass.set_pipeline(&variant.pipeline);
    }
    for (group, layout) in &variant.groups {
        let mut entries: Vec<(u32, Resource)> = pipeline
            .resources
            .iter()
            .filter(|r| r.0 == *group)
            .map(|r| (r.1, r.2))
            .collect();
        entries.sort_by_key(|e| e.0);
        let mut views = Vec::new();
        let mut samplers = Vec::new();
        let mut buffers = Vec::new();
        for &(binding, kind) in &entries {
            let index = binding % spirv::SAMPLER_BINDING_OFFSET;
            let bound = pass
                .bound
                .get(&index)
                .ok_or_else(|| format!("{}: nothing bound at uniform {index}", pipeline.name))?;
            match (kind, bound) {
                (Resource::Uniform | Resource::Storage, Bound::Buffer(b, offset, len)) => {
                    buffers.push((binding, b.clone(), *offset, *len));
                }
                (Resource::Texture { dim, arrayed, .. }, Bound::Texture(v, ..)) => {
                    let view = v.sampled(view_dimension(dim, arrayed));
                    views.push((binding, view));
                }
                (Resource::Sampler { .. }, Bound::Texture(v, filtering, nearest)) => {
                    let sampler = if format::filterable(v.format) {
                        filtering.clone()
                    } else {
                        nearest.clone()
                    };
                    samplers.push((binding, sampler));
                }
                _ => {
                    return Err(format!(
                        "{}: uniform {index} bound with the wrong kind",
                        pipeline.name
                    ));
                }
            }
        }
        let mut list: Vec<wgpu::BindGroupEntry> = Vec::new();
        for (binding, b, offset, len) in &buffers {
            list.push(wgpu::BindGroupEntry {
                binding: *binding,
                resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer: b,
                    offset: *offset,
                    size: std::num::NonZeroU64::new(*len),
                }),
            });
        }
        for (binding, v) in &views {
            list.push(wgpu::BindGroupEntry {
                binding: *binding,
                resource: wgpu::BindingResource::TextureView(v),
            });
        }
        for (binding, s) in &samplers {
            list.push(wgpu::BindGroupEntry {
                binding: *binding,
                resource: wgpu::BindingResource::Sampler(s),
            });
        }
        let group_obj = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout,
            entries: &list,
        });
        pass.pass.set_bind_group(*group, &group_obj, &[]);
    }
    pass.variant = Some(variant);
    pass.dirty = false;
    Ok(())
}

/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_draw(
    ctx: *mut Ctx,
    vertices: u32,
    instances: u32,
    first: u32,
    first_instance: u32,
) {
    guard(ctx, (), |ctx| {
        prepare(ctx)?;
        ctx.draws += 1;
        pass(ctx)?.pass.draw(
            first..first + vertices,
            first_instance..first_instance + instances,
        );
        Ok(())
    })
}

/// # Safety
/// `ctx` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_pass_draw_indexed(
    ctx: *mut Ctx,
    indices: u32,
    instances: u32,
    first: u32,
    base_vertex: i32,
    first_instance: u32,
) {
    guard(ctx, (), |ctx| {
        prepare(ctx)?;
        ctx.draws += 1;
        pass(ctx)?.pass.draw_indexed(
            first..first + indices,
            base_vertex,
            first_instance..first_instance + instances,
        );
        Ok(())
    })
}

// ---------------------------------------------------------------------------------------------------------------
// Surface

/// A surface on a Win32 window (`hwnd`, `hinstance`).
///
/// # Safety
/// `ctx` is live; the handles name a live window that outlives the surface.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_create(
    ctx: *mut Ctx,
    hwnd: *mut c_void,
    hinstance: *mut c_void,
) -> *mut Surface {
    guard(ctx, std::ptr::null_mut(), |ctx| {
        let hwnd = NonZeroIsize::new(hwnd as isize).ok_or("no window")?;
        let mut window = raw_window_handle::Win32WindowHandle::new(hwnd);
        window.hinstance = NonZeroIsize::new(hinstance as isize);
        // SAFETY: as the caller promises.
        let surface = unsafe {
            ctx.instance
                .create_surface_unsafe(wgpu::SurfaceTargetUnsafe::RawHandle {
                    raw_display_handle: Some(raw_window_handle::RawDisplayHandle::Windows(
                        raw_window_handle::WindowsDisplayHandle::new(),
                    )),
                    raw_window_handle: raw_window_handle::RawWindowHandle::Win32(window),
                })
                .map_err(|e| e.to_string())?
        };
        Ok(Box::into_raw(Box::new(Surface {
            surface,
            config: None,
            current: None,
            suboptimal: false,
        })))
    })
}

/// # Safety
/// `s` came from [`hz_surface_create`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_free(s: *mut Surface) {
    if !s.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(s) });
    }
}

/// Configures the surface (`mode`: renderpearl's PresentMode ordinal: immediate, mailbox, FIFO, FIFO relaxed);
/// whether it worked.
///
/// # Safety
/// `ctx` and `s` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_configure(
    ctx: *mut Ctx,
    s: *mut Surface,
    width: u32,
    height: u32,
    mode: u32,
) -> u32 {
    guard(ctx, 0, |ctx| {
        // SAFETY: as the caller promises.
        let s = unsafe { &mut *s };
        let caps = s.surface.get_capabilities(&ctx.adapter);
        if caps.formats.is_empty() {
            return Err(format!(
                "the surface has no format on {:?} ({}); the adapter supports it: {}",
                ctx.adapter.get_info().backend,
                ctx.adapter.get_info().name,
                ctx.adapter.is_surface_supported(&s.surface)
            ));
        }
        let format = [TextureFormat::Bgra8Unorm, TextureFormat::Rgba8Unorm]
            .into_iter()
            .find(|f| caps.formats.contains(f))
            .or_else(|| caps.formats.first().copied())
            .ok_or("the surface has no format")?;
        let wanted = match mode {
            0 => wgpu::PresentMode::Immediate,
            1 => wgpu::PresentMode::Mailbox,
            3 => wgpu::PresentMode::FifoRelaxed,
            _ => wgpu::PresentMode::Fifo,
        };
        let present_mode = if caps.present_modes.contains(&wanted) {
            wanted
        } else {
            wgpu::PresentMode::Fifo
        };
        let config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format,
            width: width.max(1),
            height: height.max(1),
            present_mode,
            desired_maximum_frame_latency: 2,
            alpha_mode: caps
                .alpha_modes
                .first()
                .copied()
                .unwrap_or(wgpu::CompositeAlphaMode::Auto),
            view_formats: vec![],
            color_space: Default::default(),
        };
        s.current = None;
        s.surface.configure(&ctx.device, &config);
        s.config = Some(config);
        Ok(1)
    })
}

/// The modes the surface can present with, as renderpearl ordinal bits.
///
/// # Safety
/// `ctx` and `s` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_modes(ctx: *mut Ctx, s: *mut Surface) -> u32 {
    guard(ctx, 4, |ctx| {
        // SAFETY: as the caller promises.
        let s = unsafe { &*s };
        let caps = s.surface.get_capabilities(&ctx.adapter);
        let mut bits = 0;
        for m in caps.present_modes {
            bits |= match m {
                wgpu::PresentMode::Immediate => 1,
                wgpu::PresentMode::Mailbox => 2,
                wgpu::PresentMode::Fifo => 4,
                wgpu::PresentMode::FifoRelaxed => 8,
                _ => 0,
            };
        }
        Ok(bits | 4)
    })
}

/// Acquires the next texture: 1 done, 0 skip this frame (occluded or timed out), -1 out of date (configure
/// again), -2 lost.
///
/// # Safety
/// `ctx` and `s` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_acquire(ctx: *mut Ctx, s: *mut Surface) -> i32 {
    guard(ctx, -2, |_| {
        // SAFETY: as the caller promises.
        let s = unsafe { &mut *s };
        s.current = None;
        Ok(match s.surface.get_current_texture() {
            wgpu::CurrentSurfaceTexture::Success(t) => {
                s.suboptimal = false;
                s.current = Some(t);
                1
            }
            wgpu::CurrentSurfaceTexture::Suboptimal(t) => {
                s.suboptimal = true;
                s.current = Some(t);
                1
            }
            wgpu::CurrentSurfaceTexture::Timeout | wgpu::CurrentSurfaceTexture::Occluded => 0,
            wgpu::CurrentSurfaceTexture::Outdated => -1,
            _ => -2,
        })
    })
}

/// # Safety
/// `s` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_suboptimal(s: *mut Surface) -> u32 {
    // SAFETY: as the caller promises.
    u32::from(unsafe { &*s }.suboptimal)
}

/// Draws the game's frame (`view`) into the acquired texture, flipped back to the screen's row order.
///
/// # Safety
/// `ctx`, `s` and `view` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_blit(ctx: *mut Ctx, s: *mut Surface, view: *const Rc<View>) {
    guard(ctx, (), |ctx| {
        ctx.no_pass("blitFromTexture")?;
        // SAFETY: as the caller promises.
        let (s, view) = unsafe { (&mut *s, &**view) };
        let Some(current) = s.current.as_ref() else {
            return Ok(());
        };
        let format = current.texture.format();
        let pipeline = blit_pipeline(ctx, format);
        let target = current.texture.create_view(&Default::default());
        let (w, h) = (
            view.width.min(current.texture.width()),
            view.height.min(current.texture.height()),
        );
        let uniform = ctx.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("horizon blit"),
            size: 16,
            usage: wgpu::BufferUsages::UNIFORM,
            mapped_at_creation: true,
        });
        uniform
            .slice(..)
            .get_mapped_range_mut()
            .expect("mapped at creation")
            .copy_from_slice(
                &[
                    h.to_le_bytes(),
                    0u32.to_le_bytes(),
                    0u32.to_le_bytes(),
                    0u32.to_le_bytes(),
                ]
                .concat(),
            );
        uniform.unmap();
        let source = view.sampled(wgpu::TextureViewDimension::D2);
        ctx.presented += 1;
        if let Ok(path) = std::env::var("HZ_WGPU_DUMP")
            && ctx.presented
                == std::env::var("HZ_WGPU_DUMP_FRAME")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(200)
        {
            dump(ctx, view, &path);
        }
        let bind = ctx.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: ctx.blit_layout.as_ref().expect("blit layout"),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&source),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: uniform.as_entire_binding(),
                },
            ],
        });
        let encoder = ctx.encoder();
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("horizon present"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: &target,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                    store: wgpu::StoreOp::Store,
                },
            })],
            ..Default::default()
        });
        if w > 0 && h > 0 {
            pass.set_pipeline(&pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.set_viewport(0.0, 0.0, w as f32, h as f32, 0.0, 1.0);
            pass.draw(0..3, 0..1);
        }
        Ok(())
    })
}

/// Debugging (`HZ_WGPU_DUMP`): the frame being presented, as a PPM file, rows as the screen shows them.
fn dump(ctx: &mut Ctx, view: &View, path: &str) {
    let (w, h) = (view.width, view.height);
    let padded = (w * 4).div_ceil(256) * 256;
    let buffer = ctx.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("horizon dump"),
        size: u64::from(padded) * u64::from(h),
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let texture = view.texture.clone();
    ctx.encoder().copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture: &texture,
            mip_level: view.base_mip,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(padded),
                rows_per_image: Some(h),
            },
        },
        wgpu::Extent3d {
            width: w,
            height: h,
            depth_or_array_layers: 1,
        },
    );
    ctx.submit();
    buffer.slice(..).map_async(wgpu::MapMode::Read, |_| {});
    let _ = ctx.device.poll(wgpu::PollType::wait_indefinitely());
    let Ok(data) = buffer.slice(..).get_mapped_range() else {
        return;
    };
    let mut out = format!(
        "P6
{w} {h}
255
"
    )
    .into_bytes();
    for row in (0..h).rev() {
        for x in 0..w {
            let at = (row * padded + x * 4) as usize;
            out.extend_from_slice(&data[at..at + 3]);
        }
    }
    let _ = std::fs::write(path, out);
    eprintln!("[horizon-wgpu] dumped the presented frame to {path}");
}

fn blit_pipeline(ctx: &mut Ctx, format: TextureFormat) -> wgpu::RenderPipeline {
    if let Some(p) = ctx.blits.get(&format) {
        return p.clone();
    }
    let layout = ctx
        .blit_layout
        .get_or_insert_with(|| {
            ctx.device
                .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                    label: Some("horizon blit"),
                    entries: &[
                        wgpu::BindGroupLayoutEntry {
                            binding: 0,
                            visibility: wgpu::ShaderStages::FRAGMENT,
                            ty: wgpu::BindingType::Texture {
                                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                                view_dimension: wgpu::TextureViewDimension::D2,
                                multisampled: false,
                            },
                            count: None,
                        },
                        wgpu::BindGroupLayoutEntry {
                            binding: 1,
                            visibility: wgpu::ShaderStages::FRAGMENT,
                            ty: wgpu::BindingType::Buffer {
                                ty: wgpu::BufferBindingType::Uniform,
                                has_dynamic_offset: false,
                                min_binding_size: None,
                            },
                            count: None,
                        },
                    ],
                })
        })
        .clone();
    // Row y of the screen is row h - 1 - y of the frame: the game's frame keeps row 0 at the bottom.
    let module = ctx
        .device
        .create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("horizon blit"),
            source: wgpu::ShaderSource::Wgsl(
                "@group(0) @binding(0) var frame: texture_2d<f32>;
             @group(0) @binding(1) var<uniform> rows: vec4<u32>;
             @vertex fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
                 let p = array(vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
                 return vec4(p[i], 0.0, 1.0);
             }
             @fragment fn fs(@builtin(position) at: vec4<f32>) -> @location(0) vec4<f32> {
                 let x = i32(at.x);
                 let y = i32(rows.x) - 1 - i32(at.y);
                 return vec4(textureLoad(frame, vec2(x, y), 0).rgb, 1.0);
             }"
                .into(),
            ),
        });
    let pl = ctx
        .device
        .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
    let pipeline = ctx
        .device
        .create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("horizon blit"),
            layout: Some(&pl),
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
    ctx.blits.insert(format, pipeline.clone());
    pipeline
}

/// Submits what was recorded and presents the acquired texture.
///
/// # Safety
/// `ctx` and `s` are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_surface_present(ctx: *mut Ctx, s: *mut Surface) -> u64 {
    guard(ctx, 0, |ctx| {
        ctx.no_pass("present")?;
        // SAFETY: as the caller promises.
        let s = unsafe { &mut *s };
        let serial = ctx.submit();
        if let Some(t) = s.current.take() {
            ctx.queue.present(t);
        }
        Ok(serial)
    })
}
