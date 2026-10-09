package dev.notune.transcribe;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.util.Locale;

/**
 * Applies the saved dark-mode choice before any activity is created, and enables
 * Material You dynamic color on Android 12+.
 *
 * <p>Process model note (corrected 2026, Android 17 pass): this application is
 * <strong>single-process</strong>. The manifest declares no
 * {@code android:process}, so the IME, the recognition service, the overlay and
 * the Activities all run in the default process. Earlier revisions of this
 * project's documentation described an isolated {@code :ime} process; that was
 * never implemented, and adding it would be actively harmful — the Rust voice
 * session deliberately shares one {@code cpal} stream and one
 * {@code Arc<Mutex<Engine>>} between the IME and the popup, which requires a
 * single process. Settings still live in marker files because the native engine
 * reads them from the filesystem and they must be readable no matter which
 * component started first.
 *
 * <p>Also defaults the transcription language to automatic detection on first
 * run: the bundled Nemotron 3.5 ASR model detects the spoken language natively
 * across 40 language-locales. The device's current language is kept in a
 * separate marker ({@code device_language}) used by the engine as the fallback
 * hint for models without native detection (e.g. Canary).
 */
public class App extends Application {
    private static final String TAG = "App";
    private static final String LANGUAGE_FILE = "model_language";
    private static final String DEVICE_LANGUAGE_FILE = "device_language";

    @Override
    public void onCreate() {
        super.onCreate();
        enableStrictModeInDebug();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);

        // First-run bootstrap + the one-time legacy→marker settings migration.
        // Both are filesystem work and the migration may touch Android Keystore,
        // neither of which belongs on the UI thread: Application.onCreate is
        // called on the main thread, and a slow Keystore open there delays the
        // first frame. Nothing awaits this any more (the previous
        // CountDownLatch + bounded wait on the settings screen is gone, see
        // SettingsManager.writeMarkerIfAbsent for how the race it guarded
        // against is now impossible by construction), so a few milliseconds of
        // delay is invisible to every consumer: all settings are read lazily
        // from marker files.
        new Thread(() -> {
            try {
                applyDeviceLanguageIfUnset();
            } catch (RuntimeException t) {
                Log.e(TAG, "First-run language bootstrap failed", t);
            }
            try {
                SettingsManager.migrateIfNeeded(this);
            } catch (Exception t) {
                // Never let the background migration kill the process (review
                // finding, 2026-08-06): an unexpected RuntimeException (e.g.
                // ClassCastException while reading a legacy pref) would
                // otherwise crash the app from this thread. The migration is
                // best-effort — settings are read lazily from markers, so a
                // failed migration only means the legacy values stay unmoved.
                // Errors (OOM, ThreadDeath) are deliberately not caught so a
                // genuinely fatal condition still surfaces to the system.
                Log.e(TAG, "Post-processing migration failed", t);
            }
        }, "app-bootstrap").start();
    }

    /**
     * Debug-only StrictMode diagnostics (§51). Detects main-thread disk/network
     * I/O, leaked closeables and leaked Activities, and reports them in logcat.
     * {@code penaltyLog()} only — never {@code penaltyDeath()} — so a diagnostic
     * can never turn into a debug crash. Release builds are untouched: StrictMode
     * itself is not enabled there, so there is no production overhead.
     */
    private void enableStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return;
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build());
            StrictMode.VmPolicy.Builder vm = new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog();
            vm.detectFileUriExposure();
            StrictMode.setVmPolicy(vm.build());
        } catch (RuntimeException e) {
            // StrictMode misconfiguration must never break the app.
            Log.w(TAG, "Could not enable StrictMode", e);
        }
    }

    /**
     * Writes "auto" as the transcription language the first time the app runs,
     * when no language has been chosen yet (the bundled model detects the
     * language natively). The device locale (BCP-47 tag, e.g. "es-ES", "en-US",
     * "fr-FR") goes to {@code device_language}, the engine's fallback hint for
     * models without native detection.
     *
     * <p>Safe to run off the main thread: when neither marker exists the engine
     * treats the language exactly as it treats "auto" (no hint — see
     * src/engine.rs), so a model load that races this bootstrap gets the same
     * behaviour it would have after it.
     */
    private void applyDeviceLanguageIfUnset() {
        File f = new File(getFilesDir(), LANGUAGE_FILE);
        if (f.exists()) return;
        writeConfig(LANGUAGE_FILE, "auto");
        writeConfig(DEVICE_LANGUAGE_FILE, Locale.getDefault().toLanguageTag());
    }

    private void writeConfig(String name, String value) {
        // Atomic temp+rename write so no reader ever sees a partially-written
        // language marker (P1.2). Non-fatal on failure: the model falls back to
        // its default language.
        MarkerFileHelper.writeString(this, name, value);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            PostProcessor.nativeTrimMemory(level);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // The native library is absent (wrong ABI) — nothing to trim.
        }
    }
}
