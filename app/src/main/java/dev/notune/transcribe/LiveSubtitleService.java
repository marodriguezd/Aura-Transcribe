package dev.notune.transcribe;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.StaticLayout;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.IntentCompat;

import java.util.ArrayDeque;

public class LiveSubtitleService extends Service {
    private static final String TAG = "LiveSubtitleService";
    public static final String ACTION_START = "dev.notune.transcribe.START_SUBTITLES";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_SUBTITLES";
    private static final String CHANNEL_ID = "LiveSubtitlesChannel";
    private static final int NOTIFICATION_ID = 12345;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (Throwable t) {
            try {
                Log.e(TAG, "Failed to load native libraries", t);
            } catch (Throwable ignored) {}
        }
    }

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;
    /** Read by the audio loop and written by teardown; volatile so the loop
     *  reliably observes the stop request (no stale JIT read). */
    private volatile boolean isRecording = false;
    /** Guards the AudioRecord/audio-thread ownership transitions (capture
     *  start, teardown, loop exit). The loop releases its own record in its
     *  {@code finally}; the stop path only releases when no live loop exists
     *  (see {@link SubtitleAudioLoop#mayReleaseRecord}). */
    private final Object mAudioLock = new Object();

    private WindowManager mWindowManager;
    private View mOverlayView;
    private TextView mSubtitleText;
    private Handler mMainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_START.equals(intent.getAction())) {
            int code = intent.getIntExtra("code", 0);
            Intent data = IntentCompat.getParcelableExtra(intent, "data", Intent.class);

            if (code == 0 || data == null) {
                Log.e(TAG, "Missing or invalid extras for media projection");
                stopSelf();
                return START_NOT_STICKY;
            }

            // ORDER MATTERS (Android 14 / API 34+). The documented contract is:
            //
            //   1. the Activity calls MediaProjectionManager.createScreenCaptureIntent()
            //      and the user grants the projection (done in LiveSubtitleActivity);
            //   2. the service starts its foreground service with the
            //      `mediaProjection` type;
            //   3. only then may MediaProjectionManager.getMediaProjection() be
            //      called.
            //
            // See "Media projection" on
            // developer.android.com/about/versions/14/changes/fgs-types-required:
            // "After you have created the foreground service, you can call
            // MediaProjectionManager.getMediaProjection()." Calling
            // getMediaProjection() while no mediaProjection foreground service is
            // running throws SecurityException("Media projections require a
            // foreground service of type
            // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"), which is the
            // exact failure the previous ordering produced.
            try {
                Notification notification = createNotification();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!obtainMediaProjection(code, data)) {
                // The foreground service is already running, so take it down
                // explicitly instead of leaving its notification behind.
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not stop the foreground service", e);
                }
                releaseProjection();
                stopSelf();
                return START_NOT_STICKY;
            }

            startSubtitleSession();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopSubtitleSession();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /**
     * Obtains the {@link MediaProjection} from the user's consent result.
     *
     * <p>Must run <em>after</em> {@link #startForeground(int, Notification, int)}
     * with {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}: the documented
     * contract for the {@code mediaProjection} foreground-service type is
     * consent (see {@code createScreenCaptureIntent()}) → start the foreground
     * service → {@code getMediaProjection()}. Calling it earlier throws
     * {@code SecurityException} because no {@code mediaProjection} foreground
     * service is running yet.
     *
     * @return true when a usable projection is held.
     */
    private boolean obtainMediaProjection(int code, Intent data) {
        if (mMediaProjection != null) return true;

        MediaProjection projection;
        try {
            projection = mProjectionManager.getMediaProjection(code, data);
        } catch (SecurityException e) {
            // The consent token was already consumed or rejected.
            Log.e(TAG, "MediaProjection consent rejected", e);
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not obtain MediaProjection", e);
            return false;
        }
        if (projection == null) {
            Log.e(TAG, "MediaProjection was null");
            return false;
        }
        mMediaProjection = projection;

        // Stop cleanly if the user revokes the projection from the status bar.
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mMainHandler.post(() -> {
                    stopSubtitleSession();
                    stopSelf();
                });
            }
        }, mMainHandler);
        return true;
    }

    /**
     * Releases a projection obtained by {@link #obtainMediaProjection} when the
     * session never reached the point of a full {@link #stopSubtitleSession()}
     * teardown (e.g. the projection could not be obtained, or startSubtitleSession
     * bailed out). Never throws.
     */
    private void releaseProjection() {
        if (mMediaProjection != null) {
            try {
                mMediaProjection.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not stop MediaProjection", e);
            }
            mMediaProjection = null;
        }
    }

    private void startSubtitleSession() {
        if (isRecording) return;

        initNative(this);
        setupOverlay();
        // MediaProjection audio capture only exists on Android 10+; on older
        // devices the whole subtitle surface is unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAudioCapture();
        } else {
            Log.e(TAG, "Live subtitles require Android 10 (API 29)+");
            stopSubtitleSession();
            stopSelf();
        }
    }

    private void stopSubtitleSession() {
        // Request loop termination before touching the record. stop() is the
        // documented way to unblock a read() parked inside the audio HAL, so
        // a live capture exits promptly instead of waiting for the next chunk.
        isRecording = false;
        AudioRecord record;
        Thread thread;
        synchronized (mAudioLock) {
            record = mAudioRecord;
            thread = mAudioThread;
        }
        if (record != null) {
            // stop() throws IllegalStateException when the AudioRecord was
            // never initialized/started (e.g. capture failed); a teardown path
            // must never crash the service (V6).
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Exception ignored) {
            }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                // Bounded wait: read() unblocks immediately upon record.stop().
                // join() returns at once when the loop already exited.
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (SubtitleAudioLoop.mayReleaseRecord(thread)) {
            // No live loop owns the record (capture never started, failed, or
            // the loop already exited): the stop path releases it. If the loop
            // is STILL alive after the bounded join — a stalled HAL can keep
            // read() parked — it keeps ownership and releases in its own
            // finally; releasing here would pull the record from under an
            // in-flight read and kill the process on a plain thread.
            AudioRecord toRelease = null;
            synchronized (mAudioLock) {
                if (mAudioThread == thread) {
                    mAudioThread = null;
                }
                if (mAudioRecord == record) {
                    mAudioRecord = null;
                    toRelease = record;
                }
            }
            releaseRecord(toRelease);
        }
        if (mMediaProjection != null) {
            try {
                mMediaProjection.stop();
            } catch (Exception ignored) {
            }
            mMediaProjection = null;
        }
        // Tear down the translator and invalidate its in-flight results so no
        // late callback can touch the next session's overlay (P0.2-style).
        mTranslationGeneration++;
        mTranslationQueue.clear();
        mCaptionSegments.clear();
        mTranslationBusy = false;
        mLastPartial = "";
        mLastTranslatedPartial = "";
        mLastPartialSource = "";
        mPendingPartialSource = null;
        mPartialBusy = false;
        mTranslationFallbackToOriginal = false;
        if (mSubtitleContextWindow != null) mSubtitleContextWindow.clearCache();
        if (mDisplayStabilizer != null) {
            mDisplayStabilizer.resetUtterance();
            mDisplayStabilizer.resetWindow();
        }
        mTranslatedWindowStart = 0;
        mTranslationUnavailableNotified = false;
        mSubtitleSourceLanguage = SubtitleTranslationTargets.AUTO;
        mSessionResolvedSourceCode = null;
        if (mTranslator != null) {
            mTranslator.cancelAll();
            mTranslator = null;
        }
        removeOverlay();
        cleanupNative();
    }

    /**
     * Releases an AudioRecord, never throwing: teardown must not crash even if
     * the record was already stopped/released. Shared by the stop path (no
     * live loop) and by the loop's own {@code finally}.
     */
    private static void releaseRecord(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            record.release();
        } catch (Exception ignored) {
        }
    }

    private void setupOverlay() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LayoutInflater inflater = LayoutInflater.from(this);
        // Root = null on purpose (lint's InflateParams suggestion does not apply):
        // the subtitle bar is attached by WindowManager, not by a ViewGroup, and
        // its WindowManager.LayoutParams are constructed in removeOverlay()'s
        // counterpart below.
        mOverlayView = inflater.inflate(R.layout.service_subtitle, null);

        mMaxLines = SubtitlePrefs.getMaxLines(this);
        mSubtitleText = mOverlayView.findViewById(R.id.subs_text);
        mSubtitleText.setText(getString(R.string.subs_waiting_for_audio));

        // New session: reset the transcript window so text from a previous
        // session never leaks into the fresh overlay (P0.2).
        mCommittedText.setLength(0);
        mCommittedBase = 0;
        mWindowStartAbs = 0;
        mDisplayedEndAbs = 0;

        // Translation session init (fork addition). "Auto" keeps the original
        // language and the classic pipeline; an explicit target arms the
        // on-device translator for this session only.
        mTranslationTarget = SubtitlePrefs.getTranslationTarget(this);
        mModelLanguage = MarkerFileHelper.readString(this, "model_language", "auto");
        mSubtitleSourceLanguage = SubtitlePrefs.getSourceLanguage(this);
        mSessionResolvedSourceCode = null;
        mSubtitleContextWindow = new SubtitleContextWindow();
        mDisplayStabilizer = new SubtitleDisplayStabilizer();
        mTranslatedWindowStart = 0;
        mLastTranslatedPartial = "";
        mLastPartialSource = "";
        mLastPartialTranslateTimeMs = 0L;
        mPendingPartialSource = null;
        mPartialBusy = false;
        mTranslationFallbackToOriginal = false;
        mTranslationMode = !SubtitleTranslationTargets.AUTO.equals(mTranslationTarget);
        mTranslator = mTranslationMode ? new OnDeviceSubtitleTranslator() : null;
        mTranslationGeneration++;
        mTranslationQueue.clear();
        mCaptionSegments.clear();
        mTranslationBusy = false;
        mLastPartial = "";
        mTranslationUnavailableNotified = false;
        
        View closeBtn = mOverlayView.findViewById(R.id.btn_close_subs);
        closeBtn.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, LiveSubtitleService.class);
            stopIntent.setAction(ACTION_STOP);
            startService(stopIntent);
        });
        
        int layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM;
        params.y = SubtitlePrefs.getOverlayY(this);

        mWindowManager.addView(mOverlayView, params);
        makeOverlayDraggable(params);
    }

    /**
     * Lets the user drag the overlay vertically; the position is persisted
     * and restored on the next session. The listener sits on the overlay
     * root, so the close button (which consumes its own touches) keeps
     * working. With {@link Gravity#BOTTOM}, {@code params.y} is the offset
     * above the bottom edge, so it grows as the finger moves up.
     */
    private void makeOverlayDraggable(WindowManager.LayoutParams params) {
        mOverlayView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int downParamsY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        downParamsY = params.y;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int maxY = getResources().getDisplayMetrics().heightPixels
                                - mOverlayView.getHeight();
                        params.y = Math.max(0, Math.min(maxY,
                                downParamsY + (int) (downRawY - event.getRawY())));
                        if (mOverlayView != null) {
                            mWindowManager.updateViewLayout(mOverlayView, params);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        // Ends a drag, but is also a plain tap on the overlay.
                        // Surface it as a click so accessibility services get
                        // the event a standard click would have produced
                        // (ClickableViewAccessibility).
                        v.performClick();
                        SubtitlePrefs.setOverlayY(LiveSubtitleService.this, params.y);
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        SubtitlePrefs.setOverlayY(LiveSubtitleService.this, params.y);
                        return true;
                }
                return false;
            }
        });
    }

    private void removeOverlay() {
        if (mOverlayView != null && mWindowManager != null) {
            mWindowManager.removeView(mOverlayView);
            mOverlayView = null;
        }
        // Drop the caption view reference too: any late native callback (an
        // old session's worker that raced cleanup) finds mSubtitleText null
        // and exits instead of updating a removed overlay (P0.2).
        mSubtitleText = null;
    }

    /**
     * Captures system audio via MediaProjection (Android 10 / API 29+ only).
     *
     * Suppressed MissingPermission: this path does NOT require RECORD_AUDIO —
     * system-audio playback capture is gated by the MediaProjection consent
     * the user granted in LiveSubtitleActivity, not by the mic permission. The
     * AudioRecord build/start below is additionally wrapped in try/catch, so a
     * ROM that enforces RECORD_AUDIO anyway surfaces as a logged error instead
     * of a crash.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private void startAudioCapture() {
        Log.d(TAG, "Starting audio capture. MediaProjection: " + mMediaProjection);
        if (mMediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, cannot capture audio");
            return;
        }

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufferSize, 16000); // 1 second buffer roughly

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        try {
            final AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            // Publish the record under the ownership lock before any failure
            // path hands it to the teardown helpers.
            synchronized (mAudioLock) {
                mAudioRecord = record;
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSubtitleSession();
                return;
            }

            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to start recording. State: " + record.getRecordingState());
                stopSubtitleSession();
                return;
            }

            Log.d(TAG, "AudioRecord started successfully");

            final Thread audioThread = new Thread(this::audioLoop);
            synchronized (mAudioLock) {
                // Published under the same lock the teardown and the loop's
                // finally use: ownership transitions can never be lost, even
                // if the loop exits immediately after start().
                mAudioThread = audioThread;
                isRecording = true;
            }
            audioThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Error starting AudioRecord", e);
            stopSubtitleSession();
        }
    }

    private void audioLoop() {
        Log.d(TAG, "Starting audio loop");
        final int bufferSize = 1024; // Process in small chunks
        final short[] buffer = new short[bufferSize];
        final float[] floatBuffer = new float[bufferSize];
        int totalRead = 0;

        // Capture the record this loop owns once: the teardown path never
        // releases it while this thread may still be inside read(), but the
        // field itself can be cleared at any time, so never re-read it here.
        final AudioRecord record;
        synchronized (mAudioLock) {
            record = mAudioRecord;
        }
        if (record == null) {
            Log.e(TAG, "Audio loop started without an AudioRecord");
            return;
        }

        try {
            while (isRecording) {
                final int read;
                try {
                    read = record.read(buffer, 0, bufferSize);
                } catch (Throwable t) {
                    // A teardown race (stop/release from another thread)
                    // surfaces as an IllegalStateException/NPE here and ends
                    // the loop; it must never kill the process with an
                    // uncaught exception on this plain thread.
                    Log.w(TAG, "Audio read stopped", t);
                    break;
                }
                if (read > 0) {
                    totalRead += read;
                    if (BuildConfig.DEBUG && totalRead % (16000 * 5) < read) { // Log approx every 5 seconds of audio
                        Log.d(TAG, "Reading audio... Total samples: " + totalRead);
                    }

                    SubtitleAudioLoop.toNormalizedFloats(buffer, read, floatBuffer);
                    pushAudio(floatBuffer, read);
                } else if (read < 0) {
                    Log.e(TAG, "Audio read error: " + read);
                    break;
                }
            }
        } catch (Throwable t) {
            // pushAudio (JNI) or any other failure: the loop thread must not
            // die with an uncaught throwable while the app keeps running.
            Log.w(TAG, "Audio loop stopped", t);
        } finally {
            // The loop owns its record's release: this is what makes teardown
            // race-free, because no read() can be in flight here. Clear only
            // state that still points at THIS loop, so a stale loop exiting
            // late can never clobber a newer session's record or thread.
            releaseRecord(record);
            synchronized (mAudioLock) {
                if (mAudioRecord == record) {
                    mAudioRecord = null;
                }
                if (mAudioThread == Thread.currentThread()) {
                    mAudioThread = null;
                    isRecording = false;
                }
            }
            Log.d(TAG, "Audio loop finished");
        }
    }

    // Transcript state: finalized text plus the partial hypothesis for the
    // segment currently being spoken. Partials replace each other; finals are
    // appended once.
    private final StringBuilder mCommittedText = new StringBuilder();
    private static final int MAX_COMMITTED_CHARS = 600;
    // Absolute offset of mCommittedText[0] within the whole session transcript
    // (grows when the buffer is trimmed at the front).
    private long mCommittedBase = 0;
    // Absolute offset where the caption window starts. Only ever moves
    // forward — this is what keeps old sentences from sliding back into view
    // when a hypothesis is revised or a segment finalizes. Positions inside
    // the partial region drift by at most a few characters when a hypothesis
    // is revised, which is invisible compared to a window jump.
    private long mWindowStartAbs = 0;
    // Line limit for the overlay (0 = unlimited), from SubtitlePrefs.
    private int mMaxLines = SubtitlePrefs.DEFAULT_MAX_LINES;
    // Absolute offset of the end of the currently displayed transcript.
    private long mDisplayedEndAbs = 0;

    // --- Live-subtitle translation (fork addition) -------------------------
    // When the user picks an explicit translation target, finalized segments
    // are shown immediately in the original language and re-rendered in the
    // target language as the ordered on-device translations arrive. All state
    // below is touched only on the main thread (segment/job bookkeeping inside
    // the onSubtitleText runnable, translation completions via the same
    // handler), so no locks are needed.
    private boolean mTranslationMode = false;
    private String mTranslationTarget = SubtitleTranslationTargets.AUTO;
    private String mModelLanguage = "auto";
    private String mSubtitleSourceLanguage = SubtitleTranslationTargets.AUTO;
    private String mSessionResolvedSourceCode = null;
    private SubtitleTranslator mTranslator;
    private SubtitleContextWindow mSubtitleContextWindow;
    private SubtitleDisplayStabilizer mDisplayStabilizer;
    // Monotonic start offset (in chars) for the translated caption window:
    // once text scrolls off the top it never comes back.
    private int mTranslatedWindowStart = 0;
    // Progressive partial-translation state (M4).
    private String mLastTranslatedPartial = "";
    private String mLastPartialSource = "";
    private long mLastPartialTranslateTimeMs = 0L;
    private String mPendingPartialSource = null;
    private boolean mPartialBusy = false;
    private boolean mTranslationFallbackToOriginal = false;
    private static final long PARTIAL_TRANSLATE_DEBOUNCE_MS = 380L;
    private final ArrayDeque<CaptionSegment> mCaptionSegments = new ArrayDeque<>();
    private final ArrayDeque<TranslationJob> mTranslationQueue = new ArrayDeque<>();
    private boolean mTranslationBusy = false;
    /** Bumped on every session start/stop; late callbacks with a stale
     *  generation are dropped (same idea as the Rust worker's generation). */
    private int mTranslationGeneration = 0;
    private String mLastPartial = "";
    /** One-time user notice when the translator falls back to the original
     *  language (no Play Services / missing pack / engine error). */
    private boolean mTranslationUnavailableNotified = false;
    private static final int MAX_CAPTION_SEGMENTS = 24;
    /** Backlog guard: beyond this, new finals skip translation and stay in
     *  the original language instead of building an unbounded queue. */
    private static final int MAX_TRANSLATION_QUEUE = 8;

    /** One caption line: finalized (translated or fallback) or still pending. */
    private static final class CaptionSegment {
        String shown;
        boolean finalText;
        CaptionSegment(String shown) {
            this.shown = shown;
        }
    }

    /** One queued translation request; carries the session generation. */
    private static final class TranslationJob {
        final String text;
        final int generation;
        TranslationJob(String text, int generation) {
            this.text = text;
            this.generation = generation;
        }
    }

    // Called from Rust (worker thread). isFinal=true commits the text;
    // isFinal=false is a partial hypothesis that replaces the previous one.
    public void onSubtitleText(String text, boolean isFinal) {
        mMainHandler.post(() -> {
            if (mSubtitleText == null) return;
            if (isFinal) {
                if (text.isEmpty()) return; // nothing new — leave display alone
                if (mTranslationMode) {
                    mLastPartial = "";
                    onFinalTranslated(text);
                } else {
                    if (mCommittedText.length() > 0) mCommittedText.append(' ');
                    mCommittedText.append(text);
                    int excess = mCommittedText.length() - MAX_COMMITTED_CHARS;
                    if (excess > 0) {
                        mCommittedText.delete(0, excess);
                        mCommittedBase += excess;
                    }
                    updateDisplay("");
                }
            } else {
                if (mTranslationMode) {
                    mLastPartial = text;
                    onPartialTranslated(text);
                    renderTranslatedCaption("");
                } else {
                    updateDisplay(text);
                }
            }
        });
    }

    // --- Translation-mode caption pipeline (fork addition) -----------------

    /** A finalized segment arrives in translation mode. Source text is never
     *  shown when ML Kit is available; the translated text (or, on failure,
     *  the original) is committed once the translation lands. */
    private void onFinalTranslated(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (mTranslationFallbackToOriginal) {
            addFinalSegment(text, true);
            if (mDisplayStabilizer != null) mDisplayStabilizer.resetUtterance();
            resolveSource(text);
            mSubtitleContextWindow.recordFinal(text, text,
                    mSessionResolvedSourceCode, System.currentTimeMillis());
            renderTranslatedCaption("");
            return;
        }
        if (isPunctuationOnly(text) || mTranslationQueue.size() >= MAX_TRANSLATION_QUEUE) {
            addFinalSegment(text, true);
            if (mDisplayStabilizer != null) mDisplayStabilizer.resetUtterance();
            resolveSource(text);
            mSubtitleContextWindow.recordFinal(text, text,
                    mSessionResolvedSourceCode, System.currentTimeMillis());
            renderTranslatedCaption("");
            return;
        }
        long now = System.currentTimeMillis();
        resolveSource(text);
        String sourceCode = mSessionResolvedSourceCode;
        String targetCode = SubtitleTranslationTargets.mlKitCode(mTranslationTarget);
        // Cache key must match the payload buildPayload() would send: an
        // expired context must not be part of the key (M6.1).
        String contextPrefix = mSubtitleContextWindow.effectiveContextPrefix(sourceCode, now);
        String cached = mSubtitleContextWindow.getCached(sourceCode, targetCode,
                contextPrefix, text);
        // Only promote synchronously when no earlier final is waiting or in
        // flight: otherwise segment B would jump ahead of segment A (M7.2).
        if (mTranslationQueue.isEmpty() && !mTranslationBusy
                && (cached != null || (mLastPartialSource.equals(text)
                        && !mLastTranslatedPartial.isEmpty()))) {
            String translated = (cached != null) ? cached : mLastTranslatedPartial;
            mLastTranslatedPartial = "";
            mPendingPartialSource = null;
            mSubtitleContextWindow.recordFinal(text, translated, sourceCode, now);
            addFinalSegment(translated, true);
            if (mDisplayStabilizer != null) mDisplayStabilizer.resetUtterance();
            renderTranslatedCaption("");
            return;
        }
        // Keep the last translated partial visible until the final lands (no
        // blank flicker); do not leak the untranslated source.
        mLastPartial = "";
        mPendingPartialSource = null;
        enqueueTranslation(text);
        renderTranslatedCaption("");
    }

    /** Partial hypothesis in translation mode: debounce + growth + latest-wins. */
    private void onPartialTranslated(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (mTranslationFallbackToOriginal) {
            mLastTranslatedPartial = text;
            return;
        }
        if (!hasMeaningfulGrowth(text, mLastPartialSource)) {
            return;
        }
        long now = System.currentTimeMillis();
        resolveSource(text);
        String sourceCode = mSessionResolvedSourceCode;
        String targetCode = SubtitleTranslationTargets.mlKitCode(mTranslationTarget);
        String contextPrefix = mSubtitleContextWindow.effectiveContextPrefix(sourceCode, now);
        String cached = mSubtitleContextWindow.getCached(sourceCode, targetCode,
                contextPrefix, text);
        if (cached != null) {
            // Cached partials go through the stabilizer too, exactly like a
            // freshly translated hypothesis (M7.2).
            mLastTranslatedPartial = (mDisplayStabilizer != null)
                    ? mDisplayStabilizer.stabilize(cached)
                    : cached;
            mLastPartialSource = text;
            return;
        }
        if (mPartialBusy) {
            mPendingPartialSource = text; // latest-wins, never queue
            return;
        }
        if (now - mLastPartialTranslateTimeMs < PARTIAL_TRANSLATE_DEBOUNCE_MS) {
            mPendingPartialSource = text;
            final String pending = text;
            final int gen = mTranslationGeneration;
            mMainHandler.postDelayed(() -> {
                if (gen != mTranslationGeneration || mSubtitleText == null) return;
                if (mPendingPartialSource == pending) {
                    mPendingPartialSource = null;
                    onPartialTranslated(pending);
                    // A cache hit inside onPartialTranslated does not repaint
                    // on its own: force it here (M7.2).
                    renderTranslatedCaption("");
                }
            }, PARTIAL_TRANSLATE_DEBOUNCE_MS - (now - mLastPartialTranslateTimeMs));
            return;
        }
        triggerPartialTranslation(text, sourceCode, targetCode, contextPrefix, now);
    }

    private void triggerPartialTranslation(String text, String sourceCode,
                                           String targetCode, String contextPrefix, long now) {
        mPartialBusy = true;
        mLastPartialSource = text;
        mLastPartialTranslateTimeMs = now;
        mPendingPartialSource = null;
        final int gen = mTranslationGeneration;
        // Translate the contextual payload (previous sentence + current one),
        // exactly the text the cache key above describes (M6.1).
        final String payload = (mSubtitleContextWindow != null)
                ? mSubtitleContextWindow.buildPayload(text, sourceCode, now)
                : text;
        mTranslator.translate(payload, sourceCode, targetCode, new SubtitleTranslator.Callback() {
            @Override
            public void onSuccess(String translated) {
                mMainHandler.post(() -> {
                    if (gen != mTranslationGeneration) return;
                    mPartialBusy = false;
                    String extracted = (mSubtitleContextWindow != null)
                            ? mSubtitleContextWindow.extractTranslated(translated)
                            : (translated == null ? "" : translated.trim());
                    if (mSubtitleContextWindow != null) {
                        mSubtitleContextWindow.putCached(sourceCode, targetCode,
                                contextPrefix, text, extracted);
                    }
                    if (mDisplayStabilizer != null) {
                        mLastTranslatedPartial = mDisplayStabilizer.stabilize(extracted);
                    } else {
                        mLastTranslatedPartial = extracted;
                    }
                    renderTranslatedCaption("");
                    String pending = mPendingPartialSource;
                    mPendingPartialSource = null;
                    if (pending != null) onPartialTranslated(pending);
                });
            }

            @Override
            public void onFailure(Throwable error) {
                mMainHandler.post(() -> {
                    if (gen != mTranslationGeneration) return;
                    mPartialBusy = false;
                    mLastTranslatedPartial = text; // safe fallback
                    renderTranslatedCaption("");
                    String pending = mPendingPartialSource;
                    mPendingPartialSource = null;
                    if (pending != null) onPartialTranslated(pending);
                });
            }

            @Override
            public void onUnavailable() {
                mMainHandler.post(() -> {
                    if (mTranslationUnavailableNotified) return;
                    mTranslationUnavailableNotified = true;
                    mTranslationFallbackToOriginal = true;
                    Toast.makeText(LiveSubtitleService.this,
                            R.string.subs_translation_unavailable, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resolveSource(String text) {
        String sc = SourceLanguageResolver.resolve(text, mSubtitleSourceLanguage,
                mModelLanguage, mSessionResolvedSourceCode);
        if (sc != null) mSessionResolvedSourceCode = sc;
    }

    private void addFinalSegment(String shown, boolean finalText) {
        CaptionSegment segment = new CaptionSegment(shown);
        segment.finalText = finalText;
        mCaptionSegments.addLast(segment);
        trimCaptionSegments();
    }

    private static boolean hasMeaningfulGrowth(String current, String previous) {
        if (previous == null || previous.trim().isEmpty()) return true;
        if (current == null || current.trim().isEmpty()) return false;
        if (endsWithPunctuation(current) && !endsWithPunctuation(previous)) return true;
        int cjkGrowth = countCjk(current) - countCjk(previous);
        if (cjkGrowth >= 2) return true;
        int prevWords = previous.trim().split("\\s+").length;
        int curWords = current.trim().split("\\s+").length;
        if (curWords > prevWords) return true;
        return false;
    }

    private static int countCjk(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) n++;
            else if (c >= 0x3040 && c <= 0x30FF) n++;
            else if (c >= 0xAC00 && c <= 0xD7AF) n++;
        }
        return n;
    }

    private static boolean endsWithPunctuation(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.trim().charAt(s.trim().length() - 1);
        return c == ',' || c == '.' || c == '!' || c == '?' || c == ';'
                || c == '，' || c == '。' || c == '！' || c == '？'
                || c == '、' || c == '；' || c == '：';
    }

    private void enqueueTranslation(String text) {
        mTranslationQueue.addLast(new TranslationJob(text, mTranslationGeneration));
        pumpTranslationQueue();
    }

    /** Starts the next queued translation; strictly serial (FIFO), so results
     *  are applied in order and a slow pair never reorders the captions. */
    private void pumpTranslationQueue() {
        if (mTranslationBusy || mTranslationQueue.isEmpty() || mTranslator == null) return;
        mTranslationBusy = true;
        final TranslationJob job = mTranslationQueue.peek();
        final String sourceCode = SourceLanguageResolver.resolve(
                job.text, mSubtitleSourceLanguage, mModelLanguage, mSessionResolvedSourceCode);
        if (sourceCode != null) {
            mSessionResolvedSourceCode = sourceCode;
        }
        final String targetCode = SubtitleTranslationTargets.mlKitCode(mTranslationTarget);
        final long now = System.currentTimeMillis();
        final String contextPrefix = (mSubtitleContextWindow != null)
                ? mSubtitleContextWindow.effectiveContextPrefix(sourceCode, now) : "";
        // If this final already landed in the cache (e.g. promoted from the
        // partial hypothesis), apply it on the main thread without calling
        // ML Kit again — FIFO order is preserved (M7.2).
        final String cached = (mSubtitleContextWindow != null)
                ? mSubtitleContextWindow.getCached(sourceCode, targetCode, contextPrefix, job.text)
                : null;
        if (cached != null) {
            mMainHandler.post(() -> applyTranslation(job, cached));
            return;
        }
        final String payload = (mSubtitleContextWindow != null)
                ? mSubtitleContextWindow.buildPayload(job.text, sourceCode, now)
                : job.text;
        mTranslator.translate(payload, sourceCode, targetCode, new SubtitleTranslator.Callback() {
            @Override
            public void onSuccess(String translated) {
                String extracted = (mSubtitleContextWindow != null)
                        ? mSubtitleContextWindow.extractTranslated(translated)
                        : (translated == null ? "" : translated.trim());
                if (mSubtitleContextWindow != null) {
                    mSubtitleContextWindow.putCached(sourceCode, targetCode,
                            contextPrefix, job.text, extracted);
                }
                mMainHandler.post(() -> applyTranslation(job, extracted));
            }

            @Override
            public void onFailure(Throwable error) {
                // Safe fallback: the original text is always shown.
                mMainHandler.post(() -> applyTranslation(job, job.text));
            }

            @Override
            public void onUnavailable() {
                // Tell the user once per session that translation fell back to
                // the original language (no Play Services, missing pack, …).
                mMainHandler.post(() -> {
                    // Same degradation as the partial path: never keep the
                    // source text hidden once ML Kit is gone (M6.1).
                    mTranslationFallbackToOriginal = true;
                    if (mTranslationUnavailableNotified) return;
                    mTranslationUnavailableNotified = true;
                    Toast.makeText(LiveSubtitleService.this,
                            R.string.subs_translation_unavailable, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Applies a translation (or its fallback) to the matching pending segment
     *  and keeps the queue moving. Runs on the main thread. */
    private void applyTranslation(TranslationJob job, String translated) {
        mTranslationBusy = false;
        mTranslationQueue.remove(job);
        if (job.generation != mTranslationGeneration) {
            pumpTranslationQueue();
            return; // stale session
        }
        resolveSource(job.text);
        if (mSubtitleContextWindow != null) {
            mSubtitleContextWindow.recordFinal(job.text, translated,
                    mSessionResolvedSourceCode, System.currentTimeMillis());
        }
        mLastTranslatedPartial = "";
        mPendingPartialSource = null;
        addFinalSegment(translated, true);
        if (mDisplayStabilizer != null) mDisplayStabilizer.resetUtterance();
        renderTranslatedCaption("");
        pumpTranslationQueue();
    }

    /** Renders the segment list plus the live partial through the same
     *  last-N-lines window the classic path uses. Pending segments keep their
     *  original text until their translation lands, so captions never pause.
     *
     *  <p>Monotonic: once a character offset has scrolled off the top it never
     *  comes back, even if a later partial becomes shorter. The active partial
     *  is rendered at 75 % alpha via {@link SubtitleDisplayStabilizer#withPartialAlpha}.
     */
    private void renderTranslatedCaption(String partial) {
        if (mSubtitleText == null) return;
        StringBuilder full = new StringBuilder();
        for (CaptionSegment s : mCaptionSegments) {
            full.append(s.shown);
            full.append(' ');
        }
        String tail = partial.isEmpty() ? mLastTranslatedPartial : partial;
        boolean hasTail = !tail.isEmpty();
        int tailStart = full.length();
        if (hasTail) full.append(tail);
        String text = full.toString();
        if (text.isEmpty()) {
            mSubtitleText.setText("");
            return;
        }
        // Monotonic window start (never moves backward).
        if (mTranslatedWindowStart > text.length()) mTranslatedWindowStart = 0;
        // Cosmetic: never start the window on the separator space.
        while (mTranslatedWindowStart < text.length() && text.charAt(mTranslatedWindowStart) == ' ') {
            mTranslatedWindowStart++;
        }
        String visible = text.substring(Math.min(mTranslatedWindowStart, text.length()));
        int tailVisibleStart = tailStart - mTranslatedWindowStart;
        if (tailVisibleStart < 0) tailVisibleStart = -1; // tail scrolled off

        if (mMaxLines > 0) {
            int width = mSubtitleText.getWidth()
                    - mSubtitleText.getPaddingLeft() - mSubtitleText.getPaddingRight();
            if (width > 0 && !visible.isEmpty()) {
                StaticLayout layout = StaticLayout.Builder
                        .obtain(visible, 0, visible.length(), mSubtitleText.getPaint(), width)
                        .build();
                int lineCount = layout.getLineCount();
                if (lineCount > mMaxLines) {
                    int cut = layout.getLineStart(lineCount - mMaxLines);
                    if (cut > 0) {
                        mTranslatedWindowStart += cut;
                        if (mTranslatedWindowStart > text.length()) mTranslatedWindowStart = text.length();
                        visible = text.substring(mTranslatedWindowStart);
                        tailVisibleStart = tailStart - mTranslatedWindowStart;
                        if (tailVisibleStart < 0) tailVisibleStart = -1;
                    }
                }
            }
        }

        if (!hasTail || tailVisibleStart < 0 || tailVisibleStart >= visible.length()) {
            mSubtitleText.setText(visible);
            return;
        }
        // Split the visible text into consolidated (100 %) and partial (75 %).
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(visible);
        int tailLen = Math.min(tail.length(), visible.length() - tailVisibleStart);
        if (tailLen > 0) {
            int baseColor = mSubtitleText.getCurrentTextColor();
            int partialColor = SubtitleDisplayStabilizer.withPartialAlpha(baseColor);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(partialColor),
                    tailVisibleStart, tailVisibleStart + tailLen,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mSubtitleText.setText(ssb);
    }

    /** Caps the stored segment list, never dropping a pending translation. */
    private void trimCaptionSegments() {
        while (mCaptionSegments.size() > MAX_CAPTION_SEGMENTS) {
            CaptionSegment first = mCaptionSegments.peekFirst();
            if (first != null && !first.finalText) break;
            CaptionSegment removed = mCaptionSegments.removeFirst();
            if (removed != null && removed.shown != null) {
                // Every dropped segment shifts the joined buffer left by its
                // length plus the separator space; keep the monotonic window
                // aligned with the text that is actually rendered (M6.2).
                int dropped = removed.shown.length() + 1;
                mTranslatedWindowStart = Math.max(0, mTranslatedWindowStart - dropped);
            }
        }
    }

    /** True when a finalized segment carries no translatable content (gap
     *  markers like "…" must not go through the translator). */
    private static boolean isPunctuationOnly(String text) {
        String t = text.trim();
        if (t.isEmpty()) return true;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetterOrDigit(t.charAt(i))) return false;
        }
        return true;
    }

    /** Renders committed text + partial through the forward-only caption window. */
    private void updateDisplay(String partial) {
        String full = partial.isEmpty()
                ? mCommittedText.toString()
                : (mCommittedText.length() == 0 ? partial : mCommittedText + " " + partial);

        // A final can end before the partial that was just on screen (the
        // forced-cut remainder belongs to the next segment, and the final
        // transcription may stop earlier than the last hypothesis). Redrawing
        // then would make already-read words vanish and "pop up" again with
        // the next partial — so only redraw once the transcript reaches at
        // least as far as what is currently displayed.
        long fullEndAbs = mCommittedBase + full.length();
        if (fullEndAbs < mDisplayedEndAbs) return;
        mDisplayedEndAbs = fullEndAbs;

        if (mMaxLines <= 0) {
            mSubtitleText.setText(full);
            return;
        }

        int rel = (int) Math.max(0, Math.min(mWindowStartAbs - mCommittedBase, full.length()));
        // Cosmetic: never start the window on the separator space.
        while (rel < full.length() && full.charAt(rel) == ' ') rel++;
        String visible = full.substring(rel);

        int width = mSubtitleText.getWidth()
                - mSubtitleText.getPaddingLeft() - mSubtitleText.getPaddingRight();
        if (width > 0 && !visible.isEmpty()) {
            StaticLayout layout = StaticLayout.Builder
                    .obtain(visible, 0, visible.length(), mSubtitleText.getPaint(), width)
                    .build();
            int lineCount = layout.getLineCount();
            if (lineCount > mMaxLines) {
                int cut = layout.getLineStart(lineCount - mMaxLines);
                visible = visible.substring(cut);
                mWindowStartAbs = mCommittedBase + rel + cut;
            }
        }
        mSubtitleText.setText(visible);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.section_subs), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        createNotificationChannel();

        Intent stopIntent = new Intent(this, LiveSubtitleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.subs_notification_active))
                .setContentText(getString(R.string.subs_notification_recording))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.subs_notification_stop), stopPendingIntent).build())
                .build();
    }

    @Override
    public void onDestroy() {
        // Make sure the overlay and capture are torn down even if the service
        // is killed without an explicit ACTION_STOP.
        stopSubtitleSession();
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timeout triggered (type=" + fgsType + ", startId=" + startId + ")");
        stopSubtitleSession();
        stopSelf(startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Native methods: JNI bridge into the Rust subtitle pipeline (see AGENTS.md S4.3)
    private native void initNative(LiveSubtitleService service);
    private native void cleanupNative();
    private native void pushAudio(float[] data, int length);
}
