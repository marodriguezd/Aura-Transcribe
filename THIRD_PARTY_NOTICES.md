# Third-Party Notices and License Scope

This file identifies material in Aura Transcribe that does not all share one license. The repository-root `LICENSE` applies GPL-3.0-only to the Aura Transcribe code authored for this fork, except where a component below is explicitly identified as third-party material under separate terms. **This file does not relicense third-party content.**

## 1. Upstream application code

Aura Transcribe is an independent fork of [Offline Voice Input](https://github.com/notune/android_transcribe_app), based on upstream release [v0.1.18 / commit d95e3a7e5555fa7309975751dc790e2c96387a28](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28), originally created by Noah Mühl.

- Original upstream code remains subject to its MIT license and original copyright notice.
- A copy of the original upstream MIT license is preserved as [`licenses/UPSTREAM-MIT.txt`](licenses/UPSTREAM-MIT.txt).
- When redistributing source or binaries containing upstream portions, preserve the applicable MIT notices and license text. GPL-3.0-only applies to the fork's own code and modifications to the extent the corresponding rights are held by the contributor; it does not erase the upstream license obligations.

## 2. Bundled speech-recognition model

- **Base model:** [NVIDIA Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).
- **Quantized asset:** [handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf).
- **Terms:** OpenMDW License Agreement 1.1 (OpenMDW-1.1), not CC-BY 4.0. See [`MODEL_LICENSE.md`](MODEL_LICENSE.md), which reproduces the applicable model terms and is packaged with the model asset.
- **Current configured artifact:** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`; SHA-256 `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65`.

The model weights are treated as a separately licensed data asset, not relicensed under GPL. The OpenMDW FAQ says a distribution may include independently licensed materials when their scopes are explicit, while recommending care with copyleft licenses. See the [OpenMDW FAQ](https://openmdw.ai/faq/), especially “Is OpenMDW-1.1 compatible with other open-source licenses?” and “Can a distribution that uses OpenMDW-1.1 also include materials that are under other licenses?”. This is a documented licensing boundary, not a substitute for legal advice on the combined APK/AAB distribution.

## 3. Native runtime dependencies

The currently declared Rust runtime dependencies include:

- `transcribe-cpp` 0.1.3 — MIT; see [`licenses/transcribe-cpp-MIT.txt`](licenses/transcribe-cpp-MIT.txt).
- `ggml`, supplied by the `transcribe-cpp` dependency — MIT; see [`licenses/ggml-MIT.txt`](licenses/ggml-MIT.txt).
- `cpal` — Apache-2.0; see [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).
- Other direct crates declared in `Cargo.toml` (`anyhow`, `log`, `android_logger`, `jni`, `libc`, `once_cell`, `crossbeam-channel`, `strsim`) use permissive upstream license options, commonly MIT and/or Apache-2.0. The workflow generates `build/reports/cargo-licenses.md` from Cargo's resolved metadata and `app/build/reports/dependency-license/` from the Android `releaseRuntimeClasspath`; both reports are uploaded in the `aura-transcribe-dependency-license-inventories` artifact. Review the report for missing declarations and verify the license text at the actual dependency version; Cargo manifest metadata alone is not proof of license compatibility.

The Android runtime dependencies declared in `gradle/libs.versions.toml` and `app/build.gradle.kts` include AndroidX libraries, Material Components, OkHttp, Kotlin standard-library artifacts, and Google ML Kit Translate. Their upstream notices and any transitive notices must be retained according to their actual resolved artifact metadata. Google ML Kit Translate also has separate [usage and attribution guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms); those provider requirements are not replaced by the application license.

The current build declares `org.json:json`, JUnit, and MockWebServer as test-only dependencies. In particular, `org.json:json` is confined to `testImplementation`; do not move it into the shipped runtime without a separate license review. The Android application uses the platform's `org.json` API at runtime. Build tools, test frameworks and test-only libraries are not intended to be bundled in the release APK/AAB.

## 4. Inherited visual assets, benchmark data and audio fixtures

### Byte-identical assets carried over from upstream

A Git blob comparison against `notune/android_transcribe_app` identifies the following visual assets in this fork as byte-for-byte identical to files in the upstream repository. They should retain their upstream attribution and license context rather than being presented as newly created Aura assets:

- Screenshots: `.screenshots/screenshot_home.png`, `.screenshots/screenshot_recording.png`, `.screenshots/screenshot_subtitles.png`.
- Store artwork: `fastlane/metadata/android/en-US/images/icon.png` and `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, and `3.png`.
- Drawable icons: `app/src/main/res/drawable/ic_backspace.xml`, `ic_check_circle.xml`, `ic_close.xml`, `ic_delete.xml`, `ic_error.xml`, `ic_mic.xml`, `ic_model.xml`, `ic_palette.xml`, `ic_settings.xml`, and `ic_subtitles.xml`.

The same blob comparison also identifies inherited non-image files, including `src/models.rs`, `app/src/main/res/layout/item_model_link.xml`, `app/src/main/res/xml/recognition_service.xml`, `.cargo/config.toml.example`, and upstream Fastlane changelogs. These are still upstream-derived, even when subsequently modified files nearby are part of the fork's own work. The original MIT license is preserved in `licenses/UPSTREAM-MIT.txt`.

### Benchmark data

- The benchmark manifest identifies the external [Google FLEURS corpus](https://huggingface.co/datasets/google/fleurs) as **CC BY 4.0**, with attribution to Google LLC and FLEURS dataset contributors. Raw FLEURS clips are not committed in the repository tree and are obtained separately for benchmarking. This data license does not apply to Aura Transcribe source code.

### Audio fixture — unresolved rights evidence

- `app/src/main/assets/bench.wav` is byte-identical to the file at the same path in the upstream repository (Git blob `3184d372cd2f8b804d3a540c70ec50d927b335d2`), added upstream in commit [`8a45999`](https://github.com/notune/android_transcribe_app/commit/8a45999bc21ae5ca25815f11463e2d1a67f3d160). It is included in the application assets, but neither the file nor that commit identifies the recording's underlying source, speaker permissions or a separate audio-rights grant. The upstream repository MIT notice is preserved, but that is not conclusive proof that the uploader controlled all rights in the recording. **This remains an evidence gap for complete rights clearance:** obtain confirmation that the recording can be redistributed or replace it with an independently created/licensed fixture before treating the asset audit as closed.

## 5. License texts included with the application

The application assets include copies of the root GPL-3.0-only license, the upstream MIT license, the MIT licenses for `transcribe-cpp` and `ggml`, the Apache-2.0 license text, and this notice file. Corresponding source files are preserved under `licenses/` and should stay byte-identical to the asset copies.

## 6. Re-audit rule

Whenever a model, crate, Gradle dependency, corpus, fixture or bundled resource changes, update this notice and review the exact license and provenance at the version actually distributed. A source-level metadata match alone is not complete license clearance. The CI workflow runs the Gradle dependency-license-report plugin and `scripts/report_cargo_licenses.py`, and retains both inventories as an artifact. Review the resolved reports before each release, verify any unknown or non-standard license statements manually, and keep them aligned with the runtime configurations actually packaged.
