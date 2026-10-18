//! Native backend for `VoiceRecognitionService`, the `android.speech.RecognitionService`
//! implementation that lets *other* keyboards/apps (SwiftKey, Gboard, …) use this app
//! as their offline speech-to-text provider via the system `SpeechRecognizer` API.
//!
//! Unlike the IME / `RecognizeActivity` surfaces (which have their own UI and a manual
//! "tap to stop" control via `voice_session`), a `RecognitionService` has no UI of its
//! own: the calling keyboard expects *us* to decide when the user has finished speaking.
//! So this module adds trailing-silence endpointing on top of the same `engine` model,
//! and finalises automatically (it also honours an explicit `stopListening`/`cancel`).

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::engine;
use crate::jni_util;
use crate::voice_session::SendStream;

// --- Endpointing / VAD tuning -------------------------------------------------
// These are deliberately simple heuristics on the smoothed mic level. Mic gain
// varies a lot between devices, so they may need tuning; finalisation always
// transcribes whatever was captured, so a mis-tuned threshold only affects the
// auto-stop *timing*, never whether text is returned.
//
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after confirmed speech that triggers auto-finalisation.
const SILENCE_MS: u64 = 1500;
/// Require a short continuous speech run before arming trailing-silence
/// endpointing. This filters microphone pops/initial noise without dropping
/// samples: the complete audio buffer is still retained for transcription.
const MIN_SPEECH_SAMPLES: usize = 1_600; // 100 ms at 16 kHz
/// If no speech is ever detected, finalise after this long anyway.
const NO_SPEECH_TIMEOUT_MS: u64 = 7000;
/// Hard cap on a single utterance (the engine internally chunks long audio).
const MAX_SESSION_MS: u64 = 60000;
/// Throttle interval for `rmsChanged` UI callbacks (10 Hz is plenty for a
/// keyboard waveform).
const LEVEL_UPDATE_MS: u64 = 100;

// Mirror of android.speech.SpeechRecognizer error codes we report.
#[allow(dead_code)]
const ERROR_AUDIO: i32 = 3;
const ERROR_SERVER: i32 = 4;
const ERROR_NO_MATCH: i32 = 7;

/// State shared between the audio callback, the endpoint-monitor thread and the
/// finaliser. Deliberately does NOT hold the cpal stream, to avoid an Arc cycle
/// (the stream's callback holds an `Arc<Endpoint>`).
struct Endpoint {
    /// Java generation for this recognition session.
    session_id: i32,
    audio_buffer: Mutex<Vec<f32>>,
    /// Total samples ever pushed (monotonic, unlike the drained buffer) —
    /// used for the minimum-audio check before transcribing.
    total_pushed: AtomicUsize,
    last_voice_ms: AtomicU64,
    noise_floor_bits: AtomicU32,
    last_level_sent_ms: AtomicU64,
    speech_started: AtomicBool,
    speech_run_samples: AtomicUsize,
    finalized: AtomicBool,
    cancelled: AtomicBool,
    started_at: Instant,
    /// Command channel to the streaming pump (created per session).
    stream_cmd_tx: Mutex<Option<crossbeam_channel::Sender<crate::engine::StreamCmd>>>,
    /// True while the streaming pump is actively streaming (so finalize
    /// routes to the pump instead of the whole-buffer path).
    streaming_active: AtomicBool,
    /// Serializes the finalize-vs-pump-start decision so exactly one path
    /// delivers the transcription.
    stream_lock: Mutex<()>,
    jvm: Arc<jni::JavaVM>,
    target: GlobalRef,
}

struct Session {
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
}

static SESSION: Lazy<Mutex<Option<Session>>> = Lazy::new(|| Mutex::new(None));

// --- JNI callbacks into VoiceRecognitionService -------------------------------
//
// Every callback goes through `jni_util::call_and_clear`: `call_method`
// reports a Java exception as `Err` but does NOT clear it, and a pending
// exception would break every later JNI call on the same thread — for the
// audio callback that is the cpal thread itself, so an uncleared failure would
// silence the rms/level stream and, right after `onBeginningOfSpeech`, the
// final results too. A failed callback is logged and cleared; native state
// (endpointing, finalisation) is unaffected.
//
// The `new_string` failure paths clear as well: allocation failures can leave
// a pending OutOfMemoryError behind.

