//! Swipe / gesture stroke decoder for the PaperLime keyboard.
//!
//! The decoder walks a candidate word's key centres in order, letting each key
//! claim the stretch of the stroke that actually goes for it. Three ideas drive
//! the scoring, all expressed here in this module's own vocabulary:
//!
//! * **Normalised gates.** Every distance threshold is a fraction of the *key
//!   pitch* (centre-to-centre distance between neighbouring keys), so the same
//!   constants work on any grid density without retuning.
//! * **Ordered claim.** Anchors are consumed left to right; each middle key must
//!   be *approached* — the finger has to get closer to it at some point after the
//!   previous key — not merely grazed while aiming elsewhere.
//! * **Backtracking + stray.** Moving against the key-to-key direction, or
//!   wandering off the line between two claimed keys, adds super-linear cost.
//! * **Forgiving ends.** The first and last keys are matched against a *window*
//!   of samples, and a short lead-in or trailing hook is priced instead of
//!   rejected — fingers are still moving when they land and lift.
//!
//! It is a self-contained value-type module: callers hand it a key grid and a
//! finger stroke and get back ranked dictionary indices.

use crate::dictionary::{MappedDictionary, PromotionTable};

/// A single 2-D sample of the finger path, in the same coordinate space as the
/// key grid (e.g. view pixels).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Trace {
    pub x: f32,
    pub y: f32,
}

impl Trace {
    #[inline]
    pub const fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }
}

/// The rectangular hit-box plus centre of a single key on the board.
#[derive(Clone, Copy, Debug, Default)]
pub struct Cell {
    pub centre: Trace,
    pub half_width: f32,
    pub half_height: f32,
}

impl Cell {
    /// Build a hit-box from a centre point and half extents.
    #[inline]
    pub const fn new(cx: f32, cy: f32, half_width: f32, half_height: f32) -> Self {
        Self {
            centre: Trace::new(cx, cy),
            half_width,
            half_height,
        }
    }
}

/// Why a candidate scored the way it did (kept for debugging / tuning).
#[derive(Clone, Copy, Debug, Default)]
pub struct TraceVerdict {
    /// Aggregate spatial cost, lower is better. `f32::MAX` = not viable.
    pub cost: f32,
    /// Did the stroke begin inside the first key's hit-box?
    pub started_on_mark: bool,
    /// Did the stroke end inside the last key's hit-box?
    pub ended_on_mark: bool,
}

/// Tuning knobs. Distance gates are all fractions of [`Self::key_pitch`], so
/// absolute pixel size barely matters.
#[derive(Clone, Copy, Debug)]
pub struct TraceWeights {
    /// Typical centre-to-centre distance between adjacent keys. Scales every
    /// gate below.
    pub key_pitch: f32,
    /// How strongly measured gaps are counted.
    pub proximity_pull: f32,
    /// How strongly a doubled-back finger segment is punished.
    pub backtrack_penalty: f32,
    /// Cost units subtracted for a clean start/end, to favour exact taps.
    pub bookend_grace: f32,
    /// Longest finger "hook" tolerated before the first key or after the last
    /// one, as a multiple of the key pitch. People lead into a swipe already
    /// moving and curl away as they lift; a little of that must not veto an
    /// otherwise clean word.
    pub max_hook: f32,
    /// Cost charged per pitch of tolerated hook, so a clean stroke still beats a
    /// sloppy one that needs the same keys.
    pub hook_pull: f32,
}

impl Default for TraceWeights {
    fn default() -> Self {
        Self {
            key_pitch: 100.0,
            proximity_pull: 1.0,
            backtrack_penalty: 3.0,
            bookend_grace: 8.0,
            max_hook: 2.0,
            hook_pull: 2.0,
        }
    }
}

/// How many opening samples are searched for the first key's best approach.
/// Three is enough to cover the lead-in of a normal swipe without letting the
/// search wander into the middle of the stroke.
const START_WINDOW: usize = 3;

