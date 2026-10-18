package dev.notune.transcribe;

/**
 * Terminal-error contract shared by the surfaces that own a native recording
 * session (IME, popup, floating overlay).
 *
 * <p>Engine errors are broadcast to every surface, but only the surface that
 * owns an active recording or a pending result may end the native session. An
 * idle surface calling {@code cancelRecording()} would discard another
 * surface's live recording (single process, one shared engine). The surfaces
 * keep their own state, so the decision is a pure function of one status and
 * that surface's ownership flags.
 */
final class NativeSessionPolicy {

    private NativeSessionPolicy() {
    }

    /**
     * True when a terminal error status must cancel this surface's native
     * session: the status starts with "Error" (matching every other error
     * check in the surfaces) and the surface owns an active recording or a
     * pending result. Idle surfaces and non-error statuses never cancel.
     */
    static boolean shouldCancelOnTerminalError(String status, boolean recording,
            boolean resultPending) {
        if (status == null || !status.startsWith("Error")) {
            return false;
        }
        return recording || resultPending;
    }
}
