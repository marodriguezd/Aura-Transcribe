//! Small audio helpers shared between the engine and the subtitle pipeline.

/// Fast sum of squares with ARM NEON vectorization on aarch64 and an unrolled
/// multi-accumulator fallback on other architectures. Saturates CPU FMA pipelines.
#[inline]
pub fn fast_sum_squares(samples: &[f32]) -> f32 {
    #[cfg(target_arch = "aarch64")]
    {
        use std::arch::aarch64::*;
        let len = samples.len();
        let ptr = samples.as_ptr();
        let mut i = 0;

        // Process in 16-float (64-byte) blocks across 4 independent NEON accumulators
        let mut acc0 = unsafe { vdupq_n_f32(0.0) };
        let mut acc1 = unsafe { vdupq_n_f32(0.0) };
        let mut acc2 = unsafe { vdupq_n_f32(0.0) };
        let mut acc3 = unsafe { vdupq_n_f32(0.0) };

        while i + 16 <= len {
            unsafe {
                let v0 = vld1q_f32(ptr.add(i));
                let v1 = vld1q_f32(ptr.add(i + 4));
                let v2 = vld1q_f32(ptr.add(i + 8));
                let v3 = vld1q_f32(ptr.add(i + 12));

                acc0 = vfmaq_f32(acc0, v0, v0);
                acc1 = vfmaq_f32(acc1, v1, v1);
                acc2 = vfmaq_f32(acc2, v2, v2);
                acc3 = vfmaq_f32(acc3, v3, v3);
            }
            i += 16;
        }

        let mut acc = unsafe {
            let sum01 = vaddq_f32(acc0, acc1);
            let sum23 = vaddq_f32(acc2, acc3);
            vaddq_f32(sum01, sum23)
        };

        // Process remaining 4-float blocks
        while i + 4 <= len {
            unsafe {
                let v = vld1q_f32(ptr.add(i));
                acc = vfmaq_f32(acc, v, v);
            }
            i += 4;
        }

        let mut total = unsafe { vaddvq_f32(acc) };

        // Scalar tail for last 1..3 elements
        while i < len {
            let x = unsafe { *ptr.add(i) };
            total += x * x;
            i += 1;
        }

        total
    }

    #[cfg(not(target_arch = "aarch64"))]
    {
        let chunks = samples.chunks_exact(8);
        let remainder = chunks.remainder();
        let mut sum0 = 0.0f32;
        let mut sum1 = 0.0f32;
        let mut sum2 = 0.0f32;
        let mut sum3 = 0.0f32;
        let mut sum4 = 0.0f32;
        let mut sum5 = 0.0f32;
        let mut sum6 = 0.0f32;
        let mut sum7 = 0.0f32;

        for chunk in chunks {
            sum0 += chunk[0] * chunk[0];
            sum1 += chunk[1] * chunk[1];
            sum2 += chunk[2] * chunk[2];
            sum3 += chunk[3] * chunk[3];
            sum4 += chunk[4] * chunk[4];
            sum5 += chunk[5] * chunk[5];
            sum6 += chunk[6] * chunk[6];
            sum7 += chunk[7] * chunk[7];
        }

        let mut total = (sum0 + sum1) + (sum2 + sum3) + (sum4 + sum5) + (sum6 + sum7);
        for &x in remainder {
            total += x * x;
        }
        total
    }
}

