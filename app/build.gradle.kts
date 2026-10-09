import java.io.FileInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.notune.transcribe"
    // Android 17 (API 37). The Java namespace intentionally stays
    // `dev.notune.transcribe`: it is an internal, source-level name that the
    // JNI symbol contract (Java_dev_notune_transcribe_*) and every
    // #[no_mangle] entry point in src/*.rs depend on. The *runtime* package the
    // platform sees is `applicationId` below.
    compileSdk = 37
    // Single source of truth for the NDK (P0.4): this exact version is what
    // CI installs (`.github/workflows/*.yml` -> sdkmanager ndk;28.2.13676358)
    // and what README/AGENTS document. Keep all three in sync.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.auratranscribe.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 41
        versionName = "0.2.2"
        ndk {
            // Android 17 requires 16 KB page-size alignment, which the native
            // build already produces (see RUSTFLAGS below). Only arm64-v8a is
            // shipped; x86/x86_64 are deliberately excluded because
            // check_cpu_features requires dotprod+fp16 (ARMv8.2, ~2018+) and
            // would SIGILL mid-inference on anything older. Lint's
            // [ChromeOsAbiSupport] warning is the expected consequence: an x86
            // Chromebook cannot run this app, and adding the ABI to silence the
            // warning would ship a library that aborts on load.
            abiFilters += "arm64-v8a"
        }
    }

    // Whether a release keystore is available at all. Hoisted out of
    // signingConfigs so buildTypes can consult the same answer: a signing
    // config whose storeFile was never set cannot be attached to a build type,
    // because AGP then fails validateSigningRelease with "Keystore file not set
    // for signing config release" — which broke `assembleRelease` on a fresh
    // clone (and on F-Droid's buildserver, which builds from source and signs
    // the APK itself). Without a keystore the release variant now produces an
    // UNSIGNED apk, as documented in AGENTS.md.
    val releaseKeystoreFile = rootProject.file("release.keystore")
    val hasReleaseKeystore = releaseKeystoreFile.exists()

    signingConfigs {
        getByName("debug") {
            val debugKsFile = rootProject.file("keystore/debug.keystore")
            if (debugKsFile.exists()) {
                storeFile = debugKsFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        create("release") {
            val ksFile = releaseKeystoreFile
            if (hasReleaseKeystore) {
                // Security hardening (2026-08-06): never fall back to default
                // credentials. A release signed with the well-known
                // "password"/"release" defaults would let anyone who obtains
                // the APK ship updates that Android accepts as the same app.
                // When the keystore exists but a signing env var is missing,
                // release builds fail fast with a clear message instead. The
                // GitHub Actions release workflow always decodes the keystore
                // from KEYSTORE_BASE64 and exports all three credentials; local
                // maintainers must export them too. See android_release.yml.
                val envStorePass = System.getenv("STORE_PASS")
                val envKeyAlias = System.getenv("KEY_ALIAS")
                val envKeyPass = System.getenv("KEY_PASS")
                // Detect every task that can produce a release artifact, not
                // just the ones whose name contains "Release"/"bundle": the
                // aggregators `assemble` and `build` (with or without a project
                // prefix, e.g. `:app:assemble`) also build the release
                // variant, and would otherwise sign with the default
                // credentials below without failing. Debug-only tasks such as
                // `assembleDebug` never touch the release signing config, so
                // they stay exempt and local debug builds keep working
                // without exported env vars.
                val taskTargetsRelease = gradle.startParameter.taskNames.any { name ->
                    val base = name.substringAfterLast(':')
                    base.equals("assemble", ignoreCase = true)
                        || base.equals("build", ignoreCase = true)
                        || base.contains("Release", ignoreCase = true)
                        || base.contains("bundle", ignoreCase = true)
                }
                if (taskTargetsRelease
                    && (envStorePass == null || envKeyAlias == null || envKeyPass == null)
                ) {
                    throw GradleException(
                        "release.keystore found but STORE_PASS/KEY_ALIAS/KEY_PASS is missing. " +
                            "Refusing to sign a release with default credentials. Export the " +
                            "three env vars (or use the CI workflow, which decodes KEYSTORE_BASE64)."
                    )
                }
                storeFile = ksFile
                storePassword = envStorePass ?: "password"
                keyAlias = envKeyAlias ?: "release"
                keyPassword = envKeyPass ?: "password"
            } else {
                println("WARNING: release.keystore not found — the release variant " +
                    "will build an UNSIGNED apk. Provide release.keystore + " +
                    "STORE_PASS/KEY_ALIAS/KEY_PASS, or let the CI workflow decode " +
                    "KEYSTORE_BASE64, to produce a signable release.")
            }
        }
    }

    buildTypes {
        release {
            // Release optimisation (P0). R8 shrinks + obfuscates Java/Kotlin and
            // the resource shrinker strips unreferenced resources. The keep
            // rules that this app genuinely needs (JNI entry points, the Java
            // callbacks Rust invokes by name, and the manifest-referenced
            // components) live in proguard-rules.pro — narrowly scoped, with a
            // reason on every rule.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing config when a keystore actually
            // backs it. Attaching an empty one makes AGP fail the build instead
            // of emitting an unsigned apk.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Dedicated variant for Macrobenchmark / Baseline Profile generation.
        // Mirrors the official Android performance-samples setup: same code path
        // as release but unminified so the measurement harness can address
        // components by name, and signed with the debug key so it is installable.
        // AGP injects <profileable android:shell="true"/> for this build type.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        // BuildConfig.DEBUG is used to gate transcript/PP-error logging to
        // debug builds only (privacy, 2026-08-04). AGP 8.x disables
        // BuildConfig generation by default; without this flag the
        // referenced classes do not compile.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk.
    // The bundled speech model is included only in release builds; debug builds
    // ship without it to keep the APK under Telegram's 50 MB file-size limit.
    // The app downloads the model from Hugging Face on first run in debug mode.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // extractNativeLibs=false (16KB safe)
            keepDebugSymbols += "**/*.so"
        }
    }

    // Per-app language picker (Android 13+): AGP derives the locale list from the
    // values-*/ folders and writes android:localeConfig for us.
    androidResources {
        generateLocaleConfig = true
    }

    // Play Asset Delivery: large model files go into a separate asset pack
    // so the base module stays under the 200 MB Play Store limit.
    assetPacks += listOf(":model_assets")

    testOptions {
        unitTests {
            // Lets plain-JUnit tests access the merged R/assets without a device,
            // matching the Handy-Android guantelete harness (AGENTS.md §3).
            isIncludeAndroidResources = true
            // Let android.jar methods (notably android.util.Log) return default
            // values instead of throwing "Method not mocked", so the
            // PostProcessor HTTP suite (P1.3) can exercise the real error paths
            // on the JVM. No existing test relies on the "not mocked" throw.
            isReturnDefaultValues = true
        }
    }

    lint {
        // Rational release-quality baseline: errors fail the build, warnings are
        // reported (so real problems stay visible) without blocking on cosmetic
        // advice. This replaces the previous `ignoreWarnings = true` +
        // ~46-check blanket disable, which hid genuine platform problems
        // (NewApi, MissingPermission, unregistered receivers, exported
        // components, hard-coded text, clickable accessibility, ...).
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        ignoreWarnings = false
        checkAllWarnings = false
        checkDependencies = false
        ignoreTestSources = true
        disable += setOf(
            // Dependency/AGP version bumps are deliberate decisions made through
            // gradle/libs.versions.toml, not something lint should nag about.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            // The launcher icon is a hand-authored vector with intentionally
            // dense paths; density lint is not actionable for vector art.
            "VectorPath",
            // The `audio/*` SEND/VIEW filters are mime handlers, not app links:
            // there is no http(s) host to verify, so this is a false positive
            // (documented inline in AndroidManifest.xml too).
            "AppLinkUrlError",
            // Overdraw suppression was informational only; kept disabled because
            // the translucent overlay/voice-panel surfaces are intentionally
            // layered.
            "Overdraw"
        )
    }
}

// ---------------------------------------------------------------------------
// Bundled-model delivery: which packaging path is this invocation producing?
// ---------------------------------------------------------------------------
// Two delivery mechanisms exist and they are mutually exclusive per artifact:
//
//   * An APK has to carry the GGUF inside the base module, because AGP ignores
//     asset packs when it packages an APK. That is the mechanism behind the
//     ~745 MB APK attached to GitHub Releases and built by F-Droid, so it must
//     keep working.
//   * An AAB must carry the GGUF ONLY in the :model_assets asset pack (a
//     separate install-time delivery), so the base module stays under Play's
//     download limit. Adding the same directory to the base module as well would
//     deliver ~750 MB to the user twice.
//
// Both artifacts are packaged from the same `release` variant, so the choice can
// only be made per *invocation*. The previous implementation sniffed the whole
// command line for "bundle" and silently preferred that branch, which made
// `./gradlew assembleRelease bundleRelease` (and anything else that merely
// mentioned a bundle task) produce a 32 MB APK with NO bundled model — and still
// report success. Verified before the fix: 32 MB and no assets/builtin-model.
//
// The rules below replace that silent guess. They are intentionally reluctant:
// the only way to get a model-less release APK out of this build is to say so
// with -Pauratranscribe.allowModelLessReleaseApk=true.
//
// "bundle" appears in several task names (bundleRelease, bundle, installReleaseBundle,
// publishReleaseBundle...), so bundle-ness is tested first and anything that also
// looks like an APK producer makes the invocation ambiguous.
//
// The classification is the `packagingDecision()` helper at the bottom of this file,
// not an inline filter chain, so the `verifyPackagingDecision` gate can drive the
// exact same implementation with a table of task lists. A regression test that
// re-implemented the rule would be worse than no test: it would pass forever while
// the build drifted. (A function, not a top-level `val`: script properties are
// initialised in declaration order, so one declared below this line would still be
// null here.)
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val (apkTaskNames, bundleTaskNames, packagingMode) = packagingDecision(requestedTaskNames)
if (packagingMode == "ambiguous") {
    throw GradleException(
        "This invocation asks for an APK and an app bundle at the same time, and they cannot both " +
            "carry the bundled model correctly: the APK needs the GGUF in the base module, while the " +
            "AAB must keep it only in the :model_assets asset pack:\n" +
            "  APK tasks:    ${apkTaskNames.joinToString(", ")}\n" +
            "  bundle tasks: ${bundleTaskNames.joinToString(", ")}\n" +
            "Packaging the same 750 MB model twice would either bloat or break the artifact, so the " +
            "build stops here. Build the two artifacts in separate invocations (this is what the CI " +
            "workflows do):\n" +
            "  ./gradlew assembleRelease\n" +
            "  ./gradlew bundleRelease"
    )
}
val isBundle = packagingMode == "bundle"
if (!isBundle) {
    android.sourceSets.getByName("release") {
        assets.srcDirs(rootProject.file("model_assets/src/main/assets"))
    }
}

dependencies {
    // AndroidX baseline (explicit so the adaptive-window and edge-to-edge APIs
    // used by the UI are not pulled in only transitively through Material).
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    // Window Size Classes drive the adaptive large-screen layouts.
    implementation(libs.androidx.window)
    // Ships the Baseline Profile with the APK (see :benchmark module).
    implementation(libs.androidx.profileinstaller)

    // Material Components (Material 3 / Material You). Pulls in AppCompat.
    implementation(libs.material)

    // AI post-processing layer (fork addition): HTTP client for the
    // OpenAI-compatible /chat/completions endpoint.
    implementation(libs.okhttp)

    // Live-subtitle translation (fork addition): on-device text translation
    // (ML Kit). Language packs are downloaded through Google Play Services on
    // first use; the actual translation runs fully on-device and works offline
    // afterwards. On devices without Play Services the translator falls back
    // to showing the original subtitle text (see OnDeviceSubtitleTranslator).
    implementation(libs.mlkit.translate)

    // One-time compatibility migration for API keys saved by v0.1.19–v0.1.21
    // in EncryptedSharedPreferences. New writes remain marker-file based; this
    // dependency is retained so upgrades can recover the old key instead of
    // silently turning post-processing into unauthenticated 401 requests.
    implementation(libs.androidx.security.crypto)

    // Unit test harness
    testImplementation(libs.junit)
    // Controlled HTTP server for the post-processing cancellation-isolation
    // tests (P0.1) and payload/fallback tests (P1.3).
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json for the JVM tests: the android.jar copy is stubbed, so
    // JSONObject.toString() would return null/defaults and the PostProcessor
    // payload tests would fail (android.jar sits last on the test classpath,
    // so this shadows it).
    testImplementation(libs.json)

    // Instrumentation tests (run on a device/emulator; not part of CI, which
    // has no arm64 device — see AGENTS.md §3).
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Material/AppCompat transitively pull the legacy kotlin-stdlib-jdk7/jdk8:1.6.21
    // (via kotlinx-coroutines-android), whose classes were folded into
    // kotlin-stdlib in Kotlin 1.8 — causing duplicate-class build failures.
    // Align them with the resolved kotlin-stdlib (1.8.22), where they are empty
    // stubs. See https://kotlinlang.org/docs/whatsnew18.html#kotlin-stdlib
    constraints {
        implementation(libs.kotlin.stdlib.jdk7)
        implementation(libs.kotlin.stdlib.jdk8)
    }
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

// Name of the NDK host-toolchain directory under toolchains/llvm/prebuilt.
// Resolved from the host OS/arch instead of hardcoding linux-x86_64 (P0.4),
// so the sysroot/libc++ wiring works on every officially supported NDK host:
// linux-x86_64, linux-aarch64 (where the NDK ships it), darwin-x86_64,
// darwin-arm64 and windows. An unsupported host fails fast with a clear
// message instead of silently pointing at a non-existent directory.
fun ndkPrebuiltDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("mac") || os.contains("darwin") ->
            if (isArm64) "darwin-arm64" else "darwin-x86_64"
        os.contains("win") -> "windows"
        os.contains("linux") -> if (isArm64) "linux-aarch64" else "linux-x86_64"
        else -> throw GradleException("Unsupported build host: $os/$arch")
    }
}

