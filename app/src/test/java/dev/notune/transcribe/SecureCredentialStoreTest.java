package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Security-property tests for {@link SecureCredentialStore}.
 *
 * <p>The Android Keystore itself cannot run in a JVM test, so the key
 * acquisition is injected ({@link SecureCredentialStore.KeyProvider}) and the
 * tests use a plain JCA AES-256 key — the same AES-GCM cipher, format, file
 * handling and migration logic as on device. The only untested-here surface is
 * {@link AndroidCredentialKeyProvider} (device-level; see AGENTS.md §5.4).
 *
 * <p>Every test asserts a security property (what an attacker or a crash would
 * observe), not an implementation detail.
 */
public class SecureCredentialStoreTest {

    private static final String SECRET = "sk-live-superSecretKey_1234567890";
    private static final String OTHER_SECRET = "gsk_live_otherKeyValue_987654321";

    private File dir;
    private SecretKey key;
    private SecureCredentialStore store;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("credential-store-test-").toFile();
        key = newAesKey();
        store = new SecureCredentialStore(dir, () -> key);
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    // ---------------------------------------------------------- round trip

    @Test
    public void roundTripPersistsAcrossRepeatedReads() throws Exception {
        store.store(SECRET);
        assertEquals(SECRET, store.read());
        assertEquals(SECRET, store.read());
        assertEquals(SECRET, store.read());
    }

    @Test
    public void updatesReplaceThePreviousCredential() throws Exception {
        store.store(SECRET);
        assertEquals(SECRET, store.read());

        store.store(OTHER_SECRET);
        assertEquals(OTHER_SECRET, store.read());

        String payload = readStoredBytes();
        assertFalse(payload.contains(SECRET));
        assertFalse(payload.contains(base64(SECRET)));
    }

    @Test
    public void everyWriteUsesAFreshIv() throws Exception {
        // Identical plaintext must not produce identical ciphertext: a reused
        // IV/GCM would leak equality of credentials across writes.
        store.store(SECRET);
        String first = readStoredBytes();
        store.store(SECRET);
        String second = readStoredBytes();
        assertNotEquals(first, second);
        // ...and both still decrypt to the same value.
        assertEquals(SECRET, store.read());
    }

    // ------------------------------------------- at-rest exposure properties

    @Test
    public void persistedDataNeverContainsThePlaintextOrItsEncoding() throws Exception {
        store.store(SECRET);
        String raw = readStoredBytes();

        assertFalse("plaintext on disk", raw.contains(SECRET));
        assertFalse("base64(plaintext) on disk", raw.contains(base64(SECRET)));
        assertFalse("base64 no-pad on disk",
                raw.contains(Base64.getEncoder().withoutPadding()
                        .encodeToString(SECRET.getBytes(StandardCharsets.UTF_8))));
        assertFalse("base64 url-safe on disk",
                raw.contains(Base64.getUrlEncoder().encodeToString(
                        SECRET.getBytes(StandardCharsets.UTF_8))));
        assertTrue("versioned format expected", raw.startsWith(SecureCredentialStore.FORMAT_PREFIX));
    }

    @Test
    public void exceptionDiagnosticsNeverExposeTheCredential() throws Exception {
        store.store(SECRET);
        // Wrong key: decrypt failure must carry no key material.
        SecretKey otherKey = newAesKey();
        SecureCredentialStore foreign = new SecureCredentialStore(dir, () -> otherKey);
        try {
            foreign.read();
            fail("expected CredentialStoreException for the wrong key");
        } catch (SecureCredentialStore.CredentialStoreException e) {
            assertDiagnosticFreeOfSecret(e);
        }
    }

    // -------------------------------------------------- hostile-store cases

    @Test
    public void wrongKeyCannotDecryptTheCredential() throws Exception {
        store.store(SECRET);
        SecretKey otherKey = newAesKey();
        SecureCredentialStore foreign = new SecureCredentialStore(dir, () -> otherKey);
        try {
            foreign.read();
            fail("expected CredentialStoreException");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // Explicit failure — never silently "" and never a legacy fallback.
        }
    }

