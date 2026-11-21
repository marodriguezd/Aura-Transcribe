package dev.notune.transcribe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-JVM helper for the contextual subtitle-translation pipeline (M4).
 *
 * <p>Keeps a sliding window of the most recent finalized source sentence so
 * ML Kit can translate the current sentence with pronominal/temporal context
 * (e.g. {@code previousSource + "\\n" + currentSource}), extracts only the
 * translation that belongs to the current sentence, and memoizes recent
 * results in a bounded LRU cache so partials/finals that repeat within a few
 * milliseconds never hit ML Kit twice.
 *
 * <p>Zero Android dependencies: every method is deterministic and unit
 * testable on the JVM.
 */
public final class SubtitleContextWindow {

    /** Maximum characters retained from the previous finalized source. */
    public static final int MAX_PREVIOUS_SOURCE_CHARS = 60;

    /** Context expires after this many milliseconds of silence. */
    public static final long CONTEXT_EXPIRY_MS = 6_000L;

    /** Bounded LRU cache size. */
    public static final int CACHE_CAPACITY = 64;

    private String lastFinalSource = "";
    private String lastFinalTranslated = null;
    private String lastSourceLang = null;
    private long lastTimestampMs = 0L;

    private final LinkedHashMap<String, String> cache;

    public SubtitleContextWindow() {
        this.cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > CACHE_CAPACITY;
            }
        };
    }

    /** Records a finalized segment so the next sentence can use it as context. */
    public void recordFinal(String source, String translated, String sourceLang, long nowMs) {
        String s = source == null ? "" : source;
        if (s.length() > MAX_PREVIOUS_SOURCE_CHARS) {
            s = s.substring(0, MAX_PREVIOUS_SOURCE_CHARS);
        }
        lastFinalSource = s;
        lastFinalTranslated = translated;
        lastSourceLang = sourceLang;
        lastTimestampMs = nowMs;
    }

    /** Clears the contextual window (new session / topic reset). */
    public void reset() {
        lastFinalSource = "";
        lastFinalTranslated = null;
        lastSourceLang = null;
        lastTimestampMs = 0L;
    }

    /** Whether the stored previous sentence is still usable as context. */
    public boolean hasValidContext(String sourceLang, long nowMs) {
        if (lastFinalSource == null || lastFinalSource.isEmpty()) return false;
        if (lastSourceLang == null || !lastSourceLang.equals(sourceLang)) return false;
        if (nowMs - lastTimestampMs > CONTEXT_EXPIRY_MS) return false;
        return true;
    }

    /**
     * Returns the active context prefix when it is still valid for
     * {@code (sourceLang, nowMs)}, or {@code ""} otherwise.
     *
     * <p>This is the value every caller must use as the LRU cache key: the
     * cache key has to describe exactly the payload that was sent to ML Kit.
     * Using {@link #getLastFinalSource()} directly would key an expired (or
     * other-language) context that {@link #buildPayload} already omitted, so a
     * context-less translation could be found later and promoted to a final
     * segment as if it had been translated with context.
     */
    public String effectiveContextPrefix(String sourceLang, long nowMs) {
        return hasValidContext(sourceLang, nowMs) ? lastFinalSource : "";
    }

    /**
     * Builds the source payload sent to ML Kit for {@code currentSource}.
     * When a valid previous sentence exists, it is prepended with a newline
     * separator so the NMT model keeps pronominal/temporal context while still
     * returning both translations.
     */
    public String buildPayload(String currentSource, String sourceLang, long nowMs) {
        if (currentSource == null) currentSource = "";
        if (!hasValidContext(sourceLang, nowMs) || lastFinalSource.isEmpty()) {
            return currentSource;
        }
        return lastFinalSource + "\n" + currentSource;
    }

    /**
     * Extracts the translation that belongs to {@code currentSource} from ML
     * Kit's raw output. ML Kit normally echoes the newline separator; when it
     * collapses it, fall back to stripping the previously translated sentence.
     */
    public String extractTranslated(String rawTranslated) {
        if (rawTranslated == null) return "";
        int idx = rawTranslated.lastIndexOf('\n');
        if (idx >= 0) {
            return rawTranslated.substring(idx + 1).trim();
        }
        if (lastFinalTranslated != null && !lastFinalTranslated.isEmpty()
                && rawTranslated.startsWith(lastFinalTranslated)) {
            String rest = rawTranslated.substring(lastFinalTranslated.length());
            int i = 0;
            while (i < rest.length()) {
                char c = rest.charAt(i);
                if (!Character.isWhitespace(c) && !isPunctuation(c)) break;
                i++;
            }
            return rest.substring(i).trim();
        }
        return rawTranslated.trim();
    }

    private static boolean isPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'
                || c == '。' || c == '，' || c == '、' || c == '！' || c == '？'
                || c == '：' || c == '；' || c == '…';
    }

    /** Cache key for a (sourceLang, targetLang, context, sourceText) tuple. */
    public static String cacheKey(String sourceLang, String targetLang,
                                 String contextPrefix, String sourceText) {
        String s = sourceLang == null ? "" : sourceLang;
        String t = targetLang == null ? "" : targetLang;
        String c = contextPrefix == null ? "" : contextPrefix;
        String x = sourceText == null ? "" : sourceText;
        return s + ":" + t + "|" + c + "|" + x;
    }

    /** Returns the cached translation, or null on a miss. */
    public String getCached(String sourceLang, String targetLang,
                            String contextPrefix, String sourceText) {
        return cache.get(cacheKey(sourceLang, targetLang, contextPrefix, sourceText));
    }

    /** Stores a translation in the LRU cache. */
    public void putCached(String sourceLang, String targetLang,
                          String contextPrefix, String sourceText, String translated) {
        cache.put(cacheKey(sourceLang, targetLang, contextPrefix, sourceText), translated);
    }

    /** Clears the LRU cache (e.g. on session reset). */
    public void clearCache() {
        cache.clear();
    }

    // --- accessors for tests / callers -------------------------------------

    public String getLastFinalSource() { return lastFinalSource; }
    public String getLastFinalTranslated() { return lastFinalTranslated; }
    public String getLastSourceLang() { return lastSourceLang; }
    public long getLastTimestampMs() { return lastTimestampMs; }
    public int cacheSize() { return cache.size(); }
}