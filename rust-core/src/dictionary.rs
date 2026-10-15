use std::collections::HashMap;
use std::ffi::CString;
use std::path::Path;

use crate::automaton::Automaton;

const MAGIC: &[u8; 4] = b"PLKD";
const VERSION: u32 = 1;

#[derive(Debug, Clone, Copy)]
pub struct EntryIndex {
    pub key_offset: usize,
    pub key_len: usize,
    pub value_offset: usize,
    pub value_len: usize,
}

#[derive(Debug)]
pub enum DictionaryError {
    Io(String),
    InvalidFormat(String),
    Mmap(String),
}

pub struct MappedDictionary {
    mapped_ptr: *mut u8,
    mapped_size: usize,
    entries: Vec<EntryIndex>,
    automaton: Automaton,
}

static CONTEXT_TRANSITIONS: &[(&str, &str, u32)] = &[
    // determiners / particles
    ("of", "the", 10),
    ("in", "the", 10),
    ("on", "the", 9),
    ("at", "the", 8),
    ("for", "the", 8),
    ("with", "the", 7),
    // pronoun + auxiliary
    ("i", "am", 12),
    ("i", "have", 9),
    ("i", "will", 9),
    ("i", "think", 10),
    ("i", "know", 10),
    ("i", "want", 10),
    ("i", "need", 10),
    ("i", "like", 9),
    ("i", "love", 8),
    ("we", "are", 11),
    ("we", "can", 10),
    ("we", "need", 10),
    ("we", "should", 9),
    ("we", "will", 9),
    ("you", "are", 11),
    ("you", "can", 10),
    ("you", "know", 9),
    ("you", "want", 9),
    ("you", "need", 9),
    ("they", "are", 9),
    ("they", "will", 7),
    ("he", "is", 9),
    ("she", "is", 9),
    // copulas / questions
    ("it", "is", 12),
    ("it", "was", 9),
    ("that", "is", 11),
    ("this", "is", 11),
    ("there", "is", 10),
    ("there", "are", 9),
    ("what", "is", 10),
    ("what", "are", 9),
    ("who", "is", 9),
    ("where", "is", 8),
    ("when", "is", 7),
    ("why", "not", 8),
    ("is", "it", 9),
    ("is", "that", 8),
    ("is", "not", 8),
    ("how", "are", 9),
    ("how", "do", 9),
    ("how", "can", 8),
    ("how", "much", 9),
    ("how", "many", 8),
    // requests / politeness
    ("thank", "you", 12),
    ("thanks", "again", 8),
    ("please", "send", 8),
    ("please", "call", 7),
    ("hello", "world", 12),
    ("hello", "there", 9),
    ("hello", "everyone", 8),
    ("good", "morning", 11),
    ("good", "night", 10),
    ("good", "day", 8),
    ("sorry", "about", 8),
    ("excuse", "me", 9),
    ("you", "welcome", 8),
    ("no", "problem", 9),
    ("no", "thanks", 8),
    ("yes", "please", 9),
    // helpers
    ("let", "me", 10),
    ("let", "us", 8),
    ("give", "me", 9),
    ("tell", "me", 10),
    ("help", "me", 10),
    ("show", "me", 8),
    ("call", "me", 8),
    ("email", "me", 8),
    ("meet", "you", 8),
    ("see", "you", 10),
    // verbs + to
    ("want", "to", 10),
    ("need", "to", 10),
    ("have", "to", 10),
    ("like", "to", 9),
    ("try", "to", 8),
    ("talk", "to", 8),
    ("speak", "to", 8),
    ("listen", "to", 8),
    ("go", "to", 8),
    ("come", "to", 7),
    ("to", "be", 10),
    ("to", "do", 9),
    ("to", "go", 8),
    ("to", "see", 8),
    ("to", "know", 8),
    // movement
    ("come", "back", 9),
    ("come", "here", 8),
    ("go", "back", 8),
    ("go", "away", 5),
    ("wait", "for", 8),
    // look / feel / find
    ("look", "at", 8),
    ("look", "for", 8),
    ("look", "like", 8),
    ("feel", "good", 8),
    ("feel", "like", 8),
    ("find", "the", 7),
    ("find", "it", 7),
    // time
    ("right", "now", 9),
    ("just", "now", 7),
    ("not", "now", 6),
    ("next", "time", 8),
    ("last", "time", 8),
    ("one", "day", 7),
    ("maybe", "today", 7),
    ("maybe", "tomorrow", 7),
    // work / daily
    ("work", "from", 6),
    ("work", "with", 6),
    ("work", "for", 6),
    ("get", "the", 6),
    ("make", "it", 7),
    ("make", "sure", 8),
    ("take", "it", 7),
    // the classic pangram
    ("the", "quick", 12),
    ("the", "lazy", 8),
    ("quick", "brown", 11),
    ("brown", "fox", 11),
    ("fox", "jumps", 11),
    ("jumps", "over", 10),
    ("over", "the", 10),
    ("lazy", "dog", 10),
];

/// Bigram weight table, sorted once on first use so lookups are a binary search
/// instead of a linear scan over the literal above.
fn context_table() -> &'static [(&'static str, &'static str, u64)] {
    static TABLE: std::sync::OnceLock<Vec<(&'static str, &'static str, u64)>> =
        std::sync::OnceLock::new();
    TABLE.get_or_init(|| {
        let mut rows: Vec<(&'static str, &'static str, u64)> = CONTEXT_TRANSITIONS
            .iter()
            .map(|(left, right, score)| (*left, *right, *score as u64))
            .collect();
        rows.sort_by(|a, b| a.0.cmp(b.0).then_with(|| a.1.cmp(b.1)));
        rows.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);
        rows
    })
}

fn context_boost(previous: &[u8], candidate: &[u8]) -> u64 {
    let table = context_table();
    table
        .binary_search_by(|probe| {
            probe
                .0
                .as_bytes()
                .cmp(previous)
                .then_with(|| probe.1.as_bytes().cmp(candidate))
        })
        .map(|index| table[index].2)
        .unwrap_or(0)
}

