package dev.notune.transcribe;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * Settings screen for the AI post-processing layer (fork addition).
 * Lets the user toggle post-processing, pick a provider preset (Groq,
 * OpenAI, Cerebras, ... or Custom), and configure API key, model name and
 * the active system prompt. The base-URL field is only shown for Custom;
 * presets carry their endpoint template.
 *
 * The model field is an editable dropdown: the refresh button next to it
 * calls the provider's /models endpoint with the current key and fills the
 * dropdown with the available model ids.
 */
public class PostProcessSettingsActivity extends AppCompatActivity {

    private SettingsManager settings;
    private MaterialSwitch switchEnabled;
    private AutoCompleteTextView dropdownProvider;
    private AutoCompleteTextView dropdownPreset;
    private TextInputLayout layoutApiUrl;
    private TextInputLayout layoutApiKey;
    private TextInputLayout layoutModel;
    private EditText editApiUrl;
    private EditText editApiKey;
    private AutoCompleteTextView editModel;
    private EditText editPrompt;
    private Button btnRefreshModels;
    private View cardLocalModel;
    private TextView txtS1Status;
    private Button btnDownloadS1;
    private ProgressBar progressS1;
    private Button btnSave;
    private Button btnTestConnection;

    /**
     * Main-thread delivery for the credential-write outcome and for the
     * provider network callbacks (both must touch views).
     */
    private Handler mainHandler;

    /** Provider id currently selected in the dropdown. */
    private String selectedProviderId;
    private String selectedPreset;

    /**
     * Suppresses the enable-check listener while the screen sets the switch
     * programmatically (initial load, switching to a provider that cannot be
     * enabled). Without it the guard below would fire a toast for a change the
     * user did not make.
     */
    private boolean suppressEnableChecks;

    private static final String[] PRESET_IDS = new String[] {
            SettingsManager.PRESET_CLEAN,
            SettingsManager.PRESET_FORMAL,
            SettingsManager.PRESET_CASUAL,
            SettingsManager.PRESET_VERBATIM
    };

    private static final int[] PRESET_STRING_RES = new int[] {
            R.string.pp_preset_clean,
            R.string.pp_preset_formal,
            R.string.pp_preset_casual,
            R.string.pp_preset_verbatim
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel this screen's in-flight model-list fetch when it is destroyed
        // (owner-scoped, P0.1), so its UI callbacks cannot touch a torn-down
        // window (e.g. calling editModel.showDropDown() after the activity is
        // gone would throw WindowManager.BadTokenException). A dictation from
        // another surface is left untouched.
        PostProcessor.cancelAllFor(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_settings);

        View rootView = findViewById(R.id.post_process_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        // No waiting on the legacy→marker migration is needed: the migration
        // can no longer overwrite an existing marker (see
        // SettingsManager.writeMarkerIfAbsent), so the race this screen used to
        // guard against by blocking the UI thread is now impossible by
        // construction. This used to be App.awaitPostProcessMigration(), a
        // bounded main-thread wait.

        settings = new SettingsManager(this);
        mainHandler = new Handler(Looper.getMainLooper());

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        switchEnabled = findViewById(R.id.switch_pp_enabled);
        dropdownProvider = findViewById(R.id.dropdown_provider);
        dropdownPreset = findViewById(R.id.dropdown_preset);
        layoutApiUrl = findViewById(R.id.layout_api_url);
        layoutApiKey = findViewById(R.id.layout_api_key);
        layoutModel = findViewById(R.id.layout_model);
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModel = findViewById(R.id.edit_model);
        editPrompt = findViewById(R.id.edit_prompt);
        btnRefreshModels = findViewById(R.id.btn_refresh_models);
        cardLocalModel = findViewById(R.id.card_local_model);
        txtS1Status = findViewById(R.id.txt_s1_status);
        btnDownloadS1 = findViewById(R.id.btn_download_s1);
        progressS1 = findViewById(R.id.progress_s1);

        // Provider dropdown
        String[] labels = new String[SettingsManager.PROVIDERS.length];
        for (int i = 0; i < SettingsManager.PROVIDERS.length; i++) {
            labels[i] = SettingsManager.PROVIDERS[i].label;
        }
        dropdownProvider.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels));

