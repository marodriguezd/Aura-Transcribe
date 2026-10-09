package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hard cap for the copy-to-cache fallback of {@link TranscribeFileActivity}.
 *
 * <p>When the incoming {@code content://}/{@code file://} URI cannot be opened
 * through a file descriptor, the Activity copies the source into its cache
 * before decoding it. That copy is untrusted input from another app, so it must
 * be bounded <em>while reading</em>: reported size metadata (OpenableColumns,
 * Content-Length) may be missing, stale or simply wrong, and only the bytes
 * actually served can be counted on.
 *
 * <p><b>Why 1 GiB.</b> The pipeline's policy cap is
 * {@link TranscribeFileActivity}'s 30-minute decoded-duration limit. The largest
 * byte rate a realistically supported container can produce under that cap is
 * uncompressed PCM: 48 kHz / 24-bit / stereo WAV is 288 000 B/s ≈ 494 MiB per
 * 30 minutes (48 kHz / 32-bit float stereo ≈ 659 MiB); every compressed codec
 * (MP3/AAC/Opus/FLAC) is far below that. 1 GiB therefore fits every valid
 * 30-minute source with more than 2× headroom, while bounding the cache write
 * for a hostile or buggy provider — or an effectively infinite stream — to a
 * single predictable number. The byte cap is a <em>complement</em> to the
 * duration cap, not a replacement: they guard different failure modes
 * (storage/CPU during copy vs. CPU during decode).
 *
 * <p>Deliberately free of Android types so the behaviour can be asserted in a
 * JVM unit test ({@code BoundedSourceCopyTest}).
 */
final class BoundedSourceCopy {

    /** Maximum bytes accepted by the fallback copy: 1 GiB. */
    static final long MAX_SOURCE_FILE_BYTES = 1L << 30;

    private static final int BUFFER_BYTES = 64 * 1024;

    private BoundedSourceCopy() {
    }

    /** Thrown as soon as the copy would exceed the byte limit; never partially swallowed. */
    static final class SourceTooLargeException extends IOException {
        SourceTooLargeException(long limitBytes) {
            super("source exceeds the " + limitBytes + "-byte import limit");
        }
    }

    /**
     * Copies {@code in} into {@code out}, aborting as soon as more than
     * {@code maxBytes} would have been written.
     *
     * <p>The limit is enforced against the bytes actually read, one read at a
     * time; the overflowing chunk is rejected <em>before</em> it is written.
     *
     * @param in           source stream (owned by the caller; not closed here)
     * @param out          destination stream (owned by the caller; not closed here)
     * @param maxBytes     inclusive byte cap; must be positive
     * @param reportedSize size the provider claims, or any value {@code <= 0} for
     *                     unknown/missing metadata. A claim above {@code maxBytes}
     *                     is honoured as a fast abort before reading anything; a
     *                     claim below the truth is never trusted — see above.
     * @return the number of bytes copied
     * @throws SourceTooLargeException when the cap would be exceeded
     * @throws IOException             when the underlying stream fails
     */
    static long copy(InputStream in, OutputStream out, long maxBytes, long reportedSize)
            throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        // Fast path only: a declared size above the cap is rejected without a
        // single read. Everything else falls through to the read-time accounting
        // below, which is authoritative (metadata may be unknown or understate
        // what the provider will actually serve).
        if (reportedSize > maxBytes) {
            throw new SourceTooLargeException(maxBytes);
        }
        byte[] buf = new byte[BUFFER_BYTES];
        long copied = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            // Reject the chunk that would cross the cap before writing any of it.
            if (read > maxBytes - copied) {
                throw new SourceTooLargeException(maxBytes);
            }
            out.write(buf, 0, read);
            copied += read;
        }
        return copied;
    }

    /**
     * {@link #copy} into a temporary file that is deleted whenever the copy does
     * not complete — limit exceeded, source stream failure, or any other
     * exception — so a failed import never leaves a partial file behind in the
     * app cache.
     *
     * <p>The file survives only when the copy finished <em>and</em> the stream
     * was flushed and closed successfully.
     *
     * @return the number of bytes copied
     */
    static long copyToFile(InputStream in, File dest, long maxBytes, long reportedSize)
            throws IOException {
        boolean success = false;
        long copied;
        try {
            try (OutputStream out = new FileOutputStream(dest)) {
                copied = copy(in, out, maxBytes, reportedSize);
            }
            // Only reached after the write stream was flushed and closed without
            // error; any failure before this line leaves success false.
            success = true;
        } finally {
            if (!success) {
                // Best effort: the caller's own cleanup (the Activity's finally
                // block) still deletes the file if this particular delete races.
                // noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
        return copied;
    }
}
