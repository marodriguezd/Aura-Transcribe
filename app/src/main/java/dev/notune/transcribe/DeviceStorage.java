package dev.notune.transcribe;

import android.content.Context;
import android.os.storage.StorageManager;

import java.io.File;

/**
 * Free-space helper for the app's pre-flight "will the model fit?" checks.
 *
 * <p>{@link File#getUsableSpace()} reports only what is free right now. Android
 * can additionally reclaim cached data belonging to this app on demand, so on a
 * nearly-full device the raw figure under-reports what a download or an import
 * could actually use — and the app would refuse an operation that would have
 * succeeded. {@link StorageManager#getAllocatableBytes} accounts for that
 * clearable cache.
 */
final class DeviceStorage {

    private DeviceStorage() {
    }

    /**
     * Bytes the app could plausibly make available for [dir].
     *
     * @param context used to reach {@link StorageManager}; may be null
     * @param dir     the directory the bytes would be written to, used as the
     *                fallback when the platform figure is unavailable
     * @return a non-negative byte count; the larger of the platform's
     *         allocatable figure and the directory's usable space
     */
    static long availableBytes(Context context, File dir) {
        long usable = dir != null ? dir.getUsableSpace() : 0L;
        if (context == null) return Math.max(0L, usable);

        try {
            StorageManager storage =
                    (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (storage != null) {
                // Throws IOException when the volume is not mounted, and on a
                // few vendor builds; the usable-space figure is a fine fallback.
                long allocatable = storage.getAllocatableBytes(StorageManager.UUID_DEFAULT);
                return Math.max(0L, Math.max(usable, allocatable));
            }
        } catch (Exception ignored) {
            // Fall through to the directory figure.
        }
        return Math.max(0L, usable);
    }
}
