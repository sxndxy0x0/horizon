//! Horizon's world interface ([`LevelAccess`]) over the live world of the real Java game: block states,
//! light and heightmaps read in place from the memory the game keeps them in, which Rust owns.
//!
//! Java registers each section of each loaded chunk: the words of its block storage (borrowed, never copied),
//! the ids its palette stands for (a copy: at most 2^bits ids, which Java may grow at any time), and the bytes of
//! its two light layers (borrowed). Horizon's code then asks for blocks by position as it asks any game.
//!
//! # The rule for threads
//!
//! Rust reads or writes a section only while no Java thread writes it: the same rule vanilla's own
//! `PalettedContainer` enforces with its `ThreadingDetector`. In the live-world probe Java's server thread waits
//! in the call while Rust reads, and the world is idle (chunks generated, light settled); Rust then reads the
//! chunks on several threads of its own, which only read.

use horizon_core::paletted::ceil_log2;
use horizon_core::position::BlockPos;
use horizon_core::random::LegacyRandom;
use horizon_core::storage::PackedStorage;
use horizon_world::level::{BlockEntityNeighbors, LevelAccess};
use horizon_world::registry::BlockStateId;
use std::collections::HashMap;
use std::slice;

/// A section's block storage: the game's words in place, and what its palette stands for.
struct Blocks {
    /// Null for a section that holds one state (vanilla keeps no words for it).
    words: *mut u64,
    count: usize,
    bits: u8,
    /// `None` for the global palette, whose stored ids are the states themselves.
    palette: Option<Vec<u32>>,
}

/// A light layer: the game's 2,048 bytes in place, or only its default level while it has none.
#[derive(Clone, Copy)]
struct Light {
    bytes: *const u8,
    default: u8,
}

/// Each heightmap of a chunk: its 256 heights' words in place.
struct Heights {
    words: *const u64,
    count: usize,
    bits: u8,
}

/// The live Java world, as Horizon sees a level.
pub struct JavaLevel {
    blocks: HashMap<(i32, i32, i32), Blocks>,
    /// Keyed by section and layer (0 block light, 1 sky light). A missing layer is absent in the game too.
    light: HashMap<(i32, i32, i32, u8), Light>,
    heights: HashMap<(i32, i32, u8), Heights>,
    random: LegacyRandom,
}

// SAFETY: the pointers are the game's memory, which Rust owns and Java keeps alive while it is registered. Under
// the rule above (no Java write while Rust reads) several Rust threads may read it at once.
unsafe impl Send for JavaLevel {}
// SAFETY: as above; `&JavaLevel` only reads.
unsafe impl Sync for JavaLevel {}

impl Default for JavaLevel {
    fn default() -> Self {
        Self::new()
    }
}

impl JavaLevel {
    pub fn new() -> Self {
        Self {
            blocks: HashMap::new(),
            light: HashMap::new(),
            heights: HashMap::new(),
            random: LegacyRandom::new(0),
        }
    }

    /// Registers a section's block storage.
    ///
    /// # Safety
    /// `words` is null or points at `count` longs of the game's storage at `bits` bits per entry, valid while
    /// registered.
    pub unsafe fn put_blocks(
        &mut self,
        section: (i32, i32, i32),
        words: *mut u64,
        count: usize,
        bits: u8,
        palette: Option<Vec<u32>>,
    ) {
        self.blocks.insert(
            section,
            Blocks {
                words,
                count,
                bits,
                palette,
            },
        );
    }

    /// Registers a light layer (`sky` false: block light).
    ///
    /// # Safety
    /// `bytes` is null or points at the layer's 2,048 bytes, valid while registered.
    pub unsafe fn put_light(
        &mut self,
        section: (i32, i32, i32),
        sky: bool,
        bytes: *const u8,
        default: u8,
    ) {
        let (x, y, z) = section;
        self.light
            .insert((x, y, z, u8::from(sky)), Light { bytes, default });
    }

    /// Registers a chunk's heightmap of `kind` (vanilla's `Heightmap.Types` ordinal).
    ///
    /// # Safety
    /// `words` points at `count` longs of the heightmap's storage at `bits` bits, valid while registered.
    pub unsafe fn put_heights(
        &mut self,
        chunk: (i32, i32),
        kind: u8,
        words: *const u64,
        count: usize,
        bits: u8,
    ) {
        self.heights
            .insert((chunk.0, chunk.1, kind), Heights { words, count, bits });
    }

    fn section(pos: BlockPos) -> ((i32, i32, i32), usize) {
        let key = (pos.x >> 4, pos.y >> 4, pos.z >> 4);
        let index = ((pos.y & 15) << 8 | (pos.z & 15) << 4 | (pos.x & 15)) as usize;
        (key, index)
    }

    /// The section's storage over the game's words, read in place.
    fn storage(blocks: &Blocks) -> Option<PackedStorage<&[u64]>> {
        if blocks.words.is_null() {
            return None;
        }
        // SAFETY: `put_blocks`' caller promised `count` longs, valid while registered.
        let words = unsafe { slice::from_raw_parts(blocks.words, blocks.count) };
        PackedStorage::from_words(blocks.bits, 4096, words).ok()
    }

