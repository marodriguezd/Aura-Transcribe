//! Loads and serves the speech-to-text engine (transcribe.cpp, GGUF models).
//!
//! The engine is a process-wide singleton behind `Arc<Mutex<..>>`: a
//! transcribe.cpp session may only be used by one thread at a time, which the
//! mutex guarantees. Model selection is read from marker files in filesDir
//! (written by `ModelsActivity`): an imported GGUF if one is selected, the
//! bundled model otherwise — with the bundled model as fallback if the
//! imported one fails to load.

use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::assets;

/// File in filesDir naming the imported GGUF (a file under `models/`) to use
/// instead of the bundled model. Absent or empty = bundled model.
const ACTIVE_MODEL_FILE: &str = "active_model";
/// File in filesDir with an optional language hint — a locale like `en-US`
/// or `auto`. Absent or empty = let the model autodetect.
const MODEL_LANGUAGE_FILE: &str = "model_language";
/// File in filesDir with the device's BCP-47 locale, written by `App` on first
/// run. Used as the language hint when the user's choice is automatic but the
/// active model has no native language detection (Canary-family models): that
/// preserves the old "transcribe in the phone's system language" default.
/// Models with native detection (Nemotron) just auto-detect instead.
const DEVICE_LANGUAGE_FILE: &str = "device_language";
/// Marker file in filesDir: when present, models that support translation
/// (e.g. Whisper) translate speech to English instead of transcribing it.
/// Ignored by models without translation support.
const MODEL_TRANSLATE_FILE: &str = "model_translate";
/// Optional file in filesDir with the CPU thread count for inference.
/// Absent/invalid/0 = default (all cores).
const MODEL_THREADS_FILE: &str = "model_threads";
/// Optional file in filesDir with the cache-aware streaming chunk selector
/// (Nemotron-family models): one of {13, 6, 1, 0}, mapping to chunk sizes
/// {1.12 s, 560 ms, 160 ms, 80 ms} (chunk = (right + 1) × 80 ms at the
/// 80 ms encoder frame rate). Absent/invalid = 13, the model's
/// max-accuracy default. Lower values trade a little WER for substantially
/// earlier and livelier partial hypotheses on slow devices.
const STREAM_CONTEXT_RIGHT_FILE: &str = "stream_context_right";
/// Optional file in filesDir with the hardware acceleration backend selection
/// ("cpu", "npu", "gpu"). Absent/invalid = "cpu" (ARM NEON + dotprod + fp16).
const HARDWARE_BACKEND_FILE: &str = "hardware_backend";

/// Longest audio passed to the model in one run (60 s). Offline conformer
/// models use full self-attention, whose cost grows quadratically with input
/// length — an unbounded shared audio file would exhaust memory on a phone.
/// Longer input is split at quiet points and the texts joined.
const MAX_RUN_SAMPLES: usize = 60 * 16_000;
/// When splitting, search this far back from the hard boundary for the
/// quietest point so words aren't cut mid-syllable.
const SPLIT_SEARCH_SAMPLES: usize = 10 * 16_000;
/// How often the streaming run drains the caller's audio buffer (~80 ms).
/// Optimized to match the native 80 ms frame rate of streaming encoder models
/// (Nemotron-family) for instantaneous partial hypothesis response.
const STREAM_TICK_MS: u64 = 80;

/// Commands fed to an active streaming run (see [`Engine::run_stream`]).
pub enum StreamCmd {
    /// A chunk of 16 kHz mono f32 audio captured since the previous tick.
    Audio(Vec<f32>),
    /// End of input: flush the stream and return the final text.
    Stop,
    /// Abandon the stream and deliver nothing.
    Cancel,
}

