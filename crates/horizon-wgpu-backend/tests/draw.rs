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
const PASS_COLOR: &str =
    "@fragment fn main(@location(0) c: vec4<f32>) -> @location(0) vec4<f32> { return c; }";

fn draw(triangle: [(f32, f32); 3], cull: bool) -> Option<Vec<u8>> {
    draw_with(triangle, cull, PASS_COLOR)
}

/// [`draw`] with a fragment shader of its own (WGSL; it gets the vertex color at location 0).
fn draw_with(triangle: [(f32, f32); 3], cull: bool, fragment: &str) -> Option<Vec<u8>> {
    draw_shape(&triangle, 4, None, cull, fragment)
}

/// Red vertices drawn as the game's `topology` (its ordinal), through `fan_indices` (an indexed triangle fan from
/// Java's copy of the index buffer) when given.
fn draw_shape(
    points: &[(f32, f32)],
    topology: i32,
    fan_indices: Option<&[u16]>,
    cull: bool,
    fragment: &str,
) -> Option<Vec<u8>> {
    // SAFETY: no probe window: the first adapter.
    let ctx = unsafe { hz_create(ptr::null()) };
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
    let fs = spirv(fragment);
    #[rustfmt::skip]
    let desc: Vec<i32> = vec![
        0, 0, 0, i32::from(cull), topology, 0,             // no immediates, depth always, no write, cull?, topology
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
        for &(x, y) in points {
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
        match fan_indices {
            Some(indices) => {
                let bytes: Vec<u8> = indices.iter().flat_map(|i| i.to_le_bytes()).collect();
                let ibuf = hz_buffer_create(ctx, bytes.len() as u64, 64 | 8);
                hz_buffer_write(ctx, ibuf, 0, bytes.as_ptr(), bytes.len() as u64);
                hz_pass_index(ctx, ibuf, 0);
                hz_pass_draw_fan_indexed(
                    ctx,
                    bytes.as_ptr(),
                    bytes.len() as u64,
                    0,
                    indices.len() as u32,
                    1,
                    0,
                    0,
                    0,
                );
            }
            None => hz_pass_draw(ctx, points.len() as u32, 1, 0, 0),
        }
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

/// The game's fog functions, in the shape naga's SPIR-V reader gives them (each argument a pointer to a local the
/// caller fills, an early return in an `if`/`else`): a red triangle near the camera, with no fog to speak of, stays
/// red. On wgpu's Vulkan path this came out black in the game, while the same math written inline did not.
#[test]
fn nested_fog_functions_keep_the_color() {
    let fragment = "
        fn linear_fog_value(vertexDistance: ptr<function, f32>, fogStart: ptr<function, f32>, fogEnd: ptr<function, f32>) -> f32 {
            let d = (*vertexDistance);
            let s = (*fogStart);
            if (d <= s) {
                return 0f;
            } else {
                let d2 = (*vertexDistance);
                let e = (*fogEnd);
                if (d2 >= e) {
                    return 1f;
                }
            }
            let d3 = (*vertexDistance);
            let s3 = (*fogStart);
            let e3 = (*fogEnd);
            let s4 = (*fogStart);
            return ((d3 - s3) / (e3 - s4));
        }
        fn total_fog_value(sph: ptr<function, f32>, cyl: ptr<function, f32>, es: ptr<function, f32>, ee: ptr<function, f32>, rs: ptr<function, f32>, re: ptr<function, f32>) -> f32 {
            var p0: f32; var p1: f32; var p2: f32; var p3: f32; var p4: f32; var p5: f32;
            p0 = (*sph); p1 = (*es); p2 = (*ee);
            let a = linear_fog_value((&p0), (&p1), (&p2));
            p3 = (*cyl); p4 = (*rs); p5 = (*re);
            let b = linear_fog_value((&p3), (&p4), (&p5));
            return max(a, b);
        }
        fn apply_fog(inColor: ptr<function, vec4<f32>>, sph: ptr<function, f32>, cyl: ptr<function, f32>, es: ptr<function, f32>, ee: ptr<function, f32>, rs: ptr<function, f32>, re: ptr<function, f32>, fogColor: ptr<function, vec4<f32>>) -> vec4<f32> {
            var fogValue: f32;
            var p6: f32; var p7: f32; var p8: f32; var p9: f32; var p10: f32; var p11: f32;
            p6 = (*sph); p7 = (*cyl); p8 = (*es); p9 = (*ee); p10 = (*rs); p11 = (*re);
            let v = total_fog_value((&p6), (&p7), (&p8), (&p9), (&p10), (&p11));
            fogValue = v;
            let c = (*inColor);
            let f = (*fogColor);
            let m = mix(c.xyz, f.xyz, vec3((fogValue * (*fogColor)[3u])));
            return vec4<f32>(m.x, m.y, m.z, (*inColor)[3u]);
        }
        fn calculate(color: ptr<function, vec4<f32>>, distance: f32) -> vec4<f32> {
            var fc: vec4<f32>;
            var q0: vec4<f32>; var q1: f32; var q2: f32; var q3: f32; var q4: f32; var q5: f32; var q6: f32; var q7: vec4<f32>;
            fc = vec4<f32>(0.0, 0.0, 1.0, 1.0);
            q0 = (*color); q1 = distance; q2 = distance; q3 = 0.0; q4 = 1024.0; q5 = 57.6; q6 = 64.0; q7 = fc;
            return apply_fog((&q0), (&q1), (&q2), (&q3), (&q4), (&q5), (&q6), (&q7));
        }
        @fragment fn main(@location(0) c: vec4<f32>) -> @location(0) vec4<f32> {
            var color: vec4<f32>;
            color = c;
            return calculate((&color), c.r * 0.5);
        }";
    let Some(out) = draw_with([(-1.0, -1.0), (3.0, -1.0), (-1.0, 3.0)], false, fragment) else {
        return;
    };
    let p = pixel(&out, 32, 32);
    assert!(
        p[0] > 250 && p[2] < 5,
        "the color, fogged by 0.5/1024 at most: {p:?}"
    );
}

/// A triangle fan (the game draws its sky discs and the sunrise glow as fans; wgpu has none): a square of four
/// vertices covers the whole target, both triangles of it, drawn straight and through an index buffer.
#[test]
fn triangle_fans_draw_every_triangle() {
    let square = [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)];
    for indices in [None, Some(&[3u16, 0, 1, 2][..])] {
        let Some(out) = draw_shape(&square, 6, indices, false, PASS_COLOR) else {
            return;
        };
        for (x, y) in [(4, 4), (60, 4), (4, 60), (60, 60)] {
            let p = pixel(&out, x, y);
            assert!(
                p[0] > 250 && p[2] < 5,
                "({x}, {y}) of the fan, indexed {}: {p:?}",
                indices.is_some()
            );
        }
    }
}

/// Timestamps (the game's GPU timings): two written around some work read back in order, in nanoseconds, once
/// the GPU is done; one never written reads as none.
#[test]
fn timestamps_read_back_in_order() {
    // SAFETY: no probe window: the first adapter.
    let ctx = unsafe { hz_create(ptr::null()) };
    if ctx.is_null() {
        eprintln!("no GPU: skipped");
        return;
    }
    // SAFETY: every pointer below is live for its call.
    unsafe {
        let pool = hz_timestamps_create(ctx, 3);
        if pool.is_null() {
            eprintln!("no timestamp queries: skipped");
            return;
        }
        hz_timestamp_write(ctx, pool, 0);
        let tex = hz_texture_create(ctx, RGBA8_UNORM, 1 | 2 | 4 | 8, 256, 256, 1, 1);
        let blue = [0.0f32, 0.0, 1.0, 1.0];
        hz_clear(ctx, tex, blue.as_ptr(), ptr::null_mut(), 1.0, 0, 0, 0, 0, 0);
        hz_timestamp_write(ctx, pool, 1);
        let serial = hz_submit(ctx);
        assert_eq!(hz_wait(ctx, serial, -1), 1);
        let mut out = [0i64; 3];
        hz_timestamps_read(ctx, pool, 0, 3, out.as_mut_ptr());
        assert!(out[0] >= 0 && out[1] >= out[0], "timestamps {out:?}");
        assert_eq!(out[2], -1, "the unwritten one");
        hz_timestamps_free(pool);
    }
}
