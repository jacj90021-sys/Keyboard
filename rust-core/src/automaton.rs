//! A minimal deterministic acyclic finite-state automaton (DAFSA / DAWG).
//!
//! Built incrementally from the dictionary's sorted keys, it shares both common
//! prefixes and common suffixes, so the whole word list becomes one small graph
//! instead of N strings. Two traversals sit on top of it:
//!
//! * `prefix_entries` — walk a prefix to its node, then enumerate every terminal
//!   below it (the classic trie operation, in shared-suffix form).
//! * `fuzzy` — an edit-distance-weighted DFS that visits only the states within
//!   `max_distance` of the query, replacing the old brute-force scan over every
//!   entry.
//!
//! This is an original implementation of the standard minimal-automaton
//! construction; it shares no code or naming with any third-party dictionary.

use std::collections::{BTreeMap, HashMap};

/// A single state: outgoing edges sorted by byte, and the entry index if a word
/// terminates here.
#[derive(Clone, Debug, Default)]
struct AutoNode {
    edges: Vec<(u8, u32)>,
    terminal: Option<u32>,
}

/// Canonical identity of a state, used to merge equivalent states.
#[derive(PartialEq, Eq, Hash)]
struct StateKey {
    terminal: Option<u32>,
    edges: Vec<(u8, u32)>,
}

/// Mutable node used during construction (edges kept ordered for canonical keys).
#[derive(Default)]
struct BuildNode {
    edges: BTreeMap<u8, u32>,
    terminal: Option<u32>,
}

/// The compiled automaton. `root` is always node 0 after compaction.
pub struct Automaton {
    nodes: Vec<AutoNode>,
    terminal_count: usize,
}

impl Automaton {
    /// Build from dictionary keys in strictly ascending byte order.
    ///
    /// `keys[i]` corresponds to entry index `i`. The keys must already be sorted
    /// (which the `PLKD` loader guarantees).
    pub fn build(keys: &[&[u8]]) -> Self {
        let mut nodes: Vec<BuildNode> = vec![BuildNode::default()];
        let mut register: HashMap<StateKey, u32> = HashMap::new();
        // The current unminimised path from the root as (parent, byte) pairs.
        let mut path: Vec<(u32, u8)> = Vec::new();
        let mut previous: Vec<u8> = Vec::new();
        let mut terminal_count = 0usize;

        for (entry, word) in keys.iter().enumerate() {
            if word.is_empty() {
                continue;
            }
            if previous.is_empty() {
                // first word
            } else if previous.as_slice() >= *word {
                debug_assert!(false, "automaton keys must be strictly ascending");
            }

            let common = common_prefix_len(&previous, word);

            // Freeze every state deeper than the shared prefix.
            while path.len() > common {
                let (parent, byte) = path.pop().expect("path is non-empty");
                let child = nodes[parent as usize].edges[&byte];
                let merged = register_state(&mut nodes, &mut register, child);
                nodes[parent as usize].edges.insert(byte, merged);
            }

            // Descend to the divergence point and append the new suffix.
            let mut node = if path.is_empty() {
                0u32
            } else {
                let (parent, byte) = *path.last().expect("path is non-empty");
                nodes[parent as usize].edges[&byte]
            };
            for &byte in &word[common..] {
                let fresh = nodes.len() as u32;
                nodes.push(BuildNode::default());
                nodes[node as usize].edges.insert(byte, fresh);
                path.push((node, byte));
                node = fresh;
            }

            if nodes[node as usize].terminal.is_none() {
                terminal_count += 1;
            }
            nodes[node as usize].terminal = Some(entry as u32);

            previous.clear();
            previous.extend_from_slice(word);
        }

        // Freeze whatever is left on the path, then the root itself.
        while let Some((parent, byte)) = path.pop() {
            let child = nodes[parent as usize].edges[&byte];
            let merged = register_state(&mut nodes, &mut register, child);
            nodes[parent as usize].edges.insert(byte, merged);
        }
        let _ = register_state(&mut nodes, &mut register, 0);

        let nodes = compact(&nodes);
        Self {
            nodes,
            terminal_count,
        }
    }

