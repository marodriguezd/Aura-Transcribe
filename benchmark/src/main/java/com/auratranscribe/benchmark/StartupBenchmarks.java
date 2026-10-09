package com.auratranscribe.benchmark;

import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.Metric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import kotlin.Unit;

/**
 * Android platform startup benchmarks for Aura Transcribe.
 *
 * <p>These measure the *host application* performance that the in-app ASR
 * benchmark (MainActivity → main_activity.rs) deliberately does not: process
 * start, Activity inflation and first-frame latency. Both numbers matter and are
 * reported separately.
 *
 * <p>Run against a real arm64 device or an emulator (this class cannot run on
 * the JVM, and is intentionally not wired into {@code check}/{@code build}):
 * <pre>
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * </pre>
 *
 * <p>These tests have no absolute threshold to violate: the same APK has to pass
 * on a 2018 phone and a 2026 flagship, so macrobenchmark reports the metrics on
 * stdout and under {@code build/outputs/connected_android_test_additional_output}
 * instead of asserting on them.
 *
 * <p>Note on compilation modes: the {@code CompilationMode} subclasses are plain
 * classes in benchmark-macro 1.5.0, not Kotlin {@code object}s, so they are
 * instantiated with {@code new} — {@code CompilationMode.None.INSTANCE} does not
 * exist and does not compile.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class StartupBenchmarks {

    /** The app under test (its applicationId). */
    private static final String PACKAGE = "com.auratranscribe.app";

    /** Iterations per measurement. Higher is more stable but slower. */
    private static final int ITERATIONS = 5;

    @Rule
    public MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    /**
     * Cold start with no AOT compilation: the process does not exist, so native
     * library loading, class loading and Activity inflation are all paid.
     *
     * <p>{@code CompilationMode.None} is the realistic worst case and the mode
     * the Baseline Profile is meant to improve.
     */
    @Test
    public void coldStartup() {
        benchmarkRule.measureRepeated(
                PACKAGE,
                Arrays.<Metric>asList(new StartupTimingMetric(), new FrameTimingMetric()),
                new CompilationMode.None(),
                StartupMode.COLD,
                ITERATIONS,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                });
    }

    /** Warm start: the process is alive, so the Activity must be recreated. */
    @Test
    public void warmStartup() {
        benchmarkRule.measureRepeated(
                PACKAGE,
                Arrays.<Metric>asList(new StartupTimingMetric(), new FrameTimingMetric()),
                new CompilationMode.None(),
                StartupMode.WARM,
                ITERATIONS,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Cold start with everything AOT compiled ({@code CompilationMode.Full}).
     *
     * <p>This is the counterpart to {@link #coldStartup()}: measuring the same
     * journey with and without AOT is what shows whether the shipped Baseline
     * Profile (plus the Play Cloud Profile) actually buys anything on this
     * device, rather than merely asserting that it was generated.
     *
     * <p>It replaces a previous {@code coldStartupWithoutCompilation} test that
     * was documented as "the worst realistic case used when validating that the
     * Baseline Profile actually helps" but in fact used exactly the same
     * {@code CompilationMode.None} and the same {@code StartupMode.COLD} as
     * {@link #coldStartup()} — it measured nothing new, just with fewer metrics.
     */
    @Test
    public void coldStartupFullyCompiled() {
        benchmarkRule.measureRepeated(
                PACKAGE,
                Arrays.<Metric>asList(new StartupTimingMetric(), new FrameTimingMetric()),
                new CompilationMode.Full(),
                StartupMode.COLD,
                ITERATIONS,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                });
    }
}
