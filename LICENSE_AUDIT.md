# License Audit — Aura Transcribe

- **Audit window:** 2026-10-19 – 2026-10-20
- **Review date:** 2026-10-20
- **Status of the licensing question:** **resolved at repository level** — see
  [Go / No-Go](#go--no-go-checklist) and [Questions for counsel](#questions-that-genuinely-require-external-legal-counsel).

This document records verifiable licensing facts about the material this
repository actually ships, the obligations attached to it and where each one is
satisfied, the defects found (severity-ranked, with remediation), and the
questions that remain open for external legal counsel. **It is not a legal
certification and nothing in it is legal advice.** The automated gates cited
below prove that documents are present, consistent and pinned; they do not
establish legal compatibility of any distribution.

---

## 1. Definitive licensing decision (fixed scope)

This decision is final for the repository and constrains every other document:

| Material | License | Canonical document |
|---|---|---|
| Application source code (Java, Rust, Gradle, resources) | **MIT** | [`LICENSE`](LICENSE) |
| Bundled Nemotron model weights (GGUF) | **OpenMDW-1.1** (not MIT) | [`MODEL_LICENSE.md`](MODEL_LICENSE.md), [`MODEL_NOTICE.md`](MODEL_NOTICE.md) |
| Third-party libraries and native components | per package | [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) + generated inventories |

- The application **stays MIT**. There will be **no migration** to
  GPL-3.0-only, Apache-2.0 or any other license. An earlier draft proposing a
  GPL-3.0-only migration was **withdrawn** (see §6) and must not return.
- Upstream copyright notices are preserved verbatim: the root `LICENSE` keeps
  `Copyright (c) 2025 Noah Mühl`, and `licenses/UPSTREAM-MIT.txt` is a
  byte-identical copy of the upstream MIT text that ships in every artifact.
- The model remains a **separately licensed data asset** under OpenMDW-1.1. No
  MIT terms are asserted over the GGUF, and no OpenMDW terms are asserted over
  the application code.
- All required third-party licenses and notices are retained and shipped (see
  §3). This is enforced mechanically, not by convention (see §7).

## 2. Evidence base (facts, with method)

Every item below was verified directly against an authoritative source during
the audit window. Hashes are SHA-256.

1. **Application license.** `LICENSE` is the upstream MIT text with
   `Copyright (c) 2025 Noah Mühl`, unmodified. `licenses/UPSTREAM-MIT.txt` is
   byte-identical to it and to the upstream repository's `LICENSE`.
2. **Model agreement, verbatim.** `licenses/OpenMDW-1.1.txt` (2,619 bytes,
   SHA-256 `2ab44b68365473c112f5092211a38f231cb23e50de68b75a13369adbd76a74df`)
   is byte-identical to the `LICENSE` published with the GGUF at the pinned
   revision `53ce288f69326b415aeef030cfcb02a08a8096dd`
   (compared over HTTPS against the upstream `resolve` URL, 2026-10-20).
   The fenced block in `MODEL_NOTICE.md` reproduces the upstream `NOTICE`
   (411 bytes, SHA-256
   `e6ffd58bf2373a13db1f59ed9d96399e49b6c97a3e88aca340c054ed55fa4bac`)
   verbatim, line breaks included.
3. **Native license texts, verbatim.** `licenses/transcribe-cpp-MIT.txt` is
   byte-identical to the `LICENSE` file inside the `transcribe-cpp` 0.1.3 crate
   (downloaded from the registry and diffed, 2026-10-20); `licenses/ggml-MIT.txt`
   is byte-identical to the ggml license text shipped by that crate's sources.
4. **Model artifact identity.** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`
   is 559,647,200 bytes, SHA-256
   `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65` —
   matching the pinned value in `app/build.gradle.kts`, `MainActivity.java`
   (runtime download) and `benchmark/manifests/models.json`. The bytes inside
   the **published** v1.0.0 APK and AAB were re-hashed against the same pin and
   match. The download URL is pinned to the verified commit, never
   `resolve/main`.
5. **Published v1.0.0 distribution surface (defective — see F-1/F-2).**
   Inspected 2026-10-20 via the zip central directories of the release assets
   (`Aura_Transcribe_v1.0.0.apk` 575,589,524 B, `Aura_Transcribe_v1.0.0.aab`
   558,508,606 B):
   - APK `assets/`: `MODEL_LICENSE.md`, `bench.wav`,
     `builtin-model/nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf` (+ `dexopt/`
     profiles). **Absent:** `LICENSE`, `THIRD_PARTY_NOTICES.md`, all
     `licenses/*` texts, `MODEL_NOTICE.md`.
   - AAB: `model_assets/assets/MODEL_LICENSE.md` + the GGUF,
     `base/assets/bench.wav`. **Absent:** same set as above.
   - The release also attaches `MODEL_LICENSE.md` (3,925 B) as a release asset.
   - The signer certificate of both artifacts matches the pinned production
     certificate in `keystore/release-cert.sha256` (not the debug key).
6. **Repository state at HEAD fixes the surface.** `app/src/main/assets/`
   carries `LICENSE`, `THIRD_PARTY_NOTICES.md` and `licenses/` (8 texts);
   `model_assets/src/main/assets/` carries `MODEL_LICENSE.md` and
   `MODEL_NOTICE.md` next to the GGUF. The Gradle gates
   (`verifyReleaseApkModel`, `verifyReleaseBundleModel`,
   `verifyPackagingDecision`) and the release workflow verify the model
   documents byte-for-byte inside the built artifact; `verify_artifact_notices.py`
   (see §7) verifies the **complete** notice set the same way.
7. **Publication metadata is consistent with the decision.** Both
   `Cargo.toml` files declare `license = "MIT"`; all six READMEs, `ABOUT.md`,
   the English store description and the F-Droid descriptor (`License: MIT`)
   state MIT for the code and OpenMDW-1.1 for the model. **No CC-BY-4.0 claim
   over the model and no copyleft claim over the code appears anywhere** in the
   publication surface; `scripts/check_license_docs.py` fails if one reappears.
8. **Dependency inventories (review artifacts, not clearances).**
   - Gradle `:app:releaseRuntimeClasspath`, regenerated 2026-10-19: 97 modules —
     91 Apache-2.0, 3 under the Android SDK License, 2 under the ML Kit Terms
     of Service, 1 blank manifest field (`org.jspecify:jspecify`, whose POM
     declares Apache-2.0; recorded as blank, not silently filled).
   - Cargo, regenerated 2026-10-19: 122 resolved entries, **0 missing** license
     metadata, 3 composite expressions outside the reviewed allowlist
     (`r-efi`, `wasip2`, `wit-bindgen`) — all target-specific and **not**
     Android build inputs. Cargo's graph includes build-time and non-Android
     targets, so this is metadata about the graph, not proof of what ships.
   - Test-only dependencies (`org.json:json`, JUnit, MockWebServer) are
     `testImplementation` and are not packaged; `org.json` is provided by the
     platform at runtime.

## 3. Obligations and where each is satisfied

| Instrument | Obligation | Where satisfied | Enforced by |
|---|---|---|---|
| MIT (app + upstream code) | Retain copyright and permission notice in copies | `assets/LICENSE` + `assets/licenses/UPSTREAM-MIT.txt` in every APK/AAB | `check_license_docs.py` (byte-identical copies), `verify_artifact_notices.py` (inside artifact) |
| OpenMDW-1.1 (model) | (1) Copy of the agreement | `assets/MODEL_LICENSE.md` + `assets/licenses/OpenMDW-1.1.txt` (APK), `model_assets/assets/MODEL_LICENSE.md` + `base/assets/licenses/OpenMDW-1.1.txt` (AAB) | `verifyReleaseApkModel` / `verifyReleaseBundleModel`, workflow `cmp` checks, `verify_artifact_notices.py` |
| OpenMDW-1.1 (model) | (2) Retain notices of origin | `MODEL_NOTICE.md` reproduces the upstream `NOTICE` verbatim, packaged next to the GGUF | same as above + `check_license_docs.py` byte pin |
| Apache-2.0 (crates, AndroidX, Material, OkHttp, …) | License text; keep `NOTICE` files where provided | `assets/licenses/Apache-2.0.txt`, `THIRD_PARTY_NOTICES.md` | `check_license_docs.py`, `verify_artifact_notices.py` |
| Illinois-NCSA / Apache-2.0 WITH LLVM-exception (`libc++_shared.so`) | Retain the notice and exception text | `assets/licenses/libc++-NOTICE.txt` + `assets/licenses/LLVM-exception.txt` | same |
| MIT (`transcribe-cpp`, `ggml`, upstream app) | Retain copyright notice | `assets/licenses/transcribe-cpp-MIT.txt`, `ggml-MIT.txt`, `UPSTREAM-MIT.txt` | same |

OpenMDW-1.1 imposes **no conditions on outputs**: transcribed text is
unaffected. It is identified by NVIDIA's model-card metadata as
`license_name: openmdw-1.1` under `license: other`; this repository does **not**
claim it is SPDX-listed or OSI-approved, cites it by full name, and uses
`LicenseRef-OpenMDW-1.1` only where tooling requires a tag.

## 4. Findings, severity-ranked

**F-1 — HIGH (defect in published artifacts; remediated at HEAD, residual
exposure).** The v1.0.0 APK and AAB shipped the model and `MODEL_LICENSE.md`
but **none** of the MIT `LICENSE`, `THIRD_PARTY_NOTICES.md` or `licenses/*`
texts (evidence §2.5). The model-focused gates did not catch this because the
*model* was present. *Remediation:* `scripts/verify_artifact_notices.py` +
18 negative tests, wired into both workflows and the release verification
(2026-10-20). *Residual:* the already-published v1.0.0 binaries remain as they
are; see counsel question Q2 and the go/no-go checklist.

**F-2 — MEDIUM (published artifacts; remediated at HEAD).** v1.0.0 also lacked
`MODEL_NOTICE.md` (the upstream `NOTICE` of origin existed only from a later
commit), so the OpenMDW-1.1 "notices of origin" obligation was met only through
the provenance section inside `MODEL_LICENSE.md`. HEAD packages the verbatim
NOTICE and the release workflow `cmp`s it. Residual exposure is the same as
F-1 (Q2).

**F-3 — MEDIUM (metadata; remediated).** The Cargo inventory's reviewed
allowlist still contained `GPL-3.0-only`, a leftover of the withdrawn GPL
migration: a copyleft expression could have passed the inventory pre-reviewed,
in tension with the MIT grant over the combined binary. Removed 2026-10-20; a
copyleft expression now always surfaces as *unrecognized* and requires explicit
review (`test_report_cargo_licenses.py` pins this).

**F-4 — LOW (provenance; remediated).** The v1.0.0 binaries also carried
`assets/bench.wav` (352,078 B), an 11-second benchmark fixture whose
third-party provenance could not be documented from repository history. It was
removed from the app (commit `147a143` replaced it with speech generated from a
licensed text fixture; `7973890` removed the in-app benchmark entirely), and
`check_license_docs.py` now fails if any audio fixture reappears in app assets.

**F-5 — LOW (inventory hygiene; open, non-blocking).** Three Cargo
expressions (`r-efi`, `wasip2`, `wit-bindgen`) remain outside the reviewed
allowlist. None is an Android build input; they are a review item before the
inventory can become a hard `--fail-on-unknown` gate.

**F-6 — LOW (metadata inconsistency; reconciled).** `org.jspecify:jspecify`
declares a blank license field in its manifest while its POM declares
Apache-2.0. The report records the blank; the reconciliation is documented in
`THIRD_PARTY_NOTICES.md` §4.

**F-7 — INFORMATIONAL.** Three Play Services modules (Android SDK License) and
two ML Kit modules (ML Kit Terms of Service + Google's translation attribution
guidelines) carry **provider terms that MIT does not replace**. Anyone
redistributing the app must evaluate them; see Q3/Q4.

**F-8 — INFORMATIONAL (closed).** The historical mislabeling of the model as
CC-BY-4.0 in all six READMEs, and the earlier silence of the Cargo manifests,
were corrected in the first audit pass; the gate suite keeps them corrected.

## 5. Facts vs. interpretations

**Facts** (verifiable, §2): hashes, byte comparisons, archive inventories,
declared metadata, resolved dependency graphs, signer fingerprints.

**Interpretations** (reasoned positions, not measurements):
- The GGUF is distributed as a *separate work* alongside the application code
  (loaded as data at runtime, not compiled in). This is how the repository
  characterizes the packaging; see Q1 for the residual counsel question.
- The GGUF conversion (repackaging + quantization, no retraining) adds no new
  license conditions and no second licensor with different terms; it adds an
  attribution fact recorded in `MODEL_NOTICE.md`.
- OpenMDW-1.1's conditions (agreement + notices of origin) are satisfied by the
  packaging described in §3. The gates prove the packaging; the *sufficiency* of
  that packaging under the instrument is an interpretation (Q1/Q2).

## 6. Why the application stays MIT (withdrawn GPL proposal)

An earlier draft migrated fork-authored code to GPL-3.0-only. It was withdrawn
for two independent reasons, and the decision is now definitive:

1. **Unnecessary.** MIT imposes no condition on the model it is distributed
   with. A copyleft application license would create a "combined work"
   compatibility question (GPL §5–§7 boundaries against a separately licensed
   weight file) that the permissive baseline does not have. Creating an
   obligation in order to argue about it is unsound license hygiene.
2. **No authority.** The fork's files contain upstream-derived material under
   MIT; relicensing is a decision for the copyright holders, no contributor
   agreement supports it, and it would force downstream consumers (including
   F-Droid, which builds from source) onto different terms for no functional
   benefit.

Keeping MIT also keeps every surface consistent: root `LICENSE`, upstream
commit of origin, Cargo manifests, Fastlane and store metadata all say MIT.

## 7. Verification (how this is kept true)

- `scripts/check_license_docs.py` + `scripts/test_check_license_docs.py`
  (31 negative tests): fails on a non-MIT root license, a stale license badge
  in any README, an MIT claim over the model, a CC-BY or copyleft claim on the
  publication surface, a Cargo license mismatch, a drifted asset copy, a
  rewritten upstream `NOTICE` or native license text (byte pins), a missing
  model document, a `bench.wav` in assets, or an unpinned model URL.
- `scripts/verify_artifact_notices.py` + `scripts/test_verify_artifact_notices.py`
  (18 negative tests): opens the produced APK/AAB and requires every license
  and notice document at its exact delivery path, exactly once, byte-identical
  to the repository sources. Runs in the release workflow after
  `verify_release_artifact.py` for both artifacts (F-1 remediation).
- `:app:verifyReleaseApkModel`, `:app:verifyReleaseBundleModel`,
  `:app:verifyPackagingDecision` — model packaging and model documents inside
  the real artifact functions, also exercised over synthetic archives.
- `scripts/report_cargo_licenses.py` + `scripts/test_report_cargo_licenses.py` —
  Cargo inventory (copyleft identifiers always *unrecognized*, never
  pre-approved); `:app:generateLicenseReport` for the Gradle inventory. Both
  inventories are retained as the `aura-transcribe-dependency-license-inventories`
  CI artifact and re-reviewed per release (§8).

## 8. Re-audit rule

Whenever a model, crate, Gradle dependency, fixture or bundled resource
changes, update `THIRD_PARTY_NOTICES.md` and this audit, and review the license
and provenance of the version actually distributed. A source-level metadata
match is not a complete license clearance. Review the generated inventories
before each release and verify any unknown or non-standard license statement
manually.

## Questions that genuinely require external legal counsel

These are open questions for qualified counsel. Nothing in this repository
answers them, and **no legal certification is claimed**:

- **Q1 — Work characterization.** Whether a GGUF inside an application package
  is best characterized as a separate work distributed alongside the app
  (aggregate) or as part of a single work. Under the current MIT + OpenMDW-1.1
  pairing, **both characterizations are permissible** (there is no copyleft
  condition to conflict), so this is not a distribution blocker; it becomes one
  only if the code were relicensed to copyleft, if OpenMDW-1.1 were replaced by
  terms with field-of-use or copyleft conditions, or if the notices stopped
  travelling with the model.
- **Q2 — Remediation of the published v1.0.0 artifacts (F-1/F-2).** Whether
  shipping a superseding v1.0.x release whose artifacts carry the complete
  notice set is sufficient, or whether the v1.0.0 assets should be replaced or
  the release annotated. The repository now guarantees future artifacts; it
  cannot retroactively alter what v1.0.0 delivered without modifying a
  published release.
- **Q3 — ML Kit attribution.** Whether the app's on-device translation of
  finalized subtitles triggers Google's ML Kit usage/attribution guidelines in
  the UI, in the documentation only, or not at all, and whether those terms
  conflict with any distribution channel's terms.
- **Q4 — Provider terms at redistribution.** Whether the Android SDK License
  and Google Play Services terms impose obligations on redistribution beyond
  the channel terms already accepted (Play, GitHub Releases), particularly for
  the F-Droid/source-build scenario.
- **Q5 — F-Droid policy.** If the F-Droid descriptor is ever enabled, whether
  the non-OSI status of OpenMDW-1.1 affects inclusion policy for the **model**
  (the descriptor is deliberately `Disabled:` today partly for this class of
  question).
- **Q6 — Nominative branding.** Whether store/README references to "NVIDIA
  Nemotron" and "Hugging Face" are nominative use in each distributing
  jurisdiction.

## Go / No-Go checklist

| # | Item | Status |
|---|---|---|
| 1 | Application code MIT, upstream notices preserved verbatim | ✅ |
| 2 | Model OpenMDW-1.1, agreement + NOTICE shipped byte-identical | ✅ |
| 3 | Full third-party license/notice set packaged in APK **and** AAB | ✅ at HEAD (v1.0.0 defective — F-1/F-2, Q2) |
| 4 | Artifact-level gate proving (3) on every release | ✅ `verify_artifact_notices.py` (2026-10-20) |
| 5 | No copyleft or CC-BY claims on the publication surface | ✅ gate-enforced |
| 6 | Manifests declare the license they ship (`MIT`) | ✅ |
| 7 | Model pinned by revision + SHA-256 everywhere it is fetched | ✅ |
| 8 | Dependency inventories generated, reviewed, retained as CI artifact | ✅ (3 expressions flagged, non-Android — F-5) |
| 9 | No audio or unprovenanced fixtures bundled | ✅ gate-enforced |
| 10 | Publication of a new release to supersede v1.0.0's notice defect | ⏳ pending (Q2 informed, decision is a release step) |

**Verdict:** no repository-level licensing blocker remains for producing and
publishing artifacts from HEAD. The only open items are the counsel questions
above (Q1–Q6) and the release decision in item 10. This document does not
constitute legal advice; see [`MODEL_LICENSE.md`](MODEL_LICENSE.md) for the
model terms themselves.
