# License Audit — Aura Transcribe

- **Review date:** 2026-10-19
- **Source-code license:** **MIT** (unchanged; the previously proposed GPL-3.0-only
  migration was withdrawn)
- **Scope:** repository source, Gradle/Cargo manifests, packaged assets, the
  bundled model artifact and its obligations, and publication metadata
  (README ×6, `ABOUT.md`, Fastlane).

## Decision

The application source code stays under **MIT**, as inherited from
`notune/android_transcribe_app`. The fork does not relicense upstream code and
does not add a copyleft layer of its own.

The bundled Nemotron GGUF is **not** MIT: it remains a separately licensed data
asset governed by the OpenMDW License Agreement 1.1 (OpenMDW-1.1). The two are
distributed together but licensed separately, and each is described in its own
document:

| Material | License | Document |
|---|---|---|
| Application source (Java, Rust, Gradle, resources) | MIT | [`LICENSE`](LICENSE) |
| Bundled model weights (GGUF) | OpenMDW-1.1 | [`MODEL_LICENSE.md`](MODEL_LICENSE.md), [`MODEL_NOTICE.md`](MODEL_NOTICE.md) |
| Native + Android dependencies | per package | [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), generated inventories |

## Why MIT, and why the GPL proposal was withdrawn

An earlier draft of this PR migrated fork-authored code to GPL-3.0-only. That
migration was withdrawn for two independent reasons:

1. **It was unnecessary.** MIT imposes no condition on the model it is
   distributed with. Adopting a copyleft license on the application would have
   created a compatibility question (GPL §5–§7 "combined work" boundaries
   against a separately licensed weight file) that the permissive baseline does
   not have. Creating an obligation in order to then argue about it is not sound
   license hygiene.
2. **The migration had no authority behind it.** Fork-authored code may be
   licensed by its authors, but the fork's files also contain upstream-derived
   material, and the change would have forced every downstream consumer —
   including F-Droid, which builds from source — into a different license for no
   functional benefit. Relicensing is a decision for copyright holders, and
   there is no recorded contributor agreement supporting it.

Keeping MIT also keeps the project consistent with its own metadata: the root
`LICENSE`, the upstream commit it derives from, and the store listing all
already described MIT.

## Findings

1. **Upstream code — MIT, preserved verbatim.** `LICENSE` is the upstream MIT
   text with `Copyright (c) 2025 Noah Mühl`, unmodified. A byte-identical copy is
   preserved as `licenses/UPSTREAM-MIT.txt` and packaged in the app assets so the
   notice travels with every APK/AAB.
2. **Manifests now declare the license they actually ship.** `Cargo.toml` and
   `crates/aura-core/Cargo.toml` declare `license = "MIT"`. Neither declared a
   license before, which made the metadata silent rather than wrong; it is now
   explicit and machine-checkable.
3. **The model was mislabeled in the READMEs.** All six READMEs credited the
   Nemotron GGUF as CC-BY 4.0. That was false: NVIDIA's model card declares
   `license_name: openmdw-1.1` under `license: other`, and the GGUF repository
   carries the same terms with a `LICENSE` and a `NOTICE`. The attribution now
   names OpenMDW-1.1 and links the reproduced agreement. No publication surface
   (README, `ABOUT.md`, store metadata, Fastlane) states the CC-BY claim any
   more, and `scripts/check_license_docs.py` fails if one reappears.
4. **Model obligations are met mechanically, not rhetorically.** OpenMDW-1.1
   requires a distributor to retain a copy of the agreement and the notices of
   origin supplied with the model. Both files are packaged at
   `assets/MODEL_LICENSE.md` / `assets/MODEL_NOTICE.md` in the APK and
   `model_assets/assets/…` in the AAB, and the release gates
   (`verifyReleaseApkModel`, `verifyReleaseBundleModel`, `verifyPackagingDecision`)
   reject an artifact where they are missing, duplicated or not byte-identical to
   their sources.
