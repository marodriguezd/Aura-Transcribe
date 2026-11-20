//! Native backend for `LiveSubtitleService` (real-time captions for device audio).
//!
//! Audio arrives via `pushAudio` in small blocks (~64 ms). Two pipelines are
//! possible, chosen once per session:
//!
//! - **Streaming** (models with native cache-aware streaming, e.g. Nemotron):
//!   the audio of the current utterance is forwarded as it arrives to a
//!   streaming worker, which feeds it into an active `transcribe_cpp::Stream`
//!   every 80 ms and emits a partial as soon as the displayed text changes.
//!   A segment boundary asks that worker to `finalize()`, and a fresh stream
//!   starts with the next sentence. Word-by-word captions at O(1) cost per
//!   frame instead of re-transcribing the whole segment.
//! - **Batch** (everything else, and the permanent fallback if streaming ever
//!   fails): build up a *segment* of speech, send the whole segment to a
//!   worker thread roughly once per tick, and display the result as a
//!   *partial*. When trailing silence is detected — or the segment hits a hard
//!   cap — the segment is *finalized*: transcribed once more and committed.
//!
//! The batch pipeline keeps latency low and bounded:
//! - Partial jobs are only submitted while the worker is idle (latest-wins),
//!   so a slow device can never build up a queue and drift behind real time.
//! - Final jobs are always queued (FIFO), so committed text is never lost.
//! - When finals queue up faster than they are processed, the worker folds
//!   them into one batched run: fixed-window models (Whisper) cost nearly
//!   the same per run regardless of audio length, so batching is what lets
//!   a slow device catch up instead of dropping text.

use crossbeam_channel;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::engine;

/// Monotonic session generation (P0.2). Every `initNative` (and every
/// `cleanupNative`) bumps it; jobs carry the generation they were created
/// under, and the worker refuses to transcribe or deliver jobs whose
/// generation no longer matches the current one. This invalidates a previous
/// session's worker deterministically without interrupting it: stale queued
/// work is skipped instead of burning CPU, and stale text is never delivered
/// to a torn-down overlay or a new session's service.
static GENERATION: AtomicU32 = AtomicU32::new(0);

const SAMPLE_RATE: usize = 16_000;
/// Minimum audio between two partial-hypothesis updates (~0.7 s).
const TICK_SAMPLES: usize = 11_200;
/// Trailing silence that finalizes the current segment (~0.7 s).
const FINALIZE_SILENCE_SAMPLES: usize = 11_200;
/// Hard cap on a single segment. Partials re-transcribe the whole current
/// segment, so this also caps the per-job inference cost: the worst-case
/// caption offset is roughly one queued final plus one in-flight job, both
/// proportional to this — keep it short so captions stay close to real time
/// even in dense speech.
const MAX_SEGMENT_SAMPLES: usize = 6 * SAMPLE_RATE;
/// A queued *final* older than this is dropped (a "…" gap is shown instead).
/// This bounds the audio-to-caption offset on devices that can't transcribe
/// in real time — without it the finals queue grows and the captions drift
/// further behind the longer the audio plays. Merging (below) makes this a
/// last resort: it only fires when even batched runs can't keep up.
const MAX_FINAL_LAG_SAMPLES: u64 = (8 * SAMPLE_RATE) as u64;
/// Cap on one merged final run. Models with a fixed input window (Whisper
/// pads everything to 30 s) cost nearly the same per run no matter how short
/// the audio is, so transcribing queued finals one by one wastes most of the
/// run on padding. Folding everything that is already queued into one run
/// amortizes that fixed cost — this is what lets a device that can't keep up
/// segment-by-segment still catch up without dropping text. Kept under the
/// 30 s window so a merged run is still a single model pass.
const MAX_MERGED_SAMPLES: usize = 25 * SAMPLE_RATE;
/// A queued *partial* older than this is stale; skip it (a fresh one follows).
const MAX_PARTIAL_LAG_SAMPLES: u64 = (3 * SAMPLE_RATE) as u64;
/// A partial is only submitted when its predicted transcription time is at
/// most this. Partials are cosmetic — on a slow (or thermally throttled)
/// device the worker's time is better spent on finals, which are what keep
/// the transcript moving. Finals are never gated on cost.
const MAX_PARTIAL_COST_SECS: f32 = 2.0;
/// Audio kept while waiting for speech so the first word isn't clipped (0.4 s).
const PREROLL_SAMPLES: usize = 6_400;
/// Silence kept after the last speech when finalizing on silence (0.2 s).
const FINAL_TAIL_SAMPLES: usize = 3_200;
/// Digital-silence floor: below this level a block can never be foreground
/// speech, no matter how quiet the measured noise floor is.
const MIN_ABSOLUTE_SPEECH_RMS: f32 = 0.004;
/// How far above the running noise floor a block must sit to count as speech.
const NOISE_FLOOR_MARGIN_RMS: f32 = 0.006;
/// Upper clamp for the noise floor. Loud continuous material must never raise
/// the floor so high that ordinary speech is ignored.
const MAX_NOISE_FLOOR_RMS: f32 = 0.035;
/// Window of recent blocks used for the noise-floor estimate (~2 s at the
/// ~64 ms blocks `pushAudio` receives).
const VAD_WINDOW_BLOCKS: usize = 32;
/// EMA applied to the window minimum: 0.7 old / 0.3 new. Fast enough to follow
/// background music within ~1 s, slow enough that one block cannot move it.
const VAD_FLOOR_EMA_OLD: f32 = 0.7;
const VAD_FLOOR_EMA_NEW: f32 = 0.3;
/// Segments shorter than this are dropped as noise (0.25 s).
const MIN_SEGMENT_SAMPLES: usize = 4_000;
/// Silence after a sentence terminator that ends the segment early (280 ms).
const EARLY_SENTENCE_SILENCE_SAMPLES: usize = 4_480;
/// A clause boundary only ends a segment once it is this long (3.5 s).
const CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES: usize = 56_000;
/// Micro-pause required after clause punctuation to split cleanly (220 ms).
const CLAUSE_SPLIT_SILENCE_SAMPLES: usize = 3_520;

