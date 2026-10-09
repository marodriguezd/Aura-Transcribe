package dev.notune.transcribe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Robust Android AudioRecord bridge following FUTO Keyboard & AOSP best practices.
 *
 * <p>Captures 16000 Hz mono 16-bit PCM via standard short[] arrays to avoid HAL driver
 * direct-buffer discrepancies, computes real-time RMS audio levels, and delivers
 * direct ByteBuffers to native JNI engine.</p>
 *
 * <p>RECORD_AUDIO is verified at runtime before any capture object is created:
 * the manifest declaration alone is not enough, and without this check a denied
 * permission would surface as an opaque HAL failure from startRecording()
 * rather than an actionable error (and it is what lets lint's MissingPermission
 * check pass without a class-level suppression).</p>
 */
public class AudioRecordBridge {
    private static final String TAG = "AudioRecordBridge";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int SAMPLES_PER_CHUNK = 1600; // 100ms at 16kHz
    public static final int CHUNK_SIZE_BYTES = SAMPLES_PER_CHUNK * 2; // 3200 bytes

    public interface Callback {
        void onAudioChunk(ByteBuffer directBuffer, int bytesRead);
        void onAudioLevel(float level);
        void onError(String message);
    }

    private AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private ByteBuffer directBuffer;

    public synchronized boolean start(Context context, String micPreference, Callback callback) {
        if (isRecording.get()) {
            stop();
        }

        if (context == null
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted; refusing to start audio capture");
            if (callback != null) callback.onError("microphone permission not granted");
            return false;
        }

        try {
            AudioDeviceManager.acquireMicrophone(context, micPreference);
            audioRecord = AudioDeviceManager.createAudioRecord(context, micPreference);
            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord");
                if (callback != null) callback.onError("Failed to initialize AudioRecord");
                AudioDeviceManager.releaseMicrophone(context);
                return false;
            }

            directBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to start recording");
                if (callback != null) callback.onError("AudioRecord failed to start recording");
                stop();
                AudioDeviceManager.releaseMicrophone(context);
                return false;
            }
            isRecording.set(true);

            final ByteBuffer nativeBuffer = directBuffer;
            captureThread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                final short[] pcmBuffer = new short[SAMPLES_PER_CHUNK];
                try {
                    while (isRecording.get() && !Thread.currentThread().isInterrupted()) {
                        AudioRecord record = audioRecord;
                        if (record == null || record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            break;
                        }
                        int samplesRead = record.read(pcmBuffer, 0, pcmBuffer.length);
                        if (samplesRead > 0) {
                            int bytesRead = samplesRead * 2;
                            nativeBuffer.clear();
                            nativeBuffer.asShortBuffer().put(pcmBuffer, 0, samplesRead);
                            nativeBuffer.position(0);
                            nativeBuffer.limit(bytesRead);

                            float rms = calculateRms(pcmBuffer, samplesRead);
                            if (callback != null && isRecording.get()) {
                                callback.onAudioLevel(rms);
                                callback.onAudioChunk(nativeBuffer, bytesRead);
                            }
                        } else if (samplesRead < 0) {
                            Log.w(TAG, "AudioRecord read error: " + samplesRead);
                            if (callback != null && isRecording.get()) {
                                callback.onError("AudioRecord read error: " + samplesRead);
                            }
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Exception in AudioRecord capture thread", t);
                    if (callback != null && isRecording.get()) {
                        callback.onError(t.getMessage());
                    }
                } finally {
                    isRecording.set(false);
                }
            }, "AudioRecordCaptureThread");
            captureThread.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Error starting AudioRecord capture", t);
            if (callback != null) callback.onError(t.getMessage());
            stop();
            AudioDeviceManager.releaseMicrophone(context);
            return false;
        }
    }

    public synchronized void stop() {
        isRecording.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Throwable ignored) {}
        }
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }
        directBuffer = null;
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public static float calculateRms(short[] buffer, int samplesRead) {
        if (buffer == null || samplesRead <= 0) return 0f;
        double sum = 0.0;
        for (int i = 0; i < samplesRead; i++) {
            double norm = buffer[i] / 32768.0;
            sum += norm * norm;
        }
        double mean = sum / samplesRead;
        double rms = Math.sqrt(mean);
        return (float) Math.min(1.0, rms * 5.0);
    }

    public static float calculateRms(ByteBuffer buffer, int bytesRead) {
        if (buffer == null || bytesRead < 2) return 0f;
        int samples = bytesRead / 2;
        double sum = 0.0;
        int oldPos = buffer.position();
        buffer.position(0);
        for (int i = 0; i < samples; i++) {
            short s = buffer.getShort();
            double norm = s / 32768.0;
            sum += norm * norm;
        }
        buffer.position(oldPos);
        double mean = sum / samples;
        double rms = Math.sqrt(mean);
        return (float) Math.min(1.0, rms * 5.0);
    }
}
