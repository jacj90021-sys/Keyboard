//! Integration test: the public API must be usable from outside the crate, and
//! the shipped dictionary asset must round-trip through it.

use keyboard_core::MappedDictionary;
use std::path::Path;

#[test]
fn shipped_asset_is_usable_through_the_public_api() {
    let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../app/src/main/assets/keyboard_dictionary.bin");
    if !asset.exists() {
        // A crate-only checkout (no app/) simply skips this check.
        return;
    }

    let dictionary = MappedDictionary::load(&asset).expect("shipped asset must parse");

    assert!(dictionary.entry_count() > 100);
    assert!(dictionary.exact_match(b"the").is_some());
    assert!(!dictionary.suggest(b"th", "", 4).is_empty());
    assert!(!dictionary.suggest(b"", "good", 4).is_empty());
}
