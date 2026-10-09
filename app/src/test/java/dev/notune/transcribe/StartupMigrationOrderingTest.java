package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Structural guards for the start-up credential-migration wiring. These read
 * production source and assert on its shape; they do NOT execute the Android
 * start-up path or prove runtime scheduling.
 *
 * <p><b>Structural vs behavioural coverage.</b> What can be behaviourally proven
 * on the JVM — that a read queued behind a start-up migration observes the
 * imported credential, that a migration failure is surfaced rather than
 * silently becoming a confirmed absence, and that repeated migration passes are
 * idempotent — is covered by {@code CredentialOperationsTest} (lane ordering,
 * read-behind-migration, failure-vs-absence) and {@code CredentialMigrationTest}
 * (idempotency, precedence, tombstones). What only a structural test can guard
 * is the Android-specific glue that the JVM suite cannot reach (android.jar is
 * stubbed, so {@code Application}/{@code Context}/{@code SharedPreferences}
 * cannot be instantiated): that {@code Application.onCreate} submits the
 * migration synchronously, and that {@code SettingsManager.migrateIfNeeded}
 * hands the whole migration to the credential lane instead of running its
 * blocking filesystem I/O inline on the caller's (main) thread.
 *
 * <p>Android guarantees {@code Application.onCreate} completes before any
 * Activity/Service/IME callback runs, so a synchronous submit there queues the
 * migration ahead of every production read. When the call was deferred to a
 * background thread, an IME view created at process start could submit its
 * toggle sync first: the read then ran before the migration and answered ABSENT
 * for a credential that existed only in a legacy prefs source — recoverable by
 * the pending migration, but invisible to the unordered read, and the toggle
 * never re-synced for the life of that view.
 */
public class StartupMigrationOrderingTest {

    @Test
    public void theStartupImportIsSubmittedSynchronouslyFromApplicationOnCreate()
            throws Exception {
        String source = readSourceFile("App.java");
        String onCreate = extractMethodBody(source, "public void onCreate()");
        assertNotNull("App.onCreate must exist", onCreate);

        // The import is submitted by a direct call in onCreate's body, before
        // any thread is spawned — so it is queued before any component created
        // after Application.onCreate can submit a read.
        int threadSpawn = onCreate.indexOf("new Thread(");
        String synchronousPart = threadSpawn >= 0 ? onCreate.substring(0, threadSpawn) : onCreate;
        assertTrue("App.onCreate must submit the credential import directly on the main"
                        + " thread; deferring it to a background thread lets an early"
                        + " component (e.g. the IME view created at process start) submit"
                        + " a read before the import, so a legacy-prefs credential reads"
                        + " as ABSENT",
                synchronousPart.contains("SettingsManager.migrateIfNeeded(this)"));

        // And it must not ALSO be submitted from the bootstrap thread, where it
        // would race the component lifecycle.
        if (threadSpawn >= 0) {
            String threadPart = onCreate.substring(threadSpawn);
            assertTrue("the bootstrap thread must not carry the credential import",
                    !threadPart.contains("migrateIfNeeded"));
        }
    }

    @Test
    public void migrateIfNeededSubmitsToTheLaneWithoutRunningBlockingWorkInline()
            throws Exception {
        String source = readSourceFile("SettingsManager.java");
        String body = extractMethodBody(source, "static void migrateIfNeeded(");
        assertNotNull("SettingsManager.migrateIfNeeded must exist", body);

        // The migration is handed to the credential lane, so the import is queued
        // behind nothing and ahead of every start-up read.
        assertTrue("migrateIfNeeded must submit the migration to the credential lane",
                body.contains("CREDENTIAL_OPS.submit("));

        // The caller's thread (the Android main thread, via Application.onCreate)
        // must perform no filesystem access and no blocking I/O: no file lock, no
        // SharedPreferences load, no marker write, no synchronous commit. Every
        // one of those runs on the lane inside runFullMigration /
        // runLegacyApiKeyMigrationRetry. (The method's own prose comment mentions
        // these operations by name; the assertions below match the code calls,
        // not the words, so they stay accurate.)
        assertFalse("migrateIfNeeded must not open a file lock on the caller's thread",
                body.contains("new FileOutputStream"));
        assertFalse("migrateIfNeeded must not load SharedPreferences on the caller's thread",
                body.contains("getSharedPreferences"));
        assertFalse("migrateIfNeeded must not acquire a FileLock on the caller's thread",
                body.contains("tryLock"));
        assertFalse("migrateIfNeeded must not commit SharedPreferences on the caller's thread",
                body.contains(".commit("));
        assertFalse("migrateIfNeeded must not create marker files on the caller's thread",
                body.contains("createNewFile"));
    }

    private static String readSourceFile(String fileName) throws Exception {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 6; i++) {
            for (String prefix : new String[]{"app/", ""}) {
                File candidate = new File(dir, prefix + "src/main/java/dev/notune/transcribe/" + fileName);
                if (candidate.isFile()) {
                    return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
            if (dir == null) break;
        }
        fail(fileName + " source not found relative to " + System.getProperty("user.dir"));
        return null;
    }

    /** Brace-matched body of the first method whose signature contains
     *  {@code signature}, or null when no such method exists. */
    private static String extractMethodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) return null;
        int open = source.indexOf('{', at);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open + 1, i);
            }
        }
        return null;
    }
}
