package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.notune.transcribe.AudioRoutingPolicy.Candidate;

/**
 * Regression suite for {@link AudioRoutingPolicy} (2026-10 corrective audit, Phase 2).
 *
 * <p>Covers the confirmed defect where {@code TYPE_BLE_SPEAKER} (an output-only LE
 * Audio speaker) was accepted as a capture source, plus the routing preference,
 * fallback, and failure-propagation contracts.</p>
 */
public class AudioRoutingPolicyTest {

    private static Candidate sco(boolean source) {
        return new Candidate(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO, source, 1);
    }

    private static Candidate bleHeadset(boolean source) {
        return new Candidate(AudioRoutingPolicy.TYPE_BLE_HEADSET, source, 2);
    }

    private static Candidate hearingAid(boolean source) {
        return new Candidate(AudioRoutingPolicy.TYPE_HEARING_AID, source, 3);
    }

    private static Candidate bleSpeaker() {
        // Sink-only LE Audio speaker: isSource() == false on real devices.
        return new Candidate(AudioRoutingPolicy.TYPE_BLE_SPEAKER, false, 4);
    }

    private static Candidate bleSpeakerReportedAsSource() {
        // Defensive: even if a broken HAL claims isSource()==true, the type is
        // output-only by platform contract and must still be rejected.
        return new Candidate(AudioRoutingPolicy.TYPE_BLE_SPEAKER, true, 5);
    }

    private static Candidate usbHeadset() {
        return new Candidate(AudioRoutingPolicy.TYPE_USB_HEADSET, true, 6);
    }

    private static Candidate usbDevice() {
        return new Candidate(AudioRoutingPolicy.TYPE_USB_DEVICE, true, 7);
    }

    private static Candidate wiredHeadset() {
        return new Candidate(AudioRoutingPolicy.TYPE_WIRED_HEADSET, true, 8);
    }

    // ---------------------------------------------------------------------
    // Type classification
    // ---------------------------------------------------------------------

    @Test
    public void bleSpeakerIsNeverABluetoothInputType() {
        // The confirmed audit defect: TYPE_BLE_SPEAKER was treated as a mic candidate.
        assertFalse(AudioRoutingPolicy.isBluetoothInputType(AudioRoutingPolicy.TYPE_BLE_SPEAKER));
        assertTrue(AudioRoutingPolicy.isBluetoothInputType(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO));
        assertTrue(AudioRoutingPolicy.isBluetoothInputType(AudioRoutingPolicy.TYPE_BLE_HEADSET));
        assertTrue(AudioRoutingPolicy.isBluetoothInputType(AudioRoutingPolicy.TYPE_HEARING_AID));
    }

    @Test
    public void wiredAndUsbTypesAreClassified() {
        assertTrue(AudioRoutingPolicy.isWiredOrUsbInputType(AudioRoutingPolicy.TYPE_WIRED_HEADSET));
        assertTrue(AudioRoutingPolicy.isWiredOrUsbInputType(AudioRoutingPolicy.TYPE_USB_HEADSET));
        assertTrue(AudioRoutingPolicy.isWiredOrUsbInputType(AudioRoutingPolicy.TYPE_USB_DEVICE));
        assertFalse(AudioRoutingPolicy.isWiredOrUsbInputType(AudioRoutingPolicy.TYPE_BUILTIN_MIC));
        assertFalse(AudioRoutingPolicy.isWiredOrUsbInputType(AudioRoutingPolicy.TYPE_BLE_SPEAKER));
    }

    // ---------------------------------------------------------------------
    // Candidate selection: Bluetooth SCO / BLE headset
    // ---------------------------------------------------------------------

    @Test
    public void selectsBluetoothScoHeadset() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(usbDevice(), sco(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO, selected.type);
    }

