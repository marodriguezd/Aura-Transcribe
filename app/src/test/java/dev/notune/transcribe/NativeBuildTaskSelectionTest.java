package dev.notune.transcribe;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit test validating task classification for native compilation skipping.
 *
 * Ensures that pure test/lint runs skip native build invocation to conserve CPU,
 * while mixed invocations (e.g. assembleDebug testDebugUnitTest) or build tasks
 * correctly require native compilation.
 */
public class NativeBuildTaskSelectionTest {

    /**
     * Mirrors the pure logic of needsNativeBuild(List<String>) in app/build.gradle.kts.
     */
    public static boolean needsNativeBuild(List<String> taskNames) {
        if (taskNames.isEmpty()) return true;
        java.util.List<String> bare = new java.util.ArrayList<>();
        for (String t : taskNames) {
            int idx = t.lastIndexOf(':');
            bare.add(idx >= 0 ? t.substring(idx + 1) : t);
        }

        boolean isVerificationOnly = true;
        for (String name : bare) {
            String lower = name.toLowerCase();
            if (!(lower.contains("lint") || lower.contains("test") || lower.contains("check") || lower.contains("verify"))) {
                isVerificationOnly = false;
                break;
            }
        }

        boolean requestsBuildArtifact = false;
        for (String name : bare) {
            String lower = name.toLowerCase();
            if (lower.startsWith("assemble") ||
                lower.startsWith("bundle") ||
                lower.startsWith("install") ||
                lower.startsWith("package") ||
                lower.contains("androidtest") ||
                lower.equals("build")) {
                requestsBuildArtifact = true;
                break;
            }
        }

        return requestsBuildArtifact || !isVerificationOnly;
    }

    @Test
    public void testPureVerificationTasksSkipNativeBuild() {
        assertFalse(needsNativeBuild(Collections.singletonList("testDebugUnitTest")));
        assertFalse(needsNativeBuild(Collections.singletonList("lintDebug")));
        assertFalse(needsNativeBuild(Collections.singletonList(":app:testDebugUnitTest")));
        assertFalse(needsNativeBuild(Arrays.asList("testDebugUnitTest", "lintDebug")));
        assertFalse(needsNativeBuild(Collections.singletonList("checkModels")));
        assertFalse(needsNativeBuild(Collections.singletonList("verifyPackagingDecision")));
    }

    @Test
    public void testArtifactBuildingTasksRequireNativeBuild() {
        assertTrue(needsNativeBuild(Collections.singletonList("assembleDebug")));
        assertTrue(needsNativeBuild(Collections.singletonList(":app:assembleRelease")));
        assertTrue(needsNativeBuild(Collections.singletonList("bundleRelease")));
        assertTrue(needsNativeBuild(Collections.singletonList("build")));
        assertTrue(needsNativeBuild(Collections.singletonList("installDebug")));
        assertTrue(needsNativeBuild(Collections.singletonList("packageRelease")));
        assertTrue(needsNativeBuild(Collections.singletonList("connectedDebugAndroidTest")));
        assertTrue(needsNativeBuild(Collections.singletonList(":app:assembleDebugAndroidTest")));
    }

    @Test
    public void testMixedInvocationsRequireNativeBuild() {
        // Critical audit fix: mixed task invocation must NOT skip native build
        assertTrue(needsNativeBuild(Arrays.asList("assembleDebug", "testDebugUnitTest")));
        assertTrue(needsNativeBuild(Arrays.asList("lintDebug", "packageRelease")));
        assertTrue(needsNativeBuild(Arrays.asList(":app:assembleDebug", ":app:lintDebug")));
    }

    @Test
    public void testEmptyOrUnknownTasksRequireNativeBuild() {
        // Tooling / sync invocation
        assertTrue(needsNativeBuild(Collections.emptyList()));
        assertTrue(needsNativeBuild(Collections.singletonList("customTask")));
    }
}
