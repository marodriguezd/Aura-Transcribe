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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Minimal settings store for the AI post-processing layer.
 *
 * Non-secret settings (enabled flag, provider, base URL, model, system prompt)
 * are stored as marker files in {@code filesDir()} so they can be read
 * consistently by the Java UI and by the native engine (which reads them
 * straight from the filesystem), without SharedPreferences.
 *
 * The API key is the deliberate exception: it is encrypted at rest with
 * AES-256-GCM under an AndroidKeyStore-held key via
 * {@link SecureCredentialStore} (marker {@code pp_api_key_enc}, versioned
 * {@code v1:} format). Base64 is an encoding, not a protection — the legacy
 * {@code pp_api_key} Base64 marker and the older SharedPreferences/
 * EncryptedSharedPreferences copies are migrated into the encrypted store at
 * startup, verifying the encrypted write before the legacy copy is removed.
 *
 * Only the Java layer reads the key (OkHttp builds the Authorization header);
 * the Rust engine has no reference to it, so no JNI surface carries it.
 *
 * Threat model: encryption at rest protects the stored credential from
 * offline extraction of app data without the Keystore key. It does NOT
 * protect it on a rooted or otherwise compromised device while the app is
 * using it — see {@link SecureCredentialStore}'s class documentation.
 *
 * Credential mutations — the start-up legacy import, a save from the settings
 * screen and an explicit deletion — are serialized on one background lane
 * ({@link CredentialOperations}, {@link #CREDENTIAL_OPS}), in submission order.
 * See {@link #setApiKey} for the contract that callers see.
 *
 * Reads ({@link #readApiKey}, ordered on the same lane) observe the state left by
 * the last <em>completed</em> mutation and are decided by {@link CredentialRead}: a
 * completed save is visible to every later read, and a deletion is visible
 * immediately — including when a copy could not physically be removed, and without
 * reporting it as a read failure. A read is queued behind a pending start-up
 * import, so a migration that has not finished is never mistaken for a missing
 * credential. A caller that must observe its own mutation waits for the completion
 * callback ({@link #setApiKey}) instead of re-reading; every such caller here does
 * (the settings screen's save, model list and connection test all run only after
 * that callback).
 *
 * The synchronous accessors that used to read the credential ({@code getApiKey},
 * {@code isPostProcessEnabled}, {@code isPostProcessConfigured}) are deliberately
 * gone: {@link #isPostProcessSwitchedOn} answers the cheap switch question with no
 * credential I/O at all, and anything that needs the credential itself goes through
 * {@link #readApiKey} / {@link #readPostProcessEnabled} on the lane.
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
    // Same file the credential store reads and removes (single source of truth).
    private static final String PP_API_KEY_FILE = SecureCredentialStore.LEGACY_MARKER_FILE;
    private static final String PP_PRESET_FILE = "pp_preset";
    private static final String MIC_MODE_FILE = "mic_mode";

    // Sentinel that guarantees the legacy -> marker migration runs at most once.
    private static final String MIGRATION_SENTINEL = "pp_migrated";
    // The API-key migration sentinel is owned by CredentialMigration.SENTINEL_NAME.

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

    /**
     * The one lane every credential mutation runs on: the start-up legacy import,
     * a save from the settings screen, and an explicit deletion. Serialization is
     * what makes the outcome deterministic — without it a migration could read a
     * legacy key, the user could delete the key, and the migration could then
     * write the deleted credential back. See {@link CredentialOperations} for why
     * this is a lane rather than a lock, and why nothing here runs on the Android
     * main thread.
     */
    private static final CredentialOperations CREDENTIAL_OPS = new CredentialOperations(
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "credential-ops");
                // Daemon: a queued mutation must never keep the process alive.
                thread.setDaemon(true);
                return thread;
            }),
            message -> Log.e(TAG, message));

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

    /**
     * Cheap, main-thread-safe view of the AI-fix switch: the marker is on and the
     * selected provider can run at all in this build.
     *
     * <p>Deliberately does <b>no</b> credential I/O — no file content read, no
     * tombstone read, no Keystore decrypt — so a UI surface may ask it while
     * laying out or handling a tap. It is not the whole answer: whether a
     * <em>usable</em> credential exists is decided by
     * {@link #readPostProcessEnabled} / {@link #readApiKey} on the credential lane.
     *
     * <p>Gating dictation on this cheap flag is also what keeps a *pending* legacy
     * import from being misread as "not configured": the request that follows does
     * the ordered read, queued behind that import, and finds the credential.
     */
    public boolean isPostProcessSwitchedOn() {
        return MarkerFileHelper.exists(appContext, PP_ENABLED_FILE)
                && isProviderAvailable(getProviderId());
    }

    public boolean isPostProcessMarkerSet() {
        return MarkerFileHelper.exists(appContext, PP_ENABLED_FILE);
    }

    /**
     * Whether post-processing is enabled <em>and usable</em>, from the switch state
     * and a credential outcome. Pure, so the rule is testable without a
     * {@code Context} and shared by {@link #readPostProcessEnabled} and the
     * settings screen.
     */
    public static boolean resolvePostProcessEnabled(boolean switchedOn, String providerId,
                                                   ApiKeyRead credential,
                                                   boolean localModelInstalled) {
        if (!switchedOn) return false;
        if (PROVIDER_LOCAL_S1.equals(providerId)) {
            // The model being on disk is not enough: without an inference engine
            // the provider produces no refinement at all, so treating it as
            // "configured" is what let the nonfunctional path be enabled.
            return LOCAL_S1_INFERENCE_AVAILABLE && localModelInstalled;
        }
        return credential != null && credential.hasCredential();
    }

    /**
     * The switch itself. Writes the marker and never reads the credential: whether
     * the credential is usable belongs to the ordered read
     * ({@link #readPostProcessEnabled}), which every caller performs before turning
     * this on. The one rule kept here needs no credential at all — a provider that
     * cannot run in this build is never persisted as enabled.
     */
    public void setPostProcessEnabled(boolean enabled) {
        if (enabled && !isProviderAvailable(getProviderId())) {
            MarkerFileHelper.setExists(appContext, PP_ENABLED_FILE, false);
            return;
        }
        MarkerFileHelper.setExists(appContext, PP_ENABLED_FILE, enabled);
    }

    // ----------------------------------------------------------------------
    // Ordered credential reads (credential lane)
    // ----------------------------------------------------------------------

    /**
     * Ordered, off-main credential read.
     *
     * <p>Submitted to the credential lane ({@link #CREDENTIAL_OPS}), so it runs
     * after every mutation already queued — the start-up legacy import included —
     * and observes the state they left. Nothing touches the filesystem, the
     * tombstone or AndroidKeyStore on the caller's thread, which is what keeps the
     * Android main thread clear of credential I/O.
     *
     * <p>The callback arrives exactly once on {@code callbackExecutor} (the main
     * executor for UI callers) and is never handed {@code null}: an internal
     * failure is reported as {@link ApiKeyRead.Status#UNREADABLE}, which no caller
     * may turn into "not configured".
     */
    public void readApiKey(Executor callbackExecutor, Consumer<ApiKeyRead> callback) {
        if (callback == null) return;
        CREDENTIAL_OPS.submitValue(this::readApiKeyOnLane, callbackExecutor,
                result -> callback.accept(result != null ? result : ApiKeyRead.unreadable()));
    }

    /**
     * Ordered, off-main "is AI fix on and usable?" check: the switch state combined
     * with a credential read queued behind every pending mutation.
     */
    public void readPostProcessEnabled(Executor callbackExecutor, Consumer<Boolean> callback) {
        if (callback == null) return;
        CREDENTIAL_OPS.submitValue(
                () -> resolvePostProcessEnabled(isPostProcessSwitchedOn(), getProviderId(),
                        readApiKeyOnLane(), isLocalS1ModelInstalled()),
                callbackExecutor,
                enabled -> callback.accept(Boolean.TRUE.equals(enabled)));
    }

    /**
     * The read itself: the Android side of it (files, marker decoding, logging)
     * handed to {@link CredentialRead#gather}, which owns the order and the
     * classification.
     *
     * <p><b>Must run on the credential lane</b>, never on the Android main thread:
     * it reads files, reads the deletion tombstone and may decrypt with
     * AndroidKeyStore. Callers outside this class use {@link #readApiKey}.
     */
    private ApiKeyRead readApiKeyOnLane() {
        File filesDir = appContext.getFilesDir();
        return CredentialRead.gather(
                filesDir,
                secureStore(filesDir),
                () -> readMarker(PP_API_KEY_FILE),
                encoded -> {
                    try {
                        return new String(
                                Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        // Never include the marker contents or decoded key in
                        // diagnostics; the failure itself is reported by gather().
                        Log.e(TAG, "Failed to decode API key from marker", e);
                        return null;
                    }
                },
                message -> Log.e(TAG, message));
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
    // API key (encrypted at rest; see SecureCredentialStore for the format,
    // threat model and legacy-marker migration semantics)
    // ----------------------------------------------------------------------

    /** Receives the durable outcome of {@link #setApiKey} on the caller's executor. */
    public interface ApiKeyCallback {
        /**
         * @param saved true when the requested end state is stored; false when
         *              the credential could not be encrypted/persisted (or the
         *              provider is unavailable), in which case nothing was written
         *              — never a plaintext fallback — and a previously stored
         *              credential, if any, is left untouched
         */
        void onApiKeySaved(boolean saved);
    }

    /**
     * Persists the API key encrypted at rest, or deletes it when {@code key} is
     * null or empty.
     *
     * <p><b>Asynchronous and serialized on purpose.</b> This is one of the three
     * credential mutations (see {@link #CREDENTIAL_OPS}); it is queued behind
     * whatever the start-up migration is doing and behind every other save and
     * deletion, so the last call wins deterministically. The mutation itself —
     * Keystore plus file I/O, plus the cross-process file lock the migration
     * takes — runs on the credential lane's background thread, never on the
     * caller's: the settings screen calls this from the Android main thread and
     * must not block on a migration. {@code callback} is invoked on
     * {@code callbackExecutor} once the operation has completed, with its durable
     * result, so a caller may report success only then.
     *
     * @param key              the plaintext key, or null/empty to delete it
     * @param callbackExecutor where to deliver the outcome (null = none)
     * @param callback         outcome receiver (null = fire and forget)
     */
    public void setApiKey(String key, Executor callbackExecutor, ApiKeyCallback callback) {
        boolean delete = (key == null || key.isEmpty());
        CREDENTIAL_OPS.submit(
                () -> delete ? deleteCredential() : storeCredential(key),
                callbackExecutor,
                callback == null ? null : callback::onApiKeySaved);
    }

    /**
     * Runs on the credential lane; true when the value was durably stored.
     *
     * <p>Delegates to the coordinator, which owns the write order: verified write
     * → clear the deletion tombstone → drop the legacy copies a verified store
     * supersedes. Clearing the tombstone in the same operation is what makes a
     * credential stored after a deletion (whose removal had failed) visible to
     * reads immediately instead of being hidden until the next start.
     */
    private boolean storeCredential(String key) {
        return credentialMigration(appContext, legacyPrefs(appContext)).store(key);
    }

    /**
     * Runs on the credential lane; true only once the credential is gone
     * everywhere, so the UI never reports a deletion that did not happen.
     */
    private boolean deleteCredential() {
        // Explicit deletion: drop the encrypted store and every legacy copy
        // (marker, EncryptedSharedPreferences and plaintext). Because it runs on
        // the same lane as the import, a migration cannot write the credential
        // back afterwards; CredentialMigration.forget() additionally tombstones a
        // copy that could not actually be removed, and reports whether the
        // deletion really completed.
        return credentialMigration(appContext, legacyPrefs(appContext)).forget();
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
        // The whole start-up migration is submitted to the credential lane and
        // the caller's thread does nothing but submit. Application.onCreate
        // runs on the Android main thread, and the migration performs
        // filesystem I/O that must never happen there: a cross-process file
        // lock, the SharedPreferences load, marker writes with an fsync
        // apiece, and a synchronous SharedPreferences commit. Submitting here
        // — before any component callback can run — is also what orders the
        // credential import ahead of every start-up read (the sentinel gate
        // below runs on the lane too, so the main thread performs no filesystem
        // access at all). See CredentialOperations for why a lane.
        Context app = context.getApplicationContext();
        CREDENTIAL_OPS.submit(() -> runStartupMigration(app), null, null);
    }

    /**
     * Runs one start-up migration pass on the credential lane. Never called on
     * the Android main thread. Fire-and-forget: the outcome is logged, and the
     * per-start retry re-attempts the API key import on the next start.
     */
    private static boolean runStartupMigration(Context app) {
        File filesDir = app.getFilesDir();
        File sentinel = new File(filesDir, MIGRATION_SENTINEL);
        if (sentinel.exists()) {
            // The ordinary settings migration may already be complete while a
            // previous process could not open Android Keystore. Retry the API
            // key migration independently, keeping its own process lock and
            // still going through the credential lane so it can never interleave
            // with a save or a deletion.
            return runLegacyApiKeyMigrationRetry(app, filesDir);
        }
        return runFullMigration(app, filesDir);
    }

    /**
     * The full first-launch settings migration, on the credential lane. Moves
     * the non-secret legacy settings into marker files, imports the API key
     * into the encrypted store, clears the legacy SharedPreferences and drops
     * the sentinel — all serialized by the cross-process lock so a trailing
     * process cannot resurrect a marker the user just removed.
     */
    private static boolean runFullMigration(Context app, File filesDir) {
        File sentinel = new File(filesDir, MIGRATION_SENTINEL);
        File lockFile = new File(filesDir, MIGRATION_SENTINEL + ".lock");
        boolean migratedThisCall = false;
        FileLock lock = null;
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileChannel channel = fos.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                // Another process is migrating. Return immediately: the next
                // App.onCreate in this process will catch up via the sentinel
                // check.
                return true;
            }

            // Re-check sentinel under the lock (the previous holder may have
            // completed in the meantime).
            if (sentinel.exists()) return true;

            SharedPreferences legacy = legacyPrefs(app);

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

            // The API key has three historical homes: the Base64 pp_api_key
            // marker (most recent), EncryptedSharedPreferences (v0.1.19–v0.1.21)
            // and plain SharedPreferences (earliest). CredentialMigration reads
            // them in that recency order and moves the credential into the
            // encrypted SecureCredentialStore BEFORE removing any legacy copy —
            // the encrypted write is verified round-trip first. If encryption is
            // unavailable (device restore, key invalidation, broken Keystore) the
            // legacy copy is preserved for a later retry, and the runtime
            // fast-fail path asks the user to re-enter the key rather than
            // sending an unauthenticated request or writing plaintext.
            //
            // The coordinator removes the legacy API-key copy itself (with a
            // checked commit()), which is why it is not part of the editor below:
            // that removal must only happen after its own verified write.
            //
            // The import runs here, inside this lane task, rather than as a
            // separate submission: this task is already on the credential lane,
            // so the import stays ordered with respect to the settings screen's
            // saves and deletions (an in-flight import can never write a
            // credential the user has just deleted — see CredentialOperations).
            credentialMigration(app, legacy).migrate();

            // Synchronously commit the removal of the non-secret legacy settings
            // (see class comment).
            try {
                SharedPreferences.Editor editor = legacy.edit();
                editor.remove(LEGACY_KEY_POST_PROCESS_ENABLED);
                editor.remove(LEGACY_KEY_PROVIDER);
                editor.remove(LEGACY_KEY_API_URL);
                editor.remove(LEGACY_KEY_MODEL_NAME);
                editor.remove(LEGACY_KEY_SYSTEM_PROMPT);
                if (!editor.commit()) {
                    // Report it instead of claiming a clean migration: the
                    // sentinel at the end of this block stops it from running
                    // again, so a lost commit leaves these legacy values on disk.
                    // They are inert (the markers above already hold the values).
                    Log.e(TAG, "Legacy settings could not be cleared from SharedPreferences");
                }
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
        return true;
    }

    /**
     * The per-start API-key migration retry, on the credential lane. Keeps its
     * own cross-process file lock so it can never interleave with a save or a
     * deletion. Runs on every start, including when the main settings migration
     * already completed: the import is idempotent, and a Keystore failure or a
     * legacy cleanup that did not persist must be retried instead of being
     * written off.
     */
    private static boolean runLegacyApiKeyMigrationRetry(Context app, File filesDir) {
        File lockFile = new File(filesDir, CredentialMigration.SENTINEL_NAME + ".lock");
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileLock lock = fos.getChannel().tryLock();
            if (lock == null) return true; // another process is migrating
            try {
                credentialMigration(app, legacyPrefs(app)).migrate();
            } finally {
                try { lock.release(); } catch (IOException ignored) { }
            }
        } catch (OverlappingFileLockException | IOException e) {
            Log.w(TAG, "Legacy API key migration deferred");
        }
        return true;
    }

    private static SharedPreferences legacyPrefs(Context app) {
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Builds the API-key migration coordinator for this app's {@code filesDir}.
     *
     * <p>The rules (precedence, write-before-delete, sentinel semantics, retry)
     * live in {@link CredentialMigration} so they are covered by JVM tests; this
     * method only supplies the Android implementation of its seams and forwards
     * its diagnostics to logcat. Nothing here logs key material.
     */
    private static CredentialMigration credentialMigration(Context app, SharedPreferences legacy) {
        return new CredentialMigration(
                secureStore(app.getFilesDir()),
                new AndroidLegacySources(app, legacy),
                app.getFilesDir(),
                DIAGNOSTICS);
    }

    private static final CredentialMigration.Diagnostics DIAGNOSTICS =
            new CredentialMigration.Diagnostics() {
                @Override
                public void warn(String message) {
                    Log.w(TAG, message);
                }

                @Override
                public void error(String message) {
                    Log.e(TAG, message);
                }
            };

    /** Store for [filesDir]; the Keystore key provider is shared process-wide. */
    private static SecureCredentialStore secureStore(File filesDir) {
        return new SecureCredentialStore(filesDir, new AndroidCredentialKeyProvider());
    }

    /**
     * The Android view of the legacy API-key sources: the Base64 marker file (the
     * same file {@link SecureCredentialStore} reads and removes), the prefs file
     * the older builds encrypted with {@code EncryptedSharedPreferences}, and the
     * earliest plaintext prefs.
     *
     * <p>Every removal is verified — a {@code commit()} result plus a re-read — so
     * a cleanup that did not actually persist is reported as retained and retried
     * on the next start rather than assumed to have succeeded.
     */
    private static final class AndroidLegacySources implements CredentialMigration.LegacySources {
        private final Context app;
        private final SharedPreferences legacy;
        private final File legacyEncryptedFile;

        AndroidLegacySources(Context app, SharedPreferences legacy) {
            this.app = app;
            this.legacy = legacy;
            this.legacyEncryptedFile = new File(
                    new File(app.getApplicationInfo().dataDir, "shared_prefs"),
                    LEGACY_ENCRYPTED_PREFS_NAME + ".xml");
        }

        @Override
        public boolean hasMarker() {
            // Non-empty, matching what SecureCredentialStore treats as a marker.
            return MarkerFileHelper.readStringFromFile(
                    app.getFilesDir(), PP_API_KEY_FILE, null) != null;
        }

        @Override
        public boolean deleteMarker() {
            MarkerFileHelper.delete(app, PP_API_KEY_FILE);
            return !new File(app.getFilesDir(), PP_API_KEY_FILE).exists();
        }

        @Override
        public boolean hasLegacyEncrypted() {
            return legacyEncryptedFile.exists();
        }

        @Override
        public CredentialMigration.LegacyRead readLegacyEncrypted() {
            // Opening EncryptedSharedPreferences CREATES its backing file, so
            // never do it when there is nothing to read: a fresh install must not
            // grow a new legacy file (nor touch the Android Keystore).
            if (!legacyEncryptedFile.exists()) {
                return new CredentialMigration.LegacyRead(true, null);
            }
            SharedPreferences encrypted = openLegacyEncrypted();
            if (encrypted == null) {
                return CredentialMigration.LegacyRead.unavailable();
            }
            try {
                String key = encrypted.getString(LEGACY_KEY_API_KEY, "");
                return new CredentialMigration.LegacyRead(
                        true, (key == null || key.isEmpty()) ? null : key);
            } catch (RuntimeException e) {
                return CredentialMigration.LegacyRead.unavailable();
            }
        }

        @Override
        public boolean deleteLegacyEncrypted() {
            if (!legacyEncryptedFile.exists()) return true;
            SharedPreferences encrypted = openLegacyEncrypted();
            if (encrypted == null) return false;
            try {
                // commit(), not apply() (lint's [ApplySharedPref] suggestion): the
                // caller reports the migration complete as soon as this returns,
                // so an asynchronous removal could be lost to process death and
                // leave the legacy key on disk for good — a durable removal is the
                // point. The result is used, and the removal re-read: a commit
                // that failed can leave the in-memory map already updated, so
                // checking only the map would report success while the key is
                // still on disk.
                boolean committed = encrypted.edit().remove(LEGACY_KEY_API_KEY).commit();
                return committed && encrypted.getString(LEGACY_KEY_API_KEY, null) == null;
            } catch (RuntimeException e) {
                // Retaining the old encrypted copy is safer than risking loss.
                return false;
            }
        }

        @Override
        public boolean hasLegacyPlain() {
            return legacy != null && legacy.contains(LEGACY_KEY_API_KEY);
        }

        @Override
        public String legacyPlain() {
            return legacy == null ? null : legacy.getString(LEGACY_KEY_API_KEY, null);
        }

        @Override
        public boolean deleteLegacyPlain() {
            if (legacy == null || !legacy.contains(LEGACY_KEY_API_KEY)) return true;
            try {
                // commit(): the plaintext copy must be durably gone before the
                // migration stops retrying its removal.
                boolean committed = legacy.edit().remove(LEGACY_KEY_API_KEY).commit();
                return committed && !legacy.contains(LEGACY_KEY_API_KEY);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Opens the legacy EncryptedSharedPreferences, or null when unavailable. */
        private SharedPreferences openLegacyEncrypted() {
            try {
                MasterKey masterKey = new MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                return EncryptedSharedPreferences.create(
                        app,
                        LEGACY_ENCRYPTED_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Exception e) {
                // Never log this exception or any key material: the legacy store
                // simply could not be read, which the caller reports generically
                // and retries on a later start.
                return null;
            }
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
