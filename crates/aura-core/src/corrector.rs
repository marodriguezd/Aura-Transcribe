//! Custom dictionary phonetic corrector (post-ASR text correction).
//!
//! Replaces misrecognized words with terms from a user-defined dictionary by
//! matching phonetic distance (Spanish + English aware) and character-bigram
//! cosine similarity for tiebreaking, while preserving original casing.

use std::collections::HashMap;

use crate::bigram::{compute_bigrams, cosine_similarity_precomputed};
use crate::phonetic::{levenshtein_distance, phonetic_key};

/// Maximum Levenshtein distance between two phonetic keys to count as a match.
pub const MAX_PHONETIC_DISTANCE: usize = 2;

/// A dictionary entry with precomputed lowercase form, phonetic key, and bigrams.
#[derive(Clone, Debug, PartialEq)]
pub struct Term {
    /// The dictionary term as written (preserves casing, e.g. "Madrid", "New York").
    pub text: String,
    /// Lowercased term, precomputed once for fast exact and orthographic matching.
    pub lower: String,
    /// Phonetic key of the lowercased term.
    pub key: String,
    /// Precomputed character-bigram counts for zero-allocation cosine tiebreaking.
    pub bigrams: HashMap<(char, char), u32>,
    /// Precomputed L2 norm of bigram vector.
    pub bigram_norm: f64,
}

/// In-memory dictionary holding single- and multi-word terms.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Dictionary {
    /// Single-word terms.
    pub single: Vec<Term>,
    /// Multi-word terms (≥2 whitespace-separated words), grouped by word count.
    pub multi: HashMap<usize, Vec<Term>>,
}

impl Dictionary {
    /// Returns true if the dictionary contains no single or multi-word terms.
    pub fn is_empty(&self) -> bool {
        self.single.is_empty() && self.multi.is_empty()
    }

    /// Total number of terms in the dictionary.
    pub fn len(&self) -> usize {
        self.single.len() + self.multi.values().map(|v| v.len()).sum::<usize>()
    }
}

/// Parses raw dictionary text (one term per line) into single- and multi-word terms.
/// Lines starting with '#' or blank lines are ignored.
pub fn parse_dict(raw: &str) -> Dictionary {
    let mut single = Vec::new();
    let mut multi: HashMap<usize, Vec<Term>> = HashMap::new();
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let lower = trimmed.to_lowercase();
        let key = phonetic_key(&lower);
        let (bigrams, bigram_norm) = compute_bigrams(&lower);
        let word_count = trimmed.split_whitespace().count();
        let term = Term {
            text: trimmed.to_string(),
            lower,
            key,
            bigrams,
            bigram_norm,
        };
        if word_count > 1 {
            multi.entry(word_count).or_default().push(term);
        } else {
            single.push(term);
        }
    }
    Dictionary { single, multi }
}

/// Returns the best matching dictionary term for `word_lower`, or `None`.
/// Tries an exact case-insensitive match first, then a phonetic Levenshtein pass
/// with an orthographic bigram cosine tiebreak.
pub fn best_term(word_lower: &str, terms: &[Term]) -> Option<String> {
    // Exact match first: a case-insensitive hit wins without phonetic work
    for t in terms {
        if t.lower == word_lower {
            return Some(t.text.clone());
        }
    }

    // Phonetic match: minimize Levenshtein distance on phonetic keys, break ties
    // with highest orthographic cosine similarity.
    let key = phonetic_key(word_lower);
    let key_len = key.len();
    let mut best: Option<(usize, f64, usize)> = None; // (distance, -cosine, index)
    let mut word_bigrams: Option<(HashMap<(char, char), u32>, f64)> = None;

    for (idx, t) in terms.iter().enumerate() {
        let max_dist = if key_len <= 4 || t.key.len() <= 4 {
            1
        } else {
            MAX_PHONETIC_DISTANCE
        };
        let len_diff = key_len.abs_diff(t.key.len());
        if len_diff > max_dist {
            continue;
        }
        let dist = levenshtein_distance(&key, &t.key);
        if dist > max_dist {
            continue;
        }

        // Lazy-compute bigrams for the search word only when candidate passes phonetic filter
        let (wb, wb_norm) = word_bigrams.get_or_insert_with(|| compute_bigrams(word_lower));
        let sim = cosine_similarity_precomputed(wb, *wb_norm, &t.bigrams, t.bigram_norm);
        let neg = -sim;
        match best {
            Some((bd, bn, _)) if (dist, neg) >= (bd, bn) => {}
            _ => best = Some((dist, neg, idx)),
        }
    }
    best.map(|(_, _, idx)| terms[idx].text.clone())
}

