//! Measure how long the shipped dictionary takes to become usable.
//!
//! The Android service mmaps this asset and then compiles the prefix trie in
//! process, so this runs on the IME's startup path — a 46k-word vocabulary is
//! only acceptable if that stays well under a perceptible pause. Query cost
//! matters too: suggestions fire on every keystroke. Run with:
//!
//! ```sh
//! cargo run --quiet --example load_bench
//! ```

use keyboard_core::MappedDictionary;
use std::path::{Path, PathBuf};
use std::time::Instant;

const WARMUP: u32 = 20;
const ROUNDS: u32 = 100;

fn asset_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/src/main/assets/keyboard_dictionary.bin")
}

fn main() {
    let path = asset_path();
    let metadata = std::fs::metadata(&path).expect("shipped asset must exist");

    let started = Instant::now();
    let dictionary = MappedDictionary::load(&path).expect("shipped asset must parse");
    let load = started.elapsed();

    println!("asset        : {:.1} KiB", metadata.len() as f64 / 1024.0);
    println!("entries      : {}", dictionary.entry_count());
    println!(
        "load (mmap + trie build): {:.2} ms",
        load.as_secs_f64() * 1e3
    );

    // Each scenario is timed on its own, because only some paths reach the
    // edit-distance scan over the whole trie:
    //   * prefix with plenty of matches  -> trie walk only
    //   * prefix with no matches         -> fuzzy fill
    //   * confident word                 -> exact lookup, early return
    //   * genuine typo                   -> fuzzy scan
    type Scenario = (&'static str, &'static dyn Fn(&MappedDictionary));
    let scenarios: [Scenario; 4] = [
        (
            "suggest 'wor' (prefix matches fill the row)",
            &|d: &MappedDictionary| {
                let _ = d.suggest(b"wor", "good", 5);
            },
        ),
        (
            "suggest 'recievv' (no matches, fuzzy fills)",
            &|d: &MappedDictionary| {
                let _ = d.suggest(b"recievv", "good", 5);
            },
        ),
        (
            "autocorrect 'hello' (confident, early return)",
            &|d: &MappedDictionary| {
                let _ = d.autocorrect(b"hello");
            },
        ),
        (
            "autocorrect 'recieve' (unknown, fuzzy scan)",
            &|d: &MappedDictionary| {
                let _ = d.autocorrect(b"recieve");
            },
        ),
    ];

    for (label, run) in scenarios {
        for _ in 0..WARMUP {
            run(&dictionary);
        }
        let started = Instant::now();
        for _ in 0..ROUNDS {
            run(&dictionary);
        }
        let per_call = started.elapsed().as_secs_f64() * 1e6 / ROUNDS as f64;
        println!("{label:<50} {per_call:>9.1} µs/call");
    }
}