        selectedProviderId = settings.getProviderId();
        SettingsManager.Provider current = SettingsManager.providerById(selectedProviderId);
        dropdownProvider.setText(current.label, false);

        dropdownProvider.setOnItemClickListener((parent, view, position, id) -> {
            SettingsManager.Provider p = SettingsManager.PROVIDERS[position];
            selectedProviderId = p.id;
            updateProviderUi(p, true);
        });

        // Preset dropdown
        String[] presetLabels = new String[PRESET_STRING_RES.length];
        for (int i = 0; i < PRESET_STRING_RES.length; i++) {
            presetLabels[i] = getString(PRESET_STRING_RES[i]);
        }
        dropdownPreset.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, presetLabels));

        selectedPreset = settings.getPostProcessPreset();
        int initialPresetIndex = 0;
        for (int i = 0; i < PRESET_IDS.length; i++) {
            if (PRESET_IDS[i].equals(selectedPreset)) {
                initialPresetIndex = i;
                break;
            }
        }
        dropdownPreset.setText(presetLabels[initialPresetIndex], false);
        dropdownPreset.setOnItemClickListener((parent, view, position, id) -> {
            selectedPreset = PRESET_IDS[position];
        });

        // Local S1 model status & download
        updateLocalModelCard();
        btnDownloadS1.setOnClickListener(v -> downloadS1Model());

        // The switch starts off and disabled: the real state (marker AND a usable
        // credential, read on the credential lane) is applied by
        // loadApiKeyAndState() once that ordered read answers, so this screen never
        // derives it from credential I/O on the UI thread.
        switchEnabled.setEnabled(false);
        switchEnabled.setChecked(false);
        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressEnableChecks) return;
            if (isChecked) {
                boolean isLocalMode = SettingsManager.PROVIDER_LOCAL_S1.equals(selectedProviderId);
                String currentKey = editApiKey.getText().toString().trim();
                if (!SettingsManager.isProviderAvailable(selectedProviderId)) {
                    // The provider is listed (so the limitation is visible) but
                    // cannot refine text in this build — there is no inference
                    // engine behind it. Letting the switch stay on would promise
                    // AI cleanup that returns the transcript unchanged.
                    buttonView.setChecked(false);
                    Toast.makeText(this, R.string.pp_local_unavailable, Toast.LENGTH_LONG).show();
                } else if (isLocalMode && !settings.isLocalS1ModelInstalled()) {
                    buttonView.setChecked(false);
                    Toast.makeText(this, R.string.pp_local_model_not_installed, Toast.LENGTH_LONG).show();
                } else if (!isLocalMode && currentKey.isEmpty()) {
                    layoutApiKey.setError(getString(R.string.pp_api_key_required));
                }
            } else {
                layoutApiKey.setError(null);
            }
        });
        editApiUrl.setText(settings.getApiUrl());
        editModel.setText(settings.getModelName());
        editPrompt.setText(settings.getActivePromptBody());
        // The saved key is loaded asynchronously by loadApiKeyAndState() below:
        // reading it here would decrypt with AndroidKeyStore on the UI thread.

        updateProviderUi(current, false);

        btnRefreshModels.setOnClickListener(v -> fetchModels());

        btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveAndClose());

        btnTestConnection = findViewById(R.id.btn_test_connection);
        btnTestConnection.setOnClickListener(v -> testConnection(btnTestConnection));

        // Everything that depends on the credential waits for the ordered read.
        loadApiKeyAndState();
    }

    /**
     * Loads the saved credential and the AI-fix state through ordered reads on the
     * credential lane, then applies them on the main thread.
     *
     * <p>Why not read here: the key is encrypted with AndroidKeyStore, so the read
     * is file I/O plus a decrypt, and the lane is what orders it behind the
     * start-up legacy import (a key that lives only in the legacy prefs is
     * therefore found rather than reported missing) and behind any save or deletion
     * already queued.
     *
     * <p>Save, test and fetch stay disabled until the read has answered — they all
     * depend on it — and an unreadable store is reported distinctly from "nothing
     * configured": the field is left empty for re-entry rather than silently
     * pretending there is no credential.
     */
    private void loadApiKeyAndState() {
        setActionsEnabled(false);
        settings.readApiKey(mainHandler::post, read -> {
            if (isFinishing() || isDestroyed()) return;
            if (read.status() == ApiKeyRead.Status.LOADED) {
                editApiKey.setText(read.key());
            } else {
                editApiKey.setText("");
                if (read.status() == ApiKeyRead.Status.UNREADABLE) {
                    // Something is stored but could not be read (corrupt/tampered
                    // ciphertext, Keystore unavailable): ask for a re-entry instead
                    // of pretending the credential is absent.
                    layoutApiKey.setError(getString(R.string.pp_api_key_required));
                }
            }
            settings.readPostProcessEnabled(mainHandler::post, enabled -> {
                if (isFinishing() || isDestroyed()) return;
                suppressEnableChecks = true;
                try {
                    switchEnabled.setChecked(enabled);
                } finally {
                    suppressEnableChecks = false;
                }
                switchEnabled.setEnabled(true);
                applyApiKeyErrorState();
                setActionsEnabled(true);
            });
        });
    }

    /** Flags the key field when the switch is on but nothing usable is stored. */
    private void applyApiKeyErrorState() {
        boolean needsKey = switchEnabled.isChecked()
                && !SettingsManager.PROVIDER_LOCAL_S1.equals(selectedProviderId)
                && editApiKey.getText().toString().trim().isEmpty();
        layoutApiKey.setError(needsKey ? getString(R.string.pp_api_key_required) : null);
    }

    /**
     * Save, test and fetch all read or depend on the credential, so they stay
     * disabled until the ordered read that loads it has answered.
     */
    private void setActionsEnabled(boolean enabled) {
        if (btnSave != null) btnSave.setEnabled(enabled);
        if (btnTestConnection != null) btnTestConnection.setEnabled(enabled);
        if (btnRefreshModels != null) btnRefreshModels.setEnabled(enabled);
    }

    private void updateLocalModelCard() {
        if (!SettingsManager.isProviderAvailable(SettingsManager.PROVIDER_LOCAL_S1)) {
            // Say why instead of offering a 380 MB download for a model nothing
            // in the app can run (see SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE).
            txtS1Status.setText(getString(R.string.pp_local_unavailable));
            btnDownloadS1.setVisibility(android.view.View.GONE);
            return;
        }
        if (settings.isLocalS1ModelInstalled()) {
            txtS1Status.setText(getString(R.string.pp_local_model_installed, "380 MB"));
            btnDownloadS1.setVisibility(android.view.View.GONE);
        } else {
            txtS1Status.setText(getString(R.string.pp_local_model_not_installed));
            btnDownloadS1.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void downloadS1Model() {
        // Defensive: the button is hidden for an unavailable provider, but the
        // path must not be reachable by any other means either.
        if (!SettingsManager.isProviderAvailable(SettingsManager.PROVIDER_LOCAL_S1)) {
            Toast.makeText(this, R.string.pp_local_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        btnDownloadS1.setEnabled(false);
        progressS1.setVisibility(android.view.View.VISIBLE);
        progressS1.setIndeterminate(true);
        txtS1Status.setText(R.string.pp_downloading_s1);

        new Thread(() -> {
            try {
                java.io.File modelsDir = new java.io.File(getFilesDir(), "models");
                if (!modelsDir.exists()) modelsDir.mkdirs();
                java.io.File targetFile = new java.io.File(modelsDir, "s1-mini-q4_k_m.gguf");
                java.io.File tempFile = new java.io.File(modelsDir, "s1-mini-q4_k_m.gguf.tmp");

                String downloadUrl = "https://huggingface.co/superwhisper/s1-mini-GGUF/resolve/main/s1-mini-q4_k_m.gguf";
                okhttp3.Request request = new okhttp3.Request.Builder().url(downloadUrl).build();
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                        .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new java.io.IOException("HTTP " + response.code());
                    }
                    if (response.body() == null) {
                        throw new java.io.IOException("Empty response");
                    }
                    try (java.io.InputStream in = response.body().byteStream();
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    if (tempFile.renameTo(targetFile)) {
                        runOnUiThread(() -> {
                            progressS1.setVisibility(android.view.View.GONE);
                            btnDownloadS1.setEnabled(true);
                            btnDownloadS1.setVisibility(android.view.View.GONE);
                            txtS1Status.setText(getString(R.string.pp_local_model_installed, "380 MB"));
                            Toast.makeText(this, R.string.pp_download_s1_done, Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        throw new java.io.IOException("Failed to rename temporary file");
                    }
                }
            } catch (Exception e) {
                Log.e("PostProcessSettings", "Failed to download S1 model", e);
                try {
                    java.io.File tempFile = new java.io.File(new java.io.File(getFilesDir(), "models"), "s1-mini-q4_k_m.gguf.tmp");
                    if (tempFile.exists()) tempFile.delete();
                } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    progressS1.setVisibility(android.view.View.GONE);
                    btnDownloadS1.setEnabled(true);
                    txtS1Status.setText(getString(R.string.pp_download_s1_error, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Shows/hides the base-URL field (Custom only) and, on user-initiated
     * provider switches, pre-fills the model field with the provider's
     * default model when the field is empty or still holds another
     * provider's default (avoids clobbering a hand-edited model).
     */
    private void updateProviderUi(SettingsManager.Provider p, boolean userInitiated) {
        boolean isLocal = SettingsManager.PROVIDER_LOCAL_S1.equals(p.id);
        boolean isCustom = p.baseUrl == null && !isLocal;

        cardLocalModel.setVisibility(isLocal ? android.view.View.VISIBLE : android.view.View.GONE);
        layoutApiUrl.setVisibility(isCustom ? android.view.View.VISIBLE : android.view.View.GONE);
        layoutApiKey.setVisibility(isLocal ? android.view.View.GONE : android.view.View.VISIBLE);
        layoutModel.setVisibility(isLocal ? android.view.View.GONE : android.view.View.VISIBLE);
        btnRefreshModels.setVisibility(isLocal ? android.view.View.GONE : android.view.View.VISIBLE);

        if (isLocal) {
            updateLocalModelCard();
            layoutApiKey.setError(null);
        }

        // A provider that cannot work must not be switchable on, and a switch
        // already on must not stay on when the user selects one. The switch is
        // re-enabled as soon as a functional provider is selected.
        boolean providerAvailable = SettingsManager.isProviderAvailable(p.id);
        suppressEnableChecks = true;
        try {
            switchEnabled.setEnabled(providerAvailable);
            if (!providerAvailable && switchEnabled.isChecked()) {
                switchEnabled.setChecked(false);
            }
        } finally {
            suppressEnableChecks = false;
        }
        if (!providerAvailable) {
            Toast.makeText(this, R.string.pp_local_unavailable, Toast.LENGTH_LONG).show();
        }
        layoutApiUrl.setVisibility(isCustom ? android.view.View.VISIBLE : android.view.View.GONE);

        // Keep the model field consistent with the selected provider.
        //  - userInitiated (provider switch): always adopt the new provider's
        //    default model. A stale model from the previous provider (e.g.
        //    "gpt-4o-mini" after switching OpenAI -> Groq, or any fetched
        //    model like "openai/gpt-oss-120b") is rejected by the new
        //    provider's API, silently defeating post-processing.
        //  - Initial load: only replace a clearly foreign default (another
        //    provider's known default model) or an empty field, so a model
        //    the user hand-typed/customised is preserved.
        if (p.defaultModel != null && !p.defaultModel.isEmpty()) {
            String currentModel = editModel.getText().toString().trim();
            boolean belongsToAnotherProvider = false;
            if (!currentModel.isEmpty()) {
                for (SettingsManager.Provider q : SettingsManager.PROVIDERS) {
                    if (!q.id.equals(p.id) && currentModel.equals(q.defaultModel)) {
                        belongsToAnotherProvider = true;
                        break;
                    }
                }
            }
            if (userInitiated) {
                editModel.setText(p.defaultModel);
            } else if (currentModel.isEmpty() || belongsToAnotherProvider) {
                editModel.setText(p.defaultModel);
            }
        }
    }

    /**
     * Fetches the /models list from the currently selected provider using
     * the values in the form (persisting them first, since PostProcessor
     * reads from SettingsManager), and fills the model dropdown.
     *
     * <p>The request only starts once the credential write has completed — not
     * merely been submitted — because the request reads the key back from
     * {@link SettingsManager}. As before, it proceeds even if that write failed:
     * the provider call then reports the missing key, which is the accurate
     * reason.
     */
    private void fetchModels() {
        persistSettings(saved -> requestModels());
    }

    private void requestModels() {
        btnRefreshModels.setEnabled(false);
        new PostProcessor(settings, mainHandler,
                () -> !isFinishing() && !isDestroyed(), this)
                .fetchModels(new PostProcessor.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                btnRefreshModels.setEnabled(true);
                editModel.setAdapter(new ArrayAdapter<>(
                        PostProcessSettingsActivity.this,
                        android.R.layout.simple_list_item_1, models));
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_models_loaded) + " (" + models.size() + ")",
                        Toast.LENGTH_SHORT).show();
                editModel.showDropDown();
            }

            @Override
            public void onError(String error) {
                btnRefreshModels.setEnabled(true);
                String detail = PostProcessor.MISSING_API_KEY_ERROR.equals(error)
                        ? getString(R.string.pp_api_key_required) : error;
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_models_error) + ": " + detail,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Persists the form, then probes the provider with the stored values. */
    private void testConnection(Button button) {
        button.setEnabled(false);
        persistSettings(saved -> requestConnection(button));
    }

    private void requestConnection(Button button) {
        new PostProcessor(settings, mainHandler,
                () -> !isFinishing() && !isDestroyed(), this)
                .testConnection(new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                button.setEnabled(true);
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_test_success), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String error) {
                button.setEnabled(true);
                String detail = PostProcessor.MISSING_API_KEY_ERROR.equals(error)
                        ? getString(R.string.pp_api_key_required) : error;
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_test_error, detail), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Save button: persists the form and closes the screen, but only once the
     * credential write has actually succeeded. A failed write is not reported as
     * a successful save, and the screen stays open so the user can retry (the
     * failure itself is already reported by {@link #persistSettings}).
     */
    private void saveAndClose() {
        persistSettings(saved -> {
            if (!saved) return;
            Toast.makeText(this, R.string.pp_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /** Continuation of {@link #persistSettings}; receives the credential outcome. */
    private interface PersistCallback {
        void onPersisted(boolean apiKeySaved);
    }

    /**
     * Writes the form.
     *
     * <p>The non-secret settings are marker files and are written synchronously.
     * The API key is deliberately not: it is encrypted with an Android Keystore
     * key, and every credential mutation — including the start-up legacy import —
     * is serialized on {@link SettingsManager}'s credential lane, which must not
     * be entered from the main thread while a migration holds it. The write is
     * therefore submitted asynchronously; the save button is disabled until the
     * outcome arrives, and {@code afterPersist} runs only once the operation has
     * completed, with its durable result.
     */
    private void persistSettings(PersistCallback afterPersist) {
        // The cheap marker state (no credential I/O): it is what every surface
        // consults, and the only thing this needs to know is whether AI fix was on
        // before this save, so turning it off can cancel in-flight calls.
        boolean wasEnabled = settings.isPostProcessMarkerSet();
        // Never persist "AI fix is on" for a provider that cannot run. This is
        // the last line of defence: the switch is disabled in the UI, but the
        // marker file is what every surface actually consults, so the decision
        // lives in SettingsManager rather than in this screen's view state.
        boolean nowEnabled = SettingsManager.resolveEnabledOnSave(
                selectedProviderId, switchEnabled.isChecked());
        boolean isLocal = SettingsManager.PROVIDER_LOCAL_S1.equals(selectedProviderId);
        String apiKey = editApiKey.getText().toString().trim();

        if (!SettingsManager.isProviderAvailable(selectedProviderId)) {
            // The value above was already forced to false; what is left is to make
            // the switch agree with what will actually be stored.
            suppressEnableChecks = true;
            try {
                switchEnabled.setChecked(false);
            } finally {
                suppressEnableChecks = false;
            }
            Toast.makeText(this, R.string.pp_local_unavailable, Toast.LENGTH_LONG).show();
        }

        if (nowEnabled) {
            if (isLocal && !settings.isLocalS1ModelInstalled()) {
                nowEnabled = false;
                switchEnabled.setChecked(false);
                Toast.makeText(this, R.string.pp_local_model_not_installed, Toast.LENGTH_LONG).show();
            } else if (!isLocal && apiKey.isEmpty()) {
                nowEnabled = false;
                switchEnabled.setChecked(false);
                layoutApiKey.setError(getString(R.string.pp_api_key_required));
                Toast.makeText(this, R.string.pp_api_key_required, Toast.LENGTH_SHORT).show();
            }
        }
        // The outcome callback runs later, so the validated decision is captured
        // now; only the credential result can still change it.
        final boolean requestedEnabled = nowEnabled;

        settings.setProviderId(selectedProviderId);
        if (selectedPreset != null) {
            settings.setPostProcessPreset(selectedPreset);
        }
        settings.setApiUrl(editApiUrl.getText().toString().trim());
        settings.setModelName(editModel.getText().toString().trim());

        // Only persist the prompt if the user actually changed it; otherwise
        // leave the marker empty so future updates to the default prompt keep
        // applying automatically.
        String prompt = editPrompt.getText().toString().trim();
        String defaultPrompt = settings.getDefaultPrompt();
        if (prompt.isEmpty() || prompt.equals(defaultPrompt)) {
            settings.setActivePromptBody("");
        } else {
            settings.setActivePromptBody(prompt);
        }

        setSaving(true);
        settings.setApiKey(apiKey, mainHandler::post, saved -> {
            setSaving(false);
            if (isFinishing() || isDestroyed()) return;

            // A failed write stored nothing (there is no plaintext fallback), so
            // the feature must not claim to be enabled with a key that will not
            // exist at request time. A previously stored credential, if any, is
            // left untouched.
            boolean effectiveEnabled = requestedEnabled && saved;
            settings.setPostProcessEnabled(effectiveEnabled);

            layoutApiKey.setError(apiKey.isEmpty() && effectiveEnabled && !isLocal
                    ? getString(R.string.pp_api_key_required) : null);
            if (!saved) {
                switchEnabled.setChecked(false);
                layoutApiKey.setError(getString(R.string.pp_api_key_save_failed));
                Toast.makeText(this, R.string.pp_api_key_save_failed, Toast.LENGTH_LONG).show();
            }

            // If post-processing is no longer on, cancel any in-flight LLM calls
            // in this process and broadcast to the IME component so it cancels its
            // own calls immediately instead of waiting for them to time out.
            if (wasEnabled && !effectiveEnabled) {
                PostProcessor.cancelAll();
                Intent cancelIntent = new Intent(PostProcessor.CANCEL_ACTION);
                cancelIntent.setPackage(getPackageName());
                sendBroadcast(cancelIntent);
            }

            if (afterPersist != null) afterPersist.onPersisted(saved);
        });
    }

    /**
     * Disables the save button while a credential write is queued or running, so
     * a second tap cannot submit a second mutation on top of the first.
     */
    private void setSaving(boolean saving) {
        if (btnSave != null) btnSave.setEnabled(!saving);
    }
}
