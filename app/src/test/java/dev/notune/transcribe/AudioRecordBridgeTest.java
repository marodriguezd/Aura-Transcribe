package dev.notune.transcribe;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM test suite for {@link AudioRecordBridge}.
 * Tests buffer constraints, RMS calculation mathematics, position preservation,
 * and null-safety contracts.
 */
public class AudioRecordBridgeTest {

    @Test
    public void testAudioConstants() {
        assertEquals(16000, AudioRecordBridge.SAMPLE_RATE);
        assertEquals(AudioFormat.CHANNEL_IN_MONO, AudioRecordBridge.CHANNEL_CONFIG);
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, AudioRecordBridge.AUDIO_FORMAT);
        assertEquals(3200, AudioRecordBridge.CHUNK_SIZE_BYTES); // 100ms at 16kHz 16-bit mono
    }

    @Test
    public void testInitialStateNotRecording() {
        AudioRecordBridge bridge = new AudioRecordBridge();
        assertFalse(bridge.isRecording());
    }

    @Test
    public void testCalculateRmsNullAndEmpty() {
        assertEquals(0f, AudioRecordBridge.calculateRms((ByteBuffer) null, 0), 0.0001f);
        assertEquals(0f, AudioRecordBridge.calculateRms((ByteBuffer) null, 3200), 0.0001f);
        assertEquals(0f, AudioRecordBridge.calculateRms((short[]) null, 0), 0.0001f);

        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        assertEquals(0f, AudioRecordBridge.calculateRms(buf, 0), 0.0001f);
        assertEquals(0f, AudioRecordBridge.calculateRms(buf, 1), 0.0001f);
    }

    @Test
    public void testCalculateRmsSilence() {
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        // All zeros
        float rms = AudioRecordBridge.calculateRms(buf, 3200);
        assertEquals(0f, rms, 0.0001f);
    }

    @Test
    public void testCalculateRmsFullScaleSquareWave() {
        int bytes = 3200;
        ByteBuffer buf = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes / 2; i++) {
            buf.putShort((short) 32767);
        }
        buf.position(0);

        float rms = AudioRecordBridge.calculateRms(buf, bytes);
        // Peak normalized amplitude ~ 1.0, scaled * 5.0 clamped to 1.0
        assertEquals(1.0f, rms, 0.0001f);
    }

    @Test
    public void testCalculateRmsPreservesBufferPosition() {
        int bytes = 1600;
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes / 2; i++) {
            buf.putShort((short) 1000);
        }
        buf.position(42);

        float rms = AudioRecordBridge.calculateRms(buf, bytes);
        assertTrue(rms > 0f);
        assertEquals(42, buf.position());
    }

    @Test
    public void testCalculateRmsScalingMonotonicity() {
        ByteBuffer quietBuf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 1600; i++) {
            quietBuf.putShort((short) 500);
        }
        quietBuf.position(0);

        ByteBuffer loudBuf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 1600; i++) {
            loudBuf.putShort((short) 5000);
        }
        loudBuf.position(0);

        float quietRms = AudioRecordBridge.calculateRms(quietBuf, 3200);
        float loudRms = AudioRecordBridge.calculateRms(loudBuf, 3200);

        assertTrue(loudRms > quietRms);
    }

    @Test
    public void testStopIdempotencyAndNullSafety() {
        AudioRecordBridge bridge = new AudioRecordBridge();
        // Multiple stop calls on unstarted bridge must be safe and idempotent
        bridge.stop();
        bridge.stop();
        assertFalse(bridge.isRecording());

        // start with null parameters should fail gracefully without unhandled crashes
        boolean result = bridge.start(null, null, null);
        assertFalse(result);
        assertFalse(bridge.isRecording());
        bridge.stop();
    }

    @Test
    public void testDirectByteBufferChunkAllocation() {
        ByteBuffer buf = ByteBuffer.allocateDirect(AudioRecordBridge.CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
        assertTrue(buf.isDirect());
        assertEquals(AudioRecordBridge.CHUNK_SIZE_BYTES, buf.capacity());
        assertEquals(3200, buf.remaining());
    }

    @Test
    public void testCalculateRmsPartialChunk() {
        // Test odd byte read count (e.g. 1599 bytes: 799 full samples)
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 800; i++) {
            buf.putShort((short) 1000);
        }
        buf.position(0);

        float rms = AudioRecordBridge.calculateRms(buf, 1599);
        assertTrue(rms > 0f);
        assertTrue(rms <= 1.0f);
    }

    @Test
    public void testShortBufferCachingFullAndPartialReadCycle() {
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(AudioRecordBridge.CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
        java.nio.ShortBuffer nativeShortBuffer = directBuffer.asShortBuffer();
        assertEquals(AudioRecordBridge.SAMPLES_PER_CHUNK, nativeShortBuffer.capacity());

        short[] pcmBuffer = new short[AudioRecordBridge.SAMPLES_PER_CHUNK];

        // 1. Full chunk write (1600 samples)
        for (int i = 0; i < pcmBuffer.length; i++) {
            pcmBuffer[i] = (short) (i + 1);
        }
        int samplesRead = 1600;
        int bytesRead = samplesRead * 2;
        nativeShortBuffer.clear();
        nativeShortBuffer.put(pcmBuffer, 0, samplesRead);
        directBuffer.position(0);
        directBuffer.limit(bytesRead);

        assertEquals(0, directBuffer.position());
        assertEquals(3200, directBuffer.limit());
        assertEquals(3200, directBuffer.remaining());
        for (int i = 0; i < samplesRead; i++) {
            assertEquals((short) (i + 1), directBuffer.getShort(i * 2));
        }

        // 2. Partial chunk write (500 samples)
        for (int i = 0; i < 500; i++) {
            pcmBuffer[i] = (short) -(i + 1);
        }
        samplesRead = 500;
        bytesRead = samplesRead * 2;
        nativeShortBuffer.clear();
        nativeShortBuffer.put(pcmBuffer, 0, samplesRead);
        directBuffer.position(0);
        directBuffer.limit(bytesRead);

        assertEquals(0, directBuffer.position());
        assertEquals(1000, directBuffer.limit());
        assertEquals(1000, directBuffer.remaining());
        for (int i = 0; i < samplesRead; i++) {
            assertEquals((short) -(i + 1), directBuffer.getShort(i * 2));
        }

        // 3. Full chunk write immediately following partial chunk write
        for (int i = 0; i < pcmBuffer.length; i++) {
            pcmBuffer[i] = (short) (i * 2);
        }
        samplesRead = 1600;
        bytesRead = samplesRead * 2;
        nativeShortBuffer.clear(); // Must restore limit to capacity (1600)
        assertEquals(1600, nativeShortBuffer.limit());
        nativeShortBuffer.put(pcmBuffer, 0, samplesRead);
        directBuffer.position(0);
        directBuffer.limit(bytesRead);

        assertEquals(0, directBuffer.position());
        assertEquals(3200, directBuffer.limit());
        assertEquals(3200, directBuffer.remaining());
        for (int i = 0; i < samplesRead; i++) {
            assertEquals((short) (i * 2), directBuffer.getShort(i * 2));
        }
    }

    @Test
    public void testShortBufferResilienceAgainstConsumerMutations() {
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(AudioRecordBridge.CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
        java.nio.ShortBuffer nativeShortBuffer = directBuffer.asShortBuffer();
        short[] pcmBuffer = new short[AudioRecordBridge.SAMPLES_PER_CHUNK];

        // Simulate callback mutating position and limit
        directBuffer.position(123);
        directBuffer.limit(456);

        // Next iteration must recover completely
        int samplesRead = 800;
        int bytesRead = samplesRead * 2;
        nativeShortBuffer.clear();
        nativeShortBuffer.put(pcmBuffer, 0, samplesRead);
        directBuffer.position(0);
        directBuffer.limit(bytesRead);

        assertEquals(0, directBuffer.position());
        assertEquals(1600, directBuffer.limit());
        assertEquals(1600, directBuffer.remaining());
    }

    @Test
    public void testShortBufferByteOrderPreservation() {
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(AudioRecordBridge.CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
        java.nio.ShortBuffer nativeShortBuffer = directBuffer.asShortBuffer();
        assertEquals(ByteOrder.nativeOrder(), directBuffer.order());
        assertEquals(ByteOrder.nativeOrder(), nativeShortBuffer.order());

        short[] pcmBuffer = new short[] { (short) 0x1234 };
        nativeShortBuffer.clear();
        nativeShortBuffer.put(pcmBuffer, 0, 1);
        directBuffer.position(0);
        directBuffer.limit(2);

        short readBack = directBuffer.getShort(0);
        assertEquals((short) 0x1234, readBack);
    }
}

