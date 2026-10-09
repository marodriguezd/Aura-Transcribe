package dev.notune.transcribe;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The single lane on which every operation that mutates the stored API key runs.
 *
 * <p><b>Why a lane instead of a lock.</b> The credential has three legacy
 * sources and one encrypted store, and the start-up migration walks all of them
 * before writing. With no serialization a migration can read a legacy key, the
 * user can delete the key, and the migration can then write the deleted
 * credential back. A lock would only prevent interleaving; it would leave the
 * outcome to whichever thread grabbed it first. Submitting every mutation to one
 * thread instead makes the order of operations the submission order, so the last
 * operation submitted is the last one applied and the final state is
 * deterministic: a deletion submitted after the import wins, and an update
 * submitted last wins over the imported value.
 *
 * <p><b>Where it runs.</b> Operations run on the injected {@code lane} — a
 * single background thread in production ({@code credential-ops}) — never on the
 * caller's thread. That matters because the settings screen submits from the
 * Android main thread and the migration shares the lane: no Keystore call, file
 * I/O or file lock can end up on the main thread, and nothing waits on the lane
 * by blocking. Completions are delivered on the injected per-submission executor
 * (the main executor for the UI), so a caller can report success only once the
 * operation really has completed.
 *
 * <p><b>Failure.</b> {@link Operation#run()} reports its own outcome; it is
 * expected to convert platform failures into {@code false} rather than throw
 * (see {@code SecureCredentialStore}/{@code CredentialMigration}, which report
 * sanitized diagnostics themselves). A runtime exception escaping a task would
 * otherwise take the lane's worker thread with it and never complete the
 * callback — a caller waiting for it would hang — so it is contained here,
 * reported through the injected sink (class name only; no credential material)
 * and turned into {@code success = false}. Only {@code RuntimeException} is
 * contained: an {@code Error} still surfaces.
 */
final class CredentialOperations {

    /** A credential mutation; returns true when it completed and took effect. */
    interface Operation {
        boolean run();
    }

    /** Receives the outcome of a submitted operation. */
    interface Completion {
        void onCompleted(boolean success);
    }

    private final Executor lane;
    private final Consumer<String> failureReporter;

    CredentialOperations(Executor lane, Consumer<String> failureReporter) {
        if (lane == null || failureReporter == null) {
            throw new IllegalArgumentException("lane and failureReporter are required");
        }
        this.lane = lane;
        this.failureReporter = failureReporter;
    }

    /**
     * Queues {@code operation} behind everything already submitted and reports
     * its result. Returns immediately; never blocks the calling thread.
     *
     * @param callbackExecutor where the completion runs (null = the caller does
     *                         not want a callback; then {@code completion} is ignored)
     * @param completion       outcome receiver, may be null
     */
    void submit(Operation operation, Executor callbackExecutor, Completion completion) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        lane.execute(() -> {
            boolean success;
            try {
                success = operation.run();
            } catch (RuntimeException unexpected) {
                failureReporter.accept("Credential operation failed ("
                        + unexpected.getClass().getSimpleName() + ")");
                success = false;
            }
            if (completion == null || callbackExecutor == null) return;
            boolean result = success;
            callbackExecutor.execute(() -> completion.onCompleted(result));
        });
    }

    /**
     * Queues a value-producing read behind everything already submitted and hands
     * its value to {@code completion} on {@code callbackExecutor}.
     *
     * <p>Same lane, same ordering guarantee as {@link #submit}: a read submitted
     * after a mutation runs after it, so it observes that mutation's result — that
     * is what lets a caller read the credential without racing the queue, and
     * without doing file I/O or a decrypt on its own thread (the Android main
     * thread, for every UI caller).
     *
     * <p>Completion is delivered <b>exactly once</b>. A {@code null} value means the
     * operation threw: it is reported through the failure sink (sanitized) and the
     * consumer must treat it as "no answer", never as "nothing stored".
     *
     * <p>{@code Error} still propagates, as in {@link #submit}.
     */
    <T> void submitValue(Callable<T> operation, Executor callbackExecutor, Consumer<T> completion) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        lane.execute(() -> {
            T value = null;
            try {
                value = operation.call();
            } catch (Exception unexpected) {
                failureReporter.accept("Credential operation failed ("
                        + unexpected.getClass().getSimpleName() + ")");
            }
            if (completion == null || callbackExecutor == null) return;
            T delivered = value;
            callbackExecutor.execute(() -> completion.accept(delivered));
        });
    }
}