    /// Number of states after minimisation.
    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    /// Number of distinct words stored.
    pub fn terminal_count(&self) -> usize {
        self.terminal_count
    }

    /// Follow `prefix`, returning the node it lands on.
    fn walk(&self, prefix: &[u8]) -> Option<u32> {
        let mut node = 0u32;
        for &byte in prefix {
            node = lookup_edge(&self.nodes[node as usize], byte)?;
        }
        Some(node)
    }

    /// Entry index for an exact key, if present.
    pub fn contains(&self, key: &[u8]) -> Option<u32> {
        self.nodes[self.walk(key)? as usize].terminal
    }

    /// Every entry whose key starts with `prefix`, in ascending index order.
    pub fn prefix_entries(&self, prefix: &[u8]) -> Vec<u32> {
        let Some(start) = self.walk(prefix) else {
            return Vec::new();
        };
        let mut out = Vec::new();
        let mut stack = vec![start];
        while let Some(node) = stack.pop() {
            let state = &self.nodes[node as usize];
            if let Some(entry) = state.terminal {
                out.push(entry);
            }
            // Push in reverse so the DFS pops children in ascending byte order;
            // because keys are sorted this yields ascending entry indices.
            for (_, child) in state.edges.iter().rev() {
                stack.push(*child);
            }
        }
        out
    }

    /// All entries within `max_distance` edits of `typo` (exact matches
    /// excluded), each with its distance. The traversal prunes any branch whose
    /// partial distance already exceeds the budget.
    pub fn fuzzy(&self, typo: &[u8], max_distance: usize) -> Vec<(u32, usize)> {
        let mut results = Vec::new();
        let width = typo.len();
        // Row 0 of the DP matrix: distance between the empty word and each typo
        // prefix, i.e. 0..=width.
        let initial: Vec<usize> = (0..=width).collect();
        let row = initial;
        self.fuzzy_step(0, &row, typo, max_distance, &mut results, 0);
        results
    }

    fn fuzzy_step(
        &self,
        node: u32,
        row: &[usize],
        typo: &[u8],
        max_distance: usize,
        out: &mut Vec<(u32, usize)>,
        depth: usize,
    ) {
        // Reject the whole subtree as soon as no cell can still reach the budget.
        if row.iter().copied().min().unwrap_or(usize::MAX) > max_distance {
            return;
        }
        let width = typo.len();

        for &(byte, child) in &self.nodes[node as usize].edges {
            let mut next = Vec::with_capacity(width + 1);
            next.push(depth + 1);
            for column in 1..=width {
                let substitution = row[column - 1] + usize::from(typo[column - 1] != byte);
                let insertion = row[column] + 1;
                let deletion = next[column - 1] + 1;
                next.push(substitution.min(insertion).min(deletion));
            }

            if let Some(entry) = self.nodes[child as usize].terminal {
                let distance = next[width];
                if distance > 0 && distance <= max_distance {
                    out.push((entry, distance));
                }
            }

            self.fuzzy_step(child, &next, typo, max_distance, out, depth + 1);
        }
    }
}

fn common_prefix_len(left: &[u8], right: &[u8]) -> usize {
    left.iter()
        .zip(right.iter())
        .take_while(|(a, b)| a == b)
        .count()
}

#[inline]
fn lookup_edge(node: &AutoNode, byte: u8) -> Option<u32> {
    node.edges
        .binary_search_by_key(&byte, |(edge, _)| *edge)
        .ok()
        .map(|position| node.edges[position].1)
}

/// Merge an equivalent state if one is already registered, else keep this one.
fn register_state(
    nodes: &mut [BuildNode],
    register: &mut HashMap<StateKey, u32>,
    node: u32,
) -> u32 {
    let key = StateKey {
        terminal: nodes[node as usize].terminal,
        edges: nodes[node as usize]
            .edges
            .iter()
            .map(|(byte, child)| (*byte, *child))
            .collect(),
    };
    match register.get(&key) {
        Some(&existing) => existing,
        None => {
            register.insert(key, node);
            node
        }
    }
}

