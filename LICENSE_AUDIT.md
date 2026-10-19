# License Audit — Aura Transcribe

- **Review date:** 2026-10-19
- **Proposed source-code license:** GNU GPL-3.0-only for fork-authored code
- **Scope:** Repository source, current manifests, model license declaration, source-tree assets and publication metadata.

## Decision

Fork-authored code in this repository is proposed to be licensed under GPL-3.0-only, while code inherited from `notune/android_transcribe_app` remains under its original MIT license. The upstream MIT notice and conditions must remain available. The migration therefore preserves that full text in `licenses/UPSTREAM-MIT.txt` and explicitly distinguishes upstream code from the code authored for this fork.

This does **not** mean every artifact in the repository is GPL-3.0-only. The bundled Nemotron GGUF remains under OpenMDW-1.1, and third-party libraries retain their own licenses. No audio is distributed with the repository or the application. These boundaries are stated in `THIRD_PARTY_NOTICES.md`, the README translations, and the model license document.

Whether the combined distribution is compatible is not determined; legal review is required.

## Findings

1. **Upstream code — MIT terms preserved.** The repository identifies itself as a fork of Offline Voice Input v0.1.18. Its original MIT license is preserved in full. Existing copyright attribution to Noah Mühl is not replaced with the fork owner's name.
2. **Nemotron model — separate license.** NVIDIA's current model card and the handy-computer GGUF distribution identify OpenMDW-1.1. The README attribution was stale and has been corrected. The model is documented as a separately licensed data asset; its terms are reproduced in `MODEL_LICENSE.md`. Whether the combined distribution is compatible is not determined; legal review is required.
3. **Dependency inventory — generated and inspected.** The Gradle report contains 97 resolved Android release-runtime modules, predominantly Apache-2.0. Three Google Play Services modules list the Android Software Development Kit License and two ML Kit modules list ML Kit Terms of Service; the relevant provider URLs are recorded in `THIRD_PARTY_NOTICES.md`. `org.jspecify:jspecify:1.0.0` has one blank manifest license-name field, but its POM explicitly declares Apache License 2.0; this metadata mismatch is reconciled. Cargo reports 122 resolved package entries, with zero packages missing both license fields; three target-specific entries (`r-efi`, `wasip2`, `wit-bindgen`) use composite expressions outside the default allowlist and are documented for re-review. Cargo metadata includes target-specific and build-time entries, so it is not a claim that all 122 packages ship in the APK. The NDK's dynamically linked `libc++_shared.so` is documented separately with the LLVM exception text and libc++ notice. Neither report by itself proves media rights or replaces legal review of the model/code package boundary.
4. **Android dependencies — attribution retained.** The resolved report was generated for `:app:releaseRuntimeClasspath`. AndroidX, Material Components, OkHttp, Kotlin, Google Play Services and Google ML Kit are disclosed, including the separate Android SDK and ML Kit terms. ML Kit attribution/usage guidelines are linked because the source-code license does not override provider requirements.
5. **Test dependencies — isolated from runtime.** `org.json:json`, JUnit and MockWebServer remain test-only. The consistency check asserts that `org.json:json` remains under `testImplementation`, rather than being packaged into the app.
6. **Benchmark suite — synthetic and self-contained.** The benchmark suite is a synthetic smoke test and does not use an external dataset. No audio is distributed: the former in-app audio fixture and audio benchmark were removed. This does not change the application-code license.
7. **Asset provenance — inherited visual assets mapped.** A Git blob comparison identifies inherited screenshots, store artwork, vector icons, source, and metadata that are byte-identical to files from the upstream repository; these are enumerated in `THIRD_PARTY_NOTICES.md` and retain upstream attribution. No audio fixture is distributed.

## Validation added

- GPL-3.0-only is kept in README badges, `crates/aura-core` metadata, Fastlane export metadata and the top-level ABOUT page; the root crate declares `MIT AND GPL-3.0-only` to reflect the upstream/fork scope.
- The six README translations link to the scope/third-party notices and describe the Nemotron GGUF under its actual model terms.
- The model license, model NOTICE and third-party license asset copies are checked for consistency.
- Release APK/AAB gates verify that `MODEL_LICENSE.md` and `MODEL_NOTICE.md` are present at the artifact asset root and byte-identical to the repository sources.
- `scripts/check_license_docs.py` runs in debug/PR and release validation workflows.
- Gradle's dependency-license-report plugin runs from the `:app` project and scans `releaseRuntimeClasspath` into `app/build/reports/dependency-license/`; `scripts/report_cargo_licenses.py` inventories license declarations from Cargo's resolved graph. Both reports were generated successfully, inspected, and uploaded as the `aura-transcribe-dependency-license-inventories` CI artifact.

## Status

This is a repository licensing migration proposal. The dependency inventories have been generated and reviewed for this commit. The only unresolved licensing blocker is legal review of the combined APK/AAB distribution between GPL-3.0-only fork-authored code and the separately licensed OpenMDW-1.1 model. The PR should remain a draft until that review is complete. This is a technical audit, not legal advice.