/// Squared Euclidean distance between two samples.
#[inline]
fn sep2(a: Trace, b: Trace) -> f32 {
    let dx = a.x - b.x;
    let dy = a.y - b.y;
    dx * dx + dy * dy
}

/// Length of the finger path between sample `from` and sample `to` (both
/// indices into `stroke`), walking sample to sample. Used to measure the lead-in
/// and trailing hook that sit outside the claimed keys.
fn arc_length(stroke: &[Trace], from: usize, to: usize) -> f32 {
    if to <= from || stroke.len() < 2 {
        return 0.0;
    }
    let mut total = 0.0;
    for index in from..to.min(stroke.len() - 1) {
        total += sep2(stroke[index], stroke[index + 1]).sqrt();
    }
    total
}

/// Shortest distance from a point to the closed segment `a -> b`.
///
/// Projects the point onto the infinite line, clamps the parameter to `[0,1]`
/// so the result is the distance to the *segment* (not the infinite line), and
/// measures to that clamped foot point.
fn drop_to_segment(p: Trace, a: Trace, b: Trace) -> f32 {
    let bx = b.x - a.x;
    let by = b.y - a.y;
    let len_sq = bx * bx + by * by;
    if len_sq <= f32::EPSILON {
        // Degenerate segment: both endpoints coincide.
        return sep2(p, a).sqrt();
    }
    let px = p.x - a.x;
    let py = p.y - a.y;
    let t = ((px * bx + py * by) / len_sq).clamp(0.0, 1.0);
    let fx = p.x - (a.x + t * bx);
    let fy = p.y - (a.y + t * by);
    (fx * fx + fy * fy).sqrt()
}

/// Is `p` inside the axis-aligned hit-box of `cell` (with a small forgiveness
/// margin)?
#[inline]
fn inside(p: Trace, cell: Cell, margin: f32) -> bool {
    (p.x - cell.centre.x).abs() <= cell.half_width + margin
        && (p.y - cell.centre.y).abs() <= cell.half_height + margin
}

/// Scan forward from `from` for the stroke *segment* that passes closest to
/// `centre`. Returns `(claim index, gap)` only when the finger genuinely *went
/// for* the key: the closest segment must lie after `from` and inside `gate`.
///
/// Measuring against segments (not raw samples) is what lets a sparsely-sampled
/// stroke still credit a key it swept cleanly over.
fn closest_approach(
    stroke: &[Trace],
    centre: Trace,
    from: usize,
    gate: f32,
) -> Option<(usize, f32)> {
    let mut best = f32::MAX;
    let mut claim = from;
    // Segment `i` runs from sample `i` to sample `i+1`; its claim index is `i+1`
    // so the walk always advances past the segment it consumed.
    for i in from..stroke.len().saturating_sub(1) {
        let d = drop_to_segment(centre, stroke[i], stroke[i + 1]);
        if d < best {
            best = d;
            claim = i + 1;
        }
    }
    // `claim == from` means no segment after `from` beat the starting point —
    // the finger never actually approached within this stretch.
    if claim == from || best > gate {
        return None;
    }
    Some((claim, best))
}

/// Charge for moving against the key-to-key direction between two claimed
/// samples. Returns 0 when aligned or when either vector is degenerate.
fn backtrack_cost(
    stroke: &[Trace],
    i0: usize,
    i1: usize,
    a_from: Trace,
    a_to: Trace,
    knob: TraceWeights,
) -> f32 {
    if i1 <= i0 {
        return 0.0;
    }
    let sx = stroke[i1].x - stroke[i0].x;
    let sy = stroke[i1].y - stroke[i0].y;
    let ax = a_to.x - a_from.x;
    let ay = a_to.y - a_from.y;
    let stroke_len = (sx * sx + sy * sy).sqrt();
    let anchor_len = (ax * ax + ay * ay).sqrt();
    if stroke_len <= f32::EPSILON || anchor_len <= f32::EPSILON {
        return 0.0;
    }
    let agreement = (sx * ax + sy * ay) / (stroke_len * anchor_len);
    if agreement < 0.0 {
        knob.backtrack_penalty * stroke_len * -agreement
    } else {
        0.0
    }
}

