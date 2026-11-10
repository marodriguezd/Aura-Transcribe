//! Custom-word phonetic correction (post-ASR, pre-delivery).
//!
//! Applies a user-maintained dictionary of "correct" terms to transcript text.
//! Pure phonetic matching, bigram similarity, and dictionary parsing logic
//! are implemented in the decoupled pure Rust `aura-core` workspace crate.

use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

pub use aura_core::bigram::*;
pub use aura_core::corrector::*;
pub use aura_core::phonetic::*;

/// Marker file in filesDir holding the user's terms, one per line. Lines
/// starting with `#` are comments; blank lines are ignored.
pub const CUSTOM_WORDS_FILE: &str = "custom_words";

/// Cached dictionary + the mtime of the file it was loaded from, so we only
/// re-read when the user edits the list.
static CACHE: Lazy<Mutex<Option<CachedDict>>> = Lazy::new(|| Mutex::new(None));

/// filesDir path, published by the engine once it has resolved it. Stored
/// globally because `transcribe_shared` runs on worker threads without JNI
/// access; the engine's `do_load` (which has JNIEnv) sets it via
/// [`set_files_dir`].
static FILES_DIR: Lazy<Mutex<Option<PathBuf>>> = Lazy::new(|| Mutex::new(None));

#[derive(Clone)]
struct CachedDict {
    mtime: SystemTime,
    dict: Arc<Dictionary>,
}

/// Publish the filesDir path so the corrector can locate the dictionary.
/// Called once from `engine::do_load` after it resolves filesDir.
pub fn set_files_dir(dir: &Path) {
    *FILES_DIR.lock().unwrap_or_else(|p| p.into_inner()) = Some(dir.to_path_buf());
}

/// Corrects `text` against the user's custom dictionary, if one is present
/// and non-empty. Returns the text unchanged when no dictionary exists, when
/// the engine hasn't published a filesDir yet, or on any I/O error —
/// correction must never lose text, so the safe fallback is always the raw
/// transcript.
pub fn correct_if_enabled(text: &str) -> String {
    if text.trim().is_empty() {
        return text.to_string();
    }
    let dict = match load_dict() {
        Some(d) => d,
        None => return text.to_string(),
    };
    if dict.is_empty() {
        return text.to_string();
    }
    correct(text, &dict)
}

/// Loads the dictionary from disk, returning the cached copy when the file's
/// mtime is unchanged. Returns `None` if no filesDir is set, the file is
/// absent, or reading fails — all treated as "no correction" (safe fallback).
fn load_dict() -> Option<Arc<Dictionary>> {
    let dir = FILES_DIR
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()?;
    let path = dir.join(CUSTOM_WORDS_FILE);
    let metadata = std::fs::metadata(&path).ok()?;
    let mtime = metadata.modified().ok()?;

    {
        let cache = CACHE.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(cached) = cache.as_ref() {
            if cached.mtime == mtime {
                return Some(cached.dict.clone());
            }
        }
    }

    let raw = match std::fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("custom_words: failed to read {}: {}", path.display(), e);
            return None;
        }
    };
    let dict = parse_dict(&raw);
    log::info!(
        "custom_words: loaded {} single + {} multi term(s)",
        dict.single.len(),
        dict.multi.values().map(|v| v.len()).sum::<usize>()
    );
    let dict = Arc::new(dict);
    *CACHE.lock().unwrap_or_else(|p| p.into_inner()) = Some(CachedDict {
        mtime,
        dict: dict.clone(),
    });
    Some(dict)
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
    fn phonetic_key_is_case_folded() {
        assert_eq!(phonetic_key("Madrid"), phonetic_key("madrid"));
        assert_eq!(phonetic_key("BARCELONA"), phonetic_key("Barcelona"));
    }

    #[test]
    fn apply_case_upper_original_uppers_term() {
        assert_eq!(apply_case("madrid", "MADRID"), "MADRID");
    }

    #[test]
    fn apply_case_mixed_keeps_term_casing() {
        assert_eq!(apply_case("Madrid", "Mad"), "Madrid");
    }

    #[test]
    fn apply_case_no_letters_returns_term() {
        assert_eq!(apply_case("madrid", "123"), "madrid");
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
}