/// Computes Root-Mean-Square (RMS) energy using vectorized SIMD sum of squares
/// with NaN/Infinity sanitization (returns unclipped RMS >= 0.0).
#[inline]
pub fn fast_rms(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum = fast_sum_squares(samples);
    if sum <= 0.0 || !sum.is_finite() {
        return 0.0;
    }
    (sum / samples.len() as f32).sqrt()
}

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    // Sliding-window energy (O3 + SIMD): compute initial window energy and
    // stepping window energies using fast_sum_squares SIMD blocks.
    // The accumulator is f64: repeated subtract/add of f32 squares would
    // otherwise accumulate rounding drift (catastrophic cancellation on
    // long, quiet recordings can even push the energy slightly negative).
    let mut energy: f64 = fast_sum_squares(&samples[from..from + WIN]) as f64;
    let mut best_pos = to;
    let mut best_energy = f64::MAX;
    let mut i = from;
    while i + WIN <= to {
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        let step = WIN / 2;
        if i + WIN + step > to {
            break;
        }
        let leaving = fast_sum_squares(&samples[i..i + step]) as f64;
        let entering = fast_sum_squares(&samples[i + WIN..i + WIN + step]) as f64;
        energy -= leaving;
        energy += entering;
        if energy < 0.0 {
            energy = 0.0;
        }
        i += step;
    }
    best_pos
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn returns_to_when_window_does_not_fit() {
        // from + WIN > to → no full window fits; contract is to return `to`.
        let samples = vec![0.0; 100];
        assert_eq!(find_quietest_split(&samples, 0, 100), 100);
    }

    #[test]
    fn empty_range_returns_to() {
        let samples: Vec<f32> = vec![];
        assert_eq!(find_quietest_split(&samples, 0, 0), 0);
    }

    #[test]
    fn flat_signal_returns_first_window_center() {
        // Uniform signal: every window ties, so the first (leftmost) wins and
        // its center is `from + WIN/2`.
        let samples = vec![1.0f32; 3200];
        assert_eq!(find_quietest_split(&samples, 0, 3200), 800);
    }

    #[test]
    fn quietest_window_center_selected() {
        // 16 kHz mono, 4800 samples. Quietest full 100 ms window is the span
        // [1600..3200] (all zeros); its centre is 2400 and must be chosen over
        // the louder neighbours.
        let mut samples = vec![1.0f32; 4800];
        for s in &mut samples[1600..3200] {
            *s = 0.0;
        }
        assert_eq!(find_quietest_split(&samples, 0, 4800), 2400);
    }

    #[test]
    fn scan_is_bounded_to_from_to_window() {
        // A quiet region outside [from, to] must NOT influence the result.
        let mut samples = vec![1.0f32; 6400];
        for s in &mut samples[4000..6400] {
            *s = 0.0;
        }
        // Scan the loud [0..3200] range only; the quiet tail is irrelevant, so
        // the result equals the flat-signal centre for that sub-range.
        assert_eq!(find_quietest_split(&samples, 0, 3200), 800);
    }

    #[test]
    fn fast_sum_squares_edge_cases() {
        assert_eq!(fast_sum_squares(&[]), 0.0);
        assert_eq!(fast_sum_squares(&[0.0]), 0.0);
        assert_eq!(fast_sum_squares(&[2.0]), 4.0);
        assert_eq!(fast_sum_squares(&[1.0, 2.0, 3.0]), 14.0);

        // Test unaligned sizes: 7, 15, 17, 33, 65 elements
        for len in [7, 15, 17, 33, 65, 1024] {
            let data: Vec<f32> = (0..len).map(|i| (i as f32) * 0.1).collect();
            let expected: f32 = data.iter().map(|&x| x * x).sum();
            let actual = fast_sum_squares(&data);
            let diff = (actual - expected).abs();
            let tol = (expected.abs() * 1e-5).max(1e-3);
            assert!(
                diff <= tol,
                "len {}: actual {} != expected {}",
                len,
                actual,
                expected
            );
        }
    }

    #[test]
    fn fast_rms_computes_correctly() {
        assert_eq!(fast_rms(&[]), 0.0);
        let samples = vec![2.0f32; 100];
        assert!((fast_rms(&samples) - 2.0).abs() < 1e-5);

        let alternating = vec![1.0f32, -1.0f32, 1.0f32, -1.0f32];
        assert!((fast_rms(&alternating) - 1.0).abs() < 1e-5);
    }
}
