# Third-Party Notices and License Scope

This file identifies material in Aura Transcribe that does not all share one license. The repository-root `LICENSE` applies GPL-3.0-only to fork-authored code, except where a component below is explicitly identified as third-party material under separate terms. **This file does not relicense third-party content.**

## 1. Upstream application code

Aura Transcribe is an independent fork of [Offline Voice Input](https://github.com/notune/android_transcribe_app), based on upstream release [v0.1.18 / commit d95e3a7e5555fa7309975751dc790e2c96387a28](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28), originally created by Noah Mühl.

- Original upstream code remains subject to its MIT license and original copyright notice.
- A copy of the original upstream MIT license is preserved as [`licenses/UPSTREAM-MIT.txt`](licenses/UPSTREAM-MIT.txt).
- When redistributing source or binaries containing upstream portions, preserve the applicable MIT notices and license text. GPL-3.0-only applies to fork-authored code and modifications to the extent the corresponding rights are held by the contributor; it does not erase the upstream license obligations.

## 2. Bundled speech-recognition model

- **Base model:** [NVIDIA Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).
- **Quantized asset:** [handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf).
- **Terms:** OpenMDW License Agreement 1.1 (OpenMDW-1.1). See [`MODEL_LICENSE.md`](MODEL_LICENSE.md), which reproduces the applicable model terms, and [`MODEL_NOTICE.md`](MODEL_NOTICE.md), which reproduces the model's NOTICE of modifications. Both files are packaged in the same APK/AAB as the model asset.
- **Current configured artifact:** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`; SHA-256 `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65`.

The model weights are treated as a separately licensed data asset, not relicensed under GPL-3.0-only. Whether the combined distribution is compatible is not determined; legal review is required.

## 3. Native runtime dependencies

The currently declared Rust runtime dependencies include:

- `transcribe-cpp` 0.1.3 — MIT; see [`licenses/transcribe-cpp-MIT.txt`](licenses/transcribe-cpp-MIT.txt).
- `ggml`, supplied by the `transcribe-cpp` dependency — MIT; see [`licenses/ggml-MIT.txt`](licenses/ggml-MIT.txt).
- `cpal` — Apache-2.0; see [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).
- `libc++_shared.so` — the C++ runtime copied from Android NDK 28.2.13676358 (LLVM libc++), dual-licensed under the University of Illinois/NCSA license and Apache-2.0 with LLVM exceptions. See [`licenses/libc++-NOTICE.txt`](licenses/libc++-NOTICE.txt) and [`licenses/LLVM-exception.txt`](licenses/LLVM-exception.txt). The library is shipped dynamically because `transcribe-cpp` links against it.
- Other direct crates declared in `Cargo.toml` (`anyhow`, `log`, `android_logger`, `jni`, `libc`, `once_cell`, `crossbeam-channel`, `strsim`) use permissive upstream license options, commonly MIT and/or Apache-2.0. The workflow generates `build/reports/cargo-licenses.md` from Cargo's resolved metadata and `app/build/reports/dependency-license/` from the Android `releaseRuntimeClasspath`; both reports are uploaded in the `aura-transcribe-dependency-license-inventories` artifact. The 2026-10-19 reports were inspected; keep reviewing each future report and verify the actual license text where package metadata is non-standard.

The Android runtime dependencies declared in `gradle/libs.versions.toml` and `app/build.gradle.kts` include AndroidX libraries, Material Components, OkHttp, Kotlin standard-library artifacts, Google Play Services and Google ML Kit Translate. Their upstream notices and any transitive notices must be retained according to their actual resolved artifact metadata.

**Resolved dependency report reviewed on 2026-10-18:** the release `releaseRuntimeClasspath` inventory contains 97 Maven modules. The reported licenses are predominantly Apache-2.0. Three Google Play Services modules declare the Android Software Development Kit License (see [Android SDK terms](https://developer.android.com/studio/terms.html)); the two ML Kit modules declare [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms), and Google ML Kit also has separate [usage and attribution guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms). These provider terms and attribution requirements are not replaced by the application source-code license. The complete HTML, JSON and generated `THIRD-PARTY-NOTICES.txt` are produced by `:app:generateLicenseReport` and retained as a CI artifact.

One report-normalization detail was manually reconciled: `org.jspecify:jspecify:1.0.0` has a blank license-name entry in its manifest, while its POM explicitly declares Apache License 2.0. The report includes that POM declaration; this is a metadata inconsistency, not an unidentified license. Review the resolved report again when dependency versions change.

The Cargo metadata report covers 122 resolved package entries, including target-specific and build-time dependencies, with no package missing both `license` and `license-file` metadata. The report contains permissive MIT/Apache choices and other notices such as BSD, ISC, Unlicense and Unicode-3.0. The 2026-10-19 run flags three **target-specific** entries with composite expressions outside the default allowlist: `r-efi` (`MIT OR Apache-2.0 OR LGPL-2.1-or-later`), `wasip2` and `wit-bindgen` (`Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT`). These are UEFI/WASI graph entries, not Android targets; they must be reviewed again before the inventory is made a hard `--fail-on-unknown` gate. Because this inventory includes target-specific/build packages, it is a metadata inventory of the resolved Cargo graph and not proof that every listed package is shipped in the Android binary. The exact target/build graph should be reviewed for each release.

The current build declares `org.json:json`, JUnit, and MockWebServer as test-only dependencies. In particular, `org.json:json` is confined to `testImplementation`; do not move it into the shipped runtime without a separate license review. The Android application uses the platform's `org.json` API at runtime. Build tools, test frameworks and test-only libraries are not intended to be bundled in the release APK/AAB.

## 4. Inherited visual assets and benchmark suite

### Byte-identical assets carried over from upstream

A Git blob comparison against `notune/android_transcribe_app` identifies the following visual assets in this fork as byte-for-byte identical to files in the upstream repository. They should retain their upstream attribution and license context rather than being presented as newly created Aura assets:

- Screenshots: `.screenshots/screenshot_home.png`, `.screenshots/screenshot_recording.png`, `.screenshots/screenshot_subtitles.png`.
- Store artwork: `fastlane/metadata/android/en-US/images/icon.png` and `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, and `3.png`.
- Drawable icons: `app/src/main/res/drawable/ic_backspace.xml`, `ic_check_circle.xml`, `ic_close.xml`, `ic_delete.xml`, `ic_error.xml`, `ic_mic.xml`, `ic_model.xml`, `ic_palette.xml`, `ic_settings.xml`, and `ic_subtitles.xml`.

The same blob comparison also identifies inherited non-image files, including `src/models.rs`, `app/src/main/res/layout/item_model_link.xml`, `app/src/main/res/xml/recognition_service.xml`, `.cargo/config.toml.example`, and upstream Fastlane changelogs. These are still upstream-derived, even when subsequently modified files nearby are part of the fork's own work. The original MIT license is preserved in `licenses/UPSTREAM-MIT.txt`.

### Benchmark suite

- The benchmark suite uses synthetic audio and does not use an external dataset. No audio is distributed with the repository or the application: the former in-app audio fixture and audio benchmark were removed.

## 5. License texts included with the application

The application assets include copies of the root GPL-3.0-only license, the upstream MIT license, the MIT licenses for `transcribe-cpp` and `ggml`, the Apache-2.0 license text, the LLVM exception license, the libc++ notice, the model license, the model NOTICE, and this notice file. Corresponding source files are preserved under `licenses/` and the repository root, and should stay byte-identical to the asset copies. The release APK/AAB gates verify that `MODEL_LICENSE.md` and `MODEL_NOTICE.md` are packaged at the artifact asset root.

## 6. Re-audit rule

Whenever a model, crate, Gradle dependency, corpus, fixture or bundled resource changes, update this notice and review the exact license and provenance at the version actually distributed. A source-level metadata match alone is not complete license clearance. The CI workflow runs the Gradle dependency-license-report plugin and `scripts/report_cargo_licenses.py`, and retains both inventories as an artifact. Review the resolved reports before each release, verify any unknown or non-standard license statements manually, and keep them aligned with the runtime configurations actually packaged.
