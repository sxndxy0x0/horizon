//! P4 of `docs/design/java-mods-everything.md`: vanilla's light propagation, run by Rust on the live game's
//! light data in place.
//!
//! A port of `LightEngine.propagateDecreases`/`propagateIncreases` with `BlockLightEngine`'s and
//! `SkyLightEngine`'s `propagateDecrease`/`propagateIncrease` (26.3), statement for statement: the same queues in
//! the same order, so every level it writes is the one vanilla writes. `runLightUpdates` keeps the rest in Java
//! (`checkNode`, the section maps' bookkeeping) and hands its queues here (`RustLight` in
//! `mods/horizon/src/shared-memory-probe/`).
//!
//! What the propagation reads and writes comes from Java, through calls back into it ([`Callbacks`]), each at most
//! once per section (or column, or pair of faces) per run:
//! - a section's light layer: its bytes in place (they live in Rust's memory: `RustDataLayer`); the first write
//!   to a section in a run goes through `LayerLightSectionStorage.setStoredLevel`'s copy-on-write, in Java;
//! - a section's blocks, as the light class of each (`RustLight`: emission, dampening, occlusion faces), kept up
//!   to date by the game's own `LevelChunkSection.setBlockState`;
//! - `lightOnInSection`, and `Shapes.faceShapeOccludes` for two faces.
//!
//! The sections the run wrote light into, with their neighbours (`SectionPos.aroundAndAtBlockPos`), go back to
//! Java's `sectionsAffectedByLightUpdates`.

use std::collections::{HashMap, HashSet};
use std::hash::{BuildHasherDefault, Hasher};
use std::sync::{Arc, RwLock};

// ---------------------------------------------------------------------------------------------------------------
// Positions, as vanilla packs them (`BlockPos.asLong`, `SectionPos.asLong`).

const HORIZONTAL: u32 = 26;
const Y_BITS: u32 = 64 - 2 * HORIZONTAL;
const X_OFFSET: u32 = Y_BITS + HORIZONTAL;
const Z_OFFSET: u32 = Y_BITS;

fn block_x(node: i64) -> i32 {
    (node << (64 - X_OFFSET - HORIZONTAL) >> (64 - HORIZONTAL)) as i32
}

fn block_y(node: i64) -> i32 {
    (node << (64 - Y_BITS) >> (64 - Y_BITS)) as i32
}

fn block_z(node: i64) -> i32 {
    (node << (64 - Z_OFFSET - HORIZONTAL) >> (64 - HORIZONTAL)) as i32
}

fn block_node(x: i32, y: i32, z: i32) -> i64 {
    let h = (1i64 << HORIZONTAL) - 1;
    let v = (1i64 << Y_BITS) - 1;
    (i64::from(x) & h) << X_OFFSET | (i64::from(y) & v) | (i64::from(z) & h) << Z_OFFSET
}

fn section_node(x: i32, y: i32, z: i32) -> i64 {
    (i64::from(x) & 4_194_303) << 42 | (i64::from(y) & 1_048_575) | (i64::from(z) & 4_194_303) << 20
}

#[cfg(test)]
fn section_x(node: i64) -> i32 {
    (node >> 42) as i32
}

#[cfg(test)]
fn section_y(node: i64) -> i32 {
    (node << 44 >> 44) as i32
}

#[cfg(test)]
fn section_z(node: i64) -> i32 {
    (node << 22 >> 42) as i32
}

/// `SectionPos.blockToSection`.
#[cfg(test)]
fn section_of(block: i64) -> i64 {
    section_node(
        block_x(block) >> 4,
        block_y(block) >> 4,
        block_z(block) >> 4,
    )
}

/// `Direction.values()`: DOWN, UP, NORTH, SOUTH, WEST, EAST.
const STEPS: [(i32, i32, i32); 6] = [
    (0, -1, 0),
    (0, 1, 0),
    (0, 0, -1),
    (0, 0, 1),
    (-1, 0, 0),
    (1, 0, 0),
];
#[cfg(test)]
const DOWN: usize = 0;
#[cfg(test)]
const UP: usize = 1;
const NORTH: usize = 2;
const SOUTH: usize = 3;
const WEST: usize = 4;
const EAST: usize = 5;

