package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SubtitleTranslationPipelineTest {

    @Test
    public void contextWindowFormatsPreviousPlusCurrent() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.recordFinal("hola mundo", "hello world", "es", 1000L);
        assertEquals("hola mundo\ntoday we learn",
                w.buildPayload("today we learn", "es", 2000L));
    }

    @Test
    public void contextWindowExpiresAfterSixSeconds() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.recordFinal("hola mundo", "hello world", "es", 1000L);
        assertEquals("today we learn",
                w.buildPayload("today we learn", "es", 8000L));
    }

    @Test
    public void contextWindowSourceLangMismatchDisablesContext() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.recordFinal("hola mundo", "hello world", "es", 1000L);
        assertEquals("today we learn",
                w.buildPayload("today we learn", "en", 2000L));
    }

    @Test
    public void extractTranslatedTakesSecondLine() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.recordFinal("prev", "prevTr", "es", 1000L);
        assertEquals("currentTr", w.extractTranslated("prevTr\ncurrentTr"));
    }

    @Test
    public void extractTranslatedFallbackWhenNoNewline() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.recordFinal("prev", "prevTr", "es", 1000L);
        // translator collapsed the newline: strip the previously translated prefix
        assertEquals("currentTr", w.extractTranslated("prevTr currentTr"));
    }

    @Test
    public void extractTranslatedReturnsRawWhenNoContext() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        assertEquals("hello", w.extractTranslated("hello"));
    }

    @Test
    public void lruCacheRoundTrip() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        w.putCached("zh", "es", "ctx", "src", "dst");
        assertEquals("dst", w.getCached("zh", "es", "ctx", "src"));
        assertNull(w.getCached("zh", "es", "ctx", "other"));
    }

    @Test
    public void lruCacheEvictsBeyondCapacity() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        for (int i = 0; i < 70; i++) {
            w.putCached("zh", "es", "ctx", "src" + i, "dst" + i);
        }
        assertTrue(w.cacheSize() <= SubtitleContextWindow.CACHE_CAPACITY);
    }

    @Test
    public void previousSourceTruncatedTo60Chars() {
        SubtitleContextWindow w = new SubtitleContextWindow();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('a');
        w.recordFinal(sb.toString(), "tr", "es", 1000L);
        assertEquals(60, w.getLastFinalSource().length());
    }

    @Test
    public void maxPreviousSourceConstant() {
        assertEquals(60, SubtitleContextWindow.MAX_PREVIOUS_SOURCE_CHARS);
    }
}