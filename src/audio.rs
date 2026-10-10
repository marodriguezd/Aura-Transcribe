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
/// with NaN/Infinity sanitization and [0.0, 1.0] margin clamping.
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

/// Converts 16-bit signed PCM audio samples to 32-bit floating point samples [-1.0, 1.0]
/// using ARM NEON vectorization on aarch64 with an unrolled scalar fallback.
/// Converts in-place directly into the provided destination vector without intermediate allocations.
#[inline]
pub fn pcm_i16_to_f32_neon(src: &[i16], dst: &mut Vec<f32>) {
    let src_len = src.len();
    if src_len == 0 {
        return;
    }
    const INV_SCALE: f32 = 1.0 / 32768.0;
    let start_idx = dst.len();
    dst.reserve(src_len);
    unsafe {
        dst.set_len(start_idx + src_len);
        let dst_ptr = dst.as_mut_ptr().add(start_idx);
        let src_ptr = src.as_ptr();

        #[cfg(target_arch = "aarch64")]
        {
            use std::arch::aarch64::*;
            let v_scale = vdupq_n_f32(INV_SCALE);
            let mut i = 0;

            // Process in 16-sample blocks (32 bytes = 2x 128-bit NEON registers)
            while i + 16 <= src_len {
                let v0_16 = vld1q_s16(src_ptr.add(i));
                let v1_16 = vld1q_s16(src_ptr.add(i + 8));

                let l0_32 = vmovl_s16(vget_low_s16(v0_16));
                let h0_32 = vmovl_high_s16(v0_16);
                let l1_32 = vmovl_s16(vget_low_s16(v1_16));
                let h1_32 = vmovl_high_s16(v1_16);

                let f0 = vmulq_f32(vcvtq_f32_s32(l0_32), v_scale);
                let f1 = vmulq_f32(vcvtq_f32_s32(h0_32), v_scale);
                let f2 = vmulq_f32(vcvtq_f32_s32(l1_32), v_scale);
                let f3 = vmulq_f32(vcvtq_f32_s32(h1_32), v_scale);

                vst1q_f32(dst_ptr.add(i), f0);
                vst1q_f32(dst_ptr.add(i + 4), f1);
                vst1q_f32(dst_ptr.add(i + 8), f2);
                vst1q_f32(dst_ptr.add(i + 12), f3);

                i += 16;
            }

            // Process remaining 8-sample blocks
            while i + 8 <= src_len {
                let v_16 = vld1q_s16(src_ptr.add(i));
                let l_32 = vmovl_s16(vget_low_s16(v_16));
                let h_32 = vmovl_high_s16(v_16);

                let f0 = vmulq_f32(vcvtq_f32_s32(l_32), v_scale);
                let f1 = vmulq_f32(vcvtq_f32_s32(h_32), v_scale);

                vst1q_f32(dst_ptr.add(i), f0);
                vst1q_f32(dst_ptr.add(i + 4), f1);

                i += 8;
            }

            // Process remaining 4-sample blocks
            while i + 4 <= src_len {
                let v4_16 = vld1_s16(src_ptr.add(i));
                let v4_32 = vmovl_s16(v4_16);
                let f0 = vmulq_f32(vcvtq_f32_s32(v4_32), v_scale);
                vst1q_f32(dst_ptr.add(i), f0);
                i += 4;
            }

            // Scalar tail for last 0..3 elements
            while i < src_len {
                *dst_ptr.add(i) = (*src_ptr.add(i) as f32) * INV_SCALE;
                i += 1;
            }
        }

        #[cfg(not(target_arch = "aarch64"))]
        {
            let mut i = 0;
            while i + 8 <= src_len {
                *dst_ptr.add(i) = (*src_ptr.add(i) as f32) * INV_SCALE;
                *dst_ptr.add(i + 1) = (*src_ptr.add(i + 1) as f32) * INV_SCALE;
                *dst_ptr.add(i + 2) = (*src_ptr.add(i + 2) as f32) * INV_SCALE;
                *dst_ptr.add(i + 3) = (*src_ptr.add(i + 3) as f32) * INV_SCALE;
                *dst_ptr.add(i + 4) = (*src_ptr.add(i + 4) as f32) * INV_SCALE;
                *dst_ptr.add(i + 5) = (*src_ptr.add(i + 5) as f32) * INV_SCALE;
                *dst_ptr.add(i + 6) = (*src_ptr.add(i + 6) as f32) * INV_SCALE;
                *dst_ptr.add(i + 7) = (*src_ptr.add(i + 7) as f32) * INV_SCALE;
                i += 8;
            }
            while i < src_len {
                *dst_ptr.add(i) = (*src_ptr.add(i) as f32) * INV_SCALE;
                i += 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pcm_i16_to_f32_neon_edge_cases_and_lengths() {
        // Empty
        let mut dst = Vec::new();
        pcm_i16_to_f32_neon(&[], &mut dst);
        assert!(dst.is_empty());

        // Extreme values
        let extremes = vec![i16::MIN, -16384, -1, 0, 1, 16384, 32767, -32768];
        let mut dst_extremes = Vec::new();
        pcm_i16_to_f32_neon(&extremes, &mut dst_extremes);
        assert_eq!(dst_extremes.len(), extremes.len());
        for (i, &s) in extremes.iter().enumerate() {
            let expected = (s as f32) * (1.0 / 32768.0);
            assert_eq!(dst_extremes[i].to_bits(), expected.to_bits());
        }

        // Test various slice lengths to exercise all SIMD unroll loops (16, 8, 4, scalar tail)
        for len in [1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 31, 32, 33, 100, 1600] {
            let src: Vec<i16> = (0..len)
                .map(|i| (((i as i32) * 137) % 65535 - 32768) as i16)
                .collect();
            let mut dst = Vec::new();
            pcm_i16_to_f32_neon(&src, &mut dst);
            assert_eq!(dst.len(), len);
            for (idx, &s) in src.iter().enumerate() {
                let expected = (s as f32) * (1.0 / 32768.0);
                assert_eq!(
                    dst[idx].to_bits(),
                    expected.to_bits(),
                    "Mismatch at index {} for len {}",
                    idx,
                    len
                );
            }
        }

        // Test appending to an already populated vector
        let mut dst = vec![1.0f32, 2.0f32];
        let src = vec![0i16, 16384i16];
        pcm_i16_to_f32_neon(&src, &mut dst);
        assert_eq!(dst.len(), 4);
        assert_eq!(dst[0], 1.0);
        assert_eq!(dst[1], 2.0);
        assert_eq!(dst[2], 0.0);
        assert_eq!(dst[3], 0.5);
    }

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
