package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Orchestration tests for {@link CredentialMigration}: the source precedence, the
 * write-before-delete rule, the failure/retry paths, the sentinel semantics and
 * the deletion guarantee.
 *
 * <p>{@link SecureCredentialStoreTest} covers the store's crypto and file
 * behaviour; this suite covers the part that used to live inside
 * {@code SettingsManager} behind Android-only dependencies (and therefore had no
 * JVM coverage at all). The store, the marker file and the sentinel are the real
 * implementations over a temp directory; only the {@code SharedPreferences} and
 * {@code EncryptedSharedPreferences} sources are in-memory doubles, so the
 * production import path is what runs here apart from the Keystore key itself.
 */
public class CredentialMigrationTest {

    private static final String MARKER_SECRET = "sk-legacy-marker_secret_1111111";
    private static final String ENCRYPTED_PREFS_SECRET = "gsk-legacy-encrypted-prefs_2222";
    private static final String PLAIN_PREFS_SECRET = "sk-legacy-plain-prefs_3333333";
    private static final String CURRENT_SECRET = "sk-current-encrypted-store_4444";
    private static final String SAVED_SECRET = "sk-saved-from-settings_5555555";

    private File dir;
    private SecretKey key;
    private CountingKeyProvider provider;
    private FakeLegacy legacy;
    private RecordingDiagnostics diagnostics;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("credential-migration-test-").toFile();
        key = newAesKey();
        provider = new CountingKeyProvider(key);
        legacy = new FakeLegacy(dir);
        diagnostics = new RecordingDiagnostics();
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    // ------------------------------------------------------------------
    // Source precedence
    // ------------------------------------------------------------------

    @Test
    public void base64MarkerWinsWhenAllThreeLegacySourcesConflict() {
        writeLegacyMarker(MARKER_SECRET);
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals(MARKER_SECRET, readStore());
        assertFalse("marker must be removed after the verified write", markerPresent());
        assertNull("stale encrypted-prefs copy must be removed", legacy.encryptedPrefsKey);
        assertNull("stale plaintext prefs copy must be removed", legacy.plainPrefsKey);
    }

    @Test
    public void legacyEncryptedPrefsWinWhenTheyConflictWithPlainPrefs() {
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals(ENCRYPTED_PREFS_SECRET, readStore());
        assertNull(legacy.encryptedPrefsKey);
        assertNull(legacy.plainPrefsKey);
    }

    @Test
    public void plainPrefsAreImportedWhenTheyAreTheOnlyLegacySource() {
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals(PLAIN_PREFS_SECRET, readStore());
        assertNull(legacy.plainPrefsKey);
    }

    @Test
    public void markerMigratesWhenThereIsNoPrefsSourceAtAll() {
        writeLegacyMarker(MARKER_SECRET);

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals(MARKER_SECRET, readStore());
        assertFalse(markerPresent());
    }

    // ------------------------------------------------------------------
    // Write before delete / failure + retry
    // ------------------------------------------------------------------

    @Test
    public void encryptionFailurePreservesTheLegacyMarkerAndRetriesLater() {
        writeLegacyMarker(MARKER_SECRET);
        provider.broken = true;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        // Still the only copy, byte for byte, and nothing partial was written.
        assertTrue(markerPresent());
        assertEquals(1, provider.calls);
        assertFalse(storeFile().exists());

        // A later start with a working Keystore completes the import.
        provider.broken = false;
        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        assertEquals(MARKER_SECRET, readStore());
        assertFalse(markerPresent());
    }

