# License Audit — Aura Transcribe

**Review date:** 2026-10-18  
**Proposed source-code license:** GNU GPL-3.0-only  
**Scope:** Repository source, current manifests, model license declaration, source-tree assets and publication metadata.

## Decision

The code in this fork can be changed from a repository-level MIT declaration to GPL-3.0-only, while preserving the original upstream MIT notice for code carried over from `notune/android_transcribe_app`. The upstream MIT license is permissive and its notice/conditions must remain available. The migration therefore preserves that full text in `licenses/UPSTREAM-MIT.txt` and explicitly distinguishes upstream code from this fork's own code.

This does **not** mean every artifact in the repository is GPL-licensed. The bundled Nemotron GGUF remains under OpenMDW-1.1, the external FLEURS benchmark data is CC BY 4.0, and third-party libraries retain their own licenses. These boundaries are stated in `THIRD_PARTY_NOTICES.md`, the README translations, and the model license document.

## Findings

1. **Upstream code — compatible in principle.** The repository identifies itself as a fork of Offline Voice Input v0.1.18. Its original MIT license is preserved in full. Existing copyright attribution to Noah Mühl is not replaced with the fork owner's name.
2. **Nemotron model — separate license.** NVIDIA's current model card and the handy-computer GGUF distribution identify OpenMDW-1.1, not CC-BY 4.0. The README attribution was stale and has been corrected. OpenMDW permits distributions to include independently licensed materials when the scopes are explicit, but its FAQ specifically warns that combining model materials with copyleft licensing needs care. The model is documented as a separately licensed data asset; legal review of the combined distribution remains prudent if certainty is required.
3. **Dependency inventory — generated and inspected.** The Gradle report contains 97 resolved Android release-runtime modules, predominantly Apache-2.0. Three Google Play Services modules list the Android Software Development Kit License and two ML Kit modules list ML Kit Terms of Service; the relevant provider URLs are recorded in `THIRD_PARTY_NOTICES.md`. `org.jspecify:jspecify:1.0.0` has one blank manifest license-name field, but its POM explicitly declares Apache License 2.0; this metadata mismatch is reconciled. Cargo reports 122 resolved package entries, with zero packages missing both license fields. Cargo metadata includes target-specific and build-time entries, so it is not a claim that all 122 packages ship in the APK. Neither report by itself proves media rights or replaces legal review of the model/code package boundary.
4. **Android dependencies — attribution retained.** The resolved report was generated for `:app:releaseRuntimeClasspath`. AndroidX, Material Components, OkHttp, Kotlin, Google Play Services and Google ML Kit are disclosed, including the separate Android SDK and ML Kit terms. ML Kit attribution/usage guidelines are linked because the source-code license does not override provider requirements.
5. **Test dependencies — isolated from runtime.** `org.json:json`, JUnit and MockWebServer remain test-only. The consistency check asserts that `org.json:json` remains under `testImplementation`, rather than being packaged into the app.
6. **Benchmark data — separately licensed.** The benchmark manifest identifies external Google FLEURS data under CC BY 4.0. This does not change the application-code license.
7. **Asset provenance — partially mapped, one material gap remains.** A Git blob comparison identifies inherited screenshots, store artwork, vector icons, source, and metadata that are byte-identical to files from the upstream repository; these are enumerated in `THIRD_PARTY_NOTICES.md` and retain upstream attribution. The shipped `app/src/main/assets/bench.wav` is also byte-identical to upstream and was added in commit `8a45999`, but neither the file nor commit documents the underlying recording's source or rights grant. Confirm redistribution rights or replace it with a clearly licensed fixture before claiming complete asset-rights clearance.

## Validation added

- GPL-3.0-only is kept in README badges, Cargo crate metadata, Fastlane export metadata and the top-level ABOUT page.
- The six README translations link to the scope/third-party notices and no longer describe the Nemotron GGUF as CC-BY 4.0.
- The model license and third-party license asset copies are checked for consistency.
- `scripts/check_license_docs.py` runs in debug/PR and release validation workflows.
- Gradle's dependency-license-report plugin runs from the `:app` project and scans `releaseRuntimeClasspath` into `app/build/reports/dependency-license/`; `scripts/report_cargo_licenses.py` inventories license declarations from Cargo's resolved graph. Both reports were generated successfully, inspected, and uploaded as the `aura-transcribe-dependency-license-inventories` CI artifact.

## Status

This is a repository licensing migration proposal. The dependency inventories have been generated and reviewed for this commit; the material unresolved rights blocker is the provenance of `app/src/main/assets/bench.wav`, plus a careful legal review of the combined APK/AAB distribution boundary between GPL-3.0-only application code and the separately licensed OpenMDW-1.1 model. The PR should remain a draft until those points are closed. This is a technical audit, not legal advice.