/// Audio pipeline a live-subtitle session uses. Chosen once per session (see
/// [`SubtitlePipelineState`]) and one-way: streaming never comes back after a
/// failure, otherwise the same audio would be transcribed and delivered twice.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubtitlePipeline {
    /// Feed the audio into an active cache-aware `Stream`: partials appear
    /// word by word at the model's 80 ms frame rate.
    Streaming,
    /// Re-transcribe the current segment every tick (models without native
    /// streaming).
    Batch,
}

/// One ordered item on the streaming worker's input channel. Audio and
/// boundary commands share a single FIFO on purpose: with two channels a
/// reset meant for a noise-only segment could be dequeued after the next
/// utterance's first blocks and cancel them. In one queue, everything the
/// stream sees is exactly what was pushed, in order.
enum StreamInput {
    /// A block of the current utterance.
    Audio(Vec<f32>),
    /// The utterance ended: flush and finalize.
    Stop,
    /// Discard the utterance (a noise-only segment).
    Reset,
}

/// What the pusher does when a segment boundary is reached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoundaryAction {
    /// Streaming: the utterance audio already lives in the stream; ask the
    /// worker to finalize it.
    FinalizeStream,
    /// Streaming but the segment is too short to be speech: reset the stream
    /// so the noise cannot leak into the next sentence.
    CancelStream,
    /// Batch: enqueue a final job.
    QueueFinal,
    /// Batch and too short to be speech: drop it.
    DropNoise,
}

/// One-way pipeline selector for a subtitle session.
///
/// Streaming is selected only when an engine is loaded *and* its model has
/// native cache-aware streaming. Once streaming fails (a missing engine, a
/// begin/feed/finalize error, the model reloaded without streaming), the
/// session degrades to [`SubtitlePipeline::Batch`] and never flips back.
/// The audio already forwarded to the failed stream is still in the pusher's
/// segment buffer, so the next boundary transcribes it through the batch path
/// — nothing is lost and nothing is delivered twice.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SubtitlePipelineState {
    pipeline: SubtitlePipeline,
}

impl SubtitlePipelineState {
    pub fn new(engine_loaded: bool, model_supports_streaming: bool) -> Self {
        let pipeline = if engine_loaded && model_supports_streaming {
            SubtitlePipeline::Streaming
        } else {
            SubtitlePipeline::Batch
        };
        Self { pipeline }
    }

    pub fn pipeline(&self) -> SubtitlePipeline {
        self.pipeline
    }

    pub fn is_streaming(&self) -> bool {
        self.pipeline == SubtitlePipeline::Streaming
    }

    /// Degrades to the batch pipeline; returns `true` only when it actually
    /// changed, so the fallback is logged exactly once.
    pub fn on_stream_failure(&mut self) -> bool {
        let changed = self.pipeline != SubtitlePipeline::Batch;
        self.pipeline = SubtitlePipeline::Batch;
        changed
    }
}

/// Boundary action for a session, from its pipeline and the length of the
/// segment that just ended.
pub fn boundary_action(pipeline: SubtitlePipeline, segment_samples: usize) -> BoundaryAction {
    let long_enough = segment_samples >= MIN_SEGMENT_SAMPLES;
    match (pipeline, long_enough) {
        (SubtitlePipeline::Streaming, true) => BoundaryAction::FinalizeStream,
        (SubtitlePipeline::Streaming, false) => BoundaryAction::CancelStream,
        (SubtitlePipeline::Batch, true) => BoundaryAction::QueueFinal,
        (SubtitlePipeline::Batch, false) => BoundaryAction::DropNoise,
    }
}

/// Whether a callback stamped with `job_generation` still belongs to the
/// current session. A mismatch means the overlay was torn down or replaced
/// (P0.2), so its text must never be delivered.
pub fn generation_is_current(job_generation: u32, current_generation: u32) -> bool {
    job_generation == current_generation
}

/// Adaptive voice-activity detector for system audio.
///
/// A fixed RMS threshold cannot work for device audio: with steady background
/// music above that threshold `silence_run` never grows and every segment is
/// only closed by the 6 s hard cap. The noise floor is therefore estimated
/// from the *minimum* RMS of a short rolling window, smoothed by a 0.7/0.3 EMA
/// so a single quiet sample cannot drop it. The minimum (rather than an EMA of
/// every block) matters: a plain EMA chases sustained speech and would
/// eventually classify the speaker as noise. Speech is anything above the
/// floor plus [`NOISE_FLOOR_MARGIN_RMS`], never below the digital-silence
/// floor [`MIN_ABSOLUTE_SPEECH_RMS`].
pub struct SubtitleVad {
    /// Smoothed estimate of the background noise RMS.
    noise_floor_rms: f32,
    /// Window minimum over the last [`VAD_WINDOW_BLOCKS`] blocks.
    window: VecDeque<f32>,
}

impl Default for SubtitleVad {
    fn default() -> Self {
        Self::new()
    }
}

impl SubtitleVad {
    pub fn new() -> Self {
        Self {
            noise_floor_rms: 0.0,
            window: VecDeque::with_capacity(VAD_WINDOW_BLOCKS),
        }
    }

    /// Current noise-floor estimate (exposed for logging/tests).
    pub fn noise_floor(&self) -> f32 {
        self.noise_floor_rms
    }

    /// Current speech threshold: the floor plus its margin, clamped up to the
    /// digital-silence floor.
    pub fn threshold(&self) -> f32 {
        (self.noise_floor_rms + NOISE_FLOOR_MARGIN_RMS).max(MIN_ABSOLUTE_SPEECH_RMS)
    }

    /// Feeds one audio block and returns whether it contains foreground speech.
    /// Pure, deterministic and O([`VAD_WINDOW_BLOCKS`]) per block.
    pub fn observe(&mut self, rms: f32) -> bool {
        let rms = if rms.is_finite() { rms.max(0.0) } else { 0.0 };
        if self.window.len() == VAD_WINDOW_BLOCKS {
            self.window.pop_front();
        }
        self.window.push_back(rms);
        let window_min = self.window.iter().copied().fold(f32::INFINITY, f32::min);
        self.noise_floor_rms = (self.noise_floor_rms * VAD_FLOOR_EMA_OLD
            + window_min * VAD_FLOOR_EMA_NEW)
            .clamp(0.0, MAX_NOISE_FLOOR_RMS);
        rms >= self.threshold()
    }
}

