package dev.notune.transcribe;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Lets the user pick the speech model: the built-in Parakeet model, or any
 * transcribe.cpp GGUF model imported from a locally downloaded file. Import
 * goes through the system file picker (SAF), so the app itself needs no
 * internet or storage permission — download links are only *shown* here and
 * open in the browser.
 *
 * The selection is stored as marker files in filesDir (same pattern as the
 * other settings), read by the Rust engine loader:
 *   - {@code active_model}: file name of the GGUF under {@code files/models/},
 *     absent/empty = built-in model.
 *   - {@code model_language}: optional language hint for GGUF models.
 */
public class ModelsActivity extends AppCompatActivity {
    private static final String TAG = "ModelsActivity";
    /** Keep this much free space beyond the model file itself. */
    private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;

    private final ActivityResultLauncher<String[]> pickModelLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                handleModelImportUri(uri);
            });

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (Throwable t) {
            try {
                Log.w(TAG, "Failed to load native libraries", t);
            } catch (Throwable ignored) {}
        }
    }

    /** One entry in the curated download list. Names are proper nouns and stay
     *  untranslated; descriptions are string resources so they localize. */
    private static final class ModelLink {
        final String name;
        final int descRes;
        final String size;
        final String url;

        ModelLink(String name, int descRes, String size, String url) {
            this.name = name;
            this.descRes = descRes;
            this.size = size;
            this.url = url;
        }
    }

    private static final ModelLink[] MODEL_LINKS = {
            new ModelLink("Parakeet TDT 110M", R.string.model_desc_parakeet110m, "135 MB",
                    "https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf/resolve/main/parakeet-tdt_ctc-110m-Q8_0.gguf"),
            new ModelLink("Canary 180M Flash", R.string.model_desc_canary_180m, "210 MB",
                    "https://huggingface.co/epapanita/canary-180m-flash-gguf/resolve/main/canary-180m-flash-q8_0.gguf"),
            new ModelLink("SenseVoice Small", R.string.model_desc_sensevoice, "241 MB",
                    "https://huggingface.co/handy-computer/SenseVoiceSmall-gguf/resolve/main/SenseVoiceSmall-Q8_0.gguf"),
            new ModelLink("Whisper Small", R.string.model_desc_whisper_small, "257 MB",
                    "https://huggingface.co/handy-computer/whisper-small-gguf/resolve/main/whisper-small-Q8_0.gguf"),
            new ModelLink("Nemotron 3.5 Streaming 0.6B", R.string.model_desc_nemotron, "751 MB",
                    "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main/nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf"),
            new ModelLink("Parakeet TDT 0.6B v3 Q8", R.string.model_desc_parakeet_v3_q8, "740 MB",
                    "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/main/parakeet-tdt-0.6b-v3-Q8_0.gguf"),
            new ModelLink("Whisper Large-v3-Turbo", R.string.model_desc_whisper_turbo, "845 MB",
                    "https://huggingface.co/handy-computer/whisper-large-v3-turbo-gguf/resolve/main/whisper-large-v3-turbo-Q8_0.gguf"),
            // Marked, not removed: the GGUF is real, the download/import plumbing
            // works and the JNI surface is in place, but nothing in this build can
            // run a text-generation model, so this entry must not read like a
            // working feature (SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE).
            new ModelLink("SuperWhisper S1-mini 0.6B (text normalizer - not usable yet)",
                    R.string.pp_local_model_title, "380 MB",
                    "https://huggingface.co/superwhisper/s1-mini-GGUF/resolve/main/s1-mini-q4_k_m.gguf"),
            new ModelLink("huggingface.co/handy-computer", R.string.model_desc_browse, "",
                    "https://huggingface.co/handy-computer"),
    };

    private static final class RecommendedPack {
        final int titleRes;
        final int descRes;
        final String actionUrl;
        final boolean isPostProcessSettings;

        RecommendedPack(int titleRes, int descRes, String actionUrl, boolean isPostProcessSettings) {
            this.titleRes = titleRes;
            this.descRes = descRes;
            this.actionUrl = actionUrl;
            this.isPostProcessSettings = isPostProcessSettings;
        }
    }

    private static final RecommendedPack[] RECOMMENDED_PACKS = {
            new RecommendedPack(
                    R.string.pack_pro_title,
                    R.string.pack_pro_desc,
                    null,
                    true
            ),
            new RecommendedPack(
                    R.string.pack_ultralight_title,
                    R.string.pack_ultralight_desc,
                    "https://huggingface.co/epapanita/canary-180m-flash-gguf/resolve/main/canary-180m-flash-q8_0.gguf",
                    false
            ),
            new RecommendedPack(
                    R.string.pack_multilingual_title,
                    R.string.pack_multilingual_desc,
                    "https://huggingface.co/handy-computer/whisper-large-v3-turbo-gguf/resolve/main/whisper-large-v3-turbo-Q8_0.gguf",
                    false
            )
    };

    private LinearLayout modelList;
    private TextView statusText;
    private View importArea;
    private ProgressBar importBar;
    private TextView importText;
    private Button importButton;
    private Spinner languageSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);

        View rootView = findViewById(R.id.models_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        modelList = findViewById(R.id.model_list);
        statusText = findViewById(R.id.txt_model_status);
        importArea = findViewById(R.id.import_area);
        importBar = findViewById(R.id.import_progress);
        importText = findViewById(R.id.txt_import);
        importButton = findViewById(R.id.btn_import);
        languageSpinner = findViewById(R.id.spinner_language);

        importButton.setOnClickListener(v -> {
            pickModelLauncher.launch(new String[]{"*/*"});
        });

        findViewById(R.id.btn_model_links).setOnClickListener(v -> showLinksDialog());
        View btnPacks = findViewById(R.id.btn_recommended_packs);
        if (btnPacks != null) {
            btnPacks.setOnClickListener(v -> showRecommendedPacksDialog());
        }

        setupLanguageSpinner();
        setupThreadsSpinner();
        setupStreamLatencySpinner();
        setupBackendSpinner();

        com.google.android.material.materialswitch.MaterialSwitch translateSwitch =
                findViewById(R.id.switch_translate);
        translateSwitch.setChecked(!readConfig("model_translate").isEmpty());
        translateSwitch.setOnCheckedChangeListener((btn, checked) -> {
            writeConfig("model_translate", checked ? "1" : "");
            statusText.setText(getString(R.string.models_loading));
            reloadModelNative(this);
        });

        refreshList();
    }

    // --- Language selection -------------------------------------------------

    /**
     * Locales offered in the language dropdown. The empty tag is the model's
     * automatic mode (true auto-detection: the bundled Nemotron model detects
     * the spoken language per utterance; models without native detection,
     * like Canary, fall back to the device language in the engine). Display
     * names come from {@link Locale#getDisplayName()}, so they follow the
     * device language without needing translations. Tags a model doesn't
     * support are degraded by the engine at run time.
     */
    private static final String[] LANGUAGE_TAGS = {
            "", "bg-BG", "hr-HR", "cs-CZ", "da-DK", "nl-NL", "en-US", "en-GB",
            "et-EE", "fi-FI", "fr-FR", "de-DE", "el-GR", "hu-HU", "it-IT",
            "lv-LV", "lt-LT", "mt-MT", "pl-PL", "pt-PT", "pt-BR", "ro-RO",
            "ru-RU", "sk-SK", "sl-SI", "es-ES", "sv-SE", "uk-UA",
            "ar-SA", "zh-CN", "hi-IN", "ja-JP", "ko-KR", "tr-TR",
    };

    private void setupLanguageSpinner() {
        String stored = readConfig("model_language");
        // "auto" is stored as the marker value but maps to the empty-tag
        // (Auto) entry; normalize so the initial setSelection matches and the
        // onItemSelected guard short-circuits (no spurious reload on open).
        if (stored.isEmpty() || stored.equalsIgnoreCase("auto")) stored = "";

        List<String> tags = new ArrayList<>(Arrays.asList(LANGUAGE_TAGS));
        // A value from an older version (free-text field) stays selectable.
        if (!stored.isEmpty() && !tags.contains(stored)) {
            tags.add(stored);
        }

        List<String> labels = new ArrayList<>(tags.size());
        for (String tag : tags) {
            labels.add(tag.isEmpty()
                    ? getString(R.string.models_language_auto)
                    : Locale.forLanguageTag(tag).getDisplayName() + " (" + tag + ")");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);
        languageSpinner.setSelection(Math.max(0, tags.indexOf(stored)), false);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String tag = tags.get(position);
                if (tag.equals(readConfig("model_language"))) return;
                // "Auto" selects the model's native automatic detection; the
                // device language is refreshed as the fallback hint for models
                // without native detection (Canary-family), resolved in Rust.
                String value = tag.isEmpty() ? "auto" : tag;
                writeConfig("model_language", value);
                if (tag.isEmpty()) {
                    writeConfig("device_language", deviceLanguageTag());
                }
                snackbar(getString(R.string.models_language_saved));
                statusText.setText(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /// The device's current language as a BCP-47 tag (e.g. "es-ES", "en-US",
    /// "fr-FR"). Used when the user picks "Auto" so the model transcribes in
    /// the phone's system language.
    private static String deviceLanguageTag() {
        return Locale.getDefault().toLanguageTag();
    }

    // --- Streaming latency (cache-aware chunk selector) --------------------

    /**
     * Cache-aware streaming chunk selector, stored in {@code stream_context_right}.
     * Applies to streaming models (the bundled Nemotron); ignored otherwise.
     * Values are the model's training menu {13, 6, 1, 0} → chunk sizes
     * {1.12 s, 560 ms, 160 ms, 80 ms}. 13 (Balanced) is the model's
     * max-accuracy default; smaller values make partial hypotheses appear
     * much sooner on slow devices at a small WER cost (see the engine's
     * per-session rtf/cadence logcat line to measure the trade-off).
     */
    private void setupStreamLatencySpinner() {
        Spinner spinner = findViewById(R.id.spinner_stream_latency);
        // Absent marker = the engine default (13). Normalizing here prevents
        // the initial setSelection from firing onItemSelected with "13" != ""
        // and triggering a needless writeConfig + model reload on every open.
        String stored = readConfig("stream_context_right");
        if (stored.isEmpty()) stored = "13";

        List<String> values = new ArrayList<>(Arrays.asList("13", "6", "1", "0"));
        int[] labelRes = {
                R.string.models_stream_latency_balanced,
                R.string.models_stream_latency_fast,
                R.string.models_stream_latency_faster,
                R.string.models_stream_latency_lowest,
        };

        List<String> labels = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            labels.add(getString(labelRes[i]));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, values.indexOf(stored)), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = values.get(position);
                if (value.equals(readConfig("stream_context_right"))) return;
                writeConfig("stream_context_right", value);
                statusText.setText(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Inference threads --------------------------------------------------

    /**
     * CPU thread count for inference, stored in {@code model_threads}. Empty =
     * automatic (the engine derives it from the CPU topology). Offered values
     * cover the realistic range on phones; the engine treats anything invalid
     * as automatic.
     */
    private void setupThreadsSpinner() {
        Spinner spinner = findViewById(R.id.spinner_threads);
        String stored = readConfig("model_threads");

        List<String> values = new ArrayList<>(
                Arrays.asList("", "1", "2", "3", "4", "5", "6", "8"));
        if (!stored.isEmpty() && !values.contains(stored)) {
            values.add(stored);
        }

        List<String> labels = new ArrayList<>(values.size());
        for (String v : values) {
            labels.add(v.isEmpty() ? getString(R.string.models_threads_auto) : v);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, values.indexOf(stored)), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = values.get(position);
                if (value.equals(readConfig("model_threads"))) return;
                writeConfig("model_threads", value);
                statusText.setText(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Hardware acceleration backend --------------------------------------

    /**
     * Inference backend, stored in {@code hardware_backend}.
     *
     * <p>Honesty fix (Android 17 pass): this screen used to offer "NPU (NNAPI)"
     * and "GPU (Vulkan)" next to "CPU". Those options never did anything —
     * {@code src/engine.rs} reads the {@code hardware_backend} marker and only
     * logs it; there is no NNAPI or Vulkan code path anywhere in the native
     * engine, so selecting them silently ran on the CPU while telling the user
     * the opposite. A control that does not correspond to real behaviour is
     * worse than no control, so the selector is now a read-only statement of the
     * one backend that exists (ARM NEON + dotprod + fp16, pinned in
     * app/build.gradle.kts via GGML_CPU_ARM_ARCH).
     *
     * <p>Any previously stored {@code npu}/{@code gpu} value left over from an
     * older build is normalised to {@code cpu} so the marker can never keep
     * advertising an unimplemented backend.
     */
    private void setupBackendSpinner() {
        Spinner spinner = findViewById(R.id.spinner_backend);
        if (spinner == null) return;

        if (!"cpu".equals(readConfig("hardware_backend"))) {
            writeConfig("hardware_backend", "cpu");
        }

        List<String> labels = new ArrayList<>(1);
        labels.add(getString(R.string.models_backend_cpu));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0, false);
        // Read-only: no OnItemSelectedListener, so there is no path that can
        // write a backend the engine does not implement.
        spinner.setEnabled(false);
        spinner.setAlpha(1f);
    }

    // --- Model list -------------------------------------------------------

    private File modelsDir() {
        File dir = new File(getFilesDir(), "models");
        dir.mkdirs();
        return dir;
    }

    private String readConfig(String name) {
        return MarkerFileHelper.readString(this, name, "");
    }

    /**
     * Every model-setting marker is written through {@link MarkerFileHelper}
     * (temp file + fsync + rename, delete on empty) so the main process and
     * a concurrent reader never observes a partially-written value (P1.2).
     */
    private void writeConfig(String name, String value) {
        MarkerFileHelper.writeString(this, name, value);
    }

    private void refreshList() {
        modelList.removeAllViews();
        String active = readConfig("active_model");

        List<String> names = new ArrayList<>();
        File[] files = modelsDir().listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (files != null) {
            for (File f : files) names.add(f.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);

        // If the active model's file has disappeared, fall back to built-in.
        if (!active.isEmpty() && !names.contains(active)) {
            writeConfig("active_model", "");
            active = "";
        }

        addModelRow(getString(R.string.models_builtin),
                getString(R.string.models_builtin_sub), null, active.isEmpty());
        for (String name : names) {
            String size = Formatter.formatShortFileSize(
                    this, new File(modelsDir(), name).length());
            addModelRow(name, size, name, name.equals(active));
        }
    }

    /** Adds one selectable row; {@code fileName} is null for the built-in model. */
    private void addModelRow(String title, String subtitle, String fileName, boolean checked) {
        View row = getLayoutInflater().inflate(R.layout.item_model, modelList, false);
        ((TextView) row.findViewById(R.id.txt_model_name)).setText(title);
        ((TextView) row.findViewById(R.id.txt_model_sub)).setText(subtitle);

        RadioButton radio = row.findViewById(R.id.radio_model);
        radio.setChecked(checked);
        row.setOnClickListener(v -> {
            if (!checked) selectModel(fileName);
        });

        ImageButton delete = row.findViewById(R.id.btn_model_delete);
        if (fileName != null) {
            delete.setOnClickListener(v -> confirmDelete(fileName));
        } else {
            delete.setVisibility(View.GONE);
        }

        modelList.addView(row);
    }

    private void selectModel(String fileNameOrNull) {
        writeConfig("active_model", fileNameOrNull == null ? "" : fileNameOrNull);
        refreshList();
        statusText.setText(getString(R.string.models_loading));
        reloadModelNative(this);
    }

    private void confirmDelete(String fileName) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_delete)
                .setMessage(getString(R.string.models_delete_confirm, fileName))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    new File(modelsDir(), fileName).delete();
                    if (fileName.equals(readConfig("active_model"))) {
                        selectModel(null);
                    } else {
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- Download links ----------------------------------------------------

    private void showLinksDialog() {
        ArrayAdapter<ModelLink> adapter = new ArrayAdapter<ModelLink>(
                this, R.layout.item_model_link, MODEL_LINKS) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_model_link, parent, false);
                ModelLink link = MODEL_LINKS[position];
                ((TextView) row.findViewById(R.id.txt_link_name)).setText(link.name);
                ((TextView) row.findViewById(R.id.txt_link_size)).setText(link.size);
                ((TextView) row.findViewById(R.id.txt_link_desc)).setText(link.descRes);
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_links_title)
                .setAdapter(adapter, (d, which) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_LINKS[which].url));
                    try {
                        startActivity(view);
                    } catch (android.content.ActivityNotFoundException e) {
                        snackbar(getString(R.string.models_no_browser));
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showRecommendedPacksDialog() {
        ArrayAdapter<RecommendedPack> adapter = new ArrayAdapter<RecommendedPack>(
                this, R.layout.item_model_link, RECOMMENDED_PACKS) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_model_link, parent, false);
                RecommendedPack pack = RECOMMENDED_PACKS[position];
                ((TextView) row.findViewById(R.id.txt_link_name)).setText(pack.titleRes);
                ((TextView) row.findViewById(R.id.txt_link_size)).setText("");
                ((TextView) row.findViewById(R.id.txt_link_desc)).setText(pack.descRes);
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_recommended_packs_title)
                .setAdapter(adapter, (d, which) -> {
                    RecommendedPack pack = RECOMMENDED_PACKS[which];
                    if (pack.isPostProcessSettings) {
                        startActivity(new Intent(this, PostProcessSettingsActivity.class));
                    } else if (pack.actionUrl != null) {
                        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(pack.actionUrl));
                        try {
                            startActivity(view);
                        } catch (android.content.ActivityNotFoundException e) {
                            snackbar(getString(R.string.models_no_browser));
                        }
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --- Import ------------------------------------------------------------

    private void handleModelImportUri(Uri uri) {
        String name = queryDisplayName(uri);
        long size = querySize(uri);

        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_import_bad_title)
                    .setMessage(R.string.models_import_bad_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // Sanitize the provider-supplied file name (P1.3 hardening): SAF
        // documents that DISPLAY_NAME has no path separators, but a hostile
        // or buggy provider could return one and escape filesDir/models.
        name = sanitizeModelFileName(name);
        if (name == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_import_bad_title)
                    .setMessage(R.string.models_import_bad_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (size > ModelImportHelper.MAX_MODEL_SIZE_BYTES) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_import_bad_title)
                    .setMessage(R.string.models_import_bad_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (size > 0 && DeviceStorage.availableBytes(this, getFilesDir()) < size + FREE_SPACE_MARGIN) {
            snackbar(getString(R.string.models_import_no_space));
            return;
        }

        importModel(uri, name, size);
    }

    private void importModel(Uri uri, String name, long size) {
        importButton.setEnabled(false);
        importArea.setVisibility(View.VISIBLE);
        importBar.setIndeterminate(size <= 0);
        importBar.setMax(100);
        importBar.setProgress(0);
        importText.setText(getString(R.string.models_importing, name));

        File dest = new File(modelsDir(), name);
        File tmp = new File(modelsDir(), name + ".part" + System.currentTimeMillis());
        // A large GGUF import can outlive this Activity (rotation, task
        // switch, low-memory kill). The thread must not touch the Activity
        // or its views after destruction: resolve views on the UI thread
        // through a weak reference guarded by isFinishing/isDestroyed.
        final java.lang.ref.WeakReference<ModelsActivity> weak = new java.lang.ref.WeakReference<>(this);
        // Application context for pure-storage work (progress is UI-only and
        // must not touch a dead Activity's views).
        final android.content.Context appContext = getApplicationContext();
        final String finalName = name;

        new Thread(() -> {
            ModelImportHelper.ImportResult result;
            try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                result = ModelImportHelper.copyAndPromote(
                        in, dest, tmp, size,
                        (copied, declared) -> {
                            if (declared > 0) {
                                final int pct = (int) Math.min(100L, copied * 100 / declared);
                                postOnUi(weak, activity -> activity.importBar.setProgress(pct));
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Model import failed", e);
                result = ModelImportHelper.ImportResult.error("Failed to open or process model stream: " + e.getMessage());
            }

            final boolean success = result.success;
            postOnUi(weak, activity -> {
                activity.importButton.setEnabled(true);
                activity.importArea.setVisibility(View.GONE);
                if (success) {
                    activity.snackbar(activity.getString(R.string.models_import_done, finalName));
                    activity.refreshList();
                } else {
                    activity.snackbar(activity.getString(R.string.models_import_failed));
                }
            });
        }, "model-import").start();
    }

    /** Runs {@code action} on the UI thread only while the Activity is alive. */
    private static void postOnUi(java.lang.ref.WeakReference<ModelsActivity> weak,
                                 java.util.function.Consumer<ModelsActivity> action) {
        final ModelsActivity activity = weak.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (weak.get() == activity && !activity.isFinishing() && !activity.isDestroyed()) {
                action.accept(activity);
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query display name", e);
        }
        return uri.getLastPathSegment();
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query size", e);
        }
        return -1;
    }

    /**
     * Rejects file names that could escape {@code filesDir/models/}: path
     * separators, parent-dir components, control characters and backslashes.
     * Returns {@code null} when the name is unsafe, so callers can surface
     * the import error instead of writing outside the sandbox.
     */
    private static String sanitizeModelFileName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) return null;
        }
        return trimmed;
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    // Called from Rust with load progress ("Loading model...", "Ready", "Error: ...").
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    private native void reloadModelNative(ModelsActivity activity);
}
