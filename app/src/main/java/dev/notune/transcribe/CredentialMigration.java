package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;

/**
 * Orchestrates the one-time (and retried) import of the post-processing API key
 * from every historical storage location into the encrypted
 * {@link SecureCredentialStore}.
 *
 * <p><b>Why this class exists.</b> The import rules are not "read the old value
 * and write the new one": three legacy sources can coexist, the encrypted store
 * is authoritative unless it cannot be decrypted, nothing may be deleted before
 * the replacement has been verified, and a failure at any step must leave the
 * user's credential recoverable. Those rules used to live inside
 * {@code SettingsManager}, where the {@code Context}/{@code SharedPreferences}/
 * {@code EncryptedSharedPreferences} dependencies made them reachable only from
 * instrumentation tests that never run in CI (AGENTS.md §5.4). The decision
 * logic lives here against the injected {@link LegacySources} seam, so it is
 * covered by plain JVM tests ({@code CredentialMigrationTest}); the Android
 * implementation is supplied by {@code SettingsManager}.
 *
 * <p><b>Precedence</b> (newest source first): legacy Base64 marker → legacy
 * {@code EncryptedSharedPreferences} → plain {@code SharedPreferences}. A marker
 * is never overwritten by an older prefs value, and the encrypted store is never
 * overwritten by a legacy copy while it still decrypts.
 *
 * <p><b>Write before delete.</b> Every import goes through
 * {@link SecureCredentialStore#store}, which encrypts, persists atomically and
 * verifies the round-trip before returning. Legacy copies are removed only after
 * that. A removal that fails is reported and retried on the next start instead of
 * being silently forgotten — and it never turns into "prefer the stale plaintext
 * over the verified encrypted value", because the encrypted copy is already
 * authoritative once it has been verified.
 *
 * <p><b>Sentinel semantics.</b> The {@code pp_api_key_migrated} file records what
 * the last pass found:
 * <ul>
 *   <li><em>absent or empty</em> — "checked, nothing to import". An empty sentinel
 *       deliberately does <b>not</b> suppress a legacy copy that shows up later:
 *       a downgraded build can write one after the sentinel exists, and that
 *       credential is real. The Base64 marker has always had this rule; the
 *       prefs sources now have it too.</li>
 *   <li><em>{@code deleted}</em> — a deletion tombstone, written by
 *       {@link #forget()} <b>before</b> it removes anything and for as long as an
 *       explicit user deletion is unfinished. It outranks every source, in both
 *       directions: the import path refuses to re-import any copy while it is
 *       present, and readers ({@link CredentialRead}) serve "no credential" for
 *       its whole lifetime. So a deleted credential can neither resurrect itself
 *       through a copy that reappears nor stay readable from a copy whose removal
 *       failed — the encrypted store included, where an unlink that fails leaves
 *       the credential readable, which {@code forget()} reports rather than
 *       hides. It is cleared once every copy is actually gone, or by
 *       {@link #store} when the user stores a new credential (which supersedes
 *       the deletion; a tombstone must never hide a key stored after it).</li>
 * </ul>
 *
 * <p>No method here logs, returns or otherwise exposes key material.
 */
final class CredentialMigration {

    /** Sentinel file in {@code filesDir()}; see the class doc for its values. */
    static final String SENTINEL_NAME = "pp_api_key_migrated";

    /** Sentinel content meaning "the user deleted the key; never re-import". */
    private static final String TOMBSTONE = "deleted";

    /**
     * The legacy (pre-encrypted-store) homes of the API key, injected so the
     * orchestration above is exercised by JVM tests with in-memory doubles.
     * Production passes the Android implementation ({@code SettingsManager}),
     * which reads the same files the app really has on disk.
     */
    interface LegacySources {
        /** Whether the legacy Base64 marker exists (cheap file probe). */
        boolean hasMarker();

        /** Removes the legacy Base64 marker; true when it is gone afterwards. */
        boolean deleteMarker();

        /** Whether the legacy {@code EncryptedSharedPreferences} file exists. */
        boolean hasLegacyEncrypted();

        /** Reads the legacy {@code EncryptedSharedPreferences} key; never throws. */
        LegacyRead readLegacyEncrypted();

        /** Removes the legacy encrypted key; true when the key is gone afterwards. */
        boolean deleteLegacyEncrypted();

