//! Native backend for `LiveSubtitleService` (real-time captions for device audio).
//!
//! Audio arrives via `pushAudio` in small blocks (~64 ms). We build up a
//! *segment* of speech, send the whole segment to a worker thread for
//! transcription roughly once per tick, and display the result as a *partial*
//! (replaceable) caption. When trailing silence is detected — or the segment
//! hits a hard cap — the segment is *finalized*: transcribed once more and
//! committed, then the buffer starts fresh.
//!
//! Three things keep latency low and bounded:
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
/// Per-block RMS at or above this counts as sound worth transcribing.
const SPEECH_RMS: f32 = 0.004;
/// Segments shorter than this are dropped as noise (0.25 s).
const MIN_SEGMENT_SAMPLES: usize = 4_000;

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
}

static LIVE_STATE: Lazy<Mutex<Option<LiveSubtitleState>>> = Lazy::new(|| Mutex::new(None));

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
    let worker_busy = Arc::new(AtomicBool::new(false));
    let total_pushed = Arc::new(AtomicU64::new(0));
    let pending_finals = Arc::new(AtomicUsize::new(0));
    let rtf_milli = Arc::new(AtomicU32::new(0));

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
    });

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

        let deliver = |env: &mut jni::JNIEnv, text: &str, is_final: bool| {
            let _ = env.with_local_frame(16, |local_env| {
                crate::jni_util::notify_subtitle(local_env, service_obj, text, is_final);
                Ok::<(), jni::errors::Error>(())
            });
        };

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
                    if !text.is_empty() && gap_pending {
                        // Mark the dropped stretch so the transcript doesn't
                        // silently glue unrelated sentences together.
                        deliver(&mut env, "…", true);
                        gap_pending = false;
                    }
                    if !text.is_empty() || job.is_final {
                        deliver(&mut env, text, job.is_final);
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
        let is_sound = rms >= SPEECH_RMS;

        if !state.has_speech {
            if is_sound {
                // Speech begins: seed the segment with the pre-roll without dropping capacity.
                state.segment.clear();
                state.segment.extend(state.preroll.drain(..));
                state.segment.extend_from_slice(slice);
                state.has_speech = true;
                state.silence_run = 0;
                state.samples_since_tick = state.segment.len();
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
        }

        let silence_done = state.silence_run >= FINALIZE_SILENCE_SAMPLES;
        if silence_done || state.segment.len() >= MAX_SEGMENT_SAMPLES {
            let mut samples = std::mem::take(&mut state.segment);
            if silence_done {
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

            if samples.len() >= MIN_SEGMENT_SAMPLES {
                // Finals are always queued (the worker may still drop them if
                // they go stale — see MAX_FINAL_LAG_SAMPLES).
                state.worker_busy.store(true, Ordering::SeqCst);
                state.pending_finals.fetch_add(1, Ordering::SeqCst);
                let _ = state.worker_tx.send(Job {
                    samples,
                    is_final: true,
                    end_sample: stream_pos,
                    generation: state.generation,
                });
            }
        } else if state.samples_since_tick >= TICK_SAMPLES
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
}