    /// The state at a position of a registered section, `None` elsewhere.
    pub fn state(&self, pos: BlockPos) -> Option<u32> {
        let (key, index) = Self::section(pos);
        let blocks = self.blocks.get(&key)?;
        let id = Self::storage(blocks).map_or(0, |s| s.read(index));
        Some(match &blocks.palette {
            Some(palette) => *palette.get(id as usize)?,
            None => id,
        })
    }

    /// The level of a light layer at a position: `None` where the game has no layer.
    pub fn light_level(&self, pos: BlockPos, sky: bool) -> Option<u8> {
        let (key, index) = Self::section(pos);
        let light = self.light.get(&(key.0, key.1, key.2, u8::from(sky)))?;
        if light.bytes.is_null() {
            return Some(light.default);
        }
        // SAFETY: `put_light`'s caller promised the layer's 2,048 bytes; `index / 2` is below 2,048.
        let byte = unsafe { *light.bytes.add(index >> 1) };
        Some((byte >> (4 * (index & 1))) & 15)
    }

    /// The 256 heights of a chunk's heightmap, as the game stores them (`y - minY`), read in place.
    pub fn heights(&self, chunk: (i32, i32), kind: u8) -> Option<Vec<u32>> {
        let h = self.heights.get(&(chunk.0, chunk.1, kind))?;
        // SAFETY: `put_heights`' caller promised `count` longs, valid while registered.
        let words = unsafe { slice::from_raw_parts(h.words, h.count) };
        PackedStorage::from_words(h.bits, 256, words)
            .ok()
            .map(|s| s.unpack())
    }

    /// Writes a state in place, as [`LevelAccess::set_raw`] does, when the section's palette already holds it.
    /// Growing a palette is the game's own work: Rust never reallocates the game's storage.
    fn write(&mut self, pos: BlockPos, state: u32) -> Option<u32> {
        let (key, index) = Self::section(pos);
        let blocks = self.blocks.get(&key)?;
        if blocks.words.is_null() {
            return None;
        }
        let id = match &blocks.palette {
            Some(palette) => palette.iter().position(|&s| s == state)? as u32,
            None if ceil_log2(state as usize + 1) <= blocks.bits => state,
            None => return None,
        };
        let old = self.state(pos)?;
        // SAFETY: `put_blocks`' caller promised `count` longs; under the rule for threads nothing else writes them.
        let words = unsafe { slice::from_raw_parts_mut(blocks.words, blocks.count) };
        PackedStorage::from_words(blocks.bits, 4096, words)
            .ok()?
            .write(index, id);
        Some(old)
    }
}

impl BlockEntityNeighbors for JavaLevel {}

impl LevelAccess for JavaLevel {
    fn block_state(&self, pos: BlockPos) -> BlockStateId {
        BlockStateId(self.state(pos).unwrap_or(0))
    }
    fn set_raw(&mut self, pos: BlockPos, state: BlockStateId) -> Option<BlockStateId> {
        self.write(pos, state.0).map(BlockStateId)
    }
    fn is_loaded(&self, pos: BlockPos) -> bool {
        self.blocks.contains_key(&Self::section(pos).0)
    }
    fn random(&mut self) -> &mut LegacyRandom {
        &mut self.random
    }
    fn game_time(&self) -> i64 {
        0
    }
    fn block_light(&self, pos: BlockPos) -> i32 {
        i32::from(self.light_level(pos, false).unwrap_or(0))
    }
    fn sky_light(&self, pos: BlockPos) -> i32 {
        i32::from(self.light_level(pos, true).unwrap_or(0))
    }
}

/// FNV-1a over values, as Java hashes the same values.
pub fn fnv(values: impl IntoIterator<Item = u32>) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for v in values {
        for b in v.to_le_bytes() {
            h ^= u64::from(b);
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
    }
    h
}

/// What Java hashes for a chunk: every block state (y, then z, then x, from `min_y` up) as Horizon's
/// [`LevelAccess`] reads it; every light level of each layer over the light sections (16 for an absent layer);
/// and every height of each of the six heightmaps (`u32::MAX` for an absent one).
pub fn chunk_hashes(
    level: &JavaLevel,
    chunk: (i32, i32),
    min_y: i32,
    max_y: i32,
    light: (i32, i32),
) -> [u64; 4] {
    let (cx, cz) = chunk;
    let mut column = Vec::with_capacity(4096);
    let mut blocks = Vec::new();
    for y in min_y..max_y {
        for z in 0..16 {
            for x in 0..16 {
                let pos = BlockPos::new(cx * 16 + x, y, cz * 16 + z);
                blocks.push(level.block_state(pos).0);
            }
        }
    }
    let mut lights = [0u64; 2];
    for (sky, hash) in lights.iter_mut().enumerate() {
        column.clear();
        for sy in light.0..light.1 {
            for i in 0..4096 {
                let pos = BlockPos::new(
                    cx * 16 + (i & 15),
                    sy * 16 + (i >> 8),
                    cz * 16 + (i >> 4 & 15),
                );
                column.push(level.light_level(pos, sky == 1).map_or(16, u32::from));
            }
        }
        *hash = fnv(column.iter().copied());
    }
    let mut heights = Vec::new();
    for kind in 0..6 {
        match level.heights(chunk, kind) {
            Some(h) => heights.extend(h),
            None => heights.push(u32::MAX),
        }
    }
    [fnv(blocks), lights[0], lights[1], fnv(heights)]
}
