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
3. **Native libraries — permissive declared runtime components.** The declared native runtime dependencies include MIT and Apache-2.0 components. License texts are retained in the source and application assets. The PR workflow now generates a resolved Gradle dependency license report and a Cargo metadata-based inventory; both are attached as a CI artifact for manual review. These reports identify declared metadata but do not by themselves prove every asset's redistribution rights or replace legal review.
4. **Android dependencies — attribution retained.** AndroidX, Material Components, OkHttp, Kotlin and Google ML Kit are disclosed. ML Kit's own attribution/usage guidelines are linked because a source-code license does not override provider requirements.
5. **Test dependencies — isolated from runtime.** `org.json:json`, JUnit and MockWebServer remain test-only. The consistency check asserts that `org.json:json` remains under `testImplementation`, rather than being packaged into the app.
6. **Benchmark data — separately licensed.** The benchmark manifest identifies external Google FLEURS data under CC BY 4.0. This does not change the application-code license.
7. **Asset rights gap — still open.** `app/src/main/assets/bench.wav` has the same Git blob in the upstream repository, but no specific audio-recording license/provenance is documented. It is shipped as an application asset. That does not prevent choosing GPL for code in principle, but it prevents claiming a complete 100% asset-rights clearance until the recording's permission is confirmed or the fixture is replaced.

## Validation added

- GPL-3.0-only is kept in README badges, Cargo crate metadata, Fastlane export metadata and the top-level ABOUT page.
- The six README translations link to the scope/third-party notices and no longer describe the Nemotron GGUF as CC-BY 4.0.
- The model license and third-party license asset copies are checked for consistency.
- `scripts/check_license_docs.py` runs in debug/PR and release validation workflows.
- Gradle's dependency-license-report plugin generates a report of resolved Maven/Gradle dependency licenses; `scripts/report_cargo_licenses.py` inventories license declarations from Cargo's resolved graph. Both reports are uploaded as the `aura-transcribe-dependency-license-inventories` CI artifact.

## Status

This is a repository licensing migration proposal. The remaining blockers are review of the generated dependency-license inventories, any missing/unrecognized license declarations they reveal, and the unresolved media-provenance item. The PR should remain a draft until the benchmark recording is cleared/replaced and the reports are reviewed against the exact release artifacts. This is a technical audit, not legal advice.