/**
 * Resolves the Android SDK directory the same way AGP does, so the NDK path can
 * be derived without `android.ndkDirectory` (removed in AGP 9).
 */
fun androidSdkDir(): String {
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotEmpty() }?.let { return it }
    project.findProperty("sdk.dir")?.toString()?.let { return it }
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return ""
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    description = "Build Rust native code via cargo-ndk"
    group = "build"

    workingDir = rootProject.projectDir   // Cargo.toml lives at project root

    // Detect the NDK from an explicit override, then from the SDK + the single
    // pinned ndkVersion declared above (which is also what CI installs).
    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: androidSdkDir().takeIf { it.isNotEmpty() }?.let { "$it/ndk/${android.ndkVersion}" }
        ?: ""
    val prebuiltDir = ndkPrebuiltDir()
    val jniLibsDir = project.file("src/main/jniLibs")
    val soFile = File(jniLibsDir, "arm64-v8a/libandroid_transcribe_app.so")

    onlyIf {
        !soFile.exists()
    }

    if (ndkDir.isNotEmpty()) {
        environment("ANDROID_NDK_HOME", ndkDir)
        environment("ANDROID_NDK_ROOT", ndkDir)
        environment("ANDROID_NDK", ndkDir)
        environment("CMAKE_ANDROID_NDK", ndkDir)
        environment("NDK_HOME", ndkDir)
        environment("CMAKE_TOOLCHAIN_FILE", "$ndkDir/build/cmake/android.toolchain.cmake")
        environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", "$ndkDir/build/cmake/android.toolchain.cmake")
        environment("CMAKE_TOOLCHAIN_FILE_aarch64_unknown_linux_android", "$ndkDir/build/cmake/android.toolchain.cmake")
        environment("CARGO_NDK_PLATFORM", "26")
        environment("RUSTFLAGS", "-C target-feature=+neon,+fp16,+dotprod -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-lc++_shared")
        environment("TRANSCRIBE_CMAKE_ARGS", "-DCMAKE_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_shared -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS_RELEASE=-O3 -DCMAKE_CXX_FLAGS_RELEASE=-O3 -DGGML_NATIVE=OFF -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF")
    }

    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )

    // Copy libc++_shared.so from NDK (needed because Rust links against it
    // dynamically). Path is host-architecture and NDK-version aware.
    doLast {
        if (ndkDir.isNotEmpty()) {
            val candidates = listOf(
                file("$ndkDir/toolchains/llvm/prebuilt/$prebuiltDir/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"),
                file("$ndkDir/toolchains/llvm/prebuilt/$prebuiltDir/sysroot/usr/lib/aarch64-linux-android/26/libc++_shared.so"),
                file("$ndkDir/sources/cxx-stl/llvm-libc++/libs/arm64-v8a/libc++_shared.so")
            )
            val libcpp = candidates.firstOrNull { it.exists() }
            val destDir = File(jniLibsDir, "arm64-v8a")
            destDir.mkdirs()
            if (libcpp != null && libcpp.exists()) {
                libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
                println("Copied libc++_shared.so from NDK at: ${libcpp.absolutePath}")
            } else {
                val found = file(ndkDir).walkTopDown().firstOrNull {
                    it.name == "libc++_shared.so" && (it.path.contains("aarch64") || it.path.contains("arm64-v8a"))
                }
                if (found != null && found.exists()) {
                    found.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
                    println("Copied fallback libc++_shared.so from NDK at: ${found.absolutePath}")
                } else {
                    println("WARNING: libc++_shared.so not located in NDK directory, proceeding")
                }
            }
        }
    }

    outputs.dir(jniLibsDir)
}

