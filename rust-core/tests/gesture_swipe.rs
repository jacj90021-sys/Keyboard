//! Integration test: a realistic swipe across a QWERTY grid must surface the
//! intended word through the public gesture API and the shipped dictionary.

use keyboard_core::{rank_stroke, Cell, MappedDictionary, PromotionTable, Trace, TraceWeights};
use std::path::Path;

/// Minimal QWERTY grid in an arbitrary pixel space: letters are laid out on
/// three rows, 100px apart, matching the drawable board closely enough for the
/// spatial scorer to work with.
fn qwerty_cell(byte: u8) -> Option<Cell> {
    const ROW_TOP: &[u8] = b"qwertyuiop";
    const ROW_HOME: &[u8] = b"asdfghjkl";
    const ROW_BOTTOM: &[u8] = b"zxcvbnm";

    let half_w = 45.0;
    let half_h = 40.0;

    if let Some(i) = ROW_TOP.iter().position(|&b| b == byte) {
        return Some(Cell::new(i as f32 * 100.0 + 50.0, 0.0, half_w, half_h));
    }
    if let Some(i) = ROW_HOME.iter().position(|&b| b == byte) {
        return Some(Cell::new(i as f32 * 100.0 + 100.0, 100.0, half_w, half_h));
    }
    if let Some(i) = ROW_BOTTOM.iter().position(|&b| b == byte) {
        return Some(Cell::new(i as f32 * 100.0 + 200.0, 200.0, half_w, half_h));
    }
    None
}

/// Simulate the finger travelling through the centres of `word`, sampling a few
/// points between consecutive keys (what a real MotionEvent stream looks like).
fn stroke_for(word: &str) -> Vec<Trace> {
    let centres: Vec<Trace> = word
        .bytes()
        .filter_map(qwerty_cell)
        .map(|c| c.centre)
        .collect();
    let mut path = Vec::new();
    for window in centres.windows(2) {
        let (a, b) = (window[0], window[1]);
        for step in 0..5 {
            let t = step as f32 / 5.0;
            path.push(Trace::new(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
        }
    }
    if let Some(last) = centres.last() {
        path.push(*last);
    }
    path
}

#[test]
fn swipe_recovers_the_intended_word() {
    let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/main/assets/keyboard_dictionary.bin");
    if !asset.exists() {
        // A crate-only checkout (no app/) simply skips this check.
        return;
    }

    let dictionary = MappedDictionary::load(&asset).expect("shipped asset must parse");
    let promotions = PromotionTable::new();

    for word in ["the", "hello", "world"] {
        let stroke = stroke_for(word);
        assert!(stroke.len() >= 2, "need a real path for {word}");

        let ranked = rank_stroke(
            &stroke,
            &dictionary,
            &promotions,
            qwerty_cell,
            TraceWeights::default(),
            8,
        );

        let names: Vec<String> = ranked
            .iter()
            .filter_map(|&i| dictionary.key_at(i))
            .map(|k| String::from_utf8_lossy(k).into_owned())
            .collect();

        assert!(
            names.iter().any(|candidate| candidate == word),
            "swiping {word} should recover it; got {names:?}"
        );
    }
}

#[test]
fn swipe_without_matching_keys_is_empty() {
    let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/main/assets/keyboard_dictionary.bin");
    if !asset.exists() {
        return;
    }
    let dictionary = MappedDictionary::load(&asset).expect("shipped asset must parse");
    let promotions = PromotionTable::new();

    // A single-point stroke is not a swipe.
    let ranked = rank_stroke(
        &[Trace::new(0.0, 0.0)],
        &dictionary,
        &promotions,
        qwerty_cell,
        TraceWeights::default(),
        8,
    );
    assert!(ranked.is_empty());
}