fn call_rms(env: &mut JNIEnv, obj: &JObject, rms_db: f32, session_id: i32) {
    jni_util::call_and_clear(
        env,
        obj,
        "onRmsChanged",
        "(FI)V",
        &[rms_db.into(), session_id.into()],
    );
}

fn call_error(env: &mut JNIEnv, obj: &JObject, code: i32, session_id: i32) {
    jni_util::call_and_clear(
        env,
        obj,
        "onError",
        "(II)V",
        &[code.into(), session_id.into()],
    );
}

fn call_results(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    match env.new_string(text) {
        Ok(jtxt) => {
            jni_util::call_and_clear(
                env,
                obj,
                "onResults",
                "(Ljava/lang/String;I)V",
                &[(&jtxt).into(), session_id.into()],
            );
        }
        Err(e) => {
            log::warn!("Could not allocate onResults text: {}", e);
            jni_util::clear_exception(env, "onResults");
        }
    }
}

fn call_partial(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    match env.new_string(text) {
        Ok(jtxt) => {
            jni_util::call_and_clear(
                env,
                obj,
                "onPartialText",
                "(Ljava/lang/String;I)V",
                &[(&jtxt).into(), session_id.into()],
            );
        }
        Err(e) => {
            // Partials are visual-only: report and keep streaming.
            log::warn!("Could not allocate onPartialText text: {}", e);
            jni_util::clear_exception(env, "onPartialText");
        }
    }
}

// --- JNI entry points ---------------------------------------------------------

/// Called from `onCreate`. Warms up the model in the background so the first
/// recognition after a cold bind is as fast as possible.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
    });
}

/// Called from `onStartListening`. Begins microphone capture and arms the
/// silence-based endpoint monitor.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListening(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
    session_id: jni::sys::jint,
) {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    // Tear down any session that is still around (e.g. the keyboard called
    // startListening twice without cancel) so its monitor/finaliser can never
    // deliver stale results to this new session.
    {
        let mut guard = SESSION.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(old) = guard.take() {
            old.shared.cancelled.store(true, Ordering::SeqCst);
            old.shared.finalized.store(true, Ordering::SeqCst);
            if let Some(tx) = old
                .shared
                .stream_cmd_tx
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .clone()
            {
                let _ = tx.send(crate::engine::StreamCmd::Cancel);
            }
            *old.stream.lock().unwrap_or_else(|p| p.into_inner()) = None;
        }
    }

    let now = Instant::now();
    let shared = Arc::new(Endpoint {
        session_id: session_id as i32,
        audio_buffer: Mutex::new(Vec::new()),
        total_pushed: AtomicUsize::new(0),
        last_voice_ms: AtomicU64::new(0),
        noise_floor_bits: AtomicU32::new(0.0f32.to_bits()),
        last_level_sent_ms: AtomicU64::new(0),
        speech_started: AtomicBool::new(false),
        speech_run_samples: AtomicUsize::new(0),
        finalized: AtomicBool::new(false),
        cancelled: AtomicBool::new(false),
        started_at: now,
        stream_cmd_tx: Mutex::new(None),
        streaming_active: AtomicBool::new(false),
        stream_lock: Mutex::new(()),
        jvm: jvm.clone(),
        target,
    });
    let stream_holder: Arc<Mutex<Option<SendStream>>> = Arc::new(Mutex::new(None));

    // Tell the keyboard we're ready to receive speech.
    {
        let mut env2 = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        jni_util::call_and_clear(
            &mut env2,
            shared.target.as_obj(),
            "onReadyForSpeech",
            "(I)V",
            &[shared.session_id.into()],
        );
    }

    // Open the microphone (16 kHz mono, matching the model + voice_session).
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(
                &mut env2,
                shared.target.as_obj(),
                ERROR_AUDIO,
                shared.session_id,
            );
            return;
        }
    };
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    let cb_shared = shared.clone();
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| audio_callback(&cb_shared, data),
        |e| log::error!("RecognitionService stream error: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            *stream_holder.lock().unwrap_or_else(|p| p.into_inner()) = Some(SendStream(s));
        }
        Err(e) => {
            log::error!("Failed to open microphone: {}", e);
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(
                &mut env2,
                shared.target.as_obj(),
                ERROR_AUDIO,
                shared.session_id,
            );
            return;
        }
    }

    // Endpoint monitor.
    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));

    // Streaming pump (Nemotron-family models): feeds the captured audio into
    // a cache-aware transcribe.cpp stream and reports partial hypotheses via
    // onPartialText. Exits silently for models without native streaming, in
    // which case finalize uses the whole-buffer path as before.
    let (cmd_tx, cmd_rx) = crossbeam_channel::unbounded::<crate::engine::StreamCmd>();
    *shared
        .stream_cmd_tx
        .lock()
        .unwrap_or_else(|p| p.into_inner()) = Some(cmd_tx);

    let pump_shared = shared.clone();
    std::thread::spawn(move || streaming_pump(pump_shared, cmd_rx));

    *SESSION.lock().unwrap_or_else(|p| p.into_inner()) = Some(Session {
        shared,
        stream: stream_holder,
    });
}

