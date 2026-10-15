//! Diagnostic probe: print what the shipped engine actually suggests for a
//! range of prefixes and commit contexts, plus autocorrect decisions. Run with
//! `cargo run --example suggest_probe` from `rust-core/`.

use keyboard_core::{MappedDictionary, PromotionTable};
use std::path::Path;

fn main() {
    let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/main/assets/keyboard_dictionary.bin");
    let dictionary = MappedDictionary::load(&asset).expect("shipped asset must parse");
    println!("entries: {}", dictionary.entry_count());

    let promotions = PromotionTable::new();

    for prefix in ["t", "th", "the", "hel", "wor", "go", "key", "zz", "recieve"] {
        let idx = dictionary.suggest_ranked(prefix.as_bytes(), "", 5, &promotions);
        let words: Vec<String> = idx
            .iter()
            .filter_map(|&i| dictionary.key_at(i))
            .map(|k| String::from_utf8_lossy(k).into_owned())
            .collect();
        println!("suggest({prefix:>8}) -> {words:?}");
    }

    for prev in ["good", "thank", "how", "the"] {
        let idx = dictionary.top_next_words(prev, 5);
        let words: Vec<String> = idx
            .iter()
            .filter_map(|&i| dictionary.key_at(i))
            .map(|k| String::from_utf8_lossy(k).into_owned())
            .collect();
        println!("next({prev:>8}) -> {words:?}");
    }

    for typo in ["teh", "recieve", "helo", "wrold", "th", "exmaple"] {
        let fixed = dictionary
            .autocorrect(typo.as_bytes())
            .and_then(|i| dictionary.key_at(i))
            .map(|k| String::from_utf8_lossy(k).into_owned());
        println!("autocorrect({typo:>8}) -> {fixed:?}");
    }
}
