//! Touch geometry: the physical model of the board under the finger.
//!
//! The rest of the engine reasons about *words*. This module reasons about
//! *keys*, and answers the two questions a purely textual engine cannot:
//!
//! * **Where is a letter on the board?** [`qwerty_position`] places every
//!   lowercase letter on an abstract three-row board measured in *key pitches*
//!   (the centre-to-centre spacing of neighbouring keys). Screen size, density
//!   and row padding drop out of the maths, so one set of constants fits a
//!   dense phone board and a coarse tablet one alike.
//! * **How badly were two words mixed up?** [`spatial_distance`] is an edit
//!   distance whose substitution cost comes from that board. Mistaking `g` for
//!   its neighbour `h` is nearly free; mistaking `g` for `p` on the far side of
//!   the board is charged in full. A swapped pair ("teh" for "the") gets a
//!   small fixed charge, because that is one keystroke-level slip, not two.
//!
//! That is what makes correction *layout aware*: the engine can prefer the word
//! the thumb could plausibly have meant over a word that merely happens to
//! differ by one edit.

use crate::gesture::Cell;

/// Rows of the abstract board, left to right, each paired with the horizontal
/// offset of its first key in key-pitch units. The stagger is the real one: the
/// home row sits half a key to the right of the top row, and the bottom row half
/// a key further right again (where the wide shift key pushes it).
const BOARD_ROWS: [(&[u8], f32); 3] =
    [(b"qwertyuiop", 0.5), (b"asdfghjkl", 1.0), (b"zxcvbnm", 2.0)];

/// Board position of a letter as `(x, y)` in key-pitch units, or `None` when the
/// byte is not a letter. Case is ignored, so `'G'` and `'g'` share a key.
pub fn qwerty_position(code: u8) -> Option<(f32, f32)> {
    let lower = code.to_ascii_lowercase();
    for (row, (letters, offset)) in BOARD_ROWS.iter().enumerate() {
        if let Some(column) = letters.iter().position(|&letter| letter == lower) {
            return Some((*offset + column as f32, row as f32));
        }
    }
    None
}

/// Distance between two letters on the board, in key-pitch units, or `None` when
/// either byte is not a letter. Horizontal neighbours are `1.0`; diagonally
/// staggered neighbours are a little over `1.0`.
pub fn key_gap(from: u8, to: u8) -> Option<f32> {
    let (fx, fy) = qwerty_position(from)?;
    let (tx, ty) = qwerty_position(to)?;
    let dx = fx - tx;
    let dy = fy - ty;
    Some((dx * dx + dy * dy).sqrt())
}

/// Distance past which two keys count as unrelated, in key-pitch units. Anything
/// this far apart (or further) pays the full substitution charge.
const SLIP_SATURATION: f32 = 3.0;

/// Charge for substituting one letter for another, in "edit" units.
const SUB_WEIGHT: f32 = 1.0;

/// Charge for an inserted or dropped letter. A missing letter is a different
/// kind of slip from a wrong key, so it keeps its own full unit.
const GAP_WEIGHT: f32 = 1.0;

/// Charge for a swapped neighbour pair. Cheaper than any substitution: the
/// finger landed on the right two keys and only their order slipped.
const SWAP_WEIGHT: f32 = 0.4;

/// Cost of mistaking `from` for `to`, scaled by how far apart the keys are.
///
/// Adjacent keys (`1.0` pitch) cost a third of a unit; keys at or beyond
/// [`SLIP_SATURATION`] cost a whole unit. Unknown bytes (digits, apostrophes)
/// cost the full unit, so the model never claims a fat finger it cannot place.
pub fn slip_cost(from: u8, to: u8) -> f32 {
    if from == to {
        return 0.0;
    }
    match key_gap(from, to) {
        Some(gap) => SUB_WEIGHT * (gap / SLIP_SATURATION).min(1.0),
        None => SUB_WEIGHT,
    }
}

