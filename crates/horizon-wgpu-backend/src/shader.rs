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
    Ok(Parsed { module, resources })
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
