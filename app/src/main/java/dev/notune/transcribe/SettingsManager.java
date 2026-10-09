package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal settings store for the AI post-processing layer.
 *
 * All settings (enabled flag, provider, base URL, model, system prompt and
 * API key) are stored as marker files in {@code filesDir()} so they can be
 * read consistently by the Java UI and by the native engine (which reads them
 * straight from the filesystem), without SharedPreferences or Keystore.
 * The API key is Base64-encoded for minimal obscurity; real protection comes
 * from the Android app sandbox that guards filesDir().
 */
public class SettingsManager implements PostProcessor.PostProcessorSettings {
    private static final String TAG = "SettingsManager";

    // SharedPreferences file used only for the one-time legacy migration.
    private static final String PREFS_NAME = "transcribe_settings";

    // Legacy keys, kept only for the one-time migration to marker files.
    private static final String LEGACY_KEY_POST_PROCESS_ENABLED = "post_process_enabled";
    private static final String LEGACY_KEY_PROVIDER = "pp_provider";
    private static final String LEGACY_KEY_API_URL = "api_url";
    private static final String LEGACY_KEY_MODEL_NAME = "model_name";
    private static final String LEGACY_KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String LEGACY_KEY_API_KEY = "api_key";
    private static final String LEGACY_ENCRYPTED_PREFS_NAME = PREFS_NAME + "_encrypted";

    // Marker file names in filesDir(). Presence of pp_enabled means ON.
    private static final String PP_ENABLED_FILE = "pp_enabled";
    private static final String PP_PROVIDER_FILE = "pp_provider";
    private static final String PP_API_URL_FILE = "pp_api_url";
    private static final String PP_MODEL_FILE = "pp_model";
    private static final String PP_PROMPT_FILE = "pp_prompt";
    private static final String PP_API_KEY_FILE = "pp_api_key";
    private static final String PP_PRESET_FILE = "pp_preset";
    private static final String MIC_MODE_FILE = "mic_mode";

    // Sentinel that guarantees the legacy -> marker migration runs at most once.
    private static final String MIGRATION_SENTINEL = "pp_migrated";
    private static final String API_KEY_MIGRATION_SENTINEL = "pp_api_key_migrated";

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    public static final String PROVIDER_LOCAL_S1 = "local_s1";

    /**
     * Whether the on-device S1-mini provider can actually refine text in this
     * build. It cannot, and that is a verified statement rather than a TODO.
     *
     * <p>The only inference backend in the dependency graph is
     * {@code transcribe-cpp 0.1.3}, whose public surface is speech recognition:
     * {@code Session::run(pcm, &RunOptions)} with {@code Task::Transcribe} or
     * {@code Task::Translate}. It has no text-generation entry point — no prompt
     * decode, no sampler, no completion, no tokenizer encode path for a
     * Qwen-family model (its {@code Feature::InitialPrompt} is a Whisper decode
     * hint, not generation). SuperWhisper S1-mini is a Qwen-based causal LM, so
     * running it needs a text-generation engine (llama.cpp or equivalent) plus a
     * BPE tokenizer, KV cache and sampler that this project does not ship.
     *
     * <p>{@code src/post_processor.rs::normalize_text_on_device} consequently
     * returns the trimmed input; see AGENTS.md §5.4 item 1. Until that changes,
     * the provider must not be selectable or enableable, because doing so would
     * present "AI cleanup" that silently returns the transcript unchanged.
     *
     * <p>Everything else around the provider is kept deliberately (the provider
     * catalogue entry, the download/status helpers, the JNI surface and the
     * native prompt builder) so that completing the inference path is additive.
     */
    public static final boolean LOCAL_S1_INFERENCE_AVAILABLE = false;

    /**
     * Whether [id] can perform post-processing at all in this build. Used by the
     * settings UI to disable the controls that would otherwise let a user enable
     * a provider that cannot work.
     */
    public static boolean isProviderAvailable(String id) {
        return !PROVIDER_LOCAL_S1.equals(id) || LOCAL_S1_INFERENCE_AVAILABLE;
    }

