//! `shader::snap_nearest`: the nearest-sampled textures of a fragment shader are sampled at texel centers.

use horizon_wgpu_backend::shader::{from_wgsl, snap_nearest};

const SHADER: &str = "
@group(0) @binding(2) var Sampler0_: texture_2d<f32>;
@group(0) @binding(66) var Sampler0_sampler: sampler;
@group(0) @binding(3) var Sampler1_: texture_2d<f32>;
@group(0) @binding(67) var Sampler1_sampler: sampler;

@fragment
fn main(@location(0) uv: vec2<f32>) -> @location(0) vec4<f32> {
    let a = textureSample(Sampler0_, Sampler0_sampler, uv * 2.0);
    let b = textureSampleLevel(Sampler0_, Sampler0_sampler, uv, 1.0);
    let c = textureSample(Sampler1_, Sampler1_sampler, uv);
    return a + b + c;
}
";

fn wgsl(module: &naga::Module) -> String {
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(module)
    .expect("valid");
    naga::back::wgsl::write_string(module, &info, naga::back::wgsl::WriterFlags::empty())
        .expect("writes")
}

#[test]
fn only_the_named_textures_sample_texel_centers() {
    let module = from_wgsl(SHADER).expect("parses").module;
    let snapped = wgsl(&snap_nearest(&module, &[2]).expect("snaps"));
    // Both samples of binding 2 sample the first level at the texel center.
    assert_eq!(
        snapped.matches("hz_texel_center(Sampler0_").count(),
        2,
        "{snapped}"
    );
    assert_eq!(
        snapped.matches("textureSampleLevel(Sampler0_").count(),
        2,
        "{snapped}"
    );
    assert!(!snapped.contains("textureSample(Sampler0_"), "{snapped}");
    // Binding 3 is not nearest-sampled: untouched.
    assert!(!snapped.contains("hz_texel_center(Sampler1_"), "{snapped}");
    assert!(snapped.contains("textureSample(Sampler1_"), "{snapped}");
}

#[test]
fn no_nearest_textures_leaves_the_shader() {
    let module = from_wgsl(SHADER).expect("parses").module;
    let same = wgsl(&snap_nearest(&module, &[9]).expect("snaps"));
    assert!(!same.contains("hz_texel_center"), "{same}");
}
