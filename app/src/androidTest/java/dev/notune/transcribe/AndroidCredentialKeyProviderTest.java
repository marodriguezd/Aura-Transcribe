package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Device-level coverage for the only credential-store surface the JVM tests
 * cannot reach: {@link AndroidCredentialKeyProvider} against the real Android
 * Keystore (TEE/StrongBox where the device provides it), and AES-GCM through
 * the Keystore-backed key rather than a plain JCA key.
 *
 * <p>These tests are deliberately non-destructive: the Keystore alias is never
 * deleted (on a dev device it protects the user's real key; deleting it would
 * force a re-entry), and only the scratch store inside the app cache dir is
 * cleaned up.
 *
 * <p>NOT executed in CI — there is no arm64 device/emulator (AGENTS.md §5.4).
 * Compile check: {@code ./gradlew :app:assembleDebugAndroidTest}. On a device:
 * {@code ./gradlew :app:connectedDebugAndroidTest}.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidCredentialKeyProviderTest {

    private static final String SECRET = "sk-device-keystoreRoundTrip_123";

    private File dir;
    private SecureCredentialStore store;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "cred-store-device-test");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        store = new SecureCredentialStore(dir, new AndroidCredentialKeyProvider());
    }

    @After
    public void tearDown() {
        store.delete();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    @Test
    public void roundTripThroughTheRealKeystore() throws Exception {
        store.store(SECRET);
        assertEquals(SECRET, store.read());

        // The persisted bytes are the versioned ciphertext, not the secret.
        byte[] raw = Files.readAllBytes(new File(dir, SecureCredentialStore.FILE_NAME).toPath());
        String payload = new String(raw, StandardCharsets.UTF_8);
        assertTrue(payload.startsWith(SecureCredentialStore.FORMAT_PREFIX));
        assertFalse(payload.contains(SECRET));
    }

    @Test
    public void theAliasKeyIsStableAcrossProviderInstances() throws Exception {
        store.store(SECRET);
        // A second provider instance must resolve the SAME Keystore alias:
        // regeneration here would orphan the ciphertext written above.
        SecureCredentialStore second =
                new SecureCredentialStore(dir, new AndroidCredentialKeyProvider());
        assertEquals(SECRET, second.read());
    }

    @Test
    public void tamperedCiphertextFailsClosedOnDevice() throws Exception {
        store.store(SECRET);
        File file = new File(dir, SecureCredentialStore.FILE_NAME);
        String payload = new String(
                Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int ctStart = payload.lastIndexOf(':') + 1;
        byte[] ciphertext = java.util.Base64.getDecoder()
                .decode(payload.substring(ctStart));
        ciphertext[0] ^= 0x01;
        String tampered = payload.substring(0, ctStart)
                + java.util.Base64.getEncoder().encodeToString(ciphertext);
        Files.write(file.toPath(), tampered.getBytes(StandardCharsets.UTF_8));

        try {
            store.read();
            fail("expected CredentialStoreException for tampered ciphertext");
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // The Keystore-backed GCM tag check rejected it — no fallback.
        }
    }

    @Test
    public void deleteRemovesTheStoreAndReadsReturnAbsent() throws Exception {
        store.store(SECRET);
        store.delete();
        assertFalse(new File(dir, SecureCredentialStore.FILE_NAME).exists());
        assertNull(store.read());
    }

    @Test
    public void tamperedCiphertextFailsClosedWithoutLegacyOrPlaintextFallback() throws Exception {
        // Place a legacy marker in the directory before storing the secure secret
        String legacySecret = "sk-legacy-unencrypted-456";
        File legacyFile = new File(dir, SecureCredentialStore.LEGACY_MARKER_FILE);
        String legacyB64 = java.util.Base64.getEncoder().encodeToString(
                legacySecret.getBytes(StandardCharsets.UTF_8));
        Files.write(legacyFile.toPath(), legacyB64.getBytes(StandardCharsets.UTF_8));

        store.store(SECRET);
        File encFile = new File(dir, SecureCredentialStore.FILE_NAME);
        String payload = new String(
                Files.readAllBytes(encFile.toPath()), StandardCharsets.UTF_8);
        int ctStart = payload.lastIndexOf(':') + 1;
        byte[] ciphertext = java.util.Base64.getDecoder()
                .decode(payload.substring(ctStart));
        ciphertext[0] ^= 0x01;
        String tampered = payload.substring(0, ctStart)
                + java.util.Base64.getEncoder().encodeToString(ciphertext);
        Files.write(encFile.toPath(), tampered.getBytes(StandardCharsets.UTF_8));

        try {
            String result = store.read();
            fail("expected CredentialStoreException for tampered ciphertext, got: " + result);
        } catch (SecureCredentialStore.CredentialStoreException expected) {
            // Authentication tag failure must fail closed without returning legacy secret or plaintext
        }

        // Verify the legacy marker was not removed or returned
        assertTrue("Legacy marker must remain untouched", legacyFile.exists());
    }

    @Test
    public void cleanupOfTestFilesLeavesKeystoreKeyIntactAndUsable() throws Exception {
        store.store(SECRET);
        // Simulate test cleanup (deleting test store files without touching the Keystore alias)
        store.delete();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        // Prove that the Keystore alias remains completely valid and usable across store lifecycles
        SecureCredentialStore freshStore =
                new SecureCredentialStore(dir, new AndroidCredentialKeyProvider());
        String nextSecret = "sk-post-cleanup-key-intact-789";
        freshStore.store(nextSecret);
        assertEquals(nextSecret, freshStore.read());
        freshStore.delete();
    }
}