    @Test
    public void failedReadBackVerificationDoesNotRemoveTheLegacyMarker() {
        writeLegacyMarker(MARKER_SECRET);
        // Encrypt once (call 1); the read-back verification (call 2) fails. The
        // write may well have landed — what must not happen is dropping the legacy
        // copy on the strength of an unverified write.
        provider.failOnCall = 2;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        assertTrue("legacy marker must survive an unverified write", markerPresent());
        assertFalse("no value may be reported as stored", storeBytes().contains(MARKER_SECRET));

        // Next start: the encrypted copy is verified and the marker is dropped.
        provider.failOnCall = -1;
        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());
        assertEquals(MARKER_SECRET, readStore());
        assertFalse(markerPresent());
    }

    @Test
    public void runtimeKeyProviderFailureDefersTheMigrationAndKeepsTheLegacyCopy() {
        writeLegacyMarker(MARKER_SECRET);
        // A provider in a bad state throws unchecked: it must reach the migration
        // as the typed failure it already handles, not as a crash on the start-up
        // thread.
        provider.runtimeFailure = true;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        assertTrue("the legacy copy is still the only credential", markerPresent());
        assertEquals(1, provider.calls);
        assertFalse(storeFile().exists());
        for (String message : diagnostics.messages) {
            assertFalse("diagnostic leaked the credential: " + message,
                    message.contains(MARKER_SECRET));
            assertFalse("diagnostic leaked the encoded credential: " + message,
                    message.contains(base64(MARKER_SECRET)));
        }
    }

    @Test
    public void legacyEncryptedStoreThatCannotBeOpenedIsRetriedInsteadOfSkipped() {
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.encryptedPrefsReadable = false;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        assertEquals("legacy key must be preserved", ENCRYPTED_PREFS_SECRET,
                legacy.encryptedPrefsKey);
        assertNull(readStore());
        assertFalse("a Keystore failure must not be recorded as 'nothing to import'",
                sentinelPresent());

        // Transient failure over: the next start imports it.
        legacy.encryptedPrefsReadable = true;
        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        assertEquals(ENCRYPTED_PREFS_SECRET, readStore());
        assertNull(legacy.encryptedPrefsKey);
    }

    @Test
    public void unreadableNewerSourceNeverFallsBackToTheOlderPlaintextCopy() {
        // Both prefs-era copies exist; the newer one (EncryptedSharedPreferences)
        // cannot be opened right now. Importing the older plaintext value would
        // then delete the newer copy as "stale", destroying the credential.
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.encryptedPrefsReadable = false;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        assertNull(readStore());
        assertEquals(PLAIN_PREFS_SECRET, legacy.plainPrefsKey);
        assertEquals(ENCRYPTED_PREFS_SECRET, legacy.encryptedPrefsKey);
    }

    @Test
    public void legacyCopyThatCannotBeRemovedIsReportedAndRetried() {
        // The audit's explicit case: a successful encrypted write followed by an
        // unsuccessful SharedPreferences.commit().
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;
        legacy.plainCommitFails = true;

        assertEquals(CredentialMigration.Outcome.LEGACY_COPY_RETAINED, migration().migrate());

        // The verified encrypted copy is authoritative; the plaintext is reported
        // as retained rather than silently assumed gone.
        assertEquals(PLAIN_PREFS_SECRET, readStore());
        assertEquals(PLAIN_PREFS_SECRET, legacy.plainPrefsKey);
        assertTrue(hasDiagnosticContaining("could not be removed"));

        // The next start retries the removal and finishes the job.
        legacy.plainCommitFails = false;
        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());
        assertNull(legacy.plainPrefsKey);
        assertEquals(PLAIN_PREFS_SECRET, readStore());
    }

    @Test
    public void failedUpdateKeepsThePreviousCredentialAndTheRecoverableCopy() throws Exception {
        store().store(CURRENT_SECRET);
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET; // older copy, must not win
        provider.broken = true;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        // Nothing was overwritten with the older value, and nothing was deleted.
        provider.broken = false;
        assertEquals(CURRENT_SECRET, readStore());
        assertEquals(PLAIN_PREFS_SECRET, legacy.plainPrefsKey);
    }

    // ------------------------------------------------------------------
    // Idempotency, sentinel and precedence of the encrypted store
    // ------------------------------------------------------------------

    @Test
    public void repeatedMigrationDoesNotReEncryptOrLoseTheCredential() {
        writeLegacyMarker(MARKER_SECRET);
        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        String storedOnce = storeBytes();
        assertEquals(MARKER_SECRET, readStore());
        // Counted from here on: readStore() above uses the Keystore itself.
        int callsAfterFirstPass = provider.calls;

        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());
        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());

        assertEquals("ciphertext must not be rewritten by a no-op pass",
                storedOnce, storeBytes());
        assertEquals("a pass with nothing to reconcile must not touch the Keystore",
                callsAfterFirstPass, provider.calls);
    }

    @Test
    public void verifiedStoreWithNoLegacyCopyNeedsNoKeystoreAccess() throws Exception {
        store().store(CURRENT_SECRET);
        provider.calls = 0;

        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());

        assertEquals(0, provider.calls);
        assertEquals(CURRENT_SECRET, readStore());
    }

    @Test
    public void verifiedStoreWinsOverEveryStaleLegacyCopy() throws Exception {
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, migration().migrate());

        assertEquals("the encrypted credential must not be replaced by a downgrade copy",
                CURRENT_SECRET, readStore());
        assertFalse(markerPresent());
        assertNull(legacy.encryptedPrefsKey);
        assertNull(legacy.plainPrefsKey);
    }

    @Test
    public void corruptStoreIsRebuiltFromTheLegacyMarker() {
        corruptStore();
        writeLegacyMarker(MARKER_SECRET);

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals(MARKER_SECRET, readStore());
        assertFalse(markerPresent());
    }

    @Test
    public void corruptStoreIsRebuiltFromThePrefsCopyInsteadOfLosingIt() {
        // The regression this pass was opened for: the previous code reported the
        // migration "done" as soon as pp_api_key_enc existed, so a corrupt file
        // plus a recoverable copy ended with the copy deleted and no credential
        // anywhere.
        corruptStore();
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertEquals("the credential must survive a corrupt store", PLAIN_PREFS_SECRET, readStore());
        assertNull(legacy.plainPrefsKey);
    }

    @Test
    public void corruptStoreWithOnlyUnreadableLegacyDataDestroysNothing() {
        corruptStore();
        String corruptBytes = storeBytes();
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.encryptedPrefsReadable = false;

        assertEquals(CredentialMigration.Outcome.DEFERRED, migration().migrate());

        assertEquals("must not be wiped while a legacy copy is unreadable",
                corruptBytes, storeBytes());
        assertEquals(ENCRYPTED_PREFS_SECRET, legacy.encryptedPrefsKey);
    }

    @Test
    public void aRecordedCheckDoesNotSuppressALaterPlainPrefsCopy() {
        // Fresh install: nothing anywhere, and the pass records that it checked.
        assertEquals(CredentialMigration.Outcome.NOTHING_TO_IMPORT, migration().migrate());
        assertTrue(sentinelPresent());
        assertFalse(sentinelIsTombstone());

        // A downgraded build writes the key afterwards. That credential is real,
        // so the sentinel must not swallow it.
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        assertEquals(PLAIN_PREFS_SECRET, readStore());
        assertNull(legacy.plainPrefsKey);
    }

    @Test
    public void aRecordedCheckDoesNotSuppressALaterBase64Marker() {
        assertEquals(CredentialMigration.Outcome.NOTHING_TO_IMPORT, migration().migrate());

        writeLegacyMarker(MARKER_SECRET);

        assertEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        assertEquals(MARKER_SECRET, readStore());
    }

    // ------------------------------------------------------------------
    // Explicit deletion
    // ------------------------------------------------------------------

    @Test
    public void deletionRemovesTheStoreAndEveryLegacyCopy() throws Exception {
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        migration().forget();

        assertNull(readStore());
        assertFalse(markerPresent());
        assertNull(legacy.encryptedPrefsKey);
        assertNull(legacy.plainPrefsKey);
        assertFalse("nothing left to block", sentinelIsTombstone());

        // And a later pass cannot bring the deleted key back.
        assertEquals(CredentialMigration.Outcome.NOTHING_TO_IMPORT, migration().migrate());
        assertNull(readStore());
    }

    @Test
    public void deletedCredentialCannotComeBackThroughTheBase64MarkerEither() {
        writeLegacyMarker(MARKER_SECRET);
        legacy.deleteMarkerFails = true; // the marker removal cannot persist

        migration().forget();

        assertTrue(markerPresent());
        assertTrue(sentinelIsTombstone());

        // The marker is the newest legacy format and its import path is normally
        // never gated by the sentinel — but a tombstone must outrank it, or a
        // deletion whose cleanup failed would resurrect itself as Base64.
        legacy.deleteMarkerFails = false;
        assertNotEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertNull(readStore());
        assertFalse(markerPresent());
        assertFalse(sentinelIsTombstone());
    }

    @Test
    public void deletedCredentialCannotComeBackWhenItsCleanupFailed() {
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;
        legacy.plainCommitFails = true; // the removal cannot persist

        migration().forget();

        assertFalse(storeFile().exists());
        assertTrue("an undeletable copy must leave a tombstone", sentinelIsTombstone());

        // Even once the copy is removable, it must never be imported as the
        // deleted credential — the pass only finishes the deletion.
        legacy.plainCommitFails = false;
        assertNotEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());

        assertNull(readStore());
        assertNull("cleanup retried to completion", legacy.plainPrefsKey);
        assertFalse("tombstone cleared once nothing is recoverable", sentinelIsTombstone());
    }

    @Test
    public void deletionReportsSuccessOnlyOnceNothingIsLeft() throws Exception {
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertTrue("a completed deletion must report success", migration().forget());
        assertFalse(sentinelIsTombstone());
    }

    @Test
    public void anUndeletableEncryptedStoreIsReportedAsAFailedDeletion() throws Exception {
        // A store path that exists but cannot be unlinked: a non-empty directory
        // is the portable way to make File.delete() fail. The credential is still
        // on disk, so claiming the deletion succeeded would be false, and letting
        // a later pass re-import a legacy copy would undo the user's deletion.
        File storePath = storeFile();
        assertTrue(storePath.mkdirs());
        assertTrue(new File(storePath, "keep").createNewFile());
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        assertFalse("an unremoved credential must not be reported as deleted",
                migration().forget());
        assertTrue("nothing was removed, so re-import stays blocked", sentinelIsTombstone());
        assertNotEquals(CredentialMigration.Outcome.MIGRATED, migration().migrate());
        assertNull("the legacy copy is not silently promoted", legacy.plainPrefsKey);

        // Clear the obstruction: the next deletion completes for real and lifts
        // the tombstone, because a credential written afterwards is legitimate.
        assertTrue(new File(storePath, "keep").delete());
        assertTrue(storePath.delete());
        assertTrue("with nothing left the deletion is done", migration().forget());
        assertFalse(sentinelIsTombstone());
    }

    // ------------------------------------------------------------------
    // What a read observes (linearizable read contract)
    // ------------------------------------------------------------------

    @Test
    public void aReadAfterACompletedUpdateObservesTheNewCredential() throws Exception {
        store().store(CURRENT_SECRET);

        assertTrue("the save must succeed", migration().store(SAVED_SECRET));

        assertEquals(SAVED_SECRET, readThroughProductionPolicy());
        // Stable across repeated reads: nothing stale resurfaces.
        assertEquals(SAVED_SECRET, readThroughProductionPolicy());
    }

    @Test
    public void aCompletedDeletionIsVisibleToReads() throws Exception {
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);

        assertTrue(migration().forget());

        assertFalse(sentinelIsTombstone());
        assertEquals("a deleted credential must not be readable", "", readThroughProductionPolicy());
        assertEquals("", readThroughProductionPolicy());
    }

    @Test
    public void aDeletedCredentialIsNotReadableWhenACopySurvives() throws Exception {
        // The removal fails, so the Base64 marker is still on disk holding the key
        // the user just deleted. The deletion is reported as unfinished — and it
        // must not be served back either, which is exactly what a reader that
        // consulted the legacy copy without the tombstone used to do.
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.deleteMarkerFails = true;

        assertFalse("the physical removal cannot be reported as complete",
                migration().forget());
        assertTrue(sentinelIsTombstone());
        assertTrue("the legacy copy really did survive", markerPresent());
        assertTrue(CredentialMigration.deletionPending(dir));
        assertEquals("a deleted credential must not be readable",
                "", readThroughProductionPolicy());

        // Once the removal succeeds, the tombstone is lifted and nothing is
        // readable: the deletion is complete in both senses.
        legacy.deleteMarkerFails = false;
        assertTrue(migration().forget());
        assertFalse(markerPresent());
        assertFalse(CredentialMigration.deletionPending(dir));
        assertEquals("", readThroughProductionPolicy());
    }

    @Test
    public void deletionIsObservableBeforeAnyCopyIsRemoved() throws Exception {
        // Tombstone-first: the deletion is visible to a read at every instant of
        // the removal, not only once it has finished. The probe runs inside the
        // marker removal, i.e. after the encrypted store has already been deleted
        // — precisely the window in which a read used to find no store and answer
        // with the deleted Base64 credential.
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.probeDuringRemoval = this::readThroughProductionPolicy;

        assertTrue(migration().forget());

        assertEquals("the deletion must be visible to a read while it is running",
                "", legacy.markerRemovalSaw);
        assertEquals("", readThroughProductionPolicy());
    }

    @Test
    public void storingANewCredentialAfterAFailedDeletionIsImmediatelyReadable()
            throws Exception {
        // The inverse hazard of the tombstone gate: a credential stored after a
        // deletion must not be hidden by the leftover tombstone. The production
        // save path clears it in the same operation as the verified write.
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        legacy.deleteMarkerFails = true;
        assertFalse(migration().forget());
        assertEquals("", readThroughProductionPolicy());

        legacy.deleteMarkerFails = false;
        assertTrue("the save must succeed", migration().store(SAVED_SECRET));

        assertFalse("the tombstone must not outlive the new credential",
                CredentialMigration.deletionPending(dir));
        assertEquals(SAVED_SECRET, readThroughProductionPolicy());
        assertFalse("the superseded legacy copy is cleaned up", markerPresent());
    }

    @Test
    public void aFailedStoreCannotMakeAReadObserveTheDeletedCredential() throws Exception {
        // The composite state a failed deletion leaves: the tombstone is in place
        // while the encrypted store is still decryptable AND a legacy copy survives.
        // Both are readable on their own, so only the tombstone can make a read
        // report "no credential".
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        MarkerFileHelper.writeStringToFile(dir, CredentialMigration.SENTINEL_NAME, "deleted");
        assertTrue("the store really is still there and readable",
                CURRENT_SECRET.equals(readStore()));
        assertTrue(markerPresent());
        assertEquals("a deleted credential is never served", "",
                readThroughProductionPolicy());

        // The keystore then breaks before the new value can be encrypted: the save
        // must report failure and the deletion must stay in force. A failed write
        // must never surface the old credential (or a half-written one) as though it
        // had succeeded, and must not lift the tombstone.
        String ciphertextBefore = storeBytes();
        provider.broken = true;
        assertFalse("an unencryptable save is a failure", migration().store(SAVED_SECRET));

        assertTrue("the deletion is still in force", CredentialMigration.deletionPending(dir));
        assertEquals("", readThroughProductionPolicy());
        assertEquals("the failed write overwrote nothing", ciphertextBefore, storeBytes());
    }

    @Test
    public void aPassThatFindsADeletionTombstoneFinishesTheDeletionInsteadOfServingTheStore()
            throws Exception {
        // The state a deletion leaves when its physical removal failed, i.e. the
        // tombstone in place while the encrypted store is still decryptable and a
        // legacy copy survives. The next pass must finish the deletion. It must NOT
        // read the still-decryptable store as "the user stored a new credential
        // after deleting" and lift the tombstone — that resurrects the deleted
        // credential for every reader.
        store().store(CURRENT_SECRET);
        writeLegacyMarker(MARKER_SECRET);
        MarkerFileHelper.writeStringToFile(dir, CredentialMigration.SENTINEL_NAME, "deleted");

        assertEquals("a deleted credential is never served", "",
                readThroughProductionPolicy());

        migration().migrate();

        assertFalse("the deletion is finished, so nothing blocks any more",
                CredentialMigration.deletionPending(dir));
        assertFalse("the surviving store copy was removed", storeFile().exists());
        assertFalse(markerPresent());
        assertEquals("", readThroughProductionPolicy());
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    @Test
    public void diagnosticsNeverExposeCredentialMaterial() {
        corruptStore();
        legacy.encryptedPrefsFilePresent = true;
        legacy.encryptedPrefsKey = ENCRYPTED_PREFS_SECRET;
        legacy.encryptedPrefsReadable = false;
        legacy.plainPrefsKey = PLAIN_PREFS_SECRET;

        CredentialMigration.Outcome outcome = migration().migrate();

        assertFalse("the pass should report why it stopped", diagnostics.messages.isEmpty());
        assertTrue("nothing may be reported as imported", outcome == CredentialMigration.Outcome.DEFERRED);
        for (String message : diagnostics.messages) {
            for (String secret : new String[] {
                    MARKER_SECRET, ENCRYPTED_PREFS_SECRET, PLAIN_PREFS_SECRET, CURRENT_SECRET}) {
                assertFalse("diagnostic leaked the credential: " + message,
                        message.contains(secret));
                assertFalse("diagnostic leaked the encoded credential: " + message,
                        message.contains(base64(secret)));
            }
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private SecureCredentialStore store() {
        return new SecureCredentialStore(dir, provider);
    }

    private CredentialMigration migration() {
        return migration(store());
    }

    private CredentialMigration migration(SecureCredentialStore store) {
        return new CredentialMigration(store, legacy, dir, diagnostics);
    }

    private String readStore() {
        try {
            return store().read();
        } catch (SecureCredentialStore.CredentialStoreException e) {
            throw new AssertionError("the encrypted store should be readable", e);
        }
    }

    private File storeFile() {
        return new File(dir, SecureCredentialStore.FILE_NAME);
    }

    private String storeBytes() {
        try {
            return new String(Files.readAllBytes(storeFile().toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void corruptStore() {
        MarkerFileHelper.writeStringToFile(dir, SecureCredentialStore.FILE_NAME,
                "v1:broken:payload");
    }

    private void writeLegacyMarker(String plaintext) {
        MarkerFileHelper.writeStringToFile(dir, SecureCredentialStore.LEGACY_MARKER_FILE,
                base64(plaintext));
    }

    private boolean markerPresent() {
        File file = new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE);
        return file.isFile() && file.length() > 0;
    }

    private boolean sentinelPresent() {
        return new File(dir, CredentialMigration.SENTINEL_NAME).exists();
    }

    /**
     * The on-disk state as the production read gathers it ({@link
     * CredentialRead#gather}, the same code {@code SettingsManager.readApiKeyOnLane()}
     * runs). Using the real store, the real marker file and the real tombstone keeps
     * this a test of the production decision, not of a re-implementation.
     *
     * <p>A read failure is served as an empty value (never as a fallback to the
     * legacy copy); the {@code ApiKeyRead} status that keeps it distinguishable from
     * an absence is asserted in {@code CredentialReadTest} and
     * {@code CredentialOperationsTest}.
     */
    private String readThroughProductionPolicy() {
        return CredentialRead.gather(
                dir,
                store(),
                () -> MarkerFileHelper.readStringFromFile(
                        dir, SecureCredentialStore.LEGACY_MARKER_FILE, null),
                encoded -> {
                    try {
                        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                },
                diagnostics::warn).key();
    }

    private boolean sentinelIsTombstone() {
        return "deleted".equals(MarkerFileHelper.readStringFromFile(
                dir, CredentialMigration.SENTINEL_NAME, null));
    }

    private boolean hasDiagnosticContaining(String needle) {
        for (String message : diagnostics.messages) {
            if (message.contains(needle)) return true;
        }
        return false;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static SecretKey newAesKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Counts key acquisitions and can be made to fail on demand. */
    private static final class CountingKeyProvider implements SecureCredentialStore.KeyProvider {
        private final SecretKey key;
        int calls;
        int failOnCall = -1;
        boolean broken;
        /** Throws an unchecked provider-state failure instead of a checked one. */
        boolean runtimeFailure;

        CountingKeyProvider(SecretKey key) {
            this.key = key;
        }

        @Override
        public SecretKey get() throws GeneralSecurityException {
            calls++;
            if (runtimeFailure) {
                throw new IllegalStateException("keystore in a bad state");
            }
            if (broken || calls == failOnCall) {
                throw new KeyStoreException("keystore unavailable");
            }
            return key;
        }
    }

    /** Collects diagnostics so a test can assert on what a log would print. */
    private static final class RecordingDiagnostics implements CredentialMigration.Diagnostics {
        final List<String> messages = new ArrayList<>();

        @Override
        public void warn(String message) {
            messages.add("warn: " + message);
        }

        @Override
        public void error(String message) {
            messages.add("error: " + message);
        }
    }

    /**
     * In-memory {@code SharedPreferences}/{@code EncryptedSharedPreferences} era
     * copies. The Base64 marker stays a real file in {@code dir} because the
     * credential store reads and removes that path itself, so a double would not
     * exercise the production code.
     */
    private static final class FakeLegacy implements CredentialMigration.LegacySources {
        private final File dir;

        boolean encryptedPrefsFilePresent;
        String encryptedPrefsKey;
        boolean encryptedPrefsReadable = true;
        boolean deleteEncryptedFails;
        boolean deleteMarkerFails;

        /** Probe run inside the marker removal, before the marker is dropped. */
        java.util.function.Supplier<String> probeDuringRemoval;
        String markerRemovalSaw;

        String plainPrefsKey;
        boolean plainCommitFails;

        FakeLegacy(File dir) {
            this.dir = dir;
        }

        @Override
        public boolean hasMarker() {
            File file = new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE);
            return file.isFile() && file.length() > 0;
        }

        @Override
        public boolean deleteMarker() {
            if (probeDuringRemoval != null) {
                markerRemovalSaw = probeDuringRemoval.get();
            }
            if (deleteMarkerFails) return false;
            MarkerFileHelper.writeStringToFile(dir, SecureCredentialStore.LEGACY_MARKER_FILE, null);
            return !hasMarker();
        }

        @Override
        public boolean hasLegacyEncrypted() {
            return encryptedPrefsFilePresent;
        }

        @Override
        public CredentialMigration.LegacyRead readLegacyEncrypted() {
            if (!encryptedPrefsFilePresent) {
                return new CredentialMigration.LegacyRead(true, null);
            }
            if (!encryptedPrefsReadable) {
                return CredentialMigration.LegacyRead.unavailable();
            }
            return new CredentialMigration.LegacyRead(true, emptyToNull(encryptedPrefsKey));
        }

        @Override
        public boolean deleteLegacyEncrypted() {
            if (deleteEncryptedFails) return false;
            encryptedPrefsKey = null;
            return true;
        }

        @Override
        public boolean hasLegacyPlain() {
            return emptyToNull(plainPrefsKey) != null;
        }

        @Override
        public String legacyPlain() {
            return plainPrefsKey;
        }

        @Override
        public boolean deleteLegacyPlain() {
            if (plainCommitFails) return false;
            plainPrefsKey = null;
            return true;
        }
    }
}
