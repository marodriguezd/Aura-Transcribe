package dev.notune.transcribe;

import java.util.List;

/**
 * Pure decision logic for audio input routing. Contains no Android framework
 * dependencies so every routing decision is unit-testable on the JVM.
 *
 * <p>Key invariants enforced here (see AGENTS.md §Phase 2 audit):
 * <ul>
 *   <li>{@code TYPE_BLE_SPEAKER} (27) is an <em>output-only</em> LE Audio speaker.
 *       It must never be selected as a capture source, even though it appears in
 *       {@code AudioManager.getAvailableCommunicationDevices()} (that list is
 *       bidirectional).</li>
 *   <li>Only devices whose capabilities include {@code source} (mirrors
 *       {@code AudioDeviceInfo.isSource()}) are eligible as input candidates.</li>
 *   <li>Bluetooth is preferred over USB/wired, which are preferred over the
 *       built-in microphone.</li>
 *   <li>The built-in microphone is an allowed fallback for AUTO mode, but never
 *       for the explicit "Bluetooth only" mode.</li>
 * </ul>
 */
public final class AudioRoutingPolicy {

    // Mirrors of android.media.AudioDeviceInfo type constants. The numeric values
    // are frozen by the public platform API contract and never change between
    // releases; duplicating them here (with this comment) is what lets the policy
    // run on the JVM without the Android framework. AudioDeviceManager asserts the
    // real constants match these values at runtime in debug builds.
    public static final int TYPE_WIRED_HEADSET = 3;      // AudioDeviceInfo.TYPE_WIRED_HEADSET
    public static final int TYPE_BLUETOOTH_SCO = 7;      // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    public static final int TYPE_USB_DEVICE = 11;        // AudioDeviceInfo.TYPE_USB_DEVICE
    public static final int TYPE_BUILTIN_MIC = 15;       // AudioDeviceInfo.TYPE_BUILTIN_MIC
    public static final int TYPE_USB_HEADSET = 22;       // AudioDeviceInfo.TYPE_USB_HEADSET
    public static final int TYPE_HEARING_AID = 23;       // AudioDeviceInfo.TYPE_HEARING_AID
    public static final int TYPE_BLE_HEADSET = 26;       // AudioDeviceInfo.TYPE_BLE_HEADSET
    public static final int TYPE_BLE_SPEAKER = 27;       // AudioDeviceInfo.TYPE_BLE_SPEAKER (output-only)

    /** A routing candidate flattened from {@code AudioDeviceInfo}. */
    public static final class Candidate {
        /** One of the TYPE_* constants above. */
        public final int type;
        /** Mirrors {@code AudioDeviceInfo.isSource()}: can capture audio from this device. */
        public final boolean source;
        /** Mirrors {@code AudioDeviceInfo.getId()}. */
        public final int id;

        public Candidate(int type, boolean source, int id) {
            this.type = type;
            this.source = source;
            this.id = id;
        }
    }

    private AudioRoutingPolicy() {
        // Pure static policy; never instantiated.
    }

    /**
     * Returns true for Bluetooth device types that carry a microphone input.
     *
     * <p>{@code TYPE_BLE_SPEAKER} is deliberately excluded: it is a sink-only LE
     * Audio speaker, not a capture source.
     */
    public static boolean isBluetoothInputType(int type) {
        return type == TYPE_BLUETOOTH_SCO
                || type == TYPE_BLE_HEADSET
                || type == TYPE_HEARING_AID;
    }

    /** Returns true for wired/USB device types usable as input. */
    public static boolean isWiredOrUsbInputType(int type) {
        return type == TYPE_WIRED_HEADSET
                || type == TYPE_USB_HEADSET
                || type == TYPE_USB_DEVICE;
    }

    /**
     * Selects the best input candidate from {@code devices}.
     *
     * @param devices         candidates in platform iteration order
     * @param bluetoothAllowed whether a Bluetooth microphone may be selected
     * @param wiredUsbAllowed  whether a USB/wired headset may be selected (AUTO mode)
     * @return the selected candidate, or {@code null} to use the built-in microphone
     */
    public static Candidate selectInputCandidate(
            List<Candidate> devices, boolean bluetoothAllowed, boolean wiredUsbAllowed) {
        if (devices == null || devices.isEmpty()) return null;
        if (bluetoothAllowed) {
            for (Candidate c : devices) {
                if (c != null && c.source && isBluetoothInputType(c.type)) return c;
            }
        }
        if (wiredUsbAllowed) {
            for (Candidate c : devices) {
                if (c != null && c.source && isWiredOrUsbInputType(c.type)) return c;
            }
        }
        return null;
    }

    /**
     * Returns true if {@code devices} contains any Bluetooth device that can
     * capture audio. Sink-only devices (e.g. TYPE_BLE_SPEAKER) never count.
     */
    public static boolean hasBluetoothInput(List<Candidate> devices) {
        return selectInputCandidate(devices, true, false) != null;
    }

    /**
     * Whether a failed explicit "Bluetooth only" acquisition may fall back to the
     * built-in microphone. It may not: the user explicitly excluded the built-in mic.
     * AUTO and built-in modes always keep the built-in fallback (documented behavior).
     */
    public static boolean builtinFallbackAllowed(String micPreference) {
        return !AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY.equals(micPreference);
    }

    /**
     * Combines the platform's reported routing outcome into the success flag for
     * an explicit Bluetooth routing attempt.
     *
     * @param candidateFound       a Bluetooth input candidate existed
     * @param platformReportedSuccess {@code setCommunicationDevice()} returned true,
     *                               or the legacy SCO start completed without error
     * @return true only when a candidate existed and the platform confirmed the route
     */
    public static boolean bluetoothRoutingSucceeded(
            boolean candidateFound, boolean platformReportedSuccess) {
        return candidateFound && platformReportedSuccess;
    }
}
