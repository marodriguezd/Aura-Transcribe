package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

import dev.notune.transcribe.BuildConfig;

public class RustInputMethodService extends InputMethodService {
    
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

    private TextView statusView;
    private TextView hintView;
    // Live window for streaming partial hypotheses (Nemotron etc.), up to
    // ~three lines.
    private ScrollView partialScroll;
    private TextView partialTextView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private Button cancelButton;
    private com.google.android.material.materialswitch.MaterialSwitch ppToggle;
    private String lastRawTranscript = null;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    // True from the moment the mic is released (manual stop or auto-stop)
    // until the final text is committed or the session is cancelled/errors
    // out. Keeps the Cancel button visible continuously across the stop →
    // transcribe → refine window so it never blinks when the "Ready" status
    // lands between ASR and post-processing.
    private boolean resultPending = false;
    private int currentSessionId = 0;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    // Set to true in onDestroy() so late post-processing callbacks can drop
    // their work instead of touching detached/destroyed views.
    private boolean isDestroyed = false;
    // Receives cross-process cancellation signals from the main process when
    // the user toggles post-processing off in Settings, so this IME process
    // can cancel its own in-flight OkHttp calls without waiting for them to
    // time out.
    private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received cross-process cancel, aborting in-flight PP calls");
            PostProcessor.cancelAll();
            // Reset the IME UI if it was stuck showing a post-processing state
            // ("Refining...", "Processing...") because the user disabled PP
            // while a transcription was being refined. Without this, the IME
            // stays in a blocked state until the cancelled HTTP call's error
            // callback fires, which can take up to the OkHttp timeout.
            mainHandler.post(() -> {
                if (!isRecording && statusView != null) {
                    String current = statusView.getText().toString();
                    if (getString(R.string.ime_refining).equals(current)
                            || getString(R.string.ime_processing).equals(current)) {
                        statusView.setText(getString(R.string.ime_tap_to_record));
                        if (hintView != null) hintView.setText(getString(R.string.ime_tap_to_record));
                        if (recordContainer != null) {
                            recordContainer.setEnabled(true);
                            recordContainer.setAlpha(1.0f);
                        }
                        // PP was turned off: the pending refine is gone, so
                        // drop the pending flag and hide Cancel immediately
                        // instead of waiting for the cancelled HTTP call's
                        // error callback to reset the UI.
                        resultPending = false;
                        if (cancelButton != null) cancelButton.setVisibility(View.GONE);
                    }
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
        // Sync Android system user dictionary words (FUTO Keyboard style)
        // Listen for cross-process post-processing cancellation from the main
        // process so we can abort in-flight OkHttp calls immediately.
        // RECEIVER_NOT_EXPORTED: only the main process (same app uid) sends
        // CANCEL_PP; other apps must not be able to trigger a PP cancel.
        // ContextCompat picks the flags-aware API on 33+ and the plain
        // registerReceiver below it, silencing UnspecifiedRegisterReceiverFlag.
        IntentFilter cancelFilter = new IntentFilter(PostProcessor.CANCEL_ACTION);
        ContextCompat.registerReceiver(this, cancelReceiver, cancelFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service (same process as the app — the
            // manifest declares no android:process; see the process-model note in
            // App.java), so AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference), wears the Material 3 theme,
            // and picks up Material You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            // Root = null on purpose: onCreateInputView() has no parent to attach
            // to — the IME framework attaches whatever it returns to the input
            // window. This is the documented pattern for an input view, so lint's
            // InflateParams suggestion does not apply here.
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Keep the key row clear of the navigation bar / gesture pill.
            //
            // Corrections over the previous version: it read the *top* padding
            // into a variable called originalPaddingBottom and used that as the
            // base for the *bottom* padding, which only happened to look right
            // because ime_layout's root has symmetric 12dp padding; it used the
            // deprecated WindowInsets.getSystemWindowInsetBottom(); it padded by
            // absolute left/right instead of start/end, so a right-to-left layout
            // would not mirror; and it read the current padding on every dispatch,
            // so repeated inset callbacks could stack. The base padding is now
            // captured once and the relative padding API is used.
            final int basePaddingBottom = view.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                // Fully qualified on purpose: this class extends
                // InputMethodService, whose nested Insets class shadows the
                // simple name (and androidx.core.graphics.Insets is the type
                // WindowInsetsCompat returns).
                androidx.core.graphics.Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());
                v.setPaddingRelative(
                        v.getPaddingStart(),
                        v.getPaddingTop(),
                        v.getPaddingEnd(),
                        basePaddingBottom + bars.bottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            cancelButton = view.findViewById(R.id.ime_cancel);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            partialScroll = view.findViewById(R.id.ime_partial_scroll);
            partialTextView = view.findViewById(R.id.ime_partial_text);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            ppToggle = view.findViewById(R.id.ime_pp_toggle);

            if (ppToggle != null) {
                ppToggle.setChecked(SettingsManager.isPostProcessEnabled(this));
                ppToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    SettingsManager settingsManager = new SettingsManager(RustInputMethodService.this);
                    if (isChecked && !settingsManager.isPostProcessConfigured()) {
                        buttonView.setChecked(false);
                        settingsManager.setPostProcessEnabled(false);
                        Toast.makeText(RustInputMethodService.this,
                                R.string.pp_not_configured_prompt, Toast.LENGTH_LONG).show();
                        return;
                    }
                    settingsManager.setPostProcessEnabled(isChecked);
                    if (!isChecked) {
                        if (!isRecording && statusView != null
                                && getString(R.string.ime_refining).equals(statusView.getText().toString())) {
                            PostProcessor.cancelAllFor(RustInputMethodService.this);
                            if (lastRawTranscript != null && !lastRawTranscript.trim().isEmpty()) {
                                commitFinalText(lastRawTranscript);
                            }
                        }
                    }
                });
            }

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    resultPending = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethodSafe();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Custom onTouch handling replaces the framework's
                        // click dispatch; report the click so accessibility
                        // services still see it (ClickableViewAccessibility).
                        v.performClick();
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;

                    case MotionEvent.ACTION_UP:
                        // See the backspace key: keep the accessibility click
                        // event that the custom onTouch handler replaced.
                        v.performClick();
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            cancelButton.setOnClickListener(v -> cancelCurrentTranscription());

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText(getString(R.string.ime_no_mic_permission));
                    if (hintView != null) hintView.setText(getString(R.string.ime_open_app_for_permission));
                    return;
                }

                if (isRecording) {
                    AudioDeviceManager.releaseMicrophone(this);
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    // Mark the result as pending so the Cancel button stays
                    // visible continuously through transcription/refining
                    // (see updateRecordButtonUI).
                    resultPending = true;
                    updateRecordButtonUI(false);
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    SettingsManager sm = new SettingsManager(this);
                    AudioDeviceManager.acquireMicrophone(this, sm.getMicMode());
                    startRecording(isAutoStopEnabled(), ++currentSessionId);
                    updateRecordButtonUI(true);
                }
            });

