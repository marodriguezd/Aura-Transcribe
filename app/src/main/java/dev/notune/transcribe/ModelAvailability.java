package dev.notune.transcribe;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Answers "is there a speech model this build can load?" without a {@code Context}.
 *
 * <p>Extracted so the probes can run off the main thread. {@link
 * #hasBundledModel(AssetManager)} walks the asset database and {@link
 * #hasImportedModel(File)} reads the {@code active_model} marker and stats the
 * model file it points at; both are disk work that StrictMode flagged when they
 * ran inside {@code MainActivity.onCreate()}/{@code onResume()}. Keeping the
 * methods free of views, dialogs and {@code Context} means a worker thread can
 * call them and post only the resulting boolean back to the UI.
 *
 * <p>Deliberately free of {@code android.app.Activity} state so the logic is
 * coverable by plain JUnit tests on the JVM (see {@code ModelAvailabilityTest});
 * only {@link #hasBundledModel(AssetManager)} needs an Android object, and a
 * null/absent {@code AssetManager} is handled explicitly rather than throwing.
 */
final class ModelAvailability {

    private static final String TAG = "ModelAvailability";

    /** Directory inside {@code assets/} that holds the bundled GGUF model. */
    static final String BUILTIN_ASSET_DIR = "builtin-model";

    /** Marker file whose content is the file name of the imported/active model. */
    static final String ACTIVE_MODEL_MARKER = "active_model";

    /** Directory under {@code filesDir()} containing user-imported models. */
    static final String MODELS_DIR = "models";

    private ModelAvailability() {
    }

    /**
     * True when the APK/AAB actually ships the bundled model.
     *
     * <p>{@code AssetManager.list()} returns an empty array for a missing
     * directory and a null array in some framework edge cases, and throws
     * {@link IOException} when the asset database cannot be read at all. All
     * three mean "no bundled model available", which is what the caller needs to
     * know, so none of them propagate.
     */
    static boolean hasBundledModel(AssetManager assets) {
        if (assets == null) return false;
        try {
            String[] list = assets.list(BUILTIN_ASSET_DIR);
            return list != null && list.length > 0;
        } catch (IOException | RuntimeException e) {
            // A broken asset database must not abort startup; the runtime
            // download path is the recovery for a build without the asset.
            Log.w(TAG, "Could not enumerate bundled model assets", e);
            return false;
        }
    }

    /**
     * True when {@code active_model} names a model that is present on disk.
     *
     * <p>Read-only and idempotent, so it is safe to call on a worker thread. The
     * marker is written by {@code MarkerFileHelper} (atomic rename), so reading
     * it can never observe a partially-written name.
     */
    static boolean hasImportedModel(File filesDir) {
        if (filesDir == null) return false;
        String name = MarkerFileHelper.readStringFromFile(filesDir, ACTIVE_MODEL_MARKER, "");
        if (name == null || name.isEmpty()) return false;
        return new File(new File(filesDir, MODELS_DIR), name).exists();
    }

    /**
     * True when either delivery path provides a model: the bundled asset or an
     * imported file. Order matches the historical check (bundled first) so a
     * build that ships the asset never pays for the marker read.
     */
    static boolean isModelReady(AssetManager assets, File filesDir) {
        return hasBundledModel(assets) || hasImportedModel(filesDir);
    }
}
