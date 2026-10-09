package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM coverage for the model-availability probes that
 * {@code MainActivity.maybeDownloadDebugModel()} now runs on a background
 * thread.
 *
 * <p>The point of these tests is the decision logic, not the threading: the
 * probes were extracted into {@link ModelAvailability} precisely so the
 * {@code active_model} marker parsing (trim, empty, dangling name) can be
 * exercised without a device or Robolectric. {@link
 * ModelAvailability#hasBundledModel(android.content.res.AssetManager)} needs a
 * real {@code AssetManager}, so only its explicit null/unavailable handling is
 * asserted here.
 */
public class ModelAvailabilityTest {

    private File filesDir;

    @Before
    public void setUp() throws IOException {
        filesDir = Files.createTempDirectory("model-availability-test").toFile();
        assertTrue(new File(filesDir, ModelAvailability.MODELS_DIR).mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(filesDir);
    }

    @Test
    public void noMarkerMeansNoImportedModel() {
        assertFalse(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void missingFilesDirMeansNoImportedModel() {
        assertFalse(ModelAvailability.hasImportedModel(null));
    }

    @Test
    public void markerPointingAtAMissingFileIsNotAModel() {
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "gone.gguf");
        assertFalse(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void markerPointingAtAnExistingFileIsAModel() throws IOException {
        File model = new File(new File(filesDir, ModelAvailability.MODELS_DIR), "present.gguf");
        assertTrue(model.createNewFile());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "present.gguf");
        assertTrue(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void markerWhitespaceIsTrimmedBeforeTheLookup() throws IOException {
        File model = new File(new File(filesDir, ModelAvailability.MODELS_DIR), "padded.gguf");
        assertTrue(model.createNewFile());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "  padded.gguf \n");
        assertTrue(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void emptyMarkerIsNotAModel() {
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER, "   ");
        assertFalse(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void importedModelPathIsNotConfusedWithAFileInFilesDirRoot() throws IOException {
        // The engine loads filesDir/models/<name>, never filesDir/<name>: a file
        // with the right name in the wrong directory must not count.
        assertTrue(new File(filesDir, "root-level.gguf").createNewFile());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "root-level.gguf");
        assertFalse(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void markerPointingIntoAMissingModelsDirectoryIsNotAModel() {
        // Covers an upgraded install: the marker survived, the imported file (and
        // its directory) did not. The background probe must not report a model that
        // the engine would then fail to load.
        File modelsDir = new File(filesDir, ModelAvailability.MODELS_DIR);
        for (File kid : modelsDir.listFiles()) {
            kid.delete();
        }
        assertTrue(modelsDir.delete());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "vanished.gguf");
        assertFalse(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void markerMayNameAModelInASubdirectoryOfModels() throws IOException {
        File nested = new File(new File(filesDir, ModelAvailability.MODELS_DIR), "imported");
        assertTrue(nested.mkdirs());
        assertTrue(new File(nested, "nested.gguf").createNewFile());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "imported/nested.gguf");
        assertTrue(ModelAvailability.hasImportedModel(filesDir));
    }

    @Test
    public void nullAssetManagerIsHandledExplicitly() {
        assertFalse(ModelAvailability.hasBundledModel(null));
    }

    @Test
    public void isModelReadyFallsBackToTheImportedModel() throws IOException {
        assertTrue(new File(new File(filesDir, ModelAvailability.MODELS_DIR), "only.gguf")
                .createNewFile());
        MarkerFileHelper.writeStringToFile(filesDir, ModelAvailability.ACTIVE_MODEL_MARKER,
                "only.gguf");
        // Assets unavailable (null) — the imported model is the remaining path.
        assertTrue(ModelAvailability.isModelReady(null, filesDir));
    }

    @Test
    public void isModelReadyIsFalseWhenNothingIsAvailable() {
        assertFalse(ModelAvailability.isModelReady(null, filesDir));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) deleteRecursively(kid);
        }
        file.delete();
    }
}
