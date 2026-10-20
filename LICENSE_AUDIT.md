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

A **second independent pass** (2026-10-20, audit of the audit) reproduced every
material claim of this file from primary sources: the upstream `LICENSE`/
`NOTICE`/agreement were re-fetched at the pinned revision and re-compared
byte-for-byte; the published v1.0.0 assets were **re-downloaded and re-hashed**
(`0550c238…`, `f3851510…` — matching the release digests and the publishing
run's validation report for commit `834fe3f`; that publication was superseded
later the same day, see “Second replacement”); the model entry inside both
artifacts was re-hashed against the pin (`86429e8c…9910d65`, 559,647,200 B,
exactly once per artifact); the signer of both artifacts was re-checked against
`keystore/release-cert.sha256` (`2ee6ab00…8483`); `zipalign -c -P 16` and
16 KB LOAD alignment of all three shipped `.so` files were re-verified; and both
dependency inventories were regenerated with identical results (97 Gradle
modules with the `jspecify` blank-field entry; 122 Cargo entries, 0 missing, 3
unrecognized). The pass found three additional repository-level defects that
the first pass had missed — F-9, F-10 and F-11 — all remediated at HEAD as
described in the findings register and in “Second-pass remediation evidence”.

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
5. **Originally published v1.0.0 distribution surface (defective — see
   F-1/F-2; superseded, kept as the historical record).** Inspected 2026-10-20
   via the zip central directories of the then-published release assets
   (`Aura_Transcribe_v1.0.0.apk` 575,589,524 B, `Aura_Transcribe_v1.0.0.aab`
   558,508,606 B). These binaries were replaced the same day under explicit
   owner authorization (§“Remediation evidence”); copies already downloaded
   remain out of the project's control:
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
| MIT (`miniz` 3.1.1, vendored in `transcribe-cpp-sys` and compiled into the shipped `.so`) | Retain copyright and permission notice | `assets/licenses/miniz-MIT.txt` (F-9 remediation) | `check_license_docs.py` byte pin, `verify_artifact_notices.py` |
| MPL-2.0 (Public Suffix List data inside `okhttp`) | Keep attribution notices; source remains available | `assets/licenses/okhttp-NOTICE.txt` + the in-jar `okhttp3/internal/publicsuffix/NOTICE`; source at publicsuffix.org (F-10 remediation) | same |

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
18 negative tests, wired into both workflows and the release verification  (2026-10-20). *Remediation of the surface itself:* the v1.0.0 release and tag
  were replaced by a corrected rebuild the same day (§“Remediation evidence”).
  *Residual:* copies of the original binaries already downloaded cannot be
  recalled; see counsel question Q2.

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

**F-9 — MEDIUM (notice gap in the distributed native code; found in the second
independent pass 2026-10-20; remediated at HEAD; residual exposure on the
published v1.0.0).** `transcribe-cpp-sys` 0.1.3 vendors **miniz 3.1.1**
(`src/third_party/miniz`, MIT — Copyright 2013-2014 RAD Game Tools and Valve
Software; Copyright 2010-2014 Rich Geldreich and Tenacious Software LLC) and
compiles it into `libtranscribe`, which is statically linked into
`libandroid_transcribe_app.so` in every artifact (evidence: the crate's
`src/CMakeLists.txt` lists `third_party/miniz/miniz.c` in the library sources;
the vendored `UPSTREAM` file documents the snapshot and its license). Neither
the notice set nor `licenses/` carried its license text, so the MIT retention
condition for that component was not satisfied by the repository's notice set
for the distributed binaries. *Remediation:* verbatim
[`licenses/miniz-MIT.txt`](licenses/miniz-MIT.txt) (SHA-256
`6f20fa76…464d3d`, pinned in `check_license_docs.py`; byte-identical to the
license traveling with the vendored snapshot and content-identical to upstream
`richgel999/miniz` 3.1.1 `LICENSE`, which uses CRLF line endings), packaged at
`assets/licenses/miniz-MIT.txt`, listed in `THIRD_PARTY_NOTICES.md` §3, and
required by `verify_artifact_notices.py` with two new negative tests. The
extended gate re-run against the v1.0.0 binaries published earlier the same day
fails closed with exactly this diagnosis. *Residual:* **cured for the shipped
artifacts.** The second replacement republished `v1.0.0` from the verified
commit `bb15ec4` later on 2026-10-20, and its published APK/AAB pass 26/26
notice checks including `miniz-MIT.txt` (§“Second replacement”). Copies of the
earlier (pre-fix) binaries already downloaded remain outside the project's
control (same class as F-1/F-2; see Q2).