/// A loaded transcribe.cpp session plus the options applied to every run.
pub struct Engine {
    session: transcribe_cpp::Session,
    language: Option<String>,
    /// Device-locale fallback hint for models without native language
    /// detection when the user's hint is automatic/absent (Canary-family).
    device_lang: Option<String>,
    task: transcribe_cpp::Task,
    /// Family-specific decode options attached to every run; `None` for
    /// models that don't take the whisper run extension.
    run_ext: Option<transcribe_cpp::RunExtension>,
    /// Family-specific streaming extension, set when the loaded model
    /// supports native cache-aware streaming (e.g. Nemotron 3.5 ASR).
    stream_ext: Option<transcribe_cpp::StreamExtension>,
    /// Whether the loaded model supports native streaming (GGUF KV).
    supports_streaming: bool,
    /// Whether the loaded model has native language detection (GGUF KV).
    native_lang_detect: bool,
    /// Cache-aware streaming chunk selector (Nemotron-family), from the
    /// `stream_context_right` marker. `Some(13)` (default) is the model's
    /// max-accuracy entry; smaller values trade WER for partial-latency.
    stream_ctx_right: Option<i32>,
    /// Status reported once loading succeeded; carries a warning when the
    /// translate setting can't do what the user expects with this model.
    ready_status: &'static str,
    /// Path of the `model_language` hint file, re-read on every run so a
    /// language change applies to the voice keyboard too,
    /// without a manual model reload.
    lang_file: PathBuf,
}

