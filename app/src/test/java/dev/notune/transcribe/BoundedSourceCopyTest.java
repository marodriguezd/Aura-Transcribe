package dev.notune.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Unit tests for the bounded copy that backs the temp-file fallback of
 * {@link TranscribeFileActivity}.
 *
 * <p>These matter because the copy loop reads untrusted bytes from another
 * app's content provider: the cap has to hold when size metadata is missing,
 * wrong, or hostile, and a failed copy must not leave a partial file in the
 * app cache. The decoded-duration cap protects a different failure mode
 * (CPU during decode) and is not exercised here.
 */
public class BoundedSourceCopyTest {

    private static final long LIMIT = 1024;

    // -------------------------------------------------------------- stream copy

    @Test
    public void copiesDataBelowTheLimit() throws Exception {
        byte[] data = new byte[512];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long copied = BoundedSourceCopy.copy(
                new ByteArrayInputStream(data), out, LIMIT, -1 /* unknown */);

        assertEquals(512, copied);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void copiesDataExactlyAtTheLimit() throws Exception {
        byte[] data = new byte[(int) LIMIT];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long copied = BoundedSourceCopy.copy(
                new ByteArrayInputStream(data), out, LIMIT, -1);

        assertEquals(LIMIT, copied);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void abortsBeforeWritingTheChunkThatWouldExceedTheLimit() throws Exception {
        byte[] data = new byte[(int) LIMIT + 1];
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            BoundedSourceCopy.copy(new ByteArrayInputStream(data), out, LIMIT, -1);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            // The single read serves the whole array at once, so the overflowing
            // chunk must be rejected before any of it lands in the output.
            assertEquals(0, out.size());
        }
    }

    @Test
    public void stopsAtTheLimitWhenTheStreamReadsInSmallChunks() throws Exception {
        byte[] data = new byte[2000];
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            BoundedSourceCopy.copy(chunked(data, 100), out, LIMIT, -1);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            // Ten full 100-byte chunks fit (1000 <= 1024); the eleventh would
            // cross the cap and is rejected before being written.
            assertEquals(1000, out.size());
            assertTrue(out.size() <= LIMIT);
        }
    }

