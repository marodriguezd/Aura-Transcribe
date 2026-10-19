# AGENTS.md — Guide for AI Assistants

> Instruction file for AI agents working in this repository. It defines the stack, commands, conventions, and critical rules that must **never** be broken. Equivalent to the standard `AGENTS.md` (the same role `CONTRIBUTING.md` plays for humans, but aimed at AIs).

> **Repo:** `Aura-Transcribe` (fork of `notune/android_transcribe_app`)
> **Type:** Android app for *offline* voice transcription with optional AI post-processing.
> **Currently declared Gradle version:** 1.0.0 (`versionCode 50`, see `app/build.gradle.kts`). The actually published version must be checked in tags/releases; do not assume it matches this file.
>
> **Language (decision 2026-08-04, completed 2026-10-12):** all agentic files
> (this `AGENTS.md`) is written in **English**. (`.ai_context/` and
> `agent_prompt.md` are not present in this repository.)
> Multilingual *product* support stays referenced throughout: the ES+EN phonetic
> encoder (seseo/yeísmo, `ñ`→`ny`), the 8 locales, diacritics-aware language
> resolution, and the per-language WER table in §5.2 are features, not violations —
> never "fix" non-English test fixtures or phonetic rules into English-only forms.

> **Scope:** this file documents the **implementation contract** (pinned versions,
> JNI handshake, critical rules, new-component templates).
> The human face of the project — step-by-step installation, prerequisites, prose
> feature list, screenshots, acknowledgements, license — lives in [`README.md`](README.md).
> If in doubt about where something goes: if it changes the **face** → README; if it
> changes **how it is built** → AGENTS.md.

---

## 1. Project Overview

Android app that turns voice into text **100% on-device**. It is exposed to the system in three different ways (to maximize compatibility with keyboards/apps):

| Surface | How it is triggered | Key files |
|---|---|---|
| **Voice popup** | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (SwiftKey-style keyboards, web voice search) | `RecognizeActivity.java` + `src/recognize.rs` |
| **Recognition service** | `SpeechRecognizer` (Android) — other keyboards/apps use it as STT | `VoiceRecognitionService.java` + `src/recog_service.rs` |
| **IME (own keyboard)** | `BIND_INPUT_METHOD` (HeliBoard/FlorisBoard/etc.) | `RustInputMethodService.java` + `src/ime.rs` |

Additional features: **live subtitles** over system audio (`LiveSubtitleService.java` + `src/subtitle.rs`), **GGUF model management** (`ModelsActivity.java` + `src/models.rs`), **floating overlay** (`FloatingOverlayService.java`) and **file transcription** (`TranscribeFileActivity.java`).

AI post-processing (fork addition): optional, *off-line-by-default*, refines text with any OpenAI-compatible LLM (`PostProcessor.java`, settings in `PostProcessSettingsActivity.java`).

**Overall architecture: A SINGLE PROCESS.** The whole app (MainActivity, IME, recognition service, overlay, subtitles) runs in the default process: `AndroidManifest.xml` declares no `android:process` (the historical claim of an isolated `:ime` process was false and never implemented — corrected 2026-10). All heavy logic (ASR, audio capture, segmentation, JNI) is in **Rust** compiled as a `cdylib` (`Cargo.toml`) and linked via JNI from Java. Settings are stored in *marker* files under `getFilesDir()` (no `SharedPreferences`) because the Rust engine only reads from the *filesystem* and they must be readable no matter which component started first.

> ⚠️ **DO NOT add `android:process=":ime"`.** It would be tempting to "isolate" the keyboard, but it would break two real invariants: `voice_session.rs` **deliberately shares a single `cpal::Stream`** between the IME and the popup, and the engine lives in a global `Arc<Mutex<Engine>>`. Splitting processes would duplicate the engine and break that sharing.

---

## 2. Technology Stack