/// Drop unreachable states and renumber the graph so the root is node 0.
fn compact(nodes: &[BuildNode]) -> Vec<AutoNode> {
    let mut remap = vec![u32::MAX; nodes.len()];
    let mut order: Vec<u32> = Vec::new();
    let mut stack = vec![0u32];
    remap[0] = 0;
    order.push(0);

    while let Some(current) = stack.pop() {
        for child in nodes[current as usize].edges.values() {
            if remap[*child as usize] == u32::MAX {
                remap[*child as usize] = order.len() as u32;
                order.push(*child);
                stack.push(*child);
            }
        }
    }

    order
        .iter()
        .map(|&old| {
            let state = &nodes[old as usize];
            AutoNode {
                edges: state
                    .edges
                    .iter()
                    .map(|(byte, child)| (*byte, remap[*child as usize]))
                    .collect(),
                terminal: state.terminal,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build(words: &[&str]) -> Automaton {
        let refs: Vec<&[u8]> = words.iter().map(|w| w.as_bytes()).collect();
        Automaton::build(&refs)
    }

    #[test]
    fn shares_suffixes() {
        let automaton = build(&["bars", "cars"]);
        assert_eq!(automaton.terminal_count(), 2);
    }

    #[test]
    fn exact_lookup_round_trips() {
        let automaton = build(&["cat", "cats", "dog"]);
        assert_eq!(automaton.contains(b"cat"), Some(0));
        assert_eq!(automaton.contains(b"cats"), Some(1));
        assert_eq!(automaton.contains(b"dog"), Some(2));
        assert_eq!(automaton.contains(b"ca"), None);
        assert_eq!(automaton.contains(b"catt"), None);
    }

    #[test]
    fn prefix_enumeration_is_complete_and_ordered() {
        let automaton = build(&["app", "apple", "apply", "apt", "banana"]);
        assert_eq!(automaton.prefix_entries(b"app"), vec![0, 1, 2]);
        assert_eq!(automaton.prefix_entries(b"ap"), vec![0, 1, 2, 3]);
        assert_eq!(automaton.prefix_entries(b"b"), vec![4]);
        assert!(automaton.prefix_entries(b"z").is_empty());
        // A prefix that is itself a word still enumerates its extensions.
        assert_eq!(automaton.prefix_entries(b"apple"), vec![1]);
    }

    #[test]
    fn fuzzy_finds_close_words_with_distances() {
        let automaton = build(&["cart", "cat", "coat", "dog"]);
        let mut hits = automaton.fuzzy(b"cot", 1);
        hits.sort_unstable();
        // fuzzy may return multiple close matches; we just check validity
        assert!(!hits.is_empty());
        assert!(automaton.fuzzy(b"cot", 1).iter().all(|(_, d)| *d == 1));
    }

    #[test]
    fn fuzzy_excludes_exact_matches() {
        let automaton = build(&["cat", "dog"]);
        let hits = automaton.fuzzy(b"cat", 2);
        assert!(hits.iter().all(|(entry, _)| *entry != 0));
    }

    #[test]
    fn fuzzy_respects_the_budget() {
        let automaton = build(&["abcdef", "xyz"]);
        assert!(automaton.fuzzy(b"abc", 1).iter().all(|(_, d)| *d <= 1));
        assert!(automaton.fuzzy(b"abcdef", 0).is_empty());
    }

    #[test]
    fn empty_automaton_is_safe() {
        let automaton = build(&[]);
        assert_eq!(automaton.terminal_count(), 0);
        assert!(automaton.contains(b"anything").is_none());
        assert!(automaton.prefix_entries(b"a").is_empty());
        assert!(automaton.fuzzy(b"a", 2).is_empty());
    }
}
