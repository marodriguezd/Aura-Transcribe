package dev.notune.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the bounded-memory file-transcription helpers.
 *
 * <p>These matter because the streaming rewrite replaced a full-file decode with
 * per-chunk processing: the resampler now sees a reused backing array and an
 * explicit valid length, so the "do not read past `length`" and "empty input is
 * not an error" cases are the ones a regression would break.
 */
public class FileAudioChunksTest {

    private static final int TARGET_RATE = 16000;

    // ---------------------------------------------------------------- resample

    @Test
    public void resampleUsesOnlyTheValidRegionOfAReusedBuffer() {
        // A 0.75-filled chunk buffer: everything past `valid` belongs to a
        // previous chunk and must never leak into the output.
        float[] buffer = new float[] { 1f, 1f, 1f, 1f, 9f, 9f, 9f, 9f };
        int valid = 4;

        float[] out = FileAudioChunks.resample(buffer, valid, 32000, TARGET_RATE);

        assertEquals(2, out.length);
        for (float sample : out) {
            assertEquals(1f, sample, 0.0001f);
        }
    }

    @Test
    public void resampleHalvesTheSampleCountWhenDownsamplingTwoToOne() {
        float[] input = new float[1600];
        for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin(i * 0.05);

        float[] out = FileAudioChunks.resample(input, input.length, 32000, TARGET_RATE);

        assertEquals(800, out.length);
    }

    @Test
    public void resampleIsIdentityLengthWhenRatesMatch() {
        float[] input = new float[] { 0.1f, 0.2f, 0.3f, 0.4f };

        float[] out = FileAudioChunks.resample(input, input.length, TARGET_RATE, TARGET_RATE);

        assertEquals(input.length, out.length);
        assertArrayEquals(input, out, 0.0001f);
    }

    @Test
    public void resampleProducesEmptyOutputInsteadOfThrowingForDegenerateInput() {
        assertArrayEquals(new float[0], FileAudioChunks.resample(null, 10, 48000, TARGET_RATE), 0f);
        assertArrayEquals(new float[0], FileAudioChunks.resample(new float[10], 0, 48000, TARGET_RATE), 0f);
        assertArrayEquals(new float[0], FileAudioChunks.resample(new float[10], 10, 0, TARGET_RATE), 0f);
        assertArrayEquals(new float[0], FileAudioChunks.resample(new float[10], 10, 48000, 0), 0f);
        assertArrayEquals(new float[0], FileAudioChunks.resample(new float[0], 0, 48000, TARGET_RATE), 0f);
    }

    @Test
    public void resampleClampsWhenTheValidLengthExceedsTheBackingArray() {
        // Defensive: a caller bug must not cause an ArrayIndexOutOfBoundsException
        // on the decode thread.
        float[] input = new float[] { 0.5f, 0.5f };

        float[] out = FileAudioChunks.resample(input, 1000, TARGET_RATE, TARGET_RATE);

        assertEquals(2, out.length);
    }

    @Test
    public void resampleOutputHasNoNaNOrInfiniteSamples() {
        float[] input = new float[48000];
        for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin(i * 0.01);

        float[] out = FileAudioChunks.resample(input, input.length, 44100, TARGET_RATE);

        assertTrue(out.length > 0);
        for (float sample : out) {
            assertTrue("sample must be finite", Float.isFinite(sample));
        }
    }

    @Test
    public void resampleUpsamplesByInsertingInterpolatedSamples() {
        // 8 kHz input (a common voice-note rate) has to be doubled to reach the
        // engine's 16 kHz. Nothing in the pipeline exercised the ratio < 1 path
        // before, even though MediaRecorder-produced files hit it.
        int inputLength = 800;
        float[] input = new float[inputLength];
        for (int i = 0; i < inputLength; i++) input[i] = 0.25f;

        float[] out = FileAudioChunks.resample(input, inputLength, 8000, TARGET_RATE);

        assertEquals(inputLength * 2, out.length);
        for (float sample : out) {
            assertEquals(0.25f, sample, 0.0001f);
        }
    }

    @Test
    public void resampleDoesNotAliasTheReusedChunkBuffer() {
        // The decoder reuses one chunk array for the whole file, so returning a
        // view of the input (or the input itself) would make every already-sent
        // chunk mutate under the engine's feet.
        float[] reused = new float[] { 0.1f, 0.2f, 0.3f, 0.4f };

        float[] out = FileAudioChunks.resample(reused, reused.length, TARGET_RATE, TARGET_RATE);

        assertNotSame(reused, out);
        reused[0] = 0.9f;
        assertEquals(0.1f, out[0], 0.0001f);
    }

    @Test
    public void resampleUsesOnlyTheValidRegionWhenUpsampling() {
        // Same guarantee as the downsampling case, on the other ratio side.
        float[] buffer = new float[] { 0.5f, 0.5f, 0.5f, 0.5f, 9f, 9f, 9f, 9f };

        float[] out = FileAudioChunks.resample(buffer, 4, 8000, TARGET_RATE);

        assertEquals(8, out.length);
        for (float sample : out) {
            assertEquals(0.5f, sample, 0.0001f);
        }
    }

    @Test
    public void resampleTruncatesToWholeSamplesForNonIntegerRatios() {
        // 44.1 kHz CD audio and 48 kHz container audio both land here; the
        // output may be one sample short but must never be long enough to read
        // past the valid region.
        int[] rates = new int[] { 44100, 48000, 32000, 11025 };
        for (int rate : rates) {
            float[] input = new float[rate / 10];
            float[] out = FileAudioChunks.resample(input, input.length, rate, TARGET_RATE);

            double expected = Math.floor(input.length * (double) TARGET_RATE / rate);
            assertEquals(rate + " Hz", (int) expected, out.length);
        }
    }

