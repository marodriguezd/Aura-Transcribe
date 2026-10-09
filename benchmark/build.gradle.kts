plugins {
    alias(libs.plugins.android.test)
}

// ---------------------------------------------------------------------------
// Macrobenchmark + Baseline Profile module.
//
// This module measures *Android platform* performance (cold/warm startup,
// interaction latency, jank) and generates the Baseline Profile shipped with
// the release APK. It is complementary to the in-app ASR benchmark
// (MainActivity.runBenchmark -> main_activity.rs), which measures native
// inference throughput; neither replaces the other.
//
// Running it needs a connected arm64 device or emulator:
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest
//   ./gradlew :benchmark:pixel6Api35BenchmarkAndroidTest   # managed device
// The module is intentionally NOT wired into `check`/`build` so that device-less
// CI (unit tests + lint + assemble) keeps working (see AGENTS.md §3).
// ---------------------------------------------------------------------------

android {
    namespace = "com.auratranscribe.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Macrobenchmark drives another app's process; it targets the same
        // platform level as the app under test.
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // Only a `benchmark` variant exists. It is unminified (so the harness can
        // address components by name) and debug-signed (so it is installable),
        // matching the app module's `benchmark` build type.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // The app under test.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    // androidx.test.filters.LargeTest (used by both benchmark classes) lives in
    // androidx.test:runner. It is NOT pulled in transitively by
    // benchmark-macro-junit4, and without it the module does not compile.
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