impl Engine {
    fn load(
        model_path: &Path,
        language: Option<String>,
        device_lang: Option<String>,
        translate: bool,
        threads: i32,
        stream_ctx_right: Option<i32>,
        lang_file: &Path,
    ) -> Result<Engine, String> {
        if !model_path.is_file() {
            return Err(format!("model file not found: {}", model_path.display()));
        }
        let model = transcribe_cpp::Model::load(model_path).map_err(|e| e.to_string())?;
        // Translation is gated on the model's capabilities: models without it
        // (e.g. Parakeet) silently keep transcribing, so the setting can stay
        // on while switching models.
        let task = if translate && model.capabilities().supports_translate {
            transcribe_cpp::Task::Translate
        } else {
            if translate {
                log::info!("translate requested but unsupported by this model; transcribing");
            }
            transcribe_cpp::Task::Transcribe
        };
        // Silently doing something other than what the translate switch says
        // looks like a bug (still-untranslated subtitles), so say it in the
        // status. Whisper Turbo is special-cased: it advertises translation
        // but was distilled without translation data — verified on-device to
        // keep transcribing German as German with task=Translate.
        let ready_status = if translate && task == transcribe_cpp::Task::Transcribe {
            "Ready (this model can't translate)"
        } else if translate && model.variant().contains("turbo") {
            "Ready (note: Whisper Turbo translates poorly; use another Whisper)"
        } else {
            "Ready"
        };
        // Whisper's stock recipe re-decodes a chunk at up to five higher
        // temperatures when its quality gates fail, so one noisy chunk can
        // cost several full decodes. For an interactive app a single greedy
        // pass is the better trade: worst case is a worse line of text, not
        // a multiplied wait. temperature_inc = 0 turns the retry ladder off;
        // models that don't take the whisper run extension are unaffected.
        let run_ext = if model.accepts_ext(
            transcribe_cpp::ExtSlot::Run,
            transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_WHISPER_RUN,
        ) {
            Some(transcribe_cpp::RunExtension::Whisper(
                transcribe_cpp::WhisperRunOptions {
                    temperature_inc: Some(0.0),
                    ..Default::default()
                },
            ))
        } else {
            None
        };

        // Streaming + language-detection capabilities come from GGUF KV
        // (Nemotron 3.5 ASR: both true; Canary 180M Flash: both false). The
        // cache-aware parakeet stream extension is what run_stream uses.
        let caps = model.capabilities();
        let supports_streaming = caps.supports_streaming;
        let native_lang_detect = caps.supports_language_detect;
        let stream_ext = if supports_streaming
            && model.accepts_ext(
                transcribe_cpp::ExtSlot::Stream,
                transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_PARAKEET_STREAM,
            ) {
            // Cache-aware streaming (Nemotron-family). att_context_right
            // picks the operating point from the model's training menu;
            // chunk = (right + 1) × 80 ms, so the documented menu {13, 6, 1, 0}
            // yields {1.12 s, 560 ms, 160 ms, 80 ms} chunks. 13 is the
            // model's max-accuracy default and partial hypotheses arrive
            // roughly once per chunk. Smaller values trade a little WER for
            // much earlier, livelier partials on slow devices (configurable
            // via the stream_context_right marker — see ModelsActivity).
            Some(transcribe_cpp::StreamExtension::ParakeetStream(
                transcribe_cpp::ParakeetStreamOptions {
                    att_context_right: stream_ctx_right,
                },
            ))
        } else {
            None
        };

        log::info!(
            "engine: {} threads, task {:?}, single-pass decode: {}, streaming: {}",
            threads,
            task,
            run_ext.is_some(),
            supports_streaming
        );
        let options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };
        let session = model.session_with(&options).map_err(|e| e.to_string())?;
        Ok(Engine {
            session,
            language,
            device_lang,
            task,
            run_ext,
            stream_ext,
            supports_streaming,
            native_lang_detect,
            stream_ctx_right,
            ready_status,
            lang_file: lang_file.to_path_buf(),
        })
    }

    /// Re-reads the `model_language` hint file and resolves the effective hint
    /// for the next run/stream. An explicit locale always wins; automatic or
    /// absent resolves to the model's native language detection when the model
    /// has one (Nemotron), otherwise to the device-locale fallback (Canary —
    /// the old default behavior). Re-read on every run so a language change
    /// applies immediately to the voice keyboard too, without
    /// a manual model reload.
    fn effective_language(&mut self) -> Option<String> {
        if let Ok(raw) = std::fs::read_to_string(&self.lang_file) {
            let s = raw.trim();
            let new_lang = if s.is_empty() || s.eq_ignore_ascii_case("auto") {
                None
            } else {
                Some(s.to_string())
            };
            if new_lang != self.language {
                self.language = new_lang;
            }
        }
        match (&self.language, self.native_lang_detect) {
            (Some(l), _) => Some(l.clone()),
            (None, true) => None,
            (None, false) => self.device_lang.clone(),
        }
    }

    /// Whether the loaded model supports native cache-aware streaming
    /// (Nemotron-family). Dictation surfaces use this to pick the streaming
    /// pump over the whole-buffer path.
    pub fn supports_streaming(&self) -> bool {
        self.supports_streaming
    }

    /// Transcribes 16 kHz mono f32 samples to text. Input longer than
    /// [`MAX_RUN_SAMPLES`] is transcribed in quiet-point chunks. Uses the
    /// engine's configured task (which honors the global `model_translate`
    /// marker where the model supports it).
    pub fn transcribe(&mut self, samples: Vec<f32>) -> Result<String, String> {
        self.transcribe_with_task(samples, None)
    }

    /// Same as [`Engine::transcribe`] but with an explicit task override.
    /// `Some(Task::Transcribe)` is what the live-subtitle path passes so a
    /// global "translate to English" switch (Whisper imports) can never turn
    /// subtitles into translated text behind the user's back — subtitle
    /// translation is a Java-side, target-selective feature instead
    /// (see `transcribe_subtitle`).
    pub fn transcribe_with_task(
        &mut self,
        samples: Vec<f32>,
        task: Option<transcribe_cpp::Task>,
    ) -> Result<String, String> {
        if samples.len() <= MAX_RUN_SAMPLES {
            return self.run_with_task(&samples, task);
        }

        let mut text = String::new();
        let mut rest: &[f32] = &samples;
        while !rest.is_empty() {
            let take = if rest.len() <= MAX_RUN_SAMPLES {
                rest.len()
            } else {
                crate::audio::find_quietest_split(
                    rest,
                    MAX_RUN_SAMPLES - SPLIT_SEARCH_SAMPLES,
                    MAX_RUN_SAMPLES,
                )
            };
            let piece = self.run_with_task(&rest[..take], task)?;
            let piece = piece.trim();
            if !piece.is_empty() {
                if !text.is_empty() {
                    text.push(' ');
                }
                text.push_str(piece);
            }
            rest = &rest[take..];
        }
        Ok(text)
    }

    /// One model run. A rejected language hint is degraded instead of
    /// failing the transcription: `de-DE` retries as `de`, then as no hint
    /// (each model knows a different set of tags — e.g. Parakeet v3 takes
    /// locales/short codes, English-only models take none). `None` task = the
    /// engine's configured task.
    fn run_with_task(
        &mut self,
        samples: &[f32],
        task: Option<transcribe_cpp::Task>,
    ) -> Result<String, String> {
        let task = task.unwrap_or(self.task);
        // Re-read the hint on every run (see effective_language). The hint is
        // degraded locally per run; the marker file stays the source of truth.
        let mut hint = self.effective_language();
        loop {
            let opts = transcribe_cpp::RunOptions {
                language: hint.clone(),
                task,
                family: self.run_ext.clone(),
                ..Default::default()
            };
            match self.session.run(samples, &opts) {
                Ok(t) => return Ok(t.text),
                Err(transcribe_cpp::Error::Unsupported(msg)) if hint.is_some() => {
                    let lang = hint.take().unwrap();
                    hint = lang.split_once('-').map(|(primary, _)| primary.to_string());
                    log::warn!(
                        "language hint '{}' rejected ({}); retrying with {:?}",
                        lang,
                        msg,
                        hint
                    );
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }

    /// Runs a cache-aware streaming session (Nemotron-family models): audio
    /// chunks arrive via [`StreamCmd::Audio`] pulled by `drain`, partial
    /// hypotheses are reported through `on_partial` (committed + tentative
    /// display text), and the final text is returned when [`StreamCmd::Stop`]
    /// arrives. [`StreamCmd::Cancel`] abandons the stream and returns
    /// `Err("Canceled")`. A rejected language hint is degraded like `run`;
    /// a requested `att_context_right` that is not in the model's menu is
    /// retried once with the model default; a failed begin leaves the
    /// session idle so retrying is safe.
    ///
    /// The engine mutex is held for the whole recording, so other surfaces
    /// block while a stream is active — the C library allows only one active
    /// stream per model anyway.
    ///
    /// Logs per-session fluidity telemetry (audio secs, wall secs, RTF,
    /// partial count, mean partial cadence, active chunk selector) so the
    /// WER-vs-fluidity trade-off can be measured on-device via logcat.
    fn run_stream(
        &mut self,
        rx: &crossbeam_channel::Receiver<StreamCmd>,
        drain: &mut dyn FnMut(&mut Vec<f32>),
        on_partial: &mut dyn FnMut(&str),
    ) -> Result<String, String> {
        if !self.supports_streaming {
            return Err("this model does not support streaming".to_string());
        }
        let mut hint = self.effective_language();
        let mut ctx_retried = false;
        // Track the chunk selector actually in effect for the stop-log
        // telemetry: a menu retry below resets it to the model default, and
        // the log must report what was really used, not the configured value.
        let mut effective_ctx = self.stream_ctx_right;
        let mut stream = loop {
            let run_opts = transcribe_cpp::RunOptions {
                language: hint.clone(),
                task: self.task,
                family: self.run_ext.clone(),
                ..Default::default()
            };
            let stream_opts = transcribe_cpp::StreamOptions {
                family: self.stream_ext.clone(),
                ..Default::default()
            };
            match self.session.stream(&run_opts, &stream_opts) {
                Ok(s) => break s,
                Err(transcribe_cpp::Error::Unsupported(msg)) if hint.is_some() => {
                    let lang = hint.take().unwrap();
                    hint = lang.split_once('-').map(|(primary, _)| primary.to_string());
                    log::warn!(
                        "language hint '{}' rejected ({}); retrying with {:?}",
                        lang,
                        msg,
                        hint
                    );
                }
                // The requested att_context_right may not exist in this
                // model's training menu (e.g. an imported GGUF whose menu
                // differs from the bundled Nemotron's): retry once with the
                // model default instead of failing the whole stream. Narrowed
                // to InvalidArgument so genuine begin failures (backend, OOM)
                // are surfaced immediately, not masked by a wasted retry.
                Err(transcribe_cpp::Error::InvalidArgument(e)) if !ctx_retried => {
                    ctx_retried = true;
                    effective_ctx = None;
                    self.stream_ext = self.stream_ext.take().map(|ext| match ext {
                        transcribe_cpp::StreamExtension::ParakeetStream(mut opts) => {
                            opts.att_context_right = None;
                            transcribe_cpp::StreamExtension::ParakeetStream(opts)
                        }
                        other => other,
                    });
                    log::warn!(
                        "parakeet stream begin rejected ({}); retrying with the model default att_context_right",
                        e
                    );
                }
                Err(e) => return Err(format!("stream begin: {}", e)),
            }
        };

        let mut total_fed: usize = 0;
        let started = std::time::Instant::now();
        let mut partial_count: usize = 0;
        let mut last_partial = started;
        let mut last_emitted = String::new();
        let mut cadence_ms: u64 = 0;
        let mut chunk: Vec<f32> = Vec::with_capacity(4096);
        loop {
            // Feed whatever audio accumulated since the last tick into reusable chunk buffer.
            chunk.clear();
            drain(&mut chunk);
            if !chunk.is_empty() {
                total_fed += chunk.len();
                stream
                    .feed(&chunk)
                    .map_err(|e| format!("stream feed: {}", e))?;
                let text = stream.text();
                let shown = text.display();
                let trimmed = shown.trim();
                if !trimmed.is_empty() && trimmed != last_emitted {
                    // Fluidity telemetry: partial cadence (mean gap between
                    // consecutive partial hypotheses) reported in the stop
                    // log line below.
                    let now = std::time::Instant::now();
                    if partial_count > 0 {
                        cadence_ms += now.duration_since(last_partial).as_millis() as u64;
                    }
                    last_partial = now;
                    partial_count += 1;
                    last_emitted.clear();
                    last_emitted.push_str(trimmed);
                    on_partial(trimmed);
                }
            }
            // Handle control commands: Stop finalizes, Cancel abandons.
            match rx.try_recv() {
                Ok(StreamCmd::Stop) => {
                    chunk.clear();
                    drain(&mut chunk);
                    if !chunk.is_empty() {
                        total_fed += chunk.len();
                        stream
                            .feed(&chunk)
                            .map_err(|e| format!("stream feed: {}", e))?;
                    }
                    stream
                        .finalize()
                        .map_err(|e| format!("stream finalize: {}", e))?;
                    let t = stream.text();
                    let final_text = t.display().trim().to_string();
                    let audio_secs = total_fed as f64 / 16_000.0;
                    let wall_secs = started.elapsed().as_secs_f64();
                    let rtf = if audio_secs > 0.0 {
                        wall_secs / audio_secs
                    } else {
                        0.0
                    };
                    let avg_cadence = if partial_count > 1 {
                        cadence_ms as f64 / (partial_count - 1) as f64
                    } else {
                        0.0
                    };
                    log::info!(
                        "streamed {:.1}s audio in {:.2}s (rtf={:.2}, partials={}, cadence={:.0}ms, att_context_right={:?})",
                        audio_secs,
                        wall_secs,
                        rtf,
                        partial_count,
                        avg_cadence,
                        effective_ctx
                    );
                    return Ok(final_text);
                }
                Ok(StreamCmd::Cancel) => {
                    stream.reset();
                    return Err("Canceled".to_string());
                }
                _ => {}
            }
            std::thread::sleep(std::time::Duration::from_millis(STREAM_TICK_MS));
        }
    }
}

/// Holds the loaded engine singleton.
static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<Engine>>>>> = Lazy::new(|| Mutex::new(None));

/// Loading coordination state + condvar for waiters.
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    /// No load in progress
    Idle,
    /// A thread is currently loading the model
    Loading,
    /// Loading completed successfully
    Done,
    /// Loading failed
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<Engine>>> {
    GLOBAL_ENGINE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
}

/// Runs a transcription on the shared engine. Two layers of hardening keep a
/// single bad run from freezing every later one (the symptom would be an IME
/// stuck at "Processing" until its process dies, since notify callbacks stop
/// coming): a panic anywhere in the engine stack is caught and surfaced as a
/// normal error, and a lock poisoned by an earlier panic is recovered instead
/// of propagating the poison forever.
pub fn transcribe_shared(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    transcribe_shared_with_task(engine, samples, None)
}

/// Runs a transcription on the shared engine for the live-subtitle path,
/// forcing `Task::Transcribe`: the global `model_translate` switch must never
/// apply to subtitles — their translation is handled Java-side with an
/// explicit target selected by the user (see `OnDeviceSubtitleTranslator`).
/// Same hardening layers as [`transcribe_shared`].
pub fn transcribe_subtitle(
    engine: &Arc<Mutex<Engine>>,
    samples: Vec<f32>,
) -> Result<String, String> {
    transcribe_shared_with_task(engine, samples, Some(transcribe_cpp::Task::Transcribe))
}

/// Shared implementation behind [`transcribe_shared`] / [`transcribe_subtitle`].
fn transcribe_shared_with_task(
    engine: &Arc<Mutex<Engine>>,
    samples: Vec<f32>,
    task: Option<transcribe_cpp::Task>,
) -> Result<String, String> {
    let audio_secs = samples.len() as f64 / 16_000.0;
    let started = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = engine
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.transcribe_with_task(samples, task)
    }))
    .unwrap_or_else(|_| {
        log::error!("transcription panicked; reporting as error");
        Err("transcription failed unexpectedly, please try again".to_string())
    });
    // Apply the user's custom-word phonetic correction (post-ASR). Wrapped
    // in its own catch_unwind so a panic in the corrector (e.g. a bug in the
    // phonetic encoder, or a poisoned cache mutex) cannot escape to JNI and
    // freeze the IME — the raw transcript is returned instead. This matches
    // the engine's own resilience pattern (AGENTS.md §5.1).
    let result = result.map(|text| {
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            crate::corrector::correct_if_enabled(&text)
        }))
        .unwrap_or_else(|_| {
            log::error!("corrector panicked; returning raw transcript");
            text
        })
    });
    log::info!(
        "transcribed {:.1}s audio in {:.2}s",
        audio_secs,
        started.elapsed().as_secs_f64()
    );
    result
}

