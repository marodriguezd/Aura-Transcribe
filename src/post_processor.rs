//! On-device AI text normalization using SuperWhisper S1-mini (0.6B GGUF).
//!
//! Provides 100% offline, privacy-first cleanup of raw ASR speech transcripts
//! (removing fillers, resolving false starts, formatting spoken numbers/dates,
//! applying punctuation and tone steering) using a quantized Qwen-based GGUF.

use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;

pub use aura_core::normalizer::*;

/// Global state holding the active S1 model context.
static S1_STATE: Lazy<Mutex<S1Manager>> = Lazy::new(|| Mutex::new(S1Manager::new()));

/// Manages S1-mini model state and memory lifecycle.
pub struct S1Manager {
    model_path: Option<PathBuf>,
    is_loaded: bool,
}

impl S1Manager {
    pub fn new() -> Self {
        Self {
            model_path: None,
            is_loaded: false,
        }
    }

    pub fn set_model_path(&mut self, path: PathBuf) {
        if self.model_path.as_ref() != Some(&path) {
            self.model_path = Some(path);
            self.unload();
        }
    }

    pub fn is_loaded(&self) -> bool {
        self.is_loaded
    }

    pub fn unload(&mut self) {
        if self.is_loaded {
            log::info!("S1Manager: unloading S1-mini model from RAM");
            self.is_loaded = false;
        }
    }
}

/// Normalizes transcript text on-device.
///
/// # Status: PLACEHOLDER — no inference is performed here
/// Validates that the downloaded model file exists on disk, updates `is_loaded`,
/// and returns the trimmed input text.
///
/// Text generation inference requires a causal LM engine (e.g. llama.cpp) not
/// currently bundled by `transcribe-cpp`. Controlled by
/// `SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE = false` (see AGENTS.md §5.4).
pub fn normalize_text_on_device(
    raw_text: &str,
    preset: &str,
    _custom_prompt: Option<&str>,
) -> Result<String, String> {
    if raw_text.trim().is_empty() {
        return Ok(raw_text.to_string());
    }

    if preset.eq_ignore_ascii_case("verbatim") || preset.eq_ignore_ascii_case("literal") {
        return Ok(raw_text.to_string());
    }

    let mut guard = S1_STATE.lock().unwrap_or_else(|p| p.into_inner());

    // Guard: refuse without a downloaded S1 model file on disk
    let _model_path = match &guard.model_path {
        Some(p) if p.exists() => p.clone(),
        _ => {
            return Err("S1 model file not found on device".to_string());
        }
    };

    log::info!(
        "S1Manager: normalizing text on-device (length: {}, preset: '{}')",
        raw_text.len(),
        preset
    );

    guard.is_loaded = true;

    // Placeholder path: return trimmed input until real inference lands
    let cleaned = raw_text.trim().to_string();
    Ok(cleaned)
}

// JNI exports for dev.notune.transcribe.PostProcessor

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_PostProcessor_nativeNormalizeOnDevice(
    mut env: JNIEnv,
    _class: JClass,
    raw_text: JString,
    preset: JString,
    custom_prompt: JString,
) -> jstring {
    let raw_str: String = match env.get_string(&raw_text) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let preset_str: String = match env.get_string(&preset) {
        Ok(s) => s.into(),
        Err(_) => "clean".to_string(),
    };

    let custom_str: Option<String> = if !custom_prompt.as_raw().is_null() {
        env.get_string(&custom_prompt).ok().map(|s| s.into())
    } else {
        None
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        normalize_text_on_device(&raw_str, &preset_str, custom_str.as_deref())
    }))
    .unwrap_or_else(|_| Err("Panic during on-device normalization".to_string()));

    match result {
        Ok(text) => match env.new_string(text) {
            Ok(jstr) => jstr.as_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            log::warn!("nativeNormalizeOnDevice error: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_PostProcessor_nativeSetS1ModelPath(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) {
    if let Ok(p_str) = env.get_string(&path) {
        let path_buf = PathBuf::from(p_str.to_str().unwrap_or(""));
        let mut guard = S1_STATE.lock().unwrap_or_else(|p| p.into_inner());
        guard.set_model_path(path_buf);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_PostProcessor_nativeUnloadS1(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = S1_STATE.lock().unwrap_or_else(|p| p.into_inner());
    guard.unload();
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_PostProcessor_nativeIsS1Loaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let guard = S1_STATE.lock().unwrap_or_else(|p| p.into_inner());
    if guard.is_loaded() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_PostProcessor_nativeTrimMemory(
    _env: JNIEnv,
    _class: JClass,
    level: jint,
) {
    // Android ComponentCallbacks2 levels:
    // TRIM_MEMORY_RUNNING_CRITICAL = 15, TRIM_MEMORY_COMPLETE = 80, TRIM_MEMORY_MODERATE = 60
    if level >= 15 {
        log::info!(
            "S1Manager: onTrimMemory level {}, unloading S1 model",
            level
        );
        let mut guard = S1_STATE.lock().unwrap_or_else(|p| p.into_inner());
        guard.unload();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_build_s1_prompt_presets() {
        let prompt_clean = build_s1_prompt("hola mundo", "clean", None);
        assert!(prompt_clean.contains("[Styling: semi-formal]"));
        assert!(prompt_clean.contains("hola mundo"));
        assert!(prompt_clean.contains(S1_SYSTEM_PROMPT));

        let prompt_formal = build_s1_prompt("hola mundo", "formal", None);
        assert!(prompt_formal.contains("[Styling: formal]"));

        let prompt_casual = build_s1_prompt("hola mundo", "casual", None);
        assert!(prompt_casual.contains("[Styling: casual]"));
    }

    #[test]
    fn test_custom_prompt_override() {
        let prompt_custom =
            build_s1_prompt("test", "clean", Some("[Styling: formal] [Context: email]"));
        assert!(prompt_custom.contains("[Styling: formal] [Context: email]"));
    }
}
