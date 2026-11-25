//! The game's texel buffers (`isamplerBuffer CloudFaces`, read with `texelFetch`) as read-only storage buffers.
//!
//! WebGPU has no texel buffers. A texel buffer of a one-component integer format (the game's only one is the
//! clouds' `R8_SINT`) becomes a storage buffer of 32-bit words at the same binding, and each `texelFetch` reads
//! the word that holds the texel, shifts its byte out and, for a signed format, extends the sign, giving the
//! vector `texelFetch` gives: (value, 0, 0, 1). Runs before [`crate::spirv::split_combined_samplers`], since a
//! GLSL `samplerBuffer` is a combined image sampler too.

use crate::spirv::{Inst, SplitError, emit, inst, parse};
use std::collections::HashMap;

const OP_TYPE_INT: u16 = 21;
const OP_TYPE_FLOAT: u16 = 22;
const OP_TYPE_VECTOR: u16 = 23;
const OP_TYPE_IMAGE: u16 = 25;
const OP_TYPE_SAMPLED_IMAGE: u16 = 27;
const OP_TYPE_RUNTIME_ARRAY: u16 = 29;
const OP_TYPE_STRUCT: u16 = 30;
const OP_TYPE_POINTER: u16 = 32;
const OP_CONSTANT: u16 = 43;
const OP_VARIABLE: u16 = 59;
const OP_LOAD: u16 = 61;
const OP_ACCESS_CHAIN: u16 = 65;
const OP_DECORATE: u16 = 71;
const OP_MEMBER_DECORATE: u16 = 72;
const OP_COMPOSITE_CONSTRUCT: u16 = 80;
const OP_IMAGE_FETCH: u16 = 95;
const OP_IMAGE: u16 = 100;
const OP_BITCAST: u16 = 124;
const OP_SHIFT_RIGHT_LOGICAL: u16 = 194;
const OP_SHIFT_RIGHT_ARITHMETIC: u16 = 195;
const OP_SHIFT_LEFT_LOGICAL: u16 = 196;
const OP_BITWISE_AND: u16 = 199;
const DIM_BUFFER: u32 = 5;
const DECORATION_BUFFER_BLOCK: u32 = 3;
const DECORATION_ARRAY_STRIDE: u32 = 6;
const DECORATION_NON_WRITABLE: u32 = 24;
const DECORATION_OFFSET: u32 = 35;
const DECORATION_BINDING: u32 = 33;
const STORAGE_UNIFORM: u32 = 2;
const STORAGE_FUNCTION: u32 = 7;

/// A texel buffer's format, by the binding it sits at: bytes per texel (1 or 4) and whether it is signed.
pub type TexelFormats = HashMap<u32, (u32, bool)>;

/// Whether an instruction is a type, constant or global variable declaration (the section new ones go in).
fn is_declaration(op: u16) -> bool {
    (19..=39).contains(&op) || (41..=52).contains(&op) || op == OP_VARIABLE
}

