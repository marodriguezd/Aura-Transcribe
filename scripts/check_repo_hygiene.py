#!/usr/bin/env python3
"""Repository hygiene gate (2026-10 corrective audit, Phase 4).

Prevents recurrence of the confirmed hygiene defect: transient agent/session
state (``.opencode/``, ``.omg/``, …) was tracked even though ``.gitignore``
declares those directories local-only — ``.gitignore`` does not untrack
already-tracked files, so an explicit gate over ``git ls-files`` is required.

Checks (all cheap, no network, no SDK):
  1. No tracked file under a forbidden local-only path (agent context dirs,
     build outputs, downloaded artifacts, ``local.properties``, the release
     keystore, GGUF models, packaged binaries).
  2. Keystore policy: only ``keystore/debug.keystore`` may be tracked. Any
     other *.keystore/*.jks/*.p12/*.pem tracked file fails the gate.
  3. Oversized tracked files: any tracked file above the size cap fails
     (the legitimate largest asset is < 1 MiB; the cap is 2 MiB).
  4. High-signal secret patterns (GitHub/AWS/Slack/OpenAI/GitLab tokens,
     private key blocks) anywhere in tracked text files. A line carrying the
     inline marker ``hygiene-ignore:fake-token`` is skipped — the sanctioned
     escape hatch for test fixtures that must contain a token-shaped literal.

Exit status: 0 when clean, 1 on any violation.
"""

import argparse
import os
import re
import subprocess
import sys

# Directories/paths that must never be tracked.
FORBIDDEN_PREFIXES = (
    ".opencode/",
    ".omg/",
    ".ai_context/",
    ".agents/",
    ".freebuff/",
    ".pytest_cache/",
    ".gradle/",
    "build/",
    "target/",
    "app/build/",
    "app/src/main/jniLibs/",
    "model_assets/src/main/assets/builtin-model/",
)

FORBIDDEN_EXACT = (
    "local.properties",
    "release.keystore",
    ".DS_Store",
)

FORBIDDEN_SUFFIXES = (
    ".gguf",
    ".apk",
    ".aab",
    ".idsig",
    ".jks",
    ".p12",
)

# Keystore policy: exactly one tracked keystore is legitimate (debug key,
# versioned on purpose — the release workflow compares against it).
ALLOWED_KEYSTORES = frozenset({"keystore/debug.keystore"})
KEYSTORE_SUFFIXES = (".keystore", ".jks", ".p12", ".pem")

SIZE_CAP_BYTES = 2 * 1024 * 1024

SECRET_PATTERNS = (
    re.compile(r"ghp_[A-Za-z0-9]{30,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{30,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"sk-[A-Za-z0-9]{32,}"),
    re.compile(r"xox[baprs]-"),
    re.compile(r"glpat-[A-Za-z0-9_-]{20,}"),
    re.compile(r"-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)

SUPPRESSION_MARKER = "hygiene-ignore:fake-token"


def git_ls_files(repo_root):
    """Returns the repository's tracked files (relative paths)."""
    output = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=repo_root,
        capture_output=True,
        check=True,
    ).stdout
    return [p.decode("utf-8", "surrogateescape") for p in output.split(b"\0") if p]


FORBIDDEN_COMPONENTS = frozenset({
    ".opencode",
    ".omg",
    ".ai_context",
    ".agents",
    ".freebuff",
    ".pytest_cache",
    ".gradle",
    "target",
    "jniLibs",
    "__pycache__",
})


def is_forbidden_path(path):
    """Returns a human-readable violation reason, or None when allowed."""
    normalized = path.replace("\\", "/")
    if normalized in FORBIDDEN_EXACT:
        return "matches a forbidden exact path"
    for prefix in FORBIDDEN_PREFIXES:
        if normalized == prefix.rstrip("/") or normalized.startswith(prefix):
            return "is under the forbidden local-only path '%s'" % prefix
    # Component matching catches nested copies (e.g. crates/aura-core/target/…).
    for component in normalized.split("/")[:-1]:
        if component in FORBIDDEN_COMPONENTS:
            return "contains the forbidden path component '%s'" % component
    for suffix in FORBIDDEN_SUFFIXES:
        if normalized.endswith(suffix):
            return "has the forbidden suffix '%s'" % suffix
    return None


def is_keystore_violation(path):
    """True when a tracked keystore-like file is not the allowed debug keystore."""
    normalized = path.replace("\\", "/")
    if not normalized.endswith(KEYSTORE_SUFFIXES):
        return False
    return normalized not in ALLOWED_KEYSTORES


def find_secret(text):
    """Returns the first secret-pattern description found in text, or None."""
    for lineno, line in enumerate(text.splitlines(), start=1):
        if SUPPRESSION_MARKER in line:
            continue
        for pattern in SECRET_PATTERNS:
            if pattern.search(line):
                return "line %d matches %s" % (lineno, pattern.pattern)
    return None


def is_binary(path):
    """Best-effort binary detection: NUL byte in the first 8 KiB."""
    try:
        with open(path, "rb") as handle:
            return b"\0" in handle.read(8192)
    except OSError:
        return True  # unreadable: treat as binary (skipped by caller anyway)


def run_checks(repo_root):
    """Runs every hygiene check; returns a list of violation strings."""
    violations = []
    for path in git_ls_files(repo_root):
        full = os.path.join(repo_root, path)

        reason = is_forbidden_path(path)
        if reason:
            violations.append("tracked file '%s' %s" % (path, reason))
            continue

        if is_keystore_violation(path):
            violations.append(
                "tracked keystore '%s' violates the keystore policy (only "
                "keystore/debug.keystore may be versioned)" % path
            )
            continue

        if os.path.isfile(full):
            size = os.path.getsize(full)
            if size > SIZE_CAP_BYTES:
                violations.append(
                    "tracked file '%s' is %d bytes, over the %d-byte cap"
                    % (path, size, SIZE_CAP_BYTES)
                )
                continue

            if not is_binary(full):
                try:
                    with open(full, "r", encoding="utf-8", errors="replace") as handle:
                        secret = find_secret(handle.read())
                except OSError as exc:
                    violations.append("cannot read tracked file '%s': %s" % (path, exc))
                    continue
                if secret:
                    violations.append(
                        "tracked file '%s' %s (possible committed secret)" % (path, secret)
                    )
    return violations


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--repo-root",
        default=os.getcwd(),
        help="repository root (default: current directory)",
    )
    args = parser.parse_args(argv)

    violations = run_checks(args.repo_root)
    if violations:
        for violation in violations:
            print("VIOLATION: %s" % violation)
        print(
            "::error::repository hygiene gate FAILED with %d violation(s). "
            "Untrack local-only files with 'git rm --cached <path>'; never "
            "commit agent session state, build outputs, models or keystores."
            % len(violations)
        )
        return 1
    print("OK: repository hygiene checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
