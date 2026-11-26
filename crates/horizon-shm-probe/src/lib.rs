//! P2 of `docs/design/java-mods-everything.md`: block storage and light that Rust owns, which vanilla's own
//! Java code reads and writes in place.
//!
//! The JVM reaches these functions through FFM (`java.lang.foreign`). Rust allocates a storage's words;
//! Java's `RustBitStorage` (`mods/horizon/src/shared-memory-probe/`) is a `SimpleBitStorage` whose every read
//! and write goes to those words, so the game's `PalettedContainer` runs on memory Rust holds. Rust then reads
//! the same memory with horizon-core's own `PackedStorage` and `PalettedContainer`, and writes to it for Java
//! to see: one copy of the data, two languages.
//!
//! Light works the same way (`RustDataLayer`), and [`level::JavaLevel`] is Horizon's own world interface over
//! the live world of the real game.
//!
//! Driven by `tools/shared_memory_probe.py`. Nothing here is linked into the game.

pub mod level;
pub mod light;

use horizon_core::paletted::{PalettedContainer, Strategy};
use horizon_core::position::BlockPos;
use horizon_core::storage::PackedStorage;
use horizon_world::level::LevelAccess;
use horizon_world::registry::BlockStateId;
use level::{JavaLevel, chunk_hashes, fnv};
use std::slice;

/// Zeroed words for a storage of `words` longs, owned by Rust until [`hz_free`].
#[unsafe(no_mangle)]
pub extern "C" fn hz_alloc(words: u32) -> *mut u64 {
    let block: Box<[u64]> = vec![0u64; words as usize].into_boxed_slice();
    Box::into_raw(block).cast::<u64>()
}

/// Frees words from [`hz_alloc`].
///
/// # Safety
/// `ptr` and `words` are exactly what one `hz_alloc` call returned and took, not freed before.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_free(ptr: *mut u64, words: u32) {
    if !ptr.is_null() {
        // SAFETY: the caller hands back an allocation of `words` longs from `hz_alloc`.
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, words as usize)) });
    }
}

/// # Safety
/// `ptr` points at `words` initialised longs that stay valid for the call.
unsafe fn view<'a>(ptr: *const u64, words: u32) -> &'a [u64] {
    if words == 0 {
        return &[];
    }
    // SAFETY: as the caller promises.
    unsafe { slice::from_raw_parts(ptr, words as usize) }
}

/// The hash of every value in the storage, read in place by horizon-core's `PackedStorage` over these words. 0
/// when the shape is not one `PackedStorage` accepts.
///
/// # Safety
/// `ptr` points at `words` initialised longs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_storage_hash(ptr: *const u64, words: u32, bits: u32, size: u32) -> u64 {
    // SAFETY: as the caller promises.
    let data = unsafe { view(ptr, words) };
    match PackedStorage::from_words(bits as u8, size as usize, data) {
        Ok(storage) => fnv(storage.unpack()),
        Err(_) => 0,
    }
}

/// How many of the 4,096 cells horizon-core's own `PalettedContainer`, built from these words and this palette
/// of block state ids, reads differently from `expected`; -1 when it refuses the words. (That container keeps
/// words of its own, so it copies them; [`JavaLevel`] reads a section in place.)
///
/// # Safety
/// `ptr` points at `words` longs, `palette` at `palette_len` ids and `expected` at 4,096 ids.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_container_mismatches(
    ptr: *const u64,
    words: u32,
    global_bits: u32,
    palette: *const u32,
    palette_len: u32,
    expected: *const u32,
) -> i32 {
    // SAFETY: as the caller promises.
    let (data, palette, expected) = unsafe {
        (
            view(ptr, words),
            slice::from_raw_parts(palette, palette_len as usize),
            slice::from_raw_parts(expected, 4096),
        )
    };
    let strategy = Strategy::blocks(global_bits as u8);
    let words = (!data.is_empty()).then_some(data);
    match PalettedContainer::from_packed(strategy, palette.to_vec(), words) {
        Ok(container) => (0..4096)
            .filter(|&i| container.get(i) != expected[i])
            .count() as i32,
        Err(_) => -1,
    }
}

/// Reads one value straight from the words, in place (`SimpleBitStorage`'s layout: values never span longs).
///
/// # Safety
/// `ptr` points at enough longs for `index` at `bits` bits.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_get(ptr: *const u64, bits: u32, index: u32) -> u32 {
    let per = 64 / bits as usize;
    let (word, shift) = (index as usize / per, (index as usize % per) * bits as usize);
    // SAFETY: as the caller promises.
    let w = unsafe { *ptr.add(word) };
    ((w >> shift) & ((1u64 << bits) - 1)) as u32
}