/// Whether `text` ends with strong sentence-terminal punctuation across Latin
/// and CJK scripts, guarding decimals and single-letter abbreviations.
pub fn ends_with_sentence_punct(text: &str) -> bool {
    let t = text.trim_end();
    let mut chars = t.chars().rev();
    let last = match chars.next() {
        Some(c) => c,
        None => return false,
    };
    match last {
        '。' | '？' | '！' | '?' | '!' | '…' => true,
        '.' => {
            let prev = chars.next();
            let prev2 = chars.next();
            match (prev, prev2) {
                // "3.14" style decimals never end a sentence.
                (Some(c), _) if c.is_ascii_digit() => false,
                // "U.S." style dotted abbreviations.
                (Some(c), Some('.')) if c.is_ascii_alphabetic() => false,
                // Single capital letter followed by a period ("A.").
                (Some(c), None) if c.is_ascii_uppercase() => false,
                (Some(c), Some(' ')) if c.is_ascii_uppercase() => false,
                _ => true,
            }
        }
        _ => false,
    }
}

/// Whether `text` ends with clause-internal punctuation.
pub fn ends_with_clause_punct(text: &str) -> bool {
    let t = text.trim_end();
    matches!(
        t.chars().last(),
        Some('，' | '、' | ',' | ';' | ':' | '：' | '—')
    )
}

/// Pure boundary decision: should the current segment be finalized now?
///
/// - 0.7 s of trailing silence or the 6 s hard cap always finalize.
/// - A sentence terminator plus a 280 ms pause finalizes early (the pause that
///   naturally follows a finished sentence).
/// - Clause punctuation inside a segment longer than 3.5 s plus a 220 ms pause
///   finalizes cleanly before the forced cut.
pub fn should_finalize_segment(
    segment_samples: usize,
    silence_run: usize,
    last_partial: &str,
) -> bool {
    if silence_run >= FINALIZE_SILENCE_SAMPLES || segment_samples >= MAX_SEGMENT_SAMPLES {
        return true;
    }
    if segment_samples >= MIN_SEGMENT_SAMPLES
        && silence_run >= EARLY_SENTENCE_SILENCE_SAMPLES
        && ends_with_sentence_punct(last_partial)
    {
        return true;
    }
    if segment_samples >= CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES
        && silence_run >= CLAUSE_SPLIT_SILENCE_SAMPLES
        && ends_with_clause_punct(last_partial)
    {
        return true;
    }
    false
}

struct Job {
    samples: Vec<f32>,
    is_final: bool,
    /// Position of the job's last sample in the overall pushed-audio stream,
    /// used by the worker to measure how stale the job is.
    end_sample: u64,
    /// Session generation this job was created under. The worker drops jobs
    /// whose generation no longer matches the current session (P0.2).
    generation: u32,
}

struct LiveSubtitleState {
    /// Current un-finalized speech segment.
    segment: Vec<f32>,
    /// Rolling pre-speech audio, prepended once speech starts.
    preroll: Vec<f32>,
    has_speech: bool,
    /// Consecutive quiet samples at the tail of `segment`.
    silence_run: usize,
    samples_since_tick: usize,
    worker_tx: crossbeam_channel::Sender<Job>,
    worker_busy: Arc<AtomicBool>,
    /// Total samples ever pushed; shared with the worker for lag measurement.
    total_pushed: Arc<AtomicU64>,
    /// Number of final jobs queued but not yet fully processed. While > 0,
    /// partials are not submitted so the worker catches up on finals first.
    pending_finals: Arc<AtomicUsize>,
    /// Measured transcription speed as milli-RTF (compute ms per audio ms),
    /// smoothed by the worker; 0 until the first job completes. Used to
    /// predict a partial's cost before submitting it.
    rtf_milli: Arc<AtomicU32>,
    /// Session generation this state belongs to; stamped on every job (P0.2).
    generation: u32,
    /// Adaptive noise-floor VAD (background-music tolerant).
    vad: SubtitleVad,
    /// Text of the newest partial hypothesis for the current segment, shared
    /// with the worker so `pushAudio` can finalize on sentence punctuation
    /// without waiting for the full 0.7 s silence window.
    last_partial_text: Arc<Mutex<String>>,
    /// Stream position where the current segment started. The worker only
    /// records partial text for jobs ending at or after it, so a late partial
    /// from the previous segment cannot trigger an early cut in this one.
    segment_epoch: Arc<AtomicU64>,
    /// Streaming/batch pipeline of this session (one-way).
    pipeline: SubtitlePipelineState,
    /// Ordered audio + boundary items of the current utterance, for the
    /// streaming worker (see [`StreamInput`]).
    stream_tx: crossbeam_channel::Sender<StreamInput>,
}

static LIVE_STATE: Lazy<Mutex<Option<LiveSubtitleState>>> = Lazy::new(|| Mutex::new(None));

/// Delivers one caption through `onSubtitleText(String, boolean)`. Both workers
/// (batch and streaming) go through this helper so the JNI signature lives in
/// one place (AGENTS.md §4.4).
fn deliver(env: &mut JNIEnv, service: &JObject, text: &str, is_final: bool) {
    let _ = env.with_local_frame(16, |local_env| {
        crate::jni_util::notify_subtitle(local_env, service, text, is_final);
        Ok::<(), jni::errors::Error>(())
    });
}

/// Forwards one block of the current utterance to the streaming worker. No-op
/// in the batch pipeline (and after a stream failure, which flips the
/// session to batch).
fn forward_stream_audio(state: &LiveSubtitleState, samples: &[f32]) {
    if state.pipeline.is_streaming() && !samples.is_empty() {
        let _ = state.stream_tx.send(StreamInput::Audio(samples.to_vec()));
    }
}

