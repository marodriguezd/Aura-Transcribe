package dev.notune.transcribe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent audio input routing manager (FUTO Keyboard style).
 *
 * <p>Handles seamless switching between Bluetooth SCO headsets, Bluetooth LE Audio,
 * USB external microphones, wired headsets, and built-in microphones.
 * Provides pre-warming handshake for zero-latency capture and dedicated AudioRecord creation.</p>
 *
 * <p>Routing correctness contract (2026-10 audit, Phase 2):</p>
 * <ul>
 *   <li>Only input-capable (source) devices are ever selected as capture targets.
 *       {@code TYPE_BLE_SPEAKER} is an output-only LE Audio speaker and is
 *       deliberately excluded everywhere a microphone candidate is considered
 *       (decision logic centralized in {@link AudioRoutingPolicy}).</li>
 *   <li>{@link #acquireMicrophone(Context, String)} returns {@code true} only when the
 *       requested routing really succeeded. It returns {@code false} exclusively when the
 *       user explicitly selected "Bluetooth only" and no Bluetooth microphone could be
 *       routed — the AUTO and built-in modes keep their documented built-in fallback.</li>
 *   <li>{@code isRoutingActive}/{@code isPrewarmed} never claim success that the platform
 *       did not confirm ({@code setCommunicationDevice()} returning {@code false} counts
 *       as failure).</li>
 *   <li>The audio mode in effect before routing is saved and restored by
 *       {@link #releaseMicrophone(Context)} instead of unconditionally forcing
 *       {@code MODE_NORMAL}.</li>
 * </ul>
 */
public final class AudioDeviceManager {
    private static final String TAG = "AudioDeviceManager";

    public static final String MIC_MODE_AUTO = "auto";
    public static final String MIC_MODE_BLUETOOTH_ONLY = "bluetooth";
    public static final String MIC_MODE_BUILTIN_ONLY = "builtin";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static volatile boolean isRoutingActive = false;
    private static volatile boolean isPrewarmed = false;

    /** Audio mode captured before the first routing change; restored on release. */
    private static Integer savedAudioMode = null;

    private AudioDeviceManager() {
        // Utility class: static routing helpers only, never instantiated
    }

    /**
     * Returns true if audio routing is currently active for recording.
     */
    public static boolean isRoutingActive() {
        return isRoutingActive;
    }

    /**
     * Returns true if the communication channel is currently pre-warmed.
     */
    public static boolean isPrewarmed() {
        return isPrewarmed;
    }

    /**
     * Pre-warms the Bluetooth communication channel in the background when the
     * keyboard window or floating bubble appears, so tapping "Record" begins
     * capturing immediately (0 ms latency) without clipping the speaker's first syllables.
     *
     * <p>{@code isPrewarmed} is only set when the platform confirmed the route
     * (a Bluetooth input candidate existed and {@code setCommunicationDevice()}
     * returned true, or the legacy SCO activation completed without error). A failed
     * attempt rolls the audio mode back and leaves the state untouched.</p>
     */
    public static synchronized void prewarmMicrophone(Context context, String micPreference) {
        if (context == null || isRoutingActive || isPrewarmed) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            if (MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
                // Builtin does not require pre-warming
                return;
            }

            if (isBluetoothConnected(context)) {
                rememberAudioMode(am);
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                try {
                    am.setSpeakerphoneOn(false);
                } catch (Exception ignored) {}

                boolean routed;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioDeviceInfo target = findTargetDevice(context, micPreference);
                    routed = target != null && setCommunicationDeviceVerified(am, target);
                } else {
                    routed = startLegacyBluetoothSco(am);
                }

                if (routed) {
                    isPrewarmed = true;
                    Log.d(TAG, "Bluetooth communication channel pre-warmed successfully");
                } else {
                    Log.w(TAG, "Pre-warm routing was not confirmed by the platform; rolling back");
                    restoreAudioMode(am);
                }
            }
        } catch (Exception t) {
            Log.w(TAG, "Error pre-warming microphone routing", t);
            restoreAudioMode(am);
        }
    }

    /**
     * Cancels any pending pre-warmed communication channel if recording was not started.
     */
    public static synchronized void cancelPrewarm(Context context) {
        if (isPrewarmed && !isRoutingActive) {
            releaseMicrophone(context);
        }
    }

    /**
     * Acquires the best audio input device based on user preference and hardware state.
     * Must be called immediately before opening the audio capture stream.
     *
     * @return {@code true} when the requested routing is in effect (or the built-in
     *         fallback was taken, which is the documented AUTO/built-in behavior);
     *         {@code false} only when the user explicitly selected "Bluetooth only"
     *         and no Bluetooth microphone could be routed. On {@code false} no routing
     *         state is left behind.
     */
    public static synchronized boolean acquireMicrophone(Context context, String micPreference) {
        if (context == null) return false;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        try {
            boolean ok;
            if (MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
                routeToBuiltinMic(am);
                ok = true;
            } else if (MIC_MODE_BLUETOOTH_ONLY.equals(micPreference)) {
                ok = routeToBluetoothMic(context, am);
            } else {
                // AUTO: Prefer Bluetooth / USB / Wired if connected, otherwise fallback to builtin
                ok = routeAuto(context, am);
            }
            if (ok) {
                isRoutingActive = true;
                isPrewarmed = false;
            } else {
                // Never leave partial state behind a failed explicit request.
                releaseRoutingState(am);
            }
            return ok;
        } catch (Exception t) {
            Log.e(TAG, "Error acquiring microphone routing", t);
            releaseRoutingState(am);
            return false;
        }
    }

    /**
     * Releases communication device routing and restores the audio mode that was
     * in effect before routing began. Idempotent: repeated calls are safe and
     * restore nothing the second time.
     */
    public static synchronized void releaseMicrophone(Context context) {
        if (context == null) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            // AudioManager is unavailable; still clear the flags so a later release
            // does not believe routing is active.
            isRoutingActive = false;
            isPrewarmed = false;
            savedAudioMode = null;
            return;
        }
        releaseRoutingState(am);
    }

    /** Clears all routing state and restores the saved audio mode. */
    private static void releaseRoutingState(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                clearCommunicationDeviceApi31(am);
            }
            try {
                am.stopBluetoothSco();
            } catch (Exception ignored) {}
            try {
                am.setBluetoothScoOn(false);
            } catch (Exception ignored) {}
            try {
                am.setSpeakerphoneOn(false);
            } catch (Exception ignored) {}
            restoreAudioMode(am);
        } catch (Exception t) {
            Log.e(TAG, "Error releasing microphone routing", t);
        } finally {
            isRoutingActive = false;
            isPrewarmed = false;
            savedAudioMode = null;
        }
    }

    /** Remembers the current audio mode once, before the first routing change. */
    private static void rememberAudioMode(AudioManager am) {
        if (savedAudioMode != null) return;
        try {
            savedAudioMode = am.getMode();
        } catch (Exception t) {
            // getMode() can throw SecurityException in exotic states; treat the
            // prior mode as unknown (falls back to MODE_NORMAL on restore).
            Log.w(TAG, "Could not read current audio mode", t);
        }
    }

    /** Restores the saved audio mode, or MODE_NORMAL when none was captured. */
    private static void restoreAudioMode(AudioManager am) {
        int mode = savedAudioMode != null ? savedAudioMode : AudioManager.MODE_NORMAL;
        try {
            am.setMode(mode);
        } catch (Exception t) {
            Log.w(TAG, "Error restoring audio mode " + mode, t);
        }
    }

    /**
     * Resolves the target AudioDeviceInfo according to user preference and hardware
     * connectivity. Only input-capable (source) devices are considered; output-only
     * devices such as {@code TYPE_BLE_SPEAKER} are never returned.
     */
    public static AudioDeviceInfo findTargetDevice(Context context, String micPreference) {
        if (context == null || MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
            return null;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;

            List<AudioRoutingPolicy.Candidate> candidates;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                candidates = toCandidates(am.getAvailableCommunicationDevices());
            } else {
                candidates = toCandidates(am.getDevices(AudioManager.GET_DEVICES_INPUTS));
            }
            boolean auto = MIC_MODE_AUTO.equals(micPreference);
            AudioRoutingPolicy.Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                    candidates, /* bluetoothAllowed= */ true, /* wiredUsbAllowed= */ auto);
            if (selected == null) return null;
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? findCommunicationDeviceById(am, selected.id)
                    : findInputDeviceById(am, selected.id);
        } catch (Exception t) {
            Log.w(TAG, "Error finding target audio device", t);
        }
        return null;
    }

    /** Maps platform devices to pure policy candidates. */
    private static List<AudioRoutingPolicy.Candidate> toCandidates(AudioDeviceInfo[] devices) {
        List<AudioRoutingPolicy.Candidate> result = new ArrayList<>();
        if (devices == null) return result;
        for (AudioDeviceInfo d : devices) {
            if (d == null) continue;
            result.add(new AudioRoutingPolicy.Candidate(d.getType(), d.isSource(), d.getId()));
        }
        return result;
    }

    /** Maps platform communication devices (API 31+ list) to pure policy candidates. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static List<AudioRoutingPolicy.Candidate> toCandidates(List<AudioDeviceInfo> devices) {
        List<AudioRoutingPolicy.Candidate> result = new ArrayList<>();
        if (devices == null) return result;
        for (AudioDeviceInfo d : devices) {
            if (d == null) continue;
            result.add(new AudioRoutingPolicy.Candidate(d.getType(), d.isSource(), d.getId()));
        }
        return result;
    }

    /** Finds the AudioDeviceInfo matching a policy candidate id. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static AudioDeviceInfo findCommunicationDeviceById(AudioManager am, int id) {
        List<AudioDeviceInfo> devices = am.getAvailableCommunicationDevices();
        if (devices == null) return null;
        for (AudioDeviceInfo d : devices) {
            if (d != null && d.getId() == id) return d;
        }
        return null;
    }

    /** Finds the AudioDeviceInfo matching a policy candidate id (legacy input list). */
    private static AudioDeviceInfo findInputDeviceById(AudioManager am, int id) {
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return null;
        for (AudioDeviceInfo d : devices) {
            if (d != null && d.getId() == id) return d;
        }
        return null;
    }

    /**
     * Calls {@code setCommunicationDevice} and reports whether the platform really
     * applied the route — no exception is not enough; the boolean result decides.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static boolean setCommunicationDeviceVerified(AudioManager am, AudioDeviceInfo target) {
        try {
            boolean set = am.setCommunicationDevice(target);
            String name = String.valueOf(target.getProductName());
            Log.d(TAG, "setCommunicationDevice(" + name + ") -> " + set);
            return set;
        } catch (Exception t) {
            Log.w(TAG, "setCommunicationDevice failed", t);
            return false;
        }
    }

    /**
     * Legacy (API 26–30) SCO activation. {@code startBluetoothSco} is asynchronous
     * and returns void, so "no exception" is the strongest synchronous confirmation
     * the platform offers on these API levels.
     */
    private static boolean startLegacyBluetoothSco(AudioManager am) {
        try {
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
            return true;
        } catch (Exception t) {
            Log.w(TAG, "Error starting Bluetooth SCO", t);
            return false;
        }
    }

    /**
     * Creates and configures a dedicated Android AudioRecord instance with
     * MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000 Hz, mono PCM 16-bit,
     * bound directly to target AudioDeviceInfo via setPreferredDevice().
     *
     * <p>Returns {@code null} when RECORD_AUDIO has not been granted at runtime.
     * Constructing an AudioRecord is only a manifest-declaration question, but
     * {@code startRecording()} silently fails (or throws) without the runtime
     * grant, so the condition is checked here and reported as a plain null
     * instead of an opaque failure inside the HAL. This is also what makes the
     * {@code MissingPermission} lint check pass without a suppression.
     */
    public static AudioRecord createAudioRecord(Context context, String micPreference) {
        if (context == null
                || ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; refusing to create an AudioRecord");
            return null;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuf <= 0) {
            minBuf = 4096;
        }
        int bufferSize = Math.max(minBuf * 4, 8192);

        boolean useVoiceComm = !MIC_MODE_BUILTIN_ONLY.equals(micPreference) && isBluetoothConnected(context);
        int primarySource = useVoiceComm ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int fallbackSource = useVoiceComm ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.MIC;

        AudioDeviceInfo target = findTargetDevice(context, micPreference);
        AudioRecord record = null;
        try {
            AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(primarySource)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build())
                .setBufferSizeInBytes(bufferSize);
            record = builder.build();
            if (target != null && record != null) {
                record.setPreferredDevice(target);
            }
            Log.i("BluetoothSCO", "AudioRecord initialized with source=" + (primarySource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ? "VOICE_COMMUNICATION" : (primarySource == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION" : "MIC")) + " target=" + (target != null ? target.getId() : "default"));
        } catch (Exception t) {
            Log.e(TAG, "Error creating AudioRecord with primary source " + primarySource, t);
        }

        // Fallback to alternate audio source if primary failed to initialize
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (record != null) {
                try { record.release(); } catch (Exception ignored) {}
            }
            try {
                AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(fallbackSource)
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                    .setBufferSizeInBytes(bufferSize);
                record = builder.build();
                if (target != null && record != null) {
                    record.setPreferredDevice(target);
                }
                Log.i("BluetoothSCO", "AudioRecord fallback initialized with source=" + (fallbackSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ? "VOICE_COMMUNICATION" : (fallbackSource == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION" : "MIC")));
            } catch (Exception t) {
                Log.e(TAG, "Error creating AudioRecord fallback with source " + fallbackSource, t);
            }
        }

        return record;
    }

    /**
     * Returns true if audio is actively being captured (or pre-warmed) from a Bluetooth headset.
     */
    public static boolean isBluetoothCapturing(Context context) {
        if (!isRoutingActive && !isPrewarmed) return false;
        return isBluetoothConnected(context);
    }

    /**
     * Returns a human-friendly name of the active input device for diagnostics.
     */
    public static String getActiveInputDeviceName(Context context) {
        if (context == null) return "Unknown";
        try {
            List<String> connected = getConnectedInputDevices(context);
            if (!connected.isEmpty()) {
                // If bluetooth connected and routing active, return bluetooth device
                for (String dev : connected) {
                    if (dev.contains("Bluetooth")) {
                        return dev;
                    }
                }
                return connected.get(0);
            }
        } catch (Exception ignored) {}
        return "📱 " + context.getString(R.string.mic_active_builtin);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void clearCommunicationDeviceApi31(AudioManager am) {
        try {
            am.clearCommunicationDevice();
        } catch (Exception t) {
            Log.w(TAG, "Error clearing communication device on API 31+", t);
        }
    }

    /**
     * AUTO-mode routing. Returns true when a confirmed route is in effect — either a
     * communication device applied by the platform, or the built-in fallback (the
     * documented AUTO behavior). Returns false only on an unexpected routing error.
     */
    private static boolean routeAuto(Context context, AudioManager am) {
        if (isBluetoothConnected(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return routeAutoApi31(context, am);
            }
            // Android 8 - 11 legacy SCO activation
            rememberAudioMode(am);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            return startLegacyBluetoothSco(am);
        }
        routeToBuiltinMic(am);
        return true;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static boolean routeAutoApi31(Context context, AudioManager am) {
        try {
            AudioRoutingPolicy.Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                    toCandidates(am.getAvailableCommunicationDevices()),
                    /* bluetoothAllowed= */ true, /* wiredUsbAllowed= */ true);
            if (selected != null) {
                rememberAudioMode(am);
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                AudioDeviceInfo target = findCommunicationDeviceById(am, selected.id);
                boolean set = target != null && setCommunicationDeviceVerified(am, target);
                if (set) {
                    return true;
                }
                // Platform rejected the route: fall back to the built-in mic (AUTO contract).
                Log.w(TAG, "AUTO routing to communication device rejected; falling back to built-in mic");
                routeToBuiltinMic(am);
                return true;
            }
            routeToBuiltinMic(am);
            return true;
        } catch (Exception t) {
            Log.e(TAG, "Error in routeAutoApi31", t);
            routeToBuiltinMic(am);
            return true;
        }
    }

    /**
     * Explicit "Bluetooth only" routing. Returns true only when a Bluetooth input
     * candidate existed and the platform confirmed the route. When it returns false
     * nothing is left routed and no mode change survives.
     */
    private static boolean routeToBluetoothMic(Context context, AudioManager am) {
        boolean candidateFound = isBluetoothConnected(context);
        if (!candidateFound) {
            Log.w(TAG, "Bluetooth-only mode requested but no Bluetooth input device is connected");
            return false;
        }
        rememberAudioMode(am);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        try {
            am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}

        boolean routed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routed = routeToBluetoothMicApi31(am);
        } else {
            routed = startLegacyBluetoothSco(am);
        }
        if (!AudioRoutingPolicy.bluetoothRoutingSucceeded(true, routed)) {
            return false;
        }
        return true;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static boolean routeToBluetoothMicApi31(AudioManager am) {
        try {
            AudioRoutingPolicy.Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                    toCandidates(am.getAvailableCommunicationDevices()),
                    /* bluetoothAllowed= */ true, /* wiredUsbAllowed= */ false);
            if (selected == null) return false;
            AudioDeviceInfo target = findCommunicationDeviceById(am, selected.id);
            return target != null && setCommunicationDeviceVerified(am, target);
        } catch (Exception t) {
            Log.e(TAG, "Error in routeToBluetoothMicApi31", t);
            return false;
        }
    }

    private static void routeToBuiltinMic(AudioManager am) {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                clearCommunicationDeviceApi31(am);
            }
            try {
                am.stopBluetoothSco();
            } catch (Exception ignored) {}
            try {
                am.setBluetoothScoOn(false);
            } catch (Exception ignored) {}
            try {
                am.setSpeakerphoneOn(false);
            } catch (Exception ignored) {}
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception t) {
            Log.w(TAG, "Error routing to built-in mic", t);
        }
    }

    /**
     * Checks if any Bluetooth microphone input device (SCO, BLE Headset microphone) is connected.
     * Output-only devices (e.g. TYPE_BLE_SPEAKER) never count as a connected microphone.
     */
    public static boolean isBluetoothConnected(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        try {
            // Check BluetoothAdapter HEADSET profile (microphone capable)
            try {
                android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    // getProfileConnectionState() is annotated with
                    // @BluetoothAdapter.ConnectionState: the value is the same as
                    // BluetoothProfile.STATE_CONNECTED, but the API contract for
                    // this accessor is the BluetoothAdapter constant set.
                    if (adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;

            if (am.isBluetoothScoOn()) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (AudioRoutingPolicy.hasBluetoothInput(
                        toCandidates(am.getAvailableCommunicationDevices()))) {
                    return true;
                }
            }

            return AudioRoutingPolicy.hasBluetoothInput(
                    toCandidates(am.getDevices(AudioManager.GET_DEVICES_INPUTS)));
        } catch (Exception t) {
            Log.w(TAG, "Error checking bluetooth microphone state", t);
        }
        return false;
    }

    /**
     * Determines whether the recording icon should display as headset (🎧) or microphone (🎙️).
     * Returns false if the user has selected "Solo micrófono interno" or if no Bluetooth mic is present.
     */
    public static boolean shouldShowHeadsetIcon(Context context) {
        if (context == null) return false;
        String micPreference = new SettingsManager(context).getMicMode();
        if (MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
            return false;
        }
        return isBluetoothConnected(context);
    }

    /**
     * Returns a human-friendly description of the currently connected input and audio devices.
     */
    public static List<String> getConnectedInputDevices(Context context) {
        List<String> result = new ArrayList<>();
        if (context == null) return result;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return result;

            // The explicit union is used instead of the GET_DEVICES_ALL alias:
            // getDevices() is annotated for exactly these two flags, so this is
            // the documented combination (and the alias is the same value).
            AudioDeviceInfo[] devices = am.getDevices(
                    AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null) return result;
            for (AudioDeviceInfo d : devices) {
                String label = "Audio Device";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        CharSequence name = d.getProductName();
                        if (name != null && name.length() > 0) {
                            label = name.toString();
                        }
                    } catch (Exception ignored) {}
                }
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                         type == AudioDeviceInfo.TYPE_HEARING_AID ||
                         type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                    String entry = "🎧 " + label + " (Bluetooth)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                           type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    String entry = "🎙️ " + label + " (USB)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                           type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    String entry = "🎧 " + label + " (Cable)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    String entry = "📱 " + label + " (Interno)";
                    if (!result.contains(entry)) result.add(entry);
                }
            }
        } catch (Exception t) {
            Log.e(TAG, "Error querying connected input devices", t);
        }
        return result;
    }
}
