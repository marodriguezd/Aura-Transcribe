# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#requirements--compatibility)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Aura Transcribe is an on-device speech-to-text application and voice input service for Android. Powered by Rust, ARM NEON SIMD optimizations, and GGML inference kernels, it performs speech recognition locally on the device CPU without requiring an active network connection.

The application functions across multiple Android integration points, providing standard keyboard voice typing, a standalone input method editor (IME), a floating dictation overlay, live captions for system audio, and offline audio file transcription.

---

## Key Features

- **On-Device Speech Recognition:** Transcription runs 100% locally using a quantized [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) model (Q8_0 GGUF). Audio recordings never leave your device.
- **System-Wide Voice Typing:** Responds to Android's `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` to supply a bottom-sheet dictation panel in compatible keyboards (such as Microsoft SwiftKey) and browser voice fields.
- **Dedicated Input Method Editor (IME):** Includes a standalone voice keyboard for one-tap dictation across apps using standard input method switching.
- **Voice Recognition Service:** Implements the system `SpeechRecognizer` service API for third-party keyboard and app integrations.
- **Floating Dictation Overlay:** Provides a draggable microphone overlay (`SYSTEM_ALERT_WINDOW`) for dictation from any screen. When enabled, an optional Accessibility Service (`FloatingDictationAccessibilityService`) inserts transcribed text directly into the focused field of the active application. If accessibility access is not granted, text is copied to the clipboard.
- **Real-Time Live Subtitles:** Captions internal device audio streams (podcasts, media playback, video calls) locally via the system MediaProjection API, with optional on-device translation of finalized captions using Google ML Kit.
- **Audio File Transcription:** Transcribes pre-recorded audio files directly on-device.
- **Multi-Device Audio Routing:** Dynamically detects and routes audio from internal microphones, USB audio interfaces, and Bluetooth headsets (SCO and BLE Audio) with automatic switching and manual overrides.
- **Language Coverage:**
  - **User Interface:** Available in 7 languages (English base, German, Spanish, French, Italian, Portuguese, and Russian).
  - **Speech Recognition:** The built-in Nemotron model supports 40 language-locales natively with streaming auto-detection; 33 regional locales are directly selectable from the language picker. Additional user-imported GGUF models (e.g., Whisper) can support up to 99 languages.
- **Optional Text Post-Processing:** Configurable formatting and cleanup using external OpenAI-compatible API providers (OpenAI, Groq, Cerebras, OpenRouter, Mistral, Together, Ollama) or an untouched *Verbatim* preset.

---

## Privacy and Processing Behavior

- **Speech Recognition is 100% Local:** All acoustic model inference, audio processing, and transcription are executed entirely on your device. Microphone input and audio files are never uploaded to remote servers.
- **Optional Remote Text Post-Processing:** AI post-processing is **disabled by default**. If you choose to enable an external API provider (such as Groq or OpenAI), only the recognized **text** (never audio) is transmitted over HTTPS to your configured endpoint. API keys are stored locally in the application's private storage.
- **Verbatim and Offline Modes:** When post-processing is turned off or set to *Verbatim*, no text is sent over the network.
- **Release Telemetry and Logging:** In release builds, logging of raw transcription text and sensitive error details is stripped.

---

## Requirements & Compatibility

### Supported Android Devices
- **Operating System:** Android 8.0 (API 26) through Android 17 (API 37).
- **Target ABI:** `arm64-v8a` **only**, aligned for 16 KB memory pages.
- **Processor Requirements:** ARMv8.2-A CPU with `dotprod` and `fp16` vector extensions. Devices with 32-bit ARM processors or x86/x86_64 architectures are unsupported.

### Build Toolchain
- **JDK:** 17 (LTS)
- **Android SDK:** Platform `android-37` (Android 17)
- **Android NDK:** `28.2.13676358`
- **Rust Toolchain:** Rust `1.78.0+` (2021 edition) with target `aarch64-linux-android`
- **cargo-ndk:** Latest release (`cargo install cargo-ndk`)
- **CMake & Ninja:** Required for native build dependencies

Cross-compilation is supported on `linux-x86_64`, `linux-aarch64`, `darwin-x86_64`, `darwin-arm64` (Apple Silicon), and Windows hosts.

---

## Basic Usage

### 1. Voice Typing Popup
1. Open Aura Transcribe and grant the Microphone permission.
2. In any application, tap the microphone key on a compatible keyboard or inside a voice search input.
3. The bottom-sheet dialog will transcribe speech in real time and insert the final text upon completion.

### 2. Dedicated Voice Keyboard (IME)
1. Enable Aura Transcribe in **Android Settings → System → Languages & Input → On-Screen Keyboards**.
2. Switch to Aura Transcribe using your active keyboard's globe/switcher key.
3. Tap the recording surface to speak; transcribed text is committed directly to the active text field.

### 3. Floating Dictation Overlay
1. Start the overlay from the home screen and grant the system overlay permission (`SYSTEM_ALERT_WINDOW`).
2. Tap the floating microphone bubble to start and stop dictation.
3. To enable direct text insertion into active inputs across other apps, turn on the optional Accessibility Service under **Android Settings → Accessibility**. The application displays an explanatory disclosure before navigating to system settings. If disabled, transcribed text is copied to the clipboard instead.

