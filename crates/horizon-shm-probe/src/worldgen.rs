//! P6 of `docs/design/java-mods-everything.md`: the live Java game's `NoiseBasedChunkGenerator.buildTerrain` (noise
//! and aquifers, material surface, carvers) done by Horizon's port of it, `horizon-world`'s generator.
//!
//! Java asks for a chunk's terrain ([`hz_gen_terrain`]) only when its own would be the same: vanilla's settings for
//! the dimension, no old chunks to blend with and no structure that adapts the terrain near the chunk (Java's
//! `Beardifier` is empty), so the generator here runs without structures. It answers with the chunk's block states
//! (vanilla's state ids: `facts.json` lists them in vanilla's order, and Java checks every name against its own
//! registry before it asks) and the fluid positions vanilla marks for postprocessing. Java writes them into its
//! `ProtoChunk` and primes the heightmaps from them, as vanilla's fill leaves them.

use horizon_core::position::ChunkPos;
use horizon_world::registry::BlockRegistry;
use horizon_world::worldgen::generator::{GeneratorData, GeneratorWorker, OverworldGenerator};
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

/// A dimension's generator for one world seed; never freed (one per dimension of a world played).
pub struct Gen {
    generator: OverworldGenerator,
    min_y: i32,
    height: i32,
}

/// A resource folder's blocks, their names as Java writes them, and its worldgen data.
#[derive(Clone)]
struct Data {
    names: Arc<Vec<String>>,
    generator: Arc<GeneratorData>,
}

/// Each resource folder's data, loaded once.
static DATA: OnceLock<Mutex<HashMap<PathBuf, Data>>> = OnceLock::new();
/// The last error, for [`hz_gen_error`].
static ERROR: Mutex<String> = Mutex::new(String::new());
/// Time spent in [`hz_gen_terrain`], and chunks made.
static NANOS: AtomicU64 = AtomicU64::new(0);
static CHUNKS: AtomicU64 = AtomicU64::new(0);

thread_local! {
    /// This thread's worker for each generator (workers hold per-thread caches).
    static WORKERS: RefCell<HashMap<usize, GeneratorWorker>> = RefCell::new(HashMap::new());
}

fn fail(e: impl std::fmt::Display) {
    *ERROR.lock().unwrap_or_else(|p| p.into_inner()) = e.to_string();
}

fn text<'a>(p: *const u8, len: usize) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    // SAFETY: Java passes `len` bytes it owns for the call.
    std::str::from_utf8(unsafe { std::slice::from_raw_parts(p, len) }).ok()
}

/// `facts.json`'s states: `[name, air, fluid, BlockState.toString(), nbt]`, of which only the fourth is kept.
#[derive(serde::Deserialize)]
struct Facts {
    states: Vec<(
        serde::de::IgnoredAny,
        serde::de::IgnoredAny,
        serde::de::IgnoredAny,
        String,
        serde::de::IgnoredAny,
    )>,
}

fn data(resources: &Path) -> Result<Data, String> {
    let mut all = DATA
        .get_or_init(Default::default)
        .lock()
        .map_err(|e| e.to_string())?;
    if let Some(d) = all.get(resources) {
        return Ok(d.clone());
    }
    let read = |what: &str| {
        std::fs::File::open(resources.join("facts.json")).map_err(|e| format!("{what}: {e}"))
    };
    let facts: Facts = serde_json::from_reader(std::io::BufReader::new(read("facts.json")?))
        .map_err(|e| format!("facts.json: {e}"))?;
    let blocks: &'static BlockRegistry = Box::leak(Box::new(
        BlockRegistry::from_facts_json(read("facts.json")?)
            .map_err(|e| format!("facts.json: {e}"))?,
    ));
    let generator = GeneratorData::load(&resources.join("worldgen"), blocks)
        .map_err(|e| format!("worldgen data: {e}"))?;
    let d = Data {
        names: Arc::new(facts.states.into_iter().map(|s| s.3).collect()),
        generator,
    };
    all.insert(resources.to_owned(), d.clone());
    Ok(d)
}

