//! A deterministic acyclic finite-state automaton (DAFSA) over the word list,
//! minimised so shared prefixes *and* shared suffixes collapse into one state.
//!
//! States deliberately carry no per-word output: a state knows only *whether* a
//! word ends there, never which entry it belongs to. That is what lets
//! equivalent states merge — a unique entry id in the state key would make
//! every state distinct, because two states can only be equivalent if their
//! entire subtrees are, and every subtree ends in a uniquely-numbered entry.
//! (Measured on the shipped 46k-word list: 107,834 states as a plain trie vs
//! 21,817 minimised.) The entry index is recovered instead by binary search
//! over the dictionary's sorted entries, which is where `fuzzy` reports each
//! matched word for.
//!
//! One traversal sits on top of it:
//!
//! * `fuzzy` — an edit-distance-weighted DFS that visits only the states within
//!   `max_distance` of the query, handing each matched word to a callback with
//!   its Damerau (transposition-aware) distance. This replaces the old
//!   brute-force scan over every entry.
//!
//! Prefix queries never touch the automaton at all: the sorted entry array
//! answers them by binary search, which is already O(log n).
//!
//! This is an original implementation of the standard minimal-automaton
//! construction; it shares no code or naming with any third-party dictionary.

use std::collections::{BTreeMap, HashMap};

/// A single state: outgoing edges sorted by byte, and whether a word ends here.
/// Entry ids are intentionally absent — see the module docs.
#[derive(Clone, Debug, Default)]
struct AutoNode {
    edges: Vec<(u8, u32)>,
    terminal: bool,
}

/// Canonical identity of a state, used to merge equivalent states.
#[derive(PartialEq, Eq, Hash)]
struct StateKey {
    terminal: bool,
    edges: Vec<(u8, u32)>,
}

/// Mutable node used during construction (edges kept ordered for canonical keys).
#[derive(Default)]
struct BuildNode {
    edges: BTreeMap<u8, u32>,
    terminal: bool,
}

/// Per-search state threaded down the fuzzy traversal: the typo being matched,
/// the word reconstructed so far, the pooled DP rows, and the match sink.
struct FuzzyContext<'a, F: FnMut(&[u8], usize)> {
    typo: &'a [u8],
    max_distance: usize,
    word: Vec<u8>,
    pool: Vec<Vec<usize>>,
    on_match: F,
}

/// The compiled automaton. `root` is always node 0 after compaction.
pub struct Automaton {
    nodes: Vec<AutoNode>,
    terminal_count: usize,
}