/// Runs a streaming transcription on the shared engine (see
/// [`Engine::run_stream`]) with the same two hardening layers as
/// [`transcribe_shared`]: a panic anywhere in the engine stack is caught and
/// surfaced as a normal error, and a lock poisoned by an earlier panic is
/// recovered. The final text goes through the phonetic corrector; partial
/// hypotheses are delivered raw.
pub fn transcribe_stream_shared(
    engine: &Arc<Mutex<Engine>>,
    rx: &crossbeam_channel::Receiver<StreamCmd>,
    drain: &mut dyn FnMut(&mut Vec<f32>),
    on_partial: &mut dyn FnMut(&str),
) -> Result<String, String> {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = engine
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.run_stream(rx, drain, on_partial)
    }))
    .unwrap_or_else(|_| {
        log::error!("streaming transcription panicked; reporting as error");
        Err("transcription failed unexpectedly, please try again".to_string())
    });
    result.map(|text| {
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            crate::corrector::correct_if_enabled(&text)
        }))
        .unwrap_or_else(|_| {
            log::error!("corrector panicked; returning raw transcript");
            text
        })
    })
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .is_some()
}

/// Drops the loaded engine and clears the load state so the next
/// `ensure_loaded*` call reloads with the current model selection. Waits for
/// an in-flight load to finish first, so a reload can't race a load of the
/// previous selection. Call from a background thread.
pub fn reset() {
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap_or_else(|p| p.into_inner());
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap_or_else(|p| p.into_inner());
    }
    *GLOBAL_ENGINE.lock().unwrap_or_else(|p| p.into_inner()) = None;
    *state = LoadState::Idle;
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    crate::jni_util::notify_status(env, obj, msg);
}