/// Permanently degrades the current session to the batch pipeline (see
/// [`SubtitlePipelineState`]). The audio already forwarded to the failed
/// stream is still in the pusher's segment buffer, so the next boundary
/// transcribes it with the batch worker: no speech is lost. (A final already
/// delivered before the failure may repeat the tail carried over from a forced
/// split; truncating the segment instead would lose audio no final covered yet.)
fn degrade_to_batch(generation: u32) {
    let mut guard = LIVE_STATE.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(state) = guard.as_mut() {
        if state.generation == generation && state.pipeline.on_stream_failure() {
            log::warn!("subtitle streaming disabled for this session; using the batch pipeline");
        }
    }
}

/// Streaming worker: one `transcribe_cpp::Stream` per utterance.
///
/// Waits for the first block of the next utterance before opening a stream, so
/// an idle session neither holds the engine mutex (the IME or the popup may
/// need it) nor keeps a stream open over silence. Partial hypotheses are
/// delivered as they change and published to `partial_text` so the pusher can
/// close the segment on sentence punctuation; `Stop` finalizes the utterance
/// and delivers the corrected final text; a failure degrades the session to
/// the batch pipeline and the loop re-arms for the next utterance.
fn run_stream_worker(
    vm: Arc<jni::JavaVM>,
    service_ref: jni::objects::GlobalRef,
    generation: u32,
    input_rx: crossbeam_channel::Receiver<StreamInput>,
    partial_text: Arc<Mutex<String>>,
) {
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => {
            log::error!("Subtitle stream worker failed to attach: {}", e);
            return;
        }
    };
    let service_obj = service_ref.as_obj();
    loop {
        // The first item of an utterance is always audio: a command with no
        // audio in front of it belongs to a segment that never reached the
        // stream (e.g. a blip cancelled before any block was queued).
        let first = match input_rx.recv() {
            Ok(StreamInput::Audio(chunk)) => chunk,
            Ok(_) => continue,
            // The channel is dropped with the session state: teardown.
            Err(_) => break,
        };
        if !generation_is_current(generation, GENERATION.load(Ordering::SeqCst)) {
            break;
        }
        let engine_arc = match engine::get_engine() {
            Some(arc) => arc,
            None => {
                degrade_to_batch(generation);
                continue;
            }
        };
        if !engine::supports_streaming_shared(&engine_arc) {
            degrade_to_batch(generation);
            continue;
        }

        // Local command channel for `run_stream`: `drain` translates the
        // ordered boundary items into it, so a boundary is seen only after
        // every block that preceded it was fed into this stream.
        let (ctrl_tx, ctrl_rx) = crossbeam_channel::unbounded::<engine::StreamCmd>();
        let mut pending_first = Some(first);
        let mut fed_samples: usize = 0;
        // Set once the utterance's boundary has been translated. Post-boundary
        // blocks belong to the NEXT utterance and must stay queued: the
        // engine's stop path drains once more, which would otherwise pull them
        // into the stream that is being finalized (and lose them).
        let mut finished = false;
        let result = {
            let mut drain = |buf: &mut Vec<f32>| {
                if finished {
                    return;
                }
                if let Some(chunk) = pending_first.take() {
                    fed_samples += chunk.len();
                    buf.extend_from_slice(&chunk);
                }
                loop {
                    match input_rx.try_recv() {
                        Ok(StreamInput::Audio(chunk)) => {
                            fed_samples += chunk.len();
                            buf.extend_from_slice(&chunk);
                        }
                        Ok(StreamInput::Stop) => {
                            finished = true;
                            let _ = ctrl_tx.send(engine::StreamCmd::Stop);
                            break;
                        }
                        Ok(StreamInput::Reset) => {
                            finished = true;
                            let _ = ctrl_tx.send(engine::StreamCmd::Cancel);
                            break;
                        }
                        Err(crossbeam_channel::TryRecvError::Empty) => break,
                        // The session state (and its sender) is gone: abandon
                        // the stream instead of holding the engine forever.
                        Err(crossbeam_channel::TryRecvError::Disconnected) => {
                            finished = true;
                            let _ = ctrl_tx.send(engine::StreamCmd::Cancel);
                            break;
                        }
                    }
                }
            };
            let mut on_partial = |text: &str| {
                if !generation_is_current(generation, GENERATION.load(Ordering::SeqCst)) {
                    return;
                }
                {
                    let mut slot = partial_text.lock().unwrap_or_else(|p| p.into_inner());
                    slot.clear();
                    slot.push_str(text);
                }
                deliver(&mut env, service_obj, text, false);
            };
            engine::transcribe_subtitle_stream_shared(
                &engine_arc,
                &ctrl_rx,
                &mut drain,
                &mut on_partial,
            )
        };

        // The published hypothesis belonged to the utterance that just ended.
        partial_text
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clear();
        if !generation_is_current(generation, GENERATION.load(Ordering::SeqCst)) {
            break;
        }
        match result {
            Ok(text) => {
                log::info!(
                    "Subtitle stream: {:.1}s audio finalized",
                    fed_samples as f64 / SAMPLE_RATE as f64
                );
                let text = text.trim();
                if !text.is_empty() {
                    deliver(&mut env, service_obj, text, true);
                }
            }
            // The pusher reset the stream (noise-only segment) or the session
            // was torn down: nothing to deliver.
            Err(e) if e == "Canceled" => {}
            Err(e) => {
                log::warn!(
                    "subtitle stream failed ({}); continuing with the batch pipeline",
                    e
                );
                degrade_to_batch(generation);
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let service_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    // New session: bump the generation so any job still queued in an older
    // session's channel is recognized as stale and never transcribed or
    // delivered (P0.2).
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst) + 1;

    let (tx, rx) = crossbeam_channel::unbounded::<Job>();
    let (stream_tx, stream_rx) = crossbeam_channel::unbounded::<StreamInput>();
    let worker_busy = Arc::new(AtomicBool::new(false));
    let total_pushed = Arc::new(AtomicU64::new(0));
    let pending_finals = Arc::new(AtomicUsize::new(0));
    let rtf_milli = Arc::new(AtomicU32::new(0));
    let last_partial_text = Arc::new(Mutex::new(String::new()));
    let segment_epoch = Arc::new(AtomicU64::new(0));
    let worker_partial_text = last_partial_text.clone();
    let worker_segment_epoch = segment_epoch.clone();

    // Pick the pipeline once per session. Native streaming requires an engine
    // that is already loaded with a streaming model; otherwise (a model
    // without streaming, or a session started before the model finished
    // loading) the batch path runs unchanged. Both flags are read without
    // touching the engine mutex: initNative runs on the Java main thread.
    let pipeline =
        SubtitlePipelineState::new(engine::is_engine_loaded(), engine::is_streaming_capable());
    log::info!("subtitle pipeline: {:?}", pipeline.pipeline());

    *LIVE_STATE.lock().unwrap_or_else(|p| p.into_inner()) = Some(LiveSubtitleState {
        segment: Vec::new(),
        preroll: Vec::new(),
        has_speech: false,
        silence_run: 0,
        samples_since_tick: 0,
        worker_tx: tx,
        worker_busy: worker_busy.clone(),
        total_pushed: total_pushed.clone(),
        pending_finals: pending_finals.clone(),
        rtf_milli: rtf_milli.clone(),
        generation,
        vad: SubtitleVad::new(),
        last_partial_text: last_partial_text.clone(),
        segment_epoch,
        pipeline,
        stream_tx,
    });

    if pipeline.is_streaming() {
        let stream_vm = vm.clone();
        let stream_service_ref = service_ref.clone();
        let stream_partial_text = last_partial_text.clone();
        std::thread::spawn(move || {
            run_stream_worker(
                stream_vm,
                stream_service_ref,
                generation,
                stream_rx,
                stream_partial_text,
            );
        });
    }

    // The batch worker is spawned for every session: it runs the pipeline for
    // models without native streaming and is the fallback when a stream fails.
    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("Subtitle worker failed to attach: {}", e);
                return;
            }
        };
        let service_obj = service_ref.as_obj();
        // Whether the previously processed final was dropped for lag — used
        // to emit a single "…" gap marker per run of dropped finals.
        let mut gap_pending = false;

        // A final that did not fit the current merged group is stashed here
        // and processed as the next group, so no queued final is dropped.
        let mut pending_job: Option<Job> = None;
        loop {
            let job = match pending_job.take() {
                Some(j) => j,
                None => match rx.recv() {
                    Ok(j) => j,
                    Err(_) => break,
                },
            };
            let mut job = job;
            // A job from a previous session (cleanup + new initNative) must
            // never be transcribed nor delivered: the overlay may be torn
            // down or a new session may own the service (P0.2). Skip without
            // touching the engine so an abandoned session cannot delay or
            // pollute the new one. The worker exits once its channel drains.
            if job.generation != GENERATION.load(Ordering::SeqCst) {
                continue;
            }
            // Fold queued finals into this run (see MAX_MERGED_SAMPLES). A
            // queued partial is dropped instead: it re-transcribes audio a
            // queued final already covers, and a fresh one follows anyway.
            if job.is_final {
                let mut merged = 0usize;
                'merge: loop {
                    if job.samples.len() >= MAX_MERGED_SAMPLES {
                        break 'merge;
                    }
                    match rx.try_recv() {
                        Ok(next) if next.is_final => {
                            // Never merge work from another generation into
                            // this job: everything queued behind a stale
                            // final belongs to an older session anyway.
                            if next.generation != job.generation {
                                break 'merge;
                            }
                            // Only append while the combined audio fits the
                            // cap; otherwise this final starts the next group.
                            if merge_fits(job.samples.len(), next.samples.len(), MAX_MERGED_SAMPLES)
                            {
                                job.samples.extend_from_slice(&next.samples);
                                job.end_sample = next.end_sample;
                                pending_finals.fetch_sub(1, Ordering::SeqCst);
                                merged += 1;
                            } else {
                                pending_job = Some(next);
                                break 'merge;
                            }
                        }
                        // A queued partial: drop it and keep merging finals.
                        Ok(_) => {}
                        Err(_) => break 'merge,
                    }
                }
                if merged > 0 {
                    log::info!(
                        "Merged {} queued finals into one {:.1}s run",
                        merged + 1,
                        job.samples.len() as f64 / SAMPLE_RATE as f64
                    );
                }
            }

            // The session may have been invalidated while we were merging;
            // never start a costly transcription for an abandoned session.
            if job.generation != GENERATION.load(Ordering::SeqCst) {
                continue;
            }
            // Stale-job policy: if transcription can't keep up with the
            // audio, skip old work instead of drifting ever further behind.
            // A merged final ends at the newest queued audio, so merging
            // usually keeps the lag below the drop threshold by itself.
            let lag = total_pushed
                .load(Ordering::SeqCst)
                .saturating_sub(job.end_sample);
            let skip = if job.is_final {
                lag > MAX_FINAL_LAG_SAMPLES
            } else {
                lag > MAX_PARTIAL_LAG_SAMPLES
            };

            if skip {
                if job.is_final {
                    log::warn!(
                        "Dropping final {:.1}s behind real time to catch up",
                        lag as f64 / SAMPLE_RATE as f64
                    );
                    gap_pending = true;
                }
            } else if let Some(engine_arc) = engine::get_engine() {
                // Re-check before the expensive engine call too: cleanup may
                // have raced in between the checks above.
                if job.generation != GENERATION.load(Ordering::SeqCst) {
                    continue;
                }
                let audio_secs = job.samples.len() as f64 / SAMPLE_RATE as f64;
                let started = std::time::Instant::now();
                // Subtitle ASR is always Task::Transcribe: the global
                // `model_translate` switch (Whisper imports) must not silently
                // translate captions — subtitle translation is a Java-side,
                // user-selected target feature instead.
                let res = engine::transcribe_subtitle(&engine_arc, job.samples);
                let elapsed = started.elapsed().as_secs_f64();
                log::info!(
                    "Subtitle {} job: {:.1}s audio in {:.2}s (lag {:.1}s)",
                    if job.is_final { "final" } else { "partial" },
                    audio_secs,
                    elapsed,
                    lag as f64 / SAMPLE_RATE as f64,
                );

                // Track transcription speed (EMA) so the pusher can predict
                // job costs; also reflects thermal throttling over time.
                let sample = (elapsed / audio_secs * 1000.0) as u32;
                let old = rtf_milli.load(Ordering::SeqCst);
                let ema = if old == 0 {
                    sample
                } else {
                    (old * 7 + sample * 3) / 10
                };
                rtf_milli.store(ema, Ordering::SeqCst);

                if let Ok(r) = res {
                    // The session may have been torn down while transcribing;
                    // never deliver stale text to a dead overlay or a new
                    // session's service (P0.2).
                    if job.generation != GENERATION.load(Ordering::SeqCst) {
                        continue;
                    }
                    let text = r.trim();
                    // Publish the newest partial hypothesis so `pushAudio` can
                    // end the segment on sentence punctuation + a short pause
                    // instead of waiting for the full silence window. A late
                    // partial from a finalized segment is ignored via the
                    // segment epoch, so it cannot cut the next segment early.
                    if job.is_final {
                        worker_partial_text
                            .lock()
                            .unwrap_or_else(|p| p.into_inner())
                            .clear();
                    } else if job.end_sample >= worker_segment_epoch.load(Ordering::SeqCst) {
                        let mut slot = worker_partial_text
                            .lock()
                            .unwrap_or_else(|p| p.into_inner());
                        slot.clear();
                        slot.push_str(text);
                    }
                    if !text.is_empty() && gap_pending {
                        // Mark the dropped stretch so the transcript doesn't
                        // silently glue unrelated sentences together.
                        deliver(&mut env, service_obj, "…", true);
                        gap_pending = false;
                    }
                    if !text.is_empty() || job.is_final {
                        deliver(&mut env, service_obj, text, job.is_final);
                    }
                }
            }

            if job.is_final {
                pending_finals.fetch_sub(1, Ordering::SeqCst);
            }
            worker_busy.store(false, Ordering::SeqCst);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    // Bump the generation first: any job still queued (or mid-transcription)
    // in this session's worker becomes stale and is dropped without work or
    // delivery. Then drop the state, which drops the sender; the worker
    // exits once its queue drains and releases its GlobalRef (P0.2).
    GENERATION.fetch_add(1, Ordering::SeqCst);
    *LIVE_STATE.lock().unwrap_or_else(|p| p.into_inner()) = None;
}

