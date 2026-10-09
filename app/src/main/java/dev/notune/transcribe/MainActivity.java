package dev.notune.transcribe;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQ_DOWNLOAD_NOTIFICATIONS = 303;
    private boolean pendingFloatingStart = false;
    private boolean pendingDebugModelDownload = false;
    private static final String ACTION_CANCEL_DEBUG_DOWNLOAD =
            "dev.notune.transcribe.CANCEL_DEBUG_DOWNLOAD";
    private static final String DEBUG_DOWNLOAD_CHANNEL_ID = "DebugModelDownload";
    private static final int DEBUG_DOWNLOAD_NOTIFICATION_ID = 30303;

    private final ActivityResultLauncher<Intent> voiceTestLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    ArrayList<String> results =
                            result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    String heard = (results != null && !results.isEmpty()) ? results.get(0) : null;
                    snackbar(heard != null && !heard.trim().isEmpty()
                            ? getString(R.string.voice_test_ok, heard)
                            : getString(R.string.voice_test_empty));
                }
                updateVoiceInputStatus();
            });

    private final ActivityResultLauncher<String[]> permissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                updateVoiceInputStatus();
                updateConnectedMicsList();
            });

    private final ActivityResultLauncher<String[]> floatingMicPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (pendingFloatingStart) {
                    pendingFloatingStart = false;
                    Boolean granted = result.get(android.Manifest.permission.RECORD_AUDIO);
                    if (Boolean.TRUE.equals(granted)) {
                        MaterialSwitch switchFloating = findViewById(R.id.switch_floating_mode);
                        if (switchFloating != null) {
                            switchFloating.setChecked(true);
                        } else {
                            startFloatingService();
                        }
                    } else {
                        snackbar(getString(R.string.floating_need_mic_body));
                    }
                }
            });

    private final ActivityResultLauncher<String[]> downloadNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (pendingDebugModelDownload) {
                    pendingDebugModelDownload = false;
                    // The launcher is only ever started from
                    // requestNotificationPermissionThenStartDownload(), which is
                    // gated on TIRAMISU. Keeping the SDK check here as well makes
                    // that assumption explicit (and keeps the API-33-only
                    // permission constant out of the pre-33 bytecode path).
                    Boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ? result.get(android.Manifest.permission.POST_NOTIFICATIONS)
                            : null;
                    if (Boolean.FALSE.equals(granted)) {
                        snackbar(getString(R.string.debug_model_notification_denied));
                    }
                    startDebugModelDownload();
                }
            });

    /**
     * Debug-model identity (P0.3). Must match {@code modelPackFiles} in
     * {@code app/build.gradle.kts}: the runtime download only activates the
     * model after verifying this exact SHA-256, so a truncated or corrupted
     * file can never become the active model (same guarantee the release
     * build's {@code checkModels} gate enforces for the bundled asset).
     */
    private static final String DEBUG_MODEL_NAME =
            "nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf";
    private static final String DEBUG_MODEL_SHA256 =
            "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c";

    // Debug builds download the model on first run. Guard against multiple
    // concurrent downloads (e.g. after a configuration change the Activity is
    // recreated but the background thread keeps running).
    private static final AtomicBoolean isDownloadingDebugModel = new AtomicBoolean(false);
    private static final AtomicBoolean cancelDebugModelDownload = new AtomicBoolean(false);
    private static final Object debugModelFinalizationLock = new Object();
    private static volatile okhttp3.Call debugModelCall;

    // Live reference to whichever MainActivity instance is currently in the
    // foreground. The model download thread reads this field on completion
    // instead of the Activity it was created in: if a config change
    // (rotation, locale change, ...) happens during the download, the original
    // Activity is destroyed and the runOnUiThread callback that targets it
    // would short-circuit, leaving the new instance stuck on
    // "Downloading model…" forever. Resolving the live instance at completion
    // lets us notify the Activity that is actually on screen, regardless of
    // which one started the download.
    // Held weakly on purpose: a strong static Activity reference would keep the
    // Activity (and its whole view tree) alive until the process died
    // (StaticFieldLeak). The reference only exists to notify whichever
    // instance is on screen, so losing it to GC is harmless - the download
    // itself is owned by a static worker and finishes regardless.
    private static volatile java.lang.ref.WeakReference<MainActivity> sActiveInstance =
            new java.lang.ref.WeakReference<>(null);

    // Model availability is probed on a background thread (see
    // maybeDownloadDebugModel): the check walks the asset database and reads the
    // active_model marker from disk, and running it during onCreate/onResume
    // tripped StrictMode on the main thread. These two fields are main-thread
    // state, touched only from onCreate/onResume and the posted result: the
    // counter is the staleness guard (a result from a superseded probe is
    // dropped instead of overwriting newer state) and the flag coalesces the
    // onCreate/onResume pair so a cold start walks the assets only once.
    private final AtomicInteger modelProbeGeneration = new AtomicInteger();
    private boolean modelProbeInFlight;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (Throwable t) {
            try {
                Log.w(TAG, "Failed to load native libraries", t);
            } catch (Throwable ignored) {}
        }
    }

    private TextView statusText;
    private TextView voiceStatusText;
    private ImageView voiceStatusIcon;
    private Button voiceGrantButton;
    private Button voiceTryButton;
    private Button startSubsButton;
    private Button benchButton;
    private TextView benchResultText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View rootView = findViewById(R.id.main_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);

        voiceGrantButton.setOnClickListener(v -> checkAndRequestPermissions());
        voiceTryButton.setOnClickListener(v -> launchVoiceTest());
        voiceHelpButton.setOnClickListener(v -> showHelpDialog());

        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_subs_advanced).setOnClickListener(v -> showSubsAdvancedDialog());

        findViewById(R.id.btn_models).setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));

        findViewById(R.id.btn_post_process).setOnClickListener(v ->
                startActivity(new Intent(this, PostProcessSettingsActivity.class)));

        // Opens Android's Personal Dictionary. The previous implementation also
        // tried to import those words programmatically, which required a
        // permission the public SDK does not expose — see UserDictionaryHelper.
        findViewById(R.id.btn_custom_words).setOnClickListener(v ->
                UserDictionaryHelper.openSystemUserDictionarySettings(this));

        benchButton = findViewById(R.id.btn_benchmark);
        benchResultText = findViewById(R.id.text_bench_result);
        benchButton.setOnClickListener(v -> runBenchmark());

        // Settings stored as marker files in filesDir: the native engine reads them
        // straight from the filesystem, with no ContentProvider involved.
        bindMarkerSwitch(R.id.switch_auto_record, "auto_record", false);
        bindMarkerSwitch(R.id.switch_select_transcription, "select_transcription", false);
        // "Pause audio" is the opt-in for taking audio focus at all; with it off
        // the app never requests focus and never interrupts playback.
        bindMarkerSwitch(R.id.switch_pause_audio, "pause_audio", false);
        // Record-in-background defaults to ON; its marker file is the opt-out.
        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false);

        // Live subtitle line limit: 4 (default), 2, or 0 = unlimited.
        RadioGroup subsLinesGroup = findViewById(R.id.rg_subtitle_lines);
        int subsLines = SubtitlePrefs.getMaxLines(this);
        if (subsLines == 4) {
            subsLinesGroup.check(R.id.rb_subs_4);
        } else if (subsLines == 0) {
            subsLinesGroup.check(R.id.rb_subs_all);
        } else {
            subsLinesGroup.check(R.id.rb_subs_2);
        }
        subsLinesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int lines = checkedId == R.id.rb_subs_4 ? 4
                    : checkedId == R.id.rb_subs_all ? 0 : 2;
            SubtitlePrefs.setMaxLines(this, lines);
        });

        RadioGroup themeGroup = findViewById(R.id.rg_theme);
        switch (ThemePrefs.getMode(this)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                themeGroup.check(R.id.rb_theme_light);
                break;
            case AppCompatDelegate.MODE_NIGHT_YES:
                themeGroup.check(R.id.rb_theme_dark);
                break;
            default:
                themeGroup.check(R.id.rb_theme_system);
                break;
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode;
            if (checkedId == R.id.rb_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rb_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, newMode);
                if (isServiceRunning(FloatingOverlayService.class)) {
                    try {
                        startService(new Intent(this, FloatingOverlayService.class));
                    } catch (Throwable ignored) {}
                }
            }
        });

        // Handle notification actions after the views are ready.
        handleDownloadIntent(getIntent());

        // Initial check
        updateVoiceInputStatus();
        setupFloatingModeControls();
        setupMicModeControls();
        setupMicDiagnostics();

        // Debug builds ship without the bundled model to keep the APK small;
        // download it from Hugging Face on first run. This is also called from
        // onResume() so a configuration change that occurs while downloading
        // still initializes the engine once the model is ready.
        maybeDownloadDebugModel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDownloadIntent(intent);
    }

    private void handleDownloadIntent(Intent intent) {
        if (intent != null && ACTION_CANCEL_DEBUG_DOWNLOAD.equals(intent.getAction())) {
            cancelDebugModelDownload();
            intent.setAction(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Claim the foreground slot so a download finishing while we are on
        // screen — including one started by an Activity that has since been
        // destroyed by a config change mid-download — can post its UI update
        // and initNative here. Read by startDebugModelDownload() on completion.
        sActiveInstance = new java.lang.ref.WeakReference<>(this);
        // Re-check on return from the keyboard chooser, settings, or a test run.
        updateVoiceInputStatus();
        setupFloatingModeControls();
        setupMicModeControls();
        // Re-check the debug model state after a configuration change or when
        // returning from another screen.
        maybeDownloadDebugModel();
    }

    private void setupFloatingModeControls() {
        MaterialSwitch switchFloating = findViewById(R.id.switch_floating_mode);
        Button btnOverlayPerm = findViewById(R.id.btn_floating_overlay_permission);
        Button btnAccessibilityPerm = findViewById(R.id.btn_floating_accessibility_permission);

        if (switchFloating == null) return;

        if (btnOverlayPerm != null) {
            btnOverlayPerm.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable t) {
                    Log.e(TAG, "Error opening overlay permission settings", t);
                }
            });
        }

        if (btnAccessibilityPerm != null) {
            btnAccessibilityPerm.setOnClickListener(v -> {
                // Route through the prominent in-app disclosure FIRST. The user
                // must be told in context what an Accessibility service can read
                // and that it is optional before being sent to Android's
                // settings; the disclosure screen is the only path there.
                try {
                    startActivity(new Intent(this, AccessibilityDisclosureActivity.class));
                } catch (RuntimeException t) {
                    Log.e(TAG, "Error opening the accessibility disclosure", t);
                }
            });
        }

        switchFloating.setOnCheckedChangeListener(null);
        boolean canOverlay = Settings.canDrawOverlays(this);
        boolean isFloatingEnabled = canOverlay && isServiceRunning(FloatingOverlayService.class);
        switchFloating.setChecked(isFloatingEnabled);

        switchFloating.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.floating_overlay_permission_msg, Toast.LENGTH_LONG).show();
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Throwable ignored) { }
                    switchFloating.setChecked(false);
                    return;
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Ask for the mic first: the floating service declares the
                    // "microphone" foreground-service type, so starting it
                    // without RECORD_AUDIO would crash it (and with START_STICKY
                    // it would crash-loop). Revert the switch and resume once
                    // the permission is granted.
                    pendingFloatingStart = true;
                    switchFloating.setChecked(false);
                    floatingMicPermissionLauncher.launch(
                            new String[]{android.Manifest.permission.RECORD_AUDIO});
                    return;
                }
                startFloatingService();
            } else {
                Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
                stopService(serviceIntent);
            }
        });
    }

    private void setupMicModeControls() {
        RadioGroup rgMic = findViewById(R.id.rg_mic_mode);
        if (rgMic == null) return;

        SettingsManager sm = new SettingsManager(this);
        String currentMode = sm.getMicMode();
        if (AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY.equals(currentMode)) {
            rgMic.check(R.id.rb_mic_bluetooth);
        } else if (AudioDeviceManager.MIC_MODE_AUTO.equals(currentMode)) {
            rgMic.check(R.id.rb_mic_auto);
        } else {
            rgMic.check(R.id.rb_mic_builtin);
        }

        rgMic.setOnCheckedChangeListener((group, checkedId) -> {
            String newMode;
            if (checkedId == R.id.rb_mic_bluetooth) {
                newMode = AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    permissionsLauncher.launch(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT});
                }
            } else if (checkedId == R.id.rb_mic_builtin) {
                newMode = AudioDeviceManager.MIC_MODE_BUILTIN_ONLY;
            } else {
                newMode = AudioDeviceManager.MIC_MODE_AUTO;
            }
            sm.setMicMode(newMode);
            updateConnectedMicsList();
        });

        updateConnectedMicsList();
    }

    private void updateConnectedMicsList() {
        TextView txtConnected = findViewById(R.id.text_connected_mics);
        if (txtConnected == null) return;

        List<String> connected = AudioDeviceManager.getConnectedInputDevices(this);
        if (!connected.isEmpty()) {
            StringBuilder sb = new StringBuilder(getString(R.string.desc_audio_input));
            sb.append("\n\n").append(getString(R.string.mic_connected_label)).append(" ");
            sb.append(TextUtils.join(", ", connected));
            txtConnected.setText(sb.toString());
        } else {
            txtConnected.setText(R.string.desc_audio_input);
        }
    }

    private final AudioRecordBridge micTestBridge = new AudioRecordBridge();
    private boolean isMicTesting = false;

    private void setupMicDiagnostics() {
        com.google.android.material.button.MaterialButton btnTest = findViewById(R.id.btn_mic_test);
        TextView txtDevice = findViewById(R.id.text_active_mic_device);
        if (btnTest == null) return;

        if (txtDevice != null) {
            String activeName = AudioDeviceManager.getActiveInputDeviceName(this);
            txtDevice.setText(getString(R.string.mic_test_active_device, activeName));
        }

        btnTest.setOnClickListener(v -> {
            if (isMicTesting) {
                stopMicTest();
            } else {
                startMicTest();
            }
        });
    }

    private void startMicTest() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsLauncher.launch(new String[]{android.Manifest.permission.RECORD_AUDIO});
            return;
        }

        com.google.android.material.button.MaterialButton btnTest = findViewById(R.id.btn_mic_test);
        MicLevelView levelView = findViewById(R.id.mic_test_level_view);
        ImageView micIcon = findViewById(R.id.mic_test_icon);
        TextView txtDevice = findViewById(R.id.text_active_mic_device);

        SettingsManager sm = new SettingsManager(this);
        String micMode = sm.getMicMode();
        AudioDeviceManager.acquireMicrophone(this, micMode);

        boolean started = micTestBridge.start(this, micMode, new AudioRecordBridge.Callback() {
            @Override
            public void onAudioChunk(java.nio.ByteBuffer directBuffer, int bytesRead) {}

            @Override
            public void onAudioLevel(float level) {
                runOnUiThread(() -> {
                    if (levelView != null && isMicTesting) {
                        levelView.setLevel(level);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> stopMicTest());
            }
        });

        if (started) {
            isMicTesting = true;
            if (btnTest != null) btnTest.setText(R.string.mic_test_btn_stop);
            if (txtDevice != null) {
                String activeName = AudioDeviceManager.getActiveInputDeviceName(this);
                txtDevice.setText(getString(R.string.mic_test_active_device, activeName));
            }
            if (micIcon != null) {
                boolean isBt = AudioDeviceManager.shouldShowHeadsetIcon(this);
                micIcon.setImageResource(isBt ? R.drawable.ic_headset : R.drawable.ic_mic);
            }
        } else {
            AudioDeviceManager.releaseMicrophone(this);
        }
    }

    private void stopMicTest() {
        if (!isMicTesting) return;
        isMicTesting = false;
        micTestBridge.stop();
        AudioDeviceManager.releaseMicrophone(this);

        com.google.android.material.button.MaterialButton btnTest = findViewById(R.id.btn_mic_test);
        MicLevelView levelView = findViewById(R.id.mic_test_level_view);
        ImageView micIcon = findViewById(R.id.mic_test_icon);

        if (btnTest != null) btnTest.setText(R.string.mic_test_btn_start);
        if (levelView != null) levelView.setLevel(0f);
        if (micIcon != null) micIcon.setImageResource(R.drawable.ic_mic);
    }

    private void startFloatingService() {
        Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        if (FloatingOverlayService.class.equals(serviceClass)) {
            return FloatingOverlayService.isRunning();
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMicTest();
        // Drop the foreground slot while we are not in the foreground. A
        // download finishing while no Activity is foregrounded will resolve
        // sActiveInstance to null and skip the UI update, leaving the next
        // onResume() to detect the imported model (ModelAvailability) and call
        // initNative there. This avoids posting runnables into a paused/destroyed
        // Activity that would silently drop them.
        if (sActiveInstance.get() == this) {
            sActiveInstance = new java.lang.ref.WeakReference<>(null);
        }
    }

    @Override
    protected void onDestroy() {
        stopMicTest();
        super.onDestroy();
    }

    /**
     * Reflects whether the primary voice-input flow is ready. Apps (SwiftKey,
     * Firefox, …) fire {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
     * {@code startActivityForResult}, which {@link RecognizeActivity} handles.
     * The flow works when (a) the mic permission is granted and (b) our activity
     * is what Android resolves that intent to — either because it is the sole
     * handler or because the user picked us as the default. This is deliberately
     * judged on the activity path, NOT on being the default {@code RecognitionService}.
     */
    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micGranted) {
            setVoiceStatus(false, getString(R.string.voice_status_need_mic));
            voiceGrantButton.setVisibility(View.VISIBLE);
            voiceTryButton.setEnabled(false);
            return;
        }

        voiceGrantButton.setVisibility(View.GONE);
        voiceTryButton.setEnabled(true);

        if (isOurAppDefaultRecognizer()) {
            setVoiceStatus(true, getString(R.string.voice_status_ready));
        } else {
            setVoiceStatus(false, getString(R.string.voice_status_almost));
        }
    }

    /** True if our RecognizeActivity is what RECOGNIZE_SPEECH resolves to. */
    private boolean isOurAppDefaultRecognizer() {
        Intent recog = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo resolved = getPackageManager()
                .resolveActivity(recog, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void setVoiceStatus(boolean ready, String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(ready ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ready
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(androidx.appcompat.R.attr.colorError);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
        // The icon is a decorative state indicator: the adjacent
        // voiceStatusText already announces the same message, so giving the icon
        // its own description made TalkBack read the status twice. Hiding it
        // from the accessibility tree keeps a single, ordered announcement.
        voiceStatusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private int themeColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    /**
     * One-tap self-test: fires the exact intent a keyboard's mic does. If ours is
     * the sole handler it launches straight away; if several apps handle it the
     * system shows a chooser, where the user can pick us and "Always" — which sets
     * the default and flips the status to ready on return.
     */
    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            voiceTestLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
            snackbar(getString(R.string.voice_test_failed));
        }
    }

    /**
     * Explains the adb escape hatch for the MediaProjection consent dialog:
     * once the PROJECT_MEDIA app-op is set to allow, the system permission
     * activity returns RESULT_OK immediately, so subtitles start without the
     * "Start recording or casting?" sheet. No app code depends on this — the
     * normal dialog flow is the untouched fallback.
     */
    private void showSubsAdvancedDialog() {
        String allowCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA allow";
        String resetCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA default";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.subs_advanced_title)
                .setMessage(getString(R.string.subs_advanced_body, allowCmd, resetCmd))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.subs_advanced_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", allowCmd));
                    snackbar(getString(R.string.subs_advanced_copied));
                })
                .show();
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_help_title)
                .setMessage(R.string.voice_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    /**
     * Binds a switch to a marker file in filesDir. With {@code inverted}, the
     * file's presence means the switch is OFF (used for default-on settings).
     */
    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted) {
        CompoundButton sw = findViewById(switchId);
        sw.setChecked(MarkerFileHelper.exists(this, fileName) != inverted);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean shouldExist = isChecked != inverted;
            MarkerFileHelper.setExists(this, fileName, shouldExist);
        });
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!needed.isEmpty()) {
            permissionsLauncher.launch(needed.toArray(new String[0]));
        }
    }

    // --- Benchmark ----------------------------------------------------------

    /**
     * Transcribes the bundled test clip through the active model and shows the
     * speed as a real-time factor. The clip is a fixed English recording so
     * results are comparable across models and devices; the run uses the
     * current model settings (language hint, translate).
     */
    private void runBenchmark() {
        benchButton.setEnabled(false);
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(R.string.bench_running);

        new Thread(() -> {
            float[] samples;
            try {
                samples = readWavAsset("bench.wav");
            } catch (IOException e) {
                Log.e(TAG, "Failed to read benchmark clip", e);
                runOnUiThread(() -> {
                    benchResultText.setText(getString(R.string.bench_error, e.getMessage()));
                    benchButton.setEnabled(true);
                });
                return;
            }
            benchmarkNative(this, samples, samples.length);
        }, "benchmark-load").start();
    }

    // Called from Rust when the benchmark run finishes.
    public void onBenchmarkResult(float audioSecs, float computeSecs, String error) {
        runOnUiThread(() -> {
            benchButton.setEnabled(true);
            benchResultText.setVisibility(View.VISIBLE);
            if (error != null && !error.isEmpty()) {
                benchResultText.setText(getString(R.string.bench_error, error));
            } else {
                benchResultText.setText(getString(R.string.bench_result,
                        String.format(java.util.Locale.getDefault(), "%.1f", audioSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f", computeSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f",
                                computeSecs > 0 ? audioSecs / computeSecs : 0)));
            }
        });
    }

    /**
     * Reads a 16 kHz mono 16-bit PCM WAV from assets into float samples. Only
     * handles the format the bundled clip is stored in; no general WAV support.
     */
    private float[] readWavAsset(String name) throws IOException {
        byte[] bytes;
        try (java.io.InputStream in = getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            bytes = out.toByteArray();
        }

        // Find the "data" chunk instead of assuming a 44-byte header.
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkSize = (bytes[offset + 4] & 0xff) | ((bytes[offset + 5] & 0xff) << 8)
                    | ((bytes[offset + 6] & 0xff) << 16) | ((bytes[offset + 7] & 0xff) << 24);
            if (bytes[offset] == 'd' && bytes[offset + 1] == 'a'
                    && bytes[offset + 2] == 't' && bytes[offset + 3] == 'a') {
                int start = offset + 8;
                int count = Math.min(chunkSize, bytes.length - start) / 2;
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    int lo = bytes[start + 2 * i] & 0xff;
                    int hi = bytes[start + 2 * i + 1];
                    samples[i] = ((hi << 8) | lo) / 32768.0f;
                }
                return samples;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        throw new IOException(getString(R.string.bench_no_data_chunk, name));
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
            // "Ready" may carry a suffix, e.g. "Ready (this model can't translate)".
            if (status.startsWith("Ready")) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    // --- Debug model download -----------------------------------------------

    /**
     * Debug builds do not include the bundled speech model (keeps the APK under
     * Telegram's 50 MB file limit). On first run, offer to download it from
     * Hugging Face and treat it as an imported model.
     */
    private void maybeDownloadDebugModel() {
        if (isDownloadingDebugModel.get()) {
            statusText.setText(R.string.debug_model_downloading);
            benchButton.setEnabled(false);
            return;
        }

        // A probe started a moment ago will report; re-running it here (a cold
        // start calls this from onCreate() and then again from onResume()) would
        // only repeat the same asset and marker walk.
        if (modelProbeInFlight) return;
        modelProbeInFlight = true;

        // "Checking assets…" is also the layout default for this TextView, so the
        // user sees one continuous state instead of a blank line until the
        // engine reports "Ready" (or the download dialog takes over).
        statusText.setText(R.string.status_checking);

        final int generation = modelProbeGeneration.incrementAndGet();
        final AssetManager assets = getAssets();
        final File filesDir = getFilesDir();

        new Thread(() -> {
            final boolean ready = ModelAvailability.isModelReady(assets, filesDir);
            runOnUiThread(() -> {
                modelProbeInFlight = false;
                // Liveness first: a probe that outlives its Activity must not
                // touch the views. isFinishing()/isDestroyed() are checked on the
                // UI thread, atomic with the work below.
                if (isFinishing() || isDestroyed()) return;
                // Staleness: a newer probe (or a newer Activity instance) has
                // already answered, so this result is obsolete.
                if (generation != modelProbeGeneration.get()) return;
                if (ready) {
                    initNative(this);
                } else {
                    showDebugModelDownloadDialog();
                }
            });
        }, "model-probe").start();
    }

    /** Offered when no model is available: the debug download dialog. */
    private void showDebugModelDownloadDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.debug_model_title)
                .setMessage(getString(R.string.debug_model_body,
                        getString(R.string.debug_model_size)))
                .setCancelable(false)
                .setPositiveButton(R.string.debug_model_download,
                        (d, w) -> requestNotificationPermissionThenStartDownload())
                .setNegativeButton(R.string.debug_model_cancel,
                        (d, w) -> statusText.setText(R.string.debug_model_cancelled))
                .show();
    }

    private void requestNotificationPermissionThenStartDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingDebugModelDownload = true;
            downloadNotificationPermissionLauncher.launch(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS});
            return;
        }
        startDebugModelDownload();
    }

    private void startDebugModelDownload() {
        if (!isDownloadingDebugModel.compareAndSet(false, true)) {
            statusText.setText(R.string.debug_model_downloading);
            benchButton.setEnabled(false);
            return;
        }
        statusText.setText(R.string.debug_model_downloading);
        benchButton.setEnabled(false);
        cancelDebugModelDownload.set(false);
        showDebugDownloadNotification(0, -1, -1);
        final Context appContext = getApplicationContext();

        new Thread(() -> {
            // Wrap the entire body in try/finally so the AtomicBoolean is
            // cleared even if mkdirs() or the disk-space check fails before
            // the HTTP path runs. Otherwise the guard stays at true and the
            // user is locked out of retrying ("Downloading model…" forever).
            try {
            File modelsDir = new File(appContext.getFilesDir(), "models");
            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                final MainActivity ui = sActiveInstance.get();
                if (ui != null) {
                    ui.runOnUiThread(() -> {
                        if (ui.isFinishing() || ui.isDestroyed()) return;
                        ui.onModelDownloadFailed(
                                ui.getString(R.string.debug_model_cannot_create_dir));
                    });
                }
                return;
            }

            // Rough sanity check: the model is ~751 MB; leave extra headroom.
            long requiredBytes = 800L * 1024 * 1024;
            if (DeviceStorage.availableBytes(appContext, appContext.getFilesDir()) < requiredBytes) {
                final MainActivity ui = sActiveInstance.get();
                if (ui != null) {
                    ui.runOnUiThread(() -> {
                        if (ui.isFinishing() || ui.isDestroyed()) return;
                        ui.onModelDownloadFailed(ui.getString(R.string.debug_model_no_space));
                    });
                }
                return;
            }

            File dest = new File(modelsDir, DEBUG_MODEL_NAME);
            File tmp = new File(modelsDir, DEBUG_MODEL_NAME + ".tmp");
            // A leftover .tmp from an interrupted download must never block a
            // fresh attempt (rename would fail over an existing file).
            if (tmp.exists()) tmp.delete();
            String url = "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main/"
                    + DEBUG_MODEL_NAME + "?download=true";

            // Mobile networks can be slow for a 751 MB file; give generous timeouts.
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder().url(url).build();
            debugModelCall = client.newCall(request);
            try (Response response = debugModelCall.execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException(appContext.getString(R.string.debug_model_http_error,
                            response.code()));
                }
                long totalBytes = response.body().contentLength();
                long downloadedBytes = 0;
                int lastPercent = -1;
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        if (cancelDebugModelDownload.get()) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        fos.write(buffer, 0, read);
                        downloadedBytes += read;
                        int percent = totalBytes > 0
                                ? (int) Math.min(100, downloadedBytes * 100 / totalBytes)
                                : -1;
                        if (percent != lastPercent
                                && (percent < 0 || percent % 2 == 0 || percent == 100)) {
                            lastPercent = percent;
                            showDebugDownloadNotification(percent, downloadedBytes, totalBytes);
                        }
                    }
                }
                if (cancelDebugModelDownload.get()) {
                    throw new java.util.concurrent.CancellationException();
                }

                // Verify the download BEFORE it can become the active model
                // (P0.3): a truncated or altered file is deleted and reported,
                // never activated — matching the release build's checkModels
                // guarantee.
                String actualHash = FileSha256.sha256Hex(tmp);
                if (cancelDebugModelDownload.get()) {
                    tmp.delete();
                    throw new java.util.concurrent.CancellationException();
                }
                if (!DEBUG_MODEL_SHA256.equalsIgnoreCase(actualHash)) {
                    tmp.delete();
                    throw new IOException(appContext.getString(R.string.debug_model_checksum_mismatch,
                            DEBUG_MODEL_SHA256, actualHash));
                }

                synchronized (debugModelFinalizationLock) {
                    if (cancelDebugModelDownload.get()) {
                        throw new java.util.concurrent.CancellationException();
                    }
                    if (!tmp.renameTo(dest)) {
                        throw new IOException(appContext.getString(R.string.debug_model_rename_failed));
                    }

                    // Mark this downloaded file as the active imported model
                    // atomically (temp + rename), the same way every other
                    // cross-process marker is written (P1.2).
                    MarkerFileHelper.writeString(appContext, "active_model", DEBUG_MODEL_NAME);
                    if (cancelDebugModelDownload.get()) {
                        MarkerFileHelper.delete(appContext, "active_model");
                        dest.delete();
                        throw new java.util.concurrent.CancellationException();
                    }
                    if (!DEBUG_MODEL_NAME.equals(
                            MarkerFileHelper.readString(appContext, "active_model", ""))) {
                        dest.delete();
                        throw new IOException(appContext.getString(R.string.debug_model_rename_failed));
                    }
                }

                // Notify whichever MainActivity is currently in the foreground
                // (resolved at completion, NOT the captured MainActivity.this
                // we were created in): if a config change happened during the
                // download, the original Activity is destroyed and a naive
                // runOnUiThread on it would short-circuit on isDestroyed().
                // Reading sActiveInstance at completion lets us post directly
                // to whichever Activity is actually on screen (B, after a
                // rotation). If no Activity is foregrounded right now, the
                // next instance picks it up in onResume() through the
                // background model probe → initNative(); the UI text catches
                // up on the next onStatusUpdate from Rust.
                final MainActivity ui = sActiveInstance.get();
                if (ui != null) {
                    // Move the liveness check INSIDE the runOnUiThread body: the
                    // outer check is on a background thread and there is a small
                    // race window between reading isDestroyed() and the runnable
                    // actually executing. Both activity destruction and the
                    // runnable target run on the UI thread, so re-checking here
                    // is atomic with the body below.
                    ui.runOnUiThread(() -> {
                        if (ui.isFinishing() || ui.isDestroyed()) return;
                        ui.statusText.setText(R.string.debug_model_downloaded);
                        ui.benchButton.setEnabled(true);
                        ui.initNative(ui);
                    });
                }
            } catch (java.util.concurrent.CancellationException e) {
                tmp.delete();
                final MainActivity ui = sActiveInstance.get();
                if (ui != null) {
                    ui.runOnUiThread(() -> {
                        if (ui.isFinishing() || ui.isDestroyed()) return;
                        ui.statusText.setText(R.string.debug_model_cancelled);
                        ui.benchButton.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                if (cancelDebugModelDownload.get()) {
                    tmp.delete();
                    return;
                }
                Log.e(TAG, "Debug model download failed", e);
                tmp.delete();
                // Same live-Activity dispatch as the success path: notify the
                // currently foregrounded Activity (if any) so the retry
                // dialog appears there. If no Activity is foregrounded, the
                // next onResume() sees no bundled/imported model and re-shows
                // the initial download dialog — a graceful fallback rather
                // than a silent loss of the failure reason for one cycle.
                final String reason = e.getMessage() != null ? e.getMessage()
                        : appContext.getString(R.string.debug_model_unknown_reason);
                final MainActivity ui = sActiveInstance.get();
                if (ui != null) {
                    // Symmetric with the success path: liveness is checked on
                    // the UI thread, atomic with the work below.
                    ui.runOnUiThread(() -> {
                        if (ui.isFinishing() || ui.isDestroyed()) return;
                        ui.onModelDownloadFailed(reason);
                    });
                }
            }
            } finally {
                debugModelCall = null;
                cancelDebugDownloadNotification();
                // Reset regardless of whether we succeeded, caught an exception,
                // or hit one of the early-return paths (mkdirs() failure or low
                // disk space) — without this, the AtomicBoolean stays at true on
                // disk-space failures and the user is locked into
                // "Downloading model…" forever with no way to retry.
                isDownloadingDebugModel.set(false);
            }
        }, "debug-model-download").start();
    }

    private void showDebugDownloadNotification(int percent, long downloadedBytes, long totalBytes) {
        android.content.Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(DEBUG_DOWNLOAD_CHANNEL_ID,
                context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.debug_model_downloading));
        manager.createNotificationChannel(channel);

        Intent cancelIntent = new Intent(context, MainActivity.class);
        cancelIntent.setAction(ACTION_CANCEL_DEBUG_DOWNLOAD);
        PendingIntent cancelPendingIntent = PendingIntent.getActivity(context, REQ_DOWNLOAD_NOTIFICATIONS,
                cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String progressText;
        if (percent >= 0 && totalBytes > 0) {
            progressText = context.getString(R.string.debug_model_downloading) + " " + percent + "%";
        } else if (downloadedBytes > 0) {
            progressText = context.getString(R.string.debug_model_downloading) + " ("
                    + formatBytes(downloadedBytes) + ")";
        } else {
            progressText = context.getString(R.string.debug_model_downloading);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DEBUG_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(progressText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .addAction(0, context.getString(R.string.debug_model_cancel), cancelPendingIntent);
        manager.notify(DEBUG_DOWNLOAD_NOTIFICATION_ID, builder.build());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void cancelDebugDownloadNotification() {
        NotificationManager manager = (NotificationManager)
                getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(DEBUG_DOWNLOAD_NOTIFICATION_ID);
    }

    private void cancelDebugModelDownload() {
        if (!isDownloadingDebugModel.get()) return;
        cancelDebugModelDownload.set(true);
        synchronized (debugModelFinalizationLock) {
            okhttp3.Call call = debugModelCall;
            if (call != null) call.cancel();
        }
    }

    private void onModelDownloadFailed(String reason) {
        statusText.setText(getString(R.string.debug_model_error, reason));
        benchButton.setEnabled(true);
        showRetryDownloadDialog(reason);
    }

    private void showRetryDownloadDialog(String reason) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.debug_model_title)
                .setMessage(getString(R.string.debug_model_error, reason))
                .setCancelable(false)
                .setPositiveButton(R.string.debug_model_retry,
                        (d, w) -> requestNotificationPermissionThenStartDownload())
                .setNegativeButton(R.string.debug_model_cancel,
                        (d, w) -> {
                            statusText.setText(R.string.debug_model_cancelled);
                            benchButton.setEnabled(true);
                        })
                .show();
    }

    private native void initNative(MainActivity activity);

    private native void benchmarkNative(MainActivity activity, float[] samples, int length);
}
