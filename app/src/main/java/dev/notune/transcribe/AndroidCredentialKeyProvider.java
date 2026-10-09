package dev.notune.transcribe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Resolves the AES-256 key used by {@link SecureCredentialStore} from Android
 * Keystore.
 *
 * <p>The key is generated on first use, non-exportable, and never leaves the
 * Keystore — it is not stored next to the encrypted data, not hardcoded, and
 * not derived from anything an attacker with only the app's data directory can
 * compute. No user authentication is required for use so background callers
 * (the app-bootstrap migration thread, the settings screen) can decrypt while
 * the device is unlocked; the key itself is hardware-backed where the device
 * provides a TEE/StrongBox-backed Keystore.
 *
 * <p>This is the only Android/Keystore-specific piece of the credential store:
 * everything else in {@link SecureCredentialStore} is plain Java and covered by
 * JVM tests, so the untested-on-CI surface is exactly this thin class (Keystore
 * integration needs a real device — see AGENTS.md §5.4).
 */
final class AndroidCredentialKeyProvider implements SecureCredentialStore.KeyProvider {

    static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    static final String KEY_ALIAS = "aura_transcribe_credential_key";

    // Serialises key generation: two racing first-use callers must not both run
    // generateKey() for the same alias, because the second generation would
    // replace the alias' key and orphan any ciphertext the first caller already
    // wrote. Static because SettingsManager creates one store per instance.
    private static final Object GENERATION_LOCK = new Object();

    @Override
    public SecretKey get() throws GeneralSecurityException {
        synchronized (GENERATION_LOCK) {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            try {
                keyStore.load(null);
            } catch (IOException e) {
                // Wrapped into GeneralSecurityException so the store reports it
                // as an explicit credential-storage failure.
                throw new KeyStoreException("Could not open the Android Keystore", e);
            }
            Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Default (and deliberately explicit): randomized encryption
                    // is required, so the Keystore generates a fresh IV per
                    // encryption and ciphertext never repeats for equal inputs.
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }
}
