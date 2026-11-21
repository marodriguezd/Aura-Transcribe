use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JFloatArray, JObject};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::engine;
use crate::jni_util;

struct TranscribeFileState {
    jvm: Arc<jni::JavaVM>,
    target_ref: jni::objects::GlobalRef,
    current_cancel: Mutex<Option<Arc<AtomicBool>>>,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(e) => {
            log::error!(
                "TranscribeFileActivity_initNative: failed to get JavaVM: {:?}",
                e
            );
            return;
        }
    };
    let target_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(e) => {
            log::error!(
                "TranscribeFileActivity_initNative: failed to ref activity: {:?}",
                e
            );
            return;
        }
    };

    let state = TranscribeFileState {
        jvm: vm.clone(),
        target_ref: target_ref.clone(),
        current_cancel: Mutex::new(None),
    };
    if let Some(previous_state) = STATE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .replace(state)
    {
        if let Some(prev_cancel) = previous_state
            .current_cancel
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
        {
            prev_cancel.store(true, Ordering::SeqCst);
        }
    }

    // Load engine in background: decode setup must not wait for model I/O
    let vm_clone = vm.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(previous_state) = STATE.lock().unwrap_or_else(|p| p.into_inner()).take() {
        if let Some(prev_cancel) = previous_state
            .current_cancel
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
        {
            prev_cancel.store(true, Ordering::SeqCst);
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_transcribeAudio(
    mut env: JNIEnv,
    _class: JClass,
    samples_array: JFloatArray,
    length: jint,
    op_id: jint,
) {
    let len = usize::try_from(length).unwrap_or(0);
    if len == 0 {
        log::warn!(
            "transcribeAudio called with empty/invalid buffer: {}",
            length
        );
        let target = {
            let guard = STATE.lock().unwrap_or_else(|p| p.into_inner());
            guard
                .as_ref()
                .map(|s| (s.jvm.clone(), s.target_ref.clone()))
        };
        if let Some((jvm, target_ref)) = target {
            if let Ok(mut env) = jvm.attach_current_thread() {
                jni_util::notify_status_with_session(
                    &mut env,
                    target_ref.as_obj(),
                    "Error: no audio data to transcribe",
                    op_id,
                );
            }
        }
        return;
    }

    if STATE.lock().unwrap_or_else(|p| p.into_inner()).is_none() {
        return;
    }

    // Allocate and copy the PCM array outside `STATE` so `cancelNative`/`cleanupNative`
    // cannot stall behind a large JNI transfer.
    let mut buffer = vec![0.0f32; len];
    if env
        .get_float_array_region(&samples_array, 0, &mut buffer)
        .is_err()
    {
        jni_util::clear_exception(&mut env, "transcribeAudio get_float_array_region");
        log::error!("Failed to read float array from Java");
        return;
    }

    let cancelled = Arc::new(AtomicBool::new(false));
    let (jvm, target_ref) = {
        let guard = STATE.lock().unwrap_or_else(|p| p.into_inner());
        let state = match guard.as_ref() {
            Some(s) => s,
            None => return,
        };
        if let Some(previous) = state
            .current_cancel
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .replace(cancelled.clone())
        {
            previous.store(true, Ordering::SeqCst);
        }
        (state.jvm.clone(), state.target_ref.clone())
    };

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Ensure engine is loaded (waits if another thread is loading)
        if engine::get_engine().is_none() {
            if cancelled.load(Ordering::SeqCst) {
                return;
            }
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                return;
            }
        }

        if cancelled.load(Ordering::SeqCst) {
            return;
        }
        if let Some(eng_arc) = engine::get_engine() {
            jni_util::notify_status_with_session(&mut env, obj, "Transcribing...", op_id);

            let res = engine::transcribe_shared(&eng_arc, buffer);

            if cancelled.load(Ordering::SeqCst) {
                return;
            }
            match res {
                Ok(text) => {
                    if !cancelled.load(Ordering::SeqCst) {
                        jni_util::notify_text_with_session(&mut env, obj, &text, op_id);
                    }
                }
                Err(e) => {
                    if !cancelled.load(Ordering::SeqCst) {
                        jni_util::notify_status_with_session(
                            &mut env,
                            obj,
                            &format!("Error: {}", e),
                            op_id,
                        );
                    }
                }
            }
        } else if !cancelled.load(Ordering::SeqCst) {
            jni_util::notify_status_with_session(&mut env, obj, "Error: model not loaded", op_id);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cancelTranscription(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(state) = STATE.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        if let Some(cancelled) = state
            .current_cancel
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
        {
            cancelled.store(true, Ordering::SeqCst);
        }
    }
}
