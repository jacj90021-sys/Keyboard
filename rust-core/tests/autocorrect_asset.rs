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
fn a_nonsense_prefix_yields_no_suggestions() {
    let Some(dictionary) = shipped_dictionary() else {
        return;
    };
    // Nothing starts with "zz" and no close match is plausible, so the bar stays
    // empty rather than being padded with unrelated common words.
    assert!(dictionary.suggest(b"zz", "", 4).is_empty());
}
