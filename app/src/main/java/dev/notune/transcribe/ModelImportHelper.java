package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Android-independent helper for safely copying and promoting imported GGUF models.
 *
 * <p>Enforces a hard limit on total bytes copied, rejects null and empty streams,
 * writes to an isolated temporary file, and atomically promotes the file only upon
 * complete, successful read.</p>
 */
public final class ModelImportHelper {

    /**
     * Maximum allowed model size: 4 GiB.
     * Accommodates supported speech recognition models (e.g. Nemotron 0.6B Q8_0 at ~751 MB,
     * large Whisper and Parakeet GGUF models up to ~3.5 GiB) while preventing unbounded disk
     * consumption from rogue or infinite input streams.
     */
    public static final long MAX_MODEL_SIZE_BYTES = 4L * 1024 * 1024 * 1024L; // 4 GiB

    /** Buffer size used for streaming model import: 1 MiB. */
    public static final int BUFFER_SIZE = 1024 * 1024;

    public interface ProgressListener {
        /**
         * Called periodically as bytes are written.
         *
         * @param bytesCopied actual number of bytes copied so far
         * @param declaredSize size reported by content provider, or <= 0 if unknown/missing
         */
        void onProgress(long bytesCopied, long declaredSize);
    }

    private ModelImportHelper() {
    }

    /**
     * Result of an import copy operation.
     */
    public static final class ImportResult {
        public final boolean success;
        public final long bytesCopied;
        public final String errorMessage;

        public ImportResult(boolean success, long bytesCopied, String errorMessage) {
            this.success = success;
            this.bytesCopied = bytesCopied;
            this.errorMessage = errorMessage;
        }

        public static ImportResult ok(long bytesCopied) {
            return new ImportResult(true, bytesCopied, null);
        }

        public static ImportResult error(String message) {
            return new ImportResult(false, 0L, message);
        }

        public static ImportResult error(long bytesCopied, String message) {
            return new ImportResult(false, bytesCopied, message);
        }
    }

    /**
     * Copies data from {@code in} to a temporary file {@code tempFile} and promotes it to {@code destFile}.
     *
     * @param in input stream to read from; must not be null
     * @param destFile target destination file
     * @param tempFile temporary file to write to before promotion
     * @param declaredSize size reported by provider, or <= 0 if unknown
     * @param progressListener optional listener for progress reporting
     * @return ImportResult indicating success or failure with error details
     */
    public static ImportResult copyAndPromote(
            InputStream in,
            File destFile,
            File tempFile,
            long declaredSize,
            ProgressListener progressListener) {
        return copyAndPromote(in, destFile, tempFile, declaredSize, MAX_MODEL_SIZE_BYTES, progressListener);
    }

    /**
     * Overload allowing configurable maxSizeBytes (used for unit testing).
     */
    public static ImportResult copyAndPromote(
            InputStream in,
            File destFile,
            File tempFile,
            long declaredSize,
            long maxSizeBytes,
            ProgressListener progressListener) {
        if (destFile == null || tempFile == null) {
            return ImportResult.error("Destination or temporary file is null");
        }
        if (in == null) {
            return ImportResult.error("Input stream is null");
        }
        if (declaredSize > maxSizeBytes) {
            return ImportResult.error("Declared size (" + declaredSize + " bytes) exceeds maximum limit (" + maxSizeBytes + " bytes)");
        }

        long copied = 0;
        boolean copySucceeded = false;

        try {
            File parent = tempFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (read > 0) {
                        if (copied + read > maxSizeBytes) {
                            return ImportResult.error(copied, "Model exceeds maximum allowed size of " + maxSizeBytes + " bytes");
                        }
                        out.write(buf, 0, read);
                        copied += read;
                        if (progressListener != null) {
                            progressListener.onProgress(copied, declaredSize);
                        }
                    }
                }
                out.flush();
                if (out instanceof FileOutputStream) {
                    ((FileOutputStream) out).getFD().sync();
                }
            }

            if (copied == 0) {
                return ImportResult.error(0L, "Imported model file is empty (0 bytes)");
            }

            copySucceeded = true;
        } catch (IOException e) {
            return ImportResult.error(copied, "Failed to read or write model data: " + e.getMessage());
        } finally {
            if (!copySucceeded && tempFile.exists()) {
                tempFile.delete();
            }
        }

        // Safe promotion: promote tempFile to destFile
        boolean promoted = promote(tempFile, destFile);
        if (!promoted) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            return ImportResult.error(copied, "Failed to promote imported model file to destination: " + destFile.getName());
        }

        return ImportResult.ok(copied);
    }

    private static boolean promote(File source, File destination) {
        if (source == null || !source.exists() || destination == null) {
            return false;
        }
        try {
            Files.move(source.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(source.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException ex) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
