package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the dictation insertion contract.
 *
 * <p>These cover the bug fixed in the Android 17 pass: the accessibility
 * insertion path used to build {@code existing + " " + text}, ignoring the
 * cursor and the selection entirely. Dictating into the middle of a sentence
 * appended to the end, and dictating over a selection produced text in the wrong
 * order. The fix inserts at the real selection; these tests pin that behaviour,
 * including the degenerate cases the old code got wrong.
 */
public class AccessibilityInsertionTest {

    // --- insertAtSelection --------------------------------------------------

    @Test
    public void insertsAtCursorInMiddleOfSentence() {
        // "Hello world" with the caret after "Hello "
        assertEquals("Hello dictated world",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "Hello world", 6, 6, "dictated "));
    }

    @Test
    public void replacesSelectionInsteadOfKeepingIt() {
        // "Hello brave world", "brave" selected -> replaced by the dictation.
        assertEquals("Hello bold world",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "Hello brave world", 6, 11, "bold"));
    }

    @Test
    public void appendsWhenCaretIsAtEnd() {
        assertEquals("Hello world again",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "Hello world", 11, 11, " again"));
    }

    @Test
    public void insertsIntoEmptyField() {
        assertEquals("first words",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "", 0, 0, "first words"));
    }

    @Test
    public void unknownSelectionAppendsRatherThanCorrupting() {
        // Some fields report -1 for an unknown selection; appending is the safe
        // degradation, never a middle-of-string splice at a bogus offset.
        assertEquals("existing dictation",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "existing", -1, -1, " dictation"));
    }

    @Test
    public void outOfRangeSelectionAppends() {
        assertEquals("abc xy",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "abc", 99, 120, " xy"));
    }

    @Test
    public void invertedSelectionIsTreatedAsUnknown() {
        // start > end must not throw (substring would).
        assertEquals("abc z",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "abc", 3, 1, " z"));
    }

    @Test
    public void nullInputsAreTolerated() {
        assertEquals("text",
                FloatingDictationAccessibilityService.insertAtSelection(null, 0, 0, "text"));
        assertEquals("abc",
                FloatingDictationAccessibilityService.insertAtSelection("abc", 0, 0, null));
        assertEquals("",
                FloatingDictationAccessibilityService.insertAtSelection(null, -1, -1, null));
    }

    @Test
    public void multilineTextIsPreserved() {
        assertEquals("line1\nline2\nline3",
                FloatingDictationAccessibilityService.insertAtSelection(
                        "line1\nline3", 6, 6, "line2\n"));
    }

    // --- caretAfterInsert ---------------------------------------------------

    @Test
    public void caretLandsAfterInsertedText() {
        assertEquals(12,
                FloatingDictationAccessibilityService.caretAfterInsert("Hello world", 6, 6, 6));
    }

    @Test
    public void caretReplacesSelectionSpan() {
        // Inserted 4 chars over the 5-char selection "brave" starting at 6.
        assertEquals(10,
                FloatingDictationAccessibilityService.caretAfterInsert("Hello brave world", 6, 11, 4));
    }

    @Test
    public void caretAtEndForUnknownSelection() {
        assertEquals(8,
                FloatingDictationAccessibilityService.caretAfterInsert("existing", -1, -1, 0));
    }

    @Test
    public void negativeInsertLengthDoesNotMoveCaretBehindStart() {
        assertEquals(3,
                FloatingDictationAccessibilityService.caretAfterInsert("abc", 3, 3, -5));
    }
}