/// Mean stray distance of the samples in `from..end` (end-exclusive) from the
/// straight line between the two claimed anchors, shaped super-linearly.
/// Returns `f32::MAX` when any sample wanders past `gate` (the stroke went
/// somewhere the word cannot justify).
fn stray_between(
    stroke: &[Trace],
    from: usize,
    end: usize,
    a_from: Trace,
    a_to: Trace,
    gate: f32,
) -> f32 {
    if end <= from {
        return 0.0;
    }
    let mut sum = 0.0f32;
    let mut count = 0usize;
    for sample in stroke.iter().take(end).skip(from) {
        let d = drop_to_segment(*sample, a_from, a_to);
        if d > gate {
            return f32::MAX;
        }
        sum += d;
        count += 1;
    }
    (sum / count as f32).powf(1.75)
}

/// Score how well `stroke` traces the key centres of `anchors` (a candidate
/// word, in order).
///
/// Returns a [`TraceVerdict`]. Lower `cost` is better; `f32::MAX` marks a
/// candidate the stroke cannot plausibly represent.
pub fn trace_cost(stroke: &[Trace], anchors: &[Cell], knob: TraceWeights) -> TraceVerdict {
    if anchors.is_empty() || stroke.len() < 2 {
        return TraceVerdict {
            cost: f32::MAX,
            started_on_mark: false,
            ended_on_mark: false,
        };
    }

    let pitch = knob.key_pitch.max(1.0);
    let last_sample = stroke.len() - 1;
    let started = inside(stroke[0], anchors[0], pitch * 0.5);
    let ended = inside(stroke[last_sample], anchors[anchors.len() - 1], pitch * 0.9);
    let bookend = TraceVerdict {
        cost: f32::MAX,
        started_on_mark: started,
        ended_on_mark: ended,
    };

    // A single-key "word" is just a tap: only the hit test matters.
    if anchors.len() == 1 {
        let cost = if started { 0.0 } else { f32::MAX };
        return TraceVerdict {
            cost,
            started_on_mark: started,
            ended_on_mark: ended,
        };
    }

    // First key: the finger must begin near it. The opening samples are searched
    // rather than trusting sample zero, so a short lead-in — the finger already
    // moving when the first sample lands — is forgiven rather than vetoing the
    // whole word.
    let mut open_gap = f32::MAX;
    let mut open_at = 0usize;
    for (index, sample) in stroke.iter().take(START_WINDOW).enumerate() {
        let gap = sep2(*sample, anchors[0].centre).sqrt();
        if gap < open_gap {
            open_gap = gap;
            open_at = index;
        }
    }
    if open_gap > pitch * 0.9 {
        return bookend;
    }
    let mut total = knob.proximity_pull * open_gap;
    let lead = arc_length(stroke, 0, open_at);
    if lead > pitch * knob.max_hook {
        return bookend;
    }
    total += knob.hook_pull * lead / pitch;

    // Middle keys: each must be approached, and hops are charged for moving
    // against the key direction and for straying off the line. The walk starts
    // at the sample where the finger actually reached the first key, so the
    // lead-in is not also charged as a stray.
    let mut cursor = open_at;
    let mut prev_claim = open_at;
    let mut prev_centre = anchors[0].centre;
    for anchor in anchors.iter().take(anchors.len() - 1).skip(1) {
        let centre = anchor.centre;
        let Some((claim, gap)) = closest_approach(stroke, centre, cursor, pitch * 0.6) else {
            return bookend;
        };
        total += knob.proximity_pull * gap.powf(1.5);
        total += backtrack_cost(stroke, prev_claim, claim, prev_centre, centre, knob);
        let stray = stray_between(stroke, prev_claim, claim, prev_centre, centre, pitch * 0.7);
        if stray == f32::MAX {
            return bookend;
        }
        total += stray;

        prev_claim = claim;
        prev_centre = centre;
        cursor = claim;
    }

    // Last key: the finger must approach it after the previous key. The closest
    // approach is searched rather than measured from the final sample, so a
    // trailing hook — the finger curling away as it lifts — no longer vetoes the
    // word. Whatever lies past the last key is charged as hook, not as stray.
    let last_centre = anchors[anchors.len() - 1].centre;
    let Some((close_claim, close_gap)) = closest_approach(
        stroke,
        last_centre,
        cursor.min(last_sample - 1),
        pitch * 0.9,
    ) else {
        return bookend;
    };
    total += knob.proximity_pull * close_gap.powf(1.5);
    total += backtrack_cost(
        stroke,
        prev_claim,
        close_claim,
        prev_centre,
        last_centre,
        knob,
    );
    let tail_stray = stray_between(
        stroke,
        prev_claim,
        close_claim,
        prev_centre,
        last_centre,
        pitch * 0.7,
    );
    if tail_stray == f32::MAX {
        return bookend;
    }
    total += tail_stray;

    let hook = arc_length(stroke, close_claim, last_sample);
    if hook > pitch * knob.max_hook {
        return bookend;
    }
    total += knob.hook_pull * hook / pitch;

    // Bookend grace: nudge clean start/end matches ahead of sloppy ones.
    if started {
        total -= knob.bookend_grace;
    }
    if ended {
        total -= knob.bookend_grace;
    }

    TraceVerdict {
        cost: if total.is_finite() { total } else { f32::MAX },
        started_on_mark: started,
        ended_on_mark: ended,
    }
}