// Wire the cargo-ndk build into the Android build lifecycle for APK builds
// (skipping heavy Rust compilation during unit testing/linting to conserve CPU).
val isUnitTestTask = gradle.startParameter.taskNames.any {
    it.contains("test", ignoreCase = true) || it.contains("lint", ignoreCase = true)
}
if (!isUnitTestTask) {
    tasks.named("preBuild") {
        dependsOn(cargoNdkBuild)
    }
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------
// The bundled model is only downloaded for release builds. Debug builds ship
// without it to keep the APK under Telegram's 50 MB file-size limit; the app
// downloads the model from Hugging Face on first run.

data class ModelFile(val name: String, val sha256: String)

// The bundled GGUF goes into the model_assets asset pack so the base module
// stays under the Play Store 200 MB compressed-download limit.
//
// Default model: Nemotron 3.5 ASR Streaming 0.6B in Q8_0 (the quantization
// with the best WER/quality trade-off per the handy-computer model card).
// Cache-aware streaming + native language detection (40 language-locales);
// the engine falls back to the device-locale hint for Canary-family models
// without native detection. SHA-256 is the HF LFS oid of the file.
val modelPackFiles = listOf(
    ModelFile("nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
        "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c"),
)

val huggingFaceRepo = "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main"

fun downloadToDir(assetsDir: File, files: List<ModelFile>) {
    assetsDir.mkdirs()
    // Remove stale GGUF files not in the current list: an app upgrade can
    // swap the bundled model, and a leftover old file would otherwise be
    // picked up by the engine's single-GGUF lookup.
    assetsDir.listFiles()?.forEach { f ->
        if (f.isFile && f.extension.equals("gguf", ignoreCase = true)
            && files.none { it.name == f.name }) {
            println("  - removing stale model asset ${f.name}")
            f.delete()
        }
    }
    files.forEach { model ->
        val destFile = File(assetsDir, model.name)
        if (destFile.exists() && model.sha256.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(destFile).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == model.sha256) {
                println("  ✓ ${model.name} already downloaded and verified")
                return@forEach
            } else {
                println("  ✗ ${model.name} checksum mismatch, re-downloading...")
                destFile.delete()
            }
        }

        if (!destFile.exists()) {
            println("  ↓ Downloading ${model.name}...")
            val downloadUrl = "$huggingFaceRepo/${model.name}?download=true"
            val proc = ProcessBuilder("curl", "-L", "-f", "-o", destFile.absolutePath, downloadUrl)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download ${model.name} (curl exit code $exitCode)")
            }

            if (model.sha256.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(destFile).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != model.sha256) {
                    throw GradleException(
                        "Checksum verification failed for ${model.name}:\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                    )
                }
                println("  ✓ ${model.name} verified")
            }
        }
    }
}