/// Applies the capitalization context of `original` to `term`.
pub fn apply_case(term: &str, original: &str) -> String {
    let letters: Vec<char> = original.chars().filter(|c| c.is_alphabetic()).collect();
    if !letters.is_empty() && letters.iter().all(|c| c.is_uppercase()) {
        term.to_uppercase()
    } else {
        term.to_string()
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Segment {
    Word(String),
    Sep(String),
}

/// Checks whether a character is part of a word.
pub fn is_word_char(c: char, prev: char, next: char) -> bool {
    if c.is_alphanumeric() || c == '\'' || c == '\u{2019}' {
        return true;
    }
    if (c == '.' || c == '-' || c == '_') && prev.is_alphanumeric() && next.is_alphanumeric() {
        return true;
    }
    false
}

/// Splits text into word and separator segments, preserving formatting and punctuation.
pub fn tokenize(text: &str) -> Vec<Segment> {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut out = Vec::new();
    let mut buf = String::new();
    let mut is_word = false;
    for i in 0..n {
        let c = chars[i];
        let prev = if i > 0 { chars[i - 1] } else { '\0' };
        let next = chars.get(i + 1).copied().unwrap_or('\0');
        let wc = is_word_char(c, prev, next);
        if wc {
            if !is_word {
                if !buf.is_empty() {
                    out.push(Segment::Sep(std::mem::take(&mut buf)));
                }
                is_word = true;
            }
            buf.push(c);
        } else {
            if is_word {
                if !buf.is_empty() {
                    out.push(Segment::Word(std::mem::take(&mut buf)));
                }
                is_word = false;
            }
            buf.push(c);
        }
    }
    if !buf.is_empty() {
        if is_word {
            out.push(Segment::Word(buf));
        } else {
            out.push(Segment::Sep(buf));
        }
    }
    out
}

/// Corrects transcript text against the given dictionary.
pub fn correct(text: &str, dict: &Dictionary) -> String {
    if text.trim().is_empty() || dict.is_empty() {
        return text.to_string();
    }

    let segments = tokenize(text);
    let word_indices: Vec<usize> = segments
        .iter()
        .enumerate()
        .filter_map(|(i, s)| {
            if matches!(s, Segment::Word(_)) {
                Some(i)
            } else {
                None
            }
        })
        .collect();
    let words: Vec<String> = word_indices
        .iter()
        .filter_map(|&i| {
            if let Segment::Word(w) = &segments[i] {
                Some(w.to_lowercase())
            } else {
                None
            }
        })
        .collect();

    let max_window = dict.multi.keys().copied().max().unwrap_or(0);

    // Reassembly buffer: start from original segments, mutate word slots.
    let mut output: Vec<String> = segments
        .iter()
        .map(|s| match s {
            Segment::Word(w) => w.clone(),
            Segment::Sep(s) => s.clone(),
        })
        .collect();

    let mut i = 0;
    while i < words.len() {
        // Multi-word match (longest window first).
        let mut matched = false;
        if max_window >= 2 {
            let upper = max_window.min(words.len() - i);
            for window in (2..=upper).rev() {
                let candidates = match dict.multi.get(&window) {
                    Some(c) if !c.is_empty() => c.as_slice(),
                    _ => continue,
                };
                let joined: String = (i..i + window)
                    .map(|j| words[j].as_str())
                    .collect::<Vec<_>>()
                    .join(" ");
                if let Some(term) = best_term(&joined, candidates) {
                    let term_words: Vec<&str> = term.split_whitespace().collect();
                    for (j, tw) in term_words.iter().enumerate() {
                        let seg_idx = word_indices[i + j];
                        if let Segment::Word(orig) = &segments[seg_idx] {
                            output[seg_idx] = apply_case(tw, orig);
                        }
                    }
                    matched = true;
                    i += window;
                    break;
                }
            }
        }
        if matched {
            continue;
        }

        // Single-word match.
        let w = &words[i];
        if let Some(term) = best_term(w, &dict.single) {
            let seg_idx = word_indices[i];
            if let Segment::Word(orig) = &segments[seg_idx] {
                output[seg_idx] = apply_case(&term, orig);
            }
        }
        i += 1;
    }

    output.concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dict_single(words: &[&str]) -> Dictionary {
        parse_dict(&words.join("\n"))
    }

    #[test]
    fn unrelated_words_are_never_replaced() {
        let d = dict_single(&["table", "Madrid", "Barcelona", "whisper", "modelo"]);
        assert_eq!(
            correct("I saw a house in the street and wanted to talk", &d),
            "I saw a house in the street and wanted to talk"
        );
    }

    #[test]
    fn exact_match_upgrades_casing() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("I live in madrid", &d), "I live in Madrid");
    }

    #[test]
    fn phonetic_match_replaces_misspelling() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("I love madriz", &d), "I love Madrid");
    }

    #[test]
    fn preserves_allcaps_context() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("MADRIZ is great", &d), "MADRID is great");
    }

    #[test]
    fn no_match_leaves_word_unchanged() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("hello world", &d), "hello world");
    }

    #[test]
    fn seseo_matches_barcelona() {
        let d = dict_single(&["Barcelona"]);
        assert_eq!(correct("I visited barselona", &d), "I visited Barcelona");
    }

    #[test]
    fn v_to_b_matches() {
        let d = dict_single(&["vaca"]);
        assert_eq!(correct("I saw a baca", &d), "I saw a vaca");
    }

    #[test]
    fn punctuation_preserved() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("madrid, city of", &d), "Madrid, city of");
    }

    #[test]
    fn empty_dictionary_passthrough() {
        let d = Dictionary::default();
        assert_eq!(correct("anything goes", &d), "anything goes");
    }

    #[test]
    fn multi_word_term_matched() {
        let d = parse_dict("New York");
        assert_eq!(correct("I live in new york", &d), "I live in New York");
    }

    #[test]
    fn word_not_in_dictionary_is_preserved() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("café", &d), "café");
    }

    #[test]
    fn empty_input_passthrough() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("", &d), "");
    }

    #[test]
    fn test_apply_case() {
        assert_eq!(apply_case("madrid", "MADRID"), "MADRID");
        assert_eq!(apply_case("Madrid", "Mad"), "Madrid");
        assert_eq!(apply_case("madrid", "123"), "madrid");
    }
}