use std::cell::RefCell;

thread_local! {
    static PUSH_BUFFER: RefCell<Vec<f32>> = RefCell::new(Vec::with_capacity(4096));
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let len = usize::try_from(length).unwrap_or(0);
    if len == 0 {
        return;
    }

    PUSH_BUFFER.with(|cell| {
        let mut input = cell.borrow_mut();
        if input.len() < len {
            input.resize(len, 0.0);
        }
        let slice = &mut input[..len];
        if env.get_float_array_region(&data, 0, slice).is_err() {
            return;
        }

        // The lock is held while cloning the segment for partial jobs (an
        // allocation that can panic on OOM), so recover from poison instead of
        // panicking the audio thread (R2).
        let mut guard = LIVE_STATE.lock().unwrap_or_else(|p| p.into_inner());
        let state = match guard.as_mut() {
            Some(s) => s,
            None => return,
        };

        let stream_pos = state.total_pushed.fetch_add(len as u64, Ordering::SeqCst) + len as u64;

        let rms = crate::audio::fast_rms(slice);
        let is_sound = state.vad.observe(rms);

        if !state.has_speech {
            if is_sound {
                // Speech begins: seed the segment with the pre-roll without dropping capacity.
                state.segment.clear();
                state.segment.extend(state.preroll.drain(..));
                state.segment.extend_from_slice(slice);
                state.has_speech = true;
                state.silence_run = 0;
                state.samples_since_tick = state.segment.len();
                // The pre-roll belongs to the utterance: forward it with the
                // first block so the first word isn't clipped in the stream.
                let seed = state.segment.clone();
                forward_stream_audio(state, &seed);
            } else {
                state.preroll.extend_from_slice(slice);
                let excess = state.preroll.len().saturating_sub(PREROLL_SAMPLES);
                if excess > 0 {
                    state.preroll.drain(..excess);
                }
                return;
            }
        } else {
            state.segment.extend_from_slice(slice);
            state.samples_since_tick += len;
            if is_sound {
                state.silence_run = 0;
            } else {
                state.silence_run += len;
            }
            // Forward the block to the streaming worker while the utterance
            // is in progress. A block that ends the utterance (below) is
            // forwarded before `Stop` is queued, so it is never dropped.
            forward_stream_audio(state, slice);
        }

        let last_partial = state
            .last_partial_text
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone();
        let boundary =
            should_finalize_segment(state.segment.len(), state.silence_run, &last_partial);
        if boundary {
            // A boundary reached through silence (0.7 s, sentence punctuation
            // + 280 ms, or clause punctuation + 220 ms) ends the utterance; the
            // 6 s hard cap in dense speech splits at the quietest point and
            // carries the remainder into the next segment so no word is cut.
            let ends_by_pause = state.silence_run >= CLAUSE_SPLIT_SILENCE_SAMPLES;
            let mut samples = std::mem::take(&mut state.segment);
            if ends_by_pause {
                // Drop most of the trailing silence; keep a short tail.
                let keep = samples.len() - state.silence_run + FINAL_TAIL_SAMPLES;
                samples.truncate(keep.min(samples.len()));
                state.has_speech = false;
                state.preroll.clear();
            } else {
                // Forced cut mid-speech: split at the quietest point in the last
                // few seconds and carry the remainder into the next segment so no
                // word is chopped in half.
                let from = samples.len().saturating_sub(3 * SAMPLE_RATE);
                let split = crate::audio::find_quietest_split(&samples, from, samples.len());
                state.segment = samples.split_off(split);
            }
            state.silence_run = 0;
            state.samples_since_tick = state.segment.len();
            // The partial that justified this boundary belongs to the segment
            // that just ended; start the next one with a clean slate.
            state
                .last_partial_text
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .clear();
            state.segment_epoch.store(stream_pos, Ordering::SeqCst);

            match boundary_action(state.pipeline.pipeline(), samples.len()) {
                BoundaryAction::FinalizeStream => {
                    // The utterance audio is already in the stream; ask the
                    // worker to finalize it and deliver the committed text.
                    let _ = state.stream_tx.send(StreamInput::Stop);
                }
                BoundaryAction::CancelStream => {
                    // Too short to be speech: reset the stream so this noise
                    // cannot contaminate the next sentence.
                    let _ = state.stream_tx.send(StreamInput::Reset);
                }
                BoundaryAction::QueueFinal => {
                    // Finals are always queued (the worker may still drop them
                    // if they go stale — see MAX_FINAL_LAG_SAMPLES).
                    state.worker_busy.store(true, Ordering::SeqCst);
                    state.pending_finals.fetch_add(1, Ordering::SeqCst);
                    let _ = state.worker_tx.send(Job {
                        samples,
                        is_final: true,
                        end_sample: stream_pos,
                        generation: state.generation,
                    });
                }
                BoundaryAction::DropNoise => {}
            }
        } else if !state.pipeline.is_streaming()
            && state.samples_since_tick >= TICK_SAMPLES
            && !state.worker_busy.load(Ordering::SeqCst)
            && state.pending_finals.load(Ordering::SeqCst) == 0
            && partial_affordable(state)
        {
            // Partial update, only while the worker is idle, no final is waiting
            // and the job is predicted to be cheap (latest-wins; finals first) so
            // a slow device never queues up work and drifts behind real time.
            state.worker_busy.store(true, Ordering::SeqCst);
            state.samples_since_tick = 0;
            let _ = state.worker_tx.send(Job {
                samples: state.segment.clone(),
                is_final: false,
                end_sample: stream_pos,
                generation: state.generation,
            });
        }
    });
}

