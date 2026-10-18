package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Device-free guard for the exception-safety contract of the
 * {@code VoiceRecognitionService} native backend ({@code src/recog_service.rs}).
 *
 * <p>{@code JNIEnv::call_method} reports a Java exception as {@code Err} but
 * does <em>not</em> clear it; a pending exception then breaks every later JNI
 * call on the same thread. In this backend that thread is the cpal audio
 * callback (level updates, beginning-of-speech) and the finaliser (end of
 * speech, results/error), so an uncleared failure can silence the stream and
 * even lose the final text. Every callback must therefore go through
 * {@code jni_util::call_and_clear} / {@code jni_util::clear_exception}, which
 * clear and log; native endpointing state is unaffected by a failed callback.
 *
 * <p><b>What cannot be tested here:</b> the actual JVM/JNI round-trip needs a
 * real {@code JNIEnv} (the Rust crate's tests are blocked by
 * transcribe-cpp-sys, and the JVM suite runs without a native library), so the
 * runtime behaviour is pinned structurally: the seven call sites and the
 * helper that clears them are source-verified. The precise manual check is:
 * trigger each callback on a device (dictate through the system
 * {@code SpeechRecognizer} so onReadyForSpeech/onBeginningOfSpeech/rms/results
 * fire) and confirm recognition completes even if a callback fails.
 */
public class RecogServiceCallbackSafetyTest {

    private static final String[] CALLBACKS = {
            "onRmsChanged",
            "onError",
            "onResults",
            "onPartialText",
            "onReadyForSpeech",
            "onBeginningOfSpeech",
            "onEndOfSpeech",
    };

    @Test
    public void everyNativeCallbackIsRoutedThroughTheExceptionClearingHelper() throws Exception {
        String source = readRustSource("recog_service.rs");
        for (String callback : CALLBACKS) {
            int nameAt = source.indexOf("\"" + callback + "\"");
            assertTrue(callback + " must be invoked from src/recog_service.rs", nameAt >= 0);
            String window = source.substring(Math.max(0, nameAt - 200), nameAt);
            assertTrue(callback + " must be invoked through jni_util::call_and_clear "
                            + "(a pending exception must never reach the next JNI call)",
                    window.contains("jni_util::call_and_clear("));
        }
    }

    @Test
    public void noRawCallMethodRemainsInTheRecognitionServiceBackend() throws Exception {
        String source = readRustSource("recog_service.rs");
        assertFalse("no callback may bypass jni_util: call_method leaves the Java exception pending",
                source.contains(".call_method("));
    }

    @Test
    public void theHelperClearsAndLogsFailures() throws Exception {
        String source = readRustSource("jni_util.rs");
        int helperAt = source.indexOf("pub fn call_and_clear(");
        assertTrue("jni_util must expose call_and_clear", helperAt >= 0);
        String helper = source.substring(helperAt, Math.min(source.length(), helperAt + 700));
        assertTrue("call_and_clear must clear the pending exception before returning",
                helper.contains("clear_pending_exception(env)"));
        assertTrue("call_and_clear must log the failure", helper.contains("log::warn!"));
        assertTrue("the new_string failure path needs an explicit clearer",
                source.contains("pub fn clear_exception("));
    }

    private static String readRustSource(String filename) throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 6; i++) {
            File candidate = new File(dir, "src/" + filename);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
            if (dir == null) break;
        }
        fail(filename + " source not found relative to " + System.getProperty("user.dir"));
        return null;
    }
}