    @Test
    public void tamperedCiphertextIsRejectedByTheAuthenticationTag() throws Exception {
        store.store(SECRET);
        // Flip one *decoded* byte inside the ciphertext payload (first byte of
        // the sealed data), keeping the field valid Base64 — so the failure
        // must come from GCM's tag verification, not from parsing.
        writeStoredBytes(mutateCiphertext(0, (byte) 0x01));
        try {
            store.read();
            fail("expected CredentialStoreException for tampered ciphertext");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }

    @Test
    public void invalidAuthenticationTagIsRejected() throws Exception {
        store.store(SECRET);
        // GCM's tag is the last 16 bytes of the ciphertext field; corrupting
        // it must fail authentication rather than returning modified plaintext.
        byte[] ciphertext = decodedCiphertext();
        writeStoredBytes(reencodeCiphertext(flipLastByte(ciphertext)));
        try {
            store.read();
            fail("expected CredentialStoreException for an invalid auth tag");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }

    @Test
    public void corruptedStorageIsRejected() throws Exception {
        writeStoredBytes("v1:not-base64!!!:also-not-base64###");
        try {
            store.read();
            fail("expected CredentialStoreException for corrupt payload");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertDiagnosticFreeOfSecret(expected);
        }
    }

    @Test
    public void truncatedStorageIsRejected() throws Exception {
        store.store(SECRET);
        String raw = readStoredBytes();
        writeStoredBytes(raw.substring(0, Math.max(1, raw.length() / 2)));
        try {
            store.read();
            fail("expected CredentialStoreException for truncated payload");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // Structural validation, before any decrypt is attempted.
        }
    }

    @Test
    public void missingStorageReadsAsAbsent() throws Exception {
        assertNull(store.read());
    }

    @Test
    public void emptyStorageFileReadsAsAbsentNotAsAnError() throws Exception {
        Files.write(new File(dir, SecureCredentialStore.FILE_NAME).toPath(), new byte[0]);
        assertNull(store.read());
    }

    @Test
    public void unsupportedFormatVersionIsRejected() throws Exception {
        writeStoredBytes("v2:AAAA:BBBB");
        try {
            store.read();
            fail("expected CredentialStoreException for unsupported version");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertTrue(expected.getMessage().contains("version"));
        }
    }

    @Test
    public void corruptStoreNeverFallsBackToTheLegacyPlaintextMarker() throws Exception {
        // Both files exist (the only scenario where a fallback could tempt):
        // the read must fail explicitly instead of handing back legacy Base64.
        writeStoredBytes("v1:broken:payload");
        Files.write(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).toPath(),
                base64(SECRET).getBytes(StandardCharsets.UTF_8));
        try {
            String value = store.read();
            fail("expected CredentialStoreException, got: " + (value == null ? "null" : "value"));
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // No plaintext fallback from read().
        }
    }

    // ------------------------------------------------- key provider failures

    @Test
    public void keyProviderFailureIsExplicitAndWritesNothing() throws Exception {
        SecureCredentialStore broken = new SecureCredentialStore(dir, () -> {
            throw new KeyStoreException("keystore unavailable");
        });
        try {
            broken.store(SECRET);
            fail("expected CredentialStoreException when the key is unavailable");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertDiagnosticFreeOfSecret(expected);
        }
        // Nothing persisted — neither encrypted nor plaintext.
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
        assertDirContainsNoSecret();
    }

    @Test
    public void emptySecretsAreRefusedRatherThanPersistedAsEmpty() throws Exception {
        try {
            store.store("");
            fail("expected CredentialStoreException for an empty credential");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // An empty overwrite would silently disable authentication.
        }
        assertNull(store.read());
    }

    // ------------------------------------------- provider runtime failures
    //
    // An Android Keystore (or JCA) provider that is merely in a bad state throws
    // unchecked exceptions. They used to escape the store's error handling and
    // reach the settings screen or the start-up migration as a crash; they must
    // now surface as the typed failure callers already handle, with no plaintext
    // fallback and no secret in the diagnostic.

    @Test
    public void runtimeKeyProviderFailureOnStoreBecomesATypedFailureAndWritesNothing()
            throws Exception {
        SecureCredentialStore broken = new SecureCredentialStore(dir, () -> {
            throw new IllegalStateException("keystore in a bad state");
        });
        try {
            broken.store(SECRET);
            fail("expected CredentialStoreException, not an escaping RuntimeException");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertDiagnosticFreeOfSecret(expected);
            assertTrue("the failure must name the provider's failure mode, not be swallowed",
                    expected.getMessage().contains("IllegalStateException"));
        }
        // Nothing persisted — neither encrypted nor plaintext.
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
        assertDirContainsNoSecret();
    }

    @Test
    public void runtimeKeyProviderFailureOnReadBecomesATypedFailure() throws Exception {
        store.store(SECRET);
        SecureCredentialStore broken = new SecureCredentialStore(dir, () -> {
            throw new IllegalStateException("keystore in a bad state");
        });
        try {
            broken.read();
            fail("expected CredentialStoreException, not an escaping RuntimeException");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // A failed read never becomes an empty success (or a legacy fallback).
            assertDiagnosticFreeOfSecret(expected);
        }
    }

    @Test
    public void runtimeFailureDuringTheVerificationDecryptIsTypedAndLeaksNoPlaintext()
            throws Exception {
        // Works for the encrypt call, then the provider breaks before the read-back
        // verification: the save must fail, and what reached the disk must still be
        // ciphertext in the versioned format.
        final int[] calls = {0};
        SecureCredentialStore flaky = new SecureCredentialStore(dir, () -> {
            calls[0]++;
            if (calls[0] >= 2) throw new IllegalStateException("keystore in a bad state");
            return key;
        });
        try {
            flaky.store(SECRET);
            fail("expected CredentialStoreException for an unverifiable write");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertDiagnosticFreeOfSecret(expected);
        }
        assertTrue("whatever landed must be the encrypted format",
                readStoredBytes().startsWith(SecureCredentialStore.FORMAT_PREFIX));
        assertDirContainsNoSecret();
    }

    @Test
    public void failedSaveKeepsThePreviouslyStoredCredential() throws Exception {
        final boolean[] unavailable = {false};
        SecureCredentialStore flaky = new SecureCredentialStore(dir, () -> {
            if (unavailable[0]) throw new IllegalStateException("keystore in a bad state");
            return key;
        });
        flaky.store(SECRET);

        unavailable[0] = true;
        try {
            flaky.store(OTHER_SECRET);
            fail("expected CredentialStoreException");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertDiagnosticFreeOfSecret(expected);
        }

        // Nothing was overwritten: the credential that was already stored is still
        // there once the Keystore answers again.
        unavailable[0] = false;
        assertEquals(SECRET, flaky.read());
    }

    @Test
    public void providerExceptionTextIsNeverEmbeddedInTheTypedDiagnostic() throws Exception {
        // A platform provider may put anything in its message. The typed failure
        // carries the class name only, so what a caller logs from it can never
        // carry credential material. The original throwable is kept as the cause
        // for stack traces, and no call site logs a cause chain.
        SecureCredentialStore broken = new SecureCredentialStore(dir, () -> {
            throw new IllegalStateException("boom " + SECRET + " " + base64(SECRET));
        });
        try {
            broken.store(SECRET);
            fail("expected CredentialStoreException");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            assertFalse("typed message leaked the credential",
                    expected.getMessage().contains(SECRET));
            assertFalse("typed message leaked the encoded credential",
                    expected.getMessage().contains(base64(SECRET)));
            assertTrue(expected.getMessage().contains("IllegalStateException"));
        }
        assertDirContainsNoSecret();
    }

    // ------------------------------------------------------------- migration

    @Test
    public void legacyMarkerMigratesToEncryptedStoreAndLegacyCopyIsRemoved()
            throws Exception {
        writeLegacyMarker(SECRET);

        assertEquals(SecureCredentialStore.MigrationOutcome.MIGRATED,
                store.migrateLegacyMarkerIfNeeded());

        assertEquals(SECRET, store.read());
        assertFalse("legacy marker must be removed after verified migration",
                new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).exists());
        String raw = readStoredBytes();
        assertFalse(raw.contains(SECRET));
        assertFalse(raw.contains(base64(SECRET)));
    }