| Layer | Technology | Version / Note |
|---|---|---|
| Native language | **Rust** | edition `2021`, `crate-type = ["cdylib"]` |
| ASR backend | [`transcribe-cpp`](https://github.com/handy-computer/transcribe.cpp) (ggml + whisper.cpp fork) | `0.1.3` |
| Audio capture | [`cpal`](https://github.com/RustAudio/cpal) | `0.15` |
| Rust ⇄ Java channels | [`jni`](https://github.com/jni-rs/jni) | `0.21` |
| Concurrency | `crossbeam-channel`, `once_cell`, `std::sync` (Arc/Mutex/atomic) | — |
| Native logging | `log` + `android_logger` | max level `Info` |
| Errors | `anyhow` (Rust), `try/catch` + `Throwable` (Java) | — |
| UI language | **Java** (no Kotlin) | Java 17 source/target |
| Android Gradle Plugin | `com.android.application` | **9.4.1** — required for API 37, see note |
| Build tool | Gradle wrapper | **9.8.0** (see `gradle/wrapper/gradle-wrapper.properties`) |
| Human toolchain | JDK 17 · Android NDK **28.2.13676358** (unified across Gradle = CI = README) · Rust `aarch64-linux-android` · [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) | Supported hosts: linux-x86_64, darwin-x86_64, darwin-arm64, windows (see `ndkPrebuiltDir()` in `app/build.gradle.kts`); a host without a prebuilt NDK fails with a clear message |
| Target NDK ABIs | **`arm64-v8a` only** (`abiFilters += "arm64-v8a"`) | x86 / armeabi-v7a excluded; 16 KB page-size aligned (`checkNativeAlignment` gate) |
| `compileSdk` / `targetSdk` / `minSdk` | Effective Gradle: `37 / 37 / 26` | **Android 17 = API 37**; Android 15 = API 35. Covers Android 8.0 (API 26) through Android 17 |
| Release optimization | R8 (minify) + resource shrinking | `isMinifyEnabled = true`, `isShrinkResources = true`; keep rules in `app/proguard-rules.pro` (JNI + callbacks by name). `benchmark` build type + `:benchmark` module for Macrobenchmark / Baseline Profile |
| Material | Material Components for Android | `1.14.0` (Material 3 + Material You) |
| HTTP (post-processing) | OkHttp | `4.12.0` |
| API key storage | marker `pp_api_key_enc` encrypted with AES-256-GCM (`v1:` format) with key in AndroidKeyStore | `SecureCredentialStore` + `javax.crypto` from the SDK; `androidx.security.crypto` only for reading the legacy format |
| kotlin-stdlib alignment | Pinned to `1.8.22` (empty) | see `constraints` block in `app/build.gradle.kts` |

> ⚠️ **Why AGP 9.x and not 8.x:** AGP **8.13.x cannot resolve the API 37 platform**. It is proven up to compile SDK 36.1 and fails with `Failed to find target with hash string 'android-37'` because it installs the platform as `android-37.0` but looks for `android-37`. Migrating to API 37 requires the 9.x line. Do not downgrade AGP/Gradle without also downgrading `compileSdk`.

> ℹ️ **R8 / non-transitive R classes:** with `android.nonTransitiveRClass=true` (and AGP 9) Material's `R` does **not** contain attributes defined in AppCompat. Use `androidx.appcompat.R.attr.colorPrimary` / `colorError`, but `com.google.android.material.R.attr.colorOnError` / `colorPrimaryContainer` (those really are Material's). Mixing them up breaks the build.

**Critical permissions** (`AndroidManifest.xml`):
- `RECORD_AUDIO`, `INTERNET` (post-processing), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MICROPHONE`,
  `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`.
- ❌ **`READ_USER_DICTIONARY` was removed** (2026-10): the platform keeps it outside the public SDK (`android.Manifest.permission` does not expose that constant), so an app on a modern SDK can **never obtain it**. Declaring it only advertised an impossible access and the `ContentProvider` query always threw `SecurityException`. **Do not reintroduce it.**
- Backup: `allowBackup="false"` **plus** `android:dataExtractionRules` + `fullBackupContent`, which exclude everything — on Android 12+ `allowBackup=false` alone does not prevent device-to-device transfer.

**i18n:** 8 parallel locales (EN `values/`, ES `values-es/`, DE `values-de/`, FR `values-fr/`, IT `values-it/`, PT `values-pt/`, RU `values-ru/`, ZH `values-zh-rCN/`) + `values-night/` (dark styles).

---

## 3. Commands and wiring

> User-facing commands and prerequisites live in [`README.md` §Building](README.md#building)
> and [`README.md` §Prerequisites](README.md#prerequisites). This section only
> lists the **wiring decisions** an agent must respect and where they diverge
> from the general rule.

### Wiring Gradle ↔ cargo-ndk (AGENT-ONLY)

- `cargoNdkBuild` is always registered as an `Exec` task, but it is wired
  into `preBuild` only when `needsNativeBuild(requestedTaskNames)` is true
  (see the `if (needsNativeBuild(...))` block around the `preBuild`
  dependency). Test-only / lint-only / `check*` / `verify*` invocations skip
  heavy native compilation; mixed or artifact-producing invocations compile.
  The task declares Rust sources + toolchain env as inputs and the two `.so`
  files as outputs, so Gradle can skip it when nothing changed. To force a
  native rebuild, delete
  `app/src/main/jniLibs/arm64-v8a/libandroid_transcribe_app.so` (or run
  `cargo ndk -t arm64-v8a build --release` by hand).
- `app/build.gradle.kts` forces `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` via
  env in `cargoNdkBuild`. **Do not lower** this flag: ggml cross-compiles with
  baseline kernels and quantized inference drops to ≥4× slower. `check_cpu_features`
  in `engine.rs` aborts at load time if the CPU lacks dotprod+fp16 (ARMv8.2 ~2018+).
- `libc++_shared.so` is copied after every build from the NDK to
  `app/src/main/jniLibs/<abi>/` (Bionic does not bundle it and `transcribe-cpp`
  links it dynamically).
- **Bundled model delivery (APK vs AAB):** mutually exclusive mechanisms for
  the same `release` variant.
  - The **APK** must carry the GGUF inside the base module, because AGP ignores
    asset packs when packaging an APK — that is why the `if (!isBundle)` block in
    `app/build.gradle.kts` adds `model_assets/src/main/assets/` to
    `assets.srcDirs` of the `release` source set (only that source set: the debug
    APK deliberately ships without a model).
  - The **AAB** must carry the GGUF **only** in the `:model_assets` asset pack
    (`dynamicDelivery = "install-time"`), or Play would receive 560 MB duplicated.
  - An invocation requesting **both** fails at configuration time with an
    actionable message (`bundleTaskNames` and `apkTaskNames` both non-empty). It used
    to silently prefer the bundle branch and produce a 32 MB model-less APK that
    compiled "fine". **Build each artifact in its own invocation.**
  - Two artifact gates verify this against the real file, not flags:
    `verifyReleaseApkModel` (a dependency of `assembleRelease`) and
    `verifyReleaseBundleModel` (a dependency of `signReleaseBundle`). Both open
    the zip and require the model to be present **exactly once and at the exact path**
    for the artifact type (APK → `assets/builtin-model/<name>`, AAB →
    `model_assets/assets/builtin-model/<name>`); a nested path or one from another
    module that merely *ends* with that suffix is rejected, and any extra copy
    of the same file under any other path counts as a duplicate.
    `verifyReleaseApkModel` can only be skipped with
    `-Pauratranscribe.allowModelLessReleaseApk=true`, which prints a WARNING and
    only covers **model-less** artifacts (a misplaced path is never skipped);
    the AAB has no opt-out.
  - The APK/AAB decision lives in two pure functions in `app/build.gradle.kts`
    (`packagingTaskSets(taskNames)` and `packagingDecision(taskNames)` → mode
    `apk|bundle|ambiguous|neither`), and the `verifyPackagingDecision` task
    exercises them together with the model gates against **synthetic files**
    (17 representative invocations × 15 files: accepts and rejects in both
    directions, including nested paths with the correct suffix, wrong module,
    duplicates with an extra copy, and the escape hatch with a misplaced model).
    It is a hard gate in both workflows and a dependency of `check`, so a change
    to the task-name heuristics fails before anyone publishes a model-less
    artifact. Do not replace it with a test that recomputes the expected answer
    on its own: it must call the same code.
  - `scripts/verify_release_artifact.py` is the **independent** verification of
    the already-built artifact (`--kind apk|aab`, content hash against the
    source asset, rejection of missing/duplicated/misplaced/truncated/corrupt).
    Its 21 tests (`scripts/test_verify_release_artifact.py`) run as a gate in
    both workflows because they are pure Python, with no SDK or downloaded model;
    they require the exact path per artifact type and demand that a zip-entry
    read error fail closed with a diagnosis (not an uncaught traceback).
  - `downloadModels` is wired to `preBuild` when the invocation produces a
    release artifact (and is not a lint/test/`checkModels` run), not when "it
    does not contain the word Debug" — which was the previous condition and left
    the release APK of `assembleDebug assembleRelease` without a model.

### Build Pipeline & Continuous Integration (SET-IN-STONE RULE)

- 🤖 **Debug builds:** ALWAYS done via GitHub Actions (`Build Debug APK` workflow), which recompiles and automatically delivers the debug APK to the user through the Telegram bot.
- 🚀 **Release builds:** ALWAYS done via GitHub Actions (`Build Signed Release APK` workflow / `android_release.yml`) via `vX.Y.Z` tags or manual triggers.
- 🛠️ **Mandatory manual trigger (`workflow_dispatch`):** all release workflows (`android_release.yml`) must keep `workflow_dispatch` enabled in their `on:` block and `tag_name: v${{ env.VERSION_NAME }}` in the publishing step. This guarantees resilience if GitHub Actions suffers an outage or loses a tag event.
- 📝 **Release text length and conciseness (KEY RULE):** the descriptive release text (the contents of `RELEASE_NOTES.md` that CI publishes to GitHub Releases) must be **at most 125 to 250 words** long. It must be technical, concise, and focused on real impact (performance, memory, consumption, new capabilities). `RELEASE_NOTES.md` contains **only the version being published**, avoiding duplication of old history (the full historical record belongs exclusively to `CHANGELOG.md`).

### Environment-based validation rule (2026-08-06, updated 2026-10 — SET-IN-STONE RULE)

The rule depends on the **host type**, not the project. First find out where you are running:

- 📱 **Mobile / embedded-type host (e.g. an ARM64 Android userspace without KVM): NEVER run heavy local builds.** `./gradlew assembleDebug`, `cargo build`, `cargo ndk ...`, NDK `cargo check`, `assembleRelease`, etc. overload the system and can leave it unresponsive (verified 2026-08-06). On that host all build validation is done **dynamically via GitHub**: `git commit` + `git push` to `main` triggers the debug workflow (`debug_telegram.yml`), which runs every gate — `cargo fmt --check`, `check_translations.py`, `test_send_telegram.py`, `test_verify_release_artifact.py`, `bench_performance.py`, `testDebugUnitTest`, `assembleDebug` (compiles the Rust via cargo-ndk), `lintDebug`, `checkModels`, `checkNativeAlignment`, `verifyPackagingDecision` and `:benchmark:assembleBenchmark` — and sends the APK to Telegram. Read the result with `gh run list` / `gh run view` and **iterate: fix → push → read CI → repeat**. Light gates are allowed: static reading, `git diff`, `cargo fmt --check`, `python3 scripts/check_translations.py`, XML string edits.
- 💻 **Physical machine (laptop/desktop with Android SDK, NDK, Rust, and network): building locally is expected and preferred.** Here real validation is local, in this order: `./gradlew :app:testDebugUnitTest` (fast; does not trigger `cargoNdkBuild`) → `python3 scripts/check_translations.py` → `./gradlew :app:lintDebug` → `./gradlew :app:assembleDebug` (uses the already-built `.so`) → `./gradlew :app:assembleRelease` if the change touches R8. On this host type there is **no** need to wait for CI to know whether it compiles.
- 🛠️ `gh` is authenticated as the maintainer; use it to inspect CI runs when on the embedded host.

### Signing (AGENT-ONLY)

The three cases are explicit in `app/build.gradle.kts` and verified
(2026-10, x86_64 host):

| State | `./gradlew :app:assembleRelease` |
|---|---|
| Keystore **+** all 3 env vars (`KEY_ALIAS`, `KEY_PASS`, `STORE_PASS`) | Signs with `signingConfigs.release` → signed `app-release.apk` (verified with `apksigner verify`) |
| Keystore **but** some env var missing, in a task that builds release | **Fails with a clear error** (2026-08-06 hardening): never signs with default credentials |
| **No** keystore | Correct build → unsigned `app-release-unsigned.apk` |

**A correct build is NOT a publishable release.** Case 3 is what produces an
unsigned artifact: it serves F-Droid/buildserver, which recompiles from source
and signs itself, but it is **never** published from CI. The separation is explicit:

| Workflow | What it produces | Does it publish? |
|---|---|---|
| `debug_telegram.yml` | **debug** APK signed with the repo's debug key | Does not publish a release: uploads the APK as a *workflow artifact* and sends it to the Telegram bot |
| `android_release.yml` | Release APK **and** AAB signed with the project key | Yes: GitHub Release with both files, only if all gates pass |
| Source build (no keystore, e.g. F-Droid) | Unsigned `app-release-unsigned.apk` | Not a channel of this repo; whoever consumes it signs it |

Release workflow guarantees (do not relax them):

1. **Credential preflight before compiling.** All four secrets
   (`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS`, `KEY_PASS`) are checked for
   presence and the job exits `1` if any is missing. *Presence* is checked: the
   value is never printed (`"${!name:-}"`, never `echo $secret`).
2. **The model-less release escape hatch is rejected.** The `Reject the model-less
   release escape hatch` step greps for `allowModelLessReleaseApk=true` in
   `.github/workflows/` and inspects the `ORG_GRADLE_PROJECT_*` environment. The needle
   is assembled from two halves (`"allowModelLess""ReleaseApk=true"`) on purpose:
   written as one literal, the step would match itself.
3. **Never sign with the debug key.** After `assembleRelease`, the APK is validated
   with `apksigner verify --print-certs` and the step compares the signer's SHA-256
   against `keystore/debug.keystore` (`androiddebugkey`); on a match,
   `::error::` and exit `1`. The AAB goes through the equivalent with `jarsigner -verify
   -certs` + `keytool -printcert -jarfile`.
4. **An unsigned artifact is never published.** A `*-unsigned.apk` is explicitly
   rejected by name, and for the AAB the **output** of `jarsigner` is inspected
   (`jar is unsigned` → error) because `jarsigner -verify` exits `0`
   even for an unsigned bundle.
5. **Correct order.** `zipalign -c -P 16 -v 4` (Android 17's 16 KB, not the
   historical `-c 4`) and `apksigner verify` run against the already-packaged APK
   and **before** `Upload`/`Create Release`; any failure stops the job, so the
   Release never gets created.
6. **Pinned signing identity (2026-10-18).** "Signed and not the debug key" is
   not enough: the keystore decoded from the secret is checked **before the
   expensive build**, and the APK's and AAB's signer certificates are checked
   after packaging, against the production certificate fingerprint pinned in
   `keystore/release-cert.sha256` (extracted from the published v1.0.0
   APK/AAB — an independent trust anchor, never derived from the artifact under
   test). Any missing, mismatched or unexpected certificate fails the job via
   `scripts/verify_signing_identity.py`. Rotation is a reviewed change to the
   pinned file, never a weakened workflow step.

> ⚠️ **`set -e -o pipefail` in every step that uses `| tee`.** Without `pipefail`
> the step status is `tee`'s (always 0): the `debug_telegram.yml` test gate "passed"
> unconditionally. There was a real case of this (2026-10-09).

- ⚠️ **The release `signingConfig` is only attached to the build type when the keystore
exists** (`hasReleaseKeystore`). Attaching a `signingConfig` whose `storeFile` was never
assigned makes AGP abort in `validateSigningRelease` with *"Keystore file not
set for signing config release"*: that broke `assembleRelease` on a clean clone
**and on the F-Droid buildserver**, which compiles from source and signs itself.
Do not bind `signingConfigs.getByName("release")` unconditionally again.
- In CI the keystore is base64-decoded from a repo secret (`KEYSTORE_BASE64`)
  **after** the preflight and before any release task; see
  `.github/workflows/android_release.yml` §`Decode Keystore`. The resulting
  `release.keystore` file is already in `.gitignore`.
- The **debug** keystore (`keystore/debug.keystore`, alias `androiddebugkey`,
  password `android`) is versioned on purpose: debug signing uses it, and the
  release workflow compares against it to reject a debug-key-signed APK. Its SHA-256 is
  `437be41f5719ee1e8c75fb2d005683bd97ce3daccaa615fa53eca89e7ef6e46d`
  (verified against `apksigner verify --print-certs` of `app-debug.apk`,
  2026-10). Do not replace it or use it for a distributable artifact.

### Validation and style (Harness Law / Uncle Bob's Gauntlet)

- 🛡️ **The Gauntlet Validation Rule:** per the test-harness philosophy postulated by Robert C. Martin ("Uncle Bob") for AI-assisted development, coding assistants must **not** request manual line-by-line reviews from the user. Every change must go through automated test/build gates and present empirical evidence (`BUILD SUCCESSFUL`) in the output logs before the task is declared done.
- 🛡️ **Algorithmic Invariants and Mandatory Negative Tests (2026-08-14):** any optimization or heuristic change to matching engines (phonetic corrector, VAD, tokenization, audio segmentation) must include negative test suites (verifying that unrelated everyday sentences/inputs produce 0 false positives and 0 spurious substitutions). Audited canonical libraries (`strsim`, etc.) with $O(1)$-order filters (e.g. fast length pruning) are preferred over ad-hoc matrix reimplementations without exhaustive coverage.
- **Multi-Architecture Support (ARM64 + x86_64):** the app and its build pipelines must be compatible and validatable on both ARM64 devices/host machines and x86_64 laptops/emulators.
- **Thermal and CPU Protection on Mobile:** local unit tests (`testDebugUnitTest`) run isolated in the JVM without triggering the heavy native Rust build (`cargoNdkBuild`), protecting the device's processor. Full APK/AAB binaries are produced in CI/CD (GitHub Actions). **On mobile/embedded-type hosts (2026-08-06 rule, see "Environment-based validation rule"), the agent compiles nothing locally: it validates only via push to `main` + the GitHub Actions debug workflow result.**
- **System AAPT2 Override (ARM64 Linux host only):** `android.aapt2FromMavenOverride` is **commented out** in `gradle.properties`. Uncomment it (pointing at a real `aapt2`) **only** if you are on an ARM64 Linux host where the Maven AAPT2 cannot run. Never leave it active with a hardcoded path: it historically pointed at `/data/data/com.termux/files/usr/bin/aapt2` and broke every resource task on any other host.
- **Automated validation:** run `./gradlew assembleDebug` or unit-test suites with no new warnings or failures + functional smoke test on device/emulator.
- **Java:** follow the §4 conventions.
- **Rust:** follow the existing inline style (4 spaces, default `rustfmt`).
- **Configured gates / evidence (Gauntlet OPEN):**
  - `testDebugUnitTest`: **371 `@Test` annotations (static count, re-verified 2026-10-19 on `7e68ba7`: 371 executed, 0 failures, 0 skipped, 31 suites)**; the green/failure count is reported by CI in the test log (last verified in CHANGELOG/CI logs): `ModelAvailabilityTest` (off-main-thread model probing: empty marker, name pointing at a nonexistent file, name with spaces, same-named file in the wrong directory, null `AssetManager`, `isModelReady` with the imported branch) + markers/subtitle prefs + `CallRegistryTest` (owner-based cancellation) + `MarkerAtomicityTest` (concurrent reads) + `FileSha256Test` + `AccessibilityInsertionTest` (dictation insertion at the real cursor/selection position, including unknown selection `-1`, out-of-range, inverted, and multiline) + `FileAudioChunksTest` (resampling bounded to the valid region, **upsampling** and non-integer ratios 44.1/48/32 kHz → 16 kHz, **guarantee that the output does not alias the reused chunk buffer**, 2:1/identity/degenerate lengths, out-of-range `length` clamping, no NaN/Inf; **`exceedsDurationCap` with the exact limit, one frame above, sample-rate scaling, and invalid arguments**; transcript joining without stray spaces, skipping empty fragments, preserving order across 60 chunks, and with a single chunk) + `PostProcessorTest` (HTTP suite P1.3 — payload, `stream:false`, `${output}` once, errors, fallback, real OkHttp timeout via seam with scaled values — **plus a real DNS failure with a `.invalid` host and connect timeout via seam against `192.0.2.1`**; production's 30 s/60 s are asserted, not waited out in wall-clock) + `SecureCredentialStoreTest` (crypto, `v1:` format, GCM tag, write verification, deletion that **reports** whether the file survived, and the failure paths of a `KeyProvider` throwing `RuntimeException`: converted into a typed `CredentialStoreException`, writing nothing and never falling back to plaintext, with the diagnostic carrying only the class name — never the provider message or key material) + `CredentialMigrationTest` (API-key migration orchestration: precedence across the three legacy sources, write-before-delete, `commit()` result verification, recovery when Keystore is unavailable, sentinel, explicit deletion, and **deletion that cannot complete**: returns `false` instead of lying and leaves the tombstone; runs against `CredentialMigration` with the real `SecureCredentialStore` and in-memory doubles only for the prefs sources) + `CredentialOperationsTest` (the **lane** serializing every credential mutation: an in-flight import does not resurrect a deleted key, concurrent upload and deletion leave a deterministic state, a migration failure does not revive the credential, a task throwing `RuntimeException` is contained, reported sanitized, and does not block the lane, and — on the reader side — enqueueing an upload and then reading observes the upload, enqueueing a deletion leaves reads empty, a read with the import paused sees the *pre*-mutation state and the imported value afterwards, and completions arrive once per submission and in submission order, including the failure paths) + `CredentialReadTest` (read contract: a deletion tombstone **hides every source** — encrypted store and legacy copy included —; an unreadable store is served as empty and **never** falls back to the plaintext marker; the marker is only served while no store exists; the result is never null) + `AudioRoutingPolicyTest` (23 tests, 2026-10-18 audit: `TYPE_BLE_SPEAKER` is never a capture candidate even if a HAL lies about `isSource()`; Bluetooth SCO/BLE headset/hearing-aid selection; USB/wired preference and first-match ordering in AUTO; built-in fallback; Bluetooth-only mode ignoring wired/USB; sink-only filtering; null/empty/degenerate lists; `builtinFallbackAllowed`; `bluetoothRoutingSucceeded` requiring both candidate and platform confirmation) + `PostProcessorTest` cross-host redirect tests (the `Authorization` header is **dropped on cross-host redirects** and kept on same-host ones — the pinned credential-leakage policy) + `testAcquireFailureLeavesNoRoutingState`/`testPrewarmNullSafety` in `AudioDeviceManagerTest` (a failed acquisition never marks routing active or pre-warmed; idempotent release). Do not cite `34`, `109`, `123`, `176`, `234`, `250`, or `348` tests: those numbers are historical.
  - **Privacy (2026-08-04):** the raw transcript and post-processing error details are **not logged in release** (gated with `BuildConfig.DEBUG`). Do not reintroduce transcribed-text logs without a debug gate.
  - **CI gates (updated 2026-10-09):**
    - *Both workflows, `validate` job (cheap, no model download):* `cargo fmt --all -- --check`, `cargo test --workspace`, `scripts/check_translations.py`, `scripts/test_send_telegram.py`, `scripts/test_benchmark_suite.py`, `scripts/bench_performance.py`, `scripts/test_verify_release_artifact.py`, `scripts/check_jni_parity.py` + `scripts/test_check_jni_parity.py`, `scripts/test_verify_signing_identity.py`, `scripts/test_check_repo_hygiene.py` + `scripts/check_repo_hygiene.py`, `scripts/check_release_notes.py` + `scripts/test_check_release_notes.py`, `testDebugUnitTest`, `:app:verifyPackagingDecision` and `:app:verifyNativeAlignmentGate` (the latter two exercise the real production functions against synthetic inputs, without downloading a byte of the GGUF).
    - *Release only (2026-10-18, Phase 3):* the decoded `release.keystore` is checked against `keystore/release-cert.sha256` (the **pinned production certificate**, extracted from the published v1.0.0 APK/AAB — an independent trust anchor, never derived from the artifact under test) **before** the expensive build; afterwards the APK's and the AAB's signer certificates are checked against the same pin by `scripts/verify_signing_identity.py`. Certificate rotation is a reviewed change to `keystore/release-cert.sha256`, never a weakened workflow check. `scripts/test_verify_signing_identity.py` (25 synthetic-fingerprint tests) proves the verifier accepts a pinned signer and rejects unpinned, mixed and malformed inputs (fails closed).
    - *Release only:* preflight of the four signing secrets, rejection of the model escape hatch, `lintDebug`, `assembleRelease` + `checkNativeAlignment` + `zipalign -c -P 16` + `apksigner verify` + debug-key rejection + `verify_release_artifact.py --kind apk`, then `bundleRelease` + `jarsigner`/`keytool` + debug-key rejection + `verify_release_artifact.py --kind aab`, and only then `Upload`/`Create Release`. APK and AAB go in **separate invocations**. `checkNativeAlignment` runs **after** the first packaging build on purpose: it scans AGP's *merged* native output, and running it before the build is exactly the old defect where it greened on a partial set (dependency libraries only, no Rust engine). Do not "restore" an early ordering to save build time — it verifies an incomplete artifact.
    - *Debug only (`build`):* `assembleDebug`, `lintDebug`, `checkModels`, `checkNativeAlignment`, `verifyPackagingDecision`, `:benchmark:assembleBenchmark` — all with `set -e -o pipefail`.
    - Every step with `| tee` carries `pipefail`; without it a red gate reports green (that was the state of the `debug_telegram.yml` test gate).
    - Actions pinned to the same majors the repo already used (`actions/*@v4`, `dtolnay/rust-toolchain@stable`, `gradle/actions/setup-gradle@v4`, `Swatinem/rust-cache@v2`); `permissions: contents: read` at workflow level and `contents: write` only in the publishing job.
  - `checkModels` verifies the SHA-256 of the bundled asset present in
    `model_assets/src/main/assets/builtin-model/`; it is still a no-op when the
    asset is missing (so `check` does not depend on 560 MB being downloaded). The
    no-op is only about the *source*: the artifact guarantee comes from
    `verifyReleaseApkModel` and `verifyReleaseBundleModel`, which do fail if the
    APK/AAB lacks the model (see §3 "Bundled model delivery"). The debug runtime
    download **also** verifies SHA-256 before activating `active_model` (P0.3,
    `FileSha256`), pending a smoke test on device.
  - `scripts/check_translations.py` checks name parity across the 7 alternative locales; it does not catch every hardcoded string in Java. P2.4 (2026-08-03): visible strings from Java/layouts are migrated to resources (44 new ones, gate PASS); documented exception for `PostProcessor` error details (no `Context`, JVM seam) and the JNI protocol strings the state machine uses as comparators.
  - `lintDebug`: hard gate. **AGP 9.4.1: 0 errors; 68 warnings, measured 2026-10-18 on both this branch and baseline `ebad7a2` with the identical distribution — i.e. no change introduces a new warning** (the previously documented "16" was stale: it predates the 51 `UnusedResources` findings that lint attributes to Material Components' own interpolators/dimens/integers shipped in the library but unreferenced by app code; they are not app-owned resources and are not worth deleting). The remaining documented warnings are **deliberate**; none silenced with `tools:ignore` or `@SuppressLint` (two fewer `ApplySharedPref` than the original inventory: the synchronous `commit()` calls in the API-key migration now check their result, which is exactly what that warning asks for, so it no longer fires). Re-triaged against the code in 2026-10 (same set; count updated after the credential-migration pass). Inventory so nobody re-investigates from scratch: 5 `InflateParams` (views whose real parent is `WindowManager`, or the view returned by `onCreateInputView()`), 3 `UselessParent` + 1 `MergeRootFrame` (the "redundant" container is the full-screen tap target, the scrim gutter, or the insets target the listener pads with `setPadding`), 1 `ObsoleteSdkInt` (`mipmap-anydpi-v26`), 2 `ButtonOrder` (an IME/overlay toolbar, not a dialog), 3 `UnusedAttribute` (`enableOnBackInvokedCallback` in the manifest, `isAccessibilityTool` in `accessibility_service_config.xml`, and the same manifest attribute reported once more for the `<application>` element: they only take effect on API 33+/31+ and they matter there), 1 `ChromeOsAbiSupport` (arm64-only by design, §5.1), 1 `TooManyViews` (the settings screen) and 51 `UnusedResources` (Material library resources, see above). Each carries its rationale in the file that triggers it (comment next to the Cancel `<Button>`, next to the disclosure `LinearLayout`, next to `abiFilters`, in the `recognize_activity.xml`/`service_subtitle.xml` headers, in the `isAccessibilityTool` doc, and in the `mipmap-anydpi-v26` XML), **except the two `UnusedAttribute` and the `TooManyViews`**: an attribute inside the `<application>` tag admits no inline XML comment and the view count belongs to the whole layout, so their justification lives here (and in `CHANGELOG.md`). The `ObsoleteSdkInt` is deliberate: `mipmap-anydpi-v26` **cannot** be renamed to `mipmap-anydpi` — the `-v26` qualifier is mandatory for adaptive-icon XML and renaming it breaks AAPT with `resource mipmap/ic_launcher not found` (re-verified 2026-10). The `lint` configuration in `app/build.gradle.kts` was cleaned up (2026-10) with `abortOnError = true`, `ignoreWarnings = false` and `checkReleaseBuilds = true`. It used to have `ignoreWarnings = true` and ~46 checks disabled in bulk, hiding real problems (`NewApi`, `MissingPermission`, unregistered receivers, exported components, hardcoded text, clickable-view accessibility…). Now only a handful of documented false positives remain disabled in the block itself (`GradleDependency`, `AndroidGradlePluginVersion`, `VectorPath`, `AppLinkUrlError` — the latter for the `audio/*` filters, which are mime handlers, not app links — and `Overdraw`). **Do not add a new `disable` without justifying it next to the code.**
  - Rust workspace tests: `cargo test --workspace` runs in both validate jobs (root `cdylib` crate + `crates/aura-core`). Measured in the CI log for `main` @ `7e68ba7` (2026-10-19): **33** tests in the root crate (`audio`, `corrector`, `post_processor`, `subtitle`, `voice_session`), **26** `aura-core` unit tests, **7** in `crates/aura-core/tests/adversarial_suite.rs` and **2** in `crates/aura-core/tests/negative_suite.rs`.
  - The P0 blockers and closing criteria are tracked in git history and `CHANGELOG.md`; no separate plan file is shipped in this repository.

---

## 4. Code Conventions and Structure

### 4.1 Architecture

- **Pattern:** **JNI bridge per Rust ↔ Java/Kotlin Service module**. Each Java service (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`) has its counterpart in `src/<mod>.rs` and exposes at least `initNative` / `cleanupNative` plus high-level actions (`startRecording`, `stopRecording`, `pushAudio`, ...).
- **Global state:** Rust uses `Lazy<Mutex<..>>` (from `once_cell`) for singletons (engine, IME session, recog session, subtitle state).
- **Shared engine:** `Arc<Mutex<Engine>>` with `Condvar` to coordinate concurrent loads. See the full pattern in `src/engine.rs` (`ensure_loaded_from_thread`).
- **Single-process model: JUST ONE.** There is no `android:process` in the manifest. **Marker files in `getFilesDir()`** are shared — *not* `SharedPreferences` — because the Rust engine only reads from the *filesystem* and settings must be available no matter which component started first (the IME can exist before `MainActivity`).

### 4.2 Naming

- **Rust:** `snake_case` for variables/functions, `PascalCase` for types (`Engine`, `VoiceSessionState`, `Endpointing`). `SCREAMING_SNAKE_CASE` for constants (`MAX_RUN_SAMPLES`, `MIN_SPEECH_LEVEL`). `Java_` prefix on exported JNI symbols.
- **Java:** `PascalCase` for classes, `camelCase` for fields/methods. Standard Android (Activity/Service/View).
- **Resources:** `snake_case` (`voice_status_ready`, `activity_main`, `bg_card`).

### 4.3 JNI naming (hard rule)

All exported native entry points follow this format:

```
Java_dev_notune_transcribe_<JavaClassName>_<methodName>
```

Same Java functions + same signatures (including the `JClass` even when unused). See e.g. `Java_dev_notune_transcribe_RecognizeActivity_initNative` in `src/recognize.rs`. **Do not rename without updating the native declaration in Java.**

Library loading order in every process (strict, in a `static` block):

```java
static {
    try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { /* warn */ }
    System.loadLibrary("android_transcribe_app");
}
```

### 4.4 Error handling and logging

- **Native logging:** `log::info!`, `log::warn!`, `log::error!` (filtered to `Info`, see `android_logger::init_once`).
- **Java logging:** `Log.d/i/w/e(TAG, msg, throwable)` with a `TAG` per class (`OfflineVoiceInput`, `MainActivity`, `LiveSubtitleService`...).
- **JNI → Java callbacks (stable signatures — do NOT change them without touching every surface):**
  - `onStatusUpdate(String)` — messages like `"Ready"`, `"Listening..."`, `"Transcribing..."`, `"Error: <msg>"`.
  - `onTextTranscribed(String)` — final text (post-processing applied if active).
  - `onAudioLevel(float)`/`onRmsChanged(float)` — rms level 0..1.
  - `onSubtitleText(String, boolean)` — partial (false) or final (true).
  - `onPartialText(String)` — live partial hypothesis from streaming models (Nemotron) while recording; visual-only in Java (final text always arrives via `onTextTranscribed`/`onResults`). Surfaces without streaming send no-ops.
  - `onAutoStop()` — endpointing monitor trigger.
  - **IME/popup recording with generation:** the `RustInputMethodService` and `RecognizeActivity` surfaces additionally keep the JNI variants `onStatusUpdate(String, int)`, `onTextTranscribed(String, int)`, `onAudioLevel(float, int)`, `onPartialText(String, int)` and `onAutoStop(int)`. The `int` is the recording's `sessionId`; Java drops late callbacks whose ID no longer matches the active generation. The engine/lifecycle keeps the ID-less variants for load states and compatibility with the other surfaces.
  - **File transcription with operation-id (P1.1):** `TranscribeFileActivity` keeps `onTextTranscribed(String, int)` and `onStatusUpdate(String, int)` where the `int` is a per-decode unique operation-id (static `AtomicInteger`, immune to Activity recreations); `transcribeAudio(float[], int, int)` receives it from Java and echoes it in every callback. Java drops callbacks whose opId is no longer current or whose Activity is `isFinishing()/isDestroyed()`.
  - `onBenchmarkResult(float audioSecs, float computeSecs, String error)`.
- **Engine resilience:** `transcribe_shared` does `panic::catch_unwind` + poisoned-`Mutex` recovery (`unwrap_or_else(|p| p.into_inner())`). If you refactor it, **keep both layers**.

### 4.5 Settings handling (project rule)

Settings are **marker files in `filesDir()`**, not `SharedPreferences`. Examples:

| Marker file | Meaning |
|---|---|
| `auto_record` | present = *Auto-start recording* ON |
| `select_transcription` | present = select the transcribed text |
| `pause_audio` | present = pause system audio while recording |
| `stop_on_hide` | present = *Record in background* OFF (default ON is the opposite) |
| `auto_stop` | present = *Auto-stop after silence* ON |
| `model_language` | content = BCP-47 locale (`en-US`, `es-ES`, …) or `auto` |
| `model_translate` | present = translate to English (Whisper) |
| `active_model` | content = name of the GGUF imported into `models/` |
| `model_threads` | content = integer count; absent/invalid = automatic |
| `stream_context_right` | content = `13`/`6`/`1`/`0` → cache-aware chunks {1.12 s, 560 ms, 160 ms, 80 ms} (Nemotron, `chunk = (right+1) × 80 ms`); absent/invalid = `13` (the model's precision default). Off-menu values are retried with the model's default in `run_stream`, they do not break the stream |
| `hardware_backend` | content = `cpu` **always**. `src/engine.rs` reads the marker and **only logs it**: there is no NNAPI/Vulkan path in the native engine. `ModelsActivity` no longer offers NPU/GPU and normalizes any old value to `cpu` — do not reintroduce UI options without a real implementation behind them |
| `custom_words` | content = correct terms, one per line (`#` lines = comments); absent/empty = phonetic correction disabled |
| `subtitle_translation_target` | content = `auto` (original language, no translation — product decision) or a fixed BCP-47 locale (`es-ES`, `en-US`, `fr-FR`, `de-DE`, `it-IT`, `pt-PT`, `ru-RU`) → on-device translation of **finalized** subtitles (ML Kit, packages via Google Play Services; always falls back to the original text). Read by `LiveSubtitleService` at session start; needs no engine reload |

