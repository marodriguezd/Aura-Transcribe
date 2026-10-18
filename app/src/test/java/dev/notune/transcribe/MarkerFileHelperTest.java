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

public class MarkerFileHelperTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-marker-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            for (File file : tempDirectory.listFiles()) {
                file.delete();
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void testWriteStringAndReadString() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "test_marker.txt", "hello_world");
        String value = MarkerFileHelper.readStringFromFile(tempDirectory, "test_marker.txt", "default");
        assertEquals("hello_world", value);
    }

    @Test
    public void testReadStringMissingFileReturnsDefault() {
        String value = MarkerFileHelper.readStringFromFile(tempDirectory, "non_existent.txt", "fallback");
        assertEquals("fallback", value);
    }

    @Test
    public void testWriteIntAndReadInt() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "test_int.txt", 42);
        int value = MarkerFileHelper.readIntFromFile(tempDirectory, "test_int.txt", 0);
        assertEquals(42, value);
    }

    @Test
    public void testWriteNullOrEmptyDeletesFile() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "delete_me.txt", "data");
        File file = new File(tempDirectory, "delete_me.txt");
        assertTrue(file.exists());

        MarkerFileHelper.writeStringToFile(tempDirectory, "delete_me.txt", "");
        assertFalse(file.exists());
    }


    @Test
    public void testWriteIfAbsentDoesNotOverwriteExisting() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "marker.txt", "original");
        boolean won = MarkerFileHelper.writeStringIfAbsentToFile(tempDirectory, "marker.txt", "new-value");
        assertFalse(won);
        assertEquals("original", MarkerFileHelper.readStringFromFile(tempDirectory, "marker.txt", ""));
    }

    @Test
    public void testWriteIfAbsentCreatesWhenAbsent() {
        boolean won = MarkerFileHelper.writeStringIfAbsentToFile(tempDirectory, "absent.txt", "value");
        assertTrue(won);
        assertEquals("value", MarkerFileHelper.readStringFromFile(tempDirectory, "absent.txt", ""));
    }

    @Test
    public void testWriteIfAbsentConcurrentAtMostOneWins() throws Exception {
        final String name = "race.txt";
        final int threads = 8;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<Thread> list = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String value = "v" + i;
            Thread t = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                if (MarkerFileHelper.writeStringIfAbsentToFile(tempDirectory, name, value)) {
                    wins.incrementAndGet();
                }
            });
            list.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : list) t.join();
        assertTrue("at most one winner", wins.get() <= 1);
        String content = MarkerFileHelper.readStringFromFile(tempDirectory, name, "");
        assertTrue("content is one of the submitted values", content.startsWith("v"));
        // never partial: full value
        assertEquals(2, content.length());
    }

}
