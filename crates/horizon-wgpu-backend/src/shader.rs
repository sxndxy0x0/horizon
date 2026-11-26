//! The game's SPIR-V as wgpu shader modules.
//!
//! 1. [`crate::texel::texel_buffers_to_storage`]: WebGPU has no texel buffers.
//!    [`crate::spirv::split_combined_samplers`]: wgpu binds textures and samplers apart.
//! 2. naga reads the module.
//! 3. A vertex module's clip-space `y` is negated. The game keeps textures with row 0 at clip-space `y = -1`
//!    (OpenGL's memory order, which its Vulkan backend keeps with a plain viewport and a flipped blit at present);
//!    wgpu puts clip-space `y = +1` at row 0. Negating `y` puts every pixel in the row the Vulkan backend puts it,
//!    so render targets, copies and read-backs hold the same rows, and the surface blit flips back.
//! 4. An output array (`out vec4 coeff[3]` at location 0: the game's order-independent transparency writes one
//!    element per color target) becomes one output per element, at locations 0, 1, 2: WebGPU has no array
//!    inputs or outputs.
//! 5. A pointer parameter a function only reads (naga's SPIR-V reader makes every GLSL `in` parameter one, the
//!    caller passing a pointer to a local copy) becomes a value parameter. On wgpu's Vulkan path a function that
//!    passes a pointer to its own local on to another function read zeros through it: the game's fog functions
//!    came out black. Values are what GLSL meant anyway.

use std::collections::HashMap;

use naga::{Expression, Handle, Module, Span, Statement, TypeInner};

