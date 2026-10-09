package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import dev.notune.transcribe.BuildConfig;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

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

    private TextView status;
    private boolean isRecording = false;
    private volatile int currentSessionId = 0;
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

        View rootView = findViewById(R.id.root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        // Keep the screen awake for the lifetime of this recording screen so it
        // never sleeps mid-capture and cuts the recording short.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micLevel = findViewById(R.id.mic_level);
        status = findViewById(R.id.txt_status);

        ImageView micIcon = findViewById(R.id.mic_icon);
        boolean isBt = AudioDeviceManager.shouldShowHeadsetIcon(this);
        if (micIcon != null) {
            micIcon.setImageResource(isBt ? R.drawable.ic_headset : R.drawable.ic_mic);
        }

        findViewById(R.id.btn_close).setOnClickListener(v -> cancelAndClose());

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> finishRecording());

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(getString(R.string.rec_mic_permission_required));
            return;
        }

        initNative(this);
        isRecording = true;
        status.setText(getString(R.string.rec_listening_tap_stop));
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        SettingsManager sm = new SettingsManager(this);
        AudioDeviceManager.acquireMicrophone(this, sm.getMicMode());
        startRecording(isAutoStopEnabled(), ++currentSessionId);
    }

    /** Discards recording, transcription, or post-processing without delivering text. */
    private void cancelAndClose() {
        isRecording = false;
        currentSessionId++;
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        PostProcessor.cancelAllFor(this);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        status.setText(getString(R.string.rec_processing));
        AudioDeviceManager.releaseMicrophone(this);
        stopRecording();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop(int sessionId) {
        if (sessionId != currentSessionId) return;
        runOnUiThread(() -> {
            if (sessionId != currentSessionId || isFinishing() || isDestroyed()) return;
            finishRecording();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The popup is no longer visible (user switched apps or went home).
        // It runs in its own task (singleTask), so it would otherwise keep
        // recording invisibly in the background and never reappear. Discard
        // and close so the next mic tap starts fresh. (Background recording
        // is a keyboard-only feature; a popup must not record unseen.)
        if (isRecording && !isFinishing()) {
            isRecording = false;
            currentSessionId++;
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
            AudioDeviceManager.releaseMicrophone(this);
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        currentSessionId++;
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        super.onDestroy();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
        // Cancel this popup's in-flight post-processing call when it is
        // destroyed — never another surface's (P0.1).
        PostProcessor.cancelAllFor(this);
    }

    // Called from Rust for recording-scoped status updates.
    public void onStatusUpdate(String s, int sessionId) {
        if (sessionId != currentSessionId) return;
        runOnUiThread(() -> {
            if (sessionId != currentSessionId || isFinishing() || isDestroyed()) return;
            showStatus(s);
        });
    }

    // Called from Rust with model lifecycle updates (not tied to a recording).
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            showStatus(s);
        });
    }

    private void showStatus(String s) {
        if (s != null && s.startsWith("Error")) {
            if (isRecording) {
                isRecording = false;
                AudioDeviceManager.releaseMicrophone(this);
            }
        }
        final String shown;
        if ("Ready".equals(s)) {
            shown = isRecording ? getString(R.string.rec_listening_tap_stop)
                    : getString(R.string.status_ready);
        } else if ("Listening...".equals(s)) {
            shown = getString(R.string.rec_listening_tap_stop);
        } else {
            shown = s;
        }
        status.setText(shown);
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level, int sessionId) {
        if (sessionId != currentSessionId) return;
        runOnUiThread(() -> {
            if (sessionId != currentSessionId || isFinishing() || isDestroyed()) return;
            if (micLevel != null) {
                micLevel.setLevel(level);
            }
        });
    }

    public void onAudioLevel(float level) {
        onAudioLevel(level, currentSessionId);
    }

    // Called from Rust with live partial hypotheses while recording
    // (streaming models). Visual-only: the final text replaces it via
    // onTextTranscribed.
    public void onPartialText(String text, int sessionId) {
        if (sessionId != currentSessionId) return;
        runOnUiThread(() -> {
            if (sessionId != currentSessionId || isFinishing() || isDestroyed()) return;
            if (isRecording && text != null && !text.trim().isEmpty()) {
                status.setText(text);
            }
        });
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text, int sessionId) {
        if (sessionId != currentSessionId) return;
        runOnUiThread(() -> {
            if (sessionId != currentSessionId || isFinishing() || isDestroyed()) return;
            if (text == null || text.trim().isEmpty()) {
                // Nothing was recognized (e.g. auto-stop after silence only).
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            SettingsManager settings = new SettingsManager(this);
            // Cheap switch check (no credential I/O on this thread). PostProcessor
            // decides with a read queued on the credential lane, behind any pending
            // legacy import, and delivers the raw transcript when there is none.
            if (settings.isPostProcessSwitchedOn()) {
                status.setText(getString(R.string.rec_refining));
                // Privacy (v0.1.24): never log the transcript or the provider
                // endpoint in release builds. Debug-only diagnostics keep the
                // ability to trace PP issues on dev builds without leaking
                // user speech into production logcat.
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "Post-processing enabled, sending final transcript ("
                            + text.length() + " chars) to " + settings.getEffectiveApiUrl());
                    Log.i(TAG, "PP-RAW: " + text);
                }

                // The ASR partials remain the live preview. Wait for one
                // complete post-processing response before returning the result.
                // Owned by this Activity so destroying the popup only cancels
                // its own call, never another surface's (P0.1).
                new PostProcessor(settings, new Handler(Looper.getMainLooper()),
                        () -> sessionId == currentSessionId
                                && !isFinishing() && !isDestroyed(), this).process(text,
                        new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        if (sessionId != currentSessionId) return;
                        String out = (refinedText != null && !refinedText.trim().isEmpty())
                                ? refinedText : text;
                        deliverResult(out);
                    }

                    @Override
                    public void onError(String error) {
                        if (sessionId != currentSessionId) return;
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "Post-processing failed, delivering raw text: " + error);
                        }
                        deliverResult(text);
                    }
                });
            } else {
                deliverResult(text);
            }
        });
    }

    private void deliverResult(String text) {
        if (text == null || text.trim().isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        ArrayList<String> results = new ArrayList<>();
        results.add(text);

        Intent data = new Intent();
        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);

        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();
}