fn opposite(d: usize) -> usize {
    d ^ 1
}

/// `BlockPos.offset(long, Direction)`.
#[cfg(test)]
fn offset(node: i64, d: usize) -> i64 {
    let (dx, dy, dz) = STEPS[d];
    block_node(block_x(node) + dx, block_y(node) + dy, block_z(node) + dz)
}

// ---------------------------------------------------------------------------------------------------------------
// `LightEngine.QueueEntry`.

const DIRECTIONS: i64 = 1008;
const FROM_EMPTY_SHAPE: i64 = 1024;
const FROM_EMISSION: i64 = 2048;

fn with_level(entry: i64, level: i32) -> i64 {
    entry & -16 | (i64::from(level) & 15)
}

fn with_direction(entry: i64, d: usize) -> i64 {
    entry | 1 << (d + 4)
}

fn without_direction(entry: i64, d: usize) -> i64 {
    entry & !(1 << (d + 4))
}

fn decrease_skip_one(old: i32, skip: usize) -> i64 {
    with_level(without_direction(DIRECTIONS, skip), old)
}

#[cfg(test)]
fn decrease_all(old: i32) -> i64 {
    with_level(DIRECTIONS, old)
}

fn increase_from_emission(level: i32, empty: bool) -> i64 {
    let mut e = DIRECTIONS | FROM_EMISSION;
    if empty {
        e |= FROM_EMPTY_SHAPE;
    }
    with_level(e, level)
}

fn increase_skip_one(level: i32, empty: bool, skip: usize) -> i64 {
    let mut e = without_direction(DIRECTIONS, skip);
    if empty {
        e |= FROM_EMPTY_SHAPE;
    }
    with_level(e, level)
}

fn increase_only_one(level: i32, empty: bool, d: usize) -> i64 {
    let mut e = 0;
    if empty {
        e |= FROM_EMPTY_SHAPE;
    }
    with_level(with_direction(e, d), level)
}

fn from_level(entry: i64) -> i32 {
    (entry & 15) as i32
}

fn is_from_empty_shape(entry: i64) -> bool {
    entry & FROM_EMPTY_SHAPE != 0
}

fn is_from_emission(entry: i64) -> bool {
    entry & FROM_EMISSION != 0
}

fn goes(entry: i64, d: usize) -> bool {
    entry & 1 << (d + 4) != 0
}

// ---------------------------------------------------------------------------------------------------------------
// Light classes: what light needs of a block state, registered by Java (`RustLight.classOf`).

/// A light class: `getLightEmission`, `getLightDampening`, `LightEngine.isEmptyShape`, and per direction the face
/// `LightEngine.getOcclusionShape` gives (0: `Shapes.empty()`, 1: `Shapes.block()`, others ids Java hands out).
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct Class {
    pub emission: u8,
    pub dampening: u8,
    pub empty_shape: bool,
    pub faces: [u32; 6],
}

const EMPTY_FACE: u32 = 0;
const FULL_FACE: u32 = 1;

struct Classes {
    list: RwLock<Arc<Vec<Class>>>,
    /// `Shapes.faceShapeOccludes(a, b)` for faces neither empty nor full, as Java answered it.
    occludes: RwLock<HashMap<(u32, u32), bool>>,
}

fn classes() -> &'static Classes {
    static CLASSES: std::sync::OnceLock<Classes> = std::sync::OnceLock::new();
    CLASSES.get_or_init(|| Classes {
        list: RwLock::new(Arc::new(Vec::new())),
        occludes: RwLock::new(HashMap::new()),
    })
}

/// Registers light class `id` (ids are dense from 0; Java registers each once, in order).
pub fn register(id: u32, class: Class) {
    let c = classes();
    let mut list = c.list.write().unwrap_or_else(|e| e.into_inner());
    let mut next = (**list).clone();
    if next.len() <= id as usize {
        next.resize(id as usize + 1, Class::default());
    }
    next[id as usize] = class;
    *list = Arc::new(next);
}

// ---------------------------------------------------------------------------------------------------------------
// The run.

