//! Host benchmark for the hot query path. Not shipped; used to validate that
//! per-keystroke work is cheap enough to run without blocking a frame, and to
//! quantify the fix when it is not.
//!
//! Run: cargo run --release --example bench -- <path-to-keyboard_dictionary.bin>

use std::path::Path;
use std::time::Instant;

use keyboard_core::{MappedDictionary, PromotionTable};

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: bench <dictionary.bin>");

    let t = Instant::now();
    let dict = MappedDictionary::load(Path::new(&path)).expect("load dictionary");
    println!("load (mmap + index + automaton build): {:?}", t.elapsed());

    let promos = PromotionTable::new();
    let n = 5_000u32;

    println!("\n-- suggest(prefix, \"\", 4) --");
    for prefix in [
        "t", "th", "the", "hel", "hell", "hello", "wr", "wro", "goo", "qzwx", "zzz", "int",
    ] {
        let _ = dict.suggest_ranked(prefix.as_bytes(), "", 4, &promos); // warm
        let t = Instant::now();
        for _ in 0..n {
            let _ = dict.suggest_ranked(prefix.as_bytes(), "", 4, &promos);
        }
        println!(
            "  {:>6}: {:>10.2} us/call",
            format!("{prefix:?}"),
            t.elapsed().as_secs_f64() * 1e6 / n as f64
        );
    }

    println!("\n-- suggest with previous word (next-word) --");
    for prefix in ["", "t", "th"] {
        let _ = dict.suggest_ranked(prefix.as_bytes(), "the", 4, &promos);
        let t = Instant::now();
        for _ in 0..n {
            let _ = dict.suggest_ranked(prefix.as_bytes(), "the", 4, &promos);
        }
        println!(
            "  {:>6}: {:>10.2} us/call",
            format!("{prefix:?}"),
            t.elapsed().as_secs_f64() * 1e6 / n as f64
        );
    }

    println!("\n-- autocorrect(word) [runs on every space] --");
    for w in [
        "teh",
        "hte",
        "recieve",
        "definately",
        "helllo",
        "wrold",
        "the",
        "hello",
    ] {
        let _ = dict.autocorrect_ranked(w.as_bytes(), &promos);
        let t = Instant::now();
        for _ in 0..n {
            let _ = dict.autocorrect_ranked(w.as_bytes(), &promos);
        }
        println!(
            "  {:>12}: {:>10.2} us/call",
            format!("{w:?}"),
            t.elapsed().as_secs_f64() * 1e6 / n as f64
        );
    }
}
