package dev.notune.transcribe;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The pure read policy for the post-processing API key: what a caller observes for
 * a given on-disk state.
 *
 * <p><b>Why it is its own class.</b> The reader needs a {@code Context} for
 * {@code filesDir()}, so its decision would otherwise be reachable only from
 * instrumentation tests that never run in CI (AGENTS.md §5.4). The decision itself
 * takes no Android dependency, so it lives here — pure, total and covered by
 * {@code CredentialReadTest}; {@link SettingsManager} keeps the file I/O, the
 * Keystore work and the logging, and runs it on the credential lane so no caller
 * pays for either on its own thread.
 *
 * <p><b>The contract this implements.</b> Reads observe the state left by the last
 * <em>completed</em> credential mutation (the lane has already ordered them):
 * <ul>
 *   <li><b>Deletion wins over every source.</b> A deletion tombstone means the user
 *       deleted the credential and reflects a state at least as new as any copy on
 *       disk, so a read observes "no credential" even while an encrypted file
 *       and/or a legacy copy still exist (an unlink that failed, a marker removal
 *       that could not persist).</li>
 *   <li><b>A stored credential is served as-is.</b> When the encrypted store
 *       exists, its value is the answer; an unreadable or absent value reads as
 *       empty and never falls back to a legacy plaintext copy, so a corrupt or
 *       tampered store cannot silently resurrect an old Base64 credential.</li>
 *   <li><b>The legacy marker is a transition-window fallback only</b>, consulted
 *       only while no encrypted store exists — the interval before the start-up
 *       import of an upgraded installation has completed.</li>
 *   <li><b>Absent is not the same as failed</b> ({@link ApiKeyRead.Status}), so a
 *       caller can tell "not configured" from "could not be loaded".</li>
 * </ul>
 *
 * <p>No method here logs, returns or otherwise exposes key material beyond the
 * credential the caller asked for.
 */
final class CredentialRead {

    private CredentialRead() {
    }

    /**
     * The value a caller should use: the credential, or "" when there is none.
     *
     * @param deletionPending a deletion tombstone is in place
     * @param storeExists     an encrypted store file is present
     * @param storedValue     its decrypted value, or null when there was nothing to
     *                        decrypt (absent/empty) or it could not be read
     * @param legacyValue     the decoded legacy Base64 marker, or null/empty
     * @return the credential to serve, or "" — never null
     */
    static String resolve(boolean deletionPending, boolean storeExists,
                          String storedValue, String legacyValue) {
        if (deletionPending) return "";
        if (storeExists) return storedValue != null ? storedValue : "";
        return legacyValue != null ? legacyValue : "";
    }

    /**
     * Turns the raw legacy Base64 marker into the credential, or null when it
     * cannot be decoded. Injected so this class stays free of
     * {@code android.util.Base64} and is equally usable from the JVM tests.
     */
    interface MarkerDecoder {
        String decode(String encoded);
    }

    /**
     * Gathers the on-disk state and applies the policy: the whole read, minus the
     * Android plumbing.
     *
     * <p><b>Why the gathering lives here too.</b> It is the part of
     * {@code SettingsManager.readApiKeyOnLane()} that decides <em>what</em> is read
     * (the tombstone first, the encrypted store next, the legacy marker only while
     * no store exists) and how a failure is classified. Keeping it in a class with
     * no {@code Context} dependency means the plain-JVM tests drive this exact code
     * over real files and a real store — rather than a mirror that could drift from
     * production — while {@code SettingsManager} keeps only the Android calls.
     *
     * <p>Must run off the caller's thread (file I/O plus, through the store, a
     * Keystore decrypt): production runs it on the credential lane.
     *
     * @param encodedMarker the raw legacy marker content, or null/empty when absent
     * @param onFailure     receives a sanitized diagnostic (never key material)
     */
    static ApiKeyRead gather(File dir, SecureCredentialStore store,
                             Supplier<String> encodedMarker, MarkerDecoder decoder,
                             Consumer<String> onFailure) {
        boolean deletionPending = CredentialMigration.deletionPending(dir);
        boolean storeExists = false;
        String stored = null;
        boolean storageFailed = false;
        if (!deletionPending) {
            // Skipped while a deletion is pending: the tombstone already decides the
            // answer, and a deleted credential must not be read even to look at it.
            storeExists = store.exists();
            if (storeExists) {
                try {
                    stored = store.read();
                } catch (SecureCredentialStore.CredentialStoreException e) {
                    // Corrupt/tampered/unsupported/Keystore-unavailable: an explicit
                    // failure, never a fallback to a plaintext legacy copy. The
                    // message carries no stored bytes.
                    storageFailed = true;
                    onFailure.accept("Stored API key could not be decrypted (" + e.getMessage()
                            + "); re-enter it in post-processing settings");
                }
            }
        }
        String legacy = null;
        if (!deletionPending && !storeExists) {
            // Transition window: the start-up import runs on the credential lane, so
            // a read queued behind it sees the imported store. The marker is
            // consulted only while no encrypted store exists yet, so the common case
            // pays nothing for it.
            String encoded = encodedMarker.get();
            if (encoded != null && !encoded.isEmpty()) {
                legacy = decoder.decode(encoded);
                if (legacy == null) {
                    storageFailed = true;
                    onFailure.accept("Failed to decode API key from marker");
                }
            }
        }
        return result(deletionPending, storeExists, stored, legacy, storageFailed);
    }

    /**
     * The full outcome: the value policy above plus whether a failure was involved.
     *
     * @param storageFailed a source that exists could not be read (ciphertext
     *                      rejected or unreadable, undecodable marker)
     */
    static ApiKeyRead result(boolean deletionPending, boolean storeExists,
                             String storedValue, String legacyValue, boolean storageFailed) {
        String key = resolve(deletionPending, storeExists, storedValue, legacyValue);
        if (!key.isEmpty()) return ApiKeyRead.loaded(key);
        // A pending deletion is a confirmed absence, not a failure: the user asked
        // for the credential to be gone, so nothing may report a read error for it.
        if (deletionPending) return ApiKeyRead.absent();
        return storageFailed ? ApiKeyRead.unreadable() : ApiKeyRead.absent();
    }
}