/// Calls back into Java (FFM upcalls), on the thread that runs the light updates.
#[repr(C)]
pub struct Callbacks {
    /// The section's layer in `LayerLightSectionStorage.updatingSectionData`: 0 when it has none (light is not
    /// stored there), the address of its 2048 bytes, or `-(default + 1)` for a layer that holds only its default
    /// level. With `write` 1: the layer to write, after `setStoredLevel`'s copy-on-write (always bytes).
    pub layer: extern "C" fn(section: i64, write: i32) -> i64,
    /// The light classes of the section's 4096 blocks (`y << 8 | z << 4 | x`, a `u16` each), or 0 for a section of
    /// air (or past the chunk's height), or -1 when there is no chunk (`LightEngine.getState`: bedrock).
    pub blocks: extern "C" fn(section: i64) -> i64,
    /// `LayerLightSectionStorage.lightOnInSection`.
    pub light_on: extern "C" fn(section: i64) -> i32,
    /// `Shapes.faceShapeOccludes` of two faces.
    pub occludes: extern "C" fn(a: u32, b: u32) -> i32,
}

#[derive(Clone, Copy)]
enum Layer {
    None,
    Default(u8),
    Bytes(*mut u8),
}

#[derive(Clone, Copy)]
enum Blocks {
    Air,
    NoChunk,
    Classes(*const u16),
}

struct Section {
    node: i64,
    layer: Layer,
    /// The layer was asked for to write (`setStoredLevel` copied it if it had to).
    writable: bool,
    blocks: Option<Blocks>,
    /// The section itself is in the affected list already.
    affected: bool,
}

/// A block in a run: its node, coordinates, section (index in `Run::sections`) and index in the section.
#[derive(Clone, Copy)]
struct At {
    node: i64,
    x: i32,
    y: i32,
    z: i32,
    sec: usize,
    idx: usize,
}

/// A block's index in its section (`DataLayer.getIndex`, `PalettedContainer`'s order): `y << 8 | z << 4 | x`.
fn cell(x: i32, y: i32, z: i32) -> usize {
    ((y & 15) << 8 | (z & 15) << 4 | (x & 15)) as usize
}

/// A hash for section and block nodes (packed coordinates, already well spread): one multiply, where the
/// standard library's SipHash costs as much as the light work around each lookup.
#[derive(Default)]
struct NodeHasher(u64);

impl Hasher for NodeHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0.rotate_left(5) ^ u64::from(b)).wrapping_mul(0x9e37_79b9_7f4a_7c15);
        }
    }

    fn write_i64(&mut self, n: i64) {
        self.0 = (self.0 ^ n as u64)
            .wrapping_mul(0x9e37_79b9_7f4a_7c15)
            .rotate_left(29);
    }
}

type NodeMap<V> = HashMap<i64, V, BuildHasherDefault<NodeHasher>>;
type NodeSet = HashSet<i64, BuildHasherDefault<NodeHasher>>;

/// One `runLightUpdates`' propagation.
pub struct Run<'a> {
    sky: bool,
    /// `SkyDataLayerStorageMap.currentLowestY`.
    lowest_y: i32,
    air: u32,
    bedrock: u32,
    calls: &'a Callbacks,
    classes: Arc<Vec<Class>>,
    sections: Vec<Section>,
    index: NodeMap<u32>,
    /// The last section looked up: most lookups are of the section the one before was in.
    last: (i64, usize),
    columns: NodeMap<bool>,
    decrease: Vec<i64>,
    decrease_at: usize,
    increase: Vec<i64>,
    increase_at: usize,
    affected: Vec<i64>,
    affected_set: NodeSet,
}

impl<'a> Run<'a> {
    /// A run over the queues Java held (pairs: node, entry), with Java's `air` and `bedrock` classes.
    pub fn new(
        sky: bool,
        lowest_y: i32,
        air: u32,
        bedrock: u32,
        calls: &'a Callbacks,
        decrease: Vec<i64>,
        increase: Vec<i64>,
    ) -> Self {
        let classes = classes()
            .list
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        Self {
            sky,
            lowest_y,
            air,
            bedrock,
            calls,
            classes,
            sections: Vec::new(),
            index: NodeMap::default(),
            last: (i64::MIN, 0),
            columns: NodeMap::default(),
            decrease,
            decrease_at: 0,
            increase,
            increase_at: 0,
            affected: Vec::new(),
            affected_set: NodeSet::default(),
        }
    }