    @Test
    public void selectsBleHeadsetAsInput() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleHeadset(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_BLE_HEADSET, selected.type);
    }

    @Test
    public void selectsHearingAidAsInput() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(hearingAid(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_HEARING_AID, selected.type);
    }

    @Test
    public void bluetoothPreferredOverUsbAndWired() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(wiredHeadset(), usbHeadset(), sco(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO, selected.type);
    }

    // ---------------------------------------------------------------------
    // Output-only device rejection (confirmed defect)
    // ---------------------------------------------------------------------

    @Test
    public void bleSpeakerIsRejectedAsInputCandidate() {
        // Only an output-only BLE speaker present: no input route may be selected.
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleSpeaker()), true, true);
        assertNull(selected);
    }

    @Test
    public void bleSpeakerIsRejectedEvenIfHalClaimsSourceCapability() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleSpeakerReportedAsSource()), true, true);
        assertNull(selected);
    }

    @Test
    public void bleSpeakerDoesNotShadowUsbFallbackInAuto() {
        // The bug's real-world harm: the BLE speaker used to win over the USB mic.
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleSpeaker(), usbHeadset()), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_USB_HEADSET, selected.type);
    }

    @Test
    public void bleSpeakerDoesNotShadowBluetoothSco() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleSpeaker(), sco(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO, selected.type);
    }

    @Test
    public void hasBluetoothInputIgnoresBleSpeaker() {
        assertFalse(AudioRoutingPolicy.hasBluetoothInput(Arrays.asList(bleSpeaker())));
        // Even if a HAL lies about the capability, the type stays rejected.
        assertFalse(AudioRoutingPolicy.hasBluetoothInput(
                Arrays.asList(bleSpeakerReportedAsSource())));
        assertTrue(AudioRoutingPolicy.hasBluetoothInput(Arrays.asList(bleSpeaker(), sco(true))));
    }

    // ---------------------------------------------------------------------
    // AUTO mode: USB / wired preference and built-in fallback
    // ---------------------------------------------------------------------

    @Test
    public void autoModeAcceptsUsbHeadsetWhenNoBluetooth() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(usbHeadset(), wiredHeadset()), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_USB_HEADSET, selected.type);
    }

    @Test
    public void autoModeAcceptsWiredHeadset() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(wiredHeadset()), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_WIRED_HEADSET, selected.type);
    }

    @Test
    public void autoModeFallsBackToBuiltinWhenNothingSelectable() {
        // null selection == built-in microphone fallback (documented AUTO behavior).
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(bleSpeaker()), true, true);
        assertNull(selected);
    }

    @Test
    public void bluetoothOnlyModeIgnoresWiredAndUsb() {
        // wiredUsbAllowed=false models the explicit "Bluetooth only" preference.
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(usbHeadset(), wiredHeadset()), true, false);
        assertNull(selected);
    }

    @Test
    public void bluetoothNotAllowedSkipsBluetoothEvenIfPresent() {
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(sco(true), usbHeadset()), false, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_USB_HEADSET, selected.type);
    }

    // ---------------------------------------------------------------------
    // Sink-only device filtering and degenerate inputs
    // ---------------------------------------------------------------------

    @Test
    public void sinkOnlyBluetoothCandidateIsSkipped() {
        // A SCO device reporting isSource()==false must not be selected.
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(sco(false), bleHeadset(true)), true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_BLE_HEADSET, selected.type);
    }

    @Test
    public void emptyAndNullDeviceListsAreSafe() {
        assertNull(AudioRoutingPolicy.selectInputCandidate(null, true, true));
        assertNull(AudioRoutingPolicy.selectInputCandidate(Collections.emptyList(), true, true));
        assertFalse(AudioRoutingPolicy.hasBluetoothInput(null));
        assertFalse(AudioRoutingPolicy.hasBluetoothInput(Collections.emptyList()));
    }

    @Test
    public void nullEntriesInDeviceListAreIgnored() {
        List<Candidate> devices = new ArrayList<>();
        devices.add(null);
        devices.add(usbHeadset());
        devices.add(null);
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(devices, true, true);
        assertNotNull(selected);
        assertEquals(AudioRoutingPolicy.TYPE_USB_HEADSET, selected.type);
    }

    @Test
    public void firstMatchingCandidateWinsInPlatformOrder() {
        Candidate first = new Candidate(AudioRoutingPolicy.TYPE_BLE_HEADSET, true, 42);
        Candidate second = new Candidate(AudioRoutingPolicy.TYPE_BLUETOOTH_SCO, true, 43);
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(first, second), true, true);
        assertEquals(42, selected.id);
    }

    // ---------------------------------------------------------------------
    // Bluetooth-only mode and platform-reported failures
    // ---------------------------------------------------------------------

    @Test
    public void builtinFallbackIsForbiddenInBluetoothOnlyMode() {
        assertFalse(AudioRoutingPolicy.builtinFallbackAllowed(
                AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY));
        assertTrue(AudioRoutingPolicy.builtinFallbackAllowed(
                AudioDeviceManager.MIC_MODE_AUTO));
        assertTrue(AudioRoutingPolicy.builtinFallbackAllowed(
                AudioDeviceManager.MIC_MODE_BUILTIN_ONLY));
        assertTrue(AudioRoutingPolicy.builtinFallbackAllowed(null));
        assertTrue(AudioRoutingPolicy.builtinFallbackAllowed("unknown_mode"));
    }

    @Test
    public void bluetoothRoutingSucceededRequiresBothConditions() {
        // A routing API returning false (or a missing candidate) must fail the acquisition.
        assertTrue(AudioRoutingPolicy.bluetoothRoutingSucceeded(true, true));
        assertFalse(AudioRoutingPolicy.bluetoothRoutingSucceeded(true, false));
        assertFalse(AudioRoutingPolicy.bluetoothRoutingSucceeded(false, true));
        assertFalse(AudioRoutingPolicy.bluetoothRoutingSucceeded(false, false));
    }

    // ---------------------------------------------------------------------
    // Negative control: unrelated everyday devices produce no spurious selection
    // ---------------------------------------------------------------------

    @Test
    public void builtinMicOnlyProducesNoExternalSelection() {
        // Built-in mic is the implicit fallback, never an explicit route target.
        Candidate selected = AudioRoutingPolicy.selectInputCandidate(
                Arrays.asList(new Candidate(AudioRoutingPolicy.TYPE_BUILTIN_MIC, true, 9)),
                true, true);
        assertNull(selected);
        assertFalse(AudioRoutingPolicy.hasBluetoothInput(
                Arrays.asList(new Candidate(AudioRoutingPolicy.TYPE_BUILTIN_MIC, true, 9))));
    }
}
