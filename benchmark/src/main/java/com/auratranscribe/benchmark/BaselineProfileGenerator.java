package com.auratranscribe.benchmark;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import kotlin.Unit;

/**
 * Generates the Baseline Profile consumed by the release build.
 *
 * <p>The generated profile is written to
 * {@code benchmark/build/outputs/connected_android_test_additional_output/…} and
 * must be copied to {@code app/src/main/baseline-prof.txt} before a release so
 * AGP packages it. It is deliberately <em>not</em> committed as a hand-written
 * file: a profile is only meaningful when produced by the compiler from real
 * executions on a device.
 *
 * <p>The profile covers the two user journeys that dominate perceived startup:
 * <ol>
 *   <li>cold start → main screen (the launcher path), and</li>
 *   <li>main screen → live-subtitles setup screen, which is the heaviest
 *       secondary surface reachable from the first screen.</li>
 * </ol>
 *
 * <p>Requires a connected arm64 device/emulator — this class cannot run on the
 * JVM, and it is intentionally not wired into {@code check}/{@code build}:
 * <pre>
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *       --tests com.auratranscribe.benchmark.BaselineProfileGenerator
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BaselineProfileGenerator {

    /** The app under test (its applicationId, which is also its resource package). */
    private static final String PACKAGE = "com.auratranscribe.app";

    /** How long to wait for a view before declaring the journey unrunnable. */
    private static final long UI_TIMEOUT_MS = 15_000L;

    @Rule
    public BaselineProfileRule baselineRule = new BaselineProfileRule();

    @Test
    public void generate() {
        // The single-argument-plus-block overload of collect() is the one
        // benchmark-macro 1.5.0 exposes to Java: the other overloads all take
        // additional leading parameters (iterations, filters, flags) that this
        // journey does not need. There is no collect(package, List<Metric>, …)
        // form — that signature does not exist in this version.
        baselineRule.collect(PACKAGE, scope -> {
            // Journey 1: launcher -> main screen (cold process start).
            scope.pressHome();
            scope.startActivityAndWait();

            // Journey 2: main screen -> live-subtitles setup screen.
            //
            // This used to click on the English label "Start Live Subtitles".
            // The app ships seven UI locales and the model covers 40
            // language-locales, so anchoring the journey to an English string
            // meant it could never run on a non-English device. UiAutomator is
            // now pointed at the view id, which does not change with locale.
            UiDevice device = scope.getDevice();
            UiObject2 startSubs = findStartSubsButton(device);
            assertNotNull(
                    "R.id.btn_subs_start was not found on the main screen. The "
                            + "live-subtitles journey would silently not run and the "
                            + "generated profile would cover only the launcher path.",
                    startSubs);
            startSubs.click();

            // Assert the second surface really came up, instead of trusting that
            // the click worked. Without this, a broken journey still produces a
            // "successful" profile that just covers the first screen.
            assertTrue(
                    "LiveSubtitleActivity did not appear within " + UI_TIMEOUT_MS
                            + "ms of tapping the live-subtitles button.",
                    device.wait(
                            Until.hasObject(By.res(PACKAGE, "live_subtitle_root")),
                            UI_TIMEOUT_MS));
            device.waitForIdle();
            return Unit.INSTANCE;
        });
    }

    /**
     * Returns the live-subtitles button, scrolling the main screen first if the
     * card sits below the fold (which it does on short screens).
     *
     * <p>Returns {@code null} when the button cannot be reached at all; the
     * caller turns that into a hard failure rather than a partial profile.
     */
    private static UiObject2 findStartSubsButton(UiDevice device) {
        UiObject2 button = device.wait(
                Until.findObject(By.res(PACKAGE, "btn_subs_start")), UI_TIMEOUT_MS);
        if (button != null) {
            return button;
        }

        // On a tablet the whole card fits on screen, so the wait above succeeds
        // and this branch is not taken. On a phone the button is often past the
        // fold, and the main screen is a plain ScrollView.
        List<UiObject2> scrollables = device.findObjects(By.scrollable(true));
        if (scrollables.isEmpty()) {
            return null;
        }
        UiObject2 scroller = scrollables.get(0);
        for (int attempt = 0; attempt < 5 && button == null; attempt++) {
            scroller.scroll(Direction.DOWN, 1.0f);
            button = device.wait(
                    Until.findObject(By.res(PACKAGE, "btn_subs_start")), 2_000L);
        }
        return button;
    }
}