val downloadModels = tasks.register("downloadModels") {
    description = "Download the built-in speech model (GGUF)"
    group = "build"

    // The GGUF -> asset pack (separate install-time delivery)
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    outputs.dir(packAssetsDir)

    doLast {
        downloadToDir(packAssetsDir, modelPackFiles)
    }
}

// QA gate that mirrors Handy-Android's `checkModelCatalog` (AGENTS.md §3
// "Validación y estilo"). It verifies the SHA-256 of every *present* bundled
// model asset against the hash declared in `modelPackFiles` and fails the build
// on any mismatch. When no asset is present (e.g. a plain debug assemble, where
// `downloadModels` is intentionally skipped to keep the APK small) it is a safe
// no-op and the runtime download path is responsible for fetching/verifying.
val checkModels = tasks.register("checkModels") {
    description = "QA gate: verify bundled model asset SHA-256 matches declared hash"
    group = "verification"

    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    doLast {
        var checked = 0
        for (model in modelPackFiles) {
            val asset = File(packAssetsDir, model.name)
            if (!asset.exists()) continue
            checked++

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(asset).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != model.sha256) {
                throw GradleException(
                    "checkModels: checksum mismatch for ${model.name}\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                )
            }
            println("  checkModels: \u2713 ${model.name} SHA-256 verified")
        }
        if (checked == 0) {
            println("checkModels: no bundled model asset present (verification skipped; runtime downloads verify on first run)")
        }
    }
}

// Run the model-hash gate as part of `check` so CI invokes it alongside tests.
tasks.named("check") {
    dependsOn(checkModels)
}

// ---------------------------------------------------------------------------
// Artifact-level model gates
// ---------------------------------------------------------------------------
// `checkModels` above validates the *source* asset. That is necessary but not
// sufficient: a correct source file proves nothing about what ended up inside
// the thing we are about to publish, and the packaging path is exactly where the
// APK/AAB divergence lives. These two gates therefore open the produced archive
// and assert the model is inside it — and, for the AAB, that it is inside it
// exactly once (a second copy in the base module would be delivered twice).
//
// Each gate is wired as a dependency of the lifecycle task that produces its
// artifact, so a release artifact cannot be produced without its gate having
// run and passed. A failure therefore names the delivery problem instead of
// silently shipping a smaller APK.
// Read once, at configuration time (a CLI property cannot change mid-build).
val allowModelLessReleaseApk =
    (providers.gradleProperty("auratranscribe.allowModelLessReleaseApk").orNull ?: "false")
        .toBoolean()

val verifyReleaseApkModel = tasks.register("verifyReleaseApkModel") {
    description = "Gate: the release APK must contain the bundled GGUF exactly once"
    group = "verification"
    dependsOn("packageRelease")

    val outputsDir = layout.buildDirectory.dir("outputs/apk/release")
    val modelSourceDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    doLast {
        val artifacts = newestArchives(outputsDir.get().asFile, ".apk")
        if (artifacts.isEmpty()) {
            throw GradleException(
                "verifyReleaseApkModel: packageRelease produced no APK in " +
                    "${outputsDir.get().asFile}. The gate cannot verify anything."
            )
        }
        val expected = modelPackFiles.associate { it.name to File(modelSourceDir, it.name).length() }
        artifacts.forEach { apk ->
            verifyModelInArchive(
                apk, expected, "APK",
                allowModelLess = allowModelLessReleaseApk,
                optOutProperty = "auratranscribe.allowModelLessReleaseApk",
            )
        }
    }
}