    /**
     * The "enabled" value that may actually be persisted for [providerId].
     *
     * <p>The settings screen is not the only thing that decides whether one of the
     * recording surfaces runs post-processing: they all read the `pp_enabled`
     * marker file. So a stale screen state — restored switch, a provider left over
     * in the marker from an older build, a caller that forgets to check — must not
     * be able to turn the feature on for a provider that cannot run. This is the
     * single rule that both {@code PostProcessSettingsActivity.save()} and the JVM
     * tests use, so the two cannot disagree.
     *
     * @param providerId the provider the user selected
     * @param requested  the switch state the UI wants to persist
     * @return [requested] when [providerId] is available in this build, otherwise
     *         {@code false}
     */
    public static boolean resolveEnabledOnSave(String providerId, boolean requested) {
        return requested && isProviderAvailable(providerId);
    }

    public static final String PRESET_CLEAN = "clean";
    public static final String PRESET_FORMAL = "formal";
    public static final String PRESET_CASUAL = "casual";
    public static final String PRESET_VERBATIM = "verbatim";

    /**
     * Provider presets: known OpenAI-compatible endpoints. The user picks a
     * provider and only fills in the API key; "custom" exposes a free-form
     * base-URL field.
     */
    public static final class Provider {
        public final String id;
        public final String label;
        public final String baseUrl;      // null for custom (user-supplied)
        public final String defaultModel;