pub struct Parsed {
    pub module: Module,
    /// Each resource the module declares: (group, binding, kind).
    pub resources: Vec<(u32, u32, Resource)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Resource {
    Uniform,
    /// A read-only storage buffer (a texel buffer, see [`crate::texel`]).
    Storage,
    Texture {
        dim: naga::ImageDimension,
        arrayed: bool,
        /// None: float; Some(true) signed int; Some(false) unsigned int.
        integer: Option<bool>,
        depth: bool,
        multi: bool,
    },
    Sampler {
        comparison: bool,
    },
}

pub fn parse(
    spirv: &[u8],
    vertex: bool,
    texels: &crate::texel::TexelFormats,
) -> Result<Parsed, String> {
    let words = crate::spirv::words(spirv);
    let words = crate::texel::texel_buffers_to_storage(&words, texels)
        .map_err(|e| format!("texel buffers: {e}"))?;
    let split = crate::spirv::split_combined_samplers(&words)
        .map_err(|e| format!("splitting samplers: {e}"))?;
    let bytes: Vec<u8> = split.iter().flat_map(|w| w.to_le_bytes()).collect();
    let options = naga::front::spv::Options {
        adjust_coordinate_space: false,
        strict_capabilities: false,
        block_ctx_dump_prefix: None,
    };
    let mut module =
        naga::front::spv::parse_u8_slice(&bytes, &options).map_err(|e| format!("naga: {e:?}"))?;
    if vertex {
        flip_y(&mut module)?;
    }
    flatten_output_arrays(&mut module)?;
    by_value_arguments(&mut module);
    Ok(Parsed {
        resources: resources(&module)?,
        module,
    })
}

/// Debugging (`HZ_WGPU_OVERRIDE=folder`): a pipeline's shader from a WGSL file there (named as `HZ_WGPU_WGSL`
/// writes them) in place of the game's, to look at one step of it at a time.
pub fn from_wgsl(text: &str) -> Result<Parsed, String> {
    let module = naga::front::wgsl::parse_str(text).map_err(|e| e.emit_to_string(text))?;
    Ok(Parsed {
        resources: resources(&module)?,
        module,
    })
}

/// The fragment shader `module` with each texture at a binding in `bindings` (sampled with nearest filtering and
/// no mip levels past the first) sampled at the center of the texel it samples: `textureSample(t, s, uv)` and
/// `textureSampleLevel(t, s, uv, l)` become `textureSampleLevel(t, s, center(t, uv), 0.0)`. With no levels past
/// the first, that is the same texel.
///
/// For D3D12, where drivers (AMD's) round a nearest-sampled coordinate that lies on a texel edge to the next texel
/// while Vulkan and OpenGL on the same GPU take the one below: the game draws its logo exactly 1:1, every
/// coordinate on an edge, and its edges came out a texel off. A texel's center is the same texel on every API.
/// (Mipmapped textures are left alone: sampling them with gradients the shader works out changes which level
/// some pixels take.)
///
/// Done on WGSL text: samples whose texture comes in as a function argument are left as they are (the game's
/// helpers that take a sampler place their coordinates themselves).
pub fn snap_nearest(module: &Module, bindings: &[u32]) -> Result<Module, String> {
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(module)
    .map_err(|e| format!("{e:?}"))?;
    let text =
        naga::back::wgsl::write_string(module, &info, naga::back::wgsl::WriterFlags::empty())
            .map_err(|e| e.to_string())?;
    // The 2D float textures at those bindings, by name: `@group(g) @binding(b) var name: texture_2d<f32>;`.
    let mut names = Vec::new();
    let mut rest = text.as_str();
    while let Some(at) = rest.find("@binding(") {
        rest = &rest[at + "@binding(".len()..];
        let binding: u32 = rest[..rest.find(')').unwrap_or(0)]
            .parse()
            .unwrap_or(u32::MAX);
        let Some(var) = rest.find("var ") else { break };
        let decl = &rest[var + 4..rest[var..].find(';').map_or(rest.len(), |e| var + e)];
        if let Some((name, ty)) = decl.split_once(':')
            && ty.trim() == "texture_2d<f32>"
            && bindings.contains(&binding)
        {
            names.push(name.trim().to_string());
        }
    }
    if names.is_empty() {
        return Ok(module.clone());
    }
    let mut out = String::with_capacity(text.len() + 512);
    let mut rest = text.as_str();
    let mut changed = false;
    while let Some(at) = rest.find("textureSample") {
        out.push_str(&rest[..at]);
        rest = &rest[at..];
        let (call, level) = if rest.starts_with("textureSample(") {
            ("textureSample(", false)
        } else if rest.starts_with("textureSampleLevel(") {
            ("textureSampleLevel(", true)
        } else {
            out.push_str("textureSample");
            rest = &rest["textureSample".len()..];
            continue;
        };
        let args_at = call.len();
        let Some((args, len)) = call_arguments(&rest[args_at..]) else {
            out.push_str(call);
            rest = &rest[args_at..];
            continue;
        };
        let wanted = if level { 4 } else { 3 };
        if args.len() == wanted && names.iter().any(|n| n == args[0]) {
            let (t, sampler, uv) = (args[0], args[1], args[2]);
            out.push_str(&format!(
                "textureSampleLevel({t}, {sampler}, hz_texel_center({t}, {uv}), 0.0)"
            ));
            changed = true;
        } else {
            out.push_str(&rest[..args_at + len]);
        }
        rest = &rest[args_at + len..];
    }
    out.push_str(rest);
    if !changed {
        return Ok(module.clone());
    }
    out.push_str(
        "\nfn hz_texel_center(t: texture_2d<f32>, uv: vec2<f32>) -> vec2<f32> {\n    \
         let size = vec2<f32>(textureDimensions(t));\n    return (floor(uv * size) + 0.5) / size;\n}\n",
    );
    Ok(from_wgsl(&out)?.module)
}

/// The top-level arguments of a call whose text starts just after its `(`, and the length up to and with its
/// `)`.
fn call_arguments(text: &str) -> Option<(Vec<&str>, usize)> {
    let mut depth = 0usize;
    let mut start = 0;
    let mut args = Vec::new();
    for (i, c) in text.char_indices() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' if depth > 0 => depth -= 1,
            ')' => {
                args.push(text[start..i].trim());
                return Some((args, i + 1));
            }
            ',' if depth == 0 => {
                args.push(text[start..i].trim());
                start = i + 1;
            }
            _ => {}
        }
    }
    None
}