/// Cheap pre-filter: estimate a candidate's cost without a full pass, so obvious
/// non-candidates are skipped before the expensive scoring. Returns an
/// optimistic (under-estimated) lower bound on the true cost.
fn optimistic_floor(stroke: &[Trace], anchors: &[Cell]) -> f32 {
    if anchors.is_empty() || stroke.len() < 2 {
        return f32::MAX;
    }
    // Lower bound = distance from the stroke midpoint to the anchor midpoint,
    // scaled down. Real cost is always >= this.
    let s = stroke[stroke.len() / 2];
    let a = anchors[anchors.len() / 2].centre;
    sep2(s, a).sqrt() * 0.5
}

/// Ranked swipe decode: scan the dictionary, score every key whose length is
/// plausible for the stroke, and return the best `max` indices (best first).
///
/// `locate` maps a dictionary key byte to its grid cell, so this module stays
/// independent of any specific layout.
pub fn rank_stroke<F>(
    stroke: &[Trace],
    dictionary: &MappedDictionary,
    promotions: &PromotionTable,
    locate: F,
    knob: TraceWeights,
    max: usize,
) -> Vec<usize>
where
    F: Fn(u8) -> Option<Cell>,
{
    if stroke.len() < 2 || max == 0 {
        return Vec::new();
    }

    // Path length bounds how many keys are plausible: a short stroke can't be a
    // long word, and a long stroke is rarely a short word.
    let mut span = 0.0f32;
    for pair in stroke.windows(2) {
        span += sep2(pair[0], pair[1]).sqrt();
    }
    let pitch = knob.key_pitch.max(1.0);
    let max_keys = ((span / (pitch * 0.5)).ceil() as usize).max(2);
    let min_keys = 2usize;

    let mut scored: Vec<(f32, usize)> = Vec::new();
    let count = dictionary.entry_count();

    for index in 0..count {
        let Some(key) = dictionary.key_at(index) else {
            continue;
        };
        if key.is_empty() || key.len() < min_keys || key.len() > max_keys {
            continue;
        }

        // Build the anchor polyline for this candidate.
        let mut anchors: Vec<Cell> = Vec::with_capacity(key.len());
        let mut viable = true;
        for &b in key {
            match locate(b) {
                Some(cell) => anchors.push(cell),
                None => {
                    viable = false;
                    break;
                }
            }
        }
        if !viable {
            continue;
        }

        // Pre-filter: bail early when even the optimistic floor is already
        // worse than the current worst kept candidate.
        if scored.len() >= max {
            let floor = optimistic_floor(stroke, &anchors);
            let worst_kept = scored.iter().map(|(c, _)| *c).fold(f32::MIN, f32::max);
            if floor > worst_kept {
                continue;
            }
        }

        let verdict = trace_cost(stroke, &anchors, knob);
        if verdict.cost == f32::MAX {
            continue;
        }

        // Fold in learned preference so frequent words edge out rare ones at
        // equal spatial fit.
        let boost = promotions.boost(key) as f32 * 0.02;
        scored.push((verdict.cost - boost, index));
    }

    scored.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    scored.truncate(max);
    scored.into_iter().map(|(_, i)| i).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A tiny 3-column grid: 'a' at (0,0), 'b' at (100,0), 'c' at (200,0),
    /// and 'd' directly below 'b' at (100,100).
    fn cell(cx: f32, cy: f32) -> Cell {
        Cell::new(cx, cy, 45.0, 45.0)
    }

    fn anchors_for(keys: &[u8]) -> Vec<Cell> {
        keys.iter()
            .map(|&b| match b {
                b'a' => cell(0.0, 0.0),
                b'b' => cell(100.0, 0.0),
                b'c' => cell(200.0, 0.0),
                b'd' => cell(100.0, 100.0),
                _ => cell(0.0, 0.0),
            })
            .collect()
    }

    #[test]
    fn straight_swipe_prefers_the_matching_word() {
        // Stroke runs left->right along the top row: should be "abc".
        let stroke = vec![
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let knob = TraceWeights::default();
        let abc = trace_cost(&stroke, &anchors_for(b"abc"), knob);
        let abd = trace_cost(&stroke, &anchors_for(b"abd"), knob);
        assert!(
            abc.cost < abd.cost,
            "abc ({}) should beat abd ({})",
            abc.cost,
            abd.cost
        );
    }

    #[test]
    fn clean_start_and_end_are_marked() {
        let stroke = vec![Trace::new(0.0, 0.0), Trace::new(200.0, 0.0)];
        let verdict = trace_cost(&stroke, &anchors_for(b"abc"), TraceWeights::default());
        assert!(verdict.started_on_mark);
        assert!(verdict.ended_on_mark);
    }

    #[test]
    fn reversed_direction_is_penalised() {
        let forward = vec![Trace::new(0.0, 0.0), Trace::new(200.0, 0.0)];
        let backward = vec![Trace::new(200.0, 0.0), Trace::new(0.0, 0.0)];
        let knob = TraceWeights::default();
        let fwd = trace_cost(&forward, &anchors_for(b"abc"), knob);
        let bwd = trace_cost(&backward, &anchors_for(b"abc"), knob);
        assert!(
            fwd.cost < bwd.cost,
            "forward ({}) should beat backward ({})",
            fwd.cost,
            bwd.cost
        );
    }

    #[test]
    fn empty_inputs_are_rejected() {
        let knob = TraceWeights::default();
        assert_eq!(trace_cost(&[], &anchors_for(b"abc"), knob).cost, f32::MAX);
        assert_eq!(
            trace_cost(&[Trace::new(0.0, 0.0)], &[], knob).cost,
            f32::MAX
        );
    }

    #[test]
    fn segment_distance_math_is_exact() {
        // Point (50,30) to segment (0,0)-(100,0): foot is (50,0), dist 30.
        let d = drop_to_segment(
            Trace::new(50.0, 30.0),
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
        );
        assert!((d - 30.0).abs() < 1e-4);
        // Beyond the segment end: clamps to the endpoint distance.
        let d2 = drop_to_segment(
            Trace::new(150.0, 0.0),
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
        );
        assert!((d2 - 50.0).abs() < 1e-4);
    }

    #[test]
    fn gates_scale_with_key_pitch() {
        // A stroke that runs 40px above the key row clears the gates on a coarse
        // (100px-pitch) grid but is rejected on a fine (20px-pitch) one, because
        // every distance gate is a fraction of the pitch.
        let stroke = vec![
            Trace::new(0.0, 40.0),
            Trace::new(100.0, 40.0),
            Trace::new(200.0, 40.0),
        ];
        let coarse = TraceWeights::default();
        let fine = TraceWeights {
            key_pitch: 20.0,
            ..TraceWeights::default()
        };
        let ok = trace_cost(&stroke, &anchors_for(b"abc"), coarse).cost;
        let rejected = trace_cost(&stroke, &anchors_for(b"abc"), fine).cost;
        assert!(ok.is_finite(), "coarse grid should accept the stroke");
        assert_eq!(rejected, f32::MAX, "fine grid should reject the stroke");
    }

    #[test]
    fn doubling_back_is_charged() {
        // Same endpoints, but the middle sample doubles back toward the start.
        let clean = vec![
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let doubled = vec![
            Trace::new(0.0, 0.0),
            Trace::new(20.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let knob = TraceWeights::default();
        let a = trace_cost(&clean, &anchors_for(b"abc"), knob);
        let b = trace_cost(&doubled, &anchors_for(b"abc"), knob);
        assert!(
            a.cost <= b.cost,
            "clean ({}) should not cost more than doubled-back ({})",
            a.cost,
            b.cost
        );
    }

    #[test]
    fn grazing_a_key_while_aiming_elsewhere_is_rejected() {
        // Stroke goes a -> b -> c, so "adc" (which needs a detour down to d)
        // must be rejected outright rather than scored.
        let stroke = vec![
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let knob = TraceWeights::default();
        let verdict = trace_cost(&stroke, &anchors_for(b"adc"), knob);
        assert_eq!(verdict.cost, f32::MAX);
    }

    #[test]
    fn trailing_hook_after_the_last_key_is_tolerated() {
        // Swipe a -> b -> c, then curl upward as the finger lifts. The hook is
        // finger-overrun, not a different word, so "abc" must still score.
        // (Before the ends were made forgiving this was rejected because the
        // final sample sat more than a pitch away from 'c'.)
        let stroke = vec![
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
            Trace::new(200.0, 120.0),
        ];
        let verdict = trace_cost(&stroke, &anchors_for(b"abc"), TraceWeights::default());
        assert!(
            verdict.cost.is_finite(),
            "a trailing hook must not veto the word"
        );
    }

    #[test]
    fn a_hook_long_enough_to_be_another_swipe_is_still_rejected() {
        // The same hook, but long enough that the finger was travelling to a
        // key the word does not contain.
        let stroke = vec![
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
            Trace::new(200.0, 400.0),
        ];
        let verdict = trace_cost(&stroke, &anchors_for(b"abc"), TraceWeights::default());
        assert_eq!(verdict.cost, f32::MAX);
    }

    #[test]
    fn short_lead_in_before_the_first_key_is_tolerated() {
        // The finger is already moving when the first sample lands, 0.6 pitch
        // above the opening key.
        let stroke = vec![
            Trace::new(0.0, 60.0),
            Trace::new(0.0, 0.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let verdict = trace_cost(&stroke, &anchors_for(b"abc"), TraceWeights::default());
        assert!(
            verdict.cost.is_finite(),
            "a short lead-in should not reject the word"
        );
    }

    #[test]
    fn a_start_far_from_the_first_key_is_still_rejected() {
        let stroke = vec![
            Trace::new(0.0, 300.0),
            Trace::new(100.0, 0.0),
            Trace::new(200.0, 0.0),
        ];
        let verdict = trace_cost(&stroke, &anchors_for(b"abc"), TraceWeights::default());
        assert_eq!(verdict.cost, f32::MAX);
    }
}
