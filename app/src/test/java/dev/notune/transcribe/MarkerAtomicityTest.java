package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM concurrency coverage for atomic marker-file writes across concurrent
 * components and threads (P1.2): a reader must never observe a partially-written
 * marker — only a complete value (the old one, the new one, or none on first creation).
 *
 * <p>This is what guarantees, e.g., that the engine never reads a torn
 * {@code model_language} or {@code stream_context_right} value while the
 * settings screen rewrites it.</p>
 */
public class MarkerAtomicityTest {

    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("marker-atomic").toFile();
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
    public void concurrentWritersNeverProducePartialReads() throws Exception {
        // Large distinct values make any torn read (truncated/mixed content)
        // detectable: with 20 KB payloads a partial read would be caught.
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            a.append((char) ('a' + i % 26));
            b.append((char) ('z' - i % 26));
        }
        final List<String> values = Arrays.asList(a.toString(), b.toString(), "value-c");
        final List<String> valid = new ArrayList<>(values);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> violation = new AtomicReference<>(null);

        List<Thread> writers = new ArrayList<>();
        for (final String value : values) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                for (int i = 0; i < 150 && violation.get() == null; i++) {
                    MarkerFileHelper.writeStringToFile(tempDirectory, "marker.txt", value);
                }
            }, "writer-" + value.charAt(0));
            writers.add(t);
            t.start();
        }

        Thread reader = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException ignored) {
            }
            for (int i = 0; i < 10000 && violation.get() == null; i++) {
                String s = MarkerFileHelper.readStringFromFile(tempDirectory, "marker.txt", "");
                // Empty is allowed only on first creation; anything else must
                // be one of the complete written values.
                if (!s.isEmpty() && !valid.contains(s)) {
                    violation.set(s.length() > 60 ? s.substring(0, 60) + "…" : s);
                    return;
                }
            }
        }, "reader");
        reader.start();

        start.countDown();
        for (Thread t : writers) t.join(30000);
        reader.join(30000);

        assertNull("reader observed a partial/corrupt marker value: " + violation.get(),
                violation.get());
        assertTrue("all writer threads must finish", writers.stream().allMatch(t -> !t.isAlive()));
    }
}
