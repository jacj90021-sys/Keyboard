//! Original swipe/gesture decoder for the PaperLime keyboard.
//!
//! Cleanroom implementation derived only from the *mathematical idea* of
//! spatial stroke matching (point-to-segment distance, directional alignment,
//! compound-cost normalization). It shares no structure, naming, or layout with
//! any third-party engine. It is a self-contained value-type module: callers
//! hand it a key grid and a finger stroke and get back ranked dictionary
//! indices.

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

/// Tuning knobs. Defaults are sane for a ~100px key grid.
#[derive(Clone, Copy, Debug)]
pub struct TraceWeights {
    /// How strongly the point-to-segment distance is counted.
    pub proximity_pull: f32,
    /// How strongly opposing travel direction is punished.
    pub heading_penalty: f32,
    /// Extra cost added when the stroke drifts far off the whole polyline.
    pub wander_penalty: f32,
    /// Distance (px) at which proximity is considered fully satisfied.
    pub reach: f32,
    /// Cost units subtracted for a clean start/end, to favour exact taps.
    pub bookend_grace: f32,
}

impl Default for TraceWeights {
    fn default() -> Self {
        Self {
            proximity_pull: 1.0,
            heading_penalty: 6.0,
            wander_penalty: 0.35,
            reach: 70.0,
            bookend_grace: 8.0,
        }
    }
}

/// Squared Euclidean distance between two samples.
#[inline]
fn sep2(a: Trace, b: Trace) -> f32 {
    let dx = a.x - b.x;
    let dy = a.y - b.y;
    dx * dx + dy * dy
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

/// Score how well `stroke` traces the polyline formed by `anchors` (the key
/// centres of a candidate word, in order).
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

    let started = inside(stroke[0], anchors[0], knob.reach);
    let ended = inside(
        stroke[stroke.len() - 1],
        anchors[anchors.len() - 1],
        knob.reach,
    );

    // A single-key "word" is just a tap: only the hit test matters.
    if anchors.len() == 1 {
        let cost = if started { 0.0 } else { f32::MAX };
        return TraceVerdict {
            cost,
            started_on_mark: started,
            ended_on_mark: ended,
        };
    }

    let mut aggregate = 0.0f32;
    let mut samples = 0usize;

    // For every finger sample, find its cheapest attachment to any edge of the
    // candidate polyline. Summing this over the stroke is the core compound
    // spatial cost.
    for s in stroke {
        let mut best = f32::MAX;
        for edge in 0..anchors.len() - 1 {
            let d = drop_to_segment(*s, anchors[edge].centre, anchors[edge + 1].centre);
            if d < best {
                best = d;
            }
        }
        // Soft saturation: beyond `reach`, extra distance is still counted but
        // with diminishing shock, so a long stray tail doesn't dominate.
        let shaped = if best <= knob.reach {
            best
        } else {
            knob.reach + (best - knob.reach) * 0.5
        };
        aggregate += shaped;
        samples += 1;
    }

    let mean_deviation = if samples == 0 {
        f32::MAX
    } else {
        aggregate / samples as f32
    };
    if !mean_deviation.is_finite() {
        return TraceVerdict {
            cost: f32::MAX,
            started_on_mark: started,
            ended_on_mark: ended,
        };
    }

    // Directional alignment: compare the finger's net travel against the
    // candidate's net direction. Moving the wrong way is heavily punished.
    let stroke_vec = (
        stroke[stroke.len() - 1].x - stroke[0].x,
        stroke[stroke.len() - 1].y - stroke[0].y,
    );
    let anchor_vec = (
        anchors[anchors.len() - 1].centre.x - anchors[0].centre.x,
        anchors[anchors.len() - 1].centre.y - anchors[0].centre.y,
    );
    let heading_penalty = heading_mismatch(stroke_vec, anchor_vec, knob);

    // Wander penalty: how far the stroke's farthest sample strays from the
    // polyline. Rewards a tight, deliberate path.
    let mut worst = 0.0f32;
    for s in stroke {
        let mut best = f32::MAX;
        for edge in 0..anchors.len() - 1 {
            let d = drop_to_segment(*s, anchors[edge].centre, anchors[edge + 1].centre);
            if d < best {
                best = d;
            }
        }
        if best > worst && best.is_finite() {
            worst = best;
        }
    }
    let wander = knob.wander_penalty * worst;

    let mut cost = knob.proximity_pull * mean_deviation + heading_penalty + wander;

    // Bookend grace: nudge clean start/end matches ahead of sloppy ones.
    if started {
        cost -= knob.bookend_grace;
    }
    if ended {
        cost -= knob.bookend_grace;
    }

    TraceVerdict {
        cost: if cost.is_finite() { cost } else { f32::MAX },
        started_on_mark: started,
        ended_on_mark: ended,
    }
}

/// Penalty for disagreement between the stroke's travel direction and the
/// candidate's travel direction, using the normalised dot product (cosine).
fn heading_mismatch(stroke_vec: (f32, f32), anchor_vec: (f32, f32), knob: TraceWeights) -> f32 {
    let (sx, sy) = stroke_vec;
    let (ax, ay) = anchor_vec;
    let s_len = (sx * sx + sy * sy).sqrt();
    let a_len = (ax * ax + ay * ay).sqrt();
    if s_len <= f32::EPSILON || a_len <= f32::EPSILON {
        return 0.0;
    }
    let dot = sx * ax + sy * ay;
    let cosine = (dot / (s_len * a_len)).clamp(-1.0, 1.0);
    // cosine == 1 -> aligned (0 penalty); cosine == -1 -> reversed (max).
    knob.heading_penalty * (1.0 - cosine) * 0.5
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
/// `key_centres` maps a dictionary key byte to its grid cell via `locate`, so
/// this module stays independent of any specific layout.
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
    let max_keys = ((span / (knob.reach * 0.5)).ceil() as usize).max(2);
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
}