/// Each resource the module declares: (group, binding, kind).
fn resources(module: &Module) -> Result<Vec<(u32, u32, Resource)>, String> {
    let mut resources = Vec::new();
    for (_, global) in module.global_variables.iter() {
        let Some(binding) = &global.binding else {
            continue;
        };
        let kind = match (&global.space, &module.types[global.ty].inner) {
            (naga::AddressSpace::Uniform, _) => Resource::Uniform,
            (naga::AddressSpace::Storage { .. }, _) => Resource::Storage,
            (
                _,
                TypeInner::Image {
                    dim,
                    arrayed,
                    class,
                },
            ) => {
                let (integer, depth, multi) = match class {
                    naga::ImageClass::Sampled { kind, multi } => (
                        match kind {
                            naga::ScalarKind::Sint => Some(true),
                            naga::ScalarKind::Uint => Some(false),
                            _ => None,
                        },
                        false,
                        *multi,
                    ),
                    naga::ImageClass::Depth { multi } => (None, true, *multi),
                    _ => return Err("storage images are not used by the game's shaders".into()),
                };
                Resource::Texture {
                    dim: *dim,
                    arrayed: *arrayed,
                    integer,
                    depth,
                    multi,
                }
            }
            (_, TypeInner::Sampler { comparison }) => Resource::Sampler {
                comparison: *comparison,
            },
            _ => continue,
        };
        resources.push((binding.group, binding.binding, kind));
    }
    Ok(resources)
}

/// Negates `y` of the position each vertex entry point returns (see the module docs).
fn flip_y(module: &mut Module) -> Result<(), String> {
    let types = module.types.clone();
    let mut vec4 = None;
    for (handle, ty) in types.iter() {
        if let TypeInner::Vector {
            size: naga::VectorSize::Quad,
            scalar,
        } = ty.inner
            && scalar.kind == naga::ScalarKind::Float
            && scalar.width == 4
        {
            vec4 = Some(handle);
        }
    }
    for ep in module.entry_points.iter_mut() {
        if ep.stage != naga::ShaderStage::Vertex {
            continue;
        }
        let Some(result) = ep.function.result.clone() else {
            continue;
        };
        let f = &mut ep.function;
        let position_member = match (&result.binding, &types[result.ty].inner) {
            (Some(b), _) if is_position(b) => None,
            (None, TypeInner::Struct { members, .. }) => Some(
                members
                    .iter()
                    .position(|m| m.binding.as_ref().is_some_and(is_position))
                    .ok_or("vertex output without a position")?,
            ),
            _ => return Err("vertex output without a position".into()),
        };
        let body = std::mem::take(&mut f.body);
        let mut out = naga::Block::with_capacity(body.len() + 2);
        let mut flipped = false;
        for (stmt, span) in body.span_into_iter() {
            if let Statement::Return { value: Some(value) } = stmt {
                let first = f.expressions.len();
                let mut add = |e: Expression| f.expressions.append(e, Span::UNDEFINED);
                let flip = |add: &mut dyn FnMut(Expression) -> Handle<Expression>,
                            pos: Handle<Expression>| {
                    let c: Vec<_> = (0..4)
                        .map(|i| {
                            add(Expression::AccessIndex {
                                base: pos,
                                index: i,
                            })
                        })
                        .collect();
                    let y = add(Expression::Unary {
                        op: naga::UnaryOperator::Negate,
                        expr: c[1],
                    });
                    (c, y)
                };
                let new_value = match position_member {
                    None => {
                        let (c, y) = flip(&mut add, value);
                        add(Expression::Compose {
                            ty: vec4.ok_or("no vec4 type")?,
                            components: vec![c[0], y, c[2], c[3]],
                        })
                    }
                    Some(m) => {
                        let TypeInner::Struct { members, .. } = &types[result.ty].inner else {
                            unreachable!()
                        };
                        let mut components = Vec::with_capacity(members.len());
                        for (i, member) in members.iter().enumerate() {
                            let field = add(Expression::AccessIndex {
                                base: value,
                                index: i as u32,
                            });
                            if i == m {
                                let (c, y) = flip(&mut add, field);
                                components.push(add(Expression::Compose {
                                    ty: member.ty,
                                    components: vec![c[0], y, c[2], c[3]],
                                }));
                            } else {
                                components.push(field);
                            }
                        }
                        add(Expression::Compose {
                            ty: result.ty,
                            components,
                        })
                    }
                };
                let range = naga::Range::new_from_bounds(
                    f.expressions
                        .iter()
                        .nth(first)
                        .map(|(h, _)| h)
                        .ok_or("no expressions")?,
                    new_value,
                );
                out.push(Statement::Emit(range), span);
                out.push(
                    Statement::Return {
                        value: Some(new_value),
                    },
                    span,
                );
                flipped = true;
            } else {
                out.push(stmt, span);
            }
        }
        f.body = out;
        if !flipped {
            return Err("vertex entry point without a return".into());
        }
    }
    Ok(())
}

