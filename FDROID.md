# F-Droid distribution status

**Status: not buildable on the F-Droid buildserver. The exported descriptor is
emitted with `Disabled:` and states why.** This document records the verified
blockers, the options, and the conditions that would let the channel be enabled.
It is a distribution note only: the application stays MIT and the model stays
OpenMDW-1.1 (see [`LICENSE_AUDIT.md`](LICENSE_AUDIT.md)).

## What the repository emits

`fastlane fdroid_export` renders
[`fastlane/fdroid/com.auratranscribe.app.yml`](fastlane/fdroid/com.auratranscribe.app.yml)
into `build/fdroid/com.auratranscribe.app.yml`. The template is committed so the
recipe is reviewable in a diff and gated by
[`scripts/check_fdroid_descriptor.py`](scripts/check_fdroid_descriptor.py).

## Verified blockers

### 1. The recipe pointed at a directory with no Gradle wrapper (fixed)

The previous descriptor declared `subdir: app` together with
`gradle: [assembleRelease]`. F-Droid runs the build in the `subdir`, and this
repository keeps `gradlew` and `settings.gradle.kts` at its **root** — there is
no `app/gradlew` — so the requested task could never start. The template no
longer sets `subdir`, and the gate fails if it comes back or if an
`app/gradlew` ever appears without the recipe being re-reviewed.

### 2. `assembleRelease` requires the bundled GGUF, which needs network (open)

`app/build.gradle.kts` wires `downloadModels` into `preBuild` for invocations
that produce the release variant. `downloadToDir` skips the network only when
`model_assets/src/main/assets/builtin-model/<name>` already exists **and**
matches the pinned SHA-256; otherwise it runs `curl` and throws
`GradleException` when that fails. The F-Droid buildserver has no network
access.

A model-less build is not a workaround: `verifyReleaseApkModel` (a dependency of
`assembleRelease`) and `verify_release_artifact.py` refuse an artifact without
the model, and `MODEL_LICENSE.md` / `MODEL_NOTICE.md` must be byte-identical
inside it. Loosening those gates to make F-Droid green would remove the
guarantee that a published build can transcribe offline, so this document treats
that as **not an option**.

### 3. The native build needs `cargo-ndk`, which is not in the image (open)

`:app:cargoNdkBuild` is an `Exec` task that runs `cargo ndk …` (see
`app/build.gradle.kts`). `cargo-ndk` is a Cargo subcommand binary that the CI
workflows install with `cargo install cargo-ndk --version 4.1.2`; it is not part
of the F-Droid build image and cannot be installed there without network access.

The old `prebuild` list also ran `cargo ndk …` itself, duplicating the Gradle
task while requiring the same missing binary. The template now only exports
`ANDROID_NDK_HOME` and documents the requirement.

## Options

| Option | What it resolves | Cost / risk | Recommendation |
|---|---|---|---|
| **A. Keep the descriptor `Disabled:` (current)** | Nothing to build; channel documented as unavailable | F-Droid users have no listing | **Chosen.** Honest, zero risk, no gate weakened |
| **B. Publish an F-Droid-specific variant that ships no model and downloads one at first run** | Blockers 2 (and 3, if the Rust toolchain is solved) | A published APK that is not self-sufficient offline; needs a new product + licensing decision, a separate gate policy, and a runtime-download UX for a keyless build. The current "release must contain the model" guarantee would no longer apply to that channel | Not now — requires an explicit product decision, not a build tweak |
| **C. Pre-seed the model in the build environment** | Blocker 2 | Works only where the build host can provide the file (the recipe would need a `prebuild` that copies a hash-verified artifact). F-Droid cannot host a 560 MB binary as a build input, so this does not make the *F-Droid* channel work | Useful for self-hosted builds, not for F-Droid |
| **D. Drop the lane/descriptor entirely** | Removes a misleading artifact | Loses the metadata export that a future compliant recipe would start from | Keep as a template (A), revisit if F-Droid changes its constraints |

## What would unblock the channel

All of the following, together:

1. `cargo-ndk` (or an equivalent offline path to `cargo build --target
   aarch64-linux-android`) available in the build image, **or** the Rust build
   reworked to need nothing beyond the NDK toolchain the image already pins.
2. A way for the build to obtain the 560 MB GGUF without network: an F-Droid
   mechanism for large build inputs, or a hosted, hash-verified artifact the
   recipe may fetch.
3. A decision on whether an F-Droid build must remain self-sufficient offline
   (option A stays) or whether a model-less variant is acceptable (option B —
   which needs its own, explicitly documented gate policy rather than a
   relaxation of the existing one).

Until then, `Disabled:` stays, and `scripts/check_fdroid_descriptor.py` fails if
it is removed without those conditions being met.
