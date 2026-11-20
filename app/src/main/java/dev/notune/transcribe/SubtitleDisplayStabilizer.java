package dev.notune.transcribe;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure-JVM helper for the visual stabilization of live (translated) subtitles
 * (Milestone M5).
 *
 * <p>Three responsibilities, all deterministic and unit-testable without
 * Android:
 *
 * <ol>
 *   <li><b>Local Agreement-2 prefix stabilization</b> for successive partial
 *       translations of the same clause: the longest common prefix between two
 *       consecutive hypotheses is anchored so that a later, slightly different
 *       hypothesis can never rewrite words that were already shown at the
 *       start of the clause.</li>
 *   <li><b>Monotonic scroll window</b>: once a segment/character has scrolled
 *       off the top of the overlay it can never scroll back into view, even if
 *       a later partial becomes shorter.</li>
 *   <li><b>Opacity helper</b>: {@link #withPartialAlpha(int)} derives a 75 %
 *       alpha variant of a theme color without any hex literal.</li>
 * </ol>
 */
public final class SubtitleDisplayStabilizer {

    // ---------------------------------------------------------------------
    // Local Agreement-2 state
    // ---------------------------------------------------------------------
    private String previousPartialHypothesis = "";
    private String lockedPrefix = "";
    private String lastAgreedPrefix = "";
    private int agreementCount = 0;

    // ---------------------------------------------------------------------
    // Monotonic scroll-window state
    // ---------------------------------------------------------------------
    private long firstVisibleSegmentId = 0L;
    private int firstVisibleCharOffset = 0;
    private long maxSegmentId = 0L;
    private final Set<Long> scrolledOffSegments = new LinkedHashSet<>();

    public SubtitleDisplayStabilizer() {
    }

    // =====================================================================
    // Local Agreement-2
    // =====================================================================

    /**
     * Feeds a new partial-translation hypothesis for the active clause and
     * returns the stabilized text that should be rendered.
     *
     * <p>The returned string always keeps the anchored prefix (if any) and
     * appends the tail of {@code candidate} after it, so a fluctuation in the
     * middle of a clause can never rewrite already-stabilized leading words.
     */
    public String stabilize(String candidate) {
        if (candidate == null) candidate = "";
        if (previousPartialHypothesis.isEmpty()) {
            previousPartialHypothesis = candidate;
            return candidate;
        }

        String lcp = isCjkDominant(previousPartialHypothesis) && isCjkDominant(candidate)
                ? longestCommonPrefix(previousPartialHypothesis, candidate)
                : longestCommonWordPrefix(previousPartialHypothesis, candidate);

        // Update the locked prefix only when two consecutive hypotheses agree
        // on a strictly longer prefix.
        if (!lcp.isEmpty() && lcp.length() > lockedPrefix.length()) {
            if (lcp.equals(lastAgreedPrefix)) {
                agreementCount++;
            } else {
                lastAgreedPrefix = lcp;
                agreementCount = 1;
            }
            if (agreementCount >= 2) {
                lockedPrefix = lcp;
                agreementCount = 0;
            }
        } else if (lcp.equals(lockedPrefix) && !lockedPrefix.isEmpty()) {
            // keep the agreement streak alive on an exact match
            lastAgreedPrefix = lcp;
        }

        String result;
        if (!lockedPrefix.isEmpty()) {
            // Preserve the anchored prefix when the candidate still mostly
            // agrees with it (>= 60 % of its length).
            int common = commonPrefixLength(candidate, lockedPrefix);
            if (candidate.startsWith(lockedPrefix)
                    || common * 5 >= lockedPrefix.length() * 3) {
                if (candidate.length() >= lockedPrefix.length()) {
                    result = lockedPrefix + candidate.substring(lockedPrefix.length());
                } else {
                    result = lockedPrefix;
                }
            } else {
                result = candidate;
            }
        } else {
            result = candidate;
        }

        previousPartialHypothesis = candidate;
        return result;
    }

    /** Alias used by some callers. */
    public String stabilizePartial(String candidate) {
        return stabilize(candidate);
    }

    /** Alias used by some callers. */
    public String update(String candidate) {
        return stabilize(candidate);
    }

    /** Alias used by some callers. */
    public String process(String candidate) {
        return stabilize(candidate);
    }

    /** Alias used by some callers. */
    public String apply(String candidate) {
        return stabilize(candidate);
    }

    /** Clears the per-utterance stabilization state (call on segment finalize). */
    public void resetUtterance() {
        previousPartialHypothesis = "";
        lockedPrefix = "";
        lastAgreedPrefix = "";
        agreementCount = 0;
    }

    /** Alias. */
    public void reset() {
        resetUtterance();
    }

    // --- accessors ----------------------------------------------------------

    public String getLockedPrefix() {
        return lockedPrefix;
    }

    public String getPreviousPartialHypothesis() {
        return previousPartialHypothesis;
    }

    public int getAgreementCount() {
        return agreementCount;
    }

    // =====================================================================
    // Static helpers
    // =====================================================================

    public static int commonPrefixLength(String a, String b) {
        if (a == null || b == null) return 0;
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    public static String longestCommonPrefix(String a, String b) {
        if (a == null || b == null) return "";
        return a.substring(0, commonPrefixLength(a, b));
    }

    /**
     * Longest common prefix measured in whole whitespace-delimited words.
     * Returns the matched words joined by single spaces (no trailing space).
     */
    public static String longestCommonWordPrefix(String a, String b) {
        if (a == null || b == null) return "";
        String[] wa = a.trim().split("\\s+");
        String[] wb = b.trim().split("\\s+");
        if (a.trim().isEmpty() || b.trim().isEmpty()) return "";
        int n = Math.min(wa.length, wb.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (wa[i].equals(wb[i])) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(wa[i]);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /** True when the string is dominated by CJK code points. */
    public static boolean isCjkDominant(String s) {
        if (s == null || s.isEmpty()) return false;
        int cjk = 0, total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)
                    || (c >= 0x3400 && c <= 0x4DBF)) {
                cjk++;
            }
        }
        return total > 0 && cjk * 2 >= total;
    }

    private static boolean isCjk(String s) {
        return isCjkDominant(s);
    }

    /**
     * Derives a ~75 % alpha variant of a theme color. Keeps the RGB channels
     * untouched and scales the original alpha by 191/255.
     */
    public static int withPartialAlpha(int baseTextColor) {
        int origAlpha = (baseTextColor >>> 24) & 0xFF;
        int partialAlpha = (origAlpha * 191) / 255;
        return (baseTextColor & 0x00FFFFFF) | (partialAlpha << 24);
    }

    // =====================================================================
    // Monotonic scroll window
    // =====================================================================

    /** Registers a newly finalized segment id (monotonic). */
    public void registerSegment(long segmentId) {
        if (segmentId > maxSegmentId) maxSegmentId = segmentId;
    }

    public long nextSegmentId() {
        maxSegmentId++;
        return maxSegmentId;
    }

    /**
     * Advances the first-visible segment boundary. The boundary only ever
     * moves forward: a smaller candidate is ignored.
     */
    public void advanceFirstVisibleSegmentIndex(int candidate) {
        if (candidate > firstVisibleSegmentId) {
            firstVisibleSegmentId = candidate;
        }
    }

    public void advanceFirstVisibleSegment(long candidate) {
        if (candidate > firstVisibleSegmentId) {
            firstVisibleSegmentId = candidate;
        }
    }

    public void advanceFirstVisibleCharOffset(int candidate) {
        if (candidate > firstVisibleCharOffset) {
            firstVisibleCharOffset = candidate;
        }
    }

    public long getFirstVisibleSegmentId() {
        return firstVisibleSegmentId;
    }

    public int getFirstVisibleSegmentIndex() {
        return (int) firstVisibleSegmentId;
    }

    public int getFirstVisibleCharOffset() {
        return firstVisibleCharOffset;
    }

    public void markScrolledOff(long segmentId) {
        scrolledOffSegments.add(segmentId);
    }

    public boolean isScrolledOff(long segmentId) {
        return scrolledOffSegments.contains(segmentId);
    }

    public Set<Long> getScrolledOffSegments() {
        return scrolledOffSegments;
    }

    /** Resets the monotonic window (new session). */
    public void resetWindow() {
        firstVisibleSegmentId = 0L;
        firstVisibleCharOffset = 0;
        maxSegmentId = 0L;
        scrolledOffSegments.clear();
    }
}