val verifyReleaseBundleModel = tasks.register("verifyReleaseBundleModel") {
    description = "Gate: the release AAB must carry the bundled GGUF in the asset pack, exactly once"
    group = "verification"
    // `packageReleaseBundle` only writes an *intermediate* bundle
    // (intermediates/intermediary_bundle/release/packageReleaseBundle/…). The
    // file under outputs/bundle/release is written by `signReleaseBundle`, which
    // also runs when there is no signing config (it then just aligns/copies).
    // Depending on the packaging task alone opened the output path while it was
    // still being written and failed with "zip END header not found"; verified
    // empirically by inspecting the bundle intermediates directory.
    dependsOn("signReleaseBundle")

    val outputsDir = layout.buildDirectory.dir("outputs/bundle/release")
    val modelSourceDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    doLast {
        val artifacts = newestArchives(outputsDir.get().asFile, ".aab")
        if (artifacts.isEmpty()) {
            throw GradleException(
                "verifyReleaseBundleModel: packageReleaseBundle produced no AAB in " +
                    "${outputsDir.get().asFile}. The gate cannot verify anything."
            )
        }
        val expected = modelPackFiles.associate { it.name to File(modelSourceDir, it.name).length() }
        // No opt-out here: an AAB without the model is not a valid delivery for
        // this app, and its whole point is the install-time asset pack.
        artifacts.forEach { aab ->
            verifyModelInArchive(aab, expected, "AAB", allowModelLess = false, optOutProperty = null)
        }
    }
}

// Wire the gates into the lifecycle tasks that produce their artifact. These are
// dependencies rather than finalizers on purpose: a failure has to fail the task
// the user actually asked for.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyReleaseApkModel)
}
tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseBundleModel)
}

// ---------------------------------------------------------------------------
// Regression gate for the packaging decision and the model verifier
// ---------------------------------------------------------------------------
// The APK/AAB hazard is only really fixed if a future edit cannot reintroduce it,
// so this gate drives the *same* `packagingDecision()` the build itself uses and
// the *same* `verifyModelInArchive()` that guards the release artifacts, against a
// table of cases and a set of synthetic archives.
//
// Both halves matter and neither is a copy of the implementation:
//   * the decision table pins the invocation classification (APK-only, AAB-only,
//     combined, debug-only, verification-only, aggregators, no tasks at all);
//   * the archive table pins accept/reject behaviour for a correctly placed
//     model, an asset-pack AAB, a model delivered twice (base + pack), a model in
//     the wrong directory, a truncated entry, and a missing model — including the
//     documented model-less escape hatch, which must only be honoured when it is
//     explicitly enabled.
//
// It is a Gradle task rather than a JVM unit test on purpose: the logic lives in
// this script, and a JVM test could only ever exercise a re-implementation. It
// needs no model download, no SDK and no native build — it writes a handful of
// tiny zips into the build directory.
val verifyPackagingDecision = tasks.register("verifyPackagingDecision") {
    description =
        "QA gate: exercise the APK/AAB packaging decision and the bundled-model verifier"
    group = "verification"

    doLast {
        runPackagingDecisionGate(layout.buildDirectory.dir("tmp/packaging-decision-gate").get().asFile)
    }
}

// Native packaging gate: every shipped .so must be 16 KB page-size aligned
// (Android 17 requirement). `extractNativeLibs=false` + a 16 KB max-page-size
// link is what makes this true; this task proves it on the built artifact
// instead of trusting the flags.
//
// IMPORTANT (Gradle Kotlin DSL): nothing may be registered *after* the last
// top-level `fun` declaration in this script. A script body stops being
// executed at that point, so every `tasks.register { }` must appear above the
// helper functions, which are therefore kept at the very bottom of the file.
// The original bug was exactly this: `checkNativeAlignment` was registered
// below `verifyNativeAlignment()`, so it never existed as a task at all and
// `check` silently did not depend on it.
val checkNativeAlignment = tasks.register("checkNativeAlignment") {
    description = "QA gate: verify native libraries are 16 KB page-size aligned"
    group = "verification"

    // The gate inspects AGP's *merged* native libraries rather than a previously
    // packaged APK. The merged output is the exact set of .so files that gets
    // copied into the APK/AAB, and it is regenerated for every variant this
    // build produces. Scanning a stale APK left over from an earlier build
    // would fail the gate for a reason unrelated to the artifact being built
    // now - which is precisely what happened when mlkit-translate was upgraded:
    // the leftover debug APK still contained the old 4 KB-aligned library.
    val projectLibs = file("src/main/jniLibs/arm64-v8a")
        .listFiles { f -> f.extension == "so" }
        ?.sorted()
        ?: emptyList()
    val mergedNativeLibs = layout.buildDirectory.dir("intermediates/merged_native_libs")

    doLast {
        var checked = 0

        // 1. Project-built libraries (the Rust engine and the NDK libc++). A
        //    failure here is a build-configuration bug. This source needs no
        //    freshness guard: it is written by cargoNdkBuild immediately before
        //    anything packages it.
        projectLibs.forEach { so ->
            verifyNativeAlignment(so, so.name)
            checked++
        }

        // 2. Dependency-contributed libraries, taken from the merged output of
        //    every variant (the merge tasks are forced to run first, below).
        //    This is the source that caught mlkit-translate 17.0.2 shipping a
        //    4 KB-aligned libtranslate_jni.so (fixed in 17.0.3).
        mergedNativeLibs.get().asFile.listFiles()?.forEach { variant ->
            variant.listFiles()?.forEach { mergeTask ->
                val arm64 = File(mergeTask, "out/lib/arm64-v8a")
                arm64.listFiles { f -> f.extension == "so" }?.forEach { so ->
                    verifyNativeAlignment(so, "${variant.name}/arm64-v8a/${so.name}")
                    checked++
                }
            }
        }

        if (checked == 0) {
            throw GradleException(
                "checkNativeAlignment: no native libraries found to verify. " +
                    "The Rust engine is required; run a build that invokes cargoNdkBuild first."
            )
        }
    }
}

// Force every variant's native-library merge to run before the gate inspects its
// output, so a stale directory from an earlier build can never be reported as a
// failure of the current one. This is also what makes a plain `assembleDebug` /
// `assembleRelease` verify its own package (the merge tasks are direct inputs of
// the packaging tasks).
val mergeNativeLibsTasks = tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("NativeLibs")
}