            tintRecordButton(false);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText(getString(R.string.ime_error_loading_keyboard, e.getMessage()));
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        SettingsManager sm = new SettingsManager(this);
        AudioDeviceManager.prewarmMicrophone(this, sm.getMicMode());

        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                AudioDeviceManager.acquireMicrophone(this, sm.getMicMode());
                startRecording(isAutoStopEnabled(), ++currentSessionId);
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                try {
                    currentSessionId++;
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try { stopRecording(); } catch (Throwable ignored) { }
                }
                AudioDeviceManager.releaseMicrophone(this);
                // The capture was discarded: nothing is pending, so the Cancel
                // button must not linger when the keyboard is shown again.
                resultPending = false;
                updateRecordButtonUI(false);
            } else {
                // Default: keep recording in the background. The transcription
                // is committed on return (or held in pendingCommitText).
                return;
            }
        } else {
            AudioDeviceManager.cancelPrewarm(this);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = true;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        if (ppToggle != null) {
            ppToggle.setChecked(SettingsManager.isPostProcessEnabled(this));
        }
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            setInputView(onCreateInputView());
        }
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        flushPendingText();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        inputActive = false;
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        tintRecordButton(recording);
        if (recording) {
            // A fresh capture starts a new pending cycle (a new recording can
            // begin while an earlier result is still finishing).
            resultPending = false;
            lastRawTranscript = null;
            statusView.setText(getString(R.string.ime_listening));
            hintView.setText(getString(R.string.ime_tap_to_stop));
            // Keep the cancel action available while capturing: it discards the
            // current recording (and any post-processing request if already in
            // flight) instead of forcing the user to stop, transcribe and only
            // then cancel — which would waste an LLM call on discarded text.
            if (cancelButton != null) {
                cancelButton.setVisibility(View.VISIBLE);
                cancelButton.setEnabled(!isDestroyed);
            }
        } else {
            statusView.setText(getString(R.string.ime_processing));
            hintView.setText(getString(R.string.ime_tap_to_record));
            // resultPending is the single source of truth from mic release
            // until commit/cancel: every stop path sets it synchronously, so
            // a stale lastStatus ("Processing...") must never re-show the
            // button after the text was already committed.
            if (cancelButton != null) {
                cancelButton.setVisibility(resultPending ? View.VISIBLE : View.GONE);
                cancelButton.setEnabled(!isDestroyed);
            }
            if (micLevelView != null) micLevelView.setLevel(0f);
            clearPartialText();
        }
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? androidx.appcompat.R.attr.colorError
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnError
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            boolean isBt = AudioDeviceManager.shouldShowHeadsetIcon(this);
            micIcon.setImageResource(isBt ? R.drawable.ic_headset : R.drawable.ic_mic);
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    /**
     * Switches to the previously used input method. No-op on API < 28, where
     * {@link #switchToPreviousInputMethod()} does not exist (minSdk is 26).
     * Do NOT annotate this method with {@code @TargetApi(P)}: that tells lint the
     * body only ever runs on API 28+, which would flag the guard below as
     * obsolete and let a 26/27 device reach the call and crash.
     */
    private void switchToPreviousInputMethodSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod();
        }
    }

    @Override
    public void onDestroy() {
        currentSessionId++;
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        super.onDestroy();
        isDestroyed = true;
        currentSessionId++;
        // Defensive: the native side may already be gone (wrong-ABI
        // emulator, service killed during teardown); never crash the IME
        // process while cleaning up.
        try { cleanupNative(); } catch (Throwable ignored) { }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        // Cancel this IME's in-flight post-processing calls when the service
        // dies (owner-scoped; the cross-process CANCEL_PP receiver keeps the
        // global cancelAll for the "PP disabled" event).
        PostProcessor.cancelAllFor(this);
        unregisterReceiver(cancelReceiver);
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop(int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (isRecording) {
                AudioDeviceManager.releaseMicrophone(RustInputMethodService.this);
                stopRecording();
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                resultPending = true;
                updateRecordButtonUI(false);
            }
        });
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> applyStatus(status));
    }

    public void onStatusUpdate(String status, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            applyStatus(status);
        });
    }

    private void applyStatus(String status) {
        Log.d(TAG, "Status: " + status);
        lastStatus = status;
        // A terminal error ends the transcription without a text callback:
        // clear the pending flag so the Cancel button does not stay visible
        // with nothing to cancel.
        if (status != null && status.startsWith("Error")) {
            resultPending = false;
            if (isRecording) {
                AudioDeviceManager.releaseMicrophone(this);
                updateRecordButtonUI(false);
            }
        }
        updateUiState();
        if (pendingSwitchBack && status != null && status.startsWith("Error")) {
            pendingSwitchBack = false;
            switchToPreviousInputMethodSafe();
        }
        if (pauseAudioActive && status != null && status.startsWith("Error")) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText(getString(R.string.ime_processing));
            } else {
                statusView.setText(getString(R.string.ime_tap_to_record));
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable recording while transcription is running, but leave the
        // dedicated cancel action enabled so the user can discard the result.
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }
        if (cancelButton != null) {
            // Visible while recording (discard the capture before it is
            // transcribed/post-processed) and for the whole pending window
            // from mic release until the result is committed or cancelled —
            // so it never blinks when "Ready" lands between ASR and
            // post-processing.
            cancelButton.setVisibility(isRecording || resultPending
                    || isTranscribing || isWaiting ? View.VISIBLE : View.GONE);
            cancelButton.setEnabled(!isDestroyed);
        }

        if (hintView != null && !isRecording) {
            hintView.setText(getString(R.string.ime_tap_to_record));
        }
    }

    // Called from Rust with live partial hypotheses while recording
    // (streaming models). Visual-only (per design): the partial text is shown
    // in the keyboard's live window (up to ~three lines, auto-scrolled to the
    // newest words); the editor receives only the final text via
    // onTextTranscribed, so there is no risk of Frankenstein text from
    // revising hypotheses mid-utterance.
    public void onPartialText(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (isRecording && partialTextView != null && partialScroll != null
                && text != null && !text.trim().isEmpty()) {
                // Show the live hypothesis and scroll the window to the
                // bottom so the newest words stay visible as it grows.
                partialTextView.setText(text);
                partialScroll.setVisibility(View.VISIBLE);
                partialScroll.post(() -> partialScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /** Clears the streaming partial window. The record area keeps its full
     *  height: the keyboard grows slightly while live partials are shown
     *  instead of compacting the mic button area. */
    private void clearPartialText() {
        if (partialTextView != null) partialTextView.setText("");
        if (partialScroll != null) partialScroll.setVisibility(View.GONE);
    }

    /** Cancels native transcription and any owner-scoped post-processing call. */
    private void cancelCurrentTranscription() {
        currentSessionId++;
        lastStatus = "Canceled";
        resultPending = false;
        lastRawTranscript = null;
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        PostProcessor.cancelAllFor(this);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
        if (statusView != null) statusView.setText(getString(R.string.ime_cancelled));
        if (hintView != null) hintView.setText(getString(R.string.ime_tap_to_record));
        if (recordContainer != null) {
            recordContainer.setEnabled(true);
            recordContainer.setAlpha(1.0f);
        }
        if (cancelButton != null) cancelButton.setVisibility(View.GONE);
    }

    // Called from Rust
    public void onTextTranscribed(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                resultPending = false;
                lastRawTranscript = null;
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText(getString(R.string.ime_tap_to_record));
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethodSafe();
                }
                return;
            }

            lastRawTranscript = text;
            SettingsManager settings = new SettingsManager(this);
            if (settings.isPostProcessEnabled()) {
                lastStatus = "Processing...";
                if (statusView != null) statusView.setText(getString(R.string.ime_refining));
                if (hintView != null) hintView.setText("");
                if (cancelButton != null) {
                    cancelButton.setVisibility(View.VISIBLE);
                    cancelButton.setEnabled(!isDestroyed);
                }

                // The LLM is intentionally not streamed. The ASR model already
                // provided the real-time preview; committing only this complete
                // result keeps the editor atomic and avoids duplicate/partial
                // text when a provider closes or revises an SSE response.
                final int processingSessionId = sessionId;
                new PostProcessor(settings, mainHandler,
                        () -> processingSessionId == currentSessionId && !isDestroyed,
                        this)
                        .process(text, new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        if (processingSessionId != currentSessionId) return;
                        String result = refinedText != null && !refinedText.trim().isEmpty()
                                ? refinedText : text;
                        commitFinalText(result);
                    }

                    @Override
                    public void onError(String error) {
                        if (processingSessionId != currentSessionId) return;
                        // Privacy (v0.1.24): the error string can carry
                        // provider details; the raw transcript is never
                        // logged in release builds.
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "Post-processing failed, delivering raw transcript: " + error);
                        }
                        commitFinalText(text);
                    }
                });
            } else {
                commitFinalText(text);
            }
        });
    }

    // Commits (or defers) the final transcribed/refined text and resets UI.
    private void commitFinalText(String text) {
        resultPending = false;
        lastRawTranscript = null;
        String committed = text + " ";
        InputConnection ic = getCurrentInputConnection();
        if (inputActive && ic != null) {
            commitTranscribedText(ic, committed);
        } else {
            // No editor is focused right now (common on long transcribes where
            // a web field in Firefox/Gemini dropped focus while we processed
            // audio). Committing now would be silently dropped, so defer the
            // text until a field is focused again instead of losing it.
            pendingCommitText = committed;
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
        if (statusView != null) statusView.setText(getString(R.string.ime_tap_to_record));
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchToPreviousInputMethodSafe();
        }
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        try {
            ic.beginBatchEdit();
            try {
                ic.commitText(committed, 1);

                if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
                    android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                        new android.view.inputmethod.ExtractedTextRequest(), 0);
                    if (et != null) {
                        int end = et.selectionStart;
                        int start = end - committed.length();
                        if (start >= 0) {
                            ic.setSelection(start, end);
                        }
                    }
                }
            } finally {
                ic.endBatchEdit();
            }
        } catch (Exception e) {
            // The target app can destroy its editor while ASR or post-processing
            // is finishing. Do not crash the IME; defer the exact final text so
            // it can be committed when the next editor becomes available.
            Log.w(TAG, "commitText failed for final transcript", e);
            pendingCommitText = committed;
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }
    public void onAudioLevel(float level, int sessionId) {
        if (sessionId != currentSessionId) return;
        if (micLevelView != null) {
            mainHandler.post(() -> {
                if (sessionId == currentSessionId) micLevelView.setLevel(level);
            });
        }
    }

    public void onAudioLevel(float level) {
        onAudioLevel(level, currentSessionId);
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    private boolean isAutoStopEnabled() {
        return new File(getFilesDir(), "auto_stop").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
