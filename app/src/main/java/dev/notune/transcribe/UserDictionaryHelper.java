package dev.notune.transcribe;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Opens Android's Personal Dictionary settings.
 *
 * <p>Programmatic sync via {@code UserDictionary.Words.CONTENT_URI} is omitted
 * because {@code READ_USER_DICTIONARY} is a hidden {@code @SystemApi} permission
 * unavailable to modern third-party apps. Users manage words in {@link CustomWordsActivity}
 * or via system settings opened here.</p>
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
            Log.w(TAG, "ACTION_USER_DICTIONARY_SETTINGS not found, opening general settings", e);
            try {
                Intent genSettings = new Intent(Settings.ACTION_SETTINGS);
                genSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(genSettings);
            } catch (RuntimeException ex) {
                Log.e(TAG, "Failed to open settings", ex);
            }
        }
    }
}