/// Writes one value straight into the words, in place, for Java to read back.
///
/// # Safety
/// `ptr` points at enough longs for `index` at `bits` bits, and nothing else writes them meanwhile.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_set(ptr: *mut u64, bits: u32, index: u32, value: u32) {
    let per = 64 / bits as usize;
    let (word, shift) = (index as usize / per, (index as usize % per) * bits as usize);
    let mask = (1u64 << bits) - 1;
    // SAFETY: as the caller promises.
    unsafe {
        let w = ptr.add(word);
        *w = (*w & !(mask << shift)) | ((u64::from(value) & mask) << shift);
    }
}

/// The hash of a light layer's 4,096 levels, read in place from its 2,048 bytes (vanilla's `DataLayer` layout).
///
/// # Safety
/// `bytes` points at 2,048 bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_light_hash(bytes: *const u8) -> u64 {
    // SAFETY: as the caller promises.
    let bytes = unsafe { slice::from_raw_parts(bytes, 2048) };
    fnv((0..4096).map(|i| u32::from((bytes[i >> 1] >> (4 * (i & 1))) & 15)))
}

/// A new, empty view of the live Java world, owned by the caller until [`hz_level_free`].
#[unsafe(no_mangle)]
pub extern "C" fn hz_level_new() -> *mut JavaLevel {
    Box::into_raw(Box::new(JavaLevel::new()))
}

/// # Safety
/// `level` came from [`hz_level_new`] and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_level_free(level: *mut JavaLevel) {
    if !level.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(level) });
    }
}

/// Registers a section's block storage: `words` in place (null for a one-state section), and a copy of the
/// `palette_len` ids its palette stands for (`global` 1: the stored ids are the states).
///
/// # Safety
/// `level` is live; `words` is null or points at `count` longs valid while the level is used; `palette` points
/// at `palette_len` ids.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_level_blocks(
    level: *mut JavaLevel,
    x: i32,
    y: i32,
    z: i32,
    words: *mut u64,
    count: u32,
    bits: u32,
    palette: *const u32,
    palette_len: u32,
    global: u32,
) {
    // SAFETY: as the caller promises.
    unsafe {
        let ids =
            (global == 0).then(|| slice::from_raw_parts(palette, palette_len as usize).to_vec());
        (*level).put_blocks((x, y, z), words, count as usize, bits as u8, ids);
    }
}

/// Registers a light layer (`sky` 1: sky light): its 2,048 bytes in place, or null while it holds only
/// `default`.
///
/// # Safety
/// `level` is live; `bytes` is null or points at 2,048 bytes valid while the level is used.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_level_light(
    level: *mut JavaLevel,
    x: i32,
    y: i32,
    z: i32,
    sky: u32,
    bytes: *const u8,
    default: u32,
) {
    // SAFETY: as the caller promises.
    unsafe { (*level).put_light((x, y, z), sky != 0, bytes, default as u8) };
}

/// Registers a chunk's heightmap of `kind` (`Heightmap.Types` ordinal): its words in place.
///
/// # Safety
/// `level` is live; `words` points at `count` longs valid while the level is used.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_level_heights(
    level: *mut JavaLevel,
    x: i32,
    z: i32,
    kind: u32,
    words: *const u64,
    count: u32,
    bits: u32,
) {
    // SAFETY: as the caller promises.
    unsafe { (*level).put_heights((x, z), kind as u8, words, count as usize, bits as u8) };
}

/// For each of `n` chunks (`chunks`: x, z pairs), [`chunk_hashes`] into `out` (4 per chunk), read on `threads`
/// Rust threads at once.
///
/// # Safety
/// `level` is live and nothing writes the registered memory during the call; `chunks` points at `2 * n` ints and
/// `out` at `4 * n` longs.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn hz_level_hash(
    level: *const JavaLevel,
    chunks: *const i32,
    n: u32,
    min_y: i32,
    max_y: i32,
    min_light: i32,
    max_light: i32,
    threads: u32,
    out: *mut u64,
) {
    // SAFETY: as the caller promises.
    let (level, chunks, out) = unsafe {
        (
            &*level,
            slice::from_raw_parts(chunks, 2 * n as usize),
            slice::from_raw_parts_mut(out, 4 * n as usize),
        )
    };
    let per = (n as usize).div_ceil(threads.max(1) as usize).max(1);
    std::thread::scope(|scope| {
        for (pairs, hashes) in chunks.chunks(2 * per).zip(out.chunks_mut(4 * per)) {
            scope.spawn(move || {
                for (pair, slot) in pairs.chunks(2).zip(hashes.chunks_mut(4)) {
                    let h = chunk_hashes(
                        level,
                        (pair[0], pair[1]),
                        min_y,
                        max_y,
                        (min_light, max_light),
                    );
                    slot.copy_from_slice(&h);
                }
            });
        }
    });
}