/// Layout-aware edit distance between two words.
///
/// Same recursion as the classic Damerau-Levenshtein distance, but the
/// substitution cell is priced by [`slip_cost`] instead of a flat `1`, and the
/// transposition cell by [`SWAP_WEIGHT`]. The result is a `f32` cost: `0.0` for
/// identical words, a fraction of a unit for a plausible fat finger, roughly
/// `1.0` per genuinely wrong key.
pub fn spatial_distance(typed: &[u8], candidate: &[u8]) -> f32 {
    let width = candidate.len();
    if typed.is_empty() {
        return GAP_WEIGHT * width as f32;
    }
    if width == 0 {
        return GAP_WEIGHT * typed.len() as f32;
    }

    // `previous` is the DP row for the typed prefix one short of the current
    // letter, `older` the row before that — all the transposition term needs.
    let mut older: Vec<f32> = Vec::new();
    let mut previous: Vec<f32> = (0..=width)
        .map(|column| GAP_WEIGHT * column as f32)
        .collect();

    for (row, &typed_byte) in typed.iter().enumerate() {
        let mut current = vec![0.0f32; width + 1];
        current[0] = GAP_WEIGHT * (row + 1) as f32;
        for (column, &candidate_byte) in candidate.iter().enumerate() {
            let substitute = previous[column] + slip_cost(typed_byte, candidate_byte);
            let delete = previous[column + 1] + GAP_WEIGHT;
            let insert = current[column] + GAP_WEIGHT;
            let mut best = substitute.min(delete).min(insert);

            // Neighbour pair typed in the wrong order ("teh" vs "the").
            if row >= 1
                && column >= 1
                && typed_byte == candidate[column - 1]
                && typed[row - 1] == candidate_byte
            {
                best = best.min(older[column - 1] + SWAP_WEIGHT);
            }
            current[column + 1] = best;
        }
        older = previous;
        previous = current;
    }

    previous[width]
}