/// Each output that is an array of N values at location L becomes N outputs at L to L + N - 1 (see the module docs).
fn flatten_output_arrays(module: &mut Module) -> Result<(), String> {
    for i in 0..module.entry_points.len() {
        let Some(result) = module.entry_points[i].function.result.clone() else {
            continue;
        };
        // A lone output array (the result itself, not a struct member) is handled as a struct of one member.
        let lone = matches!(result.binding, Some(naga::Binding::Location { .. }))
            && matches!(module.types[result.ty].inner, TypeInner::Array { .. });
        let members = match module.types[result.ty].inner.clone() {
            TypeInner::Struct { members, .. } => members,
            _ if lone => vec![naga::StructMember {
                name: Some("out".into()),
                ty: result.ty,
                binding: result.binding.clone(),
                offset: 0,
            }],
            _ => continue,
        };
        let located_array = |m: &naga::StructMember| {
            matches!(m.binding, Some(naga::Binding::Location { .. }))
                && matches!(module.types[m.ty].inner, TypeInner::Array { .. })
        };
        if !members.iter().any(located_array) {
            continue;
        }
        let mut flat = Vec::new();
        // For each new member: (original member, element of it).
        let mut plan: Vec<(u32, Option<u32>)> = Vec::new();
        let mut offset = 0;
        for (index, m) in members.iter().enumerate() {
            let element = match (&module.types[m.ty].inner, &m.binding) {
                (
                    TypeInner::Array {
                        base,
                        size: naga::ArraySize::Constant(n),
                        ..
                    },
                    Some(naga::Binding::Location { .. }),
                ) => Some((*base, n.get())),
                _ => None,
            };
            match element {
                Some((base, n)) => {
                    let Some(naga::Binding::Location {
                        location,
                        interpolation,
                        sampling,
                        blend_src,
                        per_primitive,
                    }) = m.binding.clone()
                    else {
                        unreachable!()
                    };
                    let size = module.types[base].inner.size(module.to_ctx());
                    for k in 0..n {
                        flat.push(naga::StructMember {
                            name: m.name.as_ref().map(|s| format!("{s}_{k}")),
                            ty: base,
                            binding: Some(naga::Binding::Location {
                                location: location + k,
                                interpolation,
                                sampling,
                                blend_src,
                                per_primitive,
                            }),
                            offset,
                        });
                        offset += size;
                        plan.push((index as u32, Some(k)));
                    }
                }
                None => {
                    let size = module.types[m.ty].inner.size(module.to_ctx());
                    flat.push(naga::StructMember {
                        offset,
                        ..m.clone()
                    });
                    offset += size;
                    plan.push((index as u32, None));
                }
            }
        }
        let flat_ty = module.types.insert(
            naga::Type {
                name: Some("FlatOutputs".into()),
                inner: TypeInner::Struct {
                    members: flat,
                    span: offset,
                },
            },
            Span::UNDEFINED,
        );
        let f = &mut module.entry_points[i].function;
        let body = std::mem::take(&mut f.body);
        let mut out = naga::Block::with_capacity(body.len() + 2);
        for (stmt, span) in body.span_into_iter() {
            if let Statement::Return { value: Some(value) } = stmt {
                let first = f.expressions.len();
                let mut components = Vec::with_capacity(plan.len());
                for &(member, element) in &plan {
                    let field = if lone {
                        value
                    } else {
                        f.expressions.append(
                            Expression::AccessIndex {
                                base: value,
                                index: member,
                            },
                            Span::UNDEFINED,
                        )
                    };
                    components.push(match element {
                        Some(k) => f.expressions.append(
                            Expression::AccessIndex {
                                base: field,
                                index: k,
                            },
                            Span::UNDEFINED,
                        ),
                        None => field,
                    });
                }
                let composed = f.expressions.append(
                    Expression::Compose {
                        ty: flat_ty,
                        components,
                    },
                    Span::UNDEFINED,
                );
                let start = f
                    .expressions
                    .iter()
                    .nth(first)
                    .map(|(h, _)| h)
                    .ok_or("no expressions")?;
                out.push(
                    Statement::Emit(naga::Range::new_from_bounds(start, composed)),
                    span,
                );
                out.push(
                    Statement::Return {
                        value: Some(composed),
                    },
                    span,
                );
            } else {
                out.push(stmt, span);
            }
        }
        f.body = out;
        f.result = Some(naga::FunctionResult {
            ty: flat_ty,
            binding: None,
        });
    }
    Ok(())
}

