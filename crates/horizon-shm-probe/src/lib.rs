//! P2 of `docs/design/java-mods-everything.md`: block storage that Rust owns, which vanilla's own Java code
//! reads and writes in place.
//!
//! The JVM reaches these functions through FFM (`java.lang.foreign`). Rust allocates a storage's words;
//! Java's `RustBitStorage` (`mods/horizon/src/shared-memory-probe/`) is a `SimpleBitStorage` whose every read
//! and write goes to those words, so the game's `PalettedContainer` runs on memory Rust holds. Rust then reads
//! the same memory with horizon-core's own `PackedStorage` and `PalettedContainer`, and writes to it for Java
//! to see: one copy of the data, two languages.
//!
//! Driven by `tools/shared_memory_probe.py`. Nothing here is linked into the game.

use horizon_core::paletted::{PalettedContainer, Strategy};
use horizon_core::storage::PackedStorage;
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

/// FNV-1a over values, as Java hashes the same values.
fn fnv(values: impl Iterator<Item = u32>) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for v in values {
        for b in v.to_le_bytes() {
            h ^= u64::from(b);
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
    }
    h
}

/// The hash of every value in the storage, read by horizon-core's `PackedStorage` from these words. 0 when
/// the shape is not one `PackedStorage` accepts.
///
/// horizon-core's types own their words, so this reads through a copy of them; the Java side never copies.
///
/// # Safety
/// `ptr` points at `words` initialised longs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_storage_hash(ptr: *const u64, words: u32, bits: u32, size: u32) -> u64 {
    // SAFETY: as the caller promises.
    let data = unsafe { view(ptr, words) };
    match PackedStorage::from_words(bits as u8, size as usize, data.to_vec()) {
        Ok(storage) => fnv(storage.unpack().into_iter()),
        Err(_) => 0,
    }
}

/// How many of the 4,096 cells horizon-core's own `PalettedContainer`, built from these words and this palette
/// of block state ids, reads differently from `expected`; -1 when it refuses the words.
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
        Ok(container) => (0..4096).filter(|&i| container.get(i) != expected[i]).count() as i32,
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
            assert_eq!(hz_storage_hash(ptr, words, bits, size), fnv((0..size).map(|i| (i * 7) % 32)));
            hz_free(ptr, words);
        }
    }
}
