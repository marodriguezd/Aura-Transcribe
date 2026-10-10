package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Structural guard and session-validity contract tests for ASR session identifiers
 * and JNI callback UI-thread confinement.
 *
 * <p><b>Structural field publication guard:</b>
 * JNI callbacks originated from native background threads (e.g. cpal audio thread,
 * decode threads, silence monitor) and asynchronous PostProcessor credential readers
 * read {@code currentSessionId} and {@code mCurrentSessionId} across thread boundaries.
 * These identifiers are written and incremented on the Android main UI thread. Declaring
 * them as {@code volatile int} across all surfaces (IME, Floating Overlay, Popup Recognize,
 * and Voice Recognition Service) ensures immediate visibility across threads under the
 * Java Memory Model without stale caching or memory reordering.
 * This structural test protects the {@code volatile} modifier against accidental regression;
 * it does not claim to simulate hardware-level thread scheduling or test device-level JNI
 * runtime behavior on a plain JVM runner.
 *
 * <p><b>Session-validity and callback thread confinement contract:</b>
 * Verifies that stale session callbacks and superseded operations are dropped before
 * enqueuing redundant work, and that queued updates to destroyed owners are safely rejected
 * upon execution.
 */
public class SessionIdVisibilityTest {

    @Test
    public void rustInputMethodServiceSessionIdIsVolatile() throws Exception {
        Field field = RustInputMethodService.class.getDeclaredField("currentSessionId");
        assertNotNull("RustInputMethodService.currentSessionId field must exist", field);
        assertEquals("currentSessionId must be of type int", int.class, field.getType());
        assertTrue("RustInputMethodService.currentSessionId must be volatile to prevent stale JMM reads in JNI callbacks",
                Modifier.isVolatile(field.getModifiers()));
    }

    @Test
    public void floatingOverlayServiceSessionIdIsVolatile() throws Exception {
        Field field = FloatingOverlayService.class.getDeclaredField("mCurrentSessionId");
        assertNotNull("FloatingOverlayService.mCurrentSessionId field must exist", field);
        assertEquals("mCurrentSessionId must be of type int", int.class, field.getType());
        assertTrue("FloatingOverlayService.mCurrentSessionId must be volatile to prevent stale JMM reads in JNI callbacks",
                Modifier.isVolatile(field.getModifiers()));
    }

    @Test
    public void recognizeActivitySessionIdIsVolatile() throws Exception {
        Field field = RecognizeActivity.class.getDeclaredField("currentSessionId");
        assertNotNull("RecognizeActivity.currentSessionId field must exist", field);
        assertEquals("currentSessionId must be of type int", int.class, field.getType());
        assertTrue("RecognizeActivity.currentSessionId must be volatile to prevent stale JMM reads in JNI callbacks",
                Modifier.isVolatile(field.getModifiers()));
    }

    @Test
    public void voiceRecognitionServiceSessionIdIsVolatile() throws Exception {
        Field field = VoiceRecognitionService.class.getDeclaredField("currentSessionId");
        assertNotNull("VoiceRecognitionService.currentSessionId field must exist", field);
        assertEquals("currentSessionId must be of type int", int.class, field.getType());
        assertTrue("VoiceRecognitionService.currentSessionId must be volatile to prevent stale JMM reads in JNI callbacks",
                Modifier.isVolatile(field.getModifiers()));
    }

    @Test
    public void sessionValidityPredicateRejectsStaleOrIncrementedSessions() {
        AtomicInteger activeSession = new AtomicInteger(1);
        final int capturedSession = 1;

        // Models the exact predicate used across all surfaces:
        // () -> capturedSessionId == currentSessionId && ...
        BooleanSupplier activePredicate = () -> capturedSession == activeSession.get();

        // Valid while session matches
        assertTrue("Active session must evaluate to valid", activePredicate.getAsBoolean());

        // Invalidated when session ID advances (e.g. cancelled, new recording, or destroyed)
        activeSession.incrementAndGet();
        assertFalse("Stale session must immediately evaluate to invalid once active session increments",
                activePredicate.getAsBoolean());
    }

    @Test
    public void audioLevelFilterDropsStaleOrDestroyedUpdatesBeforePosting() {
        AtomicInteger activeSession = new AtomicInteger(1);
        List<Float> executorQueue = new ArrayList<>();

        // Reusable audio-level dispatch gate matching the production pattern across
        // RustInputMethodService, FloatingOverlayService, and RecognizeActivity:
        java.util.function.BiConsumer<Float, Integer> onAudioLevel = (level, sessionId) -> {
            if (sessionId != activeSession.get()) return;
            executorQueue.add(level);
        };

        // Active session: queued
        onAudioLevel.accept(0.5f, 1);
        assertEquals(1, executorQueue.size());

        // Stale session ID: dropped before enqueuing work
        onAudioLevel.accept(0.8f, 0);
        assertEquals("Stale session update must not be enqueued", 1, executorQueue.size());

        // Destroyed owner increments session ID: dropped before enqueuing work
        activeSession.incrementAndGet();
        onAudioLevel.accept(0.9f, 1);
        assertEquals("Destroyed owner update must not be enqueued", 1, executorQueue.size());
    }