**F-10 — MEDIUM (unattributed MPL-2.0 data in the distributed artifacts; found
in the second pass 2026-10-20; remediated at HEAD; residual as F-9).** OkHttp
4.12.0 packages `okhttp3/internal/publicsuffix/publicsuffixes.gz`, compiled
from **The Public Suffix List** under the **Mozilla Public License 2.0**, and it
ships in the published APK/AAB (confirmed by entry inventory of both artifacts).
OkHttp's own `NOTICE` travels inside the jar and therefore inside the APK, so
the upstream attribution is physically present in the binaries — but the
repository's "complete notice set" never mentioned the PSL, MPL-2.0, or the
source-availability pointer, so the completeness claim was wrong at
documentation level. *Remediation:* verbatim
[`licenses/okhttp-NOTICE.txt`](licenses/okhttp-NOTICE.txt) (byte-identical to
the `NOTICE` inside `okhttp-4.12.0.jar`, SHA-256 `8a9c58fb…5e1876a`, pinned),
packaged at `assets/licenses/`, documented in `THIRD_PARTY_NOTICES.md` §4 with
the MPL-2.0 and PSL source pointers, and required by the artifact gate
(negative test added). MPL-2.0's conditions attach to the PSL data file itself
and do not relicense the application or OkHttp. *Residual:* the published
artifacts rely on OkHttp's in-jar `NOTICE` alone (F-9/Q2 class).

**F-11 — LOW (supply-chain pinning consistency; remediated).** `ModelsActivity`'s
user-facing model catalog linked the *bundled* Nemotron artifact from
`resolve/main` — the exact anti-pattern the pin policy forbids for automated
fetches. The app never downloads from that URL programmatically (it is opened
via `ACTION_VIEW`), so no automated fetch was unpinned and no shipped byte is
affected; but a user following the link could obtain bytes different from the
verified SHA-256. *Remediation:* the catalog entry is now pinned to revision
`53ce288f…8096dd`, the same verified artifact. The other catalog entries are
third-party models outside the bundled-model pin policy and remain as-is by
design.

**F-12 — INFORMATIONAL (toolchain components).** Every Rust-built binary also
carries the standard library's vendored dependencies (`miniz_oxide` 0.9.1,
`addr2line`, `gimli`, `hashbrown`, `rustc-demangle`, `object`, `libc`, `memchr`;
MIT and/or Apache-2.0). Verified as toolchain-inherent, not an app dependency:
a plain `hello.rs` build contains the same `/rust/deps/` path strings as the
shipped `.so`, and the workspace graph has no `miniz_oxide` at all. They are
invisible to `cargo metadata` and their license texts travel with the Rust
toolchain. Documented in `THIRD_PARTY_NOTICES.md` §3 so the `miniz_oxide`
(Rust) and `miniz` (C) names are never conflated.

**F-13 — INFORMATIONAL (attribution richness).** Individual ggml sources carry
merged contributors' copyright notices (Intel Corporation, Arm Limited, the
Mozilla Foundation, and the YaRN authors Jeffrey Quesnelle and Bowen Peng,
MIT). They fall under the ggml MIT grant whose text ships as
`licenses/ggml-MIT.txt`; recorded in `THIRD_PARTY_NOTICES.md` §3. Whether
per-file notices must be reproduced in a *binary* distribution in addition to
the license text is a counsel-level question (Q7).

**F-14 — INFORMATIONAL (document formatting).** The "full text" embedded in
`MODEL_LICENSE.md` is typographically normalized (line re-wrapping, curly
quotes) relative to the upstream agreement; the **byte-exact** copy travels in
both artifacts as `assets/licenses/OpenMDW-1.1.txt` (verified byte-identical to
the upstream `LICENSE` at the pinned revision, SHA-256 `2ab44b68…a74df`), so
the OpenMDW-1.1 "copy of this agreement" retention rests on that file, not on
the reformatted rendering.

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
  (33 negative tests): fails on a non-MIT root license, a stale license badge
  in any README, an MIT claim over the model, a CC-BY or copyleft claim on the
  publication surface, a Cargo license mismatch, a drifted asset copy, a
  rewritten upstream `NOTICE` or native license text (byte pins — now covering
  `miniz-MIT.txt` and `okhttp-NOTICE.txt` as well), a missing
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