checkNativeAlignment.configure {
    dependsOn(mergeNativeLibsTasks)
}

tasks.named("check") {
    dependsOn(checkNativeAlignment)
    dependsOn(verifyPackagingDecision)
}

// Only download the bundled model when the invocation actually produces a
// release artifact. Debug-only and verification-only runs skip the 750 MB
// download to keep CI fast; the app downloads the model at runtime instead.
//
// The previous test was `taskNames.any { it.contains("Debug") }`, which skipped
// the download for `assembleDebug assembleRelease` — so the *release* APK in
// that invocation was built without the model, silently, exactly like the
// bundle/APK confusion above. Requiring a release-producing task (and not a
// lint/test-only run) is the precise condition.
val isVerificationOnlyRun = requestedTaskNames.isNotEmpty() && requestedTaskNames.all { name ->
    name.contains("lint", ignoreCase = true) ||
        name.contains("test", ignoreCase = true) ||
        name.contains("checkModels", ignoreCase = true)
}
val buildsReleaseArtifact = requestedTaskNames.any { name ->
    name.contains("Release", ignoreCase = true) || name.contains("bundle", ignoreCase = true)
}
if (buildsReleaseArtifact && !isVerificationOnlyRun) {
    tasks.named("preBuild") {
        dependsOn(downloadModels)
    }
}

// ---------------------------------------------------------------------------
// Helper functions. Keep them LAST: the script body is not executed past the
// final top-level `fun` (see the note above `checkNativeAlignment`).
// ---------------------------------------------------------------------------

/**
 * Reads the alignment of every PT_LOAD segment of an ELF shared object.
 *
 * The alignment is the LAST whitespace-separated token of a `readelf -l` LOAD
 * row, e.g.
 *
 *   LOAD  0x0b1f80 0x0b5f80 0x0b5f80 0x258480 0x258480 R E 0x4000
 *
 * (Type, Offset, VirtAddr, PhysAddr, FileSiz, MemSiz, Flg, Align). Note the Flg
 * column may itself contain a space ("R E"), which is why the row is split on
 * whitespace and only the tail token is read.
 */
fun readLoadAlignments(so: File): List<Long> {
    val process = ProcessBuilder("readelf", "-lW", so.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    val aligns = mutableListOf<Long>()
    for (line in output.lineSequence()) {
        if (!line.trimStart().startsWith("LOAD")) continue
        val last = line.trim().split(Regex("\\s+")).lastOrNull() ?: continue
        val value = last.removePrefix("0x").toLongOrNull(16) ?: continue
        aligns.add(value)
    }
    return aligns
}

/**
 * Throws when any PT_LOAD segment of [so] is aligned to less than 16 KB.
 *
 * Split out as a top-level function on purpose: a local function declared inside
 * a `tasks.register { }` configuration lambda silently prevented the task from
 * being registered at all in the Kotlin DSL, which made the gate invisible
 * rather than failing loudly.
 */
fun verifyNativeAlignment(so: File, label: String) {
    val aligns = readLoadAlignments(so)
    val min = aligns.minOrNull()
    if (min == null) {
        throw GradleException("checkNativeAlignment: no LOAD segments found in $label")
    }
    if (min < 16384) {
        throw GradleException(
            "checkNativeAlignment: $label has a LOAD alignment of $min bytes (< 16384). " +
                "Android 17 requires 16 KB page-size aligned native libraries. " +
                "For a dependency library, upgrade it to a release that is 16 KB aligned."
        )
    }
    println("  checkNativeAlignment: OK $label LOAD alignment ${min}B")
}

/**
 * Returns the newest archive with [extension] inside [dir], as a single-element
 * list (or an empty list when there is none).
 *
 * Only the newest file is returned on purpose. The packaging output directory is
 * not cleaned between builds, so an older APK/AAB — possibly one produced by an
 * earlier, broken invocation — can still be sitting there. Verifying every file
 * found would fail the gate for a reason that has nothing to do with the
 * artifact this build just produced; verifying the newest one tests exactly what
 * was packaged now. Extra files are reported so a stale artifact is still
 * visible rather than silently ignored.
 */
fun newestArchives(dir: File, extension: String): List<File> {
    val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(extension) }
        ?.sortedByDescending { it.lastModified() }
        ?: return emptyList()
    if (candidates.isEmpty()) return emptyList()
    val newest = candidates.first()
    if (candidates.size > 1) {
        println(
            "  newestArchives: ${candidates.size} *$extension files in ${dir.name}; " +
                "verifying the newest (${newest.name}); others: " +
                candidates.drop(1).joinToString(", ") { it.name }
        )
    }
    return listOf(newest)
}

/**
 * Opens [archive] and asserts that every model in [expected] (asset name to
 * uncompressed byte length) is packaged exactly once under
 * `assets/builtin-model/`.
 *
 * The path suffix is matched rather than an absolute entry name because an APK
 * stores `assets/builtin-model/…` while an AAB stores the same file as
 * `model_assets/assets/builtin-model/…` (asset-pack module) or
 * `base/assets/builtin-model/…` (base module). Matching the suffix also means
 * the "delivered twice" case — the model merged into the base module *and* kept
 * in the asset pack — is counted as two hits and rejected, instead of being
 * quietly tolerated.
 *
 * Deliberately not a `tasks.register { }`-local function: see the note above
 * `checkNativeAlignment` about declarations truncating the Kotlin DSL script.
 */
