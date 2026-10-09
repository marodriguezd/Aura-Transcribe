package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract tests for {@link CredentialRead}: what a read of the stored API key
 * observes for each on-disk state.
 *
 * <p>This is the decision {@code SettingsManager.getApiKey()} makes on every
 * dictation (and on every "is post-processing configured?" probe). It is pure, so
 * these tests are the whole decision table: no Android runtime, no Keystore, no
 * temporary files — the real store and the real files are driven in
 * {@link SecureCredentialStoreTest} and {@link CredentialMigrationTest}, which
 * go through this same policy.
 *
 * <p>The load-bearing case is the first one: a deletion tombstone means the user
 * deleted the credential, so a read must observe <em>nothing</em> even while an
 * encrypted file and a legacy copy are still on disk because the removal failed.
 * The remaining cases mirror the pre-existing storage rules and are pinned here so
 * a later refactor cannot quietly reintroduce a plaintext fallback.
 */
public class CredentialReadTest {

    private static final String STORED = "sk-encrypted-store_1111111";
    private static final String LEGACY = "sk-legacy-base64-marker_2222";

    @Test
    public void theStatusTellsAFailureApartFromAConfirmedAbsence() {
        // The status is what keeps a corrupt store from being reported to the user
        // as "not configured" (or worse, as a refined transcript).
        ApiKeyRead loaded = CredentialRead.result(false, true, STORED, null, false);
        assertEquals(ApiKeyRead.Status.LOADED, loaded.status());
        assertEquals(STORED, loaded.key());
        assertTrue(loaded.hasCredential());

        ApiKeyRead absent = CredentialRead.result(false, false, null, null, false);
        assertEquals(ApiKeyRead.Status.ABSENT, absent.status());
        assertEquals("", absent.key());
        assertFalse(absent.hasCredential());

        ApiKeyRead failed = CredentialRead.result(false, true, null, LEGACY, true);
        assertEquals("a store that exists but cannot be read is not absent",
                ApiKeyRead.Status.UNREADABLE, failed.status());
        assertEquals("", failed.key());
        assertFalse("an unreadable credential is not a usable one", failed.hasCredential());

        ApiKeyRead badMarker = CredentialRead.result(false, false, null, null, true);
        assertEquals(ApiKeyRead.Status.UNREADABLE, badMarker.status());
    }

    @Test
    public void aPendingDeletionIsAConfirmedAbsenceNotAReadFailure() {
        // The user asked for the credential to be gone, so nothing may report an
        // error for it — even when a copy is still on disk and unreadable.
        ApiKeyRead deleted = CredentialRead.result(true, true, STORED, LEGACY, true);
        assertEquals(ApiKeyRead.Status.ABSENT, deleted.status());
        assertEquals("", deleted.key());
        assertFalse(deleted.hasCredential());
    }

    @Test
    public void aBlankStoredValueIsAbsenceRatherThanALoadedCredential() {
        // "Configured" must not drift between call sites: the rule every caller used
        // to spell out as key.trim().isEmpty() is part of the read result.
        assertEquals(ApiKeyRead.Status.ABSENT,
                CredentialRead.result(false, true, "", null, false).status());
        assertEquals(ApiKeyRead.Status.ABSENT,
                CredentialRead.result(false, true, "   ", null, false).status());
        assertEquals(ApiKeyRead.Status.ABSENT,
                CredentialRead.result(false, false, null, "  ", false).status());
    }

    @Test
    public void aDiagnosticRenderingNeverCarriesKeyMaterial() {
        ApiKeyRead loaded = CredentialRead.result(false, true, STORED, null, false);
        assertFalse("a log line built from a read must not contain the credential",
                loaded.toString().contains(STORED));
        assertTrue(loaded.toString().contains("LOADED"));
    }

    @Test
    public void aDeletionPendingHidesEverySource() {
        // The defect this policy exists for: the store and a legacy copy are both
        // still readable, but the user deleted the credential, so a read must not
        // return either of them.
        assertEquals("", CredentialRead.resolve(true, true, STORED, LEGACY));
        assertEquals("", CredentialRead.resolve(true, false, null, LEGACY));
        assertEquals("", CredentialRead.resolve(true, true, null, null));
        assertEquals("", CredentialRead.resolve(true, false, STORED, null));
    }

    @Test
    public void aStoredCredentialIsServedWhenNoDeletionIsPending() {
        assertEquals(STORED, CredentialRead.resolve(false, true, STORED, LEGACY));
        assertEquals(STORED, CredentialRead.resolve(false, true, STORED, null));
    }

    @Test
    public void anUnreadableStoreReadsAsEmptyAndNeverFallsBackToTheLegacyMarker() {
        // Corrupt/tampered/unsupported/Keystore-unavailable: an explicit empty
        // value. A plaintext Base64 copy must never be promoted to "the credential".
        assertEquals("", CredentialRead.resolve(false, true, null, LEGACY));
        assertEquals("", CredentialRead.resolve(false, true, "", LEGACY));
    }

    @Test
    public void theLegacyMarkerIsServedOnlyWhileNoEncryptedStoreExists() {
        // The transition window on an upgraded installation, before the start-up
        // import has written the encrypted store.
        assertEquals(LEGACY, CredentialRead.resolve(false, false, null, LEGACY));
        assertEquals("", CredentialRead.resolve(false, false, null, null));
        assertEquals("", CredentialRead.resolve(false, false, null, ""));
    }

    @Test
    public void whitespaceOnlyValuesArePassedThroughNotNormalized() {
        // Deciding whether a whitespace key counts as "configured" belongs to the
        // callers (isPostProcessConfigured trims); a read must not silently edit or
        // drop what is stored.
        assertEquals("   ", CredentialRead.resolve(false, true, "   ", null));
        assertEquals("   ", CredentialRead.resolve(false, false, null, "   "));
    }

    @Test
    public void everyOutcomeIsNonNullAndNoCredentialIsAlwaysTheEmptyString() {
        // Callers use the result directly (isEmpty(), trim(), an HTTP header), so
        // "no credential" must be observable as empty rather than as null, and a
        // pending deletion must never yield anything else.
        for (boolean deletionPending : new boolean[] {false, true}) {
            for (boolean storeExists : new boolean[] {false, true}) {
                for (String stored : new String[] {null, "", STORED}) {
                    for (String legacyValue : new String[] {null, "", LEGACY}) {
                        String value = CredentialRead.resolve(
                                deletionPending, storeExists, stored, legacyValue);
                        assertTrue("a read never returns null", value != null);
                        if (deletionPending) {
                            assertTrue("a pending deletion is never overridden",
                                    value.isEmpty());
                        }
                    }
                }
            }
        }
    }
}
