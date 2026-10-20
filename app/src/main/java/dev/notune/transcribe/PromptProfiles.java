package dev.notune.transcribe;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Storage for the post-processing prompt profiles the user can create and
 * delete.
 *
 * <p>One profile is a file in {@code filesDir()/pp_prompts/} whose <b>name is
 * the profile name</b> and whose content is the prompt body. That keeps a
 * profile atomic (one file, one write) and makes deleting one a single
 * {@code delete()} — there is no index to fall out of sync with the bodies.
 * Writes go through {@link MarkerFileHelper#writeStringToFile} so they keep the
 * project-wide unique-temp + fsync + atomic-rename contract.
 *
 * <p>The shipped default prompt is <b>not</b> a file here: it lives in
 * {@code R.string.pp_default_prompt}, so it keeps following app updates.
 * {@link #DEFAULT_ID} is reserved and can never name a user profile; a
 * user-created profile called "default" therefore becomes "default (2)".
 *
 * <p>Deliberately free of {@code Context} (the directory is passed in) so every
 * rule here is exercisable from a plain-JVM test with a temporary directory.
 */
public final class PromptProfiles {

    /** Directory under {@code filesDir()} that holds the user-created prompts. */
    public static final String DIR_NAME = "pp_prompts";

    /** Reserved id of the built-in prompt (never a file, never deletable). */
    public static final String DEFAULT_ID = "default";

    /** Longest accepted profile name; file systems truncate at ~255 bytes. */
    static final int MAX_NAME_LENGTH = 60;

    private PromptProfiles() {
        // Utility class
    }

    /** The directory that holds the user-created prompt bodies. */
    public static File dir(File filesDir) {
        return new File(filesDir, DIR_NAME);
    }

    /**
     * Names of the user-created profiles, sorted case-insensitively.
     *
     * <p>Leftover atomic-write temporaries ({@code <name>.tmp<thread>-<nano>},
     * see {@link MarkerFileHelper}) are skipped: a crash mid-write must not
     * surface a half-written temp as a prompt.
     */
    public static List<String> customIds(File filesDir) {
        File dir = dir(filesDir);
        String[] names = dir.list();
        List<String> ids = new ArrayList<>();
        if (names == null) return ids;
        for (String name : names) {
            if (name == null) continue;
            if (name.equals(DEFAULT_ID)) continue;
            if (name.contains(MarkerFileHelper.TEMP_MARKER)) continue;
            File file = new File(dir, name);
            if (!file.isFile()) continue;
            ids.add(name);
        }
        Collections.sort(ids, (a, b) -> {
            int byName = a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
            return byName != 0 ? byName : a.compareTo(b);
        });
        return ids;
    }

    /** The stored body of {@code id}, or {@code null} when there is none. */
    public static String read(File filesDir, String id) {
        if (!isSafeId(id)) return null;
        String body = MarkerFileHelper.readStringFromFile(dir(filesDir), id, null);
        return (body == null || body.trim().isEmpty()) ? null : body;
    }

    /**
     * Stores {@code body} as profile {@code id}, creating the directory when
     * needed. An empty body is refused (it would delete the file, silently
     * losing a profile): callers delete explicitly.
     *
     * @return true when the body was durably written
     */
    public static boolean write(File filesDir, String id, String body) {
        if (!isSafeId(id)) return false;
        if (body == null || body.trim().isEmpty()) return false;
        File dir = dir(filesDir);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        return MarkerFileHelper.writeStringToFile(dir, id, body);
    }

    /** Removes profile {@code id}. Returns true only when the file is gone. */
    public static boolean delete(File filesDir, String id) {
        if (!isSafeId(id)) return false;
        File file = new File(dir(filesDir), id);
        if (!file.exists()) return true;
        return file.delete();
    }

    /**
     * Normalizes a user-typed name into something that is a safe single path
     * segment: trimmed, without path separators or control characters, and
     * capped at {@link #MAX_NAME_LENGTH} characters.
     *
     * @return the cleaned name, or {@code null} when nothing usable is left
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        String name = out.toString().trim().replaceAll("\\s+", " ");
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).trim();
        }
        if (name.isEmpty()) return null;
        if (name.equals(".") || name.equals("..")) return null;
        return name;
    }

    /**
     * A free profile id derived from {@code name}: the sanitized name, or that
     * name with a " (2)", " (3)" … suffix when it is already taken (or is the
     * reserved {@link #DEFAULT_ID}).
     *
     * @return the id, or {@code null} when the name is unusable
     */
    public static String uniqueId(File filesDir, String name) {
        String base = sanitizeName(name);
        if (base == null) return null;
        String candidate = base;
        int suffix = 2;
        while (candidate.equals(DEFAULT_ID) || new File(dir(filesDir), candidate).exists()) {
            String tag = " (" + suffix + ")";
            int keep = Math.min(base.length(), MAX_NAME_LENGTH - tag.length());
            candidate = base.substring(0, keep).trim() + tag;
            suffix++;
            if (suffix > 1000) return null;
        }
        return candidate;
    }

    /**
     * Whether {@code id} can be used as a path segment: non-empty, not a
     * traversal, and free of separators. Ids come from the directory listing in
     * practice; this is the guard that keeps a corrupted or hand-written marker
     * from reaching outside {@code pp_prompts/}.
     */
    static boolean isSafeId(String id) {
        if (id == null || id.isEmpty()) return false;
        if (id.equals(".") || id.equals("..")) return false;
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }
}