    @Test
    public void queuedCallbackRechecksSessionAndDestroyedStateAtExecutionTime() {
        AtomicInteger activeSession = new AtomicInteger(1);
        AtomicBoolean isDestroyed = new AtomicBoolean(false);
        AtomicReference<String> deliveredText = new AtomicReference<>(null);

        final int callbackSessionId = 1;
        Runnable queuedTask = () -> {
            if (callbackSessionId != activeSession.get() || isDestroyed.get()) return;
            deliveredText.set("transcribed result");
        };

        // Session was superseded while task sat in queue
        activeSession.set(2);
        queuedTask.run();
        assertEquals("Superseded session must not mutate state", null, deliveredText.get());

        // Reset and test destroyed owner while task was in queue
        activeSession.set(1);
        isDestroyed.set(true);
        queuedTask.run();
        assertEquals("Destroyed owner must not receive callback execution", null, deliveredText.get());

        // Active session with live owner executes correctly
        isDestroyed.set(false);
        queuedTask.run();
        assertEquals("Active session with live owner must execute successfully",
                "transcribed result", deliveredText.get());
    }

    @Test
    public void doubleCheckPipelineDropsStaleBeforeAndAfterQueue() {
        AtomicInteger currentSession = new AtomicInteger(1);
        AtomicBoolean isDestroyed = new AtomicBoolean(false);
        List<Runnable> queue = new ArrayList<>();
        AtomicReference<String> output = new AtomicReference<>(null);

        // Dispatches with pre-check and post-check
        java.util.function.BiConsumer<String, Integer> dispatch = (text, sessionId) -> {
            if (sessionId != currentSession.get() || isDestroyed.get()) return;
            queue.add(() -> {
                if (sessionId != currentSession.get() || isDestroyed.get()) return;
                output.set(text);
            });
        };

        // 1. Stale session at dispatch: never enqueued
        dispatch.accept("stale-text", 0);
        assertEquals("Stale dispatch must not be enqueued", 0, queue.size());

        // 2. Destroyed at dispatch: never enqueued
        isDestroyed.set(true);
        dispatch.accept("destroyed-text", 1);
        assertEquals("Destroyed dispatch must not be enqueued", 0, queue.size());

        // 3. Valid at dispatch, but destroyed while queued: dropped at execution
        isDestroyed.set(false);
        dispatch.accept("valid-text", 1);
        assertEquals(1, queue.size());
        isDestroyed.set(true);
        queue.remove(0).run();
        assertEquals("Dropped at execution when destroyed", null, output.get());

        // 4. Valid at dispatch, but superseded while queued: dropped at execution
        isDestroyed.set(false);
        dispatch.accept("superseded-text", 1);
        assertEquals(1, queue.size());
        currentSession.set(2);
        queue.remove(0).run();
        assertEquals("Dropped at execution when superseded", null, output.get());

        // 5. Active session throughout: completes successfully
        dispatch.accept("final-success", 2);
        assertEquals(1, queue.size());
        queue.remove(0).run();
        assertEquals("Delivered for active session", "final-success", output.get());
    }

