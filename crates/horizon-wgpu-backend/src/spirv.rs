//! Splits the game's combined image samplers into a texture and a sampler, in the SPIR-V itself.
//!
//! The game compiles GLSL with shaderc, so every `sampler2D` is one combined image-sampler resource. wgpu, like
//! WebGPU, binds textures and samplers apart, and naga's SPIR-V reader keeps only the image of a combined one. So
//! before naga reads a module, each combined resource becomes:
//!
//! - its image, at the binding the game gave it (the frontend numbers resources by their uniform index);
//! - a sampler of its own, at that binding plus [`SAMPLER_BINDING_OFFSET`], in the same set.
//!
//! The game compiles without optimisation, so `sampler2D` values also travel as function parameters; each such
//! parameter gains a sampler parameter after it, and each call passes the matching sampler. Every load of a
//! combined value becomes a load of the image, a load of the sampler, and `OpSampledImage` of the two, under the
//! id the load had: everything that used it is unchanged.

use std::collections::{HashMap, HashSet};

/// The binding of a combined resource's sampler: its own binding plus this.
pub const SAMPLER_BINDING_OFFSET: u32 = 64;

const OP_NAME: u16 = 5;
const OP_LINE: u16 = 8;
const OP_NO_LINE: u16 = 317;
const OP_DECORATE: u16 = 71;
const OP_TYPE_SAMPLER: u16 = 26;
const OP_TYPE_SAMPLED_IMAGE: u16 = 27;
const OP_TYPE_ARRAY: u16 = 28;
const OP_TYPE_POINTER: u16 = 32;
const OP_TYPE_FUNCTION: u16 = 33;
const OP_FUNCTION_PARAMETER: u16 = 55;
const OP_FUNCTION_CALL: u16 = 57;
const OP_VARIABLE: u16 = 59;
const OP_LOAD: u16 = 61;
const OP_COPY_OBJECT: u16 = 83;
const OP_SAMPLED_IMAGE: u16 = 86;
const DECORATION_BINDING: u32 = 33;
const DECORATION_DESCRIPTOR_SET: u32 = 34;
const STORAGE_UNIFORM_CONSTANT: u32 = 0;
const MAGIC: u32 = 0x0723_0203;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Inst {
    pub(crate) op: u16,
    /// The operands, after the word that holds the opcode and the word count.
    pub(crate) words: Vec<u32>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum SplitError {
    NotSpirv,
    Truncated,
    /// An array of combined samplers, which the game's shaders do not use and this pass does not split.
    ArrayOfCombined,
    /// A texel buffer whose format the pipeline did not give.
    UnknownTexelFormat,
    /// A texel buffer of a format other than one-component 8- or 32-bit integers.
    UnsupportedTexelFormat,
}

impl std::fmt::Display for SplitError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

pub(crate) fn parse(words: &[u32]) -> Result<(Vec<u32>, Vec<Inst>), SplitError> {
    if words.len() < 5 || words[0] != MAGIC {
        return Err(SplitError::NotSpirv);
    }
    let header = words[..5].to_vec();
    let mut out = Vec::new();
    let mut at = 5;
    while at < words.len() {
        let count = (words[at] >> 16) as usize;
        if count == 0 || at + count > words.len() {
            return Err(SplitError::Truncated);
        }
        out.push(Inst {
            op: (words[at] & 0xffff) as u16,
            words: words[at + 1..at + count].to_vec(),
        });
        at += count;
    }
    Ok((header, out))
}

pub(crate) fn emit(header: &[u32], insts: &[Inst]) -> Vec<u32> {
    let mut out = header.to_vec();
    for inst in insts {
        out.push(((inst.words.len() as u32 + 1) << 16) | u32::from(inst.op));
        out.extend_from_slice(&inst.words);
    }
    out
}

pub(crate) fn inst(op: u16, words: Vec<u32>) -> Inst {
    Inst { op, words }
}

/// The module with its combined image samplers split (see the module docs). A module without any comes back as
/// it was.
pub fn split_combined_samplers(words: &[u32]) -> Result<Vec<u32>, SplitError> {
    let (mut header, mut insts) = parse(words)?;
    // The game compiles with debug info; naga takes no line markers among the types, and needs none.
    insts.retain(|i| i.op != OP_LINE && i.op != OP_NO_LINE);
    let mut bound = header[3];
    let mut fresh = || {
        let id = bound;
        bound += 1;
        id
    };

    // Combined types and the pointers to them.
    let mut sampled: HashMap<u32, u32> = HashMap::new(); // sampled image type -> its image type
    let mut pointers: HashSet<u32> = HashSet::new(); // UniformConstant pointers to a sampled image
    let mut sampler_type = None;
    for i in &insts {
        match i.op {
            OP_TYPE_SAMPLED_IMAGE => {
                sampled.insert(i.words[0], i.words[1]);
            }
            OP_TYPE_SAMPLER => sampler_type = Some(i.words[0]),
            OP_TYPE_ARRAY if sampled.contains_key(&i.words[1]) => {
                return Err(SplitError::ArrayOfCombined);
            }
            OP_TYPE_POINTER
                if i.words[1] == STORAGE_UNIFORM_CONSTANT && sampled.contains_key(&i.words[2]) =>
            {
                pointers.insert(i.words[0]);
            }
            _ => {}
        }
    }
    if pointers.is_empty() {
        return Ok(emit(&header, &insts));
    }
    let (sampler_type, declare_sampler) = match sampler_type {
        Some(id) => (id, false),
        None => (fresh(), true),
    };
    let sampler_pointer = fresh();

    // Each combined variable's sampler variable (its decorations follow the variable's own).
    let mut sampler_of: HashMap<u32, u32> = HashMap::new();
    for i in &insts {
        if i.op == OP_VARIABLE && pointers.contains(&i.words[0]) {
            sampler_of.insert(i.words[1], fresh());
        }
    }

    let mut out: Vec<Inst> = Vec::with_capacity(insts.len() + 4 * sampler_of.len());
    let mut names: HashMap<u32, String> = HashMap::new();
    for i in &insts {
        if i.op == OP_NAME {
            names.insert(i.words[0], string(&i.words[1..]));
        }
    }
    for i in insts {
        match i.op {
            OP_NAME if sampler_of.contains_key(&i.words[0]) => {
                let sampler = sampler_of[&i.words[0]];
                let name = format!(
                    "{}_sampler",
                    names.get(&i.words[0]).cloned().unwrap_or_default()
                );
                out.push(i);
                let mut words = vec![sampler];
                words.extend(encode_string(&name));
                out.push(inst(OP_NAME, words));
            }
            OP_DECORATE if sampler_of.contains_key(&i.words[0]) && i.words.len() >= 3 => {
                let sampler = sampler_of[&i.words[0]];
                let mut copy = i.words.clone();
                copy[0] = sampler;
                if copy[1] == DECORATION_BINDING {
                    copy[2] += SAMPLER_BINDING_OFFSET;
                }
                let decorated = copy[1];
                out.push(i);
                if decorated == DECORATION_BINDING || decorated == DECORATION_DESCRIPTOR_SET {
                    out.push(inst(OP_DECORATE, copy));
                }
            }
            OP_TYPE_SAMPLED_IMAGE => {
                out.push(i);
                if declare_sampler {
                    out.push(inst(OP_TYPE_SAMPLER, vec![sampler_type]));
                }
                out.push(inst(
                    OP_TYPE_POINTER,
                    vec![sampler_pointer, STORAGE_UNIFORM_CONSTANT, sampler_type],
                ));
            }
            OP_TYPE_POINTER if pointers.contains(&i.words[0]) => {
                // The variable keeps its pointer type, which now points at the image.
                out.push(inst(
                    OP_TYPE_POINTER,
                    vec![i.words[0], i.words[1], sampled[&i.words[2]]],
                ));
            }
            OP_TYPE_FUNCTION => {
                let mut words = vec![i.words[0], i.words[1]];
                for &p in &i.words[2..] {
                    words.push(p);
                    if pointers.contains(&p) {
                        words.push(sampler_pointer);
                    }
                }
                out.push(inst(OP_TYPE_FUNCTION, words));
            }
            OP_VARIABLE if sampler_of.contains_key(&i.words[1]) => {
                let sampler = sampler_of[&i.words[1]];
                out.push(i);
                out.push(inst(
                    OP_VARIABLE,
                    vec![sampler_pointer, sampler, STORAGE_UNIFORM_CONSTANT],
                ));
            }
            OP_FUNCTION_PARAMETER if pointers.contains(&i.words[0]) => {
                let sampler = fresh();
                sampler_of.insert(i.words[1], sampler);
                out.push(i);
                out.push(inst(OP_FUNCTION_PARAMETER, vec![sampler_pointer, sampler]));
            }
            OP_COPY_OBJECT if sampler_of.contains_key(&i.words[2]) => {
                let sampler = sampler_of[&i.words[2]];
                sampler_of.insert(i.words[1], sampler);
                out.push(i);
            }
            OP_FUNCTION_CALL => {
                let mut words = i.words[..3].to_vec();
                for &a in &i.words[3..] {
                    words.push(a);
                    if let Some(&s) = sampler_of.get(&a) {
                        words.push(s);
                    }
                }
                out.push(inst(OP_FUNCTION_CALL, words));
            }
            OP_LOAD
                if sampled.contains_key(&i.words[0]) && sampler_of.contains_key(&i.words[2]) =>
            {
                let (ty, result, pointer) = (i.words[0], i.words[1], i.words[2]);
                let image = fresh();
                let sampler = fresh();
                out.push(inst(OP_LOAD, vec![sampled[&ty], image, pointer]));
                out.push(inst(
                    OP_LOAD,
                    vec![sampler_type, sampler, sampler_of[&pointer]],
                ));
                out.push(inst(OP_SAMPLED_IMAGE, vec![ty, result, image, sampler]));
            }
            _ => out.push(i),
        }
    }
    header[3] = bound;
    Ok(emit(&header, &out))
}

fn string(words: &[u32]) -> String {
    let mut bytes = Vec::new();
    for w in words {
        for b in w.to_le_bytes() {
            if b == 0 {
                return String::from_utf8_lossy(&bytes).into_owned();
            }
            bytes.push(b);
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn encode_string(s: &str) -> Vec<u32> {
    let mut bytes = s.as_bytes().to_vec();
    bytes.push(0);
    while !bytes.len().is_multiple_of(4) {
        bytes.push(0);
    }
    bytes
        .chunks(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect()
}

/// The module's words from its bytes (SPIR-V is little-endian here, as shaderc writes it).
pub fn words(bytes: &[u8]) -> Vec<u32> {
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| u32::from_le_bytes(*c))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_round_trip() {
        for s in ["", "a", "abc", "abcd", "Sampler0_sampler"] {
            assert_eq!(string(&encode_string(s)), s);
        }
    }

    #[test]
    fn a_module_without_combined_samplers_is_unchanged() {
        let module = [
            MAGIC,
            0x0001_0000,
            0,
            10,
            0,
            (2 << 16) | u32::from(OP_TYPE_SAMPLER),
            3,
        ];
        assert_eq!(split_combined_samplers(&module).unwrap(), module.to_vec());
    }

    #[test]
    fn not_spirv_is_refused() {
        assert_eq!(
            split_combined_samplers(&[1, 2, 3, 4, 5]),
            Err(SplitError::NotSpirv)
        );
    }
}
