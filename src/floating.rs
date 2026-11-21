use std::sync::Mutex;

use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::voice_session::{self, VoiceSessionState};

static FLOATING_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

/// Poison-tolerant lock on the shared floating session state. If a prior call
/// panicked while holding the lock, a plain `.unwrap()` here would panic on
/// the next JNI entry and take the whole process down with it (crash loop on
/// a restarting START_STICKY service). Recover the poisoned guard instead,
/// matching the engine/session resilience pattern elsewhere in the crate.
fn with_floating_state<T>(f: impl FnOnce(&mut Option<VoiceSessionState>) -> T) -> T {
    let mut guard = FLOATING_STATE.lock().unwrap_or_else(|p| p.into_inner());
    f(&mut guard)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    if let Some(state) = voice_session::init_session(env, service) {
        *FLOATING_STATE.lock().unwrap_or_else(|p| p.into_inner()) = Some(state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *FLOATING_STATE.lock().unwrap_or_else(|p| p.into_inner()) = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_startRecording(
    env: JNIEnv,
    _class: JClass,
    auto_stop: jni::sys::jboolean,
    session_id: jni::sys::jint,
) {
    with_floating_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::start_recording(env, state, auto_stop != 0, session_id as i32);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    with_floating_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::stop_recording(env, state);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    with_floating_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::cancel_recording(env, state);
        }
    });
}
