package dev.notune.transcribe;

import java.util.List;

/**
 * Pure helpers for the audio-file transcription pipeline.
 *
 * <p>They are deliberately free of Android types: the streaming decoder in
 * {@link TranscribeFileActivity} converts one bounded chunk at a time, and these
 * functions are the parts of that pipeline whose behaviour is worth asserting in
 * a JVM unit test (resampling bounds, and how per-chunk transcripts are joined).
 */
final class FileAudioChunks {

    private FileAudioChunks() {
    }

    /**
     * Linear-interpolation resampling of the first {@code length} samples of
     * {@code input}.
     *
     * <p>Chunk-local on purpose: a tiny discontinuity at each chunk boundary is
     * irrelevant to speech recognition, and being stateless is what lets the
     * decoder release one chunk before it decodes the next.
     *
     * @param length     valid sample count in {@code input} (may be shorter than
     *                   the backing array, which is reused across chunks)
     * @param fromRate   source sample rate in Hz
     * @param toRate     target sample rate in Hz
     * @return the resampled samples, or an empty array when the input is empty or
     *         the rates are unusable
     */
    static float[] resample(float[] input, int length, int fromRate, int toRate) {
        if (input == null || length <= 0 || fromRate <= 0 || toRate <= 0) {
            return new float[0];
        }
        // Never read past the caller's valid region.
        int usable = Math.min(length, input.length);
        if (usable <= 0) return new float[0];

        double ratio = (double) fromRate / toRate;
        int outputLength = (int) (usable / ratio);
        if (outputLength <= 0) return new float[0];

        float[] output = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i * ratio;
            int idx = (int) srcIndex;
            double frac = srcIndex - idx;

            if (idx + 1 < usable) {
                output[i] = (float) (input[idx] * (1.0 - frac) + input[idx + 1] * frac);
            } else if (idx < usable) {
                output[i] = input[idx];
            }
        }
        return output;
    }

    /**
     * Whether a decoded stream has already run past the accepted duration.
     *
     * <p>This Activity is exported for {@code audio/*}, so any app can hand it an
     * arbitrarily long file; the cap stops a hostile or buggy input from pinning
     * the CPU for an unbounded time. It is expressed as a duration rather than a
     * raw sample count so it means the same thing at every input sample rate —
     * which is also why the comparison has to be done in floating point: an
     * integer division would silently floor a 30-minute-and-a-bit file back to
     * exactly the cap.
     *
     * @param sourceFrames total decoded frames seen so far (at {@code sampleRate})
     * @param sampleRate   sample rate those frames are expressed in
     * @param maxSeconds   inclusive upper bound on the accepted duration
     * @return true once the decoded duration exceeds {@code maxSeconds}; false for
     *         a nonsensical sample rate, so a malformed stream is reported as a
     *         decode error rather than as "too long"
     */
    static boolean exceedsDurationCap(long sourceFrames, int sampleRate, int maxSeconds) {
        if (sourceFrames <= 0 || sampleRate <= 0 || maxSeconds <= 0) return false;
        return (double) sourceFrames / sampleRate > maxSeconds;
    }

    /**
     * Joins the transcript of each processed chunk into one transcript.
     *
     * <p>Blank pieces are dropped and the rest are separated by a single space,
     * which is the same convention the native engine uses when it stitches the
     * segments of a long recording, so a chunked file reads identically to one
     * transcribed in a single pass.
     */
    static String joinTranscripts(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(trimmed);
        }
        return joined.toString();
    }
}