/// Ensures the engine is loaded. Safe to call from multiple threads
/// concurrently; reports status via the target's `onStatusUpdate` callback.
///
/// Convenience wrapper around [`ensure_loaded_from_thread`] for JNI entry
/// points that already hold an attached `JNIEnv`.
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let jvm = Arc::new(env.get_java_vm().map_err(|e| e.to_string())?);
    let context_ref = env.new_global_ref(context).map_err(|e| e.to_string())?;
    ensure_loaded_from_thread(&jvm, &context_ref)
}

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
///
/// - If already loaded, returns immediately.
/// - If another thread is loading, waits for it to finish.
/// - If no one is loading, this thread takes ownership of loading.
/// - If a previous load failed, retries.
///
/// Reports status via the target's `onStatusUpdate` JNI callback.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    let notify = |msg: &str| {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), msg);
        }
    };

    // Fast path: already loaded
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap_or_else(|p| p.into_inner());

    // Re-check under lock
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            // Another thread is loading — wait for it
            notify("Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap_or_else(|p| p.into_inner());
            }
            drop(state);

            if is_engine_loaded() {
                notify("Ready");
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                notify(&format!("Error: {}", msg));
                Err(msg)
            }
        }
        LoadState::Done => {
            notify("Ready");
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            // We take ownership of loading (retry on previous failure)
            *state = LoadState::Loading;
            drop(state);

            // The load path must be panic-safe too (R1): a panic in do_load
            // (e.g. an allocation failure inside the C model loader) would
            // leave LOAD_STATE = Loading forever, and every later
            // ensure_loaded* call would wait on the condvar indefinitely —
            // the same freeze the two resilience layers prevent in
            // transcribe_shared. Catch it and report Failure so the next
            // call retries cleanly.
            let result = if let Ok(mut env) = jvm.attach_current_thread() {
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    do_load(&mut env, target_ref.as_obj())
                }))
                .unwrap_or_else(|_| {
                    log::error!("model load panicked; reporting failure for retry");
                    Err("model load failed unexpectedly, please try again".to_string())
                })
            } else {
                Err("Failed to attach JNI thread".to_string())
            };

            let mut state = lock.lock().unwrap_or_else(|p| p.into_inner());
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

