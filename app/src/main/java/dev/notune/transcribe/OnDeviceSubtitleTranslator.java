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
    private int generation = 0;
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
            Translator probeClient = Translation.getClient(probeOpts);
            try {
                probeClient.close();
            } catch (Throwable ignored) {
            }
            available = true;
        } catch (Throwable t) {
            Log.w(TAG, "ML Kit translation unavailable on this device; "
                    + "subtitles stay in the original language", t);
        }
        return available;
    }

    private synchronized boolean isCurrentGeneration(int reqGen) {
        return reqGen == generation;
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
            callback.onFailure(new IllegalStateException("ML Kit translation unavailable"));
            return;
        }

        final int reqGen;
        synchronized (this) {
            reqGen = generation;
        }
        final String key = sourceLanguage + ":" + targetLanguage;
        final Translator translator = getOrCreateTranslator(key, callback);
        if (translator == null) {
            return; // error/unavailable already delivered by getOrCreateTranslator
        }

        final boolean packDownloaded;
        synchronized (this) {
            packDownloaded = downloaded.contains(key);
        }
        if (packDownloaded) {
            runTranslate(translator, key, sourceText, reqGen, callback);
        } else {
            translator.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> {
                        synchronized (this) {
                            if (reqGen != generation) return;
                            downloaded.add(key);
                            failed.remove(key);
                        }
                        runTranslate(translator, key, sourceText, reqGen, callback);
                    })
                    .addOnFailureListener(e -> {
                        synchronized (this) {
                            if (reqGen != generation) return;
                            failed.add(key);
                        }
                        Log.w(TAG, "Language pack download failed for " + key
                                + "; showing original", e);
                        callback.onUnavailable();
                        callback.onFailure(e);
                    });
        }
    }

    /** Returns the cached/created translator, or null after notifying {@code callback}. */
    private Translator getOrCreateTranslator(String key, Callback callback) {
        synchronized (this) {
            if (failed.contains(key)) {
                if (callback != null) {
                    callback.onUnavailable();
                    callback.onFailure(new IllegalStateException("Translator failed for " + key));
                }
                return null;
            }
            Translator existing = translators.get(key);
            if (existing != null) return existing;

            try {
                String srcConst = languageConstant(key.substring(0, key.indexOf(':')));
                String tgtConst = languageConstant(key.substring(key.indexOf(':') + 1));
                if (srcConst == null || tgtConst == null) {
                    if (callback != null) {
                        callback.onUnavailable();
                        callback.onFailure(
                                new IllegalArgumentException("Unsupported language pair: " + key));
                    }
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
                if (callback != null) {
                    callback.onUnavailable();
                    callback.onFailure(t);
                }
                return null;
            }
        }
    }

    private void runTranslate(Translator translator, String key, String sourceText, int reqGen,
                              Callback callback) {
        translator.translate(sourceText)
                .addOnSuccessListener(translated -> {
                    if (!isCurrentGeneration(reqGen)) return;
                    callback.onSuccess(translated);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrentGeneration(reqGen)) return;
                    Log.w(TAG, "Translation failed for " + key + "; showing original", e);
                    callback.onUnavailable();
                    callback.onFailure(e);
                });
    }

    /**
     * Pre-warms the language pack(s) for a (source, target) pair so the first
     * real translation does not pay the download latency. Invokes
     * {@code downloadModelIfNeeded()} ahead of time; reports success/failure
     * through {@code cb} (which may be null). A null/unknown language code or
     * an unavailable device short-circuits to {@code cb.onUnavailable()}.
     */
    public void prewarm(String sourceLanguage, String targetLanguage,
                        PrewarmCallback cb) {
        if (sourceLanguage == null || targetLanguage == null
                || sourceLanguage.equals(targetLanguage)) {
            if (cb != null) cb.onReady();
            return;
        }
        if (!isAvailable()) {
            if (cb != null) cb.onUnavailable();
            return;
        }
        final int reqGen;
        synchronized (this) {
            reqGen = generation;
        }
        final String key = sourceLanguage + ":" + targetLanguage;
        final Translator translator = getOrCreateTranslator(key, null);
        if (translator == null) {
            if (cb != null) cb.onUnavailable();
            return;
        }
        synchronized (this) {
            if (downloaded.contains(key)) {
                if (cb != null) cb.onReady();
                return;
            }
        }
        translator.downloadModelIfNeeded()
                .addOnSuccessListener(v -> {
                    synchronized (this) {
                        if (reqGen != generation) return;
                        downloaded.add(key);
                        failed.remove(key);
                    }
                    if (cb != null) cb.onReady();
                })
                .addOnFailureListener(e -> {
                    synchronized (this) {
                        if (reqGen != generation) return;
                        failed.add(key);
                    }
                    if (cb != null) cb.onUnavailable();
                });
    }

    /** Callback for {@link #prewarm(String, String, PrewarmCallback)}. */
    public interface PrewarmCallback {
        void onReady();
        default void onUnavailable() { }
    }

    @Override
    public void cancelAll() {
        // ML Kit Tasks are not cancellable; stale results are rejected by the
        // generation check before reaching callers. Close every translator
        // (the documented ML Kit cleanup) so a long-lived process does not
        // accumulate native resources.
        synchronized (this) {
            generation++;
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
