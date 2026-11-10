//! Centralized JNI callbacks and helpers.
//!
//! Provides clean, reusable wrappers for calling standard Java callbacks
//! (`onStatusUpdate`, `onTextTranscribed`, `onPartialText`, `onAudioLevel`, `onSubtitleText`, `onAutoStop`)
//! safely from Rust code across all surfaces.

use jni::objects::{JObject, JValue};
use jni::JNIEnv;

/// Clears a pending Java exception, if there is one, and reports whether one
/// was present. A pending exception must never survive into the next JNI call
/// on the same thread: `call_method` returns `Err(JavaException)` without
/// clearing it, and later calls on a thread with a pending exception fail (or
/// are undefined for non-exception-safe functions).
#[inline]
fn clear_pending_exception(env: &mut JNIEnv) -> bool {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        true
    } else {
        false
    }
}

/// Invokes a void Java callback and clears any exception it leaves pending, so
/// the calling native thread (for example a cpal audio callback) can keep
/// issuing JNI calls. Logs the failure and returns `false` when the callback
/// could not be delivered; the caller's native flow must continue regardless
/// (recognition state lives in Rust, not in the callback).
pub fn call_and_clear(
    env: &mut JNIEnv,
    obj: &JObject,
    method: &str,
    sig: &str,
    args: &[JValue<'_, '_>],
) -> bool {
    let called = env.call_method(obj, method, sig, args).is_ok();
    let pending = clear_pending_exception(env);
    if !called || pending {
        log::warn!("JNI callback {} failed; its exception was cleared", method);
        false
    } else {
        true
    }
}

/// Reports a failed JNI operation and clears any exception it left pending
/// (for example `new_string` returning `Err` on a large payload). Use it after
/// an `Err` result, before the next JNI call on the same thread; use
/// [`call_and_clear`] for callbacks.
pub fn clear_exception(env: &mut JNIEnv, context: &str) {
    clear_pending_exception(env);
    log::warn!(
        "JNI operation {} failed; its exception was cleared",
        context
    );
}

/// Invokes `onStatusUpdate(String)` on the target Java object.
pub fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        call_and_clear(
            env,
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

/// Invokes the recording-scoped status callback. The Java side rejects the
/// callback when its generation no longer matches the active recording.
pub fn notify_status_with_session(env: &mut JNIEnv, obj: &JObject, msg: &str, session_id: i32) {
    if let Ok(jmsg) = env.new_string(msg) {
        call_and_clear(
            env,
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;I)V",
            &[(&jmsg).into(), session_id.into()],
        );
    }
}

/// Invokes the recording-scoped final-text callback.
pub fn notify_text_with_session(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    if let Ok(jtxt) = env.new_string(text) {
        call_and_clear(
            env,
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;I)V",
            &[(&jtxt).into(), session_id.into()],
        );
    }
}

/// Invokes the recording-scoped partial-text callback.
pub fn notify_partial_with_session(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    if let Ok(jtxt) = env.new_string(text) {
        call_and_clear(
            env,
            obj,
            "onPartialText",
            "(Ljava/lang/String;I)V",
            &[(&jtxt).into(), session_id.into()],
        );
    }
}

/// Invokes the recording-scoped audio-level callback.
pub fn notify_level_with_session(env: &mut JNIEnv, obj: &JObject, level: f32, session_id: i32) {
    call_and_clear(
        env,
        obj,
        "onAudioLevel",
        "(FI)V",
        &[level.into(), session_id.into()],
    );
}

/// Invokes `onSubtitleText(String, boolean)` on the target Java object.
pub fn notify_subtitle(env: &mut JNIEnv, obj: &JObject, text: &str, is_final: bool) {
    if let Ok(jtxt) = env.new_string(text) {
        call_and_clear(
            env,
            obj,
            "onSubtitleText",
            "(Ljava/lang/String;Z)V",
            &[(&jtxt).into(), is_final.into()],
        );
    }
}

/// Invokes the recording-scoped auto-stop callback.
pub fn notify_auto_stop_with_session(env: &mut JNIEnv, obj: &JObject, session_id: i32) {
    call_and_clear(env, obj, "onAutoStop", "(I)V", &[session_id.into()]);
}