### 4. Live Subtitles
1. Tap **Start Live Subtitles** on the home screen.
2. Approve the standard Android system MediaProjection prompt (*"Start recording or casting?"*) to begin captioning internal device audio.

---

## Keyboard Interoperability

| Keyboard Application | Compatibility Mode | Notes |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech Intent Popup | Opens the bottom-sheet panel directly. *(Disable "Multi-modal voice typing" in SwiftKey Rich Input settings)* |
| **AnySoftKeyboard** | Speech Intent Popup | Microphone key launches the bottom-sheet panel. |
| **HeliBoard / FlorisBoard** | IME Switcher | Microphone key switches directly to the Aura Transcribe voice keyboard. |
| **FUTO Keyboard** | IME Switcher | Switch to Aura Transcribe via the globe/input switcher key. |
| **Fossify / OpenBoard** | IME Switcher | Switches to the Aura Transcribe voice keyboard. |
| **Gboard** | System IME Switcher | Gboard's microphone button is locked to Google Speech Services; switch to Aura Transcribe using the system keyboard switcher. |

---

## Building from Source

### 1. Clone the Repository
```bash
git clone https://github.com/marodriguezd/android_transcribe_app.git
cd android_transcribe_app
```

### 2. Download and Verify Built-in Models
The bundled Nemotron GGUF model is verified by SHA-256 before packaging:
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. Build Native Rust Libraries
Compile the native `cdylib` and JNI bindings for `arm64-v8a`:
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*(Note: `./gradlew assembleDebug` will invoke this automatically if the `.so` library is missing.)*

### 4. Assemble Debug APK
```bash
./gradlew assembleDebug
```
The output APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### 5. Run Verification Gates
```bash
./gradlew :app:testDebugUnitTest        # JVM unit tests (does not build the native library)
./gradlew :app:lintDebug                # Android Lint checks
./gradlew :app:checkModels              # SHA-256 of the bundled model source asset
./gradlew :app:checkNativeAlignment     # 16 KB page alignment of every shipped .so
./gradlew :app:verifyPackagingDecision  # APK/AAB decision + model gates, on synthetic archives
./gradlew :benchmark:assembleBenchmark  # Compiles the Macrobenchmark module (no device needed)

python3 scripts/check_translations.py          # Translation parity across the 7 locales
python3 scripts/test_verify_release_artifact.py # Self-test of the release-artifact verifier
python3 scripts/bench_performance.py           # Reference-algorithm micro-benchmarks
cargo fmt --all -- --check                     # Rust formatting
```

`verifyPackagingDecision` and `test_verify_release_artifact.py` are the two gates
that protect the bundled-model delivery: they run without downloading the 750 MB
model, because they exercise the decision logic and the verifier against
synthetic archives instead. `bench_performance.py` times pure-Python stand-ins
for the audio/corrector hot paths and **asserts** that each fast path still
agrees with its naive reference — it is a correctness gate, not a measurement of
on-device performance.

### 6. Build Release Artifacts
Build release artifacts in **separate invocations** to ensure proper asset packaging:
```bash
# Build standalone release APK (model bundled in base archive)
./gradlew assembleRelease

# Build release App Bundle (model delivered via install-time asset pack)
./gradlew bundleRelease
```

Without an authorized `release.keystore` (plus `STORE_PASS`, `KEY_ALIAS`,
`KEY_PASS`) `assembleRelease` succeeds but produces an **unsigned**
`app-release-unsigned.apk`. That is a source build, not a distributable release:
F-Droid-style buildservers compile from source and sign with their own key. If a
keystore is present but any of the three credentials is missing, the build fails
immediately rather than falling back to a debug or default key.

---

## Continuous Integration and Release Publication

| Workflow | Trigger | Produces | Publishes |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | Push to `main`/`feat/**`, any pull request, manual | Debug APK signed with the repository's debug key | A workflow artifact on every run; the APK is sent to the Telegram bot on pushes and manual runs only — pull requests get the gates and the artifact, never a notification |
| [`android_release.yml`](.github/workflows/android_release.yml) | Tag `v*`, manual dispatch | Signed release **APK and AAB** | A GitHub Release with both files, and only after every gate passes |

### Why the APK and the AAB are built separately

An APK and an AAB need different asset layouts for the same release variant. AGP
ignores asset packs when packaging an APK, so the APK must carry the GGUF in the
base module; the AAB must carry it only in the `:model_assets` install-time asset
pack, or Play would receive roughly 750 MB twice. `app/build.gradle.kts`
accordingly adds `model_assets/src/main/assets/` to the release source set only
for the APK path. A Gradle invocation that asks for both artifacts at once is
rejected at configuration time with a message naming the offending tasks.

### Checks CI enforces automatically

On every push to `main`/`feat/**` and on every pull request the debug workflow
runs `cargo fmt --all -- --check`, `scripts/check_translations.py`,
`scripts/test_send_telegram.py`, `scripts/test_verify_release_artifact.py`,
`scripts/bench_performance.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`, `:app:checkModels`, `:app:checkNativeAlignment`,
`:app:verifyPackagingDecision` and `:benchmark:assembleBenchmark`. Every step that
pipes through `tee` also sets `-o pipefail`, so a filter cannot turn a red gate
green.