/// Parse a stored value as a non-negative frequency. Values are advisory: a
/// malformed value ranks last instead of failing the whole dictionary.
fn frequency_value(raw: &[u8]) -> u64 {
    std::str::from_utf8(raw)
        .ok()
        .and_then(|text| text.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// Smallest possible on-disk size of one entry: 4-byte length header plus the
/// required (non-empty) key. Used to bound `entry_count` before allocating.
const MIN_ENTRY_BYTES: usize = 5;

/// Upper bound on an individual word's accumulated promotion so a pathological
/// stream of taps cannot overflow or completely dominate frequency scores.
const MAX_PROMOTION: u64 = 1_000_000;

/// Header written at the top of a persisted learned-words file.
const PROMOTIONS_MAGIC: &str = "PLKP1";

/// Bound on a learned word and on the total table size, so a corrupt or hostile
/// file cannot exhaust memory.
const MAX_LEARNED_WORD_BYTES: usize = 128;
const MAX_LEARNED_WORDS: usize = 20_000;

/// Autocorrect is deliberately conservative: it only ever fires on words the
/// user clearly mistyped, and never fights a deliberate choice. These
/// thresholds are stated against the shipped frequency scale (roughly 0..1000,
/// plus learned boosts), not against raw corpus counts.
///
/// Short words are left alone: correcting a 1-2 letter token is far more
/// likely to annoy than help.
const AUTOCORRECT_MIN_LEN: usize = 3;

/// A typed word scoring at least this high (frequency plus any learned boost)
/// is treated as intentional and is never replaced.
const AUTOCORRECT_CONFIDENT: u64 = 200;

/// How much more frequent the replacement must be than a *real* word that was
/// typed before the swap is judged worthwhile. Only applied when the typed word
/// is itself in the dictionary; an unknown word just has to beat the floor.
const AUTOCORRECT_MARGIN: u64 = 50;

/// A replacement must clear this frequency so a real word is never corrected
/// into an obscure dictionary entry. Scaled to the shipped word list, where
/// everyday words sit anywhere from ~20 to ~1000.
const AUTOCORRECT_FLOOR: u64 = 20;

/// Runtime word preferences learned from the user: accepted suggestions and
/// correctly typed words. Kept separate from the immutable mapped dictionary so
/// it can change without re-mapping, and so ranking stays deterministic and
/// independently testable when no table is supplied.
#[derive(Debug, Default)]
pub struct PromotionTable {
    boosts: HashMap<Vec<u8>, u64>,
}

impl PromotionTable {
    pub fn new() -> Self {
        Self {
            boosts: HashMap::new(),
        }
    }

    /// Increase a word's rank by `step`, saturating at [`MAX_PROMOTION`].
    pub fn promote(&mut self, word: &[u8], step: u64) {
        if word.is_empty() || step == 0 {
            return;
        }
        let entry = self.boosts.entry(word.to_vec()).or_insert(0);
        *entry = entry.saturating_add(step).min(MAX_PROMOTION);
    }

    pub fn boost(&self, word: &[u8]) -> u64 {
        self.boosts.get(word).copied().unwrap_or(0)
    }

    pub fn clear(&mut self) {
        self.boosts.clear();
    }

    pub fn len(&self) -> usize {
        self.boosts.len()
    }

    pub fn is_empty(&self) -> bool {
        self.boosts.is_empty()
    }

    /// Serialize to a small, deterministic, human-readable text form:
    /// a magic header line followed by `<boost>\t<word>` lines sorted by word.
    pub fn serialize(&self) -> String {
        let mut rows: Vec<(&Vec<u8>, &u64)> = self.boosts.iter().collect();
        rows.sort_by(|a, b| a.0.cmp(b.0));

        let mut output = String::from(PROMOTIONS_MAGIC);
        output.push('\n');
        for (word, boost) in rows {
            let Ok(text) = std::str::from_utf8(word) else {
                continue;
            };
            if text.is_empty() || text.contains(['\t', '\n', '\r']) {
                continue;
            }
            output.push_str(&boost.to_string());
            output.push('\t');
            output.push_str(text);
            output.push('\n');
        }
        output
    }

    /// Replace the table with the contents of [text]. Malformed lines are
    /// ignored, not fatal, so a partially damaged file still loads what it can.
    /// Returns the number of words read.
    pub fn load_from_str(&mut self, text: &str) -> usize {
        self.boosts.clear();
        let mut loaded = 0usize;
        for (line_number, line) in text.lines().enumerate() {
            if line_number == 0 && line.trim_end_matches('\r') == PROMOTIONS_MAGIC {
                continue;
            }
            let Some((boost_text, word)) = line.split_once('\t') else {
                continue;
            };
            let word = word.trim_end_matches('\r');
            if word.is_empty() || word.len() > MAX_LEARNED_WORD_BYTES {
                continue;
            }
            // Parse wide and clamp, so an over-large value still loads instead of
            // being discarded as unparsable.
            let Ok(boost) = boost_text.parse::<u128>() else {
                continue;
            };
            self.boosts.insert(
                word.as_bytes().to_vec(),
                boost.min(MAX_PROMOTION as u128) as u64,
            );
            loaded += 1;
            if loaded >= MAX_LEARNED_WORDS {
                break;
            }
        }
        loaded
    }

    /// Atomically persist the table: write a sibling temp file, then rename.
    pub fn save_to_file(&self, path: &Path) -> std::io::Result<()> {
        let temporary = path.with_extension("tmp");
        std::fs::write(&temporary, self.serialize().as_bytes())?;
        std::fs::rename(&temporary, path)
    }

    pub fn load_from_file(&mut self, path: &Path) -> std::io::Result<usize> {
        let text = std::fs::read_to_string(path)?;
        Ok(self.load_from_str(&text))
    }
}

impl MappedDictionary {
    pub fn load(path: &Path) -> Result<Self, DictionaryError> {
        // Validate and mmap the source file. Header parsing is deliberately simple
        // so corrupt caller input cannot escape the mapped buffer.
        let c_path = path
            .to_str()
            .and_then(|s| CString::new(s).ok())
            .ok_or_else(|| DictionaryError::Io("path is not valid UTF-8".to_string()))?;

        let fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDONLY, 0) };
        if fd < 0 {
            return Err(DictionaryError::Io("unable to open dictionary".to_string()));
        }

        let mut stat: libc::stat = unsafe { std::mem::zeroed() };
        if unsafe { libc::fstat(fd, &mut stat) } != 0 {
            unsafe { libc::close(fd) };
            return Err(DictionaryError::Io("unable to stat dictionary".to_string()));
        }
        let mapped_size = stat.st_size as usize;
        if mapped_size < 12 {
            unsafe { libc::close(fd) };
            return Err(DictionaryError::InvalidFormat(
                "dictionary is too small".to_string(),
            ));
        }

        let mapped_ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                mapped_size,
                libc::PROT_READ,
                libc::MAP_SHARED,
                fd,
                0,
            )
        };
        unsafe { libc::close(fd) };

        if mapped_ptr == libc::MAP_FAILED {
            return Err(DictionaryError::Mmap(
                "unable to map dictionary".to_string(),
            ));
        }

        let data = unsafe { std::slice::from_raw_parts(mapped_ptr as *const u8, mapped_size) };
        if &data[0..4] != MAGIC {
            unsafe { libc::munmap(mapped_ptr, mapped_size) };
            return Err(DictionaryError::InvalidFormat(
                "bad dictionary magic".to_string(),
            ));
        }

        let version = u32::from_le_bytes(data[4..8].try_into().unwrap_or([0; 4]));
        if version != VERSION {
            unsafe { libc::munmap(mapped_ptr, mapped_size) };
            return Err(DictionaryError::InvalidFormat(
                "unsupported dictionary version".to_string(),
            ));
        }

        let entry_count = u32::from_le_bytes(data[8..12].try_into().unwrap_or([0; 4])) as usize;

        // Bound the entry count against the file size before allocating. This
        // keeps a corrupt header from triggering a huge allocation or a long
        // scan: every entry needs at least MIN_ENTRY_BYTES bytes.
        let max_possible_entries = (mapped_size - 12) / MIN_ENTRY_BYTES;
        if entry_count > max_possible_entries {
            unsafe { libc::munmap(mapped_ptr, mapped_size) };
            return Err(DictionaryError::InvalidFormat(
                "entry count exceeds file size".to_string(),
            ));
        }

        let mut cursor = 12usize;
        let mut entries = Vec::with_capacity(entry_count);

        for _ in 0..entry_count {
            if cursor + 4 > mapped_size {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat(
                    "truncated entry header".to_string(),
                ));
            }
            let key_len =
                u16::from_le_bytes(data[cursor..cursor + 2].try_into().unwrap_or([0; 2])) as usize;
            let value_len =
                u16::from_le_bytes(data[cursor + 2..cursor + 4].try_into().unwrap_or([0; 2]))
                    as usize;
            cursor += 4;

            let key_offset = cursor;
            if key_len == 0 || cursor + key_len > mapped_size {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat(
                    "invalid key bounds".to_string(),
                ));
            }
            cursor += key_len;

            let value_offset = cursor;
            if cursor + value_len > mapped_size {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat(
                    "invalid value bounds".to_string(),
                ));
            }
            cursor += value_len;

            entries.push(EntryIndex {
                key_offset,
                key_len,
                value_offset,
                value_len,
            });
        }

        // Require strict lexicographic order so callers can perform binary search.
        // Empty dictionaries are allowed for incremental loading scenarios.
        for index in 1..entries.len() {
            let previous = &entries[index - 1];
            let current = &entries[index];
            let previous_key = &data[previous.key_offset..previous.key_offset + previous.key_len];
            let current_key = &data[current.key_offset..current.key_offset + current.key_len];
            if previous_key >= current_key {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat(
                    "dictionary keys are not sorted".to_string(),
                ));
            }
        }

        let automaton = {
            let keys: Vec<&[u8]> = entries
                .iter()
                .map(|entry| &data[entry.key_offset..entry.key_offset + entry.key_len])
                .collect();
            Automaton::build(&keys)
        };

        Ok(Self {
            mapped_ptr: mapped_ptr as *mut u8,
            mapped_size,
            entries,
            automaton,
        })
    }

    pub fn entry_count(&self) -> usize {
        self.entries.len()
    }

    pub fn key_at(&self, index: usize) -> Option<&[u8]> {
        let entry = self.entries.get(index)?;
        Some(unsafe {
            std::slice::from_raw_parts(self.mapped_ptr.add(entry.key_offset), entry.key_len)
        })
    }

    pub fn value_at(&self, index: usize) -> Option<&[u8]> {
        let entry = self.entries.get(index)?;
        Some(unsafe {
            std::slice::from_raw_parts(self.mapped_ptr.add(entry.value_offset), entry.value_len)
        })
    }

    /// Convenience accessor that decodes the entry's stored frequency.
    pub fn frequency_at(&self, index: usize) -> u64 {
        self.value_at(index).map(frequency_value).unwrap_or(0)
    }

    pub fn exact_match(&self, key: &[u8]) -> Option<usize> {
        let (mut low, mut high) = (0usize, self.entries.len());
        while low < high {
            let middle = low + ((high - low) >> 1);
            let candidate = self.key_at(middle).unwrap_or_default();
            match candidate.cmp(key) {
                std::cmp::Ordering::Less => low = middle + 1,
                std::cmp::Ordering::Greater => high = middle,
                std::cmp::Ordering::Equal => return Some(middle),
            }
        }
        None
    }

    pub fn prefix_start(&self, prefix: &[u8]) -> usize {
        // Lower-bound prefix search without allocating a full suffix.
        let mut low = 0usize;
        let mut high = self.entries.len();
        while low < high {
            let middle = low + ((high - low) >> 1);
            let key = self.key_at(middle).unwrap_or_default();
            if key < prefix {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        low
    }

    pub fn prefix_end(&self, prefix: &[u8]) -> usize {
        // Compute the first index whose key no longer starts with prefix.
        let mut extended = Vec::with_capacity(prefix.len() + 1);
        extended.extend_from_slice(prefix);
        extended.push(u8::MAX);
        let mut low = 0usize;
        let mut high = self.entries.len();
        while low < high {
            let middle = low + ((high - low) >> 1);
            let key = self.key_at(middle).unwrap_or_default();
            if key < extended.as_slice() {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        low
    }

    pub fn top_prefixes(&self, prefix: &[u8], max: usize) -> Vec<usize> {
        self.top_prefixes_ranked(prefix, max, &PromotionTable::new())
    }

    pub fn top_prefixes_ranked(
        &self,
        prefix: &[u8],
        max: usize,
        promotions: &PromotionTable,
    ) -> Vec<usize> {
        let start = self.prefix_start(prefix);
        let end = self.prefix_end(prefix);
        let mut candidates: Vec<(usize, u64)> = (start..end)
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let score = self.frequency_at(index) + promotions.boost(key);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates
            .into_iter()
            .take(max)
            .map(|(index, _)| index)
            .collect()
    }

    pub fn top_prefixes_contextual(&self, prefix: &[u8], previous: &str, max: usize) -> Vec<usize> {
        self.top_prefixes_contextual_ranked(prefix, previous, max, &PromotionTable::new())
    }

    pub fn top_prefixes_contextual_ranked(
        &self,
        prefix: &[u8],
        previous: &str,
        max: usize,
        promotions: &PromotionTable,
    ) -> Vec<usize> {
        let start = self.prefix_start(prefix);
        let end = self.prefix_end(prefix);
        let mut candidates: Vec<(usize, u64)> = (start..end)
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let exactness = if key == prefix { 20u64 } else { 0u64 };
                let frequency = self.frequency_at(index);
                let score = frequency
                    + exactness
                    + context_boost(previous.as_bytes(), key)
                    + promotions.boost(key);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates
            .into_iter()
            .take(max)
            .map(|(index, _)| index)
            .collect()
    }

    pub fn top_next_words(&self, previous: &str, max: usize) -> Vec<usize> {
        self.top_next_words_ranked(previous, max, &PromotionTable::new())
    }

    pub fn top_next_words_ranked(
        &self,
        previous: &str,
        max: usize,
        promotions: &PromotionTable,
    ) -> Vec<usize> {
        let previous_bytes = previous.as_bytes();
        // Rank by context first, then frequency. A transition hit should beat a
        // merely common word, otherwise next-word hints would just echo the most
        // frequent entries ("the", "be", ...) regardless of what was typed.
        let mut candidates: Vec<(usize, u64, u64)> = (0..self.entry_count())
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let boost = context_boost(previous_bytes, key);
                let frequency = self.frequency_at(index) + promotions.boost(key);
                (index, boost, frequency)
            })
            .collect();

        candidates.sort_by(|a, b| {
            b.1.cmp(&a.1)
                .then_with(|| b.2.cmp(&a.2))
                .then_with(|| a.0.cmp(&b.0))
        });
        candidates
            .into_iter()
            .take(max)
            .map(|(index, _, _)| index)
            .collect()
    }

    /// Fused suggestion query used by the Android boundary.
    ///
    /// While a word of at least two characters is being typed, prefix matches
    /// rank first and spelling corrections fill any remaining slots. With an
    /// empty prefix after a committed word, likely next words are returned.
    /// This keeps the whole ranking policy in one place (here) instead of
    /// splitting it across the Kotlin layer.
    pub fn suggest(&self, prefix: &[u8], previous: &str, max: usize) -> Vec<usize> {
        self.suggest_ranked(prefix, previous, max, &PromotionTable::new())
    }

    pub fn suggest_ranked(
        &self,
        prefix: &[u8],
        previous: &str,
        max: usize,
        promotions: &PromotionTable,
    ) -> Vec<usize> {
        if max == 0 {
            return Vec::new();
        }

        if prefix.len() >= 2 {
            let mut selected =
                self.top_prefixes_contextual_ranked(prefix, previous, max, promotions);
            if selected.len() < max {
                for index in self.nearest_words_ranked(prefix, max, 2, promotions) {
                    if selected.len() >= max {
                        break;
                    }
                    if !selected.contains(&index) {
                        selected.push(index);
                    }
                }
            }
            selected
        } else if !previous.is_empty() {
            self.top_next_words_ranked(previous, max, promotions)
        } else {
            Vec::new()
        }
    }

    pub fn nearest_words(&self, typo: &[u8], max: usize, max_distance: usize) -> Vec<usize> {
        self.nearest_words_ranked(typo, max, max_distance, &PromotionTable::new())
    }

    pub fn nearest_words_ranked(
        &self,
        typo: &[u8],
        max: usize,
        max_distance: usize,
        promotions: &PromotionTable,
    ) -> Vec<usize> {
        let mut matches: Vec<(usize, usize, u64)> = self
            .automaton
            .fuzzy(typo, max_distance)
            .into_iter()
            .filter_map(|(entry_index, distance)| {
                let idx = entry_index as usize;
                let key = self.key_at(idx)?;
                Some((
                    idx,
                    distance,
                    self.frequency_at(idx) + promotions.boost(key),
                ))
            })
            .collect();

        // Prefer the smallest edit distance; break ties by frequency so a common
        // word beats a rare one, then fall back to dictionary order for stability.
        matches.sort_by(|a, b| {
            a.1.cmp(&b.1)
                .then_with(|| b.2.cmp(&a.2))
                .then_with(|| a.0.cmp(&b.0))
        });
        matches
            .into_iter()
            .take(max)
            .map(|(index, _, _)| index)
            .collect()
    }

    /// Decide the single best auto-replacement for a just-typed word, or `None`
    /// to leave it untouched. Used on word commit (e.g. tapping space).
    ///
    /// The policy is intentionally cautious:
    /// * only plain lowercase words of [`AUTOCORRECT_MIN_LEN`] or longer qualify;
    /// * a word that is already frequent (or the user has taught us) is trusted;
    /// * a replacement must be closer in edit distance, meaningfully more
    ///   frequent, and clear the frequency floor.
    pub fn autocorrect(&self, typed: &[u8]) -> Option<usize> {
        self.autocorrect_ranked(typed, &PromotionTable::new())
    }

    pub fn autocorrect_ranked(&self, typed: &[u8], promotions: &PromotionTable) -> Option<usize> {
        if typed.len() < AUTOCORRECT_MIN_LEN {
            return None;
        }
        // Only bare lowercase letters are eligible; anything with digits,
        // apostrophes, or capitals is left for the caller to handle.
        if !typed.iter().all(u8::is_ascii_lowercase) {
            return None;
        }

        let typed_match = self.exact_match(typed);
        let typed_score = typed_match
            .map(|index| self.frequency_at(index))
            .unwrap_or(0)
            + promotions.boost(typed);
        if typed_score >= AUTOCORRECT_CONFIDENT {
            return None;
        }

        // Against a real (but rare) word, demand a clear frequency gain; against
        // an unknown token, only require the replacement to clear the floor.
        let min_score = if typed_match.is_some() {
            typed_score.saturating_add(AUTOCORRECT_MARGIN)
        } else {
            AUTOCORRECT_FLOOR
        };

        // A transposition ("teh" -> "the") is the most common slip and counts
        // as *two* Levenshtein edits, so the radius has to be 2 to catch it.
        // The floor/margin above stop this from over-reaching.
        let radius = 2;

        let mut candidates: Vec<(usize, usize, u64)> = self
            .automaton
            .fuzzy(typed, radius)
            .into_iter()
            .filter_map(|(entry_index, distance)| {
                let index = entry_index as usize;
                let key = self.key_at(index)?;
                if key == typed {
                    return None; // never "correct" to the word already typed
                }
                let score = self.frequency_at(index) + promotions.boost(key);
                if score < min_score {
                    return None;
                }
                Some((index, distance, score))
            })
            .collect();

        // Closest edit first, then most frequent, then dictionary order so the
        // result is deterministic.
        candidates.sort_by(|a, b| {
            a.1.cmp(&b.1)
                .then_with(|| b.2.cmp(&a.2))
                .then_with(|| a.0.cmp(&b.0))
        });
        candidates.first().map(|(index, _, _)| *index)
    }
}

#[allow(dead_code)]
fn levenshtein_distance(left: &[u8], right: &[u8]) -> usize {
    if left.is_empty() {
        return right.len();
    }
    if right.is_empty() {
        return left.len();
    }

    let mut previous: Vec<usize> = (0..=right.len()).collect();
    let mut current = vec![0usize; right.len() + 1];

    for (i, left_byte) in left.iter().enumerate() {
        current[0] = i + 1;
        let mut minimum = current[0];
        for (j, right_byte) in right.iter().enumerate() {
            let deletion = previous[j + 1] + 1;
            let insertion = current[j] + 1;
            let substitution = previous[j] + usize::from(left_byte != right_byte);
            current[j + 1] = deletion.min(insertion).min(substitution);
            minimum = minimum.min(current[j + 1]);
        }
        std::mem::swap(&mut previous, &mut current);
    }

    previous[right.len()]
}

impl Drop for MappedDictionary {
    fn drop(&mut self) {
        if !self.mapped_ptr.is_null() && self.mapped_size > 0 {
            unsafe { libc::munmap(self.mapped_ptr as *mut std::ffi::c_void, self.mapped_size) };
        }
    }
}

unsafe impl Send for MappedDictionary {}
unsafe impl Sync for MappedDictionary {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn temp_path(name: &str) -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(1);
        let counter = COUNTER.fetch_add(1, Ordering::Relaxed);
        PathBuf::from(format!("/tmp/paperlime-dict-{}-{}.bin", name, counter))
    }

    fn write_dictionary(path: &PathBuf, entries: &[(&[u8], &[u8])]) {
        let mut data = Vec::new();
        data.extend_from_slice(MAGIC);
        data.extend_from_slice(&VERSION.to_le_bytes());
        data.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for (key, value) in entries {
            data.extend_from_slice(&(key.len() as u16).to_le_bytes());
            data.extend_from_slice(&(value.len() as u16).to_le_bytes());
            data.extend_from_slice(key);
            data.extend_from_slice(value);
        }
        let mut file = std::fs::File::create(path).unwrap();
        file.write_all(&data).unwrap();
        file.sync_all().unwrap();
    }

    #[test]
    fn loads_and_matches_exact_keys() {
        let path = temp_path("exact");
        write_dictionary(
            &path,
            &[(b"alpha", b"10"), (b"beta", b"20"), (b"gamma", b"30")],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        assert_eq!(dictionary.entry_count(), 3);
        assert_eq!(dictionary.exact_match(b"beta"), Some(1));
        assert_eq!(dictionary.value_at(1).unwrap(), b"20");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn prefix_range_returns_matching_words() {
        let path = temp_path("prefix");
        write_dictionary(
            &path,
            &[
                (b"car", b"1"),
                (b"card", b"2"),
                (b"care", b"3"),
                (b"dog", b"4"),
            ],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        let start = dictionary.prefix_start(b"car");
        let end = dictionary.prefix_end(b"car");
        assert_eq!(start, 0);
        assert_eq!(end, 3);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn nearest_words_returns_sorted_close_matches() {
        let path = temp_path("corrections");
        write_dictionary(
            &path,
            &[
                (b"card", b"5"),
                (b"care", b"1"),
                (b"cash", b"50"),
                (b"dog", b"4"),
            ],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        let matches = dictionary.nearest_words(b"cahd", 4, 2);
        let keys: Vec<&[u8]> = matches
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        // distance 1: card. distance 2: cash (freq 50) then care (freq 1).
        // Index order would put care first, so this pins the frequency tie-break.
        assert_eq!(
            keys,
            vec![b"card".as_slice(), b"cash".as_slice(), b"care".as_slice()]
        );
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn autocorrects_a_clear_typo_to_the_common_word() {
        let path = temp_path("autocorrect-typo");
        write_dictionary(
            &path,
            &[(b"hte", b"3"), (b"she", b"120"), (b"the", b"1000")],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        let fixed = dictionary
            .autocorrect(b"hte")
            .map(|index| dictionary.key_at(index).unwrap());
        assert_eq!(fixed, Some(b"the".as_slice()));
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn leaves_a_confident_word_alone() {
        let path = temp_path("autocorrect-confident");
        write_dictionary(
            &path,
            &[(b"the", b"1000"), (b"tho", b"400"), (b"thy", b"500")],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        // "the" is already dominant, so nothing should replace it.
        assert_eq!(dictionary.autocorrect(b"the"), None);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn autocorrect_ignores_short_and_non_letter_tokens() {
        let path = temp_path("autocorrect-guards");
        write_dictionary(&path, &[(b"as", b"700"), (b"at", b"900"), (b"it", b"800")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        assert_eq!(dictionary.autocorrect(b"at"), None); // too short
        assert_eq!(dictionary.autocorrect(b"t'e"), None); // non-letter
        assert_eq!(dictionary.autocorrect(b"The"), None); // capitals
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn autocorrect_requires_a_meaningful_frequency_gain() {
        let path = temp_path("autocorrect-margin");
        // Both real words, below the confident bar and close in frequency: no
        // gain large enough to justify a swap.
        write_dictionary(&path, &[(b"form", b"150"), (b"from", b"180")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        // "form" is a real word and "from" does not beat it by the margin, so
        // the user's word survives.
        assert_eq!(dictionary.autocorrect(b"form"), None);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn autocorrect_respects_learned_boosts() {
        let path = temp_path("autocorrect-learned");
        write_dictionary(&path, &[(b"teh", b"5"), (b"the", b"1000")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        // Naively "teh" would be corrected to "the"...
        assert_eq!(
            dictionary
                .autocorrect(b"teh")
                .map(|i| dictionary.key_at(i).unwrap()),
            Some(b"the".as_slice())
        );

        // ...but once the user has taught us "teh", it is trusted.
        let mut promotions = PromotionTable::new();
        promotions.promote(b"teh", 400);
        assert_eq!(dictionary.autocorrect_ranked(b"teh", &promotions), None);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_malformed_dictionary_headers() {
        let path = temp_path("bad-header");
        std::fs::write(&path, b"NOPE").unwrap();
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn accepts_empty_dictionary() {
        let path = temp_path("empty");
        let mut data = Vec::new();
        data.extend_from_slice(b"PLKD");
        data.extend_from_slice(&1u32.to_le_bytes());
        data.extend_from_slice(&0u32.to_le_bytes());
        std::fs::write(&path, &data).unwrap();
        let dictionary = MappedDictionary::load(&path).unwrap();
        assert_eq!(dictionary.entry_count(), 0);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_zero_length_keys() {
        let path = temp_path("zero-key");
        let mut data = Vec::new();
        data.extend_from_slice(b"PLKD");
        data.extend_from_slice(&1u32.to_le_bytes());
        data.extend_from_slice(&1u32.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&1u16.to_le_bytes());
        data.extend_from_slice(b"x");
        std::fs::write(&path, &data).unwrap();
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn levenshtein_distance_is_reflexive_and_bounded() {
        assert_eq!(levenshtein_distance(b"keyboard", b"keyboard"), 0);
        assert_eq!(levenshtein_distance(b"a", b""), 1);
        assert_eq!(levenshtein_distance(b"ab", b"ba"), 2);
    }

    #[test]
    fn contextual_boost_changes_candidate_order() {
        let path = temp_path("context");
        write_dictionary(&path, &[(b"moon", b"1"), (b"morning", b"1")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        let boosted = dictionary.top_prefixes_contextual(b"mo", "good", 2);
        let keys: Vec<&[u8]> = boosted
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        assert_eq!(keys.first().copied(), Some(b"morning".as_slice()));
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn next_word_transition_prefers_boosted_match() {
        let path = temp_path("next-word");
        write_dictionary(&path, &[(b"moon", b"1000"), (b"morning", b"1")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        let next = dictionary.top_next_words("good", 2);
        let keys: Vec<&[u8]> = next
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        // "morning" is far rarer, but the good->morning transition must win.
        assert_eq!(keys.first().copied(), Some(b"morning".as_slice()));
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn next_word_without_context_falls_back_to_frequency() {
        let path = temp_path("next-word-freq");
        write_dictionary(&path, &[(b"moon", b"1000"), (b"morning", b"1")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        let next = dictionary.top_next_words("unrelated", 2);
        let keys: Vec<&[u8]> = next
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        assert_eq!(keys, vec![b"moon".as_slice(), b"morning".as_slice()]);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_unsorted_keys() {
        let path = temp_path("unsorted");
        write_dictionary(&path, &[(b"beta", b"1"), (b"alpha", b"2")]);
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_duplicate_keys() {
        let path = temp_path("duplicate");
        write_dictionary(&path, &[(b"same", b"1"), (b"same", b"2")]);
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_entry_count_larger_than_file() {
        let path = temp_path("huge-count");
        let mut data = Vec::new();
        data.extend_from_slice(MAGIC);
        data.extend_from_slice(&VERSION.to_le_bytes());
        data.extend_from_slice(&0xFFFF_FFFFu32.to_le_bytes());
        data.extend_from_slice(&1u16.to_le_bytes());
        data.extend_from_slice(&1u16.to_le_bytes());
        data.extend_from_slice(b"a");
        data.extend_from_slice(b"1");
        std::fs::write(&path, &data).unwrap();
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_truncated_value_bounds() {
        let path = temp_path("truncated-value");
        let mut data = Vec::new();
        data.extend_from_slice(MAGIC);
        data.extend_from_slice(&VERSION.to_le_bytes());
        data.extend_from_slice(&1u32.to_le_bytes());
        data.extend_from_slice(&1u16.to_le_bytes());
        data.extend_from_slice(&9u16.to_le_bytes());
        data.extend_from_slice(b"a");
        data.extend_from_slice(b"1");
        std::fs::write(&path, &data).unwrap();
        assert!(MappedDictionary::load(&path).is_err());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn load_never_panics_on_random_bytes() {
        // Deterministic LCG so a failure is reproducible without extra deps.
        let mut state: u64 = 0x9E37_79B9_7F4A_7C15;
        for iteration in 0..512u32 {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            let len = ((state >> 33) as usize) % 256;
            let mut bytes = Vec::with_capacity(len);
            let mut local = state;
            for _ in 0..len {
                local = local.wrapping_mul(6364136223846793005).wrapping_add(1);
                bytes.push((local >> 40) as u8);
            }
            // Exercise the header path often, but not always.
            if iteration % 3 == 0 && bytes.len() >= 12 {
                bytes[0..4].copy_from_slice(MAGIC);
                bytes[4..8].copy_from_slice(&VERSION.to_le_bytes());
            }
            let path = temp_path("fuzz");
            std::fs::write(&path, &bytes).unwrap();
            let _ = MappedDictionary::load(&path); // must not panic or abort
            let _ = std::fs::remove_file(path);
        }
    }

    #[test]
    fn shipped_dictionary_asset_loads_if_present() {
        // Guards the repo asset against format drift: if tools/build_dictionary.py
        // writes something the engine cannot read, this fails locally.
        let asset = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../app/src/main/assets/keyboard_dictionary.bin");
        if !asset.exists() {
            return;
        }
        let dictionary = MappedDictionary::load(&asset).expect("shipped asset must parse");
        assert!(dictionary.entry_count() > 100);
        assert!(dictionary.exact_match(b"the").is_some());
        // Contractions are part of the word list now.
        assert!(dictionary.exact_match(b"don't").is_some());
        let top = dictionary.top_prefixes(b"th", 4);
        assert!(!top.is_empty());
        let don = dictionary.suggest(b"don", "", 3);
        assert_eq!(
            don.first().and_then(|&index| dictionary.key_at(index)),
            Some(b"don't".as_slice())
        );
    }

    #[test]
    fn context_table_is_sorted_and_hits_expected_pairs() {
        let table = context_table();
        assert!(
            table
                .windows(2)
                .all(|w| (w[0].0, w[0].1) < (w[1].0, w[1].1)),
            "context table must be strictly sorted after init"
        );
        assert!(context_boost(b"thank", b"you") > 0);
        assert!(context_boost(b"quick", b"brown") > 0);
        assert!(context_boost(b"good", b"morning") > 0);
        assert_eq!(context_boost(b"brown", b"sugar"), 0);
        assert_eq!(context_boost(b"", b""), 0);
    }

    #[test]
    fn handles_a_large_dictionary() {
        // ~20k synthetic entries plus a few planted words, to exercise the
        // binary-search paths at a scale far beyond the shipped asset.
        let mut owned: Vec<(Vec<u8>, Vec<u8>)> = Vec::new();
        for i in 0..20_000u32 {
            owned.push((format!("word{i:05}").into_bytes(), b"1".to_vec()));
        }
        for word in ["zeal", "zebra", "zoom"] {
            owned.push((word.as_bytes().to_vec(), b"7".to_vec()));
        }
        for i in 0..3u32 {
            owned.push((format!("zzz{i:05}").into_bytes(), b"1".to_vec()));
        }
        owned.sort_by(|a, b| a.0.cmp(&b.0));

        let path = temp_path("large");
        let refs: Vec<(&[u8], &[u8])> = owned
            .iter()
            .map(|(key, value)| (key.as_slice(), value.as_slice()))
            .collect();
        write_dictionary(&path, &refs);

        let dictionary = MappedDictionary::load(&path).unwrap();
        assert_eq!(dictionary.entry_count(), 20_006);
        assert!(dictionary.exact_match(b"word15000").is_some());
        assert!(dictionary.exact_match(b"word99999").is_none());

        // Prefix ranges must stay exact at scale.
        assert_eq!(dictionary.prefix_start(b"word0"), 0);
        assert_eq!(dictionary.prefix_end(b"word0"), 10_000);
        assert_eq!(dictionary.prefix_start(b"word15"), 15_000);
        assert_eq!(dictionary.prefix_end(b"word15"), 16_000);

        // Ties are broken by index, so the first match wins.
        let top = dictionary.top_prefixes(b"word123", 3);
        assert_eq!(top.first().copied(), Some(12_300));

        // Corrections still work with tens of thousands of entries.
        let corrections = dictionary.nearest_words(b"zebr", 4, 2);
        let first = corrections
            .first()
            .and_then(|&index| dictionary.key_at(index));
        assert_eq!(first, Some(b"zebra".as_slice()));

        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn suggest_fills_remaining_slots_with_corrections() {
        let path = temp_path("suggest");
        write_dictionary(
            &path,
            &[
                (b"gel", b"1"),
                (b"he", b"1"),
                (b"hello", b"5"),
                (b"help", b"10"),
            ],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        let keys: Vec<&[u8]> = dictionary
            .suggest(b"hel", "", 4)
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        // Prefix matches first (help, hello), then corrections (gel, he).
        assert_eq!(
            keys,
            vec![
                b"help".as_slice(),
                b"hello".as_slice(),
                b"gel".as_slice(),
                b"he".as_slice(),
            ]
        );
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn suggest_deduplicates_prefix_and_correction_overlap() {
        let path = temp_path("suggest-dedup");
        write_dictionary(&path, &[(b"hello", b"5"), (b"help", b"10")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        let keys: Vec<&[u8]> = dictionary
            .suggest(b"hel", "", 4)
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        assert_eq!(keys, vec![b"help".as_slice(), b"hello".as_slice()]);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn suggest_uses_next_words_after_a_committed_word() {
        let path = temp_path("suggest-next");
        write_dictionary(&path, &[(b"moon", b"1000"), (b"morning", b"1")]);

        let dictionary = MappedDictionary::load(&path).unwrap();
        let keys: Vec<&[u8]> = dictionary
            .suggest(b"", "good", 4)
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        assert_eq!(keys.first().copied(), Some(b"morning".as_slice()));
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn suggest_is_empty_without_prefix_or_previous() {
        let path = temp_path("suggest-empty");
        write_dictionary(&path, &[(b"hello", b"5")]);
        let dictionary = MappedDictionary::load(&path).unwrap();
        assert!(dictionary.suggest(b"", "", 4).is_empty());
        assert!(dictionary.suggest(b"h", "", 4).is_empty());
        assert!(dictionary.suggest(b"hel", "", 0).is_empty());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn promotion_reranks_prefix_candidates() {
        let path = temp_path("promote-prefix");
        write_dictionary(&path, &[(b"moon", b"100"), (b"morning", b"1")]);
        let dictionary = MappedDictionary::load(&path).unwrap();

        let mut promotions = PromotionTable::new();
        let base = dictionary.top_prefixes_ranked(b"mo", 2, &promotions);
        assert_eq!(dictionary.key_at(base[0]).unwrap(), b"moon");

        promotions.promote(b"morning", 500);
        let ranked = dictionary.top_prefixes_ranked(b"mo", 2, &promotions);
        assert_eq!(dictionary.key_at(ranked[0]).unwrap(), b"morning");

        promotions.clear();
        assert!(promotions.is_empty());
        let cleared = dictionary.top_prefixes_ranked(b"mo", 2, &promotions);
        assert_eq!(dictionary.key_at(cleared[0]).unwrap(), b"moon");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn promotion_is_capped_and_ignores_empty_words() {
        let mut promotions = PromotionTable::new();
        for _ in 0..1000 {
            promotions.promote(b"word", u64::MAX);
        }
        assert_eq!(promotions.boost(b"word"), MAX_PROMOTION);
        promotions.promote(b"", 10);
        assert_eq!(promotions.len(), 1);
        assert_eq!(promotions.boost(b"absent"), 0);
    }

    #[test]
    fn promotion_boosts_corrections() {
        let path = temp_path("promote-correction");
        write_dictionary(&path, &[(b"care", b"1"), (b"cash", b"1")]);
        let dictionary = MappedDictionary::load(&path).unwrap();

        let mut promotions = PromotionTable::new();
        let base = dictionary.nearest_words_ranked(b"cahd", 2, 2, &promotions);
        assert_eq!(dictionary.key_at(base[0]).unwrap(), b"care");

        promotions.promote(b"cash", 100);
        let ranked = dictionary.nearest_words_ranked(b"cahd", 2, 2, &promotions);
        assert_eq!(dictionary.key_at(ranked[0]).unwrap(), b"cash");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn promotion_influences_next_words() {
        let path = temp_path("promote-next");
        write_dictionary(&path, &[(b"moon", b"1000"), (b"morning", b"1")]);
        let dictionary = MappedDictionary::load(&path).unwrap();

        let mut promotions = PromotionTable::new();
        promotions.promote(b"moon", 50);
        let next = dictionary.top_next_words_ranked("unrelated", 2, &promotions);
        assert_eq!(dictionary.key_at(next[0]).unwrap(), b"moon");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn promotion_serialization_round_trips() {
        let mut promotions = PromotionTable::new();
        promotions.promote(b"hello", 100);
        promotions.promote(b"world", 250);
        promotions.promote(b"good", 42);

        let text = promotions.serialize();
        assert!(text.starts_with(PROMOTIONS_MAGIC));
        // Deterministic ordering by word.
        let order: Vec<&str> = text.lines().skip(1).collect();
        assert_eq!(order, vec!["42\tgood", "100\thello", "250\tworld"]);

        let mut restored = PromotionTable::new();
        assert_eq!(restored.load_from_str(&text), 3);
        assert_eq!(restored.boost(b"good"), 42);
        assert_eq!(restored.boost(b"hello"), 100);
        assert_eq!(restored.boost(b"world"), 250);
    }

    #[test]
    fn promotion_loading_ignores_malformed_lines() {
        let mut promotions = PromotionTable::new();
        let text = "PLKP1\n\
            not-a-number\thello\n\
            garbage-without-tab\n\
            50\t\n\
            10\tgood\n\
            99999999999999999999\thuge\n\
            7\tok then-ignored\n";
        let loaded = promotions.load_from_str(text);
        // "good", "huge" (clamped), and the space-containing word all load;
        // the malformed and empty-word lines do not.
        assert_eq!(loaded, 3);
        assert_eq!(promotions.boost(b"good"), 10);
        assert_eq!(promotions.boost(b"huge"), MAX_PROMOTION);
        assert_eq!(promotions.boost(b"ok then-ignored"), 7);
        assert_eq!(promotions.boost(b"hello"), 0);
    }

    #[test]
    fn promotion_table_saves_and_loads_a_file() {
        let path = temp_path("promotions");
        let mut promotions = PromotionTable::new();
        promotions.promote(b"keyboard", 100);
        promotions.save_to_file(&path).unwrap();

        let mut restored = PromotionTable::new();
        assert_eq!(restored.load_from_file(&path).unwrap(), 1);
        assert_eq!(restored.boost(b"keyboard"), 100);
        let _ = std::fs::remove_file(path);
    }
}