/// Inference thread count: the number of performance cores, capped at 4.
///
/// Phone SoCs are heterogeneous: fast cores paired with slow efficiency
/// cores. ggml synchronizes all threads after each operation, so a thread
/// on a slow core stalls the whole pool, and using every core is slower
/// than using only the fast ones. Cores are classified by their maximum
/// frequency from sysfs: within 70% of the fastest core counts as fast.
/// The count is capped at 4 because grabbing every fast core leaves none
/// for the rest of the system (audio pipeline, the app playing the sound),
/// and any preempted worker stalls the pool at the next op barrier; past
/// 4 threads the matmuls are memory-bound on phone-class SoCs anyway, and
/// more threads mainly build up heat. If sysfs is unreadable, falls back
/// to a conservative 4. The `model_threads` config file (user-settable in
/// the Models screen) overrides the heuristic.
fn performance_core_count() -> i32 {
    let mut freqs: Vec<u64> = Vec::new();
    for i in 0..64 {
        let path = format!("/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq");
        match std::fs::read_to_string(&path) {
            Ok(s) => match s.trim().parse::<u64>() {
                Ok(f) => freqs.push(f),
                Err(_) => break,
            },
            Err(_) => break,
        }
    }
    match freqs.iter().max() {
        Some(&max) if max > 0 => {
            let fast = freqs.iter().filter(|&&f| f * 10 >= max * 7).count();
            let threads = fast.clamp(1, 4);
            log::info!(
                "cpu clusters {:?} kHz -> {} performance cores -> {} threads",
                freqs,
                fast,
                threads
            );
            threads as i32
        }
        _ => std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .min(4) as i32,
    }
}

