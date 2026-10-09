package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Serialization tests for {@link CredentialOperations}: the lane that orders the
 * start-up legacy import, a save from the settings screen and an explicit
 * deletion.
 *
 * <p>Every interleaving is forced with latches — the legacy read pauses until the
 * test releases it, and the lane being single-threaded is what makes the deletion
 * submitted meanwhile queue behind the import. No test depends on a sleep, and
 * reads only ever assert on states the lane produced. Latches also carry a
 * generous timeout, but purely as a deadlock guard: the assertions never rely on
 * elapsed time.
 *
 * <p>As everywhere else in this suite, the store, the migration coordinator and
 * the operations are the production classes; only the SharedPreferences-era
 * legacy sources are in-memory doubles, and the key is an injected JCA key. No
 * Android Keystore alias is created, read or deleted.
 */
public class CredentialOperationsTest {

    private static final String LEGACY_SECRET = "sk-legacy-pre-encryption_1111111";
    private static final String NEW_SECRET = "sk-saved-from-settings_2222222";
    private static final String SECOND_SECRET = "sk-second-saved-key_3333333";

    private File dir;
    private SecretKey key;
    private ExecutorService lane;
    private List<String> failures;
    private CredentialOperations ops;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("credential-ops-test-").toFile();
        key = newAesKey();
        failures = new ArrayList<>();
        // The production lane is exactly this: one daemon thread, FIFO.
        lane = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "credential-ops-test");
            thread.setDaemon(true);
            return thread;
        });
        ops = new CredentialOperations(lane, failures::add);
    }

    @After
    public void tearDown() {
        lane.shutdownNow();
        deleteRecursively(dir);
    }

    // ------------------------------------------------------------------
    // Deletion versus an in-flight import
    // ------------------------------------------------------------------

    @Test
    public void deletionSubmittedWhileTheImportHoldsALegacyKeyDoesNotResurrectIt() throws Exception {
        CountDownLatch keyRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        PausableLegacySource legacy = new PausableLegacySource(LEGACY_SECRET, keyRead, resumeRead);
        CredentialMigration migration = migration(legacy);

        AtomicReference<CredentialMigration.Outcome> importOutcome = new AtomicReference<>();
        CountDownLatch importDone = new CountDownLatch(1);
        ops.submit(() -> {
            importOutcome.set(migration.migrate());
            return true;
        }, Runnable::run, success -> importDone.countDown());

        // The import has read the legacy key and is about to write it. Nothing
        // here races: the lane's only thread is inside that read, so the deletion
        // submitted below cannot run before the import finishes.
        assertTrue("the import should reach the legacy read",
                keyRead.await(10, TimeUnit.SECONDS));

        AtomicBoolean deleteSucceeded = new AtomicBoolean();
        CountDownLatch deleteDone = new CountDownLatch(1);
        ops.submit(() -> {
            migration.forget();
            return true;
        }, Runnable::run, success -> {
            deleteSucceeded.set(success);
            deleteDone.countDown();
        });

        resumeRead.countDown(); // let the import write the credential it just read

        assertTrue(importDone.await(10, TimeUnit.SECONDS));
        assertTrue(deleteDone.await(10, TimeUnit.SECONDS));

        assertEquals("the import did write the legacy credential",
                CredentialMigration.Outcome.MIGRATED, importOutcome.get());
        assertTrue("the deletion is the last completed operation, so it wins",
                deleteSucceeded.get());
        assertNull("the deleted credential must not reappear", store().read());
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
    }

    @Test
    public void deletionSubmittedBeforeTheImportLeavesNothingToImport() throws Exception {
        PausableLegacySource legacy = new PausableLegacySource(LEGACY_SECRET, null, null);
        CredentialMigration migration = migration(legacy);

        assertTrue(run(() -> {
            migration.forget();
            return true;
        }));
        // The import runs afterwards and must find no legacy copy left, so the
        // deleted state survives in either submission order.
        assertEquals(CredentialMigration.Outcome.NOTHING_TO_IMPORT, runAndGet(migration::migrate));

        assertNull(store().read());
        assertNull(legacy.plainKey);
    }

    @Test
    public void aFailedImportLeavesASubsequentDeletionEffective() throws Exception {
        PausableLegacySource legacy = new PausableLegacySource(LEGACY_SECRET, null, null);
        FlakyKeyProvider provider = new FlakyKeyProvider(key);
        provider.broken = true;
        CredentialMigration migration = migration(legacy, provider);

        assertEquals(CredentialMigration.Outcome.DEFERRED, runAndGet(migration::migrate));
        assertEquals("the failed import preserved the legacy copy",
                LEGACY_SECRET, legacy.plainKey);

        assertTrue(run(() -> {
            migration.forget();
            return true;
        }));

        assertNull(store().read());
        assertNull("the deletion removed the copy the failed import left behind",
                legacy.plainKey);
    }

    // ------------------------------------------------------------------
    // Update versus import
    // ------------------------------------------------------------------

    @Test
    public void anUpdateSubmittedAfterTheImportWins() throws Exception {
        PausableLegacySource legacy = new PausableLegacySource(LEGACY_SECRET, null, null);
        CredentialMigration migration = migration(legacy);

        assertEquals(CredentialMigration.Outcome.MIGRATED, runAndGet(migration::migrate));
        assertTrue(run(() -> storeCredential(NEW_SECRET)));

        assertEquals(NEW_SECRET, store().read());
    }

    @Test
    public void anUpdateSubmittedBeforeTheImportSurvivesIt() throws Exception {
        PausableLegacySource legacy = new PausableLegacySource(LEGACY_SECRET, null, null);
        CredentialMigration migration = migration(legacy);

        assertTrue(run(() -> storeCredential(NEW_SECRET)));
        // The import then finds a readable encrypted store plus a stale legacy
        // copy: the store stays authoritative and the copy is only cleaned up.
        assertEquals(CredentialMigration.Outcome.UP_TO_DATE, runAndGet(migration::migrate));

        assertEquals(NEW_SECRET, store().read());
        assertNull(legacy.plainKey);
    }

    // ------------------------------------------------------------------
    // Repeated / concurrent mutations
    // ------------------------------------------------------------------

    @Test
    public void repeatedAndConcurrentUpdatesAndDeletionsStayDeterministic() throws Exception {
        CredentialMigration migration = migration(
                new PausableLegacySource(null, null, null));
        Set<String> writtenValues = new HashSet<>();
        writtenValues.add(NEW_SECRET);
        writtenValues.add(SECOND_SECRET);

        int submitters = 4;
        int perSubmitter = 8;
        int total = submitters * perSubmitter;
        CountDownLatch completed = new CountDownLatch(total);
        AtomicBoolean anyFailure = new AtomicBoolean();

        List<Thread> threads = new ArrayList<>();
        for (int s = 0; s < submitters; s++) {
            final int id = s;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < perSubmitter; i++) {
                    final boolean delete = (id + i) % 3 == 0;
                    final String value = (i % 2 == 0) ? NEW_SECRET : SECOND_SECRET;
                    ops.submit(() -> delete ? (deleteCredential(migration)) : storeCredential(value),
                            Runnable::run, success -> {
                                if (!success) anyFailure.set(true);
                                completed.countDown();
                            });
                }
            }, "submitter-" + s);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join(10_000);
        assertTrue("all submitted operations should complete",
                completed.await(30, TimeUnit.SECONDS));

        assertFalse("every operation should report a valid outcome", anyFailure.get());
        // Whichever interleaving happened, the value is one that was actually
        // written (never a torn or half-applied credential).
        String midState = store().read();
        assertTrue("unexpected stored value: " + midState,
                midState == null || writtenValues.contains(midState));

        // A deletion submitted after every one of them is the last operation, so
        // the end state is deterministic regardless of how they interleaved.
        assertTrue(run(() -> deleteCredential(migration)));
        assertNull(store().read());
    }

    // ------------------------------------------------------------------
    // Reads versus queued mutations
    // ------------------------------------------------------------------

    @Test
    public void anUpdateQueuedThenReadObservesTheUpdate() throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));
        assertEquals(NEW_SECRET, readThroughProductionPolicy());
    }

    @Test
    public void aDeletionQueuedThenReadObservesNoCredential() throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));
        assertEquals(NEW_SECRET, readThroughProductionPolicy());

        assertTrue(run(() -> deleteCredential(migration(noLegacy()))));

        assertEquals("a deleted credential must not be observable", "",
                readThroughProductionPolicy());
        assertEquals("", readThroughProductionPolicy());
    }

    @Test
    public void aReadWhileTheImportIsPausedSeesThePreMutationStateThenTheImportedValue()
            throws Exception {
        // Ordering is by successful completion, so a read taken while the import is
        // still holding the legacy credential observes the pre-mutation state: the
        // credential is not in the store yet and there is no marker to fall back to.
        // Once the import completes, every later read observes it.
        CountDownLatch keyRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        CredentialMigration migration =
                migration(new PausableLegacySource(LEGACY_SECRET, keyRead, resumeRead));

        CountDownLatch importDone = new CountDownLatch(1);
        ops.submit(() -> {
            migration.migrate();
            return true;
        }, Runnable::run, success -> importDone.countDown());

        assertTrue("the import should be holding the legacy key",
                keyRead.await(10, TimeUnit.SECONDS));
        assertEquals("a mutation in flight is not a completed mutation yet",
                "", readThroughProductionPolicy());

        resumeRead.countDown();
        assertTrue("the import should finish", importDone.await(10, TimeUnit.SECONDS));
        assertEquals(LEGACY_SECRET, readThroughProductionPolicy());
    }

    @Test
    public void aDeletionQueuedWhileTheImportHoldsALegacyKeyLeavesReadsEmpty() throws Exception {
        CountDownLatch keyRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        CredentialMigration migration =
                migration(new PausableLegacySource(LEGACY_SECRET, keyRead, resumeRead));

        CountDownLatch importDone = new CountDownLatch(1);
        ops.submit(() -> {
            migration.migrate();
            return true;
        }, Runnable::run, success -> importDone.countDown());
        assertTrue(keyRead.await(10, TimeUnit.SECONDS));

        CountDownLatch deleteDone = new CountDownLatch(1);
        AtomicBoolean deleteResult = new AtomicBoolean();
        ops.submit(() -> {
            deleteResult.set(deleteCredential(migration));
            return true;
        }, Runnable::run, success -> deleteDone.countDown());

        resumeRead.countDown();
        assertTrue(importDone.await(10, TimeUnit.SECONDS));
        assertTrue("the deletion should complete", deleteDone.await(10, TimeUnit.SECONDS));

        assertTrue(deleteResult.get());
        assertEquals("the imported credential must not be readable after the deletion",
                "", readThroughProductionPolicy());
        assertEquals("", readThroughProductionPolicy());
    }

    @Test
    public void aFailedUpdateAfterADeletionReportsFailureAndLeavesReadsEmpty() throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));
        assertTrue(run(() -> deleteCredential(migration(noLegacy()))));
        assertEquals("", readThroughProductionPolicy());

        FlakyKeyProvider provider = new FlakyKeyProvider(key);
        CredentialMigration migration = migration(noLegacy(), provider);
        provider.broken = true;

        assertFalse("an unencryptable save must be reported as a failure",
                run(() -> storeCredential(migration, SECOND_SECRET)));
        assertEquals("a failed save must not surface stale state", "",
                readThroughProductionPolicy());
    }

    // ------------------------------------------------------------------
    // Ordered value reads on the lane (the async credential read)
    // ------------------------------------------------------------------

    @Test
    public void aValueReadQueuedAfterAnUpdateSeesTheNewCredential() throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));

        ApiKeyRead read = readThroughLane(Runnable::run);

        assertEquals(ApiKeyRead.Status.LOADED, read.status());
        assertEquals(NEW_SECRET, read.key());
    }

    @Test
    public void aValueReadQueuedAfterADeletionSeesNoCredentialEvenWhenACopySurvives()
            throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));
        // A legacy copy whose removal cannot persist: the deletion completes as an
        // operation but the marker stays on disk, so only the tombstone can make the
        // read report "no credential".
        writeStubbornMarker(LEGACY_SECRET);
        StubbornMarkerSource legacy = new StubbornMarkerSource();

        assertFalse("the physical removal cannot be reported as complete",
                run(() -> deleteCredential(migration(legacy))));
        assertTrue("the marker really did survive", legacy.markerStillOnDisk());

        ApiKeyRead read = readThroughLane(Runnable::run);

        assertEquals("a deleted credential must not be readable",
                ApiKeyRead.Status.ABSENT, read.status());
        assertEquals("", read.key());
    }

    @Test
    public void aValueReadQueuedBehindAPendingMigrationSeesTheMigratedCredential()
            throws Exception {
        // The migration holds the legacy key; the read is queued behind it, so a
        // migration still in flight is not mistaken for a missing credential.
        CountDownLatch keyRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        CredentialMigration migration =
                migration(new PausableLegacySource(LEGACY_SECRET, keyRead, resumeRead));

        CountDownLatch importDone = new CountDownLatch(1);
        ops.submit(() -> {
            migration.migrate();
            return true;
        }, Runnable::run, success -> importDone.countDown());
        assertTrue("the import should be holding the legacy key",
                keyRead.await(10, TimeUnit.SECONDS));

        CountDownLatch readDone = new CountDownLatch(1);
        AtomicReference<ApiKeyRead> read = new AtomicReference<>();
        ops.submitValue(this::readResultOnLane, Runnable::run, result -> {
            read.set(result);
            readDone.countDown();
        });

        resumeRead.countDown();
        assertTrue("the import should finish", importDone.await(10, TimeUnit.SECONDS));
        assertTrue("the queued read must complete", readDone.await(10, TimeUnit.SECONDS));

        assertNotNull("the read must produce an outcome", read.get());
        assertEquals("the read was queued behind the import and must see its result",
                ApiKeyRead.Status.LOADED, read.get().status());
        assertEquals(LEGACY_SECRET, read.get().key());
    }

    @Test
    public void aReadFailureIsDistinguishableFromAConfirmedAbsence() throws Exception {
        // Corrupt ciphertext: something is stored, it just cannot be read — and a
        // legacy copy must not be promoted in its place.
        writeStubbornMarker(LEGACY_SECRET);
        MarkerFileHelper.writeStringToFile(dir, SecureCredentialStore.FILE_NAME,
                "v1:broken:payload");

        ApiKeyRead unreadable = readThroughLane(Runnable::run);

        assertEquals("an unreadable store is not an absent credential",
                ApiKeyRead.Status.UNREADABLE, unreadable.status());
        assertEquals("and it never falls back to the legacy copy", "", unreadable.key());

        // Deleting every copy makes the same question resolve to a confirmed
        // absence instead. (The store is removed by the deletion; the marker is
        // dropped here because this legacy double reports no marker of its own.)
        assertTrue(run(() -> deleteCredential(migration(noLegacy()))));
        assertTrue(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).delete()
                || !new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).exists());

        ApiKeyRead absent = readThroughLane(Runnable::run);
        assertEquals(ApiKeyRead.Status.ABSENT, absent.status());
        assertEquals("", absent.key());
    }

    @Test
    public void readsQueuedWithWritesNeverObservePartiallyWrittenState() throws Exception {
        // Reads and writes share one lane, so they cannot overlap at all: a read is
        // never inside a write, and one queued behind a write observes that write's
        // completed result rather than a half-written or half-decrypted file.
        for (int i = 0; i < 12; i++) {
            String value = (i % 2 == 0) ? NEW_SECRET : SECOND_SECRET;
            AtomicBoolean writeSucceeded = new AtomicBoolean();
            CountDownLatch writeDone = new CountDownLatch(1);
            ops.submit(() -> storeCredential(value), Runnable::run, success -> {
                writeSucceeded.set(success);
                writeDone.countDown();
            });

            CountDownLatch readDone = new CountDownLatch(1);
            AtomicReference<ApiKeyRead> read = new AtomicReference<>();
            ops.submitValue(this::readResultOnLane, Runnable::run, result -> {
                read.set(result);
                readDone.countDown();
            });

            assertTrue(writeDone.await(30, TimeUnit.SECONDS));
            assertTrue(readDone.await(30, TimeUnit.SECONDS));
            assertTrue("the write should have persisted", writeSucceeded.get());
            assertNotNull(read.get());
            assertEquals("a read queued behind a write must observe that write",
                    ApiKeyRead.Status.LOADED, read.get().status());
            assertEquals(value, read.get().key());
        }
    }

    @Test
    public void valueReadsCompleteExactlyOnceOnTheDesignatedExecutor() throws Exception {
        assertTrue(run(() -> storeCredential(NEW_SECRET)));

        List<String> callbackThreads = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);
        ExecutorService callbacks = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "read-callbacks");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int i = 0; i < 3; i++) {
                ops.submitValue(this::readResultOnLane, callbacks, read -> {
                    completions.incrementAndGet();
                    callbackThreads.add(Thread.currentThread().getName());
                    done.countDown();
                });
            }
            assertTrue("every read must complete", done.await(30, TimeUnit.SECONDS));
            // Exactly once per submission (the latch itself would time out on a
            // missing or duplicated completion, and the counter pins it).
            assertEquals(3, completions.get());
            assertEquals(3, callbackThreads.size());
            for (String thread : callbackThreads) {
                assertEquals("callbacks must be delivered on the designated executor",
                        "read-callbacks", thread);
            }
        } finally {
            callbacks.shutdownNow();
        }
    }

    @Test
    public void aValueReadThatThrowsIsReportedAndDeliversNoAnswer() throws Exception {
        AtomicReference<String> reported = new AtomicReference<>();
        CredentialOperations opsWithSink = new CredentialOperations(lane, reported::set);

        AtomicReference<ApiKeyRead> delivered = new AtomicReference<>(ApiKeyRead.absent());
        CountDownLatch done = new CountDownLatch(1);
        opsWithSink.<ApiKeyRead>submitValue(() -> {
            throw new IllegalStateException("boom " + LEGACY_SECRET);
        }, Runnable::run, result -> {
            delivered.set(result);
            done.countDown();
        });

        assertTrue("a throwing read must still complete exactly once",
                done.await(30, TimeUnit.SECONDS));
        assertNull("no answer is delivered, so it can never be read as 'absent'",
                delivered.get());
        assertNotNull(reported.get());
        assertTrue(reported.get().contains("IllegalStateException"));
        assertFalse("the reporter must never carry credential material",
                reported.get().contains(LEGACY_SECRET));
    }

    // ------------------------------------------------------------------
    // Failure containment on the lane
    // ------------------------------------------------------------------

    @Test
    public void aThrowingOperationIsContainedReportedSanitizedAndDoesNotStallTheLane()
            throws Exception {
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        ops.submit(() -> {
            throw new IllegalStateException("boom " + LEGACY_SECRET);
        }, Runnable::run, success -> {
            result.set(success);
            done.countDown();
        });

        assertTrue("the completion must always arrive", done.await(10, TimeUnit.SECONDS));
        assertFalse(result.get());
        assertEquals(1, failures.size());
        assertFalse("the reporter must never carry credential material",
                failures.get(0).contains(LEGACY_SECRET));
        assertTrue(failures.get(0).contains("IllegalStateException"));

        // Fire-and-forget submission still works, and the lane keeps its order.
        ops.submit(() -> storeCredential(NEW_SECRET), null, null);
        assertEquals(NEW_SECRET, runAndGet(() -> {
            String value = store().read();
            return value == null ? "" : value;
        }));
    }

    @Test
    public void completionsArriveOnceInSubmissionOrderIncludingFailurePaths()
            throws Exception {
        FlakyKeyProvider provider = new FlakyKeyProvider(key);
        CredentialMigration migration = migration(noLegacy(), provider);

        List<Integer> completedInOrder = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
        int submissions = 5;
        CountDownLatch all = new CountDownLatch(submissions);

        for (int i = 0; i < submissions; i++) {
            final int index = i;
            ops.submit(() -> {
                switch (index) {
                    case 1:  // save
                        return storeCredential(migration, NEW_SECRET);
                    case 2:  // save of a different value
                        return storeCredential(migration, SECOND_SECRET);
                    case 3:  // deletion
                        return deleteCredential(migration);
                    case 4:  // supported failure path: the keystore is gone
                        provider.broken = true;
                        return storeCredential(migration, NEW_SECRET);
                    default: // save
                        return storeCredential(migration, NEW_SECRET);
                }
            }, Runnable::run, success -> {
                completedInOrder.add(index);
                results.add(success);
                all.countDown();
            });
        }

        assertTrue("every submission must complete", all.await(30, TimeUnit.SECONDS));
        assertEquals("one completion per submission, in submission order",
                Arrays.asList(0, 1, 2, 3, 4), completedInOrder);
        assertTrue("the deletion succeeds", results.get(3));
        assertFalse("the failing save reports false", results.get(4));
        assertTrue("the earlier saves report true", results.get(0) && results.get(1));
        // The last completed operation is the failed save, so the state is the
        // deleted one: a completion never reports something that did not happen.
        assertEquals("", readThroughProductionPolicy());
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private CredentialMigration migration(CredentialMigration.LegacySources legacy) {
        return migration(legacy, new FlakyKeyProvider(key));
    }

    private CredentialMigration migration(CredentialMigration.LegacySources legacy,
                                          FlakyKeyProvider provider) {
        return new CredentialMigration(new SecureCredentialStore(dir, provider), legacy, dir,
                new CredentialMigration.Diagnostics() {
                    @Override
                    public void warn(String message) {
                        failures.add("warn: " + message);
                    }

                    @Override
                    public void error(String message) {
                        failures.add("error: " + message);
                    }
                });
    }

    private SecureCredentialStore store() {
        return new SecureCredentialStore(dir, () -> key);
    }

    /**
     * The production save path: {@code SettingsManager.storeCredential()} delegates
     * to the coordinator, which owns the order (verified write → clear the deletion
     * tombstone → drop the legacy copies a verified store supersedes). Going through
     * it here means the lane tests exercise the real save instead of a hand-rolled
     * sequence.
     */
    private boolean storeCredential(String value) {
        return storeCredential(migration(noLegacy()), value);
    }

    private boolean storeCredential(CredentialMigration migration, String value) {
        return migration.store(value);
    }

    /** No marker, no EncryptedSharedPreferences, no plain prefs copy. */
    private CredentialMigration.LegacySources noLegacy() {
        return new PausableLegacySource(null, null, null);
    }

    /**
     * The production deletion path: {@code SettingsManager.deleteCredential()}
     * delegates to {@code forget()} and reports its result, so the lane tests
     * exercise the same "is it really gone?" answer the UI acts on.
     */
    private boolean deleteCredential(CredentialMigration migration) {
        return migration.forget();
    }

    /** Submits [operation] on the lane and waits for its completion. */
    private boolean run(CredentialOperations.Operation operation) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        ops.submit(operation, Runnable::run, success -> {
            result.set(success);
            done.countDown();
        });
        assertTrue("operation did not complete", done.await(30, TimeUnit.SECONDS));
        return result.get();
    }

    private <T> T runAndGet(java.util.concurrent.Callable<T> operation) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        ops.submit(() -> {
            try {
                value.set(operation.call());
                done.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        }, Runnable::run, success -> latch.countDown());
        assertTrue("operation did not complete", latch.await(30, TimeUnit.SECONDS));
        assertTrue("operation did not produce a value", done.get());
        return value.get();
    }

    /**
     * The production read: {@link CredentialRead#gather}, the same code
     * {@code SettingsManager.readApiKeyOnLane()} runs, over this test's real store,
     * real marker file and real tombstone. The only thing a plain-JVM test cannot
     * cross is {@code filesDir()}'s {@code Context}; every decision and every file
     * access is the production one.
     */
    private ApiKeyRead readResultOnLane() {
        return CredentialRead.gather(
                dir,
                store(),
                () -> MarkerFileHelper.readStringFromFile(
                        dir, SecureCredentialStore.LEGACY_MARKER_FILE, null),
                encoded -> {
                    try {
                        return new String(java.util.Base64.getDecoder().decode(encoded),
                                StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                },
                message -> failures.add("read: " + message));
    }

    private String readThroughProductionPolicy() {
        return readResultOnLane().key();
    }

    /**
     * Runs a credential read on the production lane through the production
     * value-read path ({@link CredentialOperations#submitValue}), delivering the
     * outcome on {@code callbackExecutor}.
     */
    private ApiKeyRead readThroughLane(Executor executor) throws Exception {
        AtomicReference<ApiKeyRead> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ops.submitValue(this::readResultOnLane, executor, read -> {
            value.set(read);
            done.countDown();
        });
        assertTrue("the queued read must complete", done.await(30, TimeUnit.SECONDS));
        return value.get();
    }

    private void writeStubbornMarker(String plaintext) {
        MarkerFileHelper.writeStringToFile(dir, SecureCredentialStore.LEGACY_MARKER_FILE,
                java.util.Base64.getEncoder().encodeToString(
                        plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A legacy source whose Base64 marker exists and cannot be removed, i.e. the
     * "cleanup failed" state a deletion can leave behind. Only the tombstone can
     * stop a reader from answering with the marker.
     */
    private final class StubbornMarkerSource implements CredentialMigration.LegacySources {
        @Override
        public boolean hasMarker() {
            return markerStillOnDisk();
        }

        boolean markerStillOnDisk() {
            File file = new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE);
            return file.isFile() && file.length() > 0;
        }

        @Override
        public boolean deleteMarker() {
            return false;
        }

        @Override
        public boolean hasLegacyEncrypted() {
            return false;
        }

        @Override
        public CredentialMigration.LegacyRead readLegacyEncrypted() {
            return new CredentialMigration.LegacyRead(true, null);
        }

        @Override
        public boolean deleteLegacyEncrypted() {
            return true;
        }

        @Override
        public boolean hasLegacyPlain() {
            return false;
        }

        @Override
        public String legacyPlain() {
            return null;
        }

        @Override
        public boolean deleteLegacyPlain() {
            return true;
        }
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

    /** Counts key acquisitions; can be broken on demand. */
    private static final class FlakyKeyProvider implements SecureCredentialStore.KeyProvider {
        private final SecretKey key;
        boolean broken;

        FlakyKeyProvider(SecretKey key) {
            this.key = key;
        }

        @Override
        public SecretKey get() throws GeneralSecurityException {
            if (broken) throw new KeyStoreException("keystore unavailable");
            return key;
        }
    }

    /**
     * The prefs-era legacy copy, with a deterministic pause point inside the read
     * so a test can submit a deletion while an import holds the credential.
     */
    private static final class PausableLegacySource implements CredentialMigration.LegacySources {
        String plainKey;
        private final CountDownLatch keyRead;
        private final CountDownLatch resumeRead;

        PausableLegacySource(String plainKey, CountDownLatch keyRead, CountDownLatch resumeRead) {
            this.plainKey = plainKey;
            this.keyRead = keyRead;
            this.resumeRead = resumeRead;
        }

        @Override
        public boolean hasMarker() {
            return false;
        }

        @Override
        public boolean deleteMarker() {
            return true;
        }

        @Override
        public boolean hasLegacyEncrypted() {
            return false;
        }

        @Override
        public CredentialMigration.LegacyRead readLegacyEncrypted() {
            return new CredentialMigration.LegacyRead(true, null);
        }

        @Override
        public boolean deleteLegacyEncrypted() {
            return true;
        }

        @Override
        public boolean hasLegacyPlain() {
            return plainKey != null && !plainKey.isEmpty();
        }

        @Override
        public String legacyPlain() {
            String value = plainKey;
            if (keyRead != null) {
                // The migration has picked this key and is about to store it; hold
                // it here so the test can queue a deletion behind the lane.
                keyRead.countDown();
                try {
                    if (!resumeRead.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("pause never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return value;
        }

        @Override
        public boolean deleteLegacyPlain() {
            plainKey = null;
            return true;
        }
    }
}
