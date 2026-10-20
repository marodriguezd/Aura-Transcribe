package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM coverage for the prompt-profile store that replaced the style
 * preset dropdown: the built-in default is reserved, user prompts are one file
 * per profile, and every mutation either happens durably or reports failure.
 *
 * <p>The cases that matter in practice: a name must never escape
 * {@code pp_prompts/} or collide with the default, a multi-paragraph prompt
 * must survive a round trip unchanged, a half-written atomic temporary must not
 * appear as a prompt, and deleting a profile must be a single {@code delete()}
 * that reports whether the file is really gone.
 */
public class PromptProfilesTest {

    private File filesDir;

    @Before
    public void setUp() throws IOException {
        filesDir = Files.createTempDirectory("prompt-profiles").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(filesDir);
    }

    @Test
    public void writesAndReadsAMultiParagraphPromptUnchanged() {
        String body = "Clean the transcript.\n\n- Keep names.\n- Keep numbers: 42.";
        assertTrue(PromptProfiles.write(filesDir, "Work", body));
        assertEquals(body, PromptProfiles.read(filesDir, "Work"));
    }

    @Test
    public void createsTheDirectoryOnFirstWrite() {
        assertFalse(PromptProfiles.dir(filesDir).exists());
        assertTrue(PromptProfiles.write(filesDir, "First", "body"));
        assertTrue(PromptProfiles.dir(filesDir).isDirectory());
    }

    @Test
    public void listsUserPromptsSortedAndExcludesTheReservedDefault() {
        assertTrue(PromptProfiles.write(filesDir, "zeta", "z"));
        assertTrue(PromptProfiles.write(filesDir, "Alpha", "a"));
        // A file named after the built-in prompt is never a user prompt.
        assertTrue(PromptProfiles.write(filesDir, PromptProfiles.DEFAULT_ID, "not-a-profile"));

        List<String> ids = PromptProfiles.customIds(filesDir);
        assertEquals(2, ids.size());
        assertEquals("Alpha", ids.get(0));
        assertEquals("zeta", ids.get(1));
    }

    @Test
    public void skipsLeftoverAtomicTemporariesAndDirectories() throws IOException {
        assertTrue(PromptProfiles.write(filesDir, "Real", "r"));
        // What a crash mid-write can leave behind (see MarkerFileHelper).
        Files.write(new File(PromptProfiles.dir(filesDir),
                "Broken" + MarkerFileHelper.TEMP_MARKER + "7-123").toPath(),
                "partial".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(PromptProfiles.dir(filesDir), "aDir").mkdirs());

        List<String> ids = PromptProfiles.customIds(filesDir);
        assertEquals(1, ids.size());
        assertEquals("Real", ids.get(0));
    }

    @Test
    public void returnsNullForMissingOrUnsafeIds() {
        assertNull(PromptProfiles.read(filesDir, "nope"));
        assertNull(PromptProfiles.read(filesDir, "../escape"));
        assertNull(PromptProfiles.read(filesDir, "a/b"));
        assertNull(PromptProfiles.read(filesDir, ".."));
        assertNull(PromptProfiles.read(filesDir, ""));
        assertNull(PromptProfiles.read(filesDir, null));
    }

    @Test
    public void refusesToWriteOutsideTheDirectory() {
        assertFalse(PromptProfiles.write(filesDir, "../escape", "body"));
        assertFalse(PromptProfiles.write(filesDir, "a/b", "body"));
        assertFalse(PromptProfiles.write(filesDir, ".", "body"));
        assertFalse(new File(filesDir.getParentFile(), "escape").exists());
    }

    @Test
    public void refusesToStoreAnEmptyBody() {
        // Empty is not "delete": deleting a profile is explicit, and an empty
        // system prompt would leave the request with no instruction at all.
        assertFalse(PromptProfiles.write(filesDir, "Empty", "   "));
        assertFalse(PromptProfiles.write(filesDir, "Empty", null));
        assertTrue(PromptProfiles.customIds(filesDir).isEmpty());
    }

    @Test
    public void deleteRemovesTheFileAndReportsWhetherItIsGone() {
        assertTrue(PromptProfiles.write(filesDir, "Gone", "body"));
        assertTrue(PromptProfiles.delete(filesDir, "Gone"));
        assertFalse(new File(PromptProfiles.dir(filesDir), "Gone").exists());
        // Deleting something that is not there is a success (idempotent), but
        // an unsafe id is refused.
        assertTrue(PromptProfiles.delete(filesDir, "Gone"));
        assertFalse(PromptProfiles.delete(filesDir, "../escape"));
    }

    @Test
    public void sanitizeNameProducesASafeSinglePathSegment() {
        assertEquals("Work notes", PromptProfiles.sanitizeName("  Work   notes "));
        assertEquals("a b", PromptProfiles.sanitizeName("a/b"));
        assertEquals("a b", PromptProfiles.sanitizeName("a\\b"));
        assertEquals("a b", PromptProfiles.sanitizeName("a\nb"));
        assertNull(PromptProfiles.sanitizeName("   "));
        assertNull(PromptProfiles.sanitizeName("."));
        assertNull(PromptProfiles.sanitizeName(".."));
        assertNull(PromptProfiles.sanitizeName(null));
    }

    @Test
    public void sanitizeNameCapsTheLength() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append('x');
        String name = PromptProfiles.sanitizeName(longName.toString());
        assertNotNull(name);
        assertEquals(PromptProfiles.MAX_NAME_LENGTH, name.length());
    }

    @Test
    public void uniqueIdNeverCollidesWithAnExistingPromptOrTheDefault() {
        assertEquals("Work", PromptProfiles.uniqueId(filesDir, "Work"));

        assertTrue(PromptProfiles.write(filesDir, "Work", "body"));
        assertEquals("Work (2)", PromptProfiles.uniqueId(filesDir, "Work"));
        assertTrue(PromptProfiles.write(filesDir, "Work (2)", "body"));
        assertEquals("Work (3)", PromptProfiles.uniqueId(filesDir, "Work"));

        // The reserved default id can never name a user profile.
        assertEquals("default (2)", PromptProfiles.uniqueId(filesDir, "default"));
        assertNull(PromptProfiles.uniqueId(filesDir, "   "));
    }

    @Test
    public void uniqueIdKeepsTheSuffixWithinTheLengthCap() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < PromptProfiles.MAX_NAME_LENGTH; i++) longName.append('x');
        assertTrue(PromptProfiles.write(filesDir, longName.toString(), "body"));

        String unique = PromptProfiles.uniqueId(filesDir, longName.toString());
        assertNotNull(unique);
        assertTrue(unique.length() <= PromptProfiles.MAX_NAME_LENGTH);
        assertTrue(unique.endsWith(" (2)"));
        assertFalse(unique.equals(longName.toString()));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
