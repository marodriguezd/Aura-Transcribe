package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SubtitleTranslationTargetsTest {

    @Test
    public void autoIsValidAndMeansNoTranslation() {
        assertTrue(SubtitleTranslationTargets.isValid(SubtitleTranslationTargets.AUTO));
        assertNull(SubtitleTranslationTargets.mlKitCode(SubtitleTranslationTargets.AUTO));
    }

    @Test
    public void offeredTagsAreAllValid() {
        for (String tag : SubtitleTranslationTargets.TAGS) {
            assertTrue(tag, SubtitleTranslationTargets.isValid(tag));
        }
    }

    @Test
    public void invalidTagsRejected() {
        assertFalse(SubtitleTranslationTargets.isValid(null));
        assertFalse(SubtitleTranslationTargets.isValid(""));
        assertFalse(SubtitleTranslationTargets.isValid("xx-XX"));
        assertFalse(SubtitleTranslationTargets.isValid("zh-CN")); // not offered as a target
    }

    @Test
    public void mlKitCodeMapsOfferedTargets() {
        assertEquals("en", SubtitleTranslationTargets.mlKitCode("en-US"));
        assertEquals("es", SubtitleTranslationTargets.mlKitCode("es-ES"));
        assertEquals("fr", SubtitleTranslationTargets.mlKitCode("fr-FR"));
        assertEquals("de", SubtitleTranslationTargets.mlKitCode("de-DE"));
        assertEquals("it", SubtitleTranslationTargets.mlKitCode("it-IT"));
        assertEquals("pt", SubtitleTranslationTargets.mlKitCode("pt-PT"));
        assertEquals("ru", SubtitleTranslationTargets.mlKitCode("ru-RU"));
        assertEquals("fa", SubtitleTranslationTargets.mlKitCode("fa-IR"));
    }

    @Test
    public void mlKitCodeNullForUnsupported() {
        assertNull(SubtitleTranslationTargets.mlKitCode("xx-XX"));
        assertNull(SubtitleTranslationTargets.mlKitCode(null));
    }

    @Test
    public void primaryTagExtractsPrimarySubtag() {
        assertEquals("en", SubtitleTranslationTargets.primaryTag("en-US"));
        assertEquals("zh", SubtitleTranslationTargets.primaryTag("zh-CN"));
        assertEquals("es", SubtitleTranslationTargets.primaryTag("es"));
    }

    @Test
    public void primaryTagNullForAutoOrBlank() {
        assertNull(SubtitleTranslationTargets.primaryTag("auto"));
        assertNull(SubtitleTranslationTargets.primaryTag("Auto"));
        assertNull(SubtitleTranslationTargets.primaryTag(""));
        assertNull(SubtitleTranslationTargets.primaryTag(null));
    }
}