        /** Whether the plain legacy prefs still hold a key (cheap, in-memory probe). */
        boolean hasLegacyPlain();

        /** The plain legacy key, or null/empty when it holds none. */
        String legacyPlain();

        /**
         * Removes the plain legacy key.
         *
         * @return the {@code commit()} result (and an empty re-read); false means
         *         the plaintext copy is still on disk and must be retried
         */
        boolean deleteLegacyPlain();
    }

    /**
     * Result of reading the legacy {@code EncryptedSharedPreferences}.
     *
     * <p>{@code completed} distinguishes "opened and read (possibly empty)" from
     * "could not be opened at all" — the latter must not be mistaken for "no key
     * there", or a transient Keystore failure would permanently skip the import.
     */
    static final class LegacyRead {
        final boolean completed;
        final String key;

        LegacyRead(boolean completed, String key) {
            this.completed = completed;
            this.key = key;
        }

        /** The store could not be opened (e.g. Keystore unavailable). */
        static LegacyRead unavailable() {
            return new LegacyRead(false, null);
        }
    }

    /** Diagnostics sink. Callers pass {@code Log}; tests collect the messages. */
    interface Diagnostics {
        void warn(String message);

        void error(String message);
    }

    /** What one migration pass achieved. */
    enum Outcome {
        /** The encrypted store is present and readable, and no legacy copy remained. */
        UP_TO_DATE,
        /** A legacy credential was encrypted, verified, and every legacy copy removed. */
        MIGRATED,
        /** No credential exists in any source. */
        NOTHING_TO_IMPORT,
        /**
         * The credential itself is safe (the encrypted store holds it), but a
         * legacy copy survived the cleanup. The next pass retries the removal.
         */
        LEGACY_COPY_RETAINED,
        /**
         * This pass could not complete without risking the credential (Keystore
         * unavailable, unreadable legacy data, failed write). Every legacy copy is
         * preserved, so the next start retries.
         */
        DEFERRED
    }

    private final SecureCredentialStore store;
    private final LegacySources legacy;
    private final File filesDir;
    private final File sentinel;
    private final Diagnostics diagnostics;

    CredentialMigration(SecureCredentialStore store, LegacySources legacy,
                        File filesDir, Diagnostics diagnostics) {
        if (store == null || legacy == null || filesDir == null || diagnostics == null) {
            throw new IllegalArgumentException("store, legacy, filesDir and diagnostics are required");
        }
        this.store = store;
        this.legacy = legacy;
        this.filesDir = filesDir;
        this.sentinel = new File(filesDir, SENTINEL_NAME);
        this.diagnostics = diagnostics;
    }

