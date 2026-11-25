//! The backend end to end, as the Java side drives it: a pipeline from SPIR-V, a uniform, a vertex buffer, a draw
//! into a texture, a read-back. The triangle must land in the rows the game's Vulkan backend puts it in: row 0 at
//! clip-space y = -1.

use horizon_wgpu_backend::*;
use std::ptr;

fn spirv(wgsl: &str) -> Vec<u8> {
    let module = naga::front::wgsl::parse_str(wgsl).expect("wgsl");
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(&module)
    .expect("valid");
    // As shaderc writes the game's shaders: clip space as the source has it (naga's writer flips y by default).
    let options = naga::back::spv::Options {
        flags: naga::back::spv::WriterFlags::empty(),
        ..Default::default()
    };
    let words = naga::back::spv::write_vec(&module, &info, &options, None).expect("spv");
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

const RGBA8_UNORM: u32 = 6;
const RG32_FLOAT: i32 = 45;

/// Draws one red triangle (clip-space x, y) over a blue 64x64 texture; the texture's bytes, rows as stored.
fn draw(triangle: [(f32, f32); 3], cull: bool) -> Option<Vec<u8>> {
    let ctx = hz_create();
    if ctx.is_null() {
        eprintln!("no GPU: skipped");
        return None;
    }
    let vs = spirv(
        "struct U { offset: vec4<f32> }
         @group(0) @binding(0) var<uniform> u: U;
         struct O { @builtin(position) pos: vec4<f32>, @location(0) color: vec4<f32> }
         @vertex fn main(@location(0) p: vec2<f32>, @location(1) c: vec4<f32>) -> O {
             return O(vec4(p + u.offset.xy, 0.5, 1.0), c);
         }",
    );
    let fs = spirv(
        "@fragment fn main(@location(0) c: vec4<f32>) -> @location(0) vec4<f32> { return c; }",
    );
    #[rustfmt::skip]
    let desc: Vec<i32> = vec![
        0, 0, 0, i32::from(cull), 4, 0,                    // no immediates, depth always, no write, cull?, triangles
        1, 0, 12, 0,                                       // one buffer: slot 0, 12 bytes a vertex, per vertex
        2, 0, 0, 0, RG32_FLOAT, 0, 1, 8, RGBA8_UNORM as i32, // position, color
        1, 1, 0, 0, 0, 0, 0, 0, 0, 15,                     // one target: used, no blend, write all
    ];
    let name = "test";
    // SAFETY: every pointer below points at what its length says, and lives for the call.
    unsafe {
        let device = hz_device(ctx);
        let pipeline = hz_pipeline_create(
            device,
            name.as_ptr().cast(),
            name.len() as u32,
            vs.as_ptr(),
            vs.len() as u64,
            fs.as_ptr(),
            fs.len() as u64,
            desc.as_ptr(),
            desc.len() as u32,
            0.0,
            0.0,
        );
        assert!(!pipeline.is_null(), "pipeline refused");
        let tex = hz_texture_create(ctx, RGBA8_UNORM, 1 | 2 | 4 | 8, 64, 64, 1, 1);
        let view = hz_view_create(ctx, tex, 0, 1);
        let blue = [0.0f32, 0.0, 1.0, 1.0];
        hz_clear(ctx, tex, blue.as_ptr(), ptr::null_mut(), 1.0, 0, 0, 0, 0, 0);

        let uniform = hz_buffer_create(ctx, 256, 128 | 8);
        let offset = [0.0f32; 4];
        hz_buffer_write(ctx, uniform, 0, offset.as_ptr().cast(), 16);
        // A red triangle over the top of clip space: y from 0.2 to 1.
        let mut vertices = Vec::new();
        for (x, y) in triangle {
            vertices.extend(x.to_le_bytes());
            vertices.extend(y.to_le_bytes());
            vertices.extend([255u8, 0, 0, 255]);
        }
        let vbuf = hz_buffer_create(ctx, vertices.len() as u64, 32 | 8);
        hz_buffer_write(ctx, vbuf, 0, vertices.as_ptr(), vertices.len() as u64);

        let views = [view as *const _];
        let clears = [0u32];
        let rgba = [0.0f32; 4];
        assert_eq!(
            hz_pass_begin(
                ctx,
                views.as_ptr(),
                clears.as_ptr(),
                rgba.as_ptr(),
                1,
                ptr::null(),
                0,
                1.0,
                0,
                0,
                64,
                64
            ),
            1
        );
        hz_pass_pipeline(ctx, pipeline);
        hz_pass_buffer(ctx, 0, uniform, 0, 256);
        hz_pass_vertex(ctx, 0, vbuf, 0, vertices.len() as u64);
        hz_pass_draw(ctx, 3, 1, 0, 0);
        hz_pass_end(ctx);

        let read = hz_buffer_create(ctx, 64 * 64 * 4, 1 | 8);
        hz_texture_to_buffer(ctx, tex, read, 0, 0, 0, 0, 64, 64);
        hz_submit(ctx);
        let mut out = vec![0u8; 64 * 64 * 4];
        assert_eq!(
            hz_buffer_read(ctx, read, 0, out.len() as u64, out.as_mut_ptr()),
            1
        );
        hz_buffer_free(read);
        hz_buffer_free(vbuf);
        hz_buffer_free(uniform);
        hz_view_free(view);
        hz_texture_free(tex);
        hz_pipeline_free(pipeline);
        hz_device_free(device);
        hz_destroy(ctx);
        Some(out)
    }
}

fn pixel(out: &[u8], x: usize, row: usize) -> Vec<u8> {
    out[(row * 64 + x) * 4..(row * 64 + x) * 4 + 4].to_vec()
}

const RED: [u8; 4] = [255, 0, 0, 255];
const BLUE: [u8; 4] = [0, 0, 255, 255];

#[test]
fn a_triangle_lands_in_the_game_rows() {
    // A red triangle over the top of clip space: y from 0.2 to 1.
    let Some(out) = draw([(-1.0, 0.2), (1.0, 0.2), (0.0, 1.0)], false) else {
        return;
    };
    // Row 0 is clip-space y = -1: the triangle is in the last rows, not the first.
    assert_eq!(pixel(&out, 32, 60), RED, "the triangle, near the end rows");
    assert_eq!(pixel(&out, 32, 4), BLUE, "the cleared texture, near row 0");
}

/// The game's front faces are counter-clockwise in its clip space (y up), as OpenGL has them; its Vulkan backend
/// keeps them with a clockwise front face in its rows. Culling back faces keeps such a triangle and drops the
/// same triangle wound the other way.
#[test]
fn culling_keeps_the_game_front_faces() {
    let Some(front) = draw([(-1.0, -1.0), (1.0, -1.0), (0.0, 1.0)], true) else {
        return;
    };
    assert_eq!(
        pixel(&front, 32, 20),
        RED,
        "a counter-clockwise triangle is drawn"
    );
    let Some(back) = draw([(-1.0, -1.0), (0.0, 1.0), (1.0, -1.0)], true) else {
        return;
    };
    assert_eq!(pixel(&back, 32, 20), BLUE, "a clockwise triangle is culled");
}