Typical bindings (see `MainActivity.bindMarkerSwitch`): a `CompoundButton` whose file presence represents the state.

The post-processing **API key** is the only exception to plain markers: it is stored encrypted at rest (AES-256-GCM, versioned `v1:<iv>:<ciphertext>` format in `pp_api_key_enc`) with a generated key held in **AndroidKeyStore** (`SecureCredentialStore` + `AndroidCredentialKeyProvider`), never in plaintext or Base64. The legacy `pp_api_key` marker (Base64) and old SharedPreferences/EncryptedSharedPreferences copies are migrated at startup with *verify-before-delete*: the ciphertext is written and verified first, and only then is the legacy copy deleted; if migration fails (e.g. Keystore unavailable) the legacy credential is kept and retried on a later startup, never degrading to plaintext storage. That orchestration (precedence across the three sources, write-before-delete, sentinel, and retries) lives in `CredentialMigration` over an injectable `LegacySources` seam — covered by JVM tests (`CredentialMigrationTest`); `SettingsManager` only provides the Android implementation. The **empty** `pp_api_key_migrated` sentinel means "checked, nothing to import" and does **not** block a legacy copy appearing later (e.g. written by a downgraded build); the `deleted` content is a tombstone written only by explicit deletion when some copy — the encrypted store included — could not really be removed, and it forbids reimporting it. **Serialization (2026-10):** every credential mutation (startup import, save, deletion) goes through a single *lane* (`CredentialOperations`, `credential-ops` thread), never the calling thread or the main thread: operation order is submission order, so a later-submitted deletion wins over an already-running import (it used to be able to resurrect the key) and a later-submitted save wins over the imported value. `SettingsManager.setApiKey(key, executor, callback)` is therefore **asynchronous**: it delivers the durable result via callback and the settings screen only reports "saved" (ES locale: «guardado») and closes once the operation has really finished (on failure it shows the error and does not close). A static Keystore failure (e.g. `IllegalStateException` from a broken provider) is converted to `CredentialStoreException` at the store's crypto boundary, not with a global `catch (Throwable)`; a failed save is **not** reported as successful and never leaves the key in plaintext, and a deletion that could not remove the file returns `false` instead of claiming it deleted. **Reads (2026-10):** reading is **asynchronous and off the calling thread**. `SettingsManager.readApiKey(executor, callback)` enqueues on the `credential-ops` lane (`CredentialOperations.submitValue`) the same path `getApiKey()` used to take — on-disk state + `CredentialRead` — and delivers an `ApiKeyRead` (`LOADED` / `ABSENT` / `UNREADABLE`) via callback; `readPostProcessEnabled(executor, callback)` does the same for the switch. No caller performs credential I/O, tombstone reads, or AndroidKeyStore decryption on its own thread (the main thread for every UI surface). The synchronous accessors (`getApiKey`, `isPostProcessEnabled`, `isPostProcessConfigured`) were removed; `isPostProcessSwitchedOn()` remains as a **cheap** probe (marker + provider available, no credential I/O) to decide whether post-processing is worth requesting, leaving the credential verdict to `PostProcessor`, which reads in order before the request: a still-running legacy import is never read as "not configured", and an unread credential (UNREADABLE) is reported as such instead of as absent — in both cases without touching the network (the raw transcript is still delivered, exactly once). The read goes **before** the request, so no network call ever uses a credential from another queue state, and a surface destroyed mid-read (the async callback arrives late) no longer fires the request: `PostProcessor` checks the lifecycle validator before enqueueing on OkHttp. `CredentialRead` (pure, covered by `CredentialReadTest`) decides nothing on its own: it only receives the on-disk state. The `deleted` tombstone **overrides every source**, so a deletion is visible to any reader from the instant it is written — `forget()` writes it **before** deleting anything (it used to write it after, and in that window the store was gone but the Base64 marker was still there: a read returned the key the user had just deleted); and since the tombstone also wins when a copy could not be removed, a deleted key cannot be read from either the surviving store or a legacy copy. Ordering between reads and mutations is **by successful completion, not by enqueueing**: an in-flight mutation is not yet visible (defined and documented state), which is why every caller that must observe its own mutation waits for the completion callback — the settings screen does so on save, on model-list refresh, and on connection test — instead of re-reading; a caller waiting on a specific read enqueues it **after** its mutation, so the lane runs it behind and observes its result; conversely, `store()` clears the tombstone in the same operation as the verified write, so a new key is not hidden by an earlier deletion, and `migrate()` finding a tombstone **finishes the deletion** instead of treating the decryptable store as "the user saved a new key". Only Java reads the key (OkHttp `Authorization` header); **Rust has no reference to it**. Encryption at rest protects against offline extraction of the app's data; it does NOT protect on a rooted/compromised device while the app is using it (the key must exist in memory to build the request) — see the threat model in the `SecureCredentialStore` header. Deleting the marker does not guarantee physical erasure on flash (FTL/wear-levelling).