    /**
     * Runs one migration pass. Safe and cheap to call on every process start:
     * the common case (verified encrypted store, no legacy copy left) touches no
     * Keystore key and rewrites nothing.
     *
     * @return what this pass achieved; never throws
     */
    Outcome migrate() {
        // 1. A deletion tombstone is settled first, before any source is even
        //    considered: it means the user deleted this credential and the removal
        //    did not finish. Nothing may be re-imported (the tombstone outranks
        //    every source, the Base64 marker included) and readers serve "no
        //    credential" while it is present, so this pass finishes the deletion
        //    instead of stopping at it. Only the tombstone does this — an empty
        //    "checked" sentinel must never suppress a copy that appears later
        //    (class doc).
        if (isDeletionTombstone()) {
            diagnostics.warn("A deleted API key is still present in storage;"
                    + " finishing the deletion instead of re-importing it");
            return forget() ? Outcome.UP_TO_DATE : Outcome.DEFERRED;
        }

        // 2. The encrypted store is authoritative whenever it still decrypts.
        if (store.exists()) {
            if (!anyLegacyCopyPresent()) {
                // Nothing to reconcile: do not pay for a Keystore-backed decrypt
                // on a normal start. A corrupt store is reported explicitly by
                // the ordered read (SettingsManager#readApiKey) when it runs;
                // there is no legacy copy to fall
                // back to either, so reporting here would change nothing.
                return Outcome.UP_TO_DATE;
            }
            if (storedCredentialIsReadable()) {
                // A verified encrypted credential wins over every legacy copy
                // (downgrade leftovers, or a cleanup that failed earlier).
                return deleteLegacyCopies()
                        ? Outcome.UP_TO_DATE
                        : Outcome.LEGACY_COPY_RETAINED;
            }
            diagnostics.warn("Encrypted API key could not be read;"
                    + " the newest legacy copy will be used if one exists");
        }

        // 3. Legacy Base64 marker — the most recent pre-encryption format.
        //
        // Deliberately not gated by the (empty) sentinel: a marker written by a
        // downgraded build after the sentinel existed must still be converted
        // instead of living on as Base64 forever. When the encrypted store is
        // present but unreadable, this also rebuilds it (write is verified
        // before the marker is dropped).
        SecureCredentialStore.MigrationOutcome marker = store.migrateLegacyMarkerIfNeeded();
        if (marker == SecureCredentialStore.MigrationOutcome.MIGRATED) {
            // The marker was the newest source, so any prefs-era copy left behind
            // is stale by definition: remove it in the same pass, and report it if
            // that removal did not persist.
            return deleteLegacyCopies()
                    ? Outcome.MIGRATED
                    : Outcome.LEGACY_COPY_RETAINED;
        }
        if (marker == SecureCredentialStore.MigrationOutcome.LEGACY_UNREADABLE) {
            // Preserve what cannot be read and never guess from an older source:
            // the marker is the most recent format, so a stale prefs key must not
            // overwrite it. Retried on the next start (one cheap file read).
            diagnostics.error("Legacy API key marker is unreadable; leaving it in place");
            return Outcome.DEFERRED;
        }
        if (marker == SecureCredentialStore.MigrationOutcome.FAILED) {
            // Encryption unavailable (e.g. Keystore failure): the legacy marker is
            // still the only copy. Keep it; the next start retries.
            diagnostics.warn("API key encryption unavailable; legacy credential preserved for retry");
            return Outcome.DEFERRED;
        }

        // 4. Prefs-era sources: EncryptedSharedPreferences (v0.1.19–v0.1.21),
        //    then plain SharedPreferences (earliest).
        if (!legacy.hasLegacyEncrypted() && !legacy.hasLegacyPlain()) {
            touchSentinel();
            return Outcome.NOTHING_TO_IMPORT;
        }

        LegacyRead encryptedRead = legacy.readLegacyEncrypted();
        if (!encryptedRead.completed) {
            // The legacy encrypted store exists but could not be opened (e.g. a
            // transient Keystore failure). Do NOT fall back to the older plaintext
            // copy: once that value had been imported the newer encrypted copy
            // would be removed as "stale", destroying the credential the user
            // most recently used. Defer instead, so a later start retries.
            diagnostics.warn("Legacy encrypted API key store unavailable; will retry");
            return Outcome.DEFERRED;
        }
        String key = firstNonEmpty(encryptedRead.key,
                legacy.hasLegacyPlain() ? legacy.legacyPlain() : null);
        if (key == null) {
            // Every legacy store was readable and holds no key: record that the
            // (Keystore-backed) probe found nothing.
            touchSentinel();
            return Outcome.NOTHING_TO_IMPORT;
        }

        try {
            store.store(key);
        } catch (SecureCredentialStore.CredentialStoreException e) {
            // Nothing was written and no legacy copy removed: the credential
            // survives for a later retry (or manual re-entry).
            diagnostics.warn("API key encryption unavailable; legacy credential preserved for retry");
            return Outcome.DEFERRED;
        }
        // store() verified the round-trip, so the encrypted copy is now the
        // authority; the legacy copies are only cleanup from here on.
        return deleteLegacyCopies()
                ? Outcome.MIGRATED
                : Outcome.LEGACY_COPY_RETAINED;
    }

    /**
     * Applies an explicit user deletion of the API key.
     *
     * <p>Removes the encrypted store and every legacy copy. The tombstone is
     * written <b>first</b> and kept until no copy is left, so the deletion is
     * atomic for every observer: from the moment it exists, readers serve "no
     * credential" ({@link CredentialRead}) and the import path refuses to
     * re-import. Removing the store before the marker used to leave a window in
     * which a read found no encrypted store and answered with the very Base64
     * credential the user had just deleted.
     *
     * @return true only when nothing is left: the encrypted store and every
     *         legacy copy were actually removed. A false result means the
     *         deletion is unfinished (the caller must not report it as done);
     *         the credential is nevertheless gone as far as every reader is
     *         concerned, and a later pass finishes the removal
     */
    boolean forget() {
        writeTombstone();
        boolean complete = store.delete();
        if (!deleteLegacyCopies()) complete = false;
        if (complete) {
            clearTombstone();
        }
        return complete;
    }

