package dev.notune.transcribe;

import android.util.Log;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * On-device text translation for live subtitles backed by Google ML Kit
 * Translation (fork addition).
 *
 * <p>ML Kit translates fully on-device once the per-pair language packs are
 * downloaded (the first use downloads them through Google Play Services;
 * afterwards no network involvement is needed for the actual translation). On
 * devices without Play Services — or when a pack download fails — the request
 * falls back to the source text, so subtitles never disappear: the safe-fallback
 * guarantee is the same one the ASR and the AI post-processor already provide.
 *
 * <p>Only the language subset the app actually offers is wired to ML Kit
 * constants ({@link #languageConstant(String)}); unknown codes short-circuit
 * to the source text. Translators are cached per (source, target) pair and
 * pack-download state is remembered per pair for the session.
 */
public final class OnDeviceSubtitleTranslator implements SubtitleTranslator {
    private static final String TAG = "OnDeviceSubtitleTranslator";

    private final Map<String, Translator> translators = new HashMap<>();
    private final Set<String> downloaded = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Translator probe;
    private boolean availabilityChecked = false;
    private boolean available = false;

    /** Maps an ML Kit language code to its {@link TranslateLanguage} constant. */
    private static String languageConstant(String code) {
        if (code == null) return null;
        switch (code) {
            case "en": return TranslateLanguage.ENGLISH;
            case "es": return TranslateLanguage.SPANISH;
            case "fr": return TranslateLanguage.FRENCH;
            case "de": return TranslateLanguage.GERMAN;
            case "it": return TranslateLanguage.ITALIAN;
            case "pt": return TranslateLanguage.PORTUGUESE;
            case "ru": return TranslateLanguage.RUSSIAN;
            case "fa": return TranslateLanguage.PERSIAN;
            case "zh": return TranslateLanguage.CHINESE;
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            default: return null;
        }
    }

    /**
     * Lazy availability probe. Creating a client on a device without ML Kit /
     * Google Play Services fails fast, so we remember the outcome for the
     * session and keep showing the original language instead of spamming
     * failures per segment.
     */
    private synchronized boolean isAvailable() {
        if (availabilityChecked) return available;
        availabilityChecked = true;
        try {
            TranslatorOptions probeOpts = new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.SPANISH)
                    .build();
            probe = Translation.getClient(probeOpts);
            available = true;
        } catch (Throwable t) {
            Log.w(TAG, "ML Kit translation unavailable on this device; "
                    + "subtitles stay in the original language", t);
        }
        return available;
    }

    @Override
    public void translate(String sourceText, String sourceLanguage, String targetLanguage,
                          Callback callback) {
        if (sourceText == null || sourceText.isEmpty()) {
            callback.onSuccess(sourceText);
            return;
        }
        if (sourceLanguage == null || targetLanguage == null
                || sourceLanguage.equals(targetLanguage)) {
            // Nothing translatable to do — same language is not an outage.
            callback.onSuccess(sourceText);
            return;
        }
        if (!isAvailable()) {
            callback.onUnavailable();
            callback.onSuccess(sourceText);
            return;
        }

        final String key = sourceLanguage + ":" + targetLanguage;
        final Translator translator = getOrCreateTranslator(key, sourceText, callback);
        if (translator == null) {
            return; // fallback already delivered by getOrCreateTranslator
        }

        final boolean packDownloaded;
        synchronized (this) {
            packDownloaded = downloaded.contains(key);
        }
        if (packDownloaded) {
            runTranslate(translator, key, sourceText, callback);
        } else {
            translator.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> {
                        synchronized (this) {
                            downloaded.add(key);
                            failed.remove(key);
                        }
                        runTranslate(translator, key, sourceText, callback);
                    })
                    .addOnFailureListener(e -> {
                        synchronized (this) {
                            failed.add(key);
                        }
                        Log.w(TAG, "Language pack download failed for " + key
                                + "; showing original", e);
                        callback.onUnavailable();
                        callback.onSuccess(sourceText);
                    });
        }
    }

    /** Returns the cached/created translator, or null after delivering the fallback. */
    private Translator getOrCreateTranslator(String key, String sourceText, Callback callback) {
        synchronized (this) {
            if (failed.contains(key)) {
                callback.onUnavailable();
                callback.onSuccess(sourceText);
                return null;
            }
            Translator existing = translators.get(key);
            if (existing != null) return existing;

            try {
                String srcConst = languageConstant(key.substring(0, key.indexOf(':')));
                String tgtConst = languageConstant(key.substring(key.indexOf(':') + 1));
                if (srcConst == null || tgtConst == null) {
                    callback.onUnavailable();
                    callback.onSuccess(sourceText);
                    return null;
                }
                TranslatorOptions options = new TranslatorOptions.Builder()
                        .setSourceLanguage(srcConst)
                        .setTargetLanguage(tgtConst)
                        .build();
                Translator created = Translation.getClient(options);
                translators.put(key, created);
                return created;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create translator for " + key + "; showing original", t);
                failed.add(key);
                callback.onUnavailable();
                callback.onSuccess(sourceText);
                return null;
            }
        }
    }

    private void runTranslate(Translator translator, String key, String sourceText,
                              Callback callback) {
        translator.translate(sourceText)
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Translation failed for " + key + "; showing original", e);
                    callback.onUnavailable();
                    callback.onSuccess(sourceText);
                });
    }

    @Override
    public void cancelAll() {
        // ML Kit Tasks are not cancellable; stale results are dropped by the
        // service's session generation before they reach any UI. We do close
        // every translator (the documented ML Kit cleanup) so a long-lived
        // process does not accumulate per-session native resources.
        synchronized (this) {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Throwable ignored) {
                }
                probe = null;
            }
            for (Translator t : translators.values()) {
                try {
                    t.close();
                } catch (Throwable ignored) {
                }
            }
            translators.clear();
            downloaded.clear();
            failed.clear();
        }
    }
}