/// Called from `onStopListening`: the keyboard asked us to finish now. Finalise
/// with whatever we've captured so far.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_stopListening(
    _env: JNIEnv,
    _class: JClass,
) {
    let session = SESSION
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(|s| (s.shared.clone(), s.stream.clone()));
    if let Some((shared, stream)) = session {
        std::thread::spawn(move || finalize(shared, stream));
    }
}

/// Called from `onCancel`: discard everything, return nothing.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = SESSION.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(session) = guard.as_ref() {
        session.shared.cancelled.store(true, Ordering::SeqCst);
        session.shared.finalized.store(true, Ordering::SeqCst);
        if let Some(tx) = session
            .shared
            .stream_cmd_tx
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone()
        {
            let _ = tx.send(crate::engine::StreamCmd::Cancel);
        }
        *session.stream.lock().unwrap_or_else(|p| p.into_inner()) = None;
    }
    *guard = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_destroyNative(
    env: JNIEnv,
    class: JClass,
) {
    Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(env, class);
}

// --- Audio + endpointing ----------------------------------------------------------

fn audio_callback(shared: &Arc<Endpoint>, data: &[f32]) {
    if shared.finalized.load(Ordering::SeqCst) {
        return;
    }

    shared
        .audio_buffer
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .extend_from_slice(data);
    shared.total_pushed.fetch_add(data.len(), Ordering::SeqCst);

    // Vectorized RMS via SIMD/NEON: keep the audio callback O(1)
    let rms = crate::audio::fast_rms(data);
    let level = (rms * 6.0).clamp(0.0, 1.0);

    let floor = f32::from_bits(shared.noise_floor_bits.load(Ordering::Relaxed));
    let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;

    if is_speech {
        let elapsed_ms = shared.started_at.elapsed().as_millis() as u64;
        if shared.speech_started.load(Ordering::SeqCst) {
            // Once speech has been confirmed, every speech frame refreshes the
            // trailing-silence clock (lock-free).
            shared.last_voice_ms.store(elapsed_ms, Ordering::Relaxed);
        } else {
            // Do not arm trailing-silence endpointing for a single microphone
            // pop or initial noise spike. Require a continuous 100 ms speech
            // run first; all samples remain in audio_buffer.
            let run = shared
                .speech_run_samples
                .fetch_add(data.len(), Ordering::SeqCst)
                + data.len();
            if run >= MIN_SPEECH_SAMPLES
                && shared.speech_started.swap(true, Ordering::SeqCst) == false
            {
                shared.last_voice_ms.store(elapsed_ms, Ordering::Relaxed);
                if let Ok(mut env) = shared.jvm.attach_current_thread_permanently() {
                    jni_util::call_and_clear(
                        &mut env,
                        shared.target.as_obj(),
                        "onBeginningOfSpeech",
                        "(I)V",
                        &[shared.session_id.into()],
                    );
                }
            }
        }
    } else {
        if !shared.speech_started.load(Ordering::SeqCst) {
            // The candidate speech run must be continuous.
            shared.speech_run_samples.store(0, Ordering::SeqCst);
        }
        // Slowly adapt the noise floor while no speech is present (lock-free CAS).
        let mut current_bits = shared.noise_floor_bits.load(Ordering::Relaxed);
        loop {
            let current_val = f32::from_bits(current_bits);
            let new_val = (current_val * 0.95 + level * 0.05).clamp(0.0, 1.0);
            match shared.noise_floor_bits.compare_exchange_weak(
                current_bits,
                new_val.to_bits(),
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(b) => current_bits = b,
            }
        }
    }

    // Throttled mic-level updates for the keyboard's waveform UI (lock-free).
    let now_ms = shared.started_at.elapsed().as_millis() as u64;
    let last = shared.last_level_sent_ms.load(Ordering::Relaxed);
    if now_ms >= last + LEVEL_UPDATE_MS {
        shared.last_level_sent_ms.store(now_ms, Ordering::Relaxed);
        if let Ok(mut env) = shared.jvm.attach_current_thread_permanently() {
            call_rms(
                &mut env,
                shared.target.as_obj(),
                level * 10.0,
                shared.session_id,
            );
        }
    }
}

