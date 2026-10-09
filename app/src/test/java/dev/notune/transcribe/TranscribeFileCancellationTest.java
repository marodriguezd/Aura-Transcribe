package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Structural guard for the stale-transcription fix in
 * {@code TranscribeFileActivity}: the {@code PostProcessor} it creates must
 * include the operation id in its owner-validity predicate, so a credential
 * read that completes after the operation was cancelled or replaced cannot
 * start a network request with the stale transcript.
 *
 * <p><b>Structural vs behavioural coverage.</b> The reusable policy this
 * relies on — "a read that completes after the owner predicate is false sends
 * no request and delivers no callback" — is behaviourally covered by
 * {@code PostProcessorTest.aDestroyedConsumerGetsNoCallbackWhileTheReadStillCompletes}
 * (the predicate there is liveness; the operation id is the same
 * {@code BooleanSupplier} contract, checked after the read and before the
 * request). What only a structural test can guard is the Activity-level wiring
 * the JVM suite cannot reach (android.jar is stubbed, so the Activity cannot be
 * instantiated): that {@code deliverTranscript} captures the operation id and
 * puts it in the predicate. The full Activity end-to-end (cancel → read
 * completes → no request, newer operation unaffected) is a device-level check.
 */
public class TranscribeFileCancellationTest {

    @Test
    public void deliverTranscriptIncludesTheOperationIdInThePostProcessorPredicate()
            throws Exception {
        String source = readSourceFile();
        String body = extractMethodBody(source, "private void deliverTranscript(");
        assertNotNull("TranscribeFileActivity.deliverTranscript must exist", body);

        assertTrue("deliverTranscript must build a PostProcessor",
                body.contains("new PostProcessor("));

        // The predicate must compare the captured operation id against the
        // current one. The entry guard and the response callbacks use !=
        // (opId != currentOpId); the predicate is the only == currentOpId in the
        // body, so this pins the fix without matching those guards.
        assertTrue("the PostProcessor predicate must include the operation id, so a read"
                        + " that completes after the operation was cancelled or replaced"
                        + " cannot start a request with the stale transcript",
                body.contains("== currentOpId"));
    }

    private static String readSourceFile() throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 6; i++) {
            for (String prefix : new String[]{"app/", ""}) {
                File candidate = new File(dir, prefix + "src/main/java/dev/notune/transcribe/TranscribeFileActivity.java");
                if (candidate.isFile()) {
                    return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
            if (dir == null) break;
        }
        fail("TranscribeFileActivity.java source not found relative to " + System.getProperty("user.dir"));
        return null;
    }

    /** Brace-matched body of the first method whose signature contains
     *  {@code signature}, or null when no such method exists. */
    private static String extractMethodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) return null;
        int open = source.indexOf('{', at);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open + 1, i);
            }
        }
        return null;
    }
}
