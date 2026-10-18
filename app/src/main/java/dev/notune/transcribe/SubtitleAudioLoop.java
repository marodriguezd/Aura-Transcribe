package dev.notune.transcribe;

/**
 * Pure helpers for {@link LiveSubtitleService}'s system-audio capture loop.
 *
 * <p>The loop is the single owner of its {@code AudioRecord}. A teardown may
 * only release the record when no live loop exists: {@code AudioRecord.stop()}
 * normally unblocks a parked {@code read()}, but a stalled audio HAL can keep
 * that call inside the driver past any bounded join, and releasing the record
 * then would pull the resource from under an in-flight read (an uncaught
 * exception on a plain thread, i.e. a process kill). A live loop keeps
 * ownership and releases the record in its own {@code finally} as soon as the
 * read returns or throws. The decision is a pure function of the owning
 * thread's liveness, so it is covered by JVM tests without a device.
 */
final class SubtitleAudioLoop {

    private SubtitleAudioLoop() {
    }

    /**
     * True when a teardown path may release the AudioRecord itself: no owning
     * loop exists at all, or the loop is confirmed dead. While the thread is
     * alive it owns the release, even if it is currently wedged inside
     * {@code AudioRecord.read()}.
     */
    static boolean mayReleaseRecord(Thread audioThread) {
        return audioThread == null || !audioThread.isAlive();
    }

    /**
     * Converts the first {@code count} PCM 16-bit samples to normalized floats
     * ({@code sample / 32768.0f}) in {@code out}. Only the valid region is
     * written, so the caller can reuse {@code out} across reads of different
     * lengths without the previous, longer read leaking into this one.
     */
    static void toNormalizedFloats(short[] samples, int count, float[] out) {
        // Fast reciprocal multiplication (auto-vectorizable in ART).
        final float invScale = 1.0f / 32768.0f;
        for (int i = 0; i < count; i++) {
            out[i] = samples[i] * invScale;
        }
    }
}