## Remediation evidence — the v1.0.0 replacement (2026-10-20)

Executed under the owner's explicit authorization (delete Release `v1.0.0` +
its attached assets + remote tag `v1.0.0`; recreate the tag at the verified
corrected commit; let the guarded release workflow publish):

- **Candidate process:** PR #15 (superseding #14, closed by a branch rename),
  green CI, merged to `main` as `834fe3f5b8c9f69242e9fdea1eab9ca1bab9ec0b`.
  Validation-only signed runs against the candidate (`39406276811`) and the
  exact tag-target commit (`39409321129`) both passed before anything was
  deleted.
- **Replacement:** Release `v1.0.0` (3 assets) and tag `v1.0.0` deleted;
  annotated tag `v1.0.0` recreated at `834fe3f`; publishing run `39410426931`
  succeeded. No other release or tag exists or was created.
- **Published artifacts, verified after publication by downloading them:**
  - `Aura_Transcribe_v1.0.0.apk` — SHA-256
    `0550c238b22418111adb8cffd350ad7358c09890627a37039056cdbeb44916b0`
  - `Aura_Transcribe_v1.0.0.aab` — SHA-256
    `f3851510151cec5a25ad7fdb4d91dbe3aa8260969f3117dc582597b8e9c21001`
  - Both hashes match the publishing run's validation report for the exact
    staged files.
- **Content inspection (the real published bytes):** 22/22 notice-set checks
  `OK` — 11 documents (MIT `LICENSE`, `THIRD_PARTY_NOTICES.md`,
  `MODEL_LICENSE.md`, `MODEL_NOTICE.md` and the 7 `licenses/*` texts) in both
  artifacts, each byte-identical to the repository sources at its exact
  delivery path (`assets/` in the APK; `base/assets/` +
  `model_assets/assets/` in the AAB). Model present exactly once (559,647,200
  B) and verified against the pinned SHA-256. **No `bench.wav` or other audio
  entry in either artifact.**
- **Signatures:** the APK (`apksigner verify --print-certs`) and the AAB
  (`keytool -printcert -jarfile`; `jarsigner` reports *jar verified.*) are both
  signed by the pinned production certificate
  `2ee6ab002e02310523a3e2565b8128a11952d902a2c748d7c5b869bc17208483`.
- **Alignment:** `zipalign -c -P 16` “Verification successful” on the APK;
  `checkNativeAlignment` reports 16384 B LOAD alignment for every shipped
  `.so`.

What this evidence does **not** establish: it proves what the *current*
release carries. It cannot recall or alter copies of the original (defective)
binaries already downloaded, and it is not a legal certification.

## Second replacement — F-9/F-10 shipped in v1.0.0 (2026-10-20)

