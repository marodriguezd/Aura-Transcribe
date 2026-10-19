#!/usr/bin/env python3
"""Fail closed on the release workflow's publishing guards.

`android_release.yml` is the only workflow in the repository that can publish.
Two properties must hold or a routine CI edit could turn every manual run into
a release:

1. `workflow_dispatch` defaults to `validation_only: true`, so a manual run
   builds and verifies the signed artifacts without creating anything.
2. The single publishing step (`softprops/action-gh-release`) is reachable only
   from a `refs/tags/v*` ref **and** a run that is allowed to publish, and the
   large APK/AAB workflow artifacts are retained only for such runs.

The check is textual on purpose: it must not depend on PyYAML being installed
in the validate job, and it asserts the literal guard expression that GitHub
evaluates.

Exit status: 0 = safe, 1 = a guard is missing or weakened.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_WORKFLOW = ".github/workflows/android_release.yml"

# The exact expression GitHub evaluates for the publishing step.
PUBLISH_GUARD = (
    "startsWith(github.ref, 'refs/tags/v') && "
    "(github.event_name == 'push' || inputs.validation_only == false)"
)
TAG_GUARD = "startsWith(github.ref, 'refs/tags/v')"
RELEASE_ACTION = "softprops/action-gh-release"
STEP_SPLIT_RE = re.compile(r"(?m)^(?=    - )")
FORBIDDEN_COMMANDS = (
    re.compile(r"(?m)^\s*gh release (?:create|edit|upload)\b"),
    re.compile(r"(?m)^\s*git tag\b"),
    re.compile(r"(?m)^\s*git push\b"),
)


def fail(message: str) -> None:
    print(f"RELEASE WORKFLOW CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"workflow file is missing: {path}")
    return path.read_text(encoding="utf-8")


def steps_of(text: str) -> list[tuple[str | None, str]]:
    """Split the workflow into `(step name, step text)` chunks."""
    chunks = [c for c in STEP_SPLIT_RE.split(text) if c.lstrip().startswith("- ")]
    out: list[tuple[str | None, str]] = []
    for chunk in chunks:
        match = re.search(r"(?m)^    - name: (.*)$", chunk)
        out.append((match.group(1).strip() if match else None, chunk))
    return out


def find_step(steps: list[tuple[str | None, str]], name: str) -> str:
    matches = [text for step_name, text in steps if step_name == name]
    if not matches:
        fail(f"step {name!r} is missing")
    if len(matches) > 1:
        fail(f"step {name!r} appears {len(matches)} times; exactly one is expected")
    return matches[0]


def if_line(step_text: str) -> str | None:
    match = re.search(r"(?m)^\s*if:\s*(.+)$", step_text)
    return match.group(1).strip() if match else None


def check_dispatch_default(text: str) -> None:
    if not re.search(r"(?m)^  workflow_dispatch:\s*$", text):
        fail("workflow_dispatch is missing; a tag-outage rerun would be impossible")
    if not re.search(r"(?m)^      validation_only:\s*$", text):
        fail("workflow_dispatch has no `validation_only` input")
    if not re.search(r"(?m)^        default: true\s*$", text):
        fail("`validation_only` must default to true, so a manual run publishes nothing")
    if not re.search(r"(?m)^        type: boolean\s*$", text):
        fail("`validation_only` must be declared as a boolean input")


def check_single_publisher(text: str) -> None:
    publishers = text.count(RELEASE_ACTION)
    if publishers != 1:
        fail(
            f"expected exactly one {RELEASE_ACTION} step, found {publishers}; "
            "every additional publisher needs its own reviewed guard"
        )


def check_publish_guard(steps: list[tuple[str | None, str]]) -> None:
    guard = if_line(find_step(steps, "Create Release"))
    if guard is None:
        fail("the `Create Release` step has no `if:` guard and would run on every dispatch")
    if guard != PUBLISH_GUARD:
        fail(
            "the `Create Release` guard is not the reviewed expression.\n"
            f"  expected: {PUBLISH_GUARD}\n"
            f"  found:    {guard}"
        )
    if "tag_name: v${{ env.VERSION_NAME }}" not in find_step(steps, "Create Release"):
        fail("the `Create Release` step must keep `tag_name: v${{ env.VERSION_NAME }}`")


def check_artifact_uploads(steps: list[tuple[str | None, str]]) -> None:
    for name in ("Upload APK Artifact", "Upload AAB Artifact"):
        guard = if_line(find_step(steps, name))
        if guard is None or TAG_GUARD not in guard:
            fail(
                f"{name} is not limited to tag refs; a validation-only run would "
                "leave a distributable artifact behind"
            )
    report = find_step(steps, "Upload validation report")
    guard = if_line(report)
    if guard is None or "always()" not in guard:
        fail("the validation report must be uploaded unconditionally (`if: always()`)")
    mode = find_step(steps, "Report release mode")
    if "RELEASE_MODE" not in mode:
        fail("the `Report release mode` step no longer records RELEASE_MODE")


def check_no_other_publishing_commands(text: str) -> None:
    for pattern in FORBIDDEN_COMMANDS:
        match = pattern.search(text)
        if match:
            fail(
                "the workflow runs a publishing command "
                f"({match.group(0).strip()!r}); publishing must go through the "
                "single guarded `Create Release` step"
            )


def run(workflow: Path) -> None:
    text = read(workflow)
    check_dispatch_default(text)
    check_single_publisher(text)
    steps = steps_of(text)
    check_publish_guard(steps)
    check_artifact_uploads(steps)
    check_no_other_publishing_commands(text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: the root that contains this script)",
    )
    parser.add_argument(
        "--workflow",
        default=DEFAULT_WORKFLOW,
        help=f"workflow path relative to --root (default: {DEFAULT_WORKFLOW})",
    )
    args = parser.parse_args(argv)

    run(args.root.resolve() / args.workflow)
    print("Release workflow publishing guards are intact.")
    print("  manual dispatch -> validation_only: true (builds and verifies, publishes nothing)")
    print("  publishing      -> tag ref + (push | validation_only: false)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
