# AGENTS.md — Implementation Contract for AI Assistants

> Repository: `Aura-Transcribe` (fork of `notune/android_transcribe_app`)
> Runtime package: `com.auratranscribe.app` (internal Java/JNI namespace: `dev.notune.transcribe`)
> Version in `app/build.gradle.kts`: `1.0.3` (`versionCode 54`). Check tags/releases before assuming a version is published.
> Language: `AGENTS.md` is written in **English**. Multilingual product support (ES+EN phonetic encoder with seseo/yeísmo and `ñ`→`ny`, diacritics folding, 8 UI locales, 40 ASR locales) is an intentional core feature — never strip non-English phonetic rules or locale fixtures.

---

## 1. Architecture Overview (SINGLE-PROCESS INVARIANT)

Aura Transcribe performs **100% offline on-device speech recognition** in Rust (`cdylib`) over `transcribe-cpp` (ggml) and `cpal`, exposed to Android Java UI/services through JNI:

| Surface | Integration Mechanism | Key Files |
|---|---|---|
| **Voice popup** | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` | `RecognizeActivity.java` + `src/recognize.rs` |
| **Recognition service** | Android `SpeechRecognizer` service | `VoiceRecognitionService.java` + `src/recog_service.rs` |
| **Voice keyboard (IME)** | `BIND_INPUT_METHOD` | `RustInputMethodService.java` + `src/ime.rs` |
| **Floating overlay** | `SYSTEM_ALERT_WINDOW` + optional Accessibility paste | `FloatingOverlayService.java`, `FloatingDictationAccessibilityService.java` + `src/floating.rs` |
| **Live subtitles** | `MediaProjection` system audio capture + ML Kit translate | `LiveSubtitleService.java`, `OnDeviceSubtitleTranslator.java` + `src/subtitle.rs` |
| **File transcription** | `ACTION_SEND` / `ACTION_VIEW` (`audio/*`) | `TranscribeFileActivity.java`, `FileAudioChunks.java` + `src/transcribe_file.rs` |
| **Models & setup** | GGUF import/download & language configuration | `ModelsActivity.java`, `MainActivity.java` + `src/models.rs`, `src/main_activity.rs` |

- **CRITICAL — Single-process architecture:** `AndroidManifest.xml` declares NO `android:process`. **NEVER add `android:process=":ime"` or split services into separate processes.** `voice_session.rs` deliberately shares one `cpal::Stream` between IME and popup, and all surfaces share one global `Arc<Mutex<Engine>>`.
- **Settings persistence:** Marker files under `getFilesDir()` (atomic via `MarkerFileHelper`), **not** `SharedPreferences`, because Rust reads settings directly from the filesystem regardless of which Android component initializes first. The sole exception is the encrypted post-processing API key (`pp_api_key_enc`).

---

## 2. Technology Stack & Pinned Versions

| Layer | Technology | Pinned Version / Contract |
|---|---|---|
| Native language | **Rust** (edition `2021`, `crate-type = ["cdylib"]`) | Workspace root + `crates/aura-core` |
| ASR backend | `transcribe-cpp` (ggml + whisper/nemotron fork) | `0.1.3` |
| Audio capture | `cpal` (native) + `AudioRecordBridge` (`VOICE_COMMUNICATION`) | `0.15` |
| JNI / Concurrency | `jni` `0.21`, `crossbeam-channel`, `once_cell`, `anyhow`, `log` + `android_logger` (`Info`) | No `.unwrap()`/`.expect()` across JNI |
| Android UI / HTTP | **Java 17** (no Kotlin), Material Components `1.14.0`, OkHttp `4.12.0` | Non-transitive R classes (`androidx.appcompat.R.attr.*` vs `com.google.android.material.R.attr.*`) |
| Live-subtitle translation | ML Kit Translate (`com.google.mlkit:translate`) | `17.0.3` — **do not downgrade** (`17.0.2` has 4 KB-aligned `libtranslate_jni.so`) |
| Build toolchain | AGP `9.4.1`, Gradle `9.8.0`, Android NDK `28.2.13676358`, `cargo-ndk` | Supported hosts: `linux-x86_64`, `darwin-x86_64`, `darwin-arm64`, `windows` |
| Android SDK / ABI | `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`, ABI `arm64-v8a` only | **Android 17 = API 37** (API 35 = Android 15); 16 KB ELF alignment enforced |
| Release shrinker | R8 full mode (`isMinifyEnabled = true`, `isShrinkResources = true`) | Keep rules in `app/proguard-rules.pro` |
| Credential storage | AES-256-GCM (`v1:` in `pp_api_key_enc`) + AndroidKeyStore | `SecureCredentialStore` (`androidx.security.crypto` `1.1.0-alpha06` legacy migration read only) |
| Kotlin stdlib alignment | Pinned to `1.8.22` via `constraints` in `app/build.gradle.kts` | Prevents transitive duplicate classes |
| Locales | 8 complete `values*` locales: EN (base), ES, DE, FR, IT, PT, RU, ZH (`values-zh-rCN`) | Plus `values-night/`, `values-w600dp/`, `values-w840dp/` |

- **Removed / forbidden permissions:** ❌ **Never reintroduce `READ_USER_DICTIONARY`** (not in public Android SDK; always throws `SecurityException` on `ContentProvider`). Backup is disabled (`allowBackup="false"`, `dataExtractionRules.xml` and `fullBackupContent.xml` exclude everything with `path="."`).

---

## 3. Build Wiring, Packaging Gates & CI/CD

### Native & Bundled-Model Build Wiring (`app/build.gradle.kts`, `.cargo/config.toml`)
- `cargoNdkBuild` attaches to `preBuild` only when `needsNativeBuild(requestedTaskNames)` returns `true` (pure `test*`/`lint*`/`check*`/`verify*` skip it). Delete `app/src/main/jniLibs/arm64-v8a/libandroid_transcribe_app.so` to force a native rebuild.
- `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` is pinned in `cargoNdkBuild`, and `.cargo/config.toml` passes `-C link-arg=-Wl,-z,max-page-size=16384`, `-C link-arg=-lc++_shared`, and `-C target-feature=+neon,+fp16,+dotprod`. **Never lower these**: quantized inference drops ≥4× slower or `check_cpu_features` rejects incompatible CPUs cleanly at load time. `libc++_shared.so` is copied from NDK `28.2.13676358` into `app/src/main/jniLibs/arm64-v8a/`.
- **Mutually exclusive APK vs AAB model delivery:**
  - **Release APK:** GGUF (`~560 MB` Q5_K_M) in base module (`assets/builtin-model/<name>`), because AGP ignores asset packs when building APKs. Debug builds intentionally ship without the GGUF.
  - **Release AAB:** GGUF ONLY in `:model_assets` install-time asset pack (`model_assets/assets/builtin-model/<name>`).
  - Requesting APK and bundle tasks in the same Gradle invocation fails at configuration time (`packagingDecision == "ambiguous"`). **Always invoke `assembleRelease` and `bundleRelease` separately.**
  - `downloadModels` downloads the GGUF into `model_assets/src/main/assets/builtin-model/` only when `shouldWireModelDownload(requestedTaskNames)` is `true` (`needsBundledModel && !isVerificationOnlyRun`). `copyModelLicense` copies root `MODEL_LICENSE.md` and `MODEL_NOTICE.md` into `model_assets/src/main/assets/`.
  - **Artifact & logic gates:** `verifyReleaseApkModel`, `verifyReleaseBundleModel`, `verifyPackagingDecision`, `verifyModelDownloadDecision`, `verifyNativeAlignmentGate`, `checkNativeAlignment`, `checkModels`, plus independent `scripts/verify_release_artifact.py` and `scripts/verify_artifact_notices.py`. `-Pauratranscribe.allowModelLessReleaseApk=true` is forbidden in CI and only skips missing-model checks locally (never misplaced paths; AAB has no opt-out).

### Distribution Channels, CI & Signing Rules (SET-IN-STONE)
- **Active channels ONLY:**
  1. `.github/workflows/debug_telegram.yml` — runs validation + debug build on `main`/`feat/**`/PR/`workflow_dispatch`, delivers debug APK via Telegram bot (never on PRs).
  2. `.github/workflows/android_release.yml` — builds and verifies signed Release **APK and AAB** via `vX.Y.Z` tags or manual `workflow_dispatch`.
- **Mandatory release guards (enforced by `scripts/check_release_workflow.py`):**
  - `workflow_dispatch` requires `validation_only` boolean defaulting to `true`.
  - Both `Upload APK Artifact`, `Upload AAB Artifact`, and the single `Create Release` step (`softprops/action-gh-release`) must carry `if: startsWith(github.ref, 'refs/tags/v') && (github.event_name == 'push' || inputs.validation_only == false)` (`PUBLISH_GUARD`).
  - `Extract Version Name` + `Verify tag matches Gradle version` run early in `build-release` before toolchains/signing/packaging.
  - All 4 signing secrets (`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS`, `KEY_PASS`) are checked for presence before building; decoded keystore + final APK/AAB certificates are verified by `scripts/verify_signing_identity.py` against `keystore/release-cert.sha256`.
  - Debug-key rejection (`keystore/debug.keystore`), `zipalign -c -P 16 -v 4`, `apksigner verify --print-certs`, and `jarsigner -verify -certs` (output inspected for `jar is unsigned`) + `keytool -printcert -jarfile` run before upload/publish.
  - Local clean clones without `release.keystore` build an unsigned APK (`hasReleaseKeystore == false`); if `release.keystore` exists without all 3 env vars on a release-artifact task, Gradle fails closed.
- **`RELEASE_NOTES.md` rule:** Contains **only the version being published**, strictly **125–250 words**, concise technical impact; full history belongs solely in `CHANGELOG.md`. Enforced by `scripts/check_release_notes.py`.
- **`set -e -o pipefail`:** Mandatory on every CI shell step using `| tee`.
- **Environment-based validation rule:**
  - **Mobile / embedded ARM64 host (e.g. Termux without KVM): NEVER run `./gradlew`, `cargo build`, `cargo check`, `cargo ndk`, or `cargo test` locally** (can lock up the host). Run lightweight gates locally (`cargo fmt --all -- --check`, `python3 scripts/check_*.py`, `python3 scripts/test_*.py`, `cmp`, `git`), commit/push to `feat/**` or `main`, and verify full compilation/tests/lint/alignment in GitHub Actions via `gh run list` / `gh run view`.
  - **Desktop / laptop with SDK/NDK/Rust:** Run local Gradle/Cargo checks directly before pushing.

---

## 4. Code Conventions & Critical Subsystem Contracts

### 4.1 JNI Boundary (`Java_dev_notune_transcribe_<Class>_<method>`)
- **Strict parity:** Every Java `native` declaration and Rust `#[no_mangle] pub unsafe extern "system" fn` export (**29 declarations/exports**) is checked by `scripts/check_jni_parity.py`. Never rename/add/remove one side without the other. Keep JNI symbols covered in `app/proguard-rules.pro`.
- **Load order:** Every surface holding `native` methods runs `System.loadLibrary("c++_shared")` (inside `try/catch (UnsatisfiedLinkError)`) followed by `System.loadLibrary("android_transcribe_app")`.
- **Callbacks:** Keep exact method names/signatures on Java targets: `onStatusUpdate(String)` / `(String, int)`, `onTextTranscribed(String)` / `(String, int)`, `onAudioLevel(float)` / `(float, int)`, `onRmsChanged(float)`, `onSubtitleText(String, boolean)`, `onPartialText(String)` / `(String, int)`, `onAutoStop()` / `(int)`.
  - `RecognizeActivity`, `RustInputMethodService`, and `FloatingOverlayService` use integer `sessionId` generation guards to drop stale callbacks.
  - `TranscribeFileActivity` uses a per-decode monotonic `opId` (`AtomicInteger`) and `isFinishing()/isDestroyed()` checks.
- **Safety:** `src/jni_util.rs` clears pending JNI exceptions (`env.exception_check()`/`exception_clear()`); `assets.rs` clears JNI exceptions and closes Java `InputStream`s deterministically. `engine::transcribe_shared` wraps execution in `panic::catch_unwind` and poisoned-`Mutex` recovery (`unwrap_or_else(|p| p.into_inner())`). Never drop those layers or panic inside `cpal` callbacks (audio callback mutex writes must stay O(1)).

### 4.2 Rust ↔ Java Module Mapping

| Rust module (`src/*.rs`) | Java component | Notes |
|---|---|---|
| `lib.rs` | — | Root crate exports (`pub mod ...`) |
| `engine.rs` | Global `Engine` singleton | `Condvar` coordinated load; `LoadState::Failed(msg)`; **re-reads `model_language` on EVERY `run()` (NEVER cache)**; retries `att_context_right` with `None` on `InvalidArgument` |
| `audio.rs` | — | `find_quietest_split`, signal helpers |
| `voice_session.rs` | `RecognizeActivity`, `RustInputMethodService` | Shared `cpal::Stream`, `VoiceSessionState` `Drop` cleanup, generation-checked session state |
| `ime.rs` / `recognize.rs` / `floating.rs` | `RustInputMethodService` / `RecognizeActivity` / `FloatingOverlayService` | Thin JNI bridges + session teardown (`switchKeyboardButton` / `onWindowShown` / `onDestroy` release mic) |
| `recog_service.rs` | `VoiceRecognitionService` | Opens mic & calls `s.play()` BEFORE `onReadyForSpeech`; Release/Acquire atomics; restores audio slice under `stream_lock` on mid-stream error |
| `subtitle.rs` | `LiveSubtitleService` | Dual-pipeline (`StreamInput` FIFO vs batch fallback), `SubtitleVad` rolling noise floor, `SubtitlePipelineState` generation & one-way degradation |
| `models.rs` | `ModelsActivity` | Imports/lists GGUF models in `filesDir()/models/`, triggers `engine::reset()` |
| `transcribe_file.rs` | `TranscribeFileActivity` | Chunked decoding with `opId`, lock-free PCM copy, previous cancel flag cancelled on re-init/cleanup |
| `corrector.rs` (+ `crates/aura-core`) | (`CustomWordsActivity`) | ES+EN phonetic key (`seseo`/`yeísmo`, `ñ`→`ny`, accented vowels folded, `gü`→`gw`) + char-count Levenshtein ≤ 2 + bigram tiebreak; invalidates cache if `metadata`/`modified()` fails |
| `assets.rs`, `jni_util.rs`, `main_activity.rs` | `MainActivity`, internal | Bundled GGUF extraction check (closes stream on all error paths), JNI callback helpers |

### 4.3 Settings Markers & Encrypted Credentials
- Plain markers in `filesDir()` (write via `MarkerFileHelper` unique temp + `fsync` + atomic rename): `auto_record`, `select_transcription`, `pause_audio`, `stop_on_hide`, `auto_stop`, `model_language`, `model_translate`, `active_model`, `model_threads`, `stream_context_right` (`13`/`6`/`1`/`0`), `hardware_backend` (`cpu` only — no NNAPI/Vulkan backend exists), `custom_words`, `pp_prompt_profile`, `pp_prompts/<id>` (via `PromptProfiles`, strips `.tmp`), `subtitle_translation_target`, `subtitle_source_language`.
- **Encrypted API key (`pp_api_key_enc`):** AES-256-GCM + AndroidKeyStore (`SecureCredentialStore`). Legacy migration (`CredentialMigration`) uses verify-before-delete. All reads & writes run asynchronously on `CredentialOperations` (`credential-ops` thread) via `SettingsManager.readApiKey`/`setApiKey`/`readPostProcessEnabled` — **never read credentials synchronously on the main thread**. Deleting sets the `deleted` tombstone before removal and `store()`/`migrate()` enforce it strictly (never falling back to plaintext or legacy copies; **Rust never sees the API key**).

### 4.4 Post-Processing, Subtitles, Audio Routing & UI Contracts
- **Post-Processing (`PostProcessor.java`):** Optional cloud OpenAI-compatible HTTP only, OFF by default. `pp_default_prompt` MUST stay a single XML line with `\n` escapes (AAPT2 collapses literal newlines). `${output}` injects once (`injected` guard avoids duplicate transcript). Final-only (never post-process `onPartialText`), per-owner cancellation via `CallRegistry` (`cancelAllFor(owner)`), mandatory raw-text fallback on error/cancel/timeout, `Authorization` header dropped on cross-host HTTP redirects, raw transcript logs gated behind `BuildConfig.DEBUG`.
- **Live Subtitles (`LiveSubtitleService`, `OnDeviceSubtitleTranslator`, `SourceLanguageResolver`, `SubtitleDisplayStabilizer`):**
  - Pipeline chosen once per session (native 80 ms streaming vs batch fallback; one-way permanent degradation on stream failure).
  - Do not raise `MAX_FINAL_LAG_SAMPLES = 8 s`, `MAX_PARTIAL_LAG_SAMPLES = 3 s`, or `MAX_PARTIAL_COST_SECS = 2.0` without device testing.
  - **Mandatory `mediaProjection` FGS order (Android 14+):** `createScreenCaptureIntent()` consent → `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` → `MediaProjectionManager.getMediaProjection()`. Stop service on capture setup failure.
  - Translation uses `subtitle_translation_target` (never `model_translate`), serial FIFO queue + LRU cache, `mUtteranceGeneration` + `OnDeviceSubtitleTranslator.generation` cancellation counters (never caches raw contextual payload on error), `SourceLanguageResolver` lowercases with `Locale.ROOT`, `WindowManager` updates guarded against detached views.
- **Audio Input Routing (`AudioRoutingPolicy`, `AudioDeviceManager`, `AudioRecordBridge`):**
  - Only capture-capable (`isSource()`) devices; `TYPE_BLE_SPEAKER` is excluded everywhere.
  - Separate API 31+ communication-device selection (`selectCommunicationCandidate` / `hasBluetoothCommunicationDevice`) from `AudioRecord.setPreferredDevice` input selection (`GET_DEVICES_INPUTS`).
  - `acquireMicrophone` returns `false` only when Bluetooth-only fails platform confirmation (leaving clean state and surfacing `mic_bluetooth_unavailable`); restores prior `AudioManager` mode on `releaseMicrophone`.
  - No blanket `catch (Throwable)` in routing paths. `AudioRecordBridge` holds `volatile AudioRecord`, releases uninitialized records cleanly, and gates `release()` in `stop()` on `!captureThread.isAlive()`.
- **UI, File Decode & Accessibility:**
  - M3 DayNight + `DynamicColors`; style everything with `?attr/color*` (no hex literals except documented overlay scrims / semantic status). Wrap `RustInputMethodService` & `FloatingOverlayService` context explicitly with `ThemePrefs.wrapForNight` + `DynamicColors.wrapContextIfAvailable`.
  - Minimum **48 dp × 48 dp** touch targets (never `android:minWidth/Height="0dp"`). Preserve `resultPending` as the single source of truth for showing `ime_cancel` after stop.
  - `MainActivity` probes models off the main thread (`ModelAvailability` on `"model-probe"` with generation guard). `TranscribeFileActivity` decodes bounded 30s chunks with `opId`, restricts fallback URI scheme to `content`/`android.resource`, and stops waiting cleanly on decode `"Error"`. `FloatingDictationAccessibilityService` synchronizes `mLastFocusedNode`, ignores own-package events, and sets cursor selection via `ACTION_SET_SELECTION`.

---

## 5. Legal & Packaging Verification Rules

- **Dual licensing:** App source is **MIT** (`LICENSE`, `UPSTREAM-MIT.txt`, `Cargo.toml`, `crates/aura-core/Cargo.toml`, `README.md`, `ABOUT.md`). Bundled Nemotron GGUF (`nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`) is **OpenMDW-1.1** (`MODEL_LICENSE.md`, `MODEL_NOTICE.md`, `licenses/OpenMDW-1.1.txt`) — **never describe the model as MIT or CC-BY-4.0**, and **never distribute audio fixtures**.
- **Pinned download integrity:** Keep Hugging Face commit `53ce288f69326b415aeef030cfcb02a08a8096dd` and SHA-256 `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65` synchronized across `app/build.gradle.kts`, `MainActivity.java`, `MODEL_LICENSE.md`, and `MODEL_NOTICE.md`.
- **Immutable/synced notices:** Never edit `LICENSE`, `MODEL_NOTICE.md`, or `licenses/*`. Keep `THIRD_PARTY_NOTICES.md` and `MODEL_LICENSE.md` byte-identical to their packaged copies in `app/src/main/assets/` and `model_assets/src/main/assets/`. Enforced by `scripts/check_license_docs.py` and `scripts/verify_artifact_notices.py`.

---

## 6. Guardrails, Known Limits & Commit Format

- **DO NOT:** change JNI callback signatures without updating all surfaces; cache `model_language` in `Engine`; drop `catch_unwind`/poison recovery in `transcribe_shared`; add x86/armeabi-v7a ABIs without CPU feature gating; store credentials in plaintext or read them on the UI thread; use literal newlines in `pp_default_prompt`; remove bundled-model APK/AAB separation; commit secrets or large binaries (`scripts/check_repo_hygiene.py`).
- **DO:** keep `kotlin-stdlib` `1.8.22` constraint; add UI strings to all 8 `values*/strings.xml` locales (verified by `scripts/check_translations.py`); write markers via `MarkerFileHelper`; keep `lintDebug` clean (`abortOnError = true`, justify any new suppressions in place).
- **Adding a new JNI surface:** `static` load order (`c++_shared` → `android_transcribe_app`), Java `native` methods + matching `Java_dev_notune_transcribe_<Class>_<method>` exports in `src/<mod>.rs` (`lib.rs` `pub mod`), register in `AndroidManifest.xml` (no `android:process`), update `app/proguard-rules.pro` if invoked reflectively/by name, keep `scripts/check_jni_parity.py` green.
- **Known limits:** No arm64 device/emulator exists in GitHub Actions (JVM unit tests run in CI; `:app:assembleDebugAndroidTest` compiles instrumentation tests without executing them on hardware). Hardware Bluetooth routing and foldable/tablet runtime behavior require physical device smoke testing.
- **Commits:** Conventional imperative English without trailing period (`feat:`, `fix:`, `chore:`, `ci:`, `docs:`, `refactor:`, `build:`).