    /// The sections light was written in and around, in the order first met.
    pub fn affected(&self) -> &[i64] {
        &self.affected
    }

    /// The section's index in `sections`, made (its layer asked of Java) the first time it is met.
    fn sec(&mut self, node: i64) -> usize {
        if self.last.0 == node {
            return self.last.1;
        }
        let i = match self.index.get(&node) {
            Some(&i) => i as usize,
            None => {
                let i = self.sections.len();
                self.sections.push(Section {
                    node,
                    layer: decode(((self.calls.layer)(node, 0)) as isize),
                    writable: false,
                    blocks: None,
                    affected: false,
                });
                self.index.insert(node, i as u32);
                i
            }
        };
        self.last = (node, i);
        i
    }

    /// A block: its node, coordinates, section and index in the section, worked out once.
    fn at(&mut self, node: i64) -> At {
        let (x, y, z) = (block_x(node), block_y(node), block_z(node));
        At {
            node,
            x,
            y,
            z,
            sec: self.sec(section_node(x >> 4, y >> 4, z >> 4)),
            idx: cell(x, y, z),
        }
    }

    /// `BlockPos.offset(node, d)`, as an [`At`]: inside the same section, no lookup.
    fn next(&mut self, a: &At, d: usize) -> At {
        let (dx, dy, dz) = STEPS[d];
        let (x, y, z) = (a.x + dx, a.y + dy, a.z + dz);
        let same = (x >> 4) == (a.x >> 4) && (y >> 4) == (a.y >> 4) && (z >> 4) == (a.z >> 4);
        let sec = if same {
            a.sec
        } else {
            self.sec(section_node(x >> 4, y >> 4, z >> 4))
        };
        At {
            node: block_node(x, y, z),
            x,
            y,
            z,
            sec,
            idx: cell(x, y, z),
        }
    }

    /// `LayerLightSectionStorage.storingLightForSection`, for the block's section.
    fn storing_at(&self, a: &At) -> bool {
        !matches!(self.sections[a.sec].layer, Layer::None)
    }

    /// `LayerLightSectionStorage.storingLightForSection`.
    fn storing(&mut self, section: i64) -> bool {
        let i = self.sec(section);
        !matches!(self.sections[i].layer, Layer::None)
    }

    /// `LayerLightSectionStorage.getStoredLevel`.
    fn level_at(&self, a: &At) -> i32 {
        match self.sections[a.sec].layer {
            Layer::None => panic!("light read in a section with no layer"),
            Layer::Default(level) => i32::from(level),
            // SAFETY: Java keeps the layer's bytes alive for the run (they are the map's), 2048 of them.
            Layer::Bytes(p) => i32::from(unsafe { *p.add(a.idx >> 1) } >> (4 * (a.idx & 1)) & 15),
        }
    }

    fn stored(&mut self, block: i64) -> i32 {
        let a = self.at(block);
        self.level_at(&a)
    }

    /// `LayerLightSectionStorage.setStoredLevel`.
    fn set_at(&mut self, a: &At, level: i32) {
        let calls = self.calls;
        let s = &mut self.sections[a.sec];
        if !s.writable {
            s.layer = decode(((calls.layer)(s.node, 1)) as isize);
            s.writable = true;
        }
        let Layer::Bytes(p) = s.layer else {
            panic!("a layer to write without bytes");
        };
        let shift = 4 * (a.idx & 1);
        // SAFETY: as in `level_at`; the light thread is the only writer.
        unsafe {
            let b = p.add(a.idx >> 1);
            *b = (*b & !(15 << shift)) | ((level as u8 & 15) << shift);
        }
        // A block inside the section (not on its faces) affects that section alone.
        let inside = |c: i32| (1..15).contains(&(c & 15));
        if inside(a.x) && inside(a.y) && inside(a.z) {
            if !s.affected {
                s.affected = true;
                let node = s.node;
                if self.affected_set.insert(node) {
                    self.affected.push(node);
                }
            }
            return;
        }
        self.affect(a.x, a.y, a.z);
    }

    fn set_stored(&mut self, block: i64, level: i32) {
        let a = self.at(block);
        self.set_at(&a, level);
    }

