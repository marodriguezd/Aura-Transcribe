package dev.notune.transcribe;

import android.content.Context;

/**
 * Live-subtitle preferences, stored as a small file in filesDir like the other
 * settings (readable from any process without a content provider).
 */
public final class SubtitlePrefs {
    private static final String FILE_NAME = "subtitle_lines";
    /** Default: keep four lines visible so translation has more context. */
    public static final int DEFAULT_MAX_LINES = 4;

    private SubtitlePrefs() {}

    /** Returns the line limit for the subtitle overlay; 0 means unlimited. */
    public static int getMaxLines(Context ctx) {
        return MarkerFileHelper.readInt(ctx, FILE_NAME, DEFAULT_MAX_LINES);
    }

    public static void setMaxLines(Context ctx, int lines) {
        MarkerFileHelper.writeInt(ctx, FILE_NAME, lines);
    }

    private static final String OVERLAY_Y_FILE = "subtitle_overlay_y";
    /** Default: a small margin above the bottom edge. */
    public static final int DEFAULT_OVERLAY_Y = 100;

    /** Offset of the overlay above the screen bottom, set by dragging it. */
    public static int getOverlayY(Context ctx) {
        return MarkerFileHelper.readInt(ctx, OVERLAY_Y_FILE, DEFAULT_OVERLAY_Y);
    }

    public static void setOverlayY(Context ctx, int y) {
        MarkerFileHelper.writeInt(ctx, OVERLAY_Y_FILE, y);
    }

    private static final String TRANSLATION_TARGET_FILE = "subtitle_translation_target";

    /**
     * Live-subtitle translation target (fork addition): a BCP-47 tag from
     * {@link SubtitleTranslationTargets#TAGS}, or {@code auto} = keep the
     * original language (no translation). Read by {@code LiveSubtitleService}
     * at session start, so changing it needs no engine reload.
     */
    public static String getTranslationTarget(Context ctx) {
        String v = MarkerFileHelper.readString(ctx, TRANSLATION_TARGET_FILE, "");
        return SubtitleTranslationTargets.isValid(v) ? v : SubtitleTranslationTargets.AUTO;
    }

    public static void setTranslationTarget(Context ctx, String tag) {
        MarkerFileHelper.writeString(ctx, TRANSLATION_TARGET_FILE,
                SubtitleTranslationTargets.isValid(tag) ? tag : SubtitleTranslationTargets.AUTO);
    }
}