/// The state at a position as Horizon's `LevelAccess::block_state` reads it; -1 outside the registered sections.
///
/// # Safety
/// `level` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_level_state(level: *const JavaLevel, x: i32, y: i32, z: i32) -> i64 {
    // SAFETY: as the caller promises.
    let level = unsafe { &*level };
    let pos = BlockPos::new(x, y, z);
    if level.is_loaded(pos) {
        i64::from(level.block_state(pos).0)
    } else {
        -1
    }
}

/// Writes a state in place through Horizon's `LevelAccess::set_raw`; returns the state it replaced, or -1 when
/// the section's palette does not hold `state` (growing it is the game's work).
///
/// # Safety
/// `level` is live and no Java thread writes that section meanwhile.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_level_set(
    level: *mut JavaLevel,
    x: i32,
    y: i32,
    z: i32,
    state: u32,
) -> i64 {
    // SAFETY: as the caller promises.
    let level = unsafe { &mut *level };
    level
        .set_raw(BlockPos::new(x, y, z), BlockStateId(state))
        .map_or(-1, |old| i64::from(old.0))
}

/// How many of the game's block states (`states`: lines of `id<TAB>name<TAB>k=v,...` in UTF-8, properties sorted
/// by name) Horizon's registry, loaded from `facts.json` in `root`, names differently, counting a difference in
/// the number of states too: 0 when Horizon's ids are the game's ids. -1 when the registry does not load, -2 when
/// the lines do not parse.
///
/// # Safety
/// `root` points at `root_len` bytes of a UTF-8 path and `states` at `states_len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_registry_check(
    root: *const u8,
    root_len: u32,
    states: *const u8,
    states_len: u32,
) -> i64 {
    // SAFETY: as the caller promises.
    let (root, states) = unsafe {
        (
            slice::from_raw_parts(root, root_len as usize),
            slice::from_raw_parts(states, states_len as usize),
        )
    };
    let (Ok(root), Ok(states)) = (std::str::from_utf8(root), std::str::from_utf8(states)) else {
        return -2;
    };
    let Ok(registry) = horizon_world::level::data::load_registry(std::path::Path::new(root)) else {
        return -1;
    };
    let mut wrong: i64 = 0;
    let mut seen: i64 = 0;
    for line in states.lines() {
        let mut parts = line.split('\t');
        let (Some(id), Some(name)) = (parts.next(), parts.next()) else {
            return -2;
        };
        let Ok(id) = id.parse::<u32>() else {
            return -2;
        };
        let properties = parts.next().unwrap_or("");
        seen += 1;
        let ours = registry.state(BlockStateId(id)).map(|state| {
            let mut pairs: Vec<String> = state
                .properties
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect();
            pairs.sort();
            (state.name.clone(), pairs.join(","))
        });
        if ours.as_ref().map(|(n, p)| (n.as_str(), p.as_str())) != Some((name, properties)) {
            wrong += 1;
        }
    }
    wrong + (seen - registry.len() as i64).abs()
}

/// Registers light class `id` (see [`light::Class`]): `faces` points at 6 face ids, by `Direction` ordinal.
///
/// # Safety
/// `faces` points at 6 `u32`s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_light_class(
    id: u32,
    emission: u32,
    dampening: u32,
    empty_shape: u32,
    faces: *const u32,
) {
    // SAFETY: as the caller promises.
    let f = unsafe { slice::from_raw_parts(faces, 6) };
    light::register(
        id,
        light::Class {
            emission: emission.min(15) as u8,
            dampening: dampening.min(15) as u8,
            empty_shape: empty_shape != 0,
            faces: [f[0], f[1], f[2], f[3], f[4], f[5]],
        },
    );
}

/// One `runLightUpdates`' propagation (see [`light`]): the decrease and increase queues as Java held them (pairs of
/// longs: node, entry), sky or block light, `SkyDataLayerStorageMap.currentLowestY`, Java's air and bedrock classes.
/// Returns the entries dequeued; `affected` gets a list of the sections written in and around (`*affected_len`
/// longs, freed with [`hz_light_free`]).
///
/// # Safety
/// `calls` points at live upcalls; `decrease`/`increase` at `*_len` longs; `affected` and `affected_len` are
/// writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_light_run(
    calls: *const light::Callbacks,
    sky: u32,
    lowest_y: i32,
    air: u32,
    bedrock: u32,
    decrease: *const i64,
    decrease_len: u32,
    increase: *const i64,
    increase_len: u32,
    affected: *mut *mut i64,
    affected_len: *mut u32,
) -> i32 {
    // SAFETY: as the caller promises.
    let (calls, dec, inc) = unsafe {
        (
            &*calls,
            longs(decrease, decrease_len).to_vec(),
            longs(increase, increase_len).to_vec(),
        )
    };
    let start = std::time::Instant::now();
    let mut run = light::Run::new(sky != 0, lowest_y, air, bedrock, calls, dec, inc);
    let count = run.propagate();
    LIGHT_NANOS.fetch_add(
        start.elapsed().as_nanos() as u64,
        std::sync::atomic::Ordering::Relaxed,
    );
    let list: Box<[i64]> = run.affected().into();
    // SAFETY: as the caller promises.
    unsafe {
        *affected_len = list.len() as u32;
        *affected = Box::into_raw(list).cast::<i64>();
    }
    count
}

