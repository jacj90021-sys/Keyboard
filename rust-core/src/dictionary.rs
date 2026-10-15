use std::ffi::CString;
use std::path::Path;

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
            return Err(DictionaryError::InvalidFormat("dictionary is too small".to_string()));
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
            return Err(DictionaryError::Mmap("unable to map dictionary".to_string()));
        }

        let data = unsafe { std::slice::from_raw_parts(mapped_ptr as *const u8, mapped_size) };
        if &data[0..4] != MAGIC {
            unsafe { libc::munmap(mapped_ptr, mapped_size) };
            return Err(DictionaryError::InvalidFormat("bad dictionary magic".to_string()));
        }

        let version = u32::from_le_bytes(data[4..8].try_into().unwrap_or([0; 4]));
        if version != VERSION {
            unsafe { libc::munmap(mapped_ptr, mapped_size) };
            return Err(DictionaryError::InvalidFormat("unsupported dictionary version".to_string()));
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
                return Err(DictionaryError::InvalidFormat("truncated entry header".to_string()));
            }
            let key_len = u16::from_le_bytes(data[cursor..cursor + 2].try_into().unwrap_or([0; 2])) as usize;
            let value_len = u16::from_le_bytes(data[cursor + 2..cursor + 4].try_into().unwrap_or([0; 2])) as usize;
            cursor += 4;

            let key_offset = cursor;
            if key_len == 0 || cursor + key_len > mapped_size {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat("invalid key bounds".to_string()));
            }
            cursor += key_len;

            let value_offset = cursor;
            if cursor + value_len > mapped_size {
                unsafe { libc::munmap(mapped_ptr, mapped_size) };
                return Err(DictionaryError::InvalidFormat("invalid value bounds".to_string()));
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
                return Err(DictionaryError::InvalidFormat("dictionary keys are not sorted".to_string()));
            }
        }

        Ok(Self {
            mapped_ptr: mapped_ptr as *mut u8,
            mapped_size,
            entries,
        })
    }

    pub fn entry_count(&self) -> usize {
        self.entries.len()
    }

    pub fn key_at(&self, index: usize) -> Option<&[u8]> {
        let entry = self.entries.get(index)?;
        Some(unsafe {
            std::slice::from_raw_parts(
                self.mapped_ptr.add(entry.key_offset),
                entry.key_len,
            )
        })
    }

    pub fn value_at(&self, index: usize) -> Option<&[u8]> {
        let entry = self.entries.get(index)?;
        Some(unsafe {
            std::slice::from_raw_parts(
                self.mapped_ptr.add(entry.value_offset),
                entry.value_len,
            )
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
        let start = self.prefix_start(prefix);
        let end = self.prefix_end(prefix);
        let mut candidates: Vec<(usize, u64)> = (start..end)
            .map(|index| {
                let score = self.frequency_at(index);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates.into_iter().take(max).map(|(index, _)| index).collect()
    }

    pub fn top_prefixes_contextual(
        &self,
        prefix: &[u8],
        previous: &str,
        max: usize,
    ) -> Vec<usize> {
        let start = self.prefix_start(prefix);
        let end = self.prefix_end(prefix);
        let mut candidates: Vec<(usize, u64)> = (start..end)
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let exactness = if key == prefix { 20u64 } else { 0u64 };
                let frequency = self.frequency_at(index);
                let score = frequency + exactness + context_boost(previous.as_bytes(), key);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates.into_iter().take(max).map(|(index, _)| index).collect()
    }

    pub fn top_next_words(&self, previous: &str, max: usize) -> Vec<usize> {
        let previous_bytes = previous.as_bytes();
        // Rank by context first, then frequency. A transition hit should beat a
        // merely common word, otherwise next-word hints would just echo the most
        // frequent entries ("the", "be", ...) regardless of what was typed.
        let mut candidates: Vec<(usize, u64, u64)> = (0..self.entry_count())
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let boost = context_boost(previous_bytes, key);
                let frequency = self.frequency_at(index);
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

    pub fn nearest_words(&self, typo: &[u8], max: usize, max_distance: usize) -> Vec<usize> {
        let mut matches: Vec<(usize, usize, u64)> = (0..self.entry_count())
            .filter_map(|index| {
                let key = self.key_at(index)?;
                if key.len().abs_diff(typo.len()) > max_distance {
                    return None;
                }
                let distance = levenshtein_distance(key, typo);
                if distance == 0 || distance > max_distance {
                    return None;
                }
                Some((index, distance, self.frequency_at(index)))
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
}

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
            &[
                (b"alpha", b"10"),
                (b"beta", b"20"),
                (b"gamma", b"30"),
            ],
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
        assert_eq!(keys, vec![b"card".as_slice(), b"cash".as_slice(), b"care".as_slice()]);
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
        write_dictionary(
            &path,
            &[
                (b"moon", b"1"),
                (b"morning", b"1"),
            ],
        );

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
        write_dictionary(
            &path,
            &[
                (b"moon", b"1000"),
                (b"morning", b"1"),
            ],
        );

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
        write_dictionary(
            &path,
            &[
                (b"moon", b"1000"),
                (b"morning", b"1"),
            ],
        );

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
        let top = dictionary.top_prefixes(b"th", 4);
        assert!(!top.is_empty());
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
}