    /// `SectionPos.aroundAndAtBlockPos` into `sectionsAffectedByLightUpdates`.
    fn affect(&mut self, x: i32, y: i32, z: i32) {
        let (x0, x1) = ((x - 1) >> 4, (x + 1) >> 4);
        let (y0, y1) = ((y - 1) >> 4, (y + 1) >> 4);
        let (z0, z1) = ((z - 1) >> 4, (z + 1) >> 4);
        for sx in x0..=x1 {
            for sy in y0..=y1 {
                for sz in z0..=z1 {
                    let node = section_node(sx, sy, sz);
                    if self.affected_set.insert(node) {
                        self.affected.push(node);
                    }
                }
            }
        }
    }

    /// The light class of the block (`LightEngine.getState`).
    fn class_of(&mut self, a: &At) -> u32 {
        let calls = self.calls;
        let s = &mut self.sections[a.sec];
        let node = s.node;
        let blocks = *s.blocks.get_or_insert_with(|| match (calls.blocks)(node) {
            0 => Blocks::Air,
            -1 => Blocks::NoChunk,
            p => Blocks::Classes(p as *const u16),
        });
        match blocks {
            Blocks::Air => self.air,
            Blocks::NoChunk => self.bedrock,
            // SAFETY: Java keeps the section's classes alive while it lives; 4096 of them.
            Blocks::Classes(p) => u32::from(unsafe { *p.add(a.idx) }),
        }
    }

    fn class(&mut self, id: u32) -> Class {
        if id as usize >= self.classes.len() {
            // Registered since the run began (Java names classes as it meets new block states).
            self.classes = classes()
                .list
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
        }
        self.classes[id as usize]
    }

    /// `LightEngine.getOpacity`.
    fn opacity(&mut self, class: u32) -> i32 {
        i32::from(self.class(class).dampening).max(1)
    }

    /// `LightEngine.shapeOccludes`.
    fn shape_occludes(&mut self, from: u32, to: u32, d: usize) -> bool {
        let (a, b) = (self.class(from), self.class(to));
        let fa = if a.empty_shape {
            EMPTY_FACE
        } else {
            a.faces[d]
        };
        let fb = if b.empty_shape {
            EMPTY_FACE
        } else {
            b.faces[opposite(d)]
        };
        faces_occlude(self.calls, fa, fb)
    }

    /// `BlockLightEngine.getEmission`.
    fn emission(&mut self, a: &At, class: u32) -> i32 {
        let emission = i32::from(self.class(class).emission);
        if emission > 0 && self.light_on(self.sections[a.sec].node) {
            emission
        } else {
            0
        }
    }

    /// `LayerLightSectionStorage.lightOnInSection`, by column.
    fn light_on(&mut self, section: i64) -> bool {
        let zero = section & -1_048_576;
        let calls = self.calls;
        *self
            .columns
            .entry(zero)
            .or_insert_with(|| (calls.light_on)(section) != 0)
    }

    fn enqueue_decrease(&mut self, node: i64, entry: i64) {
        self.decrease.push(node);
        self.decrease.push(entry);
    }

    fn enqueue_increase(&mut self, node: i64, entry: i64) {
        self.increase.push(node);
        self.increase.push(entry);
    }

    /// `LightEngine.propagateDecreases` then `propagateIncreases`: the number of entries dequeued.
    pub fn propagate(&mut self) -> i32 {
        let mut count = 0;
        while self.decrease_at < self.decrease.len() {
            let from = self.decrease[self.decrease_at];
            let entry = self.decrease[self.decrease_at + 1];
            self.decrease_at += 2;
            if self.sky {
                self.sky_decrease(from, entry);
            } else {
                self.block_decrease(from, entry);
            }
            count += 1;
        }
        while self.increase_at < self.increase.len() {
            let from = self.increase[self.increase_at];
            let entry = self.increase[self.increase_at + 1];
            self.increase_at += 2;
            let mut level = self.stored(from);
            let target = from_level(entry);
            if is_from_emission(entry) && level < target {
                self.set_stored(from, target);
                level = target;
            }
            if level == target {
                if self.sky {
                    self.sky_increase(from, entry, level);
                } else {
                    self.block_increase(from, entry, level);
                }
            }
            count += 1;
        }
        count
    }

