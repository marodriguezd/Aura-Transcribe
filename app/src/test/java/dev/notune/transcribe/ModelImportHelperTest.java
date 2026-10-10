package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelImportHelperTest {

    private File tempDir;
    private File destFile;
    private File tempFile;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("model-import-test").toFile();
        destFile = new File(tempDir, "test_model.gguf");
        tempFile = new File(tempDir, "test_model.gguf.part");
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File[] sub = f.listFiles();
                        if (sub != null) {
                            for (File s : sub) s.delete();
                        }
                    }
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    @Test
    public void nullInputStreamFailsCleanlyAndLeavesNoArtifacts() {
        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                null, destFile, tempFile, 1024L, null);
        assertFalse(result.success);
        assertFalse(destFile.exists());
        assertFalse(tempFile.exists());
    }

    @Test
    public void emptyInputStreamFailsAndLeavesNoArtifacts() {
        ByteArrayInputStream empty = new ByteArrayInputStream(new byte[0]);
        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                empty, destFile, tempFile, 0L, null);
        assertFalse(result.success);
        assertEquals("Imported model file is empty (0 bytes)", result.errorMessage);
        assertFalse(destFile.exists());
        assertFalse(tempFile.exists());
    }

    @Test
    public void streamFailureMidwayCleansUpTempAndPreservesDestination() throws IOException {
        Files.write(destFile.toPath(), "existing_valid_model".getBytes(StandardCharsets.UTF_8));
        assertTrue(destFile.exists());

        InputStream failingStream = new InputStream() {
            private int count = 0;
            @Override
            public int read() throws IOException {
                if (++count > 256) {
                    throw new IOException("Simulated network/stream failure midway");
                }
                return 'A';
            }
        };

        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                failingStream, destFile, tempFile, 1024L, null);
        assertFalse(result.success);
        assertFalse(tempFile.exists());
        assertTrue(destFile.exists());
        assertEquals("existing_valid_model", new String(Files.readAllBytes(destFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void exceedingByteLimitAbortsAndDeletesTemp() {
        byte[] data = new byte[2048];
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        // Enforce a small limit of 512 bytes
        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                in, destFile, tempFile, -1L, 512L, null);
        assertFalse(result.success);
        assertTrue(result.errorMessage.contains("exceeds maximum allowed size"));
        assertFalse(tempFile.exists());
        assertFalse(destFile.exists());
    }

    @Test
    public void declaredSizeExceedingLimitRejectsImmediately() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[100]);
        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                in, destFile, tempFile, 1000L, 500L, null);
        assertFalse(result.success);
        assertTrue(result.errorMessage.contains("exceeds maximum limit"));
        assertFalse(tempFile.exists());
        assertFalse(destFile.exists());
    }

    @Test
    public void successfulCopyingAndAtomicPromotion() throws IOException {
        byte[] expected = "GGUF_VALID_MODEL_HEADER_AND_WEIGHTS".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(expected);

        AtomicLong reportedCopied = new AtomicLong(0);
        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                in, destFile, tempFile, expected.length,
                (copied, declared) -> reportedCopied.set(copied));

        assertTrue(result.success);
        assertEquals(expected.length, result.bytesCopied);
        assertEquals(expected.length, reportedCopied.get());
        assertTrue(destFile.exists());
        assertFalse(tempFile.exists());
        assertArrayEquals(expected, Files.readAllBytes(destFile.toPath()));
    }

    @Test
    public void misleadingDeclaredSizeHandledSafely() throws IOException {
        byte[] expected = "TEST_MODEL_DATA".getBytes(StandardCharsets.UTF_8);

        // Case 1: declaredSize = -1 (unknown)
        ByteArrayInputStream in1 = new ByteArrayInputStream(expected);
        ModelImportHelper.ImportResult res1 = ModelImportHelper.copyAndPromote(
                in1, destFile, tempFile, -1L, null);
        assertTrue(res1.success);
        assertArrayEquals(expected, Files.readAllBytes(destFile.toPath()));

        // Case 2: declaredSize much larger than actual
        ByteArrayInputStream in2 = new ByteArrayInputStream(expected);
        ModelImportHelper.ImportResult res2 = ModelImportHelper.copyAndPromote(
                in2, destFile, tempFile, 1000000L, null);
        assertTrue(res2.success);
        assertArrayEquals(expected, Files.readAllBytes(destFile.toPath()));
    }

    @Test
    public void destinationAlreadyExistsReplacedAtomicallyOnSuccess() throws IOException {
        Files.write(destFile.toPath(), "old_model_data".getBytes(StandardCharsets.UTF_8));
        byte[] newModel = "new_model_data_version_2".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(newModel);

        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                in, destFile, tempFile, newModel.length, null);
        assertTrue(result.success);
        assertArrayEquals(newModel, Files.readAllBytes(destFile.toPath()));
        assertFalse(tempFile.exists());
    }

    @Test
    public void failedPromotionPreservesExistingDestination() throws IOException {
        // Create destination as a non-empty directory so promotion of a regular file onto it fails
        assertTrue(destFile.mkdir());
        File child = new File(destFile, "locked.bin");
        assertTrue(child.createNewFile());

        byte[] imported = "some_model_data".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(imported);

        ModelImportHelper.ImportResult result = ModelImportHelper.copyAndPromote(
                in, destFile, tempFile, imported.length, null);

        assertFalse(result.success);
        assertTrue(result.errorMessage.contains("Failed to promote"));
        assertTrue(destFile.isDirectory());
        assertTrue(child.exists());
        assertFalse(tempFile.exists());
    }
}