    @Test
    public void enforcesTheLimitWhenTheSizeIsUnknown() throws Exception {
        // reportedSize = -1: no metadata at all (the common case for raw
        // streams). The read-time accounting must still stop the copy.
        byte[] data = new byte[10 * (int) LIMIT];
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            BoundedSourceCopy.copy(new ByteArrayInputStream(data), out, LIMIT, -1);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            assertTrue(out.size() <= LIMIT);
        }
    }

    @Test
    public void doesNotTrustAnUnderreportedSize() throws Exception {
        // The provider claims 5 bytes but serves 10x the limit: a metadata-only
        // check would wave this through.
        byte[] data = new byte[10 * (int) LIMIT];
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            BoundedSourceCopy.copy(new ByteArrayInputStream(data), out, LIMIT, 5);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            assertTrue(out.size() <= LIMIT);
        }
    }

    @Test
    public void rejectsAnOverreportedSizeBeforeReadingAnything() throws Exception {
        byte[] data = new byte[10];
        CountingInputStream in = new CountingInputStream(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            BoundedSourceCopy.copy(in, out, LIMIT, LIMIT + 1);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            assertEquals("a declared size over the cap must abort before reading", 0, in.reads);
            assertEquals(0, out.size());
        }
    }

    @Test
    public void rejectsANonPositiveLimit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BoundedSourceCopy.copy(new ByteArrayInputStream(new byte[1]), out, 0, -1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // A zero/negative cap would otherwise make every byte "too large"
            // (or, worse, be treated as unlimited after a refactor).
        }
    }

    // ------------------------------------------------------------ file copy

    @Test
    public void copyToFileKeepsACompletedCopy() throws Exception {
        File dest = reservedTempPath();
        byte[] data = new byte[256];

        long copied = BoundedSourceCopy.copyToFile(
                new ByteArrayInputStream(data), dest, LIMIT, -1);

        assertEquals(256, copied);
        assertTrue(dest.isFile());
        assertEquals(256, dest.length());
        assertTrue(dest.delete());
    }

    @Test
    public void copyToFileDeletesThePartialFileWhenTheLimitIsHit() throws Exception {
        File dest = reservedTempPath();
        byte[] data = new byte[(int) LIMIT + 1];

        try {
            BoundedSourceCopy.copyToFile(new ByteArrayInputStream(data), dest, LIMIT, -1);
            fail("expected SourceTooLargeException");
        } catch (BoundedSourceCopy.SourceTooLargeException expected) {
            assertFalse("partial file must be deleted after an over-limit copy",
                    dest.exists());
        }
    }

    @Test
    public void copyToFileDeletesTheFileWhenTheSourceStreamFails() throws Exception {
        File dest = reservedTempPath();

        try {
            BoundedSourceCopy.copyToFile(new FailingInputStream(), dest, LIMIT, -1);
            fail("expected the source failure to propagate");
        } catch (IOException expected) {
            assertEquals("simulated source failure", expected.getMessage());
            assertFalse("partial file must be deleted after a failed copy",
                    dest.exists());
        }
    }

    // ----------------------------------------------------------- policy cap

    @Test
    public void maxSourceFileBytesFitsThirtyMinutesOfRealisticInputs() {
        // Worst realistic 30-minute sources under the duration cap: the cap must
        // never reject a file the duration policy would have accepted...
        long pcm24BitStereo = 1800L * 48000L * 2L * 3L;   // 48 kHz/24-bit/stereo
        long pcm32BitFloatStereo = 1800L * 48000L * 2L * 4L; // 48 kHz/32-bit float
        assertTrue(BoundedSourceCopy.MAX_SOURCE_FILE_BYTES >= pcm24BitStereo);
        assertTrue(BoundedSourceCopy.MAX_SOURCE_FILE_BYTES >= pcm32BitFloatStereo);
        // ...while staying a hard, phone-sized bound on cache writes.
        assertTrue(BoundedSourceCopy.MAX_SOURCE_FILE_BYTES <= 2L * (1L << 30));
        assertTrue(BoundedSourceCopy.MAX_SOURCE_FILE_BYTES > 0);
    }

    // ------------------------------------------------------------- utilities

    /** Returns a path that does not exist yet (reserved, then released). */
    private static File reservedTempPath() throws IOException {
        File file = File.createTempFile("bounded-source-copy-test-", ".tmp");
        assertTrue(file.delete());
        return file;
    }

    /** An InputStream that yields at most {@code chunk} bytes per read call. */
    private static InputStream chunked(byte[] data, int chunk) {
        return new InputStream() {
            private int pos = 0;

            @Override
            public int read() {
                if (pos >= data.length) return -1;
                return data[pos++] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= data.length) return -1;
                int n = Math.min(Math.min(len, chunk), data.length - pos);
                System.arraycopy(data, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    /** An InputStream that serves one byte and then fails, counting reads. */
    private static final class CountingInputStream extends InputStream {
        private final byte[] data;
        private int pos = 0;
        int reads = 0;

        CountingInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            reads++;
            if (pos >= data.length) return -1;
            return data[pos++] & 0xff;
        }
    }

    private static final class FailingInputStream extends InputStream {
        private boolean served = false;

        @Override
        public int read() throws IOException {
            if (!served) {
                served = true;
                return 42;
            }
            throw new IOException("simulated source failure");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // Override the array variant too: the JDK's default implementation
            // swallows IOExceptions once it has served at least one byte.
            if (!served) {
                served = true;
                b[off] = 42;
                return 1;
            }
            throw new IOException("simulated source failure");
        }
    }
}