fun verifyModelInArchive(
    archive: File,
    expected: Map<String, Long>,
    kind: String,
    allowModelLess: Boolean,
    optOutProperty: String?,
) {
    ZipFile(archive).use { zip ->
        for ((modelName, expectedBytes) in expected) {
            val suffix = "assets/builtin-model/$modelName"
            val matches = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(suffix) }
                .toList()

            if (matches.isEmpty()) {
                if (allowModelLess) {
                    println(
                        "  $kind ${archive.name}: WARNING no $modelName inside — allowed by " +
                            "-P$optOutProperty=true. This artifact is NOT distributable."
                    )
                    continue
                }
                val cannotBeMissing = if (optOutProperty == null) {
                    "There is no opt-out for this delivery path ($kind): the install-time asset " +
                        "pack is the only correct way to ship it."
                } else {
                    "To build an artifact WITHOUT the model for local testing only, pass " +
                        "-P$optOutProperty=true — never distribute that one."
                }
                throw GradleException(
                    "$kind ${archive.name} does not contain $suffix. A release $kind must ship " +
                        "the bundled model. If the GGUF was never fetched, run " +
                        "`./gradlew downloadModels` (a release build wires it into preBuild " +
                        "automatically). If the model was removed from " +
                        "model_assets/src/main/assets/builtin-model by hand, restore it. " +
                        cannotBeMissing
                )
            }
            if (matches.size > 1) {
                throw GradleException(
                    "$kind ${archive.name} contains $modelName ${matches.size} times " +
                        "(${matches.joinToString(", ") { it.name }}). The model must be delivered " +
                        "exactly once: an APK carries it in the base module, an AAB only in the " +
                        ":model_assets asset pack. Packaging both copies ships ~750 MB twice."
                )
            }

            val entry = matches.first()
            if (expectedBytes > 0 && entry.size != expectedBytes) {
                throw GradleException(
                    "$kind ${archive.name} packages $suffix at ${entry.size} bytes, but the source " +
                        "asset is $expectedBytes bytes. The packaged model is truncated or stale; " +
                        "re-run `./gradlew downloadModels` (it verifies the SHA-256 on download)."
                )
            }
            println(
                "  $kind ${archive.name}: OK ${entry.name} (${entry.size} bytes, " +
                    "delivered once)"
            )
        }
    }
}

/**
 * Splits an invocation's task names into `(apkProducers, bundleProducers)`.
 *
 * Accepts raw Gradle names (`:app:assembleRelease`) as well as already stripped
 * ones (`assembleRelease`): the configuration block passes stripped names, but
 * the regression table below deliberately exercises both, so the function must
 * not depend on its caller having normalised them.
 *
 * A name is a bundle producer if it mentions `bundle` (bundleRelease, bundle,
 * installReleaseBundle, publishReleaseBundle, …). It is an APK producer if it
 * looks like packaging/lifecycle work but does not mention `bundle` — `assemble`
 * and `install` cover assembleRelease and the installRelease* tasks, `package`
 * covers the packaging tasks, and the bare `build` aggregator builds every
 * variant including release. Anything else (`clean`, `test*`, `lint*`,
 * `checkModels`, …) belongs to neither set.
 *
 * Deliberately conservative: `assembleDebug bundleRelease` is reported as
 * ambiguous even though the debug APK needs no model, because guessing which
 * artifact the caller meant is what produced the original 32 MB model-less APK.
 */
fun packagingTaskSets(taskNames: List<String>): Pair<List<String>, List<String>> {
    val bare = taskNames.map { it.substringAfterLast(':') }
    val bundles = bare.filter { it.contains("bundle", ignoreCase = true) }
    val apks = bare.filter { name ->
        !name.contains("bundle", ignoreCase = true) && (
            name.startsWith("assemble") ||
                name.startsWith("install") ||
                name.startsWith("package") ||
                name == "build"
            )
    }
    return apks to bundles
}

/**
 * The complete APK-vs-app-bundle decision for one Gradle invocation:
 * `Triple(apkProducers, bundleProducers, mode)` where `mode` is `"apk"`,
 * `"bundle"`, `"ambiguous"` or `"neither"`.
 *
 * `"ambiguous"` is the state the configuration block refuses to build: a single
 * invocation that asks for both artifacts cannot give the APK its base-module
 * model and keep the AAB's model only in the asset pack at the same time.
 *
 * Declared as a function rather than a top-level `val` on purpose: script-level
 * properties are initialised in declaration order, so one declared at the bottom
 * of this file would still be null where the decision is consumed near the top.
 */
fun packagingDecision(taskNames: List<String>): Triple<List<String>, List<String>, String> {
    val (apks, bundles) = packagingTaskSets(taskNames)
    val mode = when {
        apks.isNotEmpty() && bundles.isNotEmpty() -> "ambiguous"
        bundles.isNotEmpty() -> "bundle"
        apks.isNotEmpty() -> "apk"
        else -> "neither"
    }
    return Triple(apks, bundles, mode)
}