impl Automaton {
    /// Build from dictionary keys in strictly ascending byte order.
    ///
    /// `keys[i]` corresponds to entry index `i`; the index itself is not stored
    /// (only the fact that a word ends at its state). The keys must already be
    /// sorted (which the `PLKD` loader guarantees).
    pub fn build(keys: &[&[u8]]) -> Self {
        let mut nodes: Vec<BuildNode> = vec![BuildNode::default()];
        let mut register: HashMap<StateKey, u32> = HashMap::new();
        // The current unminimised path from the root as (parent, byte) pairs.
        let mut path: Vec<(u32, u8)> = Vec::new();
        let mut previous: Vec<u8> = Vec::new();
        let mut word_count = 0usize;

        for word in keys {
            if word.is_empty() {
                continue;
            }
            if !previous.is_empty() && previous.as_slice() >= *word {
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

            if !nodes[node as usize].terminal {
                word_count += 1;
            }
            nodes[node as usize].terminal = true;

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
            terminal_count: word_count,
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

    /// Whether an exact key is stored in the automaton.
    pub fn contains(&self, key: &[u8]) -> bool {
        self.walk(key)
            .is_some_and(|node| self.nodes[node as usize].terminal)
    }

    /// Report every word within `max_distance` edits of `typo` through
    /// `on_match(word, distance)` as the traversal finds it. The exact word
    /// itself is excluded.
    ///
    /// The metric is Damerau-Levenshtein (optimal string alignment), not plain
    /// Levenshtein: swapping two neighbouring letters is a single slip of the
    /// fingers, so it costs 1 edit instead of the 2 that Levenshtein charges.
    /// That matters for ranking — `"recieve"` is a transposition of
    /// `"receive"` but also one substitution away from `"relieve"`, so under
    /// Levenshtein the rarer neighbour could win the tie-break. The matrix row
    /// one step *back* is threaded down for exactly that term.
    ///
    /// Because states carry no entry ids, matches arrive as words; the caller
    /// maps them back to dictionary entries.
    pub fn fuzzy(&self, typo: &[u8], max_distance: usize, on_match: impl FnMut(&[u8], usize)) {
        let width = typo.len();
        let mut context = FuzzyContext {
            typo,
            max_distance,
            word: Vec::with_capacity(width + max_distance),
            pool: Vec::new(),
            on_match,
        };
        // Row 0 of the DP matrix: distance between the empty word and each typo
        // prefix, i.e. 0..=width. There is no row -1, so the parent context
        // starts as `None` and the transposition term stays disabled for the
        // first edge.
        let row = (0..=width).collect();
        self.fuzzy_step(0, row, None, 0, &mut context);
    }

    /// Walk one edge deeper, extending the DP matrix by a row.
    ///
    /// `row` is the matrix row for the candidate prefix *before* the incoming
    /// edge (owned here and recycled into the pool on exit); `parent` carries
    /// the row before that plus that prefix's last letter, which together are
    /// all the transposition term needs.
    fn fuzzy_step<F: FnMut(&[u8], usize)>(
        &self,
        node: u32,
        row: Vec<usize>,
        parent: Option<(&[usize], u8)>,
        depth: usize,
        context: &mut FuzzyContext<'_, F>,
    ) {
        let width = context.typo.len();
        // Reject the whole subtree as soon as no cell can still reach the budget.
        if row.iter().copied().min().unwrap_or(usize::MAX) > context.max_distance {
            context.pool.push(row);
            return;
        }

        for &(byte, child) in &self.nodes[node as usize].edges {
            // Rows are pooled: the DFS would otherwise allocate and free one
            // small vector per visited edge, which dominated query latency.
            let mut next = context.pool.pop().unwrap_or_default();
            next.resize(width + 1, 0);
            next[0] = depth + 1;
            for column in 1..=width {
                let substitution = row[column - 1] + usize::from(context.typo[column - 1] != byte);
                let insertion = row[column] + 1;
                let deletion = next[column - 1] + 1;
                let mut best = substitution.min(insertion).min(deletion);
                // Transposition: the candidate's last two letters are this typo
                // prefix's last two swapped ("recieve"/"receive"), which is one
                // keystroke-level slip rather than two edits.
                if column >= 2 && byte == context.typo[column - 2] {
                    if let Some((parent_row, parent_byte)) = parent {
                        if parent_byte == context.typo[column - 1] {
                            best = best.min(parent_row[column - 2] + 1);
                        }
                    }
                }
                next[column] = best;
            }

            context.word.push(byte);
            if self.nodes[child as usize].terminal {
                let distance = next[width];
                if distance > 0 && distance <= context.max_distance {
                    (context.on_match)(&context.word, distance);
                }
            }
            self.fuzzy_step(
                child,
                next,
                Some((row.as_slice(), byte)),
                depth + 1,
                context,
            );
            context.word.pop();
        }
        context.pool.push(row);
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

    /// Every match as `(word, distance)`, sorted for stable assertions.
    fn fuzzy_words(
        automaton: &Automaton,
        typo: &[u8],
        max_distance: usize,
    ) -> Vec<(Vec<u8>, usize)> {
        let mut hits = Vec::new();
        automaton.fuzzy(typo, max_distance, |word, distance| {
            hits.push((word.to_vec(), distance));
        });
        hits.sort();
        hits
    }

    #[test]
    fn shared_suffixes_collapse_into_one_state() {
        // "bars" and "cars" differ only in the first letter, so everything from
        // "a" on is shared; the "b" and "c" states themselves also have
        // identical futures and merge. That leaves root plus four states, where
        // a plain trie would need eight.
        let automaton = build(&["bars", "cars"]);
        assert_eq!(automaton.terminal_count(), 2);
        assert_eq!(automaton.node_count(), 5);
    }

    #[test]
    fn equivalent_terminal_states_merge_across_words() {
        // Both words end in the same "-ing" tail, and the shared end state must
        // be a single node even though it stands for two different entries --
        // that is only possible because states store no entry id. The "o" and
        // "k" states have identical futures ("ing") and collapse too. A plain
        // trie would need 12 states here; the minimised graph needs 8.
        let automaton = build(&["doing", "making"]);
        assert_eq!(automaton.terminal_count(), 2);
        assert_eq!(automaton.node_count(), 8);
        assert!(automaton.contains(b"doing"));
        assert!(automaton.contains(b"making"));
        assert!(!automaton.contains(b"ing"), "the shared tail is not a word");
    }

    #[test]
    fn exact_lookup_round_trips() {
        let automaton = build(&["cat", "cats", "dog"]);
        assert!(automaton.contains(b"cat"));
        assert!(automaton.contains(b"cats"));
        assert!(automaton.contains(b"dog"));
        assert!(!automaton.contains(b"ca"));
        assert!(!automaton.contains(b"catt"));
    }

    #[test]
    fn fuzzy_finds_close_words_with_distances() {
        let automaton = build(&["cart", "cat", "coat", "dog"]);
        // "cot" is one edit from both "cat" and "coat"; "cart" needs two.
        assert_eq!(
            fuzzy_words(&automaton, b"cot", 1),
            vec![(b"cat".to_vec(), 1), (b"coat".to_vec(), 1)]
        );
    }

    #[test]
    fn fuzzy_treats_a_transposition_as_a_single_edit() {
        // "recieve" is one swap away from "receive" but one substitution away
        // from "relieve". Under plain Levenshtein the swap cost 2 edits, so the
        // frequency tie-break in the correction path could hand "relieve" the
        // win; Damerau keeps both at 1 so frequency decides honestly.
        let automaton = build(&["receive", "relieve"]);
        assert_eq!(
            fuzzy_words(&automaton, b"recieve", 1),
            vec![(b"receive".to_vec(), 1), (b"relieve".to_vec(), 1)]
        );
        assert!(fuzzy_words(&automaton, b"recieve", 0).is_empty());
    }

    #[test]
    fn fuzzy_excludes_exact_matches() {
        let automaton = build(&["cat", "dog"]);
        assert!(fuzzy_words(&automaton, b"cat", 2)
            .iter()
            .all(|(word, _)| word.as_slice() != b"cat"));
    }

    #[test]
    fn fuzzy_respects_the_budget() {
        let automaton = build(&["abcdef", "xyz"]);
        assert!(fuzzy_words(&automaton, b"abc", 1)
            .iter()
            .all(|(_, distance)| *distance <= 1));
        assert!(fuzzy_words(&automaton, b"abcdef", 0).is_empty());
    }

    #[test]
    fn empty_automaton_is_safe() {
        let automaton = build(&[]);
        assert_eq!(automaton.terminal_count(), 0);
        assert!(!automaton.contains(b"anything"));
        assert!(fuzzy_words(&automaton, b"a", 2).is_empty());
    }
}