5. **The GGUF conversion adds no new license conditions on top of the base
   model.** The converter (`handy-computer`) publishes the same OpenMDW-1.1 terms
   and a `NOTICE` describing the conversion; `MODEL_NOTICE.md` reproduces that
   `NOTICE` verbatim. The conversion is a repackaging plus quantization — the
   weights were not retrained — so it does not introduce a second licensor with
   different terms. It does add an attribution fact (who converted, from which
   commit), which the notice records.
6. **Dependency inventory — generated and inspected.** The Gradle report for
   `:app:releaseRuntimeClasspath` contains 97 modules: 91 Apache-2.0, 3 under the
   Android SDK License, 2 under the ML Kit Terms of Service, 1 with a blank
   manifest field (`org.jspecify:jspecify`, whose POM declares Apache-2.0). The
   Cargo report contains 122 resolved entries with no missing license metadata
   and 3 target-specific composite expressions flagged for re-review
   (`r-efi`, `wasip2`, `wit-bindgen`) — none of them Android build inputs.
   Neither report is a legal clearance; both are review artifacts.
7. **Test dependencies are isolated.** `org.json:json`, JUnit and MockWebServer
   are `testImplementation`-only and are not packaged in the app.
8. **Audio fixture removed.** The in-app `bench.wav`, its UI card, JNI bridge and
   the eight locale strings were removed, as were the scripts that generated it.
   No audio is tracked or bundled. The retained benchmark tooling is synthetic
   and does not claim measured ASR accuracy.
9. **Inherited visual assets are attributed.** Byte-identical screenshots, store
   artwork and drawable icons from upstream are listed in
   `THIRD_PARTY_NOTICES.md` and remain covered by the upstream MIT grant.

## What this audit does *not* claim

- It is **not** a legal certification, and passing its automated gates does not
  establish legal compatibility of any distribution. The gates prove that the
  documents are present, consistent and pinned — nothing more.
- It does not verify media rights for any third-party content beyond the
  provenance checks described above.
- It does not assert that OpenMDW-1.1 is an OSI-approved or SPDX-listed license.
  It is cited by name, as NVIDIA identifies it.
- It does not assert that every module in the resolved inventories ships in the
  APK; the inventories cover build-time and non-Android targets too.

A residual legal-characterization question remains available to counsel: whether
a GGUF inside an application package is best characterized as a separate work
distributed alongside the app (aggregate) or as part of a single work. With MIT
on the code side, **both characterizations are permissible** — MIT has no
copyleft condition to conflict with OpenMDW-1.1, and OpenMDW-1.1's own
conditions (agreement + notices) are satisfied by this packaging. That is why
the question is no longer a blocker; it would become one again only if the code
were relicensed to a copyleft license, if OpenMDW-1.1 were replaced by a version
with field-of-use or copyleft terms, or if the notices stopped travelling with
the model.

## Verification

- `scripts/check_license_docs.py` — fails on a non-MIT root license, a stale
  license badge in any README, an `MIT` claim over the model, a CC-BY or GPL
  claim, a Cargo license mismatch, a drifted asset copy, a missing model NOTICE,
  a `bench.wav` in assets, or a model URL that is not pinned to the verified
  revision. `scripts/test_check_license_docs.py` exercises it in both directions
  (accepting the current tree, rejecting each specific defect).
- `:app:verifyPackagingDecision` — drives the real archive gates over synthetic
  APK/AAB fixtures, including the model-document cases.
- `:app:generateLicenseReport` and `scripts/report_cargo_licenses.py` — the two
  inventories referenced above, retained as a CI artifact.
- `python3 scripts/check_translations.py` — every new/reworded string stays
  present in all locales.

## Status

The licensing question is **resolved for this repository**: MIT for the
application, OpenMDW-1.1 for the model, notices packaged and verified. No
licensing blocker remains for distribution of the APK or AAB. Nothing in this
audit constitutes legal advice; see `MODEL_LICENSE.md` for the model terms
themselves.