/// Measure the board's key pitch — the centre-to-centre spacing of neighbouring
/// keys — from a live grid, in whatever units the grid uses (view pixels on
/// Android).
///
/// Every distance gate in the swipe decoder is stated as a fraction of this
/// pitch, so getting it from the board instead of assuming a fixed pixel value is
/// what keeps the decoder calibrated across screen sizes and densities. The
/// estimate is the smallest positive horizontal gap between two keys sharing a
/// row; on any real board that is exactly one pitch, because keys in a row are
/// laid out contiguously. Rows of different widths (a number row, a staggered
/// bottom row) do not disturb it, since the minimum is taken across all of them.
pub fn estimate_pitch(grid: &[Option<Cell>; 256]) -> Option<f32> {
    let cells: Vec<Cell> = grid.iter().flatten().copied().collect();
    let mut pitch = f32::MAX;
    for (index, first) in cells.iter().enumerate() {
        for second in cells.iter().skip(index + 1) {
            // Same row: vertical centres coincide to within a key's half-height.
            let row_tolerance = first.half_height.min(second.half_height);
            if (first.centre.y - second.centre.y).abs() > row_tolerance {
                continue;
            }
            let gap = (first.centre.x - second.centre.x).abs();
            if gap > 0.0 && gap < pitch {
                pitch = gap;
            }
        }
    }
    (pitch < f32::MAX).then_some(pitch)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn board_placement_matches_a_real_keyboard() {
        // Top row starts half a key in and runs to the far right.
        assert_eq!(qwerty_position(b'q'), Some((0.5, 0.0)));
        assert_eq!(qwerty_position(b'p'), Some((9.5, 0.0)));
        // Home row is staggered half a key right; 'a' sits between q and w.
        assert_eq!(qwerty_position(b'a'), Some((1.0, 1.0)));
        // 'z' lands under the home row's 's'.
        assert_eq!(
            qwerty_position(b'z'),
            qwerty_position(b's').map(|(x, _)| (x, 2.0))
        );
        assert_eq!(qwerty_position(b'z'), Some((2.0, 2.0)));
        // Case is not a key of its own.
        assert_eq!(qwerty_position(b'Q'), qwerty_position(b'q'));
        assert_eq!(qwerty_position(b'1'), None);
        assert_eq!(qwerty_position(b'\''), None);
    }

    #[test]
    fn horizontal_neighbours_are_one_pitch_apart() {
        assert!((key_gap(b'a', b's').unwrap() - 1.0).abs() < 1e-6);
        assert!((key_gap(b'q', b'w').unwrap() - 1.0).abs() < 1e-6);
        assert!(key_gap(b'a', b'z').unwrap() > 1.0);
        assert!(key_gap(b'q', b'p').unwrap() > 8.0);
    }

    #[test]
    fn adjacent_keys_are_a_cheap_slip() {
        // g/h and f/g share an edge: a third of a unit.
        assert!(slip_cost(b'g', b'h') < 0.4);
        assert!(slip_cost(b'f', b'g') < 0.4);
        // g/p are on opposite corners of the board: full price.
        assert!((slip_cost(b'g', b'p') - 1.0).abs() < 1e-6);
        // A letter with itself is free.
        assert_eq!(slip_cost(b'a', b'a'), 0.0);
        // Bytes with no board position are charged as a full substitution.
        assert_eq!(slip_cost(b'a', b'1'), 1.0);
    }

    #[test]
    fn layout_slips_beat_far_substitutions() {
        // "gello" -> "hello" only ever moved onto the neighbouring key.
        let near = spatial_distance(b"gello", b"hello");
        // "gello" -> "cello" had to cross to the row below; still plausible,
        // but never cheaper than the neighbour.
        let below = spatial_distance(b"gello", b"cello");
        assert!(near < 1.0, "neighbour slip should be sub-unit: {near}");
        assert!(near < below, "neighbour {near} should beat row-hop {below}");
    }

    #[test]
    fn transposition_is_cheaper_than_a_wrong_key() {
        // "teh" -> "the" is a swapped pair; "teh" -> "ten" is a wrong key.
        let swapped = spatial_distance(b"teh", b"the");
        let substituted = spatial_distance(b"teh", b"ten");
        assert!(
            swapped < substituted,
            "swap {swapped} should beat substitution {substituted}"
        );
        assert!(swapped < 1.0);
    }

    #[test]
    fn identical_and_empty_words_are_free_or_counted() {
        assert_eq!(spatial_distance(b"keyboard", b"keyboard"), 0.0);
        assert_eq!(spatial_distance(b"", b"abc"), 3.0);
        assert_eq!(spatial_distance(b"abc", b""), 3.0);
    }

    #[test]
    fn distance_is_symmetric_on_plain_words() {
        // Not a guarantee of the recursion in general (the transposition term is
        // directional), but it must hold for a plain single substitution.
        let forward = spatial_distance(b"cat", b"cst");
        let backward = spatial_distance(b"cst", b"cat");
        assert!((forward - backward).abs() < 1e-6);
        assert!(forward > 0.0 && forward < 1.0);
    }

    #[test]
    fn pitch_is_measured_from_the_grid() {
        // Three keys on a row 40 px apart, plus one on the row below. The row
        // below must not be mistaken for a horizontal neighbour.
        let mut grid: [Option<Cell>; 256] = [None; 256];
        grid[b'a' as usize] = Some(Cell::new(0.0, 0.0, 18.0, 22.0));
        grid[b's' as usize] = Some(Cell::new(40.0, 0.0, 18.0, 22.0));
        grid[b'd' as usize] = Some(Cell::new(80.0, 0.0, 18.0, 22.0));
        grid[b'z' as usize] = Some(Cell::new(20.0, 44.0, 18.0, 22.0));

        let pitch = estimate_pitch(&grid).expect("a row exists");
        assert!((pitch - 40.0).abs() < 1e-3, "expected 40 px, got {pitch}");
    }

    #[test]
    fn pitch_needs_two_keys_sharing_a_row() {
        let mut grid: [Option<Cell>; 256] = [None; 256];
        grid[b'a' as usize] = Some(Cell::new(0.0, 0.0, 18.0, 22.0));
        assert_eq!(estimate_pitch(&grid), None);

        // Keys stacked in a column are not horizontal neighbours.
        grid[b'q' as usize] = Some(Cell::new(0.0, 44.0, 18.0, 22.0));
        assert_eq!(estimate_pitch(&grid), None);
    }
}
