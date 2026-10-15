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
    ("the", "quick", 12),
    ("quick", "brown", 10),
    ("brown", "fox", 10),
    ("jumped", "over", 8),
    ("over", "the", 9),
    ("i", "am", 11),
    ("we", "are", 11),
    ("you", "can", 10),
    ("can", "use", 8),
    ("this", "is", 11),
    ("it", "is", 10),
    ("please", "send", 7),
    ("thank", "you", 12),
    ("thanks", "again", 8),
    ("hello", "world", 12),
    ("good", "morning", 10),
    ("good", "night", 9),
    ("see", "you", 9),
    ("come", "back", 8),
    ("go", "away", 4),
];

fn context_boost(previous: &str, candidate: &str) -> u64 {
    for (left, right, score) in CONTEXT_TRANSITIONS {
        if *left == previous && *right == candidate {
            return *score as u64;
        }
    }
    0
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
                let score = self
                    .value_at(index)
                    .and_then(|value| std::str::from_utf8(value).ok())
                    .and_then(|text| text.trim().parse::<u64>().ok())
                    .unwrap_or(0);
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
                let text = String::from_utf8_lossy(key);
                let exactness = if key == prefix { 20u64 } else { 0u64 };
                let frequency = self
                    .value_at(index)
                    .and_then(|value| std::str::from_utf8(value).ok())
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .unwrap_or(0);
                let score = frequency + exactness + context_boost(previous, &text);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates.into_iter().take(max).map(|(index, _)| index).collect()
    }

    pub fn top_next_words(&self, previous: &str, max: usize) -> Vec<usize> {
        let mut candidates: Vec<(usize, u64)> = (0..self.entry_count())
            .map(|index| {
                let key = self.key_at(index).unwrap_or_default();
                let text = String::from_utf8_lossy(key);
                let frequency = self
                    .value_at(index)
                    .and_then(|value| std::str::from_utf8(value).ok())
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .unwrap_or(0);
                let score = frequency + (context_boost(previous, &text) * 4);
                (index, score)
            })
            .collect();

        candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        candidates.into_iter().take(max).map(|(index, _)| index).collect()
    }

    pub fn nearest_words(&self, typo: &[u8], max: usize, max_distance: usize) -> Vec<usize> {
        let mut matches: Vec<(usize, usize)> = (0..self.entry_count())
            .filter_map(|index| {
                let key = self.key_at(index)?;
                if key.len().abs_diff(typo.len()) > max_distance {
                    return None;
                }
                let distance = levenshtein_distance(key, typo);
                if distance == 0 || distance > max_distance {
                    return None;
                }
                Some((index, distance))
            })
            .collect();

        matches.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0)));
        matches
            .into_iter()
            .take(max)
            .map(|(index, _)| index)
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
                (b"card", b"1"),
                (b"care", b"2"),
                (b"cash", b"3"),
                (b"dog", b"4"),
            ],
        );

        let dictionary = MappedDictionary::load(&path).unwrap();
        let matches = dictionary.nearest_words(b"cahd", 4, 2);
        let keys: Vec<&[u8]> = matches
            .iter()
            .map(|&index| dictionary.key_at(index).unwrap())
            .collect();
        assert_eq!(keys, vec![b"card".as_slice(), b"care".as_slice(), b"cash".as_slice()]);
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
}