Executed under the same owner authorization (delete every existing release and
tag; recreate only `v1.0.0` at the verified commit; let the guarded release
workflow publish), after PR [#17](https://github.com/marodriguezd/Aura-Transcribe/pull/17)
(merged as `7133a6d`) and PR [#18](https://github.com/marodriguezd/Aura-Transcribe/pull/18)
(merged as `bb15ec4`) landed. This closes the residual recorded against F-9 and
F-10: the artifacts published earlier that day carried the pre-fix notice set.

- **Verified commit:** `bb15ec4eb5253342be67acfab275fce2b8593364` (`main`,
  versionCode 51, `versionName` 1.0.0). Gates re-run on that exact commit:
  `cargo fmt --check`; `cargo test --workspace` (68 tests: 33 + 26 + 7 + 2);
  `:app:testDebugUnitTest` (371 tests, 31 suites, 0 failures, 0 skipped);
  `:app:lintDebug` (0 errors, 68 documented warnings); the packaging-decision,
  model-download, native-alignment and `checkModels` gate tasks; and the Python
  compliance suite (`check_license_docs`, `check_release_workflow`,
  `check_jni_parity`, `check_translations`, `check_release_notes`,
  `check_repo_hygiene`, `check_fdroid_descriptor` plus their 200 negative
  tests).
- **Pre-publication signed validation:** validation-only release run
  `39424308202` (`workflow_dispatch`, `validation_only=true`) on `bb15ec4`
  succeeded: **26/26** notice-set checks (13 documents per artifact), the model
  once per artifact (559,647,200 B, pinned digest) and the pinned signer
  `2ee6ab00…8483`. That run created no tag, release or asset.
- **Replacement:** the sole existing release `v1.0.0` and its tag were deleted
  (0 releases and 0 tags remained), then annotated tag `v1.0.0` was recreated
  at `bb15ec4` (tag object `77f61490dabae795df19989802426d46d8214969`, which
  dereferences to that commit). Publishing run `39425583352` succeeded and its
  `Create Release` step executed under the guarded condition
  (`startsWith(github.ref, 'refs/tags/v') && (push || validation_only == false)`).
- **Published artifacts, re-downloaded and re-hashed after publication:**
  - `Aura_Transcribe_v1.0.0.apk` — SHA-256
    `e9bbb41ab034a4459ecaa22dfeb144260e4a801f9ca1e8c79e43baf3b387ae59`
    (575,258,923 B)
  - `Aura_Transcribe_v1.0.0.aab` — SHA-256
    `4fc6b79bc20355afc062510d44c45349f8108beee8df8108a791b82b8df442a8`
    (558,222,129 B)
  - Both match the GitHub-reported asset digests. The AAB digest is identical to
    the validation-only candidate; the APK digest differs from that candidate
    (`a3eec11f…`) because APK packaging embeds timestamps.
- **Content inspection of the published bytes:** 13/13 documents per artifact
  (26 checks total) `OK` — `LICENSE`, `THIRD_PARTY_NOTICES.md`,
  `MODEL_LICENSE.md`, `MODEL_NOTICE.md` and all 9 `licenses/*` texts, including
  `miniz-MIT.txt` (1,162 B) and `okhttp-NOTICE.txt` (218 B), each byte-identical
  to the repository sources at its exact delivery path (`assets/` in the APK;
  `base/assets/` + `model_assets/assets/` in the AAB). Model present exactly
  once per artifact (559,647,200 B, `86429e8c…9910d65`).
- **Signatures:** the APK (`apksigner verify --print-certs`) reports a single V2
  signer `2ee6ab002e02310523a3e2565b8128a11952d902a2c748d7c5b869bc17208483`; the
  AAB (`jarsigner -verify` → *jar verified*) carries the same certificate
  (`keytool -printcert -jarfile`). Both match the pinned trust anchor.
- **Alignment:** `zipalign -c -P 16 -v 4` reports “Verification successful”.

What this evidence does **not** establish: copies of the pre-fix v1.0.0
binaries obtained before the replacement cannot be recalled, and the Q2
counsel question about the earlier omission is unaffected by it. It is not a
legal certification.

## Second-pass remediation evidence (2026-10-20, F-9/F-10/F-11)

- Branch `licensing/notice-gap-remediation`, candidate commit
  `377cf5d969c71eacb0a5aeb35c73d497c4d18298`, merged as PR
  [#17](https://github.com/marodriguezd/Aura-Transcribe/pull/17) into `main`
  as `7133a6d0cd9cc737c24e4fc3fd0365c81301159b`, after the full validation
  suite (Python compliance gates, Rust fmt + workspace tests, Android JVM
  tests, lint, packaging-decision gates, release-workflow guards; PR CI run
  `39419197557` green) and a **validation-only signed release build** against
  the exact candidate commit (run `39419225434`, `validation_only=true`,
  success; no tag, no release, no asset published).
- New verbatim texts: `licenses/miniz-MIT.txt` (SHA-256 `6f20fa76…464d3d`, the
  license of the miniz 3.1.1 snapshot vendored in `transcribe-cpp-sys` and
  compiled into the shipped native library) and `licenses/okhttp-NOTICE.txt`
  (SHA-256 `8a9c58fb…5e1876a`, the Public Suffix List / MPL-2.0 attribution
  from `okhttp-4.12.0.jar`); both are byte-pinned in `check_license_docs.py`,
  packaged at `assets/licenses/`, and required by `verify_artifact_notices.py`.
- `THIRD_PARTY_NOTICES.md` (+ its packaged copy) now documents miniz, the
  ggml contributor notices, the Rust-std vendored components, and the PSL /
  MPL-2.0 data with its source-availability pointer. Two new negative tests
  (33 total) pin the new byte-identity requirements.
- The `ModelsActivity` catalog link for the bundled Nemotron artifact is
  pinned to revision `53ce288f…8096dd` (F-11).
- The validation-only run's real signed artifacts (inspected through its
  retained `release-validation-report.txt`) pass **26/26** notice-set checks —
  13 documents per artifact, including `miniz-MIT.txt` (1,162 B) and
  `okhttp-NOTICE.txt` (218 B), each byte-identical to the repository sources —
  with the model present exactly once per artifact (559,647,200 B) and the
  pinned signer `2ee6ab00…8483`. Candidate hashes, for the record and **not
  published anywhere**: APK `b43eb05a0ca6b8f87971c9419b1b1ae673620aa3565ee37a2d71a8ea33996e7b`,
  AAB `ae7df172f9a250c8f8bfd4ec8a3a9fd02b4fd7b16038351d374b86ffae0e1927`.
- Re-running the extended `verify_artifact_notices.py` against the **published**
  v1.0.0 artifacts fails closed with exactly the expected residual: the two new
  texts and the updated notices file are absent there. That residual is real
  and is **not** claimed to be cured: like F-1/F-2 it can only be addressed in
  a future release (see Q2; publishing new artifacts was out of scope for this
  audit and was not done).

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
- **Q2 — Remediation of the originally published v1.0.0 artifacts (F-1/F-2);
  exact open decision.** The repository-side remediation is complete and was
  owner-authorized (2026-10-20): the v1.0.0 release and its tag were
  **replaced** by a functionally identical corrected rebuild (versionCode 51,
  `versionName` 1.0.0) carrying the complete notice set, verified before and
  after publication (§“Remediation evidence”). The **exact decision that
  remains for external legal counsel** is whether the replacement cures the
  omission in v1.0.0 binaries that users already downloaded — copies already
  obtained cannot be recalled — or whether the project must additionally
  (a) notify users who obtained the original binaries, (b) keep a public
  record of the replacement (this file serves as one), or (c) take other
  remedial steps in the distributing jurisdictions. **Deleting the old release
  cannot recall copies already downloaded; historical liability and legal
  uncertainty are not extinguished by the replacement**, and nothing in this
  document claims that they are.
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
- **Q7 — Per-file copyright notices in binary form (F-13).** Whether MIT's
  "this copyright notice" retention duty, for multi-contributor trees such as
  ggml (Intel, Arm, Mozilla, YaRN authors), requires reproducing the individual
  per-file copyright lines in a binary distribution in addition to the
  aggregate license text the project ships, or whether the upstream license
  text suffices.

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
| 10 | Publication of a corrected release to supersede v1.0.0's notice defect | ✅ done 2026-10-20 (owner-authorized): Release `v1.0.0` + tag replaced twice; the final publication is from verified commit `bb15ec4` (versionCode 51) and its published bytes were re-downloaded and inspected — 26/26 notice checks OK, pinned signer, model once per artifact, 16 KB aligned (§“Second replacement”). Copies of the original binaries already downloaded remain outside the project's control (Q2) |
| 11 | Vendored native code (`miniz`) and jar-resource third-party data (PSL/MPL-2.0) identified, attributed and shipped | ✅ at HEAD **and in the published v1.0.0 artifacts** (second-pass F-9/F-10): gate-enforced at HEAD and verified on the published bytes — `miniz-MIT.txt` and `okhttp-NOTICE.txt` present, byte-identical (§“Second replacement”) |

**Verdict:** no repository-level licensing blocker remains for producing and
publishing artifacts **from HEAD**: the corrected v1.0.0 (versionCode 51) is
published from the verified commit `bb15ec4`, its bytes were independently
re-downloaded and inspected (§“Remediation evidence” and “Second replacement”),
and the second-pass gaps (F-9/F-10/F-11) are remediated, gate-enforced at HEAD
**and present in the published artifacts** (26/26 notice checks). The earlier
publication of 2026-10-20 carried the pre-fix notice set; it was replaced the
same day and no release or tag other than `v1.0.0` exists. Copies of the
pre-fix binaries already downloaded cannot be recalled.
The open items are the counsel questions above (Q1–Q7) — above all Q2, since
deleting the old release cannot recall copies already downloaded and no claim
is made that historical liability or legal uncertainty has disappeared. This
document does not constitute legal advice; see
[`MODEL_LICENSE.md`](MODEL_LICENSE.md) for the model terms themselves.
