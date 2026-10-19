#!/usr/bin/env python3
"""Check the F-Droid descriptor template and the lane that emits it.

The descriptor is committed as `fastlane/fdroid/com.auratranscribe.app.yml` and
rendered by `fastlane fdroid_export`. Two classes of defect are gated here:

* **Recipe defects that were real** — `subdir: app` pointed F-Droid at a module
  directory with no Gradle wrapper, and a `cargo ndk` prebuild required a tool
  the buildserver cannot install. The recipe may not reintroduce either.
* **Honesty defects** — the channel is not buildable offline (the release
  variant requires the 560 MB GGUF and the gates refuse a model-less APK), so
  the descriptor must stay `Disabled:` with that reason until it is resolved,
  and it must not claim a network step it cannot run.

The check is textual on purpose: the `validate` jobs must not depend on PyYAML,
and every assertion maps to a line a reviewer can see. It also verifies the
lane reads the template (instead of keeping a second copy in Ruby).

Exit status: 0 = consistent, 1 = a defect or an unjustified `Disabled:` removal.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_TEMPLATE = "fastlane/fdroid/com.auratranscribe.app.yml"
DEFAULT_FASTFILE = "fastlane/Fastfile"
PINNED_NDK = "28.2.13676358"
PLACEHOLDERS = ("@@VERSION_NAME@@", "@@VERSION_CODE@@", "@@COMMIT_SHA@@")


def fail(message: str) -> None:
    print(f"FDROID CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(root: Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_text(encoding="utf-8")


def check_template(root: Path, rel: str) -> None:
    text = read(root, rel)

    # 1. No `subdir`: the wrapper and settings.gradle.kts are at the repo root.
    if re.search(r"(?m)^\s+subdir:\s*\S+", text):
        fail(
            "the descriptor sets `subdir:`, but the Gradle wrapper and "
            "settings.gradle.kts are at the repository root; F-Droid would run "
            "in a module directory with no wrapper"
        )

    # 2. The wrapper really is at the root, and absent from app/.
    if not (root / "gradlew").is_file():
        fail("gradlew is missing at the repository root")
    if (root / "app" / "gradlew").exists():
        fail("app/gradlew exists; re-review whether `subdir: app` is now correct")

    # 3. The recipe must stay honest about being disabled.
    disabled = re.search(r"(?ms)^Disabled:\s*(.*?)(?=^\S|\Z)", text)
    if not disabled:
        fail(
            "the descriptor has no `Disabled:` field. The F-Droid channel is not "
            "buildable offline (bundled 560 MB GGUF + no cargo-ndk); if that has "
            "changed, prove it with a real build and update FDROID.md first"
        )
    reason = " ".join(disabled.group(1).split()).lower()
    for fragment in ("gguf", "network", "cargo-ndk"):
        if fragment not in reason:
            fail(f"the `Disabled:` reason does not mention {fragment!r}")

    # 4. Licensing and identity must match the rest of the repository.
    if not re.search(r"(?m)^License: MIT$", text):
        fail("the descriptor must declare `License: MIT` for the application")
    if not re.search(
        r"(?m)^Repo: https://github\.com/marodriguezd/Aura-Transcribe\.git$", text
    ):
        fail("the descriptor points at the wrong repository")

    # 5. The build recipe itself.
    if not re.search(r"(?m)^\s+- assembleRelease$", text):
        fail("the build list must request `assembleRelease`")
    for forbidden in ("curl ", "wget ", "cargo install"):
        if forbidden in text:
            fail(
                f"the recipe declares {forbidden.strip()!r}; the F-Droid "
                "buildserver is offline and would fail at that step"
            )
    if f"ndk: {PINNED_NDK}" not in text:
        fail(f"the descriptor must pin `ndk: {PINNED_NDK}`")

    # 6. Every placeholder the lane substitutes must be present, and none may be
    #    a YAML trap (an unquoted `{...}` would parse as a flow mapping).
    for placeholder in PLACEHOLDERS:
        if placeholder not in text:
            fail(f"placeholder {placeholder} is missing")
    if re.search(r"(?m)^\s+versionCode: \{", text):
        fail("versionCode must use @@VERSION_CODE@@, not a YAML flow mapping")

    # 7. A misspelt placeholder is worse than a missing one: the lane substitutes
    #    the three known names, so `@@COMMIT_SHAA@@` would ship verbatim. The
    #    template may only contain the placeholders the lane knows about.
    unknown = sorted(set(re.findall(r"@@[A-Za-z_]+@@", text)) - set(PLACEHOLDERS))
    if unknown:
        fail(
            "the template contains placeholder(s) the lane never substitutes: "
            + ", ".join(unknown)
        )


def check_lane(root: Path, rel: str) -> None:
    fastfile = read(root, rel)
    if "fdroid/com.auratranscribe.app.yml" not in fastfile:
        fail("the fdroid_export lane does not read the committed template")
    if "Unsubstituted" not in fastfile:
        fail("the lane does not fail on an unsubstituted placeholder")
    if "fdroid_yaml = <<~YAML" in fastfile:
        fail(
            "the lane still builds the descriptor from a Ruby heredoc; the "
            "committed template is the single source of truth"
        )

    # The leftover check must name the placeholders. A bare `include?("@@")`
    # matched the template's own header comment ("substitutes the @@...@@
    # placeholders"), so the lane failed on every run, even with a perfect
    # template. Regression: that exact form is rejected here.
    if re.search(r"fdroid_yaml\.include\?\(\s*[\"']@@[\"']\s*\)", fastfile):
        fail(
            'the lane tests `include?("@@")`; the template header mentions the '
            "placeholders, so that check fires on every run. Check the three "
            "placeholder names instead"
        )
    for placeholder in PLACEHOLDERS:
        if placeholder not in fastfile:
            fail(
                f"the lane's leftover check does not name {placeholder}; it must "
                "substitute and verify the three known placeholders"
            )


def run(root: Path, template: str, fastfile: str) -> None:
    check_template(root, template)
    check_lane(root, fastfile)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: the root that contains this script)",
    )
    parser.add_argument("--template", default=DEFAULT_TEMPLATE)
    parser.add_argument("--fastfile", default=DEFAULT_FASTFILE)
    args = parser.parse_args(argv)

    run(args.root.resolve(), args.template, args.fastfile)
    print("F-Droid descriptor is consistent with this build.")
    print("  emitted with `Disabled:` (offline build + mandatory bundled model)")
    print("  root Gradle project (no `subdir`), pinned NDK, MIT, no network steps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