/// Time spent in [`hz_light_run`] (upcalls into Java included), for the probe's report.
static LIGHT_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Nanoseconds spent in [`hz_light_run`] so far.
#[unsafe(no_mangle)]
pub extern "C" fn hz_light_nanos() -> i64 {
    LIGHT_NANOS.load(std::sync::atomic::Ordering::Relaxed) as i64
}

/// Frees a list from [`hz_light_run`].
///
/// # Safety
/// `ptr` and `len` are what one `hz_light_run` handed out, not freed before.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_light_free(ptr: *mut i64, len: u32) {
    if !ptr.is_null() {
        // SAFETY: as the caller promises.
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len as usize)) });
    }
}

/// # Safety
/// `ptr` points at `len` longs, or `len` is 0.
unsafe fn longs<'a>(ptr: *const i64, len: u32) -> &'a [i64] {
    if len == 0 {
        &[]
    } else {
        // SAFETY: as the caller promises.
        unsafe { slice::from_raw_parts(ptr, len as usize) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_place_reads_and_writes_agree_with_horizon_core() {
        let (bits, size) = (5u32, 4096u32);
        let words = size.div_ceil(64 / bits);
        let ptr = hz_alloc(words);
        for i in 0..size {
            // SAFETY: the allocation holds `words` longs for `size` values at `bits` bits.
            unsafe { hz_set(ptr, bits, i, (i * 7) % 32) };
        }
        // SAFETY: as above.
        unsafe {
            assert_eq!(hz_get(ptr, bits, 4095), (4095 * 7) % 32);
            assert_eq!(
                hz_storage_hash(ptr, words, bits, size),
                fnv((0..size).map(|i| (i * 7) % 32))
            );
            hz_free(ptr, words);
        }
    }

    /// The level reads blocks, light and heights in place, and writes a state its palette holds.
    #[test]
    fn java_level_reads_and_writes_in_place() {
        let mut words = vec![0u64; 256];
        PackedStorage::from_words(4, 4096, words.as_mut_slice())
            .unwrap()
            .write(0x123, 2);
        let mut light = vec![0u8; 2048];
        light[0x123 >> 1] = 0x70;
        let mut heights = vec![0u64; 256usize.div_ceil(64 / 9)];
        PackedStorage::from_words(9, 256, heights.as_mut_slice())
            .unwrap()
            .write(5, 300);
        let level = hz_level_new();
        let palette = [10u32, 11, 12];
        // SAFETY: the memory outlives the level, which nothing else touches.
        unsafe {
            hz_level_blocks(
                level,
                1,
                0,
                2,
                words.as_mut_ptr(),
                256,
                4,
                palette.as_ptr(),
                3,
                0,
            );
            hz_level_light(level, 1, 0, 2, 0, light.as_ptr(), 0);
            hz_level_light(level, 1, 0, 2, 1, std::ptr::null(), 15);
            hz_level_heights(level, 1, 2, 4, heights.as_ptr(), heights.len() as u32, 9);
            // Index 0x123 of section (1, 0, 2): x 3, z 2, y 1.
            let (x, y, z) = (16 + 3, 1, 32 + 2);
            assert_eq!(hz_level_state(level, x, y, z), 12);
            assert_eq!(hz_level_state(level, x + 1, y, z), 10);
            assert_eq!(hz_level_state(level, 0, 0, 0), -1);
            let l = &*level;
            assert_eq!(l.block_light(BlockPos::new(x, y, z)), 7);
            assert_eq!(l.sky_light(BlockPos::new(x, y, z)), 15);
            assert_eq!(l.heights((1, 2), 4).unwrap()[5], 300);
            assert_eq!(hz_level_set(level, x + 1, y, z, 11), 10);
            assert_eq!(hz_level_set(level, x + 1, y, z, 99), -1);
            assert_eq!(hz_level_state(level, x + 1, y, z), 11);
            let mut out = [0u64; 4];
            hz_level_hash(level, [1, 2].as_ptr(), 1, 0, 16, 0, 1, 2, out.as_mut_ptr());
            assert_eq!(out, chunk_hashes(&*level, (1, 2), 0, 16, (0, 1)));
            hz_level_free(level);
        }
        let read = PackedStorage::from_words(4, 4096, words.as_slice()).unwrap();
        assert_eq!(read.read(0x124), 1);
    }
}
