#!/usr/bin/env bash
# ==============================================================================
# Aura Transcribe - Live Audio & Bluetooth SCO Diagnostics Monitor
# ==============================================================================
# Streams real-time audio routing, SCO handshake, RMS energy, and ASR latency
# from connected Android device via ADB.
# ==============================================================================

set -e

echo "🔍 Checking ADB connection..."
if ! command -v adb >/dev/null 2>&1; then
    echo "❌ Error: 'adb' not found in PATH."
    exit 1
fi

DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" || true)
if [ -z "$DEVICES" ]; then
    echo "⚠️ No Android device connected via ADB. Please connect device via USB or Wireless ADB."
    exit 1
fi

echo "📱 Connected device: $DEVICES"
echo "🧹 Clearing previous logcat buffers..."
adb logcat -c

echo "================================================================="
echo "  🎧 AURA TRANSCRIBE - LIVE AUDIO & BLUETOOTH SCO MONITOR"
echo "================================================================="
echo "Monitoring tags: AudioDeviceManager | AudioRecordBridge | BluetoothSCO | OfflineVoiceInput"
echo "Press Ctrl+C to exit."
echo "-----------------------------------------------------------------"

adb logcat -v time \
    AudioDeviceManager:V \
    AudioRecordBridge:V \
    BluetoothSCO:V \
    OfflineVoiceInput:V \
    *:S