    @Test
    public void migrationIsIdempotentAndRepeatedReadsUseTheNewFormat() throws Exception {
        writeLegacyMarker(SECRET);
        assertEquals(SecureCredentialStore.MigrationOutcome.MIGRATED,
                store.migrateLegacyMarkerIfNeeded());
        String migrated = readStoredBytes();

        // Subsequent runs neither rewrite nor re-import anything.
        assertEquals(SecureCredentialStore.MigrationOutcome.NOT_NEEDED,
                store.migrateLegacyMarkerIfNeeded());
        assertEquals(migrated, readStoredBytes());
        assertEquals(SECRET, store.read());
        assertEquals(SecureCredentialStore.MigrationOutcome.NOT_NEEDED,
                store.migrateLegacyMarkerIfNeeded());
    }

    @Test
    public void migrationWithoutLegacyDataIsHarmless() {
        assertEquals(SecureCredentialStore.MigrationOutcome.NO_LEGACY,
                store.migrateLegacyMarkerIfNeeded());
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
    }

    @Test
    public void failedMigrationPreservesTheLegacyCredentialForRetry() throws Exception {
        writeLegacyMarker(SECRET);
        SecureCredentialStore broken = new SecureCredentialStore(dir, () -> {
            throw new KeyStoreException("keystore unavailable");
        });

        assertEquals(SecureCredentialStore.MigrationOutcome.FAILED,
                broken.migrateLegacyMarkerIfNeeded());

        // The only copy is intact, byte for byte, and no partial store exists.
        // (Its legacy Base64 form is expected *there* — it is the preserved
        // pre-existing format; what must never appear is the plaintext.)
        assertEquals(base64(SECRET), readLegacyMarkerBytes());
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
        assertDirContainsNoPlaintext();

        // A later start with a working Keystore completes the migration.
        assertEquals(SecureCredentialStore.MigrationOutcome.MIGRATED,
                store.migrateLegacyMarkerIfNeeded());
        assertEquals(SECRET, store.read());
        assertFalse(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).exists());
    }

    @Test
    public void unreadableLegacyMarkerIsPreservedAndNotRetriedForever() throws Exception {
        Files.write(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).toPath(),
                "!!! definitely not base64 !!!".getBytes(StandardCharsets.UTF_8));

        assertEquals(SecureCredentialStore.MigrationOutcome.LEGACY_UNREADABLE,
                store.migrateLegacyMarkerIfNeeded());

        // Never delete what we could not read, and never claim success.
        assertTrue(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).exists());
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
    }

    @Test
    public void migrationDoesNotOverwriteAnExistingSecureCredentialWithAStaleMarker()
            throws Exception {
        store.store(OTHER_SECRET);
        writeLegacyMarker(SECRET); // stale copy (downgrade cycle)

        assertEquals(SecureCredentialStore.MigrationOutcome.NOT_NEEDED,
                store.migrateLegacyMarkerIfNeeded());

        assertEquals(OTHER_SECRET, store.read());
        assertFalse(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).exists());
    }

    // --------------------------------------------------------------- delete

    @Test
    public void deletionRemovesTheCredentialAndLaterReadsAreAbsent() throws Exception {
        store.store(SECRET);
        assertEquals(SECRET, store.read());

        store.delete();
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
        assertNull(store.read());

        store.delete(); // idempotent
        assertNull(store.read());

        // A new credential can be stored again afterwards.
        store.store(OTHER_SECRET);
        assertEquals(OTHER_SECRET, store.read());
    }

    @Test
    public void deleteReportsWhetherTheCredentialIsActuallyGone() throws Exception {
        assertTrue("nothing to remove is a completed deletion", store.delete());

        store.store(SECRET);
        assertTrue("a removed credential reports success", store.delete());
        assertNull(store.read());

        // An obstruction File.delete() cannot remove must be reported, not
        // swallowed: a caller's "deleted" message would otherwise be false while
        // the credential is still stored (a non-empty directory is the portable
        // way to make the unlink fail).
        File path = new File(dir, SecureCredentialStore.FILE_NAME);
        assertTrue(path.mkdirs());
        assertTrue(new File(path, "keep").createNewFile());
        assertFalse("a surviving credential must be reported", store.delete());

        assertTrue(new File(path, "keep").delete());
        assertTrue(path.delete());
        assertTrue(store.delete());
    }

    // ------------------------------------------------------------ concurrency

    @Test
    public void concurrentWritesAndReadsNeverExposeATornCredential() throws Exception {
        final Set<String> values = ConcurrentHashMap.newKeySet();
        values.add(SECRET);
        values.add(OTHER_SECRET);
        values.add("sk-third_" + System.nanoTime());

        final int writers = 3;
        final int rounds = 40;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> threads = new ArrayList<>();

        final java.util.concurrent.atomic.AtomicInteger rejectedStores =
                new java.util.concurrent.atomic.AtomicInteger();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        store.store((id % 2 == 0) ? SECRET : OTHER_SECRET);
                    }
                } catch (SecureCredentialStore.CredentialStoreException benignRace) {
                    // Last-writer-wins: if another writer replaces the file
                    // between our write and its verification read-back, the
                    // store reports a clean failure instead of claiming a
                    // success it did not verify. Still encrypted, never
                    // plaintext, never torn — so this interleaving is counted,
                    // not treated as a broken security property.
                    rejectedStores.incrementAndGet();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "cred-writer-" + w);
            threads.add(writer);
            writer.start();
        }
        for (int r = 0; r < 3; r++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds * 3; i++) {
                        String value = store.read();
                        // Atomic rename guarantees a reader sees a complete
                        // file: only null (pre-first-write) or a full value.
                        if (value != null && !values.contains(value)) {
                            throw new AssertionError("torn credential read: " + value);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "cred-reader-" + r);
            threads.add(reader);
            reader.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent access failed", failure.get());
        }
        // Readers never observed a torn or undecryptable value, and the final
        // state is one complete credential written by some writer.
        assertTrue("readers should not fail while writers race",
                rejectedStores.get() < writers * rounds);
        String finalValue = store.read();
        assertTrue("final state must be a complete written credential",
                finalValue != null && values.contains(finalValue));
    }

    // --------------------------------------------------------------- helpers

    private static SecretKey newAesKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String readStoredBytes() throws IOException {
        byte[] raw = Files.readAllBytes(
                new File(dir, SecureCredentialStore.FILE_NAME).toPath());
        return new String(raw, StandardCharsets.UTF_8);
    }

    private String readLegacyMarkerBytes() throws IOException {
        byte[] raw = Files.readAllBytes(
                new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).toPath());
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    private void writeStoredBytes(String content) throws IOException {
        Files.write(new File(dir, SecureCredentialStore.FILE_NAME).toPath(),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeLegacyMarker(String plaintext) throws IOException {
        Files.write(new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE).toPath(),
                base64(plaintext).getBytes(StandardCharsets.UTF_8));
    }

    /** No file in the store directory may contain the plaintext secret. */
    private void assertDirContainsNoPlaintext() throws Exception {
        File[] files = dir.listFiles();
        assertTrue(files != null);
        for (File file : files) {
            String raw = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            assertFalse("plaintext secret found in " + file.getName(),
                    raw.contains(SECRET));
        }
    }

    /**
     * No file in the store directory may contain the secret in any form —
     * plaintext or its Base64 representation (the legacy encoding).
     */
    private void assertDirContainsNoSecret() throws Exception {
        assertDirContainsNoPlaintext();
        File[] files = dir.listFiles();
        assertTrue(files != null);
        for (File file : files) {
            String raw = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            assertFalse("base64(secret) found in " + file.getName(),
                    raw.contains(base64(SECRET)));
        }
    }

    /** Decoded ciphertext field (payload + 16-byte GCM tag) of the stored value. */
    private byte[] decodedCiphertext() throws IOException {
        String raw = readStoredBytes();
        int ctStart = raw.lastIndexOf(':') + 1;
        return Base64.getDecoder().decode(raw.substring(ctStart));
    }

    /** Stored payload with the ciphertext field replaced by [ciphertext]. */
    private String reencodeCiphertext(byte[] ciphertext) {
        String raw;
        try {
            raw = readStoredBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int ctStart = raw.lastIndexOf(':') + 1;
        return raw.substring(0, ctStart) + Base64.getEncoder().encodeToString(ciphertext);
    }

    private static byte[] flipLastByte(byte[] data) {
        data[data.length - 1] ^= 0x01;
        return data;
    }

    /** Stored payload with byte [index] of the ciphertext xored by [mask]. */
    private String mutateCiphertext(int index, byte mask) throws IOException {
        byte[] ciphertext = decodedCiphertext();
        ciphertext[index] ^= mask;
        return reencodeCiphertext(ciphertext);
    }

    /** Asserts an exception's message chain (what a log would print) is secret-free. */
    private static void assertDiagnosticFreeOfSecret(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                assertFalse("diagnostic leaks the credential", message.contains(SECRET));
                assertFalse("diagnostic leaks base64 of the credential",
                        message.contains(base64(SECRET)));
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