    // -- BlockLightEngine --------------------------------------------------------------------------------------

    /// `BlockLightEngine.propagateIncrease`.
    fn block_increase(&mut self, from: i64, entry: i64, from_level: i32) {
        let f = self.at(from);
        let mut from_class = None;
        for d in 0..6 {
            if !goes(entry, d) {
                continue;
            }
            let to = self.next(&f, d);
            if !self.storing_at(&to) {
                continue;
            }
            let to_level = self.level_at(&to);
            if from_level - 1 <= to_level {
                continue;
            }
            let to_class = self.class_of(&to);
            let new_level = from_level - self.opacity(to_class);
            if new_level <= to_level {
                continue;
            }
            let fc = match from_class {
                Some(c) => c,
                None => {
                    let c = if is_from_empty_shape(entry) {
                        self.air
                    } else {
                        self.class_of(&f)
                    };
                    from_class = Some(c);
                    c
                }
            };
            if !self.shape_occludes(fc, to_class, d) {
                self.set_at(&to, new_level);
                if new_level > 1 {
                    let empty = self.class(to_class).empty_shape;
                    self.enqueue_increase(
                        to.node,
                        increase_skip_one(new_level, empty, opposite(d)),
                    );
                }
            }
        }
    }

    /// `BlockLightEngine.propagateDecrease`.
    fn block_decrease(&mut self, from: i64, entry: i64) {
        let f = self.at(from);
        let old_from = from_level(entry);
        for d in 0..6 {
            if !goes(entry, d) {
                continue;
            }
            let to = self.next(&f, d);
            if !self.storing_at(&to) {
                continue;
            }
            let to_level = self.level_at(&to);
            if to_level == 0 {
                continue;
            }
            // `toLevel <= oldFromLevel - 1`
            if to_level < old_from {
                let to_class = self.class_of(&to);
                let to_emission = self.emission(&to, to_class);
                self.set_at(&to, 0);
                if to_emission < to_level {
                    self.enqueue_decrease(to.node, decrease_skip_one(to_level, opposite(d)));
                }
                if to_emission > 0 {
                    let empty = self.class(to_class).empty_shape;
                    self.enqueue_increase(to.node, increase_from_emission(to_emission, empty));
                }
            } else {
                self.enqueue_increase(to.node, increase_only_one(to_level, false, opposite(d)));
            }
        }
    }

    // -- SkyLightEngine ----------------------------------------------------------------------------------------

    /// `SkyLightSectionStorage.hasLightDataAtOrBelow`.
    fn data_at_or_below(&self, section_y: i32) -> bool {
        section_y >= self.lowest_y
    }

    /// `SkyLightEngine.propagateIncrease`.
    fn sky_increase(&mut self, from: i64, entry: i64, from_level: i32) {
        let f = self.at(from);
        let mut from_class = None;
        let empty_below = self.empty_sections_below_if_at_border(from);
        for d in 0..6 {
            if !goes(entry, d) {
                continue;
            }
            let to = self.next(&f, d);
            if !self.storing_at(&to) {
                continue;
            }
            let to_level = self.level_at(&to);
            if from_level - 1 <= to_level {
                continue;
            }
            let to_class = self.class_of(&to);
            let new_level = from_level - self.opacity(to_class);
            if new_level <= to_level {
                continue;
            }
            let fc = match from_class {
                Some(c) => c,
                None => {
                    let c = if is_from_empty_shape(entry) {
                        self.air
                    } else {
                        self.class_of(&f)
                    };
                    from_class = Some(c);
                    c
                }
            };
            if !self.shape_occludes(fc, to_class, d) {
                self.set_at(&to, new_level);
                if new_level > 1 {
                    let empty = self.class(to_class).empty_shape;
                    self.enqueue_increase(
                        to.node,
                        increase_skip_one(new_level, empty, opposite(d)),
                    );
                }
                self.propagate_from_empty_sections(to.node, d, new_level, true, empty_below);
            }
        }
    }

