package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Teardown-race contract tests for {@link SubtitleAudioLoop}, plus a structural
 * guard that {@link LiveSubtitleService} wires them in.
 *
 * <p><b>Decision coverage.</b> A teardown may only release the AudioRecord
 * when no live owning loop exists. A stalled audio HAL can keep
 * {@code AudioRecord.read()} parked past any bounded join, and releasing the
 * record then would pull the resource from under the in-flight read — an
 * uncaught exception on a plain thread, i.e. a process kill. The decision is
 * pure (a function of the owning thread's liveness), so it is covered here
 * with real {@link Thread} objects; the JVM suite cannot instantiate an
 * {@code AudioRecord} (android.jar is stubbed), so the device-level race
 * itself is verified by the manual check described below.
 *
 * <p><b>Structural wiring guard.</b> The stop path must route the release
 * through the shared decision, the owning loop must release in its
 * {@code finally}, and the loop's I/O must be wrapped so a race cannot kill
 * the process. These are pinned by source inspection, like the other wiring
 * guards in this suite.
 *
 * <p><b>Manual/device check (not automatable here):</b> start live subtitles
 * on a device (API 29+), stop them while audio is playing, and confirm no
 * crash and no {@code "Audio read stopped"} log unless the HAL genuinely
 * stalls; repeat start/stop rapidly to exercise the teardown path.
 */
public class SubtitleAudioLoopTest {

    // --- mayReleaseRecord: the release-ownership decision -------------------

    @Test
    public void noOwningLoopMeansTheStopPathOwnsTheRelease() {
        assertTrue("a capture that never started has no loop to protect",
                SubtitleAudioLoop.mayReleaseRecord(null));
    }

    @Test
    public void liveLoopKeepsRecordOwnershipUntilItIsConfirmedDead() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread audioThread = new Thread(() -> {
            parked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        audioThread.start();
        assertTrue(parked.await(2, TimeUnit.SECONDS));
        try {
            assertFalse("a live loop may still be inside read(); the stop path must not release",
                    SubtitleAudioLoop.mayReleaseRecord(audioThread));
        } finally {
            release.countDown();
            audioThread.join(2000);
        }
        assertTrue("once the loop is dead the stop path owns the release",
                SubtitleAudioLoop.mayReleaseRecord(audioThread));
    }

    @Test
    public void neverStartedThreadIsNotALiveLoop() {
        assertTrue("a thread object whose start() never ran owns nothing",
                SubtitleAudioLoop.mayReleaseRecord(new Thread()));
    }

    // --- Sample conversion used by the loop ---------------------------------

    @Test
    public void conversionScalesOnlyTheValidRegion() {
        short[] samples = {0, 32767, (short) -32768, 16384, 12345};
        float[] out = {-1f, -1f, -1f, -1f, -1f};

        SubtitleAudioLoop.toNormalizedFloats(samples, 3, out);

        assertEquals(0f, out[0], 0f);
        assertEquals(32767f / 32768f, out[1], 0.0001f);
        assertEquals(-1f, out[2], 0f);
        assertEquals("samples past the read count must not leak into the push",
                -1f, out[3], 0f);
        assertEquals("samples past the read count must not leak into the push",
                -1f, out[4], 0f);
    }

    @Test
    public void conversionRewritesOnlyTheValidPrefixWhenReusingTheBuffer() {
        // Mirrors the loop: `read` shrinks between calls and the same float
        // buffer is reused. The loop pushes exactly `read` samples, so a stale
        // tail of a previous longer read can never leak into the engine.
        short[] samples = new short[4];
        float[] reused = new float[4];
        for (int i = 0; i < 4; i++) {
            samples[i] = 16384; // 0.5 full scale
        }
        SubtitleAudioLoop.toNormalizedFloats(samples, 4, reused);
        assertEquals(0.5f, reused[3], 0f);

        samples[0] = 0;
        samples[1] = 0;
        SubtitleAudioLoop.toNormalizedFloats(samples, 2, reused);
        assertEquals(0f, reused[0], 0f);
        assertEquals(0f, reused[1], 0f);
        assertEquals("untouched beyond the new read length", 0.5f, reused[2], 0f);
    }

    // --- Structural wiring guards -------------------------------------------

    @Test
    public void stopPathNeverReleasesTheRecordOutsideTheOwnershipCheck() throws Exception {
        String stop = methodBody(readSource("LiveSubtitleService.java"),
                "void stopSubtitleSession()");
        assertTrue("the stop path must decide the release through SubtitleAudioLoop",
                stop.contains("SubtitleAudioLoop.mayReleaseRecord("));
        assertFalse("the stop path must not release the record unconditionally "
                        + "(the owning loop may still be inside read())",
                stop.contains(".release()"));
    }

    @Test
    public void owningLoopReleasesInFinallyAndSurvivesTeardownRaces() throws Exception {
        String loop = methodBody(readSource("LiveSubtitleService.java"), "void audioLoop()");
        assertTrue("the owning loop must release its record in its finally block",
                loop.contains("finally") && loop.contains("releaseRecord(record)"));
        assertTrue("a read() that throws during teardown must be caught, not kill the thread",
                loop.contains("catch (Throwable"));
        assertFalse("the loop must never re-read the mutable field inside the run",
                loop.contains("mAudioRecord.read("));
    }

    @Test
    public void isRecordingIsVolatileForCrossThreadTeardown() throws Exception {
        Field field = LiveSubtitleService.class.getDeclaredField("isRecording");
        assertEquals("isRecording must remain a boolean", boolean.class, field.getType());
        assertTrue("the audio loop must observe the stop request without a stale JIT read",
                Modifier.isVolatile(field.getModifiers()));
    }

    // --- Source helpers ------------------------------------------------------

    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(signature + " must exist in LiveSubtitleService.java", at >= 0);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        fail("unbalanced body for " + signature);
        return null;
    }

    private static String readSource(String filename) throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 6; i++) {
            for (String prefix : new String[]{"app/", ""}) {
                File candidate = new File(dir, prefix + "src/main/java/dev/notune/transcribe/" + filename);
                if (candidate.isFile()) {
                    return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
            if (dir == null) break;
        }
        fail(filename + " source not found relative to " + System.getProperty("user.dir"));
        return null;
    }
}