/// The generator of `dimension` (`minecraft:overworld`, ...) in a world of `preset` (`minecraft:normal`, ...) and
/// `seed`, from the prepared resources at `resources`; null on error ([`hz_gen_error`]).
#[unsafe(no_mangle)]
pub extern "C" fn hz_gen_open(
    resources: *const u8,
    resources_len: usize,
    preset: *const u8,
    preset_len: usize,
    dimension: *const u8,
    dimension_len: usize,
    seed: i64,
) -> *mut Gen {
    let (Some(resources), Some(preset), Some(dimension)) = (
        text(resources, resources_len),
        text(preset, preset_len),
        text(dimension, dimension_len),
    ) else {
        fail("bad arguments");
        return std::ptr::null_mut();
    };
    let made = data(Path::new(resources)).and_then(|d| {
        OverworldGenerator::for_world(d.generator, seed, preset, dimension, false)
            .map_err(|e| e.to_string())
    });
    match made {
        Ok(generator) => {
            let (min_y, height) = generator.level();
            Box::into_raw(Box::new(Gen {
                generator,
                min_y,
                height,
            }))
        }
        Err(e) => {
            fail(e);
            std::ptr::null_mut()
        }
    }
}

/// The number of block states the resources know; their names come from [`hz_gen_state_name`].
#[unsafe(no_mangle)]
pub extern "C" fn hz_gen_state_count(resources: *const u8, resources_len: usize) -> i32 {
    match text(resources, resources_len)
        .ok_or("bad path".to_owned())
        .and_then(|r| data(Path::new(r)))
    {
        Ok(d) => d.names.len() as i32,
        Err(e) => {
            fail(e);
            -1
        }
    }
}

/// Loads the resources' data ahead of the first world (the agent calls it at start, on a thread of its own): 0,
/// or -1 ([`hz_gen_error`]).
#[unsafe(no_mangle)]
pub extern "C" fn hz_gen_preload(resources: *const u8, resources_len: usize) -> i32 {
    match text(resources, resources_len)
        .ok_or("bad path".to_owned())
        .and_then(|r| data(Path::new(r)))
    {
        Ok(_) => 0,
        Err(e) => {
            fail(e);
            -1
        }
    }
}

/// `lines` joined by newlines into `out`: the length, or minus the length needed when `cap` is too small.
///
/// # Safety
/// `out` holds `cap` writable bytes.
unsafe fn write_lines<S: AsRef<str>>(lines: &[S], out: *mut u8, cap: usize) -> i64 {
    let all = lines
        .iter()
        .map(AsRef::as_ref)
        .collect::<Vec<&str>>()
        .join("\n");
    if all.len() > cap {
        return -(all.len() as i64);
    }
    // SAFETY: as the caller promises.
    unsafe { std::ptr::copy_nonoverlapping(all.as_ptr(), out, all.len()) };
    all.len() as i64
}

/// Every state's name as Java's `BlockState.toString()` writes it, one per line in id order, into `out`: the
/// length, or minus the length needed when `cap` is too small, or `i64::MIN` on error.
///
/// # Safety
/// `resources` holds `resources_len` bytes; `out` holds `cap` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_gen_state_names(
    resources: *const u8,
    resources_len: usize,
    out: *mut u8,
    cap: usize,
) -> i64 {
    match text(resources, resources_len)
        .ok_or("bad path".to_owned())
        .and_then(|r| data(Path::new(r)))
    {
        // SAFETY: as the caller promises.
        Ok(d) => unsafe { write_lines(&d.names, out, cap) },
        Err(e) => {
            fail(e);
            i64::MIN
        }
    }
}

/// The generator's biome names (`minecraft:plains`, ...), one per line in the order of the ids
/// [`hz_gen_terrain`] gives: the length, or minus the length needed.
///
/// # Safety
/// `gen` is from [`hz_gen_open`]; `out` holds `cap` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_gen_biome_names(
    generator: *const Gen,
    out: *mut u8,
    cap: usize,
) -> i64 {
    // SAFETY: as the caller promises.
    let g = unsafe { &*generator };
    unsafe { write_lines(g.generator.data().biome_names(), out, cap) }
}

/// The generator's levels: `out[0]` its lowest block y, `out[1]` its height.
///
/// # Safety
/// `gen` is from [`hz_gen_open`]; `out` holds two ints.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_gen_level(generator: *const Gen, out: *mut i32) {
    // SAFETY: as the caller promises.
    let g = unsafe { &*generator };
    unsafe {
        *out = g.min_y;
        *out.add(1) = g.height;
    }
}

