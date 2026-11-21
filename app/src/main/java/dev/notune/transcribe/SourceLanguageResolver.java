package dev.notune.transcribe;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves the <em>source</em> language of a finalized subtitle segment so the
 * on-device translator knows what to translate from. The live-subtitle path
 * only delivers text (not the detected-language tag the ASR model produces
 * internally), and ML Kit needs an explicit source language, so we resolve it
 * here.
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>A fixed language chosen in the Speech models screen
 *       ({@code model_language} marker holding a concrete tag). The user knows
 *       what they are watching, so this always wins.</li>
 *   <li>Script detection of the segment text itself: kana → Japanese (kana
 *       beats CJK because Japanese text is mostly CJK + kana), hangul → Korean,
 *       CJK ideographs → Chinese, Cyrillic → Russian, plus a conservative
 *       Latin diacritic heuristic (German/French/Italian/Portuguese; Spanish is
 *       detected through its unambiguous markers ñ/¿/¡).</li>
 *   <li>{@code null} → no translation for that segment (the original text is
 *       shown, which is the safe fallback).</li>
 * </ol>
 *
 * <p>Only <em>distinctive</em> diacritics decide Latin results: accents shared
 * by several languages (é, à, ü, á, …) are deliberately not scored, because a
 * single ambiguous accent must never pick a language and produce a wrong
 * translation.</p>
 *
 * <p>Returns only ML Kit language codes the translator supports.</p>
 */
public final class SourceLanguageResolver {
    private SourceLanguageResolver() {}

    /** Resolves the ML Kit source code for a segment. */
    public static String resolve(String text, String modelLanguageTag) {
        String fromModel = primaryCode(modelLanguageTag);
        if (fromModel != null) return fromModel;
        return detectFromText(text);
    }

    /**
     * Extended resolution used by {@code LiveSubtitleService}: an explicit
     * subtitle source language wins, then the model language, then text
     * detection (script + diacritics + Latin stopword lexicon), and finally
     * the session memory code when the fragment is too short/neural to decide.
     */
    public static String resolve(String text, String subtitleSourceTag,
                                 String modelLanguageTag, String sessionMemoryCode) {
        String fromSubtitle = primaryCode(subtitleSourceTag);
        if (fromSubtitle != null) return fromSubtitle;
        String fromModel = primaryCode(modelLanguageTag);
        if (fromModel != null) return fromModel;
        String detected = detectFromTextWithLexicon(text);
        if (detected != null) return detected;
        if (sessionMemoryCode != null && hasLetterOrDigit(text)) return sessionMemoryCode;
        return null;
    }

    /**
     * Like {@link #detectFromText(String)} but, when script/diacritic detection
     * returns {@code null}, falls back to an O(1) Latin stopword lexicon.
     */
    public static String detectFromTextWithLexicon(String text) {
        String direct = detectFromText(text);
        if (direct != null) return direct;
        return detectLatinByStopwords(text);
    }

