package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubtitleDisplayStabilizerTest {

    @Test
    public void withPartialAlphaScalesAlphaTo75Percent() {
        int base = 0xCC123456;
        int out = SubtitleDisplayStabilizer.withPartialAlpha(base);
        assertEquals(base & 0x00FFFFFF, out & 0x00FFFFFF);
        int origAlpha = (base >>> 24) & 0xFF;
        int expected = (origAlpha * 191) / 255;
        assertEquals(expected, (out >>> 24) & 0xFF);
    }

    @Test
    public void withPartialAlphaZeroAlphaStaysZero() {
        int base = 0x00ABCDEF;
        assertEquals(base, SubtitleDisplayStabilizer.withPartialAlpha(base));
    }

    @Test
    public void commonPrefixLengthBasic() {
        assertEquals(5, SubtitleDisplayStabilizer.commonPrefixLength("hello world", "hello there"));
        assertEquals(0, SubtitleDisplayStabilizer.commonPrefixLength("abc", "xyz"));
        assertEquals(3, SubtitleDisplayStabilizer.commonPrefixLength("abc", "abc"));
    }

    @Test
    public void longestCommonPrefixBasic() {
        assertEquals("hello ", SubtitleDisplayStabilizer.longestCommonPrefix("hello world", "hello there"));
    }

    @Test
    public void longestCommonWordPrefixBasic() {
        assertEquals("hola mundo", SubtitleDisplayStabilizer.longestCommonWordPrefix("hola mundo foo", "hola mundo bar"));
    }

    @Test
    public void longestCommonWordPrefixEmptyOnFirstWordMismatch() {
        assertEquals("", SubtitleDisplayStabilizer.longestCommonWordPrefix("foo bar", "baz qux"));
    }

    @Test
    public void isCjkDominant() {
        assertTrue(SubtitleDisplayStabilizer.isCjkDominant("今天我们学习中文"));
        assertFalse(SubtitleDisplayStabilizer.isCjkDominant("hello world"));
    }

    @Test
    public void stabilizeAnchorsPrefixOnDivergence() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.stabilize("hola mundo foo");
        s.stabilize("hola mundo bar");
        s.stabilize("hola mundo baz");
        // lock should be anchored now
        String out = s.stabilize("hola munda qux");
        assertTrue("anchored prefix preserved: " + out, out.startsWith("hola mundo"));
    }

    @Test
    public void stabilizeFirstHypothesisReturnedAsIs() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        assertEquals("hello", s.stabilize("hello"));
    }

    @Test
    public void resetUtteranceClearsLockedPrefix() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.stabilize("hola mundo foo");
        s.stabilize("hola mundo bar");
        s.stabilize("hola mundo baz");
        s.resetUtterance();
        assertEquals("", s.getLockedPrefix());
        assertEquals("xyz", s.stabilize("xyz"));
    }

    @Test
    public void resetAliasWorks() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.reset();
        assertEquals("", s.getLockedPrefix());
    }

    @Test
    public void monotonicSegmentIndexNeverMovesBackward() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.advanceFirstVisibleSegmentIndex(5);
        assertEquals(5, s.getFirstVisibleSegmentIndex());
        s.advanceFirstVisibleSegmentIndex(3);
        assertEquals(5, s.getFirstVisibleSegmentIndex());
        s.advanceFirstVisibleSegmentIndex(7);
        assertEquals(7, s.getFirstVisibleSegmentIndex());
    }

    @Test
    public void monotonicCharOffsetNeverMovesBackward() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.advanceFirstVisibleCharOffset(10);
        assertEquals(10, s.getFirstVisibleCharOffset());
        s.advanceFirstVisibleCharOffset(4);
        assertEquals(10, s.getFirstVisibleCharOffset());
    }

    @Test
    public void monotonicSegmentIdNeverMovesBackward() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.advanceFirstVisibleSegment(5L);
        assertEquals(5L, s.getFirstVisibleSegmentId());
        s.advanceFirstVisibleSegment(2L);
        assertEquals(5L, s.getFirstVisibleSegmentId());
    }

    @Test
    public void scrolledOffSegmentsTrack() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.markScrolledOff(3L);
        assertTrue(s.isScrolledOff(3L));
        assertFalse(s.isScrolledOff(4L));
    }

    @Test
    public void resetWindowClearsMonotonicState() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        s.advanceFirstVisibleSegmentIndex(5);
        s.markScrolledOff(2L);
        s.resetWindow();
        assertEquals(0, s.getFirstVisibleSegmentIndex());
        assertFalse(s.isScrolledOff(2L));
    }

    @Test
    public void negativeSingleSyllableChangeDoesNotCorrupt() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        // No crash, returns candidate
        assertEquals("a", s.stabilize("a"));
        assertEquals("b", s.stabilize("b"));
    }

    @Test
    public void stabilizePartialAlias() {
        SubtitleDisplayStabilizer s = new SubtitleDisplayStabilizer();
        assertEquals("x", s.stabilizePartial("x"));
    }
}
