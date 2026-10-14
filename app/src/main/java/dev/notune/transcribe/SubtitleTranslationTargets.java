package dev.notune.transcribe;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Supported targets for live-subtitle translation (fork addition).
 *
 * <p>Values are BCP-47 tags stored in the {@code subtitle_translation_target}
 * marker file (see {@link SubtitlePrefs}). {@link #AUTO} means <em>no
 * translation</em>: the subtitles keep the original language the ASR
 * detected — this is the product decision recorded for the feature
 * (Automatic = original language). An explicit tag enables on-device text
 * translation of the finalized transcript.
 */
public final class SubtitleTranslationTargets {
    /** Marker value meaning "no translation" (the historical behavior). */
    public static final String AUTO = "auto";

    /**
     * BCP-47 tags offered in the subtitles card. The first entry (AUTO) keeps
     * the original language; the rest are the UI locales this app ships, which
     * are also the languages a user is most likely to want subtitles in.
     */
    public static final List<String> TAGS = Arrays.asList(
            AUTO,
            "en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "pt-PT", "ru-RU", "fa-IR");

    private SubtitleTranslationTargets() {}

    /** Whether a stored tag is one of the supported values (auto included). */
    public static boolean isValid(String tag) {
        return tag != null && (tag.equals(AUTO) || TAGS.contains(tag));
    }

    /**
     * The ML Kit language code for a BCP-47 target tag, or {@code null} when
     * the tag is auto/unknown. Only codes the on-device translator actually
     * supports are returned.
     */
    public static String mlKitCode(String tag) {
        String primary = primaryTag(tag);
        if (primary == null) return null;
        switch (primary) {
            case "en": case "es": case "fr": case "de": case "it": case "pt": case "ru":
            case "fa":
                return primary;
            default:
                return null;
        }
    }

    /**
     * Primary subtag of a BCP-47 tag, lowercased; {@code null} for
     * auto/empty/blank input.
     */
    public static String primaryTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.isEmpty() || t.equalsIgnoreCase(AUTO)) return null;
        int dash = t.indexOf('-');
        return (dash < 0 ? t : t.substring(0, dash)).toLowerCase(Locale.ROOT);
    }
}