    /// `SkyLightEngine.propagateDecrease`.
    fn sky_decrease(&mut self, from: i64, entry: i64) {
        let f = self.at(from);
        let empty_below = self.empty_sections_below_if_at_border(from);
        let old_from = from_level(entry);
        for d in 0..6 {
            if !goes(entry, d) {
                continue;
            }
            let to = self.next(&f, d);
            if !self.storing_at(&to) {
                continue;
            }
            let to_level = self.level_at(&to);
            if to_level == 0 {
                continue;
            }
            // `toLevel <= oldFromLevel - 1`
            if to_level < old_from {
                self.set_at(&to, 0);
                self.enqueue_decrease(to.node, decrease_skip_one(to_level, opposite(d)));
                self.propagate_from_empty_sections(to.node, d, to_level, false, empty_below);
            } else {
                self.enqueue_increase(to.node, increase_only_one(to_level, false, opposite(d)));
            }
        }
    }

    /// `SkyLightEngine.countEmptySectionsBelowIfAtBorder`.
    fn empty_sections_below_if_at_border(&mut self, block: i64) -> i32 {
        let y = block_y(block);
        if y & 15 != 0 {
            return 0;
        }
        let (x, z) = (block_x(block), block_z(block));
        let (lx, lz) = (x & 15, z & 15);
        if lx != 0 && lx != 15 && lz != 0 && lz != 15 {
            return 0;
        }
        let (sx, sy, sz) = (x >> 4, y >> 4, z >> 4);
        let mut below = 0;
        while !self.storing(section_node(sx, sy - below - 1, sz))
            && self.data_at_or_below(sy - below - 1)
        {
            below += 1;
        }
        below
    }

    /// `SkyLightEngine.propagateFromEmptySections`.
    fn propagate_from_empty_sections(
        &mut self,
        to: i64,
        d: usize,
        to_level: i32,
        increase: bool,
        empty_below: i32,
    ) {
        if empty_below == 0 {
            return;
        }
        let (x, z) = (block_x(to), block_z(to));
        if !crossed_section_edge(d, x & 15, z & 15) {
            return;
        }
        let y = block_y(to);
        let (sx, sz) = (x >> 4, z >> 4);
        let mut sy = (y >> 4) - 1;
        let bottom = sy - empty_below + 1;
        while sy >= bottom {
            if !self.storing(section_node(sx, sy, sz)) {
                sy -= 1;
                continue;
            }
            let min_y = sy << 4;
            for ly in (0..16).rev() {
                let node = block_node(x, min_y + ly, z);
                if increase {
                    self.set_stored(node, to_level);
                    if to_level > 1 {
                        self.enqueue_increase(node, increase_skip_one(to_level, true, opposite(d)));
                    }
                } else {
                    self.set_stored(node, 0);
                    self.enqueue_decrease(node, decrease_skip_one(to_level, opposite(d)));
                }
            }
            sy -= 1;
        }
    }
}

/// `SkyLightEngine.crossedSectionEdge`.
fn crossed_section_edge(d: usize, x: i32, z: i32) -> bool {
    match d {
        NORTH => z == 15,
        SOUTH => z == 0,
        WEST => x == 15,
        EAST => x == 0,
        _ => false,
    }
}

/// `Shapes.faceShapeOccludes` (empty with empty: no; a full face: yes; the rest asked of Java once).
fn faces_occlude(calls: &Callbacks, a: u32, b: u32) -> bool {
    if a == FULL_FACE || b == FULL_FACE {
        return true;
    }
    if a == EMPTY_FACE && b == EMPTY_FACE {
        return false;
    }
    let c = classes();
    if let Some(&o) = c
        .occludes
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .get(&(a, b))
    {
        return o;
    }
    let o = (calls.occludes)(a, b) != 0;
    c.occludes
        .write()
        .unwrap_or_else(|e| e.into_inner())
        .insert((a, b), o);
    o
}