    @Test
    public void resampleOfAVeryShortChunkDegradesToEmptyInsteadOfThrowing() {
        // The tail of a decode can be a handful of frames; resampling it would
        // produce zero whole samples, and that must be an empty chunk (which
        // transcribeChunk then skips) rather than an exception on the decode
        // thread.
        float[] oneFrame = new float[] { 0.5f };

        assertArrayEquals(new float[0],
                FileAudioChunks.resample(oneFrame, 1, 44100, TARGET_RATE), 0f);
    }

    // ------------------------------------------------------- exceedsDurationCap

    @Test
    public void exceedsDurationCapIsFalseUpToAndIncludingTheCap() {
        assertFalse(FileAudioChunks.exceedsDurationCap(0, 16000, 1800));
        assertFalse(FileAudioChunks.exceedsDurationCap(16000L * 1800, 16000, 1800));
        assertFalse(FileAudioChunks.exceedsDurationCap(16000L * 1799, 16000, 1800));
    }

    @Test
    public void exceedsDurationCapIsTrueOneFramePastTheCap() {
        // One frame past 30 minutes at 16 kHz. An integer division would floor
        // this back to exactly the cap and accept it, so this is the assertion
        // that keeps the check honest.
        assertTrue(FileAudioChunks.exceedsDurationCap(16000L * 1800 + 1, 16000, 1800));
    }

    @Test
    public void exceedsDurationCapScalesWithTheInputSampleRate() {
        // The cap is a duration, not a sample count: 8 kHz audio hits it after
        // half as many frames as 16 kHz audio.
        assertFalse(FileAudioChunks.exceedsDurationCap(8000L * 1800, 8000, 1800));
        assertTrue(FileAudioChunks.exceedsDurationCap(8000L * 1800 + 1, 8000, 1800));
        assertTrue(FileAudioChunks.exceedsDurationCap(96000L * 1800 + 1, 96000, 1800));
    }

    @Test
    public void exceedsDurationCapIgnoresNonsensicalArguments() {
        // A stream that declares a broken rate must surface as a decode error,
        // never as "the file is too long".
        assertFalse(FileAudioChunks.exceedsDurationCap(1L, 0, 1800));
        assertFalse(FileAudioChunks.exceedsDurationCap(1L, -16000, 1800));
        assertFalse(FileAudioChunks.exceedsDurationCap(1L, 16000, 0));
        assertFalse(FileAudioChunks.exceedsDurationCap(-1L, 16000, 1800));
    }

    // ---------------------------------------------------------- joinTranscripts

    @Test
    public void joinTranscriptsSeparatesChunksWithASingleSpace() {
        List<String> parts = Arrays.asList("hello there", "general kenobi", "you are a bold one");

        assertEquals("hello there general kenobi you are a bold one",
                FileAudioChunks.joinTranscripts(parts));
    }

    @Test
    public void joinTranscriptsSkipsSilentChunks() {
        // A chunk of pure silence (or background noise the engine declines to
        // transcribe) reports an empty string; it must not produce a double space
        // or a leading/trailing one.
        List<String> parts = Arrays.asList("first", "", "   ", "second");

        assertEquals("first second", FileAudioChunks.joinTranscripts(parts));
    }

    @Test
    public void joinTranscriptsTrimsEachChunk() {
        List<String> parts = Arrays.asList("  padded  ", "\tTabbed\n");

        assertEquals("padded Tabbed", FileAudioChunks.joinTranscripts(parts));
    }

    @Test
    public void joinTranscriptsHandlesNullAndEmptyInput() {
        assertEquals("", FileAudioChunks.joinTranscripts(null));
        assertEquals("", FileAudioChunks.joinTranscripts(Collections.emptyList()));
        assertEquals("", FileAudioChunks.joinTranscripts(Arrays.asList(null, "", "  ")));
    }

    @Test
    public void joinTranscriptsIgnoresNullElementsBetweenRealChunks() {
        List<String> parts = new ArrayList<>();
        parts.add("alpha");
        parts.add(null);
        parts.add("omega");

        assertEquals("alpha omega", FileAudioChunks.joinTranscripts(parts));
    }

    @Test
    public void joinTranscriptsPreservesChunkOrderForALongFile() {
        // A 30-minute file is ~60 chunks; the transcript must read in decode
        // order, with exactly one space between pieces.
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 60; i++) parts.add("chunk" + i);

        String joined = FileAudioChunks.joinTranscripts(parts);

        assertEquals("chunk0 chunk1", joined.substring(0, "chunk0 chunk1".length()));
        assertTrue(joined.endsWith("chunk59"));
        assertEquals(60, joined.split(" ").length);
    }

    @Test
    public void joinTranscriptsOfASingleChunkIsThatChunkTrimmed() {
        assertEquals("only one",
                FileAudioChunks.joinTranscripts(Collections.singletonList("  only one ")));
    }

    @Test
    public void joinTranscriptsResultNeverStartsWithOrEndsWithSpace() {
        List<String> parts = Arrays.asList("", "  first  ", "", "last", "  ");

        String joined = FileAudioChunks.joinTranscripts(parts);

        assertEquals("first last", joined);
        assertEquals(joined, joined.trim());
    }
}