fn endpoint_monitor(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    loop {
        std::thread::sleep(Duration::from_millis(100));

        if shared.cancelled.load(Ordering::SeqCst) || shared.finalized.load(Ordering::SeqCst) {
            return;
        }

        let elapsed = shared.started_at.elapsed();
        let elapsed_ms = elapsed.as_millis() as u64;
        let speech = shared.speech_started.load(Ordering::SeqCst);
        let last_voice_ms = shared.last_voice_ms.load(Ordering::Relaxed);
        let silence_ms = elapsed_ms.saturating_sub(last_voice_ms);

        let done = (speech && silence_ms >= SILENCE_MS)
            || elapsed >= Duration::from_millis(MAX_SESSION_MS)
            || (!speech && elapsed >= Duration::from_millis(NO_SPEECH_TIMEOUT_MS));

        if done {
            finalize(shared, stream);
            return;
        }
    }
}

/// Feeds the recording into a cache-aware streaming run and delivers partial
/// hypotheses via `onPartialText` and the final text via `onResults` (or an
/// error code). Exits without delivering anything when the model has no
/// native streaming — `finalize` then falls back to the whole-buffer path.
fn streaming_pump(
    shared: Arc<Endpoint>,
    rx: crossbeam_channel::Receiver<crate::engine::StreamCmd>,
) {
    // Wait for the engine (a session can start while it is still loading).
    let engine_arc = loop {
        if let Some(e) = engine::get_engine() {
            break e;
        }
        if shared.finalized.load(Ordering::SeqCst) || shared.cancelled.load(Ordering::SeqCst) {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    };

    // Serialize the start decision with finalize/cancel so exactly one path
    // delivers the transcription (see finalize).
    {
        let _guard = shared.stream_lock.lock().unwrap_or_else(|p| p.into_inner());
        if shared.finalized.load(Ordering::SeqCst) || shared.cancelled.load(Ordering::SeqCst) {
            return;
        }
        let is_streaming = engine_arc
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .supports_streaming();
        if !is_streaming {
            return; // finalize falls back to the whole-buffer path
        }
        shared.streaming_active.store(true, Ordering::SeqCst);
    }

    let mut env = match shared.jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let target = shared.target.as_obj();

    // Everything drained stays here so a failed/mid-stream fallback can
    // re-transcribe the whole recording offline (no text lost).
    // Pre-allocate 30 seconds to prevent dynamic reallocation during streaming.
    let mut local: Vec<f32> = Vec::with_capacity(30 * 16_000);

    let result = {
        let shared_ref = &shared;
        let mut drain = |chunk: &mut Vec<f32>| {
            let mut b = shared_ref
                .audio_buffer
                .lock()
                .unwrap_or_else(|p| p.into_inner());
            chunk.extend(b.drain(..));
            local.extend_from_slice(chunk);
        };
        let mut partial = |text: &str| {
            let _ = env.with_local_frame(16, |local_env| {
                call_partial(local_env, target, text, shared.session_id);
                Ok::<(), jni::errors::Error>(())
            });
        };
        engine::transcribe_stream_shared(&engine_arc, &rx, &mut drain, &mut partial)
    };
    shared.streaming_active.store(false, Ordering::SeqCst);

    match result {
        Ok(text) if !text.trim().is_empty() => {
            call_results(&mut env, target, &text, shared.session_id)
        }
        Ok(_) => call_error(&mut env, target, ERROR_NO_MATCH, shared.session_id),
        Err(msg) if msg == "Canceled" => {}
        Err(msg) => {
            // The stream could not run (e.g. begin failed): fall back to the
            // whole-buffer transcription so no text is lost.
            if local.is_empty() {
                log::error!("Streaming failed: {}", msg);
                call_error(&mut env, target, ERROR_SERVER, shared.session_id);
            } else {
                match engine::transcribe_shared(&engine_arc, local) {
                    Ok(text) if !text.trim().is_empty() => {
                        call_results(&mut env, target, &text, shared.session_id)
                    }
                    Ok(_) => call_error(&mut env, target, ERROR_NO_MATCH, shared.session_id),
                    Err(e) => {
                        log::error!("Transcription failed: {}", e);
                        call_error(&mut env, target, ERROR_SERVER, shared.session_id);
                    }
                }
            }
        }
    }
    clear_session(&shared);
}

/// Stop capture, run the model on the buffered audio and deliver results/error.
/// Idempotent: only the first caller (monitor or explicit stop) does the work.
/// With a streaming model active, signals the pump instead (it finalizes and
/// delivers, so exactly one path reports results).
fn finalize(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return; // already finalised/cancelled
    }

    // Stop the microphone (also drops the audio callback's Arc<Endpoint>).
    *stream.lock().unwrap_or_else(|p| p.into_inner()) = None;

    let mut env = match shared.jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let target = shared.target.as_obj();

    let speech = shared.speech_started.load(Ordering::SeqCst);
    if speech {
        // A pending exception here would make the following call_results /
        // call_error fail too — exactly the "never lose text" path.
        jni_util::call_and_clear(
            &mut env,
            target,
            "onEndOfSpeech",
            "(I)V",
            &[shared.session_id.into()],
        );
    }

    // ~0.2s minimum of audio to bother transcribing (total pushed, not the
    // drained buffer, which the pump consumes as it goes).
    if shared.total_pushed.load(Ordering::SeqCst) < 3200 {
        // Abort the streaming pump (if any) so it delivers nothing.
        if let Some(tx) = shared
            .stream_cmd_tx
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone()
        {
            let _ = tx.send(crate::engine::StreamCmd::Cancel);
        }
        call_error(&mut env, target, ERROR_NO_MATCH, shared.session_id);
        clear_session(&shared);
        return;
    }

    if engine::get_engine().is_none() {
        if engine::ensure_loaded(&mut env, target).is_err() {
            call_error(&mut env, target, ERROR_SERVER, shared.session_id);
            clear_session(&shared);
            return;
        }
    }

    {
        let _guard = shared.stream_lock.lock().unwrap_or_else(|p| p.into_inner());
        if shared.streaming_active.load(Ordering::SeqCst) {
            // Streaming pump owns the transcription: signal end of input; the
            // pump finalizes, delivers the results and clears the session.
            if let Some(tx) = shared
                .stream_cmd_tx
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .clone()
            {
                let _ = tx.send(crate::engine::StreamCmd::Stop);
            }
            return;
        }
    }

    // Whole-buffer path (non-streaming models, or the pump never started).
    let buffer = {
        let mut guard = shared
            .audio_buffer
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        std::mem::take(&mut *guard)
    };
    match engine::get_engine() {
        Some(eng_arc) => {
            let res = engine::transcribe_shared(&eng_arc, buffer);
            match res {
                Ok(text) if !text.trim().is_empty() => {
                    call_results(&mut env, target, &text, shared.session_id)
                }
                Ok(_) => call_error(&mut env, target, ERROR_NO_MATCH, shared.session_id),
                Err(e) => {
                    log::error!("Transcription failed: {}", e);
                    call_error(&mut env, target, ERROR_SERVER, shared.session_id);
                }
            }
        }
        None => call_error(&mut env, target, ERROR_SERVER, shared.session_id),
    }

    clear_session(&shared);
}

/// Clear the global session, but only if it is still *this* session — a newer
/// `startListening` may already have installed a fresh one.
fn clear_session(shared: &Arc<Endpoint>) {
    let mut guard = SESSION.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(s) = guard.as_ref() {
        if Arc::ptr_eq(&s.shared, shared) {
            *guard = None;
        }
    }
}