/** Writes a tiny ZIP containing exactly [entries] (entry path to raw content). */
fun writeProbeArchive(target: File, entries: List<Pair<String, ByteArray>>) {
    target.parentFile?.mkdirs()
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        for ((path, bytes) in entries) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/**
 * Runs [verifyModelInArchive] over a probe archive and asserts the outcome.
 *
 * [shouldPass] is the contract under test in BOTH directions: a gate that
 * suddenly accepts a model-less artifact is as broken as one that rejects a
 * correct one, and only asserting the happy path would hide the first.
 */
fun expectModelGateOutcome(
    archive: File,
    expected: Map<String, Long>,
    allowModelLess: Boolean,
    label: String,
    shouldPass: Boolean,
) {
    val outcome = runCatching {
        verifyModelInArchive(
            archive,
            expected,
            "PROBE",
            allowModelLess,
            if (allowModelLess) "auratranscribe.allowModelLessReleaseApk" else null,
        )
    }
    if (shouldPass && outcome.isFailure) {
        throw GradleException(
            "verifyPackagingDecision: '$label' must pass the bundled-model gate, but it was " +
                "rejected: ${outcome.exceptionOrNull()?.message ?: "no message"}"
        )
    }
    if (!shouldPass && outcome.isSuccess) {
        throw GradleException(
            "verifyPackagingDecision: '$label' must be REJECTED by the bundled-model gate, but it " +
                "passed. The gate no longer protects against this packaging mistake."
        )
    }
}

/**
 * Body of the `verifyPackagingDecision` gate: drives the real
 * [packagingDecision] and the real [verifyModelInArchive] over a fixed table.
 *
 * Everything here is synthetic and microscopic (a few 4 KB zips in the build
 * directory), so the gate runs in every CI job without downloading the 750 MB
 * model or building a single native library.
 */
fun runPackagingDecisionGate(workDir: File) {
    workDir.mkdirs()

    // ---- 1. Which artifact is this invocation producing? -------------------
    val invocationCases = listOf(
        Triple(listOf("assembleRelease"), "apk", "APK-only release build"),
        Triple(listOf(":app:assembleRelease"), "apk", "APK-only release build with a project prefix"),
        Triple(listOf("bundleRelease"), "bundle", "app-bundle-only release build"),
        Triple(listOf(":app:bundleRelease"), "bundle", "app-bundle-only build with a project prefix"),
        Triple(listOf("installReleaseBundle"), "bundle", "bundle install task"),
        Triple(listOf("publishReleaseBundle"), "bundle", "bundle publish task"),
        Triple(listOf("assembleRelease", "bundleRelease"), "ambiguous", "APK and bundle at once"),
        Triple(listOf("assembleDebug", "bundleRelease"), "ambiguous", "debug APK and release bundle at once"),
        Triple(listOf("assembleDebug"), "apk", "debug-only build"),
        Triple(listOf("installDebug"), "apk", "debug install"),
        Triple(listOf("assembleBenchmark"), "apk", "benchmark-variant build"),
        Triple(listOf("assemble"), "apk", "bare assemble aggregator"),
        Triple(listOf("build"), "apk", "bare build aggregator"),
        Triple(listOf("clean", "assembleRelease"), "apk", "clean plus a release build"),
        Triple(listOf("testDebugUnitTest", "lintDebug"), "neither", "verification-only run"),
        Triple(listOf("checkModels"), "neither", "bundled-model hash check alone"),
        Triple(emptyList(), "neither", "no task names (IDE sync / tooling)"),
    )
    for ((taskNames, expectedMode, label) in invocationCases) {
        val (apks, bundles, mode) = packagingDecision(taskNames)
        if (mode != expectedMode) {
            throw GradleException(
                "verifyPackagingDecision: '$label' ${taskNames} was classified as '$mode' " +
                    "but must be '$expectedMode' (apk=$apks, bundle=$bundles). This is the rule " +
                    "that stops a model-less release APK from being packaged silently."
            )
        }
    }
    println("  verifyPackagingDecision: ${invocationCases.size} invocation cases classified correctly")

    // ---- 2. Does the model verifier accept and reject the right archives? ---
    val sourceDir = File(workDir, "source").apply { mkdirs() }
    val modelName = "synthetic-model.gguf"
    val secondModelName = "synthetic-second-model.gguf"
    val modelBytes = ByteArray(4096) { index -> (index % 251).toByte() }
    val secondModelBytes = ByteArray(512) { index -> (index % 97).toByte() }
    File(sourceDir, modelName).writeBytes(modelBytes)
    File(sourceDir, secondModelName).writeBytes(secondModelBytes)

    val oneModel = mapOf(modelName to modelBytes.size.toLong())
    val bothModels = mapOf(
        modelName to modelBytes.size.toLong(),
        secondModelName to secondModelBytes.size.toLong(),
    )
    val baseModulePath = "assets/builtin-model/$modelName"
    val assetPackPath = "model_assets/assets/builtin-model/$modelName"

    // APK-shaped archive: the model inside the base module.
    val apkArchive = File(workDir, "apk-ok.zip")
    writeProbeArchive(apkArchive, listOf(baseModulePath to modelBytes))
    expectModelGateOutcome(
        apkArchive, oneModel, false,
        "APK with the model in the base module", true,
    )

    // AAB-shaped archive: the model only in the :model_assets pack.
    val aabArchive = File(workDir, "aab-ok.zip")
    writeProbeArchive(aabArchive, listOf(assetPackPath to modelBytes))
    expectModelGateOutcome(
        aabArchive, oneModel, false,
        "AAB with the model only in the :model_assets pack", true,
    )

    // Delivered twice (base module plus asset pack): the user downloads ~750 MB twice.
    val duplicated = File(workDir, "aab-duplicated.zip")
    writeProbeArchive(
        duplicated,
        listOf(assetPackPath to modelBytes, "base/assets/builtin-model/$modelName" to modelBytes),
    )
    expectModelGateOutcome(
        duplicated, oneModel, false,
        "AAB with the model in both the base module and the asset pack", false,
    )

    // Present, but somewhere the runtime never looks it up.
    val misplaced = File(workDir, "wrong-directory.zip")
    writeProbeArchive(misplaced, listOf("assets/model/$modelName" to modelBytes))
    expectModelGateOutcome(
        misplaced, oneModel, false,
        "archive with the model outside assets/builtin-model", false,
    )

    // Right place, wrong bytes (a truncated or stale copy).
    val truncated = File(workDir, "truncated.zip")
    writeProbeArchive(truncated, listOf(baseModulePath to modelBytes.copyOf(64)))
    expectModelGateOutcome(
        truncated, oneModel, false,
        "archive whose packaged model is truncated", false,
    )

    // No model at all — and the documented escape hatch must honour it only when set.
    val missing = File(workDir, "no-model.zip")
    writeProbeArchive(missing, listOf("assets/unrelated.txt" to ByteArray(8)))
    expectModelGateOutcome(
        missing, oneModel, false,
        "archive with no model at all", false,
    )
    expectModelGateOutcome(
        missing, oneModel, true,
        "model-less archive with -Pauratranscribe.allowModelLessReleaseApk=true", true,
    )

    // Every declared model must be present, not just the first one in the map.
    val multiOk = File(workDir, "multi-ok.zip")
    writeProbeArchive(
        multiOk,
        listOf(
            baseModulePath to modelBytes,
            "assets/builtin-model/$secondModelName" to secondModelBytes,
        ),
    )
    expectModelGateOutcome(
        multiOk, bothModels, false,
        "archive with every declared model present", true,
    )

    val multiIncomplete = File(workDir, "multi-incomplete.zip")
    writeProbeArchive(multiIncomplete, listOf(baseModulePath to modelBytes))
    expectModelGateOutcome(
        multiIncomplete, bothModels, false,
        "archive missing one of the declared models", false,
    )

    println("  verifyPackagingDecision: 9 archive cases verified (accept and reject)")
}