        Provider(String id, String label, String baseUrl, String defaultModel) {
            this.id = id;
            this.label = label;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    public static final Provider[] PROVIDERS = new Provider[] {
        // The label carries the limitation on purpose: it is what the provider
        // dropdown renders, and a user who picks "On-Device" must not be left
        // believing the transcript is being refined on the phone. See
        // LOCAL_S1_INFERENCE_AVAILABLE above for the exact blocker.
        new Provider(PROVIDER_LOCAL_S1,
                "On-Device (SuperWhisper S1-mini) \u2014 unavailable in this build",
                null, "s1-mini-q4_k_m.gguf"),
        new Provider("groq",      "Groq",       "https://api.groq.com/openai/v1",       "llama-3.3-70b-versatile"),
        new Provider("openai",    "OpenAI",     "https://api.openai.com/v1",            "gpt-4o-mini"),
        new Provider("cerebras",  "Cerebras",   "https://api.cerebras.ai/v1",           "llama-3.3-70b"),
        new Provider("openrouter","OpenRouter", "https://openrouter.ai/api/v1",         "meta-llama/llama-3.3-70b-instruct"),
        new Provider("mistral",   "Mistral",    "https://api.mistral.ai/v1",            "mistral-small-latest"),
        new Provider("together",  "Together",   "https://api.together.xyz/v1",          "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        new Provider("ollama",    "Ollama (local)", "http://localhost:11434/v1",        "llama3.2"),
        new Provider("custom",    "Custom",     null,                                    ""),
    };

    public static Provider providerById(String id) {
        for (Provider p : PROVIDERS) {
            if (p.id.equals(id)) return p;
        }
        return PROVIDERS[PROVIDERS.length - 1]; // custom
    }

    private final Context appContext;

    public SettingsManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public Context getContext() {
        return appContext;
    }

    // ----------------------------------------------------------------------
    // Toggle (marker file)
    // ----------------------------------------------------------------------

    public boolean isPostProcessEnabled() {
        return MarkerFileHelper.exists(appContext, PP_ENABLED_FILE) && isPostProcessConfigured();
    }

    public boolean isPostProcessMarkerSet() {
        return MarkerFileHelper.exists(appContext, PP_ENABLED_FILE);
    }

    public boolean isPostProcessConfigured() {
        String provider = getProviderId();
        if (PROVIDER_LOCAL_S1.equals(provider)) {
            // The model being on disk is not enough: without an inference engine
            // the provider produces no refinement at all, so treating it as
            // "configured" is what let the nonfunctional path be enabled.
            return LOCAL_S1_INFERENCE_AVAILABLE && isLocalS1ModelInstalled();
        }
        String key = getApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public void setPostProcessEnabled(boolean enabled) {
        if (enabled && !isPostProcessConfigured()) {
            MarkerFileHelper.setExists(appContext, PP_ENABLED_FILE, false);
            return;
        }
        MarkerFileHelper.setExists(appContext, PP_ENABLED_FILE, enabled);
    }

    public static boolean isPostProcessEnabled(Context context) {
        if (!MarkerFileHelper.exists(context, PP_ENABLED_FILE)) {
            return false;
        }
        SettingsManager sm = new SettingsManager(context);
        return sm.isPostProcessConfigured();
    }

    // ----------------------------------------------------------------------
    // Provider + effective base URL (marker file)
    // ----------------------------------------------------------------------

    public String getApiUrl() {
        String value = readMarker(PP_API_URL_FILE);
        return (value == null || value.isEmpty()) ? DEFAULT_API_URL : value;
    }

    public void setApiUrl(String url) {
        writeMarker(PP_API_URL_FILE, url);
    }

    public String getProviderId() {
        String value = readMarker(PP_PROVIDER_FILE);
        return (value == null || value.isEmpty()) ? "custom" : value;
    }

    public void setProviderId(String id) {
        writeMarker(PP_PROVIDER_FILE, id);
    }

    public String getEffectiveApiUrl() {
        Provider p = providerById(getProviderId());
        if (p.baseUrl != null) return p.baseUrl;
        return getApiUrl();
    }

    // ----------------------------------------------------------------------
    // API key (marker file, Base64-encoded)
    // ----------------------------------------------------------------------

    public String getApiKey() {
        String encoded = readMarker(PP_API_KEY_FILE);
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            return new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Never include the marker contents or decoded key in diagnostics.
            Log.e(TAG, "Failed to decode API key from marker", e);
            return "";
        }
    }

    public void setApiKey(String key) {
        if (key == null || key.isEmpty()) {
            writeMarker(PP_API_KEY_FILE, null);
            return;
        }
        writeMarker(PP_API_KEY_FILE,
                Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    // ----------------------------------------------------------------------
    // Model name + prompt (marker files)
    // ----------------------------------------------------------------------

    public String getModelName() {
        String value = readMarker(PP_MODEL_FILE);
        return (value == null || value.isEmpty()) ? DEFAULT_MODEL : value;
    }

    public void setModelName(String model) {
        writeMarker(PP_MODEL_FILE, model);
    }

    public String getActivePromptBody() {
        String p = readMarker(PP_PROMPT_FILE);
        return (p == null || p.trim().isEmpty()) ? getDefaultPrompt() : p;
    }

    public void setActivePromptBody(String prompt) {
        writeMarker(PP_PROMPT_FILE, prompt);
    }

    public String getDefaultPrompt() {
        return appContext.getString(R.string.pp_default_prompt);
    }

    public String getPostProcessPreset() {
        String p = readMarker(PP_PRESET_FILE);
        return (p == null || p.isEmpty()) ? PRESET_CLEAN : p;
    }

    public void setPostProcessPreset(String preset) {
        writeMarker(PP_PRESET_FILE, preset);
    }

    public File getLocalS1ModelFile() {
        File modelsDir = new File(appContext.getFilesDir(), "models");
        File preferred = new File(modelsDir, "s1-mini-q4_k_m.gguf");
        if (preferred.exists()) return preferred;
        File alt = new File(modelsDir, "s1-mini.gguf");
        if (alt.exists()) return alt;
        return preferred;
    }

    public boolean isLocalS1ModelInstalled() {
        return getLocalS1ModelFile().exists();
    }

    public String getMicMode() {
        String val = readMarker(MIC_MODE_FILE);
        return val != null && !val.trim().isEmpty() ? val.trim() : AudioDeviceManager.MIC_MODE_BUILTIN_ONLY;
    }

    public void setMicMode(String mode) {
        writeMarker(MIC_MODE_FILE, mode != null ? mode : AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
    }

    // ----------------------------------------------------------------------
    // Marker file helpers
    // ----------------------------------------------------------------------

    private String readMarker(String fileName) {
        return MarkerFileHelper.readString(appContext, fileName, null);
    }

    private void writeMarker(String fileName, String value) {
        MarkerFileHelper.writeString(appContext, fileName, value);
    }

    // ----------------------------------------------------------------------
    // One-time migration from SharedPreferences to marker files.
    //
    // Runs on first launch after an update.
    // Concurrency concerns this protects against:
    //   1. Sentinel check + legacy read + marker write is a TOCTOU race:
    //      both processes can pass the sentinel check, read the legacy
    //      SharedPreferences, and queue their own marker writes.
    //   2. The IME process can lag behind the main process. If the user
    //      disables post-processing (deletes the marker) while the IME is
    //      still in its migration block, a careless migration would resurrect
    //      the marker right after the user removed it.
    //
    // Fix: serialize the whole migration with an OS-level file lock on
    // filesDir()/pp_migrated.lock, and clear the legacy SharedPreferences
    // with a synchronous `commit()` before the sentinel. The lock guarantees
    // that any process waiting for us will only call `getSharedPreferences()`
    // AFTER our commit() returns, so it loads the cleared values and does not
    // resurrect any marker. `apply()` would not be enough because it is async:
    // a trailing process could read the legacy file before our async write
    // flushed, see the stale value, and recreate the marker.
    // ----------------------------------------------------------------------

    public static void migrateIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        File filesDir = app.getFilesDir();
        File sentinel = new File(filesDir, MIGRATION_SENTINEL);
        if (sentinel.exists()) {
            // The ordinary settings migration may already be complete while a
            // previous process could not open Android Keystore. Retry the API
            // key migration independently under its own process lock so main
            // and any other starter cannot read/clean the legacy store concurrently.
            migrateLegacyApiKeyLocked(app, filesDir);
            return;
        }

        File lockFile = new File(filesDir, MIGRATION_SENTINEL + ".lock");
        boolean migratedThisCall = false;
        FileLock lock = null;
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileChannel channel = fos.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                // Another process is migrating. Return immediately: the next
                // App.onCreate in this process will catch up via the sentinel
                // check. Avoid sleeping on the main thread (App.onCreate is
                // on the UI thread) because the
                // typical migration is <10ms.
                return;
            }

            // Re-check sentinel under the lock (the previous holder may have
            // completed in the meantime).
            if (sentinel.exists()) return;

            SharedPreferences legacy = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            if (legacy.contains(LEGACY_KEY_POST_PROCESS_ENABLED)) {
                boolean enabled = legacy.getBoolean(LEGACY_KEY_POST_PROCESS_ENABLED, false);
                if (enabled) {
                    try {
                        new File(filesDir, PP_ENABLED_FILE).createNewFile();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to create pp_enabled marker during migration", e);
                    }
                }
            }

            if (legacy.contains(LEGACY_KEY_PROVIDER)) {
                        writeMarkerIfAbsent(filesDir, PP_PROVIDER_FILE, legacy.getString(LEGACY_KEY_PROVIDER, "custom"));
            }

            if (legacy.contains(LEGACY_KEY_API_URL)) {
                writeMarkerIfAbsent(filesDir, PP_API_URL_FILE, legacy.getString(LEGACY_KEY_API_URL, DEFAULT_API_URL));
            }

            if (legacy.contains(LEGACY_KEY_MODEL_NAME)) {
                writeMarkerIfAbsent(filesDir, PP_MODEL_FILE, legacy.getString(LEGACY_KEY_MODEL_NAME, DEFAULT_MODEL));
            }

            if (legacy.contains(LEGACY_KEY_SYSTEM_PROMPT)) {
                String prompt = legacy.getString(LEGACY_KEY_SYSTEM_PROMPT, "");
                if (prompt != null && !prompt.isEmpty()) {
                    writeMarkerIfAbsent(filesDir, PP_PROMPT_FILE, prompt);
                }
            }

            // The API key was historically stored in EncryptedSharedPreferences,
            // not in the ordinary legacy preferences above. Read it with the
            // exact old MasterKey/prefs name before clearing legacy state, then
            // immediately move it into the cross-process marker store. If the
            // old Keystore entry is unavailable (device restore, key invalidation
            // or a corrupted legacy file), leave the marker absent: the runtime
            // fast-fail path will ask the user to re-enter the key rather than
            // sending an unauthenticated request and hiding the real cause.
            // The outer migration lock already serializes this process-wide
            // block; do not acquire the API-key lock recursively here.
            boolean apiKeyMigrationReady = migrateLegacyApiKey(app, filesDir, legacy);

            // Synchronously commit the legacy-key removal (see class comment).
            try {
                SharedPreferences.Editor editor = legacy.edit();
                editor.remove(LEGACY_KEY_POST_PROCESS_ENABLED);
                editor.remove(LEGACY_KEY_PROVIDER);
                editor.remove(LEGACY_KEY_API_URL);
                editor.remove(LEGACY_KEY_MODEL_NAME);
                editor.remove(LEGACY_KEY_SYSTEM_PROMPT);
                // The API key is removed only after migrateLegacyApiKey has
                // copied it successfully. If the old encrypted store cannot
                // be opened, retaining this plain fallback lets the next app
                // start recover it instead of destroying the last copy.
                if (apiKeyMigrationReady) {
                    editor.remove(LEGACY_KEY_API_KEY);
                }
                editor.commit();
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear legacy SharedPreferences", e);
            }

            // Create the sentinel LAST so any trailing process that races
            // with us and somehow acquires the lock next sees the migration
            // as complete and exits early.
            try {
                sentinel.createNewFile();
                migratedThisCall = true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to create migration sentinel", e);
            }
        } catch (OverlappingFileLockException e) {
            // Another thread in the same JVM already holds the lock; treat as
            // success (it will complete the migration).
            Log.d(TAG, "Migration lock held by sibling thread");
        } catch (IOException e) {
            Log.e(TAG, "Failed during migration", e);
        } finally {
            if (lock != null && lock.isValid()) {
                try { lock.release(); } catch (IOException ignored) {}
            }
            // Drop the lockfile on a successful migration so it does not leak
            // into filesDir forever. The sentinel guards against re-running
            // the migration, so the lockfile is no longer needed.
            if (migratedThisCall) {
                // Best effort; ignore failures. Deleting after releasing the
                // lock is safe — any process still holding a stale handle will
                // simply see EOF when it eventually closes.
                lockFile.delete();
            }
        }
    }

    private static void migrateLegacyApiKeyLocked(Context app, File filesDir) {
        File lockFile = new File(filesDir, API_KEY_MIGRATION_SENTINEL + ".lock");
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileLock lock = fos.getChannel().tryLock();
            if (lock == null) return;
            try {
                migrateLegacyApiKey(app, filesDir,
                        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
            } finally {
                try { lock.release(); } catch (IOException ignored) { }
            }
        } catch (OverlappingFileLockException | IOException e) {
            Log.w(TAG, "Legacy API key migration deferred");
        }
    }

    private static void migrateLegacyApiKeyLocked(Context app, File filesDir,
                                                  SharedPreferences legacy) {
        File lockFile = new File(filesDir, API_KEY_MIGRATION_SENTINEL + ".lock");
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileLock lock = fos.getChannel().tryLock();
            if (lock == null) return;
            try {
                migrateLegacyApiKey(app, filesDir, legacy);
            } finally {
                try { lock.release(); } catch (IOException ignored) { }
            }
        } catch (OverlappingFileLockException | IOException e) {
            Log.w(TAG, "Legacy API key migration deferred");
        }
    }

    private static boolean migrateLegacyApiKey(Context app, File filesDir,
                                               SharedPreferences legacy) {
        if (hasUsableApiKeyMarker(app)) return true;
        if (new File(filesDir, API_KEY_MIGRATION_SENTINEL).exists()) return false;

        File legacyEncryptedFile = new File(
                new File(app.getApplicationInfo().dataDir, "shared_prefs"),
                LEGACY_ENCRYPTED_PREFS_NAME + ".xml");
        boolean hasPlainFallback = legacy != null && legacy.contains(LEGACY_KEY_API_KEY);
        if (!legacyEncryptedFile.exists() && !hasPlainFallback) {
            // Fresh installs have no legacy key. Avoid creating a new
            // EncryptedSharedPreferences file or touching Android Keystore.
            try {
                new File(filesDir, API_KEY_MIGRATION_SENTINEL).createNewFile();
            } catch (IOException ignored) {
                // Best effort only; the next startup can repeat this check.
            }
            return false;
        }

        String key = "";
        SharedPreferences encrypted = null;
        boolean encryptedReadCompleted = false;
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            encrypted = EncryptedSharedPreferences.create(
                    app,
                    LEGACY_ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            key = encrypted.getString(LEGACY_KEY_API_KEY, "");
            encryptedReadCompleted = true;
        } catch (Exception e) {
            // Try the historical plaintext fallback below. Never expose the
            // exception or any key material in logs.
            Log.w(TAG, "Legacy encrypted API key unavailable; trying fallback");
        }

        if ((key == null || key.isEmpty()) && legacy != null) {
            key = legacy.getString(LEGACY_KEY_API_KEY, "");
        }

        if (key != null && !key.isEmpty()) {
            String encoded = Base64.encodeToString(
                    key.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            MarkerFileHelper.writeString(app, PP_API_KEY_FILE, encoded);
            // Verify the atomic marker write before removing the only legacy
            // copy. MarkerFileHelper deliberately has a void API and can only
            // report a best-effort write, so read it back and compare exactly.
            String stored = MarkerFileHelper.readString(app, PP_API_KEY_FILE, null);
            if (!encoded.equals(stored)) {
                Log.w(TAG, "Legacy API key migration deferred; marker write could not be verified");
                return false;
            }
            // Best-effort cleanup after the marker has been verified.
            //
            // commit(), not apply() (lint's [ApplySharedPref] suggestion): the
            // caller marks the migration complete as soon as this returns, so an
            // asynchronous removal could be lost to process death and leave the
            // legacy plain/encrypted key on disk for good — a durable removal is
            // the point. It costs nothing on the UI thread either: the whole
            // migration runs on App's "app-bootstrap" worker.
            if (encrypted != null) {
                try {
                    encrypted.edit().remove(LEGACY_KEY_API_KEY).commit();
                } catch (Exception ignored) {
                    // Retaining the old encrypted copy is safer than risking
                    // loss if cleanup fails.
                }
            }
            return true;
        }

        if (encryptedReadCompleted) {
            // No old key existed. Do not reopen Keystore on every process start.
            try {
                new File(filesDir, API_KEY_MIGRATION_SENTINEL).createNewFile();
            } catch (IOException ignored) {
                // The migration remains harmless if this best-effort marker fails.
            }
        }
        // If encryptedReadCompleted is false, leave the marker absent so a
        // later startup can retry after a transient Keystore failure.
        return false;
    }

    private static boolean hasUsableApiKeyMarker(Context app) {
        String encoded = MarkerFileHelper.readString(app, PP_API_KEY_FILE, null);
        if (encoded == null || encoded.isEmpty()) return false;
        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            return decoded.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Writes a marker only when it does not already exist.
     *
     * <p>The legacy→marker migration runs on a background thread (see
     * {@link App#onCreate}), so it can in principle interleave with a user
     * editing settings. It used to be a plain overwrite, and the settings screen
     * therefore had to block on a {@code CountDownLatch} (with a timeout) before
     * letting the user change anything — main-thread waiting that existed purely
     * to paper over this write order.
     *
     * <p>Making the migration non-destructive removes the hazard at its root: a
     * marker that already exists means the value is either the user's or a
     * previous migration's, and in both cases it must not be replaced by a stale
     * legacy preference. With no possible clobber there is nothing to
     * synchronise, so no listener of this class ever waits on the UI thread.
     */
    private static void writeMarkerIfAbsent(File filesDir, String fileName, String value) {
        File f = new File(filesDir, fileName);
        if (f.exists()) return;
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        File temp = new File(filesDir, fileName + ".tmp");
        try (FileOutputStream os = new FileOutputStream(temp)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
            if (!temp.renameTo(f)) {
                Log.e(TAG, "Failed to rename marker file " + fileName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write marker file " + fileName, e);
        } finally {
            temp.delete();
        }
    }


}
