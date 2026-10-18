#!/usr/bin/env python3
"""Release-notes gate for Aura Transcribe.

Validates ``RELEASE_NOTES.md`` against the project rules (AGENTS.md §1.3 /
§4.11):

* the file must exist and be non-empty;
* its word count must be between 125 and 250 (inclusive);
* it must not accumulate past-release history — i.e. no version token other
  than the current one, and no more than one version-bearing header;
* (optional, non-fatal) ``CHANGELOG.md`` should contain a matching entry.

Exit status: 0 = pass, 1 = fail.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MIN_WORDS = 125
MAX_WORDS = 250

VERSION_TOKEN_RE = re.compile(r"v?\d+\.\d+(?:\.\d+)?")
HEADER_RE = re.compile(r"^#{1,6}\s+.*\bv\d*\.\d", re.MULTILINE)


def get_current_version(root: Path) -> str | None:
    """Best-effort current version from ``app/build.gradle.kts``."""
    gradle = root / "app" / "build.gradle.kts"
    if not gradle.is_file():
        return None
    try:
        text = gradle.read_text(encoding="utf-8")
    except OSError:
        return None
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        return None
    return match.group(1).strip()


def normalize_version(token: str) -> str:
    return token.strip().lstrip("vV")


def count_words(text: str) -> int:
    return len(text.split())


def count_version_headers(text: str) -> int:
    return len(HEADER_RE.findall(text))


def find_version_tokens(text: str) -> list[str]:
    return VERSION_TOKEN_RE.findall(text)


def validate(release_text: str, changelog_text: str | None = None,
            current_version: str | None = None) -> list[str]:
    """Return a list of human-readable problems (empty == pass)."""
    errors: list[str] = []

    if not release_text.strip():
        errors.append("RELEASE_NOTES.md is empty")
        return errors

    words = count_words(release_text)
    if words < MIN_WORDS:
        errors.append(f"word count {words} is below the minimum of {MIN_WORDS}")
    if words > MAX_WORDS:
        errors.append(f"word count {words} exceeds the maximum of {MAX_WORDS}")

    headers = count_version_headers(release_text)
    if headers > 1:
        errors.append(f"found {headers} version headers; expected at most one")

    if current_version is not None:
        current_norm = normalize_version(current_version)
        bad_tokens = [
            tok for tok in find_version_tokens(release_text)
            if normalize_version(tok) != current_norm
        ]
        if bad_tokens:
            errors.append(
                "release notes mention a non-current version: "
                + ", ".join(sorted(set(bad_tokens)))
            )

    return errors


def validate_files(release_path: Path, changelog_path: Path | None = None,
                  current_version: str | None = None) -> list[str]:
    errors: list[str] = []
    if not release_path.is_file():
        return [f"missing file: {release_path}"]
    try:
        release_text = release_path.read_text(encoding="utf-8")
    except OSError as exc:
        return [f"cannot read {release_path}: {exc}"]

    if current_version is None:
        # Derive from a sibling build.gradle.kts when possible.
        root = release_path.parent
        # Walk up a couple of levels to find app/build.gradle.kts.
        for candidate_root in (release_path.parent, release_path.parent.parent,
                               release_path.parent.parent.parent):
            gv = get_current_version(candidate_root)
            if gv is not None:
                current_version = gv
                break

    errors.extend(validate(release_text, None, current_version))

    # Optional, non-fatal CHANGELOG check.
    changelog_text = None
    if changelog_path is not None and changelog_path.is_file():
        try:
            changelog_text = changelog_path.read_text(encoding="utf-8")
        except OSError:
            changelog_text = None
    elif changelog_path is None:
        for candidate_root in (release_path.parent, release_path.parent.parent,
                               release_path.parent.parent.parent):
            cp = candidate_root / "CHANGELOG.md"
            if cp.is_file():
                changelog_path = cp
                try:
                    changelog_text = cp.read_text(encoding="utf-8")
                except OSError:
                    changelog_text = None
                break

    if current_version is not None and changelog_text is not None:
        if (current_version not in changelog_text
                and ("v" + current_version) not in changelog_text):
            print(f"WARNING: CHANGELOG.md has no entry for {current_version}",
                  file=sys.stderr)

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate RELEASE_NOTES.md")
    parser.add_argument("--root", type=Path, default=ROOT,
                        help="repository root (default: repo root)")
    parser.add_argument("--release-notes", type=Path, default=None,
                        help="path to RELEASE_NOTES.md")
    parser.add_argument("--changelog", type=Path, default=None,
                        help="path to CHANGELOG.md")
    args = parser.parse_args(argv)

    release_path = args.release_notes
    if release_path is None:
        release_path = args.root / "RELEASE_NOTES.md"
    changelog_path = args.changelog
    if changelog_path is None:
        changelog_path = args.root / "CHANGELOG.md"

    current_version = get_current_version(args.root)

    errors = validate_files(release_path, changelog_path, current_version)

    if errors:
        for err in errors:
            print(f"FAIL: {err}", file=sys.stderr)
        print("[CHECK-RELEASE-NOTES] FAIL", file=sys.stderr)
        return 1

    text = release_path.read_text(encoding="utf-8")
    print(f"[CHECK-RELEASE-NOTES] PASS: {count_words(text)} words, "
          f"{count_version_headers(text)} version header(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
