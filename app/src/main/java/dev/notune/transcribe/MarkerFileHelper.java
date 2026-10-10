package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Centralized helper for managing marker files in {@code filesDir()}.
 *
 * All app settings and states (auto_record, model_language, theme_mode, etc.)
 * are stored as marker files in the private app files directory to enable
 * consistent, content-provider-free access from Java and from the native
 * engine, which reads these files straight from the filesystem.
 */
public final class MarkerFileHelper {
    private static final String TAG = "MarkerFileHelper";

    private MarkerFileHelper() {
        // Utility class
    }

    /**
     * Checks if a marker file exists in {@code filesDir()}.
     */
    public static boolean exists(Context context, String fileName) {
        if (context == null || fileName == null) return false;
        return new File(context.getApplicationContext().getFilesDir(), fileName).exists();
    }

    /**
     * Creates or deletes a marker file depending on {@code present}.
     */
    public static void setExists(Context context, String fileName, boolean present) {
        if (context == null || fileName == null) return;
        File file = new File(context.getApplicationContext().getFilesDir(), fileName);
        if (present) {
            if (!file.exists()) {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create marker file: " + fileName, e);
                }
            }
        } else {
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * Reads a UTF-8 string from a marker file. Returns {@code defaultValue} if absent or error.
     */
    public static String readString(Context context, String fileName, String defaultValue) {
        if (context == null || fileName == null) return defaultValue;
        File file = new File(context.getApplicationContext().getFilesDir(), fileName);
        if (!file.isFile()) return defaultValue;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.w(TAG, "Failed to read string marker: " + fileName, e);
            return defaultValue;
        }
    }

    /**
     * Writes a UTF-8 string to a marker file using safe atomic promotion. If {@code value}
     * is null or empty, deletes the marker file.
     *
     * <p>The value is written and flushed (with {@code fsync}) to a unique temporary file
     * in the same directory, then promoted via {@link Files#move} with atomic replacement.
     * If atomic promotion fails, the previous destination file is preserved intact,
     * the temporary file is deleted, and a secret-free error diagnostic is logged.
     * The method never falls back to directly overwriting the destination file.</p>
     *
     * @return true if the marker file was successfully written (or deleted when empty); false on failure
     */
    public static boolean writeString(Context context, String fileName, String value) {
        if (context == null || fileName == null) return false;
        File dir = context.getApplicationContext().getFilesDir();
        return writeStringToFile(dir, fileName, value);
    }

    /**
     * Reads an integer from a marker file. Returns {@code defaultValue} if absent or unparseable.
     */
    public static int readInt(Context context, String fileName, int defaultValue) {
        String s = readString(context, fileName, null);
        if (s == null || s.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Writes an integer as a string to a marker file using safe atomic promotion.
     *
     * @return true if the marker file was successfully written; false on failure
     */
    public static boolean writeInt(Context context, String fileName, int value) {
        return writeString(context, fileName, Integer.toString(value));
    }

    /**
     * Deletes a marker file if it exists.
     */
    public static void delete(Context context, String fileName) {
        if (context == null || fileName == null) return;
        File file = new File(context.getApplicationContext().getFilesDir(), fileName);
        if (file.exists()) {
            file.delete();
        }
    }

    // -----------------------------------------------------------------------
    // Direct File-based methods for JVM unit testing without Context
    // -----------------------------------------------------------------------

    public static String readStringFromFile(File dir, String fileName, String defaultValue) {
        if (dir == null || fileName == null) return defaultValue;
        File file = new File(dir, fileName);
        if (!file.isFile()) return defaultValue;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return defaultValue;
        }
    }

    /**
     * Writes a UTF-8 string to a marker file in {@code dir} using safe atomic promotion.
     * If {@code value} is null or empty, deletes the marker file.
     *
     * <p>Promotes a flushed temporary file to {@code fileName} using an atomic replacement
     * mechanism. If promotion fails, the previous destination file is preserved intact,
     * the temporary file is deleted, and the method fails safely without direct overwriting.</p>
     *
     * @return true if the marker file was successfully written (or deleted when empty); false on failure
     */
     public static boolean writeStringToFile(File dir, String fileName, String value) {
         if (dir == null || fileName == null) return false;
         File file = new File(dir, fileName);
         if (value == null || value.isEmpty()) {
             if (file.exists()) {
                 return file.delete();
             }
             return true;
         }
         File temp = new File(dir, uniqueTempName(fileName));
         try (java.io.FileOutputStream os = new java.io.FileOutputStream(temp)) {
             os.write(value.getBytes(StandardCharsets.UTF_8));
             os.flush();
             os.getFD().sync();
             if (!promoteAtomic(temp, file)) {
                 Log.e(TAG, "Failed to atomically promote marker file: " + fileName);
                 return false;
             }
             return true;
         } catch (IOException e) {
             Log.e(TAG, "Failed to write temporary marker file: " + fileName, e);
             return false;
         } finally {
             if (temp.exists()) {
                 temp.delete();
             }
         }
     }

     /**
      * Safely promotes a temporary file to destination using an atomic replacement mechanism.
      * If ATOMIC_MOVE is unsupported, attempts REPLACE_EXISTING move, but never falls back
      * to a direct file-stream overwrite.
      */
     private static boolean promoteAtomic(File temp, File destination) {
         if (temp == null || destination == null || !temp.exists()) {
             return false;
         }
         try {
             Files.move(temp.toPath(), destination.toPath(),
                     StandardCopyOption.ATOMIC_MOVE,
                     StandardCopyOption.REPLACE_EXISTING);
             return true;
         } catch (AtomicMoveNotSupportedException e) {
             try {
                 Files.move(temp.toPath(), destination.toPath(),
                         StandardCopyOption.REPLACE_EXISTING);
                 return true;
             } catch (IOException ex) {
                 return false;
             }
         } catch (IOException e) {
             return false;
         }
     }

     /**
      * Per-write unique temp name: concurrent writers of the same marker must
      * never share a temp path (their renames would race and could expose a
      * partially-written target). Thread id + a monotonic nanoTime keep names
      * distinct across threads and repeated calls.
      */
     private static String uniqueTempName(String fileName) {
         return fileName + ".tmp" + Thread.currentThread().getId() + "-" + System.nanoTime();
     }

     public static int readIntFromFile(File dir, String fileName, int defaultValue) {
         String s = readStringFromFile(dir, fileName, null);
         if (s == null || s.isEmpty()) return defaultValue;
         try {
             return Integer.parseInt(s);
         } catch (NumberFormatException e) {
             return defaultValue;
         }
     }

     public static boolean writeIntToFile(File dir, String fileName, int value) {
         return writeStringToFile(dir, fileName, Integer.toString(value));
     }
}
