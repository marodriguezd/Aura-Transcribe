package dev.notune.transcribe;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Persists the user's dark-mode choice and applies it.
 *
 * Stored as a small file in the app's private files dir (not SharedPreferences)
 * so the value is reliably visible to the IME surface as well,
 * matching the existing marker-file settings (auto_record, etc.).
 *
 * Values are AppCompatDelegate night-mode constants:
 *   MODE_NIGHT_FOLLOW_SYSTEM (-1) | MODE_NIGHT_NO (1) | MODE_NIGHT_YES (2)
 */
public final class ThemePrefs {
    private static final String TAG = "ThemePrefs";
    private static final String FILE_NAME = "theme_mode";

    private ThemePrefs() {}

    public static int getMode(Context context) {
        int mode = MarkerFileHelper.readInt(context, FILE_NAME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (mode == AppCompatDelegate.MODE_NIGHT_NO
                || mode == AppCompatDelegate.MODE_NIGHT_YES
                || mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            return mode;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** Persists the choice and applies it (AppCompat recreates running activities). */
    public static void setMode(Context context, int mode) {
        MarkerFileHelper.writeInt(context, FILE_NAME, mode);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** Applies the persisted choice to AppCompat. Call from Application.onCreate(). */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(getMode(context));
    }

    /**
     * Returns a context whose resources reflect the chosen night mode, for the IME
     * (a non-AppCompat Service, so AppCompat's delegate cannot theme it; it runs in
     * the same process as the app — the manifest declares no android:process).
     * FOLLOW_SYSTEM returns the base unchanged so it follows the system.
     */
    public static Context wrapForNight(Context base, int mode) {
        int night;
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            night = Configuration.UI_MODE_NIGHT_YES;
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            night = Configuration.UI_MODE_NIGHT_NO;
        } else {
            return base; // follow system
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(config);
    }

    /** True if the given context currently resolves to night (dark) resources. */
    public static boolean isNight(Context context) {
        int flags = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return flags == Configuration.UI_MODE_NIGHT_YES;
    }
}
