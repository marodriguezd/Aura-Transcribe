//! Character-bigram extraction and cosine similarity for orthographic tiebreaking.

use std::collections::HashMap;

/// Computes character-bigram frequencies and the L2 norm for a string.
pub fn compute_bigrams(s: &str) -> (HashMap<(char, char), u32>, f64) {
    let mut m = HashMap::new();
    let mut chars = s.chars();
    if let Some(mut prev) = chars.next() {
        for curr in chars {
            *m.entry((prev, curr)).or_insert(0u32) += 1;
            prev = curr;
        }
    }
    let norm = m.values().map(|&v| (v as f64).powi(2)).sum::<f64>().sqrt();
    (m, norm)
}

/// Computes character-bigram cosine similarity between two strings.
///
/// Returns a value in [0.0, 1.0] where 1.0 indicates identical bigram distribution.
pub fn bigram_cosine(a: &str, b: &str) -> f64 {
    let (ca, na) = compute_bigrams(a);
    let (cb, nb) = compute_bigrams(b);
    cosine_similarity_precomputed(&ca, na, &cb, nb)
}

/// Fast cosine similarity using precomputed bigram count maps and L2 norms.
#[inline]
pub fn cosine_similarity_precomputed(
    counts_a: &HashMap<(char, char), u32>,
    norm_a: f64,
    counts_b: &HashMap<(char, char), u32>,
    norm_b: f64,
) -> f64 {
    if norm_a == 0.0 || norm_b == 0.0 {
        return 0.0;
    }
    let mut dot = 0u32;
    for (g, &va) in counts_a.iter() {
        if let Some(&vb) = counts_b.get(g) {
            dot += va * vb;
        }
    }
    dot as f64 / (norm_a * norm_b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_identical_strings() {
        let sim = bigram_cosine("madrid", "madrid");
        assert!((sim - 1.0).abs() < 1e-6);
    }

    #[test]
    fn test_disjoint_strings() {
        let sim = bigram_cosine("abc", "xyz");
        assert_eq!(sim, 0.0);
    }

    #[test]
    fn test_empty_or_single_char_strings() {
        assert_eq!(bigram_cosine("", "madrid"), 0.0);
        assert_eq!(bigram_cosine("a", "madrid"), 0.0);
        assert_eq!(bigram_cosine("", ""), 0.0);
    }

    #[test]
    fn test_partial_similarity() {
        let sim1 = bigram_cosine("madriz", "madrid");
        let sim2 = bigram_cosine("madriz", "barcelona");
        assert!(sim1 > sim2);
        assert!(sim1 > 0.6);
    }
}