/// Whether a partial of the current segment is predicted to transcribe within
/// [`MAX_PARTIAL_COST_SECS`], based on the worker's measured speed. As the
/// segment grows (or the device slows down under thermal throttling) partials
/// stop, leaving the worker free for the finals that carry the transcript.
fn partial_affordable(state: &LiveSubtitleState) -> bool {
    let rtf = state.rtf_milli.load(Ordering::SeqCst) as f32 / 1000.0;
    let segment_secs = state.segment.len() as f32 / SAMPLE_RATE as f32;
    segment_secs * rtf <= MAX_PARTIAL_COST_SECS
}

/// Pure decision: may `next_len` samples be appended to a merged group that
/// already holds `current_len` samples without exceeding `cap`?
/// An empty group always takes the first segment (provided it fits on its
/// own, which holds for every real segment).
pub fn merge_fits(current_len: usize, next_len: usize, cap: usize) -> bool {
    if current_len == 0 {
        next_len <= cap
    } else {
        current_len.saturating_add(next_len) <= cap
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_fit_appended() {
        assert!(merge_fits(100, 50, 150));
    }

    #[test]
    fn one_sample_over_starts_new_group() {
        assert!(!merge_fits(100, 51, 150));
    }

    #[test]
    fn empty_group_takes_first_segment() {
        assert!(merge_fits(0, 150, 150));
        assert!(merge_fits(0, 1, 150));
    }

    #[test]
    fn order_preserved_single_merge() {
        // A group that cannot fit the next segment leaves it for the next
        // group; the decision is purely a function of lengths, so repeated
        // calls in order yield stable results.
        assert!(merge_fits(0, 60, 100));
        assert!(merge_fits(60, 40, 100));
        assert!(!merge_fits(60, 41, 100));
    }

    /// Feeds `blocks` identical RMS blocks and returns how many were speech.
    fn feed(vad: &mut SubtitleVad, rms: f32, blocks: usize) -> usize {
        (0..blocks).filter(|_| vad.observe(rms)).count()
    }

    #[test]
    fn vad_detects_speech_in_digital_silence() {
        let mut vad = SubtitleVad::new();
        assert!(!vad.observe(0.0));
        assert!(vad.observe(0.05));
        assert!(!vad.observe(0.0));
    }

    #[test]
    fn vad_adapts_to_constant_background_music() {
        // Music at 0.02 used to keep `silence_run` at zero forever. After the
        // window fills, the floor must sit above the music so pauses count.
        let mut vad = SubtitleVad::new();
        for _ in 0..(VAD_WINDOW_BLOCKS + 10) {
            vad.observe(0.02);
        }
        assert!(!vad.observe(0.02), "steady music must become background");
        assert!(vad.observe(0.08), "speech over the music must be detected");
        assert!(!vad.observe(0.02), "the pause back to music is silence");
        assert!(vad.noise_floor() > 0.014);
    }

    #[test]
    fn vad_adapts_to_low_background_hum() {
        let mut vad = SubtitleVad::new();
        for _ in 0..(VAD_WINDOW_BLOCKS + 10) {
            vad.observe(0.006);
        }
        assert!(!vad.observe(0.006));
        assert!(vad.observe(0.05));
    }

    #[test]
    fn vad_isolated_peak_does_not_poison_the_floor() {
        let mut vad = SubtitleVad::new();
        feed(&mut vad, 0.001, VAD_WINDOW_BLOCKS + 10);
        let before = vad.noise_floor();
        assert!(vad.observe(0.9), "a loud transient is itself sound");
        feed(&mut vad, 0.001, VAD_WINDOW_BLOCKS);
        assert!(vad.noise_floor() < before + 0.005);
        assert!(
            vad.observe(0.03),
            "normal speech after the peak is detected"
        );
    }

    #[test]
    fn vad_floor_is_clamped() {
        let mut vad = SubtitleVad::new();
        feed(&mut vad, 0.5, VAD_WINDOW_BLOCKS * 4);
        assert!(vad.noise_floor() <= MAX_NOISE_FLOOR_RMS + f32::EPSILON);
        assert!(vad.observe(1.0));
    }

    #[test]
    fn vad_ignores_non_finite_rms() {
        let mut vad = SubtitleVad::new();
        assert!(!vad.observe(f32::NAN));
        assert!(!vad.observe(f32::INFINITY));
        assert!(vad.noise_floor().is_finite());
        assert!(vad.observe(0.05));
    }

    #[test]
    fn pipeline_needs_both_engine_and_streaming_model() {
        assert_eq!(
            SubtitlePipelineState::new(true, true).pipeline(),
            SubtitlePipeline::Streaming
        );
        assert_eq!(
            SubtitlePipelineState::new(true, false).pipeline(),
            SubtitlePipeline::Batch
        );
        assert_eq!(
            SubtitlePipelineState::new(false, true).pipeline(),
            SubtitlePipeline::Batch
        );
        assert_eq!(
            SubtitlePipelineState::new(false, false).pipeline(),
            SubtitlePipeline::Batch
        );
    }

    #[test]
    fn stream_failure_degrades_one_way() {
        let mut state = SubtitlePipelineState::new(true, true);
        assert!(state.is_streaming());
        assert!(
            state.on_stream_failure(),
            "the first failure flips the mode"
        );
        assert!(!state.is_streaming());
        assert!(
            !state.on_stream_failure(),
            "a later failure must not report another change (single log line)"
        );
        let mut batch = SubtitlePipelineState::new(false, true);
        assert!(!batch.on_stream_failure());
        assert_eq!(batch.pipeline(), SubtitlePipeline::Batch);
    }

    #[test]
    fn streaming_boundary_actions() {
        assert_eq!(
            boundary_action(SubtitlePipeline::Streaming, MIN_SEGMENT_SAMPLES),
            BoundaryAction::FinalizeStream
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Streaming, MAX_SEGMENT_SAMPLES),
            BoundaryAction::FinalizeStream
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Streaming, MIN_SEGMENT_SAMPLES - 1),
            BoundaryAction::CancelStream
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Streaming, 0),
            BoundaryAction::CancelStream
        );
    }

    #[test]
    fn batch_boundary_actions() {
        assert_eq!(
            boundary_action(SubtitlePipeline::Batch, MIN_SEGMENT_SAMPLES),
            BoundaryAction::QueueFinal
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Batch, MAX_SEGMENT_SAMPLES),
            BoundaryAction::QueueFinal
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Batch, MIN_SEGMENT_SAMPLES - 1),
            BoundaryAction::DropNoise
        );
        assert_eq!(
            boundary_action(SubtitlePipeline::Batch, 0),
            BoundaryAction::DropNoise
        );
    }

    /// Negative test: the batch pipeline must never route audio into a stream,
    /// whatever the segment length.
    #[test]
    fn batch_pipeline_never_uses_stream_actions() {
        for len in [
            0,
            1,
            MIN_SEGMENT_SAMPLES - 1,
            MIN_SEGMENT_SAMPLES,
            MAX_SEGMENT_SAMPLES,
            MAX_SEGMENT_SAMPLES * 2,
        ] {
            assert!(
                matches!(
                    boundary_action(SubtitlePipeline::Batch, len),
                    BoundaryAction::QueueFinal | BoundaryAction::DropNoise
                ),
                "batch must stay on the batch path for {len} samples"
            );
        }
    }

    #[test]
    fn generation_mismatch_rejects_stale_callbacks() {
        assert!(generation_is_current(7, 7));
        assert!(!generation_is_current(6, 7));
        assert!(!generation_is_current(7, 8));
        assert!(!generation_is_current(0, u32::MAX));
    }

    #[test]
    fn sentence_punctuation_detected_across_scripts() {
        for text in ["Hola.", "你好。", "¿Qué?", "¡Ya!", "Well!", "End…"] {
            assert!(ends_with_sentence_punct(text), "{text}");
        }
    }

    #[test]
    fn sentence_punctuation_negative_cases() {
        for text in [
            "3.14",
            "U.S.",
            "A.",
            "version 2.0",
            "sin puntuacion",
            "",
            "   ",
        ] {
            assert!(!ends_with_sentence_punct(text), "{text}");
        }
    }

    #[test]
    fn clause_punctuation_detected() {
        for text in ["hola,", "你好，", "uno、", "a; b:", "x：", "y—"] {
            assert!(ends_with_clause_punct(text), "{text}");
        }
        assert!(!ends_with_clause_punct("hola."));
        assert!(!ends_with_clause_punct("hola"));
    }

    #[test]
    fn full_silence_or_hard_cap_always_finalizes() {
        assert!(should_finalize_segment(
            8_000,
            FINALIZE_SILENCE_SAMPLES,
            "hola"
        ));
        assert!(should_finalize_segment(MAX_SEGMENT_SAMPLES, 0, ""));
    }

    #[test]
    fn sentence_terminator_ends_early_with_short_pause() {
        assert!(should_finalize_segment(
            8_000,
            EARLY_SENTENCE_SILENCE_SAMPLES,
            "Hola."
        ));
        assert!(should_finalize_segment(
            8_000,
            EARLY_SENTENCE_SILENCE_SAMPLES,
            "你好。"
        ));
    }

    #[test]
    fn sentence_terminator_without_pause_or_terminator_does_not_cut() {
        assert!(!should_finalize_segment(8_000, 1_000, "Hola."));
        assert!(!should_finalize_segment(
            8_000,
            EARLY_SENTENCE_SILENCE_SAMPLES,
            "Hola"
        ));
        // Negative cases: decimals and dotted abbreviations are not sentence
        // ends, so even with a 280 ms pause they must not cut early.
        assert!(!should_finalize_segment(
            8_000,
            EARLY_SENTENCE_SILENCE_SAMPLES,
            "3.14"
        ));
        assert!(!should_finalize_segment(
            8_000,
            EARLY_SENTENCE_SILENCE_SAMPLES,
            "U.S."
        ));
    }

    #[test]
    fn clause_split_needs_length_punctuation_and_pause() {
        assert!(should_finalize_segment(
            CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES,
            CLAUSE_SPLIT_SILENCE_SAMPLES,
            "hola,"
        ));
        assert!(!should_finalize_segment(
            CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES - 1,
            CLAUSE_SPLIT_SILENCE_SAMPLES,
            "hola,"
        ));
        assert!(!should_finalize_segment(
            CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES + 4_000,
            CLAUSE_SPLIT_SILENCE_SAMPLES - 1,
            "hola,"
        ));
        assert!(!should_finalize_segment(
            CLAUSE_SPLIT_MIN_SEGMENT_SAMPLES + 4_000,
            CLAUSE_SPLIT_SILENCE_SAMPLES,
            "hola"
        ));
    }
}