    /** Whether the text contains at least one letter or digit. */
    public static boolean hasLetterOrDigit(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) return true;
        }
        return false;
    }

    /**
     * Maps a BCP-47 {@code model_language} tag to an ML Kit code; {@code null}
     * for auto/blank/unsupported tags (falls through to script detection).
     */
    public static String primaryCode(String tag) {
        String primary = SubtitleTranslationTargets.primaryTag(tag);
        if (primary == null) return null;
        switch (primary) {
            case "en": case "es": case "fr": case "de": case "it": case "pt":
            case "ru": case "zh": case "ja": case "ko":
                return primary;
            default:
                return null;
        }
    }

    /**
     * Detects the language of a segment from its text alone. Non-Latin
     * scripts are authoritative; the Latin heuristic only fires on strong,
     * unambiguous evidence so a wrong-language translation never appears for
     * plain ASCII text.
     */
    public static String detectFromText(String text) {
        if (text == null || text.isEmpty()) return null;

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean hasKana = false, hasHangul = false, hasCjk = false, hasCyrillic = false;
        boolean sawLatinLetter = false;
        int de = 0, fr = 0, it = 0, pt = 0;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);

            if (isKana(cp)) { hasKana = true; continue; }
            if (isHangul(cp)) { hasHangul = true; continue; }
            if (isCjkIdeograph(cp)) { hasCjk = true; continue; }
            if (isCyrillic(cp)) { hasCyrillic = true; continue; }

            if (!sawLatinLetter && (isAsciiLetter(cp) || isLatinSupplementLetter(cp))) {
                sawLatinLetter = true;
            }
            // Strong markers: unambiguous on their own.
            if (cp == 'ñ' || cp == '¿' || cp == '¡') return "es";
            if (cp == 'ß') return "de";
            if (cp == 'œ') return "fr";

            // Distinctive diacritics only (é/à/ü/á are shared by several
            // languages and would cause wrong guesses).
            if (cp == 'ä' || cp == 'ö') de++;
            if (isFren(cp)) fr++;
            if (isItal(cp)) it++;
            if (isPort(cp)) pt++;
        }

        // Non-Latin scripts are authoritative. Kana beats CJK (Japanese text
        // is mostly CJK + kana); hangul beats CJK (Korean may embed hanja).
        if (hasKana) return "ja";
        if (hasHangul) return "ko";
        if (hasCjk) return "zh";
        if (hasCyrillic) return "ru";

        if (!sawLatinLetter) return null;

        int best = Math.max(Math.max(de, fr), Math.max(it, pt));
        if (best < 1) return null;
        int winners = 0;
        String winLang = null;
        if (de == best) { winners++; winLang = "de"; }
        if (fr == best) { winners++; winLang = "fr"; }
        if (it == best) { winners++; winLang = "it"; }
        if (pt == best) { winners++; winLang = "pt"; }
        return winners == 1 ? winLang : null;
    }

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    private static boolean isLatinSupplementLetter(int cp) {
        return cp >= 0x00C0 && cp <= 0x024F;
    }

    private static boolean isKana(int cp) {
        return (cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF);
    }

    private static boolean isHangul(int cp) {
        return cp >= 0xAC00 && cp <= 0xD7AF;
    }

    private static boolean isCjkIdeograph(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static boolean isCyrillic(int cp) {
        return cp >= 0x0400 && cp <= 0x04FF;
    }

    /** Distinctive French diacritics (é/è/à are shared with other languages). */
    private static boolean isFren(int cp) {
        return cp == 'â' || cp == 'ê' || cp == 'ë' || cp == 'î' || cp == 'ï'
                || cp == 'ô' || cp == 'û' || cp == 'ç';
    }

    /** Distinctive Italian diacritics (à/è/é are shared with French/Portuguese). */
    private static boolean isItal(int cp) {
        return cp == 'ì' || cp == 'ò' || cp == 'ù';
    }

    /** Distinctive Portuguese diacritics (á/â/ç/ê/ô are shared with others). */
    private static boolean isPort(int cp) {
        return cp == 'ã' || cp == 'õ';
    }

    // Stopword lexicon (O(1) Latin language detection). High-signal function
    // words per language; see SUBTITLES_SPEC M3.2.
    private static final Set<String> STOP_EN = new HashSet<>(Arrays.asList(
            "the","and","that","with","have","this","from","they","would","about",
            "there","their","what","which","when","your","will","can","just","because",
            "into","people","think","know","going","really","very","here","some","than",
            "welcome","back","hello","everyone","today"));
    private static final Set<String> STOP_ES = new HashSet<>(Arrays.asList(
            "los","las","del","una","para","con","por","pero","como","esta","este","esto",
            "son","todo","todos","fue","muy","sin","sobre","entre","cuando","desde",
            "porque","nosotros","tienen","hace","donde","hola","bienvenidos","hoy"));
    private static final Set<String> STOP_FR = new HashSet<>(Arrays.asList(
            "les","des","dans","pour","avec","sur","pas","plus","cette","sont","mais",
            "comme","tout","tous","aussi","fait","bien","sans","entre","quand","depuis",
            "parce","nous","vous","ils","elle","leurs","bonjour","bienvenue"));
    private static final Set<String> STOP_DE = new HashSet<>(Arrays.asList(
            "der","die","das","und","ist","nicht","mit","auf","sich","dem","den","ein",
            "eine","als","auch","aber","oder","wie","wir","sind","war","wird","noch",
            "nach","durch","wenn","nur","hallo","willkommen","heute"));
    private static final Set<String> STOP_IT = new HashSet<>(Arrays.asList(
            "che","non","per","sono","della","delle","degli","anche","questo","questa",
            "essere","molto","hanno","fatto","tutto","tutti","dalla","nella","quando",
            "siamo","dopo","prima","sempre","ciao","benvenuti","oggi"));
    private static final Set<String> STOP_PT = new HashSet<>(Arrays.asList(
            "uma","com","mais","mas","foi","seus","suas","pela","pelo","nos","das","dos",
            "isso","muito","quando","sobre","depois","ainda","mesmo","ola","bem","hoje"));

    private static final Set<String> ULTRA_EXCLUSIVE = new HashSet<>(Arrays.asList(
            "the","and","that","with","this","from","they","welcome","hello","everyone",
            "los","las","del","pero","nosotros","bienvenidos",
            "les","dans","avec","cette","bonjour",
            "der","und","nicht","wir",
            "della","degli","questo","questa","sono",
            "muito","isso","seus","pela"));

    public static String detectLatinByStopwords(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] tokens = text.toLowerCase(java.util.Locale.ROOT)
                .split("[^a-z\\u00C0-\\u024F]+");
        Set<String>[] sets = new Set[] { STOP_EN, STOP_ES, STOP_FR, STOP_DE, STOP_IT, STOP_PT };
        String[] langs = { "en", "es", "fr", "de", "it", "pt" };
        int[] scores = new int[6];
        boolean[] ultraSeen = new boolean[6];
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            for (int i = 0; i < 6; i++) {
                if (sets[i].contains(tok)) {
                    scores[i]++;
                    if (ULTRA_EXCLUSIVE.contains(tok)) ultraSeen[i] = true;
                }
            }
        }
        int best = 0;
        for (int s : scores) if (s > best) best = s;
        if (best < 1) return null;
        int winners = 0;
        int winIdx = -1;
        for (int i = 0; i < 6; i++) {
            if (scores[i] == best) { winners++; winIdx = i; }
        }
        boolean thresholdOk;
        if (best >= 2) {
            thresholdOk = true;
        } else { // best == 1
            thresholdOk = ultraSeen[winIdx];
        }
        if (!thresholdOk) return null;
        if (winners != 1) return null;
        return langs[winIdx];
    }
}