    @Test
    public void recognizeActivityHasEarlySessionCheckInAudioLevel() throws Exception {
        String source = readSource("RecognizeActivity.java");
        int methodIndex = source.indexOf("public void onAudioLevel(float level, int sessionId)");
        assertTrue("RecognizeActivity must declare onAudioLevel(float, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("runOnUiThread");

        assertTrue("onAudioLevel must check sessionId != currentSessionId before runOnUiThread",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void voiceRecognitionServiceHasEarlySessionCheckInRmsChanged() throws Exception {
        String source = readSource("VoiceRecognitionService.java");
        int methodIndex = source.indexOf("public void onRmsChanged(float rmsdB, int sessionId)");
        assertTrue("VoiceRecognitionService must declare onRmsChanged(float, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mainHandler.post");

        assertTrue("onRmsChanged must check sessionId != currentSessionId before mainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void rustInputMethodServiceHasEarlySessionCheckInOnTextTranscribed() throws Exception {
        String source = readSource("RustInputMethodService.java");
        int methodIndex = source.indexOf("public void onTextTranscribed(String text, int sessionId)");
        assertTrue("RustInputMethodService must declare onTextTranscribed", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mainHandler.post");

        assertTrue("onTextTranscribed must check session before mainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void floatingOverlayServiceHasEarlySessionCheckInOnTextTranscribed() throws Exception {
        String source = readSource("FloatingOverlayService.java");
        int methodIndex = source.indexOf("public void onTextTranscribed(String text, int sessionId)");
        assertTrue("FloatingOverlayService must declare onTextTranscribed", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != mCurrentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mMainHandler.post");

        assertTrue("onTextTranscribed must check session before mMainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void rustInputMethodServiceHasEarlySessionCheckInAudioLevel() throws Exception {
        String source = readSource("RustInputMethodService.java");
        int methodIndex = source.indexOf("public void onAudioLevel(float level, int sessionId)");
        assertTrue("RustInputMethodService must declare onAudioLevel(float, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mainHandler.post");

        assertTrue("onAudioLevel must check session before mainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void floatingOverlayServiceHasEarlySessionCheckInAudioLevel() throws Exception {
        String source = readSource("FloatingOverlayService.java");
        int methodIndex = source.indexOf("public void onAudioLevel(float level, int sessionId)");
        assertTrue("FloatingOverlayService must declare onAudioLevel(float, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != mCurrentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mMainHandler.post");

        assertTrue("onAudioLevel must check session before mMainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void recognizeActivityHasEarlySessionCheckInOnTextTranscribed() throws Exception {
        String source = readSource("RecognizeActivity.java");
        int methodIndex = source.indexOf("public void onTextTranscribed(String text, int sessionId)");
        assertTrue("RecognizeActivity must declare onTextTranscribed(String, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("runOnUiThread");

        assertTrue("onTextTranscribed must check sessionId != currentSessionId before runOnUiThread",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }

    @Test
    public void voiceRecognitionServiceHasEarlySessionCheckInOnResults() throws Exception {
        String source = readSource("VoiceRecognitionService.java");
        int methodIndex = source.indexOf("public void onResults(String text, int sessionId)");
        assertTrue("VoiceRecognitionService must declare onResults(String, int)", methodIndex >= 0);

        String methodBody = source.substring(methodIndex, source.indexOf('}', methodIndex) + 200);
        int guardIndex = methodBody.indexOf("if (sessionId != currentSessionId) return;");
        int dispatchIndex = methodBody.indexOf("mainHandler.post");

        assertTrue("onResults must check sessionId != currentSessionId before mainHandler.post",
                guardIndex >= 0 && guardIndex < dispatchIndex);
    }


    @Test
    public void audioLevelSessionReplacementDrainsStaleWorkAndDeliversActiveSessionMeterUpdate() {
        AtomicInteger activeSession = new AtomicInteger(1);
        AtomicBoolean isDestroyed = new AtomicBoolean(false);
        List<Runnable> mainQueue = new ArrayList<>();
        AtomicReference<Float> currentMeterLevel = new AtomicReference<>(0.0f);

        // Production dispatch logic matching RustInputMethodService / RecognizeActivity / FloatingOverlayService
        java.util.function.BiConsumer<Float, Integer> onAudioLevel = (level, sessionId) -> {
            if (sessionId != activeSession.get()) return;
            mainQueue.add(() -> {
                if (sessionId == activeSession.get() && !isDestroyed.get()) {
                    currentMeterLevel.set(level);
                }
            });
        };

        // 1. Session 1 receives audio level and queues UI runnable
        onAudioLevel.accept(0.42f, 1);
        assertEquals("Session 1 update should be enqueued", 1, mainQueue.size());

        // 2. Session 1 is cancelled and Session 2 starts before queue executes
        activeSession.set(2);

        // 3. Callback from stale Session 1 is rejected before enqueue
        onAudioLevel.accept(0.55f, 1);
        assertEquals("Stale Session 1 callback must be rejected before enqueue", 1, mainQueue.size());

        // 4. Session 2 receives audio level and queues UI runnable
        onAudioLevel.accept(0.85f, 2);
        assertEquals("Session 2 update should be enqueued", 2, mainQueue.size());

        // 5. Drain the queue in FIFO order
        // First runnable (Session 1): executes after session became stale -> safely dropped, meter unchanged
        Runnable staleRunnable = mainQueue.remove(0);
        staleRunnable.run();
        assertEquals("Stale Session 1 runnable must not update the meter", 0.0f, currentMeterLevel.get(), 0.001f);

        // Second runnable (Session 2): executes normally -> updates meter
        Runnable activeRunnable = mainQueue.remove(0);
        activeRunnable.run();
        assertEquals("Active Session 2 runnable must deliver meter update", 0.85f, currentMeterLevel.get(), 0.001f);

        // 6. Active Session 2 continues updating responsively after stale drain
        onAudioLevel.accept(0.92f, 2);
        assertEquals(1, mainQueue.size());
        mainQueue.remove(0).run();
        assertEquals("Active Session 2 continues responsive updates", 0.92f, currentMeterLevel.get(), 0.001f);

        // 7. Verify destruction: owner destroyed increments active session and sets isDestroyed
        onAudioLevel.accept(0.99f, 2);
        assertEquals(1, mainQueue.size());
        isDestroyed.set(true);
        activeSession.incrementAndGet();
        mainQueue.remove(0).run();
        assertEquals("Destroyed owner must not receive UI meter update", 0.92f, currentMeterLevel.get(), 0.001f);

        // 8. Subsequent callbacks after destruction are rejected before enqueue via volatile session ID
        onAudioLevel.accept(0.10f, 2);
        assertEquals("Destroyed owner must reject callbacks before enqueue", 0, mainQueue.size());
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