fn decode(code: isize) -> Layer {
    match code {
        0 => Layer::None,
        c if c < 0 => Layer::Default((-c - 1) as u8),
        p => Layer::Bytes(p as *mut u8),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A synthetic world for the propagation alone: `SIDE`³ sections of air with light stored everywhere.
    mod bench_world {
        use std::cell::RefCell;
        use std::collections::HashMap;

        thread_local! {
            pub static LAYERS: RefCell<HashMap<i64, Box<[u8; 2048]>>> = RefCell::new(HashMap::new());
            pub static SIDE: RefCell<i32> = const { RefCell::new(0) };
        }

        pub extern "C" fn layer(section: i64, _write: i32) -> i64 {
            let side = SIDE.with(|s| *s.borrow());
            let (x, y, z) = (
                super::section_x(section),
                super::section_y(section),
                super::section_z(section),
            );
            if !(0..side).contains(&x) || !(0..side).contains(&y) || !(0..side).contains(&z) {
                return 0;
            }
            LAYERS.with(|l| {
                let mut l = l.borrow_mut();
                let b = l.entry(section).or_insert_with(|| Box::new([0u8; 2048]));
                b.as_mut_ptr() as i64
            })
        }

        pub extern "C" fn blocks(_section: i64) -> i64 {
            0
        }

        pub extern "C" fn light_on(_section: i64) -> i32 {
            1
        }

        pub extern "C" fn occludes(_a: u32, _b: u32) -> i32 {
            1
        }
    }

    #[test]
    #[ignore = "a benchmark: cargo test --release -p horizon-shm-probe bench -- --ignored --nocapture"]
    fn bench_block_light_flood() {
        register(0, Class::default());
        let side = 8;
        bench_world::SIDE.with(|s| *s.borrow_mut() = side);
        let calls = Callbacks {
            layer: bench_world::layer,
            blocks: bench_world::blocks,
            light_on: bench_world::light_on,
            occludes: bench_world::occludes,
        };
        // An emitter every 12 blocks: overlapping floods of 14 levels.
        let mut increase = Vec::new();
        let n = side * 16;
        for x in (2..n).step_by(12) {
            for y in (2..n).step_by(12) {
                for z in (2..n).step_by(12) {
                    increase.push(block_node(x, y, z));
                    increase.push(increase_from_emission(15, true));
                }
            }
        }
        let start = std::time::Instant::now();
        let mut run = Run::new(false, 0, 0, 0, &calls, Vec::new(), increase);
        let count = run.propagate();
        let took = start.elapsed();
        println!(
            "{count} entries in {took:?}: {:.1} ns an entry, {} sections",
            took.as_nanos() as f64 / f64::from(count),
            run.sections.len()
        );
        assert!(count > 100_000);
    }

    #[test]
    fn positions_pack_as_vanilla() {
        for &(x, y, z) in &[
            (0, 0, 0),
            (-1, -64, -1),
            (29_999_999, 319, -29_999_999),
            (17, -1, -33),
        ] {
            let n = block_node(x, y, z);
            assert_eq!((block_x(n), block_y(n), block_z(n)), (x, y, z));
            let s = section_node(x >> 4, y >> 4, z >> 4);
            assert_eq!(
                (section_x(s), section_y(s), section_z(s)),
                (x >> 4, y >> 4, z >> 4)
            );
            assert_eq!(section_of(n), s);
        }
        // BlockPos.asLong(1, 2, 3) and SectionPos.asLong(1, 2, 3) in vanilla.
        assert_eq!(block_node(1, 2, 3), 274_877_919_234);
        assert_eq!(section_node(1, 2, 3), 4_398_049_656_834);
        assert_eq!(offset(block_node(0, 0, 0), WEST), block_node(-1, 0, 0));
        assert_eq!(opposite(DOWN), UP);
    }

    #[test]
    fn queue_entries_match_vanilla() {
        // QueueEntry.decreaseAllDirections(1), as LightEngine.PULL_LIGHT_IN_ENTRY.
        assert_eq!(decrease_all(1), 1009);
        // SkyLightEngine.ADD_SKY_SOURCE_ENTRY: increaseSkipOneDirection(15, false, UP).
        assert_eq!(increase_skip_one(15, false, UP), 1008 & !(1 << 5) | 15);
        assert!(goes(increase_only_one(3, false, EAST), EAST));
        assert!(!goes(increase_only_one(3, false, EAST), WEST));
        assert_eq!(from_level(increase_from_emission(14, true)), 14);
        assert!(is_from_emission(increase_from_emission(14, true)));
        assert!(is_from_empty_shape(increase_from_emission(14, true)));
    }
}
