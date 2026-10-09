package dev.notune.transcribe;

/**
 * The outcome of an ordered credential read: the credential itself (only when
 * {@link Status#LOADED}) plus what its absence means.
 *
 * <p><b>Why the status matters.</b> A caller that only saw {@code ""} could not
 * tell three different situations apart: the user has not configured
 * post-processing ({@link Status#ABSENT}), the user deleted the credential
 * ({@link Status#ABSENT}, via the deletion tombstone), or something <em>is</em>
 * stored but could not be loaded — corrupt or tampered ciphertext, an unavailable
 * AndroidKeyStore key, an undecodable legacy marker
 * ({@link Status#UNREADABLE}). Treating the last one as "not configured" is how a
 * user with a valid credential gets told to re-enter it, so the distinction is
 * part of the read contract rather than a log line.
 *
 * <p>An in-flight read is not a status: before the callback arrives the caller is
 * in "pending" (the credential lane has not answered yet), which is exactly how a
 * legacy import that is still queued is handled — the read is ordered behind it,
 * so a pending migration never resolves to {@code ABSENT} (see
 * {@link SettingsManager#readApiKey}).
 *
 * <p>Instances are immutable. {@link #toString()} deliberately renders the status
 * only, so a diagnostic that happens to print one never carries key material.
 */
public final class ApiKeyRead {

    /** What a read found. */
    public enum Status {
        /** A usable credential was read. */
        LOADED,
        /** Nothing is stored, or the user deleted it (deletion tombstone). */
        ABSENT,
        /** Something is stored but could not be read; never reported as absent. */
        UNREADABLE
    }

    private static final ApiKeyRead ABSENT = new ApiKeyRead(Status.ABSENT, "");
    private static final ApiKeyRead UNREADABLE = new ApiKeyRead(Status.UNREADABLE, "");

    private final Status status;
    private final String key;

    private ApiKeyRead(Status status, String key) {
        this.status = status;
        this.key = key;
    }

    /**
     * A read that produced a credential. A blank value (null, empty or
     * whitespace-only) is a confirmed absence, not a loaded credential: that is the
     * rule every caller used to spell out as {@code key.trim().isEmpty()}, kept here
     * so "configured" cannot drift between call sites.
     */
    static ApiKeyRead loaded(String key) {
        if (key == null || key.trim().isEmpty()) return ABSENT;
        return new ApiKeyRead(Status.LOADED, key);
    }

    /** Nothing stored, or deleted by the user. */
    static ApiKeyRead absent() {
        return ABSENT;
    }

    /** Storage or key access failed; distinct from a confirmed absence. */
    static ApiKeyRead unreadable() {
        return UNREADABLE;
    }

    public Status status() {
        return status;
    }

    /** The credential, or "" when there is none. Never null. */
    public String key() {
        return key;
    }

    public boolean hasCredential() {
        return status == Status.LOADED;
    }

    @Override
    public String toString() {
        // Never include key material in a diagnostic rendering.
        return "ApiKeyRead[" + status + "]";
    }
}