/// CPU features this build's ggml kernels require (see GGML_CPU_ARM_ARCH in
/// the gradle build): dot-product and half-precision SIMD, present on arm64
/// cores since ~2018. Without this check, an older CPU would crash with an
/// illegal instruction mid-inference instead of showing an error.
#[cfg(target_arch = "aarch64")]
fn check_cpu_features() -> Result<(), String> {
    const HWCAP_ASIMHP: libc::c_ulong = 1 << 10; // FEAT_FP16 (asimdhp)
    const HWCAP_ASIMDDP: libc::c_ulong = 1 << 20; // FEAT_DotProd (asimddp)
    let hwcap = unsafe { libc::getauxval(libc::AT_HWCAP) };
    if hwcap & HWCAP_ASIMDDP == 0 || hwcap & HWCAP_ASIMHP == 0 {
        return Err(
            "this device's CPU is too old for this app version (needs arm64 \
             dotprod/fp16, available on phones from ~2018 on)"
                .to_string(),
        );
    }
    Ok(())
}

#[cfg(not(target_arch = "aarch64"))]
fn check_cpu_features() -> Result<(), String> {
    Ok(())
}

/// Reads a single-value config file (trimmed); `None` if absent or empty.
fn read_config(path: &Path) -> Option<String> {
    let s = std::fs::read_to_string(path).ok()?;
    let s = s.trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// Performs the model load: the selected imported GGUF if any (falling back
/// to the bundled model on failure), otherwise the bundled model.
fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    if let Err(msg) = check_cpu_features() {
        notify_status(env, context, &format!("Error: {}", msg));
        return Err(msg);
    }

    let files_dir = assets::files_dir(env, context).map_err(|e| {
        let msg = format!("Failed to resolve filesDir: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;
    // Absent/empty or "auto" = no hint (the model's automatic mode). A tag
    // the model doesn't know is degraded per run — see Engine::run.
    let language = read_config(&files_dir.join(MODEL_LANGUAGE_FILE))
        .filter(|l| !l.eq_ignore_ascii_case("auto"));
    // Device-locale fallback for models without native detection (Canary).
    let device_lang = read_config(&files_dir.join(DEVICE_LANGUAGE_FILE));
    let translate = files_dir.join(MODEL_TRANSLATE_FILE).exists();
    let threads = read_config(&files_dir.join(MODEL_THREADS_FILE))
        .and_then(|s| s.parse::<i32>().ok())
        .filter(|&n| n > 0)
        .unwrap_or_else(performance_core_count);
    // Cache-aware streaming chunk selector (Nemotron-family). Only the
    // documented menu {13, 6, 1, 0} is valid; anything else falls back to 13
    // (the max-accuracy default). A value the *imported* model doesn't have
    // in its own menu is retried with the model default in run_stream rather
    // than failing the stream.
    let stream_ctx_right = match read_config(&files_dir.join(STREAM_CONTEXT_RIGHT_FILE)) {
        Some(raw) => match raw.parse::<i32>() {
            Ok(v) if matches!(v, 13 | 6 | 1 | 0) => Some(v),
            _ => {
                log::warn!("invalid stream_context_right '{raw}'; using default (13)");
                Some(13)
            }
        },
        None => Some(13),
    };

    let hardware_backend =
        read_config(&files_dir.join(HARDWARE_BACKEND_FILE)).unwrap_or_else(|| "cpu".to_string());
    log::info!("engine: configured hardware backend: {}", hardware_backend);

    // Publish filesDir so the corrector can locate the custom-words marker
    // file. Done here (before the imported-model attempt) so the corrector
    // works whether the active model is imported or bundled; if it fails the
    // corrector is a no-op (safe fallback).
    crate::corrector::set_files_dir(&files_dir);

    if let Some(name) = read_config(&files_dir.join(ACTIVE_MODEL_FILE)) {
        let path = files_dir.join("models").join(&name);
        notify_status(env, context, &format!("Loading model {}...", name));
        match Engine::load(
            &path,
            language.clone(),
            device_lang.clone(),
            translate,
            threads,
            stream_ctx_right,
            &files_dir.join(MODEL_LANGUAGE_FILE),
        ) {
            Ok(engine) => {
                let status = engine.ready_status;
                *GLOBAL_ENGINE.lock().unwrap_or_else(|p| p.into_inner()) =
                    Some(Arc::new(Mutex::new(engine)));
                notify_status(env, context, status);
                return Ok(());
            }
            Err(e) => {
                log::error!("Imported model {} failed to load: {}", path.display(), e);
                notify_status(
                    env,
                    context,
                    &format!("Error loading {}: {} — using built-in model", name, e),
                );
                // fall through to the bundled model
            }
        }
    }

    notify_status(env, context, "Checking assets...");

    let path = assets::extract_builtin_model(env, context).map_err(|e| {
        let msg = format!("Asset error: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;

    notify_status(env, context, "Loading model...");

    match Engine::load(
        &path,
        language,
        device_lang,
        translate,
        threads,
        stream_ctx_right,
        &files_dir.join(MODEL_LANGUAGE_FILE),
    ) {
        Ok(engine) => {
            let status = engine.ready_status;
            *GLOBAL_ENGINE.lock().unwrap_or_else(|p| p.into_inner()) =
                Some(Arc::new(Mutex::new(engine)));
            notify_status(env, context, status);
            Ok(())
        }
        Err(e) => {
            // Load failed — likely corrupt/incomplete extraction. Invalidate
            // it so the next attempt re-extracts from the APK.
            assets::invalidate_builtin_model(&path);
            let msg = format!("Model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