/// The module with each texel buffer at a binding in `formats` read as a storage buffer (see the module docs). A
/// module without texel buffers comes back as it was.
pub fn texel_buffers_to_storage(
    words: &[u32],
    formats: &TexelFormats,
) -> Result<Vec<u32>, SplitError> {
    let (mut header, insts) = parse(words)?;
    let mut bound = header[3];
    let mut fresh = || {
        let id = bound;
        bound += 1;
        id
    };
    let mut buffer_types = Vec::new();
    for i in &insts {
        let buffer_image = i.op == OP_TYPE_IMAGE && i.words.get(2) == Some(&DIM_BUFFER);
        let around_one = i.op == OP_TYPE_SAMPLED_IMAGE && buffer_types.contains(&i.words[1]);
        if buffer_image || around_one {
            buffer_types.push(i.words[0]);
        }
    }
    if buffer_types.is_empty() {
        return Ok(words.to_vec());
    }
    let pointers: Vec<u32> = insts
        .iter()
        .filter(|i| i.op == OP_TYPE_POINTER && buffer_types.contains(&i.words[2]))
        .map(|i| i.words[0])
        .collect();
    let binding_of: HashMap<u32, u32> = insts
        .iter()
        .filter(|i| i.op == OP_DECORATE && i.words.len() >= 3 && i.words[1] == DECORATION_BINDING)
        .map(|i| (i.words[0], i.words[2]))
        .collect();
    let mut variables: HashMap<u32, (u32, bool)> = HashMap::new();
    for i in &insts {
        if i.op == OP_VARIABLE && pointers.contains(&i.words[0]) {
            let binding = binding_of.get(&i.words[1]).copied().unwrap_or(u32::MAX);
            let format = formats
                .get(&binding)
                .copied()
                .ok_or(SplitError::UnknownTexelFormat)?;
            if format.0 != 1 && format.0 != 4 {
                return Err(SplitError::UnsupportedTexelFormat);
            }
            variables.insert(i.words[1], format);
        }
    }
    let scalar_of: HashMap<u32, u32> = insts
        .iter()
        .filter(|i| i.op == OP_TYPE_VECTOR)
        .map(|i| (i.words[0], i.words[1]))
        .collect();
    let floats: Vec<u32> = insts
        .iter()
        .filter(|i| i.op == OP_TYPE_FLOAT)
        .map(|i| i.words[0])
        .collect();

    let (uint, int, word_array, block, block_pointer, word_pointer) =
        (fresh(), fresh(), fresh(), fresh(), fresh(), fresh());
    let constants: Vec<(u32, u32)> = [0u32, 2, 3, 0xff, 24]
        .into_iter()
        .map(|v| (v, fresh()))
        .collect();
    let constant = |v: u32| {
        constants
            .iter()
            .find(|c| c.0 == v)
            .map(|c| c.1)
            .expect("declared constant")
    };
    // Zero and one of each fetch's component type: (type, zero, one).
    let mut components: Vec<(u32, u32, u32)> = Vec::new();

    let mut from: HashMap<u32, u32> = HashMap::new(); // a loaded image value -> its texel-buffer variable
    let mut body: Vec<Inst> = Vec::with_capacity(insts.len() + 32);
    for i in insts {
        match i.op {
            // The texel-buffer types themselves go: nothing uses them now, and naga takes no buffer images.
            OP_TYPE_IMAGE | OP_TYPE_SAMPLED_IMAGE if buffer_types.contains(&i.words[0]) => {}
            OP_TYPE_POINTER if pointers.contains(&i.words[0]) => {}
            OP_VARIABLE if variables.contains_key(&i.words[1]) => {
                body.push(inst(
                    OP_VARIABLE,
                    vec![block_pointer, i.words[1], STORAGE_UNIFORM],
                ));
            }
            OP_LOAD if variables.contains_key(&i.words[2]) => {
                from.insert(i.words[1], i.words[2]);
            }
            OP_IMAGE if from.contains_key(&i.words[2]) => {
                let variable = from[&i.words[2]];
                from.insert(i.words[1], variable);
            }
            OP_IMAGE_FETCH if from.contains_key(&i.words[2]) => {
                let (result_type, result, coord) = (i.words[0], i.words[1], i.words[3]);
                let variable = from[&i.words[2]];
                let (bytes, signed) = variables[&variable];
                let component = scalar_of.get(&result_type).copied().unwrap_or(result_type);
                let (zero, one) = match components.iter().find(|c| c.0 == component) {
                    Some(&(_, z, o)) => (z, o),
                    None => {
                        let (z, o) = (fresh(), fresh());
                        components.push((component, z, o));
                        (z, o)
                    }
                };
                let index = fresh();
                body.push(inst(OP_BITCAST, vec![uint, index, coord]));
                let mut word_index = index;
                let mut shift = None;
                if bytes == 1 {
                    let (w, low, s) = (fresh(), fresh(), fresh());
                    body.push(inst(
                        OP_SHIFT_RIGHT_LOGICAL,
                        vec![uint, w, index, constant(2)],
                    ));
                    body.push(inst(OP_BITWISE_AND, vec![uint, low, index, constant(3)]));
                    body.push(inst(OP_SHIFT_LEFT_LOGICAL, vec![uint, s, low, constant(3)]));
                    word_index = w;
                    shift = Some(s);
                }
                let (pointer, word) = (fresh(), fresh());
                body.push(inst(
                    OP_ACCESS_CHAIN,
                    vec![word_pointer, pointer, variable, constant(0), word_index],
                ));
                body.push(inst(OP_LOAD, vec![uint, word, pointer]));
                let mut value = word;
                if let Some(s) = shift {
                    let (shifted, masked) = (fresh(), fresh());
                    body.push(inst(OP_SHIFT_RIGHT_LOGICAL, vec![uint, shifted, word, s]));
                    body.push(inst(
                        OP_BITWISE_AND,
                        vec![uint, masked, shifted, constant(0xff)],
                    ));
                    value = masked;
                }
                let as_component = fresh();
                if signed && bytes == 1 {
                    let (up, as_int, extended) = (fresh(), fresh(), fresh());
                    body.push(inst(
                        OP_SHIFT_LEFT_LOGICAL,
                        vec![uint, up, value, constant(24)],
                    ));
                    body.push(inst(OP_BITCAST, vec![int, as_int, up]));
                    body.push(inst(
                        OP_SHIFT_RIGHT_ARITHMETIC,
                        vec![int, extended, as_int, constant(24)],
                    ));
                    body.push(inst(OP_BITCAST, vec![component, as_component, extended]));
                } else {
                    body.push(inst(OP_BITCAST, vec![component, as_component, value]));
                }
                body.push(inst(
                    OP_COMPOSITE_CONSTRUCT,
                    vec![result_type, result, as_component, zero, zero, one],
                ));
            }
            _ => body.push(i),
        }
    }

    // New declarations go before the first global variable (after the types they use); new decorations go
    // after the last existing annotation.
    let mut declarations = vec![
        inst(OP_TYPE_INT, vec![uint, 32, 0]),
        inst(OP_TYPE_INT, vec![int, 32, 1]),
        inst(OP_TYPE_RUNTIME_ARRAY, vec![word_array, uint]),
        inst(OP_TYPE_STRUCT, vec![block, word_array]),
        inst(OP_TYPE_POINTER, vec![block_pointer, STORAGE_UNIFORM, block]),
        inst(OP_TYPE_POINTER, vec![word_pointer, STORAGE_UNIFORM, uint]),
    ];
    for &(value, id) in &constants {
        declarations.push(inst(OP_CONSTANT, vec![uint, id, value]));
    }
    for &(component, zero, one) in &components {
        let one_bits = if floats.contains(&component) {
            1.0f32.to_bits()
        } else {
            1
        };
        declarations.push(inst(OP_CONSTANT, vec![component, zero, 0]));
        declarations.push(inst(OP_CONSTANT, vec![component, one, one_bits]));
    }
    let first_global = body
        .iter()
        .position(|i| i.op == OP_VARIABLE && i.words.get(2) != Some(&STORAGE_FUNCTION))
        .or_else(|| {
            body.iter()
                .rposition(|i| is_declaration(i.op))
                .map(|p| p + 1)
        })
        .unwrap_or(body.len());
    body.splice(first_global..first_global, declarations);

    let mut decorations = vec![
        inst(OP_DECORATE, vec![word_array, DECORATION_ARRAY_STRIDE, 4]),
        inst(OP_DECORATE, vec![block, DECORATION_BUFFER_BLOCK]),
        inst(OP_MEMBER_DECORATE, vec![block, 0, DECORATION_OFFSET, 0]),
    ];
    for &variable in variables.keys() {
        decorations.push(inst(OP_DECORATE, vec![variable, DECORATION_NON_WRITABLE]));
    }
    let after_annotations = body
        .iter()
        .rposition(|i| i.op == OP_DECORATE || i.op == OP_MEMBER_DECORATE)
        .map(|p| p + 1)
        .unwrap_or(0);
    body.splice(after_annotations..after_annotations, decorations);
    header[3] = bound;
    Ok(emit(&header, &body))
}