The release workflow runs the same test, translation, formatting and
packaging-decision gates first, then additionally:

- refuses to run when any of the four signing secrets is absent, before the expensive build;
- refuses to build if the model-less release escape hatch appears in a workflow file or in an `ORG_GRADLE_PROJECT_*` environment variable;
- runs `:app:lintDebug` and `:app:checkNativeAlignment` before packaging, so a misaligned dependency fails fast;
- verifies the packaged APK with `zipalign -c -P 16 -v 4` (the 16 KB requirement of Android 17) and `apksigner verify --print-certs`, and fails if the signer's SHA-256 equals the repository debug key's;
- verifies the packaged AAB with `jarsigner -verify -certs` and `keytool -printcert -jarfile` (the output is inspected, because `jarsigner -verify` exits `0` for an unsigned bundle) and applies the same debug-key rejection;
- re-verifies the model inside each built artifact with `scripts/verify_release_artifact.py`, which compares the packaged bytes against the verified source asset by content hash and rejects a model that is missing, duplicated, in the wrong directory, truncated or corrupted.

Publication (`Upload`/`Create Release`) happens only after all of the above, so a
failed signing or model check cannot publish an artifact.

### Checks that have not run

Instrumentation tests, Macrobenchmark measurements, Baseline Profile generation,
and runtime validation on tablets/foldables all require a device or emulator. No
arm64 device or emulator is available in this environment, so none of them have
been executed; `:benchmark:assembleBenchmark` **compiling** is not evidence of
measured runtime performance. No Macrobenchmark timing or Baseline Profile is
published here, and none should be assumed.

The release workflow's signing path has likewise not been exercised end to end
with real production credentials in this environment (no `release.keystore`,
`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS` or `KEY_PASS` is available locally).
What is verified locally is the failure behavior: without credentials the output
is unsigned and unpublishable, and an artifact signed with the repository debug
key is rejected.

---

## Model Packaging Notes

The release APK and the Android App Bundle (AAB) use distinct model delivery strategies:
- The **Release APK** bundles the model file inside the base module (`assets/builtin-model/`).
- The **Release AAB** delivers the model via the `:model_assets` install-time asset pack.

To prevent packaging errors, the build configuration deliberately rejects running both tasks in a single command (e.g., `./gradlew assembleRelease bundleRelease`). Always build each artifact in its own command.

Both artifacts are additionally verified by opening them after packaging:
`verifyReleaseApkModel` (after `packageRelease`) and `verifyReleaseBundleModel`
(after `signReleaseBundle`) require the GGUF to be present **exactly once** — the
bundle-to-APK duplicate layout is detected because the AAB is matched by the
`assets/builtin-model/…` suffix, so a copy under `base/` as well as one in the
asset pack counts as a duplicate. `scripts/verify_release_artifact.py` performs
the same check independently on an already-built artifact, comparing content
hashes against the source asset.

The only way to produce a release APK without the model is to pass
`-Pauratranscribe.allowModelLessReleaseApk=true`, which prints that the result is
not distributable. It exists for local experiments; the release workflow greps
for it and refuses to build. An AAB has no such opt-out.

---

## Known Limitations

- **On-Device S1-mini Post-Processing is Unavailable:** While configuration options, model download paths, and prompt templates exist in the codebase for S1-mini, on-device text generation is not implemented in this build. The current inference backend (`transcribe-cpp`) is specialized for speech recognition and does not support causal language model generation. The local S1-mini provider is disabled in the settings UI.
- **Hardware Architecture Restriction:** The application only runs on 64-bit ARM hardware (`arm64-v8a`) with ARMv8.2 dotprod/fp16 support. It does not run on x86 or x86_64 emulators or devices.
- **Accessibility Insertion Support:** Direct text insertion via the floating overlay relies on Android's Accessibility framework. Non-standard or web-based text fields that do not expose editable accessibility nodes may require manually pasting from the clipboard.

---

## Attribution and License

Aura Transcribe is open-source software licensed under the [MIT License](LICENSE).

```text
MIT License

Copyright (c) 2025 Noah Mühl
```

### Upstream Project

This repository is an independent fork of [Offline Voice Input](https://github.com/notune/android_transcribe_app) (`notune/android_transcribe_app`), originally created by Noah Mühl and based on upstream release [v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28).

Subsequent additions and architectural changes in this fork—including multi-device audio routing, floating dictation overlays, optional remote text post-processing, and multi-surface integrations—were developed independently and are not associated with the original upstream author.

### Third-Party Credits

- **Speech Model Architecture:** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) by NVIDIA, quantized to GGUF (Q8_0) by [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) (licensed under CC-BY 4.0).
- **Inference Kernels:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) and [ggml](https://github.com/ggerganov/ggml) by CJ Pais and Georgi Gerganov.
- **Audio Capture:** [cpal](https://github.com/RustAudio/cpal) (Cross-Platform Audio Library).
