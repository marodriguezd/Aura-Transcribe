package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM persistence coverage for {@link MarkerFileHelper} that does NOT
 * require a {@code Context}/Robolectric. This is the operational test harness
 * the Guantelete gate (AGENTS.md §3 "Validación y estilo") relies on:
 * `testDebugUnitTest` runs these directly on the JVM so they execute fast in CI
 * without an emulator.
 */
public class MarkerFileHelperPersistenceTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-persist-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            File[] kids = tempDirectory.listFiles();
            if (kids != null) {
                for (File k : kids) k.delete();
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void readStringTrimsWhitespace() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "ws.txt", "  hello  \n");
        assertEquals("hello", MarkerFileHelper.readStringFromFile(tempDirectory, "ws.txt", ""));
    }

    @Test
    public void readIntReturnsDefaultOnGarbage() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "bad.txt", "not-a-number");
        assertEquals(99, MarkerFileHelper.readIntFromFile(tempDirectory, "bad.txt", 99));
    }

    @Test
    public void readIntReturnsDefaultWhenMissing() {
        assertEquals(99, MarkerFileHelper.readIntFromFile(tempDirectory, "absent.txt", 99));
    }

    @Test
    public void writeIntRoundTripNegative() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "neg.txt", -7);
        assertEquals(-7, MarkerFileHelper.readIntFromFile(tempDirectory, "neg.txt", 0));
    }

    @Test
    public void writeStringOverwritesExisting() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "ow.txt", "old");
        MarkerFileHelper.writeStringToFile(tempDirectory, "ow.txt", "new");
        assertEquals("new", MarkerFileHelper.readStringFromFile(tempDirectory, "ow.txt", ""));
    }

    @Test
    public void writeEmptyDeletesFile() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "del.txt", "data");
        assertTrue(new File(tempDirectory, "del.txt").exists());
        MarkerFileHelper.writeStringToFile(tempDirectory, "del.txt", "");
        assertFalse(new File(tempDirectory, "del.txt").exists());
    }

    @Test
    public void readStringMissingReturnsDefault() {
        assertEquals("fallback", MarkerFileHelper.readStringFromFile(tempDirectory, "nope.txt", "fallback"));
    }

    @Test
    public void hardwareBackendPersistenceRoundTrip() {
        assertEquals("cpu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "hardware_backend", "npu");
        assertEquals("npu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "hardware_backend", "gpu");
        assertEquals("gpu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
    }

    @Test
    public void streamLatencyPersistenceRoundTrip() {
        assertEquals("13", MarkerFileHelper.readStringFromFile(tempDirectory, "stream_context_right", "13"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "stream_context_right", "0");
        assertEquals("0", MarkerFileHelper.readStringFromFile(tempDirectory, "stream_context_right", "13"));
    }

    @Test
    public void firstTimeCreationReturnsTrueAndWritesValue() {
        File marker = new File(tempDirectory, "first_create.txt");
        assertFalse(marker.exists());
        boolean ok = MarkerFileHelper.writeStringToFile(tempDirectory, "first_create.txt", "initial_val");
        assertTrue(ok);
        assertTrue(marker.exists());
        assertEquals("initial_val", MarkerFileHelper.readStringFromFile(tempDirectory, "first_create.txt", ""));
    }

    @Test
    public void replacementOfExistingValueAtomicallyUpdatesContent() {
        boolean ok1 = MarkerFileHelper.writeStringToFile(tempDirectory, "replace.txt", "v1");
        assertTrue(ok1);
        assertEquals("v1", MarkerFileHelper.readStringFromFile(tempDirectory, "replace.txt", ""));

        boolean ok2 = MarkerFileHelper.writeStringToFile(tempDirectory, "replace.txt", "v2");
        assertTrue(ok2);
        assertEquals("v2", MarkerFileHelper.readStringFromFile(tempDirectory, "replace.txt", ""));
    }

    @Test
    public void promotionFailurePreservesExistingDestination() throws IOException {
        File existing = new File(tempDirectory, "target_conflict");
        assertTrue(existing.mkdir());
        File sub = new File(existing, "sub.txt");
        assertTrue(sub.createNewFile());

        // Attempting to overwrite a directory with a file will fail promotion
        boolean ok = MarkerFileHelper.writeStringToFile(tempDirectory, "target_conflict", "new_content");
        assertFalse(ok);
        assertTrue(existing.isDirectory());
        assertTrue(sub.exists());
    }
}
