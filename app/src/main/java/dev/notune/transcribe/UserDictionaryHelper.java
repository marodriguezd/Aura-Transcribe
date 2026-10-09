package dev.notune.transcribe;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Opens Android's Personal Dictionary settings.
 *
 * <h3>Why the programmatic sync was removed (Android 17 pass)</h3>
 * This class used to also query {@code UserDictionary.Words.CONTENT_URI} and copy
 * the system dictionary into the {@code custom_words} marker so the native
 * phonetic corrector picked those words up.
 *
 * <p>That path could never work. The content provider is guarded by
 * {@code android.permission.READ_USER_DICTIONARY}, which the platform deliberately
 * keeps out of the public SDK — {@code android.Manifest.permission} has no such
 * constant, so an app targeting a modern SDK cannot request it at runtime (it is
 * a {@code @SystemApi}). The manifest declared the permission anyway, which is
 * worse than useless: it advertises an access the app can never hold. At runtime
 * the query always threw {@code SecurityException}, was caught, and was logged as
 * "query skipped/unavailable" — i.e. the feature silently never worked, while
 * costing a failing cross-process ContentResolver call on every launch of the
 * app, the keyboard and the voice popup.
 *
 * <p>The user-facing capability is unchanged and still fully available: the app
 * has its own custom-words editor ({@link CustomWordsActivity}), which is what the
 * native corrector actually reads, and this helper still takes the user to
 * Android's Personal Dictionary so they can manage their system words there.
 *
 * <p>If Android ever publishes a supported way to read the user dictionary, the
 * sync can be reinstated behind a real runtime permission request.
 */
public class UserDictionaryHelper {

    private static final String TAG = "UserDictionaryHelper";

    /** Marker file holding the user's custom words, read by src/corrector.rs. */
    public static final String CUSTOM_WORDS_FILE = "custom_words";

    /**
     * Opens Android's Personal Dictionary settings screen, where the user manages
     * their own words. Falls back to the generic settings screen (and then to a
     * log entry) on builds that do not expose this screen, so it can never throw.
     */
    public static void openSystemUserDictionarySettings(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "ACTION_USER_DICTIONARY_SETTINGS not found, trying string action fallback", e);
            try {
                Intent fallback = new Intent("android.settings.USER_DICTIONARY_SETTINGS");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (RuntimeException ex) {
                Log.w(TAG, "USER_DICTIONARY_SETTINGS fallback failed, opening general settings", ex);
                try {
                    Intent genSettings = new Intent(Settings.ACTION_SETTINGS);
                    genSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(genSettings);
                } catch (RuntimeException ex2) {
                    Log.e(TAG, "Failed to open settings", ex2);
                }
            }
        }
    }
}
