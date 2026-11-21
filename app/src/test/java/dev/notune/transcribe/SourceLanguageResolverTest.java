package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SourceLanguageResolverTest {

    // --- Script detection ---------------------------------------------------

    @Test
    public void chineseIdeographsDetectZh() {
        assertEquals("zh", SourceLanguageResolver.detectFromText("今天我们学习中文。"));
    }

    @Test
    public void mixedCjkWithLatinStillDetectsZh() {
        assertEquals("zh", SourceLanguageResolver.detectFromText("Red Note 的评论区"));
    }

    @Test
    public void japaneseKanaDetectsJa() {
        assertEquals("ja", SourceLanguageResolver.detectFromText("今日は日本語を勉強します。"));
    }

    @Test
    public void koreanHangulDetectsKo() {
        assertEquals("ko", SourceLanguageResolver.detectFromText("오늘은 한국어를 공부합니다."));
    }

    @Test
    public void cyrillicDetectsRu() {
        assertEquals("ru", SourceLanguageResolver.detectFromText("Сегодня мы учим русский язык."));
    }

    // --- Latin heuristic ----------------------------------------------------

    @Test
    public void spanishTildeDetectsEs() {
        assertEquals("es", SourceLanguageResolver.detectFromText("mañana niño"));
    }

    @Test
    public void spanishInvertedMarksDetectEs() {
        assertEquals("es", SourceLanguageResolver.detectFromText("¿Qué tal estás?"));
    }

    @Test
    public void germanSharpSDDetectsDe() {
        assertEquals("de", SourceLanguageResolver.detectFromText("Straße Fußball"));
    }

    @Test
    public void germanUmlautsDetectDe() {
        assertEquals("de", SourceLanguageResolver.detectFromText("über morgen Mädchen grüße"));
    }

    @Test
    public void frenchAccentsDetectFr() {
        // ê is a distinctive French diacritic (é/è/à are shared with Italian
        // and Portuguese and deliberately never decide on their own).
        assertEquals("fr", SourceLanguageResolver.detectFromText("L'être est dans la forêt"));
    }

    @Test
    public void italianDistinctiveAccentsDetectIt() {
        assertEquals("it", SourceLanguageResolver.detectFromText("più o meno"));
    }

    @Test
    public void portugueseTildeDetectsPt() {
        assertEquals("pt", SourceLanguageResolver.detectFromText("não são pão coração"));
    }

    @Test
    public void uppercaseLatinDiacriticsDetectProperly() {
        assertEquals("de", SourceLanguageResolver.detectFromText("ÜBER ÄRGERT ÖFTER"));
        assertEquals("fr", SourceLanguageResolver.detectFromText("FORÊT FRANÇAISE"));
        assertEquals("it", SourceLanguageResolver.detectFromText("PIÙ O MENO CITTÀ CÒSÌ"));
        assertEquals("pt", SourceLanguageResolver.detectFromText("CORAÇÃO NÃO SÃO"));
        assertEquals("es", SourceLanguageResolver.detectFromText("MAÑANA NIÑO"));
    }

    @Test
    public void plainAsciiTextReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText("this is plain english text"));
    }

    @Test
    public void singleAmbiguousAccentReturnsNull() {
        // "café" — the é is shared by fr/it/pt; one occurrence must not decide.
        assertNull(SourceLanguageResolver.detectFromText("café"));
    }

    @Test
    public void emptyTextReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText(""));
        assertNull(SourceLanguageResolver.detectFromText(null));
    }

    @Test
    public void punctuationOnlyReturnsNull() {
        assertNull(SourceLanguageResolver.detectFromText("…"));
    }

    // --- model_language precedence ------------------------------------------

    @Test
    public void fixedModelLanguageOverridesDetection() {
        // Chinese speech with a fixed es-ES hint: the user says the audio is
        // Spanish, so the translator uses es regardless of the text's script.
        assertEquals("es", SourceLanguageResolver.resolve("今天我们学习中文。", "es-ES"));
    }

    @Test
    public void autoModelLanguageFallsBackToDetection() {
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", "auto"));
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", ""));
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", null));
    }

    @Test
    public void unsupportedModelLanguageFallsBackToDetection() {
        assertEquals("zh", SourceLanguageResolver.resolve("今天我们学习中文。", "xx-XX"));
    }

    @Test
    public void autoModelLanguageWithLatinTextReturnsNull() {
        assertNull(SourceLanguageResolver.resolve("this is plain english text", "auto"));
    }

    // --- primaryCode mapping ------------------------------------------------

    @Test
    public void primaryCodeMapsSupportedTags() {
        assertEquals("en", SourceLanguageResolver.primaryCode("en-US"));
        assertEquals("es", SourceLanguageResolver.primaryCode("es-ES"));
        assertEquals("zh", SourceLanguageResolver.primaryCode("zh-CN"));
        assertEquals("pt", SourceLanguageResolver.primaryCode("pt-PT"));
    }

    @Test
    public void primaryCodeNullForAutoOrBlank() {
        assertNull(SourceLanguageResolver.primaryCode("auto"));
        assertNull(SourceLanguageResolver.primaryCode(""));
        assertNull(SourceLanguageResolver.primaryCode(null));
        assertNull(SourceLanguageResolver.primaryCode("  "));
    }

    @Test
    public void primaryCodeNullForUnsupported() {
        assertNull(SourceLanguageResolver.primaryCode("xx-XX"));
    }

    // --- Extended resolve (subtitle source + session memory) ----------------

    @Test
    public void subtitleSourceTagBeatsModelLanguage() {
        assertEquals("es", SourceLanguageResolver.resolve("hello world", "es-ES", "en-US", null));
    }

    @Test
    public void modelLanguageUsedWhenSourceAuto() {
        assertEquals("en", SourceLanguageResolver.resolve("hello world", "auto", "en-US", null));
    }

    @Test
    public void sessionMemoryUsedForShortNeutralFragment() {
        assertEquals("fr", SourceLanguageResolver.resolve("ok", "auto", "auto", "fr"));
    }

    @Test
    public void sessionMemoryIgnoredWithoutLetters() {
        assertNull(SourceLanguageResolver.resolve("...", "auto", "auto", "fr"));
    }

    // --- detectFromTextWithLexicon positives --------------------------------

    @Test
    public void lexiconEnglishPhrase() {
        assertEquals("en", SourceLanguageResolver.detectFromTextWithLexicon(
                "Hello everyone, welcome back to the channel"));
    }

    @Test
    public void lexiconSpanishPhraseWithoutDiacritics() {
        assertEquals("es", SourceLanguageResolver.detectFromTextWithLexicon(
                "Hola a todos y bienvenidos a este video de hoy"));
    }

    @Test
    public void lexiconFrenchPhrase() {
        assertEquals("fr", SourceLanguageResolver.detectFromTextWithLexicon(
                "bonjour les amis dans cette ville"));
    }

    @Test
    public void lexiconGermanPhrase() {
        assertEquals("de", SourceLanguageResolver.detectFromTextWithLexicon(
                "hallo und willkommen in dieser welt heute"));
    }

    @Test
    public void lexiconItalianPhrase() {
        assertEquals("it", SourceLanguageResolver.detectFromTextWithLexicon(
                "ciao sono molto contento oggi"));
    }

    @Test
    public void lexiconPortuguesePhrase() {
        assertEquals("pt", SourceLanguageResolver.detectFromTextWithLexicon(
                "ola muito obrigado por isso hoje"));
    }

    // --- detectFromTextWithLexicon negatives (no false positives) -----------

    @Test
    public void lexiconSingleAmbiguousWordCafe() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("cafe"));
    }

    @Test
    public void lexiconSingleAmbiguousWordHotel() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("hotel"));
    }

    @Test
    public void lexiconSingleAmbiguousWordTest() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("test"));
    }

    @Test
    public void lexiconSingleAmbiguousWordOk() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("OK"));
    }

    @Test
    public void lexiconDigitsOnly() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("12345"));
    }

    @Test
    public void lexiconPunctuationOnly() {
        assertNull(SourceLanguageResolver.detectFromTextWithLexicon("..."));
    }

    @Test
    public void hasLetterOrDigitBasic() {
        org.junit.Assert.assertTrue(SourceLanguageResolver.hasLetterOrDigit("abc"));
        org.junit.Assert.assertTrue(SourceLanguageResolver.hasLetterOrDigit("123"));
        org.junit.Assert.assertTrue(SourceLanguageResolver.hasLetterOrDigit("123a"));
        org.junit.Assert.assertFalse(SourceLanguageResolver.hasLetterOrDigit("..."));
        org.junit.Assert.assertFalse(SourceLanguageResolver.hasLetterOrDigit(null));
    }
}