### 4.6 Rust ↔ Java module mapping (module mapping for agents)

The folder tree lives in [`README.md` §Project Structure](README.md#project-structure).
This table maps **which Rust module ↔ which Java component ↔ where the shared
engine is invoked** — the information an agent needs but the README does not
cover.

| Rust module (`src/<mod>.rs`) | Java component | Invokes the engine from | Particulars (AGENT-ONLY) |
|---|---|---|---|
| `lib.rs` | — | — | Root `pub mod`. Any new module must be declared here. |
| `engine.rs` | (global singleton) | `voice_session.rs`, `subtitle.rs` (worker), `recog_service.rs` (audio callback), `main_activity.rs` (benchmark) | `ensure_loaded_from_thread` coordinates with `Condvar`; **language is re-read on every `run`** (do not cache). |
| `audio.rs` | — | `engine.rs` (`find_quietest_split`) | Pure utility, no JNI. |
| `voice_session.rs` | `RecognizeActivity`, `RustInputMethodService` | via JNI callback `onTextTranscribed(text)` | Shares one `cpal::Stream` between both surfaces; own auto-stop. |
| `subtitle.rs` | `LiveSubtitleService` | via JNI callback `onSubtitleText(text, isFinal)` | Partial/final pipeline with merging; calibrated lag policies (see §4.8). |
| `recog_service.rs` | `VoiceRecognitionService` | synchronously in `audio_callback` | Endpointing with silent VAD; no auto-stop (the monitor owns it). |
| `ime.rs` | `RustInputMethodService` | thin bridge → `voice_session` | JNI calls: `init/cleanup` + `start/stop/cancel` Recording. |
| `recognize.rs` | `RecognizeActivity` | thin bridge → `voice_session` | Identical to `ime.rs` but with `jboolean auto_stop` in `startRecording`. |
| `main_activity.rs` | `MainActivity` | benchmark + status notifier | `initNative`, `benchmarkNative`; caches samples in a `Vec<f32>` from `bench.wav`. |
| `models.rs` | `ModelsActivity` | engine reload via `engine::reset()` | Imports GGUF into `filesDir/models/<name>`. |
| `transcribe_file.rs` | `TranscribeFileActivity` | a single `transcribe_shared` | Shares audio from `Intent.ACTION_SEND/VIEW` (`audio/*`). |
| `corrector.rs` | (`CustomWordsActivity` writes the marker file) | via `engine::transcribe_shared` (post-ASR, pre-callback) | Post-ASR phonetic correction: reads `filesDir/custom_words`, encodes every transcript word and dictionary term with an ES+EN phonetic encoder (seseo/yeísmo, `ñ`→`ny`), replaces misrecognized words with the closest term (Levenshtein ≤ 2 over phonetic keys + bigram-cosine tiebreak). Safe fallback: any failure (no dictionary, I/O error, panic) returns the raw text. Runs inside its own `catch_unwind` so it never freezes the IME. Covers ALL surfaces (IME, popup, subtitles, SpeechRecognizer, file) in one shot. |
| `assets.rs` | (internal) | `engine.rs` (`do_load`) | Extracts the bundled model from APK assets on first start; `invalidate_builtin_model` deletes corrupt extractions. |

**Modular rule:** moving logic between modules also requires updating
`AndroidManifest.xml` (activity/service registration) and `lib.rs` (`pub mod`).
Do not rename Rust/Java files without updating the JNI entry (§4.3).**AI post-processing (fork addition, Java-only — AGENT-ONLY contract):**
- **`pp_default_prompt` is a single line with `\n` escapes.** AAPT2 **collapses literal
  newlines** in a `<string>` into a single space (verified with `aapt2 dump strings`). A
  multiline value breaks at runtime: the `PostProcessSettingsActivity` field shows everything
  joined and the payload sent to the LLM is a flat wall — that was the cause of the "joined
  prompt" bug and the LLM not refining the text. Use `\n` (and `\n\n` for blank paragraphs)
  inside the value.
- **The transcript is never sent twice.** If the active prompt contains `${output}`, the raw
  text is injected into the system prompt and the `user message` becomes the fixed marker
  `"Apply the instructions to the transcript above."`. If there is NO `${output}`, the text
  travels in the user message. Do not duplicate it (`injected` flag in `PostProcessor.java`'s
  `process()`): sending it twice makes the LLM return the input unrefined.
- **Final-only post-processing:** the transcriber streaming (`onPartialText`) is only a
  visual preview. When `onTextTranscribed` arrives, the final text is sent exactly once via
  `process()` with a complete JSON response (`stream` is not sent), and the editor receives
  a single `commitText` with the refined result. If post-processing is off, the raw ASR text
  is committed directly; if the call fails, is cancelled, or returns an empty/invalid
  response, the raw text is always committed. This avoids pasting partial tokens, duplicates,
  or "Frankenstein" text while keeping ASR streaming fluid.
- **Cancellation/lifecycle:** cancellation is **per owner** (`PostProcessor.cancelAllFor(owner)`, identity of the Activity/Service owning the call; tracked in `CallRegistry`): destroying a surface or cancelling a recognition never cancels another surface's legitimate request. `PostProcessor.cancelAll()` (global) is reserved for truly global events: PP-off toggle (with the `CANCEL_ACTION` broadcast, which the IME listens to) and IME service death. Activity/IME validators prevent late callbacks on destroyed components, and the OkHttp `Response` is always closed, including errors and failed parses.
- **Subtitle translation (fork addition, AGENT-ONLY contract):** the bundled model (Nemotron) does **not** translate; translation is a Java-side subtitle-only stage over **finalized** segments (`LiveSubtitleService` + `OnDeviceSubtitleTranslator` with ML Kit). The target lives in the `subtitle_translation_target` marker (`auto` = original language; fixed locale = translate) and the service reads it at session start — do **not** reuse `model_translate` (that is ASR-global and must **never** apply to subtitles: `engine::transcribe_subtitle` forces `Task::Transcribe`). Serial FIFO queue (max 8), results applied in order on the main thread, always falling back to the original text (no Play Services, no pack, error, saturation), session generation for late callbacks, and automatic source resolved from the text's script (`SourceLanguageResolver`) when `model_language` is not fixed.

### 4.7 Rust: Engine singleton contracts (do not break)

- **`engine::get_engine()` → `Option<Arc<Mutex<Engine>>>`**: shared by all Java components (a single process). Guarantees: panic catching, poisoned-Mutex recovery.
- **`engine::ensure_loaded*`**: idempotent, multi-thread safe, waits via `Condvar` while another thread loads, retries after `Failed(_)`.
- **`Engine::transcribe`** splits long audio at the quietest point (`audio::find_quietest_split`); joins the texts with a space.
- **`Engine::run` re-reads `model_language` on EVERY call** (v0.1.20→21 issue). That is what makes the language change in the `ModelsActivity` spinner apply instantly on every surface (including the IME, which may be alive before the Activity) with no manual model reload. **Do not cache the language inside the Engine** — that was the original bug.

### 4.8 Subtitles: predictable-cost pipeline

`src/subtitle.rs` is probably the subtlest code. Rules:

- **Partials:** only enqueued when (a) worker idle, (b) no pending finals, (c) `segment_secs * rtf <= MAX_PARTIAL_COST_SECS` (latest-wins). If the device transcribes slower than the audio, partials are cut — by design.
- **Finals:** always enqueued (FIFO), *but* folded with `try_recv` up to `MAX_MERGED_SAMPLES` (25 s) to cushion Whisper's fixed window.
- **Lag policies (important, do not raise these thresholds lightly):**
  - `MAX_FINAL_LAG_SAMPLES = 8 s` → drop with a `"…"` gap.
  - `MAX_PARTIAL_LAG_SAMPLES = 3 s` → discard.
  - `MAX_PARTIAL_COST_SECS = 2.0` → cost-based partials on/off toggle.
- Merging finals is what lets a slow device keep up with real time without losing text. Remove it and latency grows unbounded.

### 4.9 Java UI: observable conventions

- **Base theme:** `Theme.Material3.DayNight.NoActionBar` + `DynamicColors.applyToActivitiesIfAvailable(this)` in `App.onCreate` (Material You from Android 12+). `AppTheme` **overrides no color**: the Material 3 tonal scheme is used as-is (it used to override only `colorPrimary`/`colorOnPrimary`, leaving the remaining roles on baseline and producing a half-applied palette). Every surface uses `?attr/color*` attributes; **do not add hex literals** to layouts or drawables. The only documented exceptions are semantic tokens with no M3 role (`status_ok`) and overlay scrims drawn over third-party content (`subs_scrim*`, `overlay_scrim*`), which deliberately keep the same value in light and dark.
- **Touch targets:** effective minimum **48dp × 48dp** on every interactive control (IME keys, close, cancel, overlay, subtitles). The glyph may stay 24dp; the container may not. Do not use `android:minWidth/minHeight="0dp"` to shrink a button below the minimum.
- **IME:** `RustInputMethodService` is not an `AppCompatActivity` (it is an `InputMethodService`, in the same process as the rest of the app). It builds its own theme-aware context with `ThemePrefs.wrapForNight` + `DynamicColors.wrapContextIfAvailable` so the view matches the main app's theme setting.
- **Voice screen (popup):** translucent `AppTheme.VoicePanel` — NOT fullscreen — so the invoking app keeps its UI behind.
- **Internal engine state (`"Loading"`, `"Initializing"`, `"Waiting"`) is NEVER shown to the user:** the UI always maps to `Tap to Record`, `Listening...`, `Processing...`, etc. (see `updateUiState` in `RustInputMethodService`).
- **Screens must repaint on theme changes** (e.g. the IME rebuilds its `inputView` if `ThemePrefs.isNight` changed since `onStartInputView`).
- **IME Cancel button (`ime_cancel`):** visible during recording (discards the capture before transcribing/post-processing, avoiding API spend) and during the whole `resultPending` window (from mic release to commit/cancel). `resultPending` in `RustInputMethodService` is the **single source of truth** in `updateRecordButtonUI(false)`; **do not reintroduce** `lastStatus` (Transcribing/Processing) checks in that branch — a stale `"Processing..."` would re-show the button after commit (flicker). Every stop path (mic, auto-stop, keyboard switch) sets `resultPending` synchronously; commit, cancel, empty text, `stop_on_hide`, terminal error, and the `CANCEL_PP` receiver clear it; a new recording resets it.

### 4.10 i18n

- **Every new string** → add it in `app/src/main/res/values/strings.xml` and propagate to `values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`.
- If a string must **not** be translated (e.g. default system prompt), use `translatable="false"` (see `pp_default_prompt`).
- **Multiline `<string>` values:** AAPT2 collapses literal newlines into a single space. If the text must render/send multi-paragraph (the `pp_default_prompt` case), keep the value on ONE line with `\n` escapes (`\n\n` = blank paragraph). Verified with `aapt2 dump strings`; see §4.6.
- If language order matters (e.g. settings appearing in a side column with an icon), respect the existing order in `MainActivity.bindMarkerSwitch`.

### 4.11 Version and release

- **Version bumps:** edit both `versionCode` **and** `versionName` in `app/build.gradle.kts`. `versionCode` always increments.
- **Release notes (KEY RULE):** `RELEASE_NOTES.md` contains **only the notes for the version being published**, with a mandatory length of **at most 125–250 words** (CI uses `body_path: RELEASE_NOTES.md` for `softprops/action-gh-release@v2`). Do not accumulate past-version histories in this file (the full historical record lives in `CHANGELOG.md`).
- **APK and AAB are both published, but in separate invocations.** The release workflow builds both (`assembleRelease` → `Aura_Transcribe_vX.Y.Z.apk`; `bundleRelease` → `Aura_Transcribe_vX.Y.Z.aab`) and creates a single GitHub Release with both files. What **cannot** be done is requesting both in the same Gradle call: the APK needs the GGUF in the base module and the AAB only in the `:model_assets` asset pack (`dynamicDelivery` install-time, see `model_assets/build.gradle.kts`), so `app/build.gradle.kts` falls back to adding `model_assets/src/main/assets/` to `assets.srcDirs` only when NOT a bundle, and an ambiguous invocation fails at configuration. See "Bundled model delivery" in §3.

---

### 4.12 Audio input routing contract (2026-10-18 corrective audit, Phase 2)

`AudioDeviceManager` + `AudioRoutingPolicy` (pure-JVM decision logic, `AudioRoutingPolicyTest`):

- **Only input-capable (source) devices are ever selected as capture targets.**
  `AudioDeviceInfo.TYPE_BLE_SPEAKER` (27) is an *output-only* LE Audio speaker and
  is excluded everywhere a microphone candidate is considered — it appears in
  `getAvailableCommunicationDevices()` because that list is bidirectional, which
  is exactly how it used to hijack AUTO routing away from a real USB/BT mic.
  Devices are additionally filtered by `isSource()`.
- **`acquireMicrophone(context, micPreference)` returns `boolean`.** `false`
  means only one thing: the user explicitly chose "Bluetooth only" and the
  platform did not confirm a Bluetooth input route (no candidate, or
  `setCommunicationDevice()` returned `false`, or legacy SCO start failed).
  In that case no routing state is left behind and every surface reports the
  localized `mic_bluetooth_unavailable` message instead of silently capturing
  from the built-in mic. AUTO and built-in modes always keep their documented
  built-in fallback and effectively return `true`.
- **`isRoutingActive`/`isPrewarmed` never contradict the platform**: they are
  set only after a confirmed route; a failed pre-warm rolls the audio mode back.
- **The prior audio mode is saved on the first routing change and restored by
  `releaseMicrophone`** (never unconditionally `MODE_NORMAL`), and release is
  idempotent.
- The policy mirrors `AudioDeviceInfo` type constants as plain ints so every
  decision is JVM-testable; do not re-embed device-type lists in
  `AudioDeviceManager` — extend `AudioRoutingPolicy` instead.
- Blanket `catch (Throwable)` is **not** allowed in the routing paths (2026-10):
  expected platform failures are `Exception`s; fatal VM errors must propagate.
- Real Bluetooth routing (SCO handshake, BLE headset capture, `setCommunicationDevice`
  on hardware) still requires an on-device smoke test; the JVM suite proves the
  decision logic only (documented in AGENTS/CHANGELOG).

## 5. Rules for AI Agents

### 5.1 AVOID (things that would break the project)

- ❌ **Do not change the JNI callback signatures** listed in §4.4 without updating every Java surface (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`).
- ❌ **Do not cache `model_language`** inside `Engine` or across calls. The intentional behavior is reading the marker file on every `Engine::run`, so changing the language in `ModelsActivity` applies instantly on every surface (IME, popup, subtitles, service) without a model reload. That was the critical v0.1.20 → v0.1.21 bug.
- ❌ **Do not remove the two resilience layers** of `transcribe_shared` (`catch_unwind` + poisoned-Mutex recovery). Doing so leaves the IME stuck on "Processing" after a single panic until the process dies.
- ❌ **Do not add support for other ABIs** (`armeabi-v7a`, `x86`, `x86_64`) without reviewing first:
  - `check_cpu_features` requires dotprod+fp16 (ARMv8.2 ~2018+); any older device would fail mid-inference with `SIGILL`.
  - the ggml cross-compile with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` is pinned in `app/build.gradle.kts`.
- ❌ **Do not break SHA-256 verification** or remove the "bundled model" marker — it protects against apps updating to a new model while keeping the old one cached. The debug runtime download verifies the hash with `FileSha256` **before** writing `active_model` (P0.3, implemented 2026-08-03); do not degrade that path to hash-less activation.
- ❌ **Do not convert the app to Kotlin** without discussing it first. It is a Java project on purpose (upstream legacy, avoids KSP/codegen overhead).
- ❌ **Do not move settings to `SharedPreferences`** without also migrating the marker files consumed by Rust (`model_language`, `model_translate`, `active_model`, `model_threads`). Rust code only reads from the *filesystem*, not prefs.
- ❌ **Do not add blocking synchronous dependencies** to the `cpal` audio-callback event (in `voice_session.rs`/`recog_service.rs`). Every write to `audio_buffer` must be O(1) under the mutex.
- ❌ **Do not change the native library load order** (always `c++_shared` before `android_transcribe_app`) or skip the `try/catch UnsatisfiedLinkError` — it guards against broken devices/ABIs.
- ❌ **Do not raise the thresholds** `MAX_FINAL_LAG_SAMPLES` / `MAX_PARTIAL_LAG_SAMPLES` / `MAX_PARTIAL_COST_SECS` in `subtitle.rs` without testing slow hardware: those numbers are calibrated so an average device does not fall behind, they are not free tuning.
- ❌ **Do not replace `transcribe_shared` with code that ignores panics.** The `panic::catch_unwind` documents a real case: the model can panic in the background over large allocations, and without the catch the effect would be a frozen IME.
- ❌ **Do not break the post-processing fallback:** if the LLM call fails, the raw transcript is always delivered (`onError → deliverResult(text)`). That is the "never lose text" guarantee.
- ❌ **Do not read the credential synchronously from a calling thread again (least of all the main thread).** No synchronous `getApiKey()`/`isPostProcessConfigured()`: file I/O, tombstone reads, and AndroidKeyStore decryption belong on the `credential-ops` lane via `SettingsManager.readApiKey`, which is also what orders the read behind the startup legacy import. A **pending** read is not an absent credential: do not report it as "not configured" (ES locale: «no configurado») or fire the request with a credential out of thin air.
- ❌ **Do not write `pp_default_prompt` (or any long `<string>`) with literal newlines.** AAPT2 collapses them into a single space: it breaks the prompt UI and flattens the payload guiding the LLM to refine. Use `\n` escapes (see §4.6).
- ❌ **Do not produce signed release builds outside the official GitHub Actions pipeline.** Debug builds reach the user via GitHub Actions + Telegram bot, signed ones via `android_release.yml`. **Building locally to validate is allowed and expected** on a real dev host (laptop/desktop), as long as the artifact is not published and releases are not hand-signed. See "Environment-based validation rule" in §3.
- ❌ **Do not delete the explanatory comment in app/build.gradle.kts about `assetPacks` + bundle-vs-APK.** It is the trap that broke upstream v0.1.x and was documented specifically to avoid it.
- ❌ **Do not introduce automated tests importing the whole module** through JNI in CI: there is no arm64 emulator on the GitHub Actions runner. CI validation is build + lint + JVM tests; the functional smoke test is manual on device. Instrumentation tests (if added) compile but are **not** run in CI.

### 5.2 DO (positive rules for new code)

- ✅ When adding a new JNI callback, document it with its exact signature in this `AGENTS.md` (§4.4) and add a default no-op stub in Java so builds where the Java side is not wired yet do not break.
- ✅ Every new user setting goes as a **marker file in `filesDir()`**, no exceptions. **The API key is the only exception to the plain format**: it persists encrypted via `SecureCredentialStore` (AES-256-GCM + AndroidKeyStore); do not use Base64 as "protection" and never write the key in plaintext as a fallback when Keystore fails (`setApiKey` delivers `false` via callback and the UI shows it, without closing the screen or pretending it saved). Every key write goes through the `CredentialOperations` lane (§4.5); do not write the encrypted marker from outside that lane. A new non-sensitive setting is still a plain marker.
- ✅ Every marker write goes through `MarkerFileHelper` (per-write **unique** temp + fsync + rename; delete on empty). Never a direct `FileOutputStream` on the final name: concurrent writers sharing the same `*.tmp` can expose partial content (caught by `MarkerAtomicityTest`). Several components (Activity, IME, services) write the same settings, so this stays mandatory even though everything runs in one process.
- ✅ Before adding visible strings, duplicate them across all 8 locales. To add a string you do **not** want translated, mark it `translatable="false"`.
- ✅ Before touching the `Engine`, first read `src/engine.rs` in full (~370 lines, heavily commented): warm-up decisions, language re-read, bundled-model fallback, capability warnings (`supports_translate`/`variant().contains("turbo")`), Whisper `temperature_inc` — everything is there for a documented reason.
- ✅ When adding a new surface (Activity/Service), remember:
  1. `static` block with `c++_shared` + `android_transcribe_app`.
  2. Native method declared in Java + Rust implementation with the `Java_dev_notune_transcribe_<ClassName>_` prefix.
  3. JNI calls with `get_java_vm`, `new_global_ref`, and proper release in `cleanupNative`.
  4. Settings go through marker files (never `SharedPreferences`), because the Rust engine reads them from the *filesystem*. Do **not** add `android:process`: the app is single-process on purpose (§1).
- ✅ When adding a bundled model:
  1. Upload the real SHA-256, **never** invent one.
  2. Add the `ModelFile` to `modelPackFiles` (`app/build.gradle.kts`).
  3. If the model is > 200 MB, keep `model_assets` as an asset pack.
- ✅ When changing the Rust load logic:
  - The `libpthread.a` shim and the dynamic `c++_shared` library are **required for the Android build**. See the comment in `build.rs`.
- ✅ Before touching the benchmark, first read the `MainActivity.runBenchmark`/`readWavAsset` comments (16 kHz mono 16-bit PCM only).
- ✅ Version bump: `versionCode` always +1 over current; `versionName` semver-friendly; add entry at the top of `RELEASE_NOTES.md` (not at the end).
- ✅ If you break a language or locale: the app **transcribes in the device locale by default** (`App.applyDeviceLanguageIfUnset`) and it is opt-out. Any user override must respect that behavior (see `ModelsActivity` + `engine::run`). Spanish (`es-ES`) and the other 7 locales are first-class: the ES+EN phonetic encoder, the BCP-47 locale list, and the per-language WER trade-off below exist so transcription quality never silently regresses for non-English users.
- ✅ When adding a Gradle dependency: respect the `constraints` block pinning `kotlin-stdlib-jdk7/jdk8` to `1.8.22`. Without that alignment, new builds break with `Duplicate class` from transitive Material legacy.
- ✅ When changing the subtitle flow, preserve the `onSubtitleText(text, isFinal)` contract: `isFinal=true` appends, `isFinal=false` replaces the partial hypothesis within the same window.
- ✅ When touching cache-aware streaming (`run_stream`/`transcribe_stream_shared` in `engine.rs`), preserve: (a) the `att_context_right` retry off the GGUF menu with the model's default (`Error::InvalidArgument` → retry with `None`), (b) language-hint degradation, and (c) the Stop-log session telemetry (`rtf`, partial count, mean cadence, `att_context_right`) — the only way to measure the WER-vs-fluency trade-off on a real device via logcat. Published trade-off reference: chunks {1120, 560, 320, 160, 80} ms with FLEURS WER at 1.12 s of en 7.91 / es 4.11 / fr 9.03 / it 4.25 / pt 5.48 / de 8.31; the card claims "competitive" accuracy even at 80 ms.
- ✅ Do not add unnecessary `unsafe` Rust outside the JNI entry points (`#[no_mangle] pub unsafe extern "system" fn Java_…`).

### 5.3 Template for tests / new components (quick reference)

Creating a new Rust module with JNI → requires:

1. **Rust** (`src/<mod>.rs`):
   ```rust
   use jni::objects::{JClass, JObject};
   use jni::JNIEnv;
   use once_cell::sync::Lazy;
   use std::sync::Mutex;

   pub struct FooState { /* JVM + GlobalRef to the target */ }

   static STATE: Lazy<Mutex<Option<FooState>>> = Lazy::new(|| Mutex::new(None));

   #[no_mangle]
   pub unsafe extern "system" fn Java_dev_notune_transcribe_FooService_initNative(
       env: JNIEnv, _class: JClass, target: JObject,
   ) { /* new_global_ref, attach thread, etc. */ }
   ```
2. **Java** (parallel):
   ```java
   public class FooService extends Service {
       static {
           try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { }
           System.loadLibrary("android_transcribe_app");
       }
       public void onStatusUpdate(String s) { /* runOnUiThread or mainHandler */ }
       private native void initNative(FooService self);
       private native void cleanupNative();
   }
   ```
3. **Manifest**: add `<service android:name=".FooService" .../>`. Do **not** add `android:process` — the app is single-process on purpose (§1). If the service captures audio, declare `android:foregroundServiceType="microphone"` **and** request `RECORD_AUDIO` before calling `startForeground`.
4. **lib.rs**: `pub mod foo;`.

Creating a new UI toggle setting → marker file in `filesDir()` with a `snake_case` name; bind it with `bindMarkerSwitch(...)` in `MainActivity` (existing pattern). Propagate the string to all 8 locales.

### 5.4 Known limitations (read before "fixing" or "documenting" any of this)

Real, verified state, with the supporting evidence. Do **not** describe
any of these as working, and do not "fix" them halfway.

1. **On-device post-processing (S1-mini) is NOT implemented and, on top of that,
   not reachable from the UI.** Double barrier, both verified:
   - **No inference engine.** The only backend in the tree is
     `transcribe-cpp 0.1.3`, whose public API is *speech recognition*
     (`Model::load` / `Session::run(pcm: &[f32], RunOptions{ task: Transcribe |
     Translate, … })`): there is no text-generation entry point (no prompt
     decode, no sampler, no tokenizer-encode path for a Qwen-family model (its
     `Feature::InitialPrompt` is a Whisper decode hint, not generation).
     SuperWhisper S1-mini is a Qwen-based causal LM, so running it requires a
     text-generation engine (llama.cpp or equivalent) plus a BPE tokenizer, KV
     cache, and sampler, none of which this project ships. That is why
     `src/post_processor.rs::normalize_text_on_device` is a **placeholder**:
     it verifies the GGUF exists, builds the real prompt with
     `aura_core::normalizer::build_s1_prompt`, and **discards it**.
   - **The UI no longer offers it as if it worked.**
     `SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE = false` makes
     `isProviderAvailable("local_s1")` return `false`: the provider renders as
     *"unavailable in this build"*, the switch cannot be enabled, the download
     button is hidden, and an obsolete marker is answered with
     `PostProcessor.LOCAL_S1_UNAVAILABLE_ERROR` instead of returning the
     simulated text. `pack_ultralight_desc` was rewritten in all 8 locales to stop
     promising S1-mini refinement.
   If inference is ever implemented, `LOCAL_S1_INFERENCE_AVAILABLE`, the
   `normalize_text_on_device` doc-comment, and this item must be flipped
   **together** with a real implementation — never separately. `is_loaded` means
   "a valid path was seen", not "weights are in RAM".
2. **With the gate closed (`LOCAL_S1_INFERENCE_AVAILABLE = false`), R8 strips
   from release the ENTIRE JNI surface of the on-device provider:**
   `nativeNormalizeOnDevice`, `nativeSetS1ModelPath`, `nativeUnloadS1`, and
   `nativeIsS1Loaded`. Verified by reading the string table of
   `app/build/outputs/mapping/release/mapping.txt` and the release APK's
   `classes.dex` (2026-10): the `static final false` constant folds the branch, and R8
   also deletes its call sites (`processOnDeviceInternal`), so no live call remains
   that could throw `UnsatisfiedLinkError` — that pairing (caller and callee out
   together) is the real guarantee, not the symbol's presence. `nativeTrimMemory`
   **does** survive, because `App.onTrimMemory` really calls it. The four Rust
   functions still exist in the cdylib and the Java declarations stay in the code:
   they are the lifecycle API for when an inference engine exists (item 1). Do not
   delete them for looking "unused" in the release DEX.
3. **Instrumentation, Macrobenchmark, and Baseline Profile have NEVER been run**:
   there is no arm64 device/emulator. The `:benchmark` module **compiles**
   (`:benchmark:assembleBenchmark`) but its tests are on-device and out of CI on
   purpose. Do not present a Baseline Profile as generated.
4. **`assembleRelease` and `bundleRelease` can no longer be requested together**
   (they used to silently produce a 32 MB model-less APK; see §3 "Bundled model
   delivery"): the combined invocation fails at configuration, and the
   `verifyReleaseApkModel`/`verifyReleaseBundleModel` gates open the real artifact and
   refuse (verified 2026-10 in all four combinations: APK only, AAB only,
   combined, and missing model). Do not "fix" this by returning to a single source set
   for both: it would deliver the model twice in the AAB.
5. **Resolved: the Rust workspace tests DO run in CI.** Both validate jobs run
   `cargo test --workspace` (root `cdylib` crate + `crates/aura-core`), and the
   root crate's `#[cfg(test)]` modules in `audio` and `corrector` execute there
   (29 tests in the root crate, 26 + 2 in `aura-core`, measured 2026-10). The
   earlier "blocked by `transcribe-cpp-sys v0.1.3`" note is outdated.
6. **Resolved (2026-10): the two `MainActivity` startup disk reads are NO longer
   on the main thread.** `maybeDownloadDebugModel()` (invoked from
   `onCreate`/`onResume`) used to call `hasImportedModel()` (`active_model` marker)
   and `hasBundledModel()` (`getAssets().list("builtin-model")`) synchronously, which
   StrictMode flagged in debug. Both probes now live in `ModelAvailability` (no
   `Context`, no views) and run on a `"model-probe"` thread; the result returns via
   `runOnUiThread` guarded by `isFinishing()/isDestroyed()` and a **generation**
   (`modelProbeGeneration`), so a stale result cannot overwrite fresh state, and a
   `modelProbeInFlight` flag avoids repeating the probe in the cold-start
   `onCreate`+`onResume` pair. While it runs, the status `TextView` shows
   `status_checking` ("Checking assets…", already its default value in the layout)
   and the engine overwrites it with "Ready" when `initNative` finishes. Coverage:
   `ModelAvailabilityTest` (10 JVM tests, including empty marker, name pointing at a
   nonexistent file, and name in the wrong directory). Detection behavior is unchanged
   (`exists()`, not `isFile()`); only the thread it runs on changed. Do **not**
   call the probes directly from the main thread again.
7. **Mandatory `mediaProjection` FGS ordering** (Android 14+): the Activity calls
   `createScreenCaptureIntent()` and the user grants → the service calls
   `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` → **only
   then** `MediaProjectionManager.getMediaProjection()`. Reversed, it throws
   `SecurityException` ("Media projections require a foreground service of type
   …") and `LiveSubtitleService` used to turn it into a silent `stopSelf()`.
   Documented at `developer.android.com/about/versions/14/changes/fgs-types-required`.

---

## 6. Commit conventions

- Style observed in `git log`:
  - `feat: <what>`, `fix: <what>`, `chore: <what>`, `ci: <what>`, `docs: <what>`, `refactor: <what>`, `build: <what>`.
  - Message language: English, concise imperative phrases, no trailing period.
- For user-visible changes deserving a release, **first draft the block in `RELEASE_NOTES.md` strictly within the 125–250 word limit**, then commit, then tag `vX.Y.Z` (CI fires the signed build and creates the GitHub Release with the notes).

---

## 7. TL;DR for a new AI

- Read `src/engine.rs` and `Cargo.toml` first.
- Respect JNI names (`Java_dev_notune_transcribe_<Class>_<method>`) and callback signatures in §4.4.
- Settings = marker files in `filesDir`. The API key NEVER goes in plaintext: it is stored encrypted (`pp_api_key_enc`, AES-256-GCM + AndroidKeyStore); the Base64 `pp_api_key` marker is only the legacy format the migration replaces (verify-before-delete, no plaintext fallback).
- **Credentials are read asynchronously on the `credential-ops` lane** (`SettingsManager.readApiKey` / `readPostProcessEnabled`): no component does credential I/O or decryption on the main thread, and "not yet read" (pending legacy import) **never** means "not configured". When deciding whether to post-process, use the cheap `isPostProcessSwitchedOn()` and leave the credential verdict to `PostProcessor`, which reads in order before the request.
- Post-processing: optional, mandatory *safe-fallback* to the raw text.
- Language is re-read on every `Engine::run` (do not cache it in memory inside the engine).
- Subtitles use a partial/final model with calibrated lag policies; raising those numbers breaks the contract.
- Historical bug to avoid: v0.1.20 → v0.1.21 (language cached in the engine). The "re-read on every run" rule is written in many comments on purpose.
- **The app is SINGLE-PROCESS.** There is no `android:process` and there must not be one (§1): the IME and the popup share one `cpal::Stream` and the global engine.
- **Android 17 is API 37** (Android 15 is API 35). `compileSdk`/`targetSdk` = 37, AGP 9.4.1, Gradle 9.8.0, NDK 28.2.13676358. Never describe API 35 as "Android 17".
- Release with active R8 + resource shrinking; if you touch reflection, JNI, or string-invoked names, update `app/proguard-rules.pro`.
- Touch targets ≥ 48dp and colors always via `?attr/color*` (no hex literals in layouts/drawables).
- Before a PR: `./gradlew :app:testDebugUnitTest`, `python3 scripts/check_translations.py` and `./gradlew :app:assembleDebug` must pass clean, and new strings must be in all 8 locales.
- Spanish (`es-ES`) is a first-class locale, not an afterthought: the ES+EN phonetic encoder (seseo/yeísmo, `ñ`→`ny`), the BCP-47 locale list, and the per-language WER reference in §5.2 exist so non-English transcription never silently regresses. Never "normalize" non-English fixtures or phonetic rules away.
- **Validate builds per environment (2026-08-06, updated 2026-10):** on a mobile/embedded-type host NEVER compile locally — `git push` to `main` and read the GitHub Actions debug workflow with `gh run list`/`gh run view`. On a real physical machine (laptop/desktop with SDK, NDK, Rust, and network) **do compile locally**; it is the preferred validation path.