fn is_position(b: &naga::Binding) -> bool {
    matches!(b, naga::Binding::BuiltIn(naga::BuiltIn::Position { .. }))
}

/// GLSL `in` parameters by value (see the module docs, step 5): each pointer parameter a function only reads
/// becomes a value parameter, copied into a local at the function's start (the body keeps using that local as it
/// used the pointer), and each call passes the loaded value. Parameters a function writes, or hands on to a call
/// that may write them, stay pointers.
fn by_value_arguments(module: &mut Module) {
    use naga::{Expression as E, Statement as S};

    /// The expression a pointer expression is derived from (through access chains).
    fn root(exprs: &naga::Arena<E>, mut h: Handle<E>) -> Handle<E> {
        loop {
            match exprs[h] {
                E::AccessIndex { base, .. } | E::Access { base, .. } => h = base,
                _ => return h,
            }
        }
    }
    /// Every Store pointer and every call (function, its arguments) in a block, nested blocks included.
    fn walk(
        block: &naga::Block,
        stores: &mut Vec<Handle<E>>,
        calls: &mut Vec<(Handle<naga::Function>, Vec<Handle<E>>)>,
    ) {
        for stmt in block.iter() {
            match stmt {
                S::Store { pointer, .. } => stores.push(*pointer),
                S::Call {
                    function,
                    arguments,
                    ..
                } => calls.push((*function, arguments.clone())),
                S::Block(b) => walk(b, stores, calls),
                S::If { accept, reject, .. } => {
                    walk(accept, stores, calls);
                    walk(reject, stores, calls);
                }
                S::Loop {
                    body, continuing, ..
                } => {
                    walk(body, stores, calls);
                    walk(continuing, stores, calls);
                }
                S::Switch { cases, .. } => {
                    for c in cases {
                        walk(&c.body, stores, calls);
                    }
                }
                S::Atomic { pointer, .. } => stores.push(*pointer),
                _ => {}
            }
        }
    }

    // Which arguments of which functions are read-only pointers, to a fixed point: an argument handed to a call
    // stays a pointer unless the callee's parameter is itself read-only.
    let handles: Vec<Handle<naga::Function>> = module.functions.iter().map(|(h, _)| h).collect();
    let mut by_value: HashMap<Handle<naga::Function>, Vec<bool>> = HashMap::new();
    for &h in &handles {
        let f = &module.functions[h];
        let flags = f
            .arguments
            .iter()
            .map(|a| {
                matches!(
                    module.types[a.ty].inner,
                    TypeInner::Pointer {
                        space: naga::AddressSpace::Function,
                        ..
                    }
                )
            })
            .collect();
        by_value.insert(h, flags);
    }
    loop {
        let mut changed = false;
        for &h in &handles {
            let f = &module.functions[h];
            let mut stores = Vec::new();
            let mut calls = Vec::new();
            walk(&f.body, &mut stores, &mut calls);
            let argument_of = |e: Handle<E>| match f.expressions[root(&f.expressions, e)] {
                E::FunctionArgument(i) => Some(i as usize),
                _ => None,
            };
            let mut written = vec![false; f.arguments.len()];
            for s in stores {
                if let Some(i) = argument_of(s) {
                    written[i] = true;
                }
            }
            for (callee, args) in &calls {
                for (k, &a) in args.iter().enumerate() {
                    if let Some(i) = argument_of(a) {
                        let callee_reads_only = by_value
                            .get(callee)
                            .and_then(|v| v.get(k))
                            .copied()
                            .unwrap_or(false);
                        if !callee_reads_only {
                            written[i] = true;
                        }
                    }
                }
            }
            let flags = by_value.get_mut(&h).expect("every function");
            for (i, w) in written.into_iter().enumerate() {
                if w && flags[i] {
                    flags[i] = false;
                    changed = true;
                }
            }
        }
        if !changed {
            break;
        }
    }

    // Each function: its read-only pointer arguments become values, copied into locals the body points at.
    let types = module.types.clone();
    for &h in &handles {
        let flags = by_value[&h].clone();
        if !flags.contains(&true) {
            continue;
        }
        let f = &mut module.functions[h];
        let mut stores = Vec::new();
        for (i, &convert) in flags.iter().enumerate() {
            if !convert {
                continue;
            }
            let TypeInner::Pointer { base, .. } = types[f.arguments[i].ty].inner else {
                continue;
            };
            f.arguments[i].ty = base;
            let local = f.local_variables.append(
                naga::LocalVariable {
                    name: f.arguments[i].name.as_ref().map(|n| format!("{n}_in")),
                    ty: base,
                    init: None,
                },
                Span::UNDEFINED,
            );
            // Where the body named the pointer argument, it now names the local.
            let pointers: Vec<Handle<E>> = f
                .expressions
                .iter()
                .filter(|(_, e)| matches!(e, E::FunctionArgument(k) if *k as usize == i))
                .map(|(eh, _)| eh)
                .collect();
            for &p in &pointers {
                f.expressions[p] = E::LocalVariable(local);
            }
            let value = f
                .expressions
                .append(E::FunctionArgument(i as u32), Span::UNDEFINED);
            let pointer = match pointers.first() {
                Some(&p) => p,
                None => f
                    .expressions
                    .append(E::LocalVariable(local), Span::UNDEFINED),
            };
            stores.push(S::Store { pointer, value });
        }
        let mut body = std::mem::take(&mut f.body);
        let mut out = naga::Block::with_capacity(body.len() + stores.len());
        for s in stores {
            out.push(s, Span::UNDEFINED);
        }
        out.append(&mut body);
        f.body = out;
    }

    // Each call passes a loaded value where its callee now takes one.
    fn rewrite(
        block: &mut naga::Block,
        exprs: &mut naga::Arena<E>,
        by_value: &HashMap<Handle<naga::Function>, Vec<bool>>,
    ) {
        let old = std::mem::take(block);
        let mut out = naga::Block::with_capacity(old.len());
        for (mut stmt, span) in old.span_into_iter() {
            match &mut stmt {
                S::Call {
                    function,
                    arguments,
                    ..
                } => {
                    if let Some(flags) = by_value.get(function) {
                        for (k, a) in arguments.iter_mut().enumerate() {
                            if flags.get(k).copied().unwrap_or(false) {
                                let load = exprs.append(E::Load { pointer: *a }, Span::UNDEFINED);
                                out.push(S::Emit(naga::Range::new_from_bounds(load, load)), span);
                                *a = load;
                            }
                        }
                    }
                }
                S::Block(b) => rewrite(b, exprs, by_value),
                S::If { accept, reject, .. } => {
                    rewrite(accept, exprs, by_value);
                    rewrite(reject, exprs, by_value);
                }
                S::Loop {
                    body, continuing, ..
                } => {
                    rewrite(body, exprs, by_value);
                    rewrite(continuing, exprs, by_value);
                }
                S::Switch { cases, .. } => {
                    for c in cases.iter_mut() {
                        rewrite(&mut c.body, exprs, by_value);
                    }
                }
                _ => {}
            }
            out.push(stmt, span);
        }
        *block = out;
    }
    for &h in &handles {
        let f = &mut module.functions[h];
        let mut body = std::mem::take(&mut f.body);
        rewrite(&mut body, &mut f.expressions, &by_value);
        f.body = body;
    }
    for ep in module.entry_points.iter_mut() {
        let f = &mut ep.function;
        let mut body = std::mem::take(&mut f.body);
        rewrite(&mut body, &mut f.expressions, &by_value);
        f.body = body;
    }
}