    /**
     * Stores {@code key} as the new credential, verifying the write, and
     * reconciles what a verified store supersedes: the deletion tombstone and the
     * legacy copies.
     *
     * <p>The tombstone must not outlive the credential the user has just stored —
     * readers serve "no credential" while one is present ({@link CredentialRead}) —
     * so clearing it belongs in this same operation rather than in a later pass,
     * which is what makes "store a new credential after a deletion whose removal
     * failed" observable immediately. It is cleared only after the write has been
     * verified: a failed write leaves the deletion in force.
     *
     * @return true when the credential is durably stored
     */
    boolean store(String key) {
        try {
            store.store(key);
        } catch (SecureCredentialStore.CredentialStoreException e) {
            // Sanitized: the message carries the failure mode, never material.
            diagnostics.error("API key could not be encrypted (" + e.getMessage()
                    + "); nothing was persisted");
            return false;
        }
        clearTombstoneIfSet();
        deleteLegacyCopies();
        return true;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private boolean anyLegacyCopyPresent() {
        return legacy.hasMarker() || legacy.hasLegacyEncrypted() || legacy.hasLegacyPlain();
    }

    private boolean storedCredentialIsReadable() {
        try {
            return store.read() != null;
        } catch (SecureCredentialStore.CredentialStoreException e) {
            // Diagnostic only; the message carries no stored bytes (see
            // SecureCredentialStore).
            diagnostics.warn("Encrypted API key unreadable (" + e.getMessage() + ")");
            return false;
        }
    }

    /**
     * Removes every legacy copy.
     *
     * @return true only when no copy is known to remain; a false result makes the
     *         caller report a retained copy, and the next pass retries
     */
    private boolean deleteLegacyCopies() {
        boolean complete = true;
        if (legacy.hasMarker() && !legacy.deleteMarker()) {
            diagnostics.warn("Legacy API key marker could not be removed; will retry");
            complete = false;
        }
        if (legacy.hasLegacyEncrypted() && !legacy.deleteLegacyEncrypted()) {
            diagnostics.warn("Legacy encrypted API key copy could not be removed; will retry");
            complete = false;
        }
        if (legacy.hasLegacyPlain() && !legacy.deleteLegacyPlain()) {
            // The commit() failed: the plaintext key is still on disk, so the
            // deletion/import is not finished. Retried on the next start.
            diagnostics.warn("Legacy plaintext API key could not be removed; will retry");
            complete = false;
        }
        return complete;
    }

    /**
     * Whether a deletion tombstone is in place — the user deleted the credential
     * and the removal has not finished, so every read must observe "no
     * credential" (see {@link CredentialRead}).
     *
     * <p>Static and Keystore-free on purpose: the reader path must be able to ask
     * this without a {@code CredentialMigration} instance, without a decrypt and
     * without depending on an available key.
     */
    static boolean deletionPending(File filesDir) {
        return TOMBSTONE.equals(
                MarkerFileHelper.readStringFromFile(filesDir, SENTINEL_NAME, null));
    }

    private boolean isDeletionTombstone() {
        return deletionPending(filesDir);
    }

    private void writeTombstone() {
        MarkerFileHelper.writeStringToFile(filesDir, SENTINEL_NAME, TOMBSTONE);
    }

    /** Drops a deletion tombstone that is no longer needed. */
    private void clearTombstoneIfSet() {
        if (isDeletionTombstone()) clearTombstone();
    }

    /** Leaves an empty "checked" sentinel in place of a tombstone. */
    private void clearTombstone() {
        if (sentinel.exists() && !sentinel.delete()) {
            diagnostics.warn("API key migration sentinel could not be reset");
            return;
        }
        touchSentinel();
    }

    /** Records "checked, nothing to import" (best effort, as before). */
    private void touchSentinel() {
        if (sentinel.exists()) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            sentinel.createNewFile();
        } catch (IOException ignored) {
            // Best effort: worst case the (cheap, Keystore-free) probe repeats.
        }
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) return first;
        if (second != null && !second.isEmpty()) return second;
        return null;
    }
}
