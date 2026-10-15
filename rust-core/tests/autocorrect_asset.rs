//! Integration test: autocorrect must work through the public API against the
//! dictionary asset that actually ships, not just against synthetic tables.

use keyboard_core::MappedDictionary;
use std::path::Path;

fn shipped_dictionary() -> Option<MappedDictionary> {
    let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/main/assets/keyboard_dictionary.bin");
    if !asset.exists() {
        return None; // crate-only checkout
    }
    Some(MappedDictionary::load(&asset).expect("shipped asset must parse"))
}

fn correct(dictionary: &MappedDictionary, typed: &str) -> Option<String> {
    dictionary
        .autocorrect(typed.as_bytes())
        .and_then(|index| dictionary.key_at(index))
        .map(|key| String::from_utf8_lossy(key).into_owned())
}

#[test]
fn transposed_real_word_is_corrected() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    // "teh" is a classic transposition of "the".
    assert_eq!(correct(&dictionary, "teh").as_deref(), Some("the"));
}

#[test]
fn common_words_are_left_alone() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    // Already-correct real words must never be rewritten.
    for word in ["the", "and", "hello", "keyboard"] {
        assert_eq!(
            correct(&dictionary, word),
            None,
            "{word} is a real word and must not be autocorrected"
        );
    }
}

#[test]
fn short_tokens_are_never_touched() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    assert_eq!(correct(&dictionary, "te"), None);
    assert_eq!(correct(&dictionary, "a"), None);
}

#[test]
fn classic_misspelling_reaches_the_right_word() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    assert_eq!(correct(&dictionary, "recieve").as_deref(), Some("receive"));
}

#[test]
fn a_nonsense_prefix_never_pads_the_bar_with_unrelated_words() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    // "zz" turns out to be a rare dictionary word itself, so a z-word or a
    // genuinely close match is a fair answer. What must never happen is
    // padding the bar with unrelated common words -- that is the bug this
    // guards, and it took a real-size vocabulary to reproduce.
    let unrelated = ["the", "and", "of", "to", "in", "hello", "you"];
    let suggestions = dictionary.suggest(b"zz", "", 4);
    assert!(
        !suggestions.is_empty(),
        "'zz' is a word, so it must be offered"
    );
    for index in suggestions {
        let key = dictionary
            .key_at(index)
            .expect("index came from the dictionary");
        let word = String::from_utf8_lossy(key).into_owned();
        assert!(
            !unrelated.contains(&word.as_str()),
            "{word:?} has nothing to do with 'zz'"
        );
        if !word.starts_with("zz") {
            assert!(
                edit_distance(b"zz", key) <= 2,
                "{word:?} is too far from 'zz' to read as a typo fix"
            );
        }
    }
}

/// Tiny OSA edit distance, mirroring the engine's transposition-aware metric.
fn edit_distance(left: &[u8], right: &[u8]) -> usize {
    let (rows, cols) = (left.len() + 1, right.len() + 1);
    let mut grid = vec![vec![0usize; cols]; rows];
    for (i, row) in grid.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, slot) in grid[0].iter_mut().enumerate() {
        *slot = j;
    }
    for i in 1..rows {
        for j in 1..cols {
            let cost = usize::from(left[i - 1] != right[j - 1]);
            let mut best = (grid[i - 1][j] + 1)
                .min(grid[i][j - 1] + 1)
                .min(grid[i - 1][j - 1] + cost);
            if i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1] {
                best = best.min(grid[i - 2][j - 2] + 1);
            }
            grid[i][j] = best;
        }
    }
    grid[rows - 1][cols - 1]
}
