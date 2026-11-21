use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

/// Poison-tolerant lock on the shared IME session state. If a prior call
/// panicked while holding the lock, recover the poisoned guard instead of
/// panicking on unwrap.
fn with_ime_state<T>(f: impl FnOnce(&mut Option<VoiceSessionState>) -> T) -> T {
    let mut guard = IME_STATE.lock().unwrap_or_else(|p| p.into_inner());
    f(&mut guard)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    if let Some(state) = voice_session::init_session(env, service) {
        *IME_STATE.lock().unwrap_or_else(|p| p.into_inner()) = Some(state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *IME_STATE.lock().unwrap_or_else(|p| p.into_inner()) = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
    auto_stop: jni::sys::jboolean,
    session_id: jni::sys::jint,
) {
    with_ime_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::start_recording(env, state, auto_stop != 0, session_id as i32);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    with_ime_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::stop_recording(env, state);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    with_ime_state(|state| {
        if let Some(state) = state.as_mut() {
            voice_session::cancel_recording(env, state);
        }
    });
}
