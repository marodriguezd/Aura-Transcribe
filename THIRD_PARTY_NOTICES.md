# Third-Party Notices and License Scope

This file states which parts of Aura Transcribe are covered by which terms. The
repository-root `LICENSE` applies **MIT** to the application source code, except
where a component below is explicitly identified as third-party material under
separate terms. **This file does not relicense third-party content.**

## 1. Upstream application code (MIT)

Aura Transcribe is an independent fork of [Offline Voice Input](https://github.com/notune/android_transcribe_app), based on upstream release [v0.1.18 / commit d95e3a7e5555fa7309975751dc790e2c96387a28](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28), originally created by Noah Mühl.

- The upstream MIT license and its original copyright notice
  (`Copyright (c) 2025 Noah Mühl`) are preserved unchanged in the root
  [`LICENSE`](LICENSE) and duplicated verbatim as
  [`licenses/UPSTREAM-MIT.txt`](licenses/UPSTREAM-MIT.txt).
- Fork-authored code is distributed under the same MIT terms. No upstream
  notice was removed, renamed or reattributed, and no upstream code was
  relicensed.
- The MIT condition — retain the copyright notice and permission notice in
  copies or substantial portions — is satisfied by shipping `LICENSE` inside
  every APK/AAB at `assets/LICENSE`, alongside this file.

## 2. Bundled speech-recognition model (OpenMDW-1.1 — **not** MIT)

- **Base model:** [NVIDIA Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).
- **Quantized asset:** [handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf), revision `53ce288f69326b415aeef030cfcb02a08a8096dd`.
- **Terms:** OpenMDW License Agreement 1.1 (OpenMDW-1.1)
  (<https://openmdw.ai/license/1-1/>). The agreement is reproduced in
  [`MODEL_LICENSE.md`](MODEL_LICENSE.md); the model's notice of origin is
  reproduced in [`MODEL_NOTICE.md`](MODEL_NOTICE.md).
- **Artifact:** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`,
  SHA-256 `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65`.

The model weights are a **separately licensed data asset**. They are not MIT
and are not relicensed by this repository; conversely, MIT is not asserted over
the GGUF. OpenMDW-1.1 requires a distributor to retain (1) a copy of the
agreement and (2) the notices of origin supplied with the model materials; both
travel next to the GGUF in the APK (`assets/`) and in the AAB
(`model_assets/assets/`), and the release gates reject an artifact without them.

OpenMDW-1.1 is identified by NVIDIA's own model-card metadata as
`license_name: openmdw-1.1` under `license: other`. It is **not** claimed here to
be an SPDX-listed or OSI-approved license, and it is not represented by an SPDX
identifier. The tag `LicenseRef-OpenMDW-1.1` appears only where tooling requires
a short identifier.

## 3. Native runtime dependencies

- `transcribe-cpp` 0.1.3 — MIT; see [`licenses/transcribe-cpp-MIT.txt`](licenses/transcribe-cpp-MIT.txt).
- `ggml`, supplied by `transcribe-cpp` — MIT; see [`licenses/ggml-MIT.txt`](licenses/ggml-MIT.txt).
- `cpal` — Apache-2.0; see [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).
- `libc++_shared.so` — the C++ runtime copied from Android NDK 28.2.13676358
  (LLVM libc++), dual-licensed under the University of Illinois/NCSA license and
  Apache-2.0 with LLVM exceptions. See
  [`licenses/libc++-NOTICE.txt`](licenses/libc++-NOTICE.txt) and
  [`licenses/LLVM-exception.txt`](licenses/LLVM-exception.txt). It is shipped
  dynamically because `transcribe-cpp` links against it.
- Other direct crates declared in `Cargo.toml` (`anyhow`, `log`,
  `android_logger`, `jni`, `libc`, `once_cell`, `crossbeam-channel`, `strsim`)
  use permissive upstream options, commonly MIT and/or Apache-2.0.

**Resolved Rust inventory, regenerated 2026-10-19:** 122 resolved package
entries, 0 missing both `license` and `license-file` metadata, 3 with composite
expressions outside the reviewed allowlist. The workspace crates report `MIT`.
The three flagged entries are target-specific and are **not** Android build
inputs: `r-efi` (`MIT OR Apache-2.0 OR LGPL-2.1-or-later`) and `wasip2` /
`wit-bindgen` (`Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT`). They
remain a review item before the inventory can become a hard
`--fail-on-unknown` gate; Cargo's graph includes build-time and non-Android
targets, so the inventory is metadata, not proof of what ships in the APK.

## 4. Android runtime dependencies

The resolved `:app:releaseRuntimeClasspath` inventory contains **97 modules**,
regenerated 2026-10-19:

| Declared license | Modules |
|---|---|
| Apache-2.0 (in its several textual spellings) | 91 |
| Android Software Development Kit License | 3 |
| ML Kit Terms of Service | 2 |
| Blank manifest field (reconciled below) | 1 |

AndroidX, Material Components, OkHttp, Kotlin standard-library artifacts and
Google Play Services are Apache-2.0. Three Google Play Services modules declare
the [Android SDK terms](https://developer.android.com/studio/terms.html); the two
ML Kit modules declare the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms),
and Google ML Kit has separate [usage and attribution guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms).
Those provider terms are **not** replaced by the application's MIT license and
must be evaluated by whoever redistributes the app.

One metadata inconsistency was reconciled manually: `org.jspecify:jspecify:1.0.0`
has a blank license-name field in its manifest while its POM declares
Apache-2.0. The report records the blank field rather than silently filling it.

`org.json:json`, JUnit and MockWebServer are **test-only**
(`testImplementation`); `org.json` is not packaged into the app. The app uses the
platform's `org.json` API at runtime. Build tools and test frameworks are not
bundled in the release APK/AAB.

## 5. Inherited visual assets

A Git blob comparison against `notune/android_transcribe_app` identifies the
following files as byte-for-byte identical to upstream files, so they carry
upstream authorship and the upstream MIT grant rather than being presented as
newly created Aura assets:

- Screenshots: `.screenshots/screenshot_home.png`, `.screenshots/screenshot_recording.png`, `.screenshots/screenshot_subtitles.png`.
- Store artwork: `fastlane/metadata/android/en-US/images/icon.png` and `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, `3.png`.
- Drawable icons: `app/src/main/res/drawable/ic_backspace.xml`, `ic_check_circle.xml`, `ic_close.xml`, `ic_delete.xml`, `ic_error.xml`, `ic_mic.xml`, `ic_model.xml`, `ic_palette.xml`, `ic_settings.xml`, `ic_subtitles.xml`.

The same comparison also identifies inherited non-image files, including
`src/models.rs`, `app/src/main/res/layout/item_model_link.xml`,
`app/src/main/res/xml/recognition_service.xml`, `.cargo/config.toml.example` and
the upstream Fastlane changelogs. These remain upstream-derived even where
adjacent files were modified by the fork.

## 6. Audio fixtures and benchmark material

No audio is distributed with the repository or with the application. The former
in-app WAV fixture and the in-app audio benchmark were removed; the remaining
developer tooling is a synthetic smoke test that generates its signal at run
time and never reports measured ASR accuracy. The Macrobenchmark module contains
no audio fixture either.

## 7. License texts included with the application

The application assets carry copies of the MIT license, the upstream MIT
license, the `transcribe-cpp` and `ggml` MIT texts, the Apache-2.0 text, the LLVM
exception text, the libc++ notice, this notice file, and the model's
`MODEL_LICENSE.md` and `MODEL_NOTICE.md`. Sources live under `licenses/` and the
repository root; `scripts/check_license_docs.py` fails if any asset copy drifts
from its source, and the release gates verify the model documents inside the
built APK/AAB.

## 8. Re-audit rule

Whenever a model, crate, Gradle dependency, fixture or bundled resource changes,
update this notice and review the exact license and provenance of the version
actually distributed. A source-level metadata match is not a complete license
clearance. `:app:generateLicenseReport` and
`scripts/report_cargo_licenses.py` produce the inventories retained as the
`aura-transcribe-dependency-license-inventories` CI artifact; review them before
each release and verify any unknown or non-standard license statement manually.
