package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Terminal-error contract tests for {@link NativeSessionPolicy}.
 *
 * <p><b>Decision coverage.</b> A terminal ("Error...") status must cancel the
 * native session only for the surface that owns an active recording or a
 * pending result; idle surfaces and non-error statuses must leave the native
 * session alone (engine errors are broadcast to every surface, and an idle
 * surface cancelling would end another surface's recording). Repeated errors
 * after the surface cleared its flags must produce no second cancellation.
 *
 * <p><b>Structural wiring guard.</b> The three owning surfaces (IME, popup,
 * floating overlay) must route their error path through the shared policy and
 * call native {@code cancelRecording()} right after it. The JVM suite cannot
 * instantiate these Android components (android.jar is stubbed), so the wiring
 * is pinned by source inspection; the behaviour of the shared decision itself
 * is covered by the pure tests above.
 */
public class NativeSessionPolicyTest {

    @Test
    public void activeRecordingOnTerminalErrorMustCancelNativeSession() {
        assertTrue("a surface holding the capture must end the native session on error",
                NativeSessionPolicy.shouldCancelOnTerminalError(
                        "Error: failed to open microphone: busy", true, false));
    }

    @Test
    public void pendingResultOnTerminalErrorMustCancelNativeSession() {
        assertTrue("a surface waiting for a result must end the native session on error",
                NativeSessionPolicy.shouldCancelOnTerminalError(
                        "Error: Model failed to load", false, true));
    }

    @Test
    public void errorWithBothOwnershipFlagsCancels() {
        assertTrue(NativeSessionPolicy.shouldCancelOnTerminalError(
                "Error: no microphone available. Check permissions.", true, true));
    }

    @Test
    public void idleSurfaceOnTerminalErrorMustNotCancel() {
        assertFalse("a global engine error must not cancel another surface's recording",
                NativeSessionPolicy.shouldCancelOnTerminalError(
                        "Error: model load failed unexpectedly", false, false));
    }

    @Test
    public void nonErrorStatusesNeverCancelEvenWhileActive() {
        String[] statuses = {
                "Ready", "Listening...", "Transcribing...", "Processing...",
                "Waiting for model...", "Loading model...", "Checking assets...",
                "Canceled", ""
        };
        for (String status : statuses) {
            assertFalse("status \"" + status + "\" is not a terminal error",
                    NativeSessionPolicy.shouldCancelOnTerminalError(status, true, true));
        }
        assertFalse("a null status is not a terminal error",
                NativeSessionPolicy.shouldCancelOnTerminalError(null, true, true));
    }

    @Test
    public void repeatedTerminalErrorIsIdempotentForTheSurfaceOnceCancelled() {
        boolean recording = true;
        boolean resultPending = false;

        // First error: the surface owns the session, so it cancels ...
        assertTrue(NativeSessionPolicy.shouldCancelOnTerminalError(
                "Error: transcription failed unexpectedly", recording, resultPending));

        // ... and its error path clears the ownership flags.
        recording = false;
        resultPending = false;

        // A repeated error callback must not cancel a second time.
        assertFalse(NativeSessionPolicy.shouldCancelOnTerminalError(
                "Error: transcription failed unexpectedly", recording, resultPending));
    }

    @Test
    public void anyErrorPrefixStatusIsTerminalForTheOwningSurface() {
        // Matches the surfaces' existing startsWith("Error") classification,
        // including the non-fatal "using built-in model" message: the UI
        // already ends the recording on it, so the native session must end too.
        assertTrue(NativeSessionPolicy.shouldCancelOnTerminalError(
                "Error loading model.gguf: corrupt — using built-in model", true, false));
    }

    @Test
    public void allOwningSurfacesRouteTheErrorThroughTheSharedPolicy() throws Exception {
        assertSurfaceWired("RustInputMethodService.java", "isRecording", "resultPending");
        assertSurfaceWired("RecognizeActivity.java", "isRecording", "resultPending");
        assertSurfaceWired("FloatingOverlayService.java", "mIsRecording", "mResultPending");
    }

    private static void assertSurfaceWired(String filename, String recordingFlag,
            String pendingFlag) throws Exception {
        String source = readSource(filename);
        int at = source.indexOf("NativeSessionPolicy.shouldCancelOnTerminalError(");
        assertTrue(filename + " must decide terminal-error cancellation through NativeSessionPolicy",
                at >= 0);
        String call = source.substring(at, Math.min(source.length(), at + 200));
        assertTrue(filename + " must pass its active-recording flag to the policy",
                call.contains(recordingFlag));
        assertTrue(filename + " must pass its pending-result flag to the policy",
                call.contains(pendingFlag));
        int cancelAt = source.indexOf("cancelRecording()", at);
        assertTrue(filename + " must call native cancelRecording() right after the policy grants it",
                cancelAt > at && cancelAt - at < 500);
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
