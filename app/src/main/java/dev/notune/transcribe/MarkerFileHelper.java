package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
     * Writes a UTF-8 string to a marker file atomically. If {@code value} is
     * null or empty, deletes the file.
     *
     * <p>The temp file is unique per write (P1.2): concurrent writers of the
     * same marker (settings UI, native readers) must never share a
     * temp path, or one writer's rename can move the file another writer is
     * still writing to, exposing partial content to readers. With per-write
     * temps, every rename is atomic and readers only ever see a complete
     * value.</p>
     */
    public static void writeString(Context context, String fileName, String value) {
        if (context == null || fileName == null) return;
        File dir = context.getApplicationContext().getFilesDir();
        File file = new File(dir, fileName);
        if (value == null || value.isEmpty()) {
            if (file.exists()) file.delete();
            return;
        }
        File temp = new File(dir, uniqueTempName(fileName));
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(temp)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
            if (!temp.renameTo(file)) {
                // Fallback to direct write if rename fails across partitions
                Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write marker file: " + fileName, e);
        } finally {
            if (temp.exists()) temp.delete();
        }
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
     * Writes an integer as a string to a marker file.
     */
    public static void writeInt(Context context, String fileName, int value) {
        writeString(context, fileName, Integer.toString(value));
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

    public static void writeStringToFile(File dir, String fileName, String value) {
        if (dir == null || fileName == null) return;
        if (value == null || value.isEmpty()) {
            File file = new File(dir, fileName);
            if (file.exists()) file.delete();
            return;
        }
        File temp = new File(dir, uniqueTempName(fileName));
        File file = new File(dir, fileName);
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(temp)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
            if (!temp.renameTo(file)) {
                Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Log or fallback
        } finally {
            if (temp.exists()) temp.delete();
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

    public static void writeIntToFile(File dir, String fileName, int value) {
        writeStringToFile(dir, fileName, Integer.toString(value));
    }
}