/// Chunk `x`, `z`'s terrain: `states` gets its block states section by section from the bottom, each in
/// `(y << 8) | (z << 4) | x` order (`height * 256` ints); `post` its postprocessing offsets (vanilla's packing),
/// `post_counts` how many in each section (`height / 16` ints), and `biomes` its quart biomes (`height / 16 * 64`
/// shorts, each section's in vanilla's `(y << 4) | (z << 2) | x` order, ids into [`hz_gen_biome_names`]). The
/// number of offsets, or -1 (also when they pass `post_cap`).
///
/// # Safety
/// `gen` is from [`hz_gen_open`]; the buffers are as large as said.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_gen_terrain(
    generator: *const Gen,
    x: i32,
    z: i32,
    states: *mut u32,
    post: *mut u16,
    post_cap: usize,
    post_counts: *mut i32,
    biomes: *mut u16,
) -> i32 {
    let start = Instant::now();
    // SAFETY: as the caller promises.
    let g = unsafe { &*generator };
    let made = WORKERS.with(|w| {
        let mut workers = w.borrow_mut();
        let worker = workers
            .entry(generator as usize)
            .or_insert_with(|| g.generator.worker());
        worker.generate_proto(ChunkPos::new(x, z))
    });
    let chunk = match made {
        Ok(c) => c,
        Err(e) => {
            fail(e);
            return -1;
        }
    };
    let sections = (g.height / 16) as usize;
    // SAFETY: as the caller promises.
    let states = unsafe { std::slice::from_raw_parts_mut(states, sections * 4096) };
    let counts = unsafe { std::slice::from_raw_parts_mut(post_counts, sections) };
    let post = unsafe { std::slice::from_raw_parts_mut(post, post_cap) };
    let biomes = unsafe { std::slice::from_raw_parts_mut(biomes, sections * 64) };
    for s in 0..sections {
        for q in 0..64 {
            let (x, y, z) = (
                (q & 3) as i32 * 4,
                (q >> 4) as i32 * 4,
                ((q >> 2) & 3) as i32 * 4,
            );
            biomes[s * 64 + q] = chunk.biome_id_at(x, g.min_y + 16 * s as i32 + y, z).0 as u16;
        }
    }
    // facts.json lists minecraft:air first, as vanilla numbers it.
    let air = 0u32;
    let mut section = [0u32; 4096];
    for s in 0..sections {
        let y = g.min_y + 16 * s as i32;
        let out = &mut states[s * 4096..(s + 1) * 4096];
        if chunk.terrain.section_states(y, &mut section) {
            out.copy_from_slice(&section);
        } else {
            out.fill(air);
        }
    }
    let lists = chunk.terrain.postprocessing();
    let mut n = 0;
    for s in 0..sections {
        let list = lists.get(s).map(Vec::as_slice).unwrap_or(&[]);
        if n + list.len() > post_cap {
            fail("more postprocessing offsets than the buffer holds");
            return -1;
        }
        post[n..n + list.len()].copy_from_slice(list);
        counts[s] = list.len() as i32;
        n += list.len();
    }
    NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
    CHUNKS.fetch_add(1, Ordering::Relaxed);
    n as i32
}

/// The last error's text into `out`; its length.
///
/// # Safety
/// `out` holds `cap` writable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_gen_error(out: *mut u8, cap: usize) -> i32 {
    let e = ERROR.lock().unwrap_or_else(|p| p.into_inner());
    let n = e.len().min(cap);
    // SAFETY: Java passes `cap` writable bytes.
    unsafe { std::ptr::copy_nonoverlapping(e.as_ptr(), out, n) };
    n as i32
}

/// Time in [`hz_gen_terrain`] so far (ns), and `out` gets the chunks made.
///
/// # Safety
/// `out` holds a long.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_gen_nanos(out: *mut u64) -> u64 {
    // SAFETY: as the caller promises.
    unsafe { *out = CHUNKS.load(Ordering::Relaxed) };
    NANOS.load(Ordering::Relaxed)
}
