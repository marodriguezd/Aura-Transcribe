//! # aura-core
//!
//! Pure Rust text processing engine for Spanish/English phonetic encoding,
//! orthographic bigram cosine tiebreaking, and custom dictionary phonetic
//! correction.
//!
//! Designed to run 100% offline with zero Android/NDK dependencies, making it
//! fully testable and runnable natively on any architecture.

pub mod bigram;
pub mod corrector;
pub mod phonetic;

// Convenient re-exports: single import path for downstream crates
pub use bigram::{bigram_cosine, compute_bigrams, cosine_similarity_precomputed};
pub use corrector::{
    apply_case, best_term, correct, is_word_char, parse_dict, tokenize, Dictionary, Segment, Term,
    MAX_PHONETIC_DISTANCE,
};
pub use phonetic::{collapse_duplicates, levenshtein_distance, phonetic_distance, phonetic_key};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_end_to_end_aura_core_pipeline() {
        let raw_dict = "
            # Custom technical & geographical terms
            Madrid
            Barcelona
            Whisper
            SuperWhisper
            New York
        ";
        let dict = parse_dict(raw_dict);
        assert_eq!(dict.len(), 5);

        // Phonetic correction: seseo-tolerant lookup must restore canonical casing
        let input = "ayer viaje a madriz y luego a barselona con wisper en new york";
        let corrected = correct(input, &dict);
        assert_eq!(
            corrected,
            "ayer viaje a Madrid y luego a Barcelona con Whisper en New York"
        );
    }
}
