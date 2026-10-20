#!/usr/bin/env python3
"""Verify that a packaged APK or AAB carries the complete license & notice set.

Why this exists (2026-10 licensing audit): the published v1.0.0 artifacts
(Aura_Transcribe_v1.0.0.apk/.aab) shipped the bundled GGUF and its
``MODEL_LICENSE.md`` but **no** ``MODEL_NOTICE.md``, no MIT ``LICENSE``, no
``THIRD_PARTY_NOTICES.md`` and none of the ``licenses/`` texts. The
model-focused gates (``verify_release_artifact.py`` and the Gradle
``verifyRelease*Model`` gates) would not have caught that: the model was there.
This script is the notice-set counterpart — it opens the produced archive and
requires every license/notice document at its exact delivery path, byte-identical
to the repository source.

Delivery paths:

* APK: base-module assets, i.e. ``assets/…`` (release builds fold
  ``model_assets/src/main/assets/`` into the same root, so the model documents
  are ``assets/MODEL_LICENSE.md`` and ``assets/MODEL_NOTICE.md``).
* AAB: the base module's assets live under ``base/assets/…`` and the
  ``:model_assets`` asset pack under ``model_assets/assets/…``.

Required set:

* always — ``LICENSE`` (MIT), ``THIRD_PARTY_NOTICES.md`` and every text in
  ``licenses/`` (the verbatim upstream agreements and notices);
* with ``--require-model-docs`` (release artifacts) — ``MODEL_LICENSE.md`` and
  ``MODEL_NOTICE.md`` next to the model at the artifact's model-documents path.

Debug APKs are checked without ``--require-model-docs``: they deliberately ship
without the bundled model, so no Model Materials are distributed with them and
the OpenMDW-1.1 retention duties attach to the release artifacts instead.

Exit codes: 0 = verified, 1 = verification failed, 2 = usage error.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import posixpath
import sys
import zipfile

CHUNK = 1024 * 1024

# The full set of third-party license texts that must travel with the app.
# Mirrors scripts/check_license_docs.py; kept explicit so deleting a text from
# BOTH the repository and the artifact is still a visible, reviewed change.
LICENSE_TEXTS = (
    "UPSTREAM-MIT.txt",
    "transcribe-cpp-MIT.txt",
    "ggml-MIT.txt",
    "miniz-MIT.txt",
    "Apache-2.0.txt",
    "okhttp-NOTICE.txt",
    "LLVM-exception.txt",
    "libc++-NOTICE.txt",
    "OpenMDW-1.1.txt",
)

APP_DOCUMENTS = ("LICENSE", "THIRD_PARTY_NOTICES.md")
MODEL_DOCUMENTS = ("MODEL_LICENSE.md", "MODEL_NOTICE.md")


class UsageError(SystemExit):
    pass


def fail(message: str) -> None:
    print(f"NOTICE CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_bytes(root: str, rel: str) -> bytes:
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        fail(
            f"required source document is missing: {rel}. The gate cannot verify an "
            "archive without its source of truth."
        )
    with open(path, "rb") as handle:
        return handle.read()


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def expected_entries(kind: str, root: str, require_model_docs: bool) -> list[tuple[str, str]]:
    """`(archive path, source path)` pairs the artifact must carry."""
    if kind == "apk":
        app_prefix = "assets/"
        model_prefix = "assets/"
    else:
        app_prefix = "base/assets/"
        model_prefix = "model_assets/assets/"

    pairs: list[tuple[str, str]] = []
    for rel in APP_DOCUMENTS:
        pairs.append((app_prefix + rel, rel))
    for name in LICENSE_TEXTS:
        pairs.append((app_prefix + "licenses/" + name, "licenses/" + name))
    if require_model_docs:
        for rel in MODEL_DOCUMENTS:
            pairs.append((model_prefix + rel, rel))
    return pairs


def check_license_dir_is_fully_listed(root: str) -> None:
    """The explicit LICENSE_TEXTS list must cover licenses/ exactly (drift guard)."""
    directory = os.path.join(root, "licenses")
    if not os.path.isdir(directory):
        fail("licenses/ directory is missing from the repository root")
    present = sorted(
        name
        for name in os.listdir(directory)
        if os.path.isfile(os.path.join(directory, name)) and not name.startswith(".")
    )
    missing = [name for name in present if name not in LICENSE_TEXTS]
    if missing:
        fail(
            f"licenses/ contains {missing} which the verifier does not require; add "
            "them to LICENSE_TEXTS (and THIRD_PARTY_NOTICES.md) in the same change"
        )
    absent = [name for name in LICENSE_TEXTS if name not in present]
    if absent:
        fail(f"LICENSE_TEXTS lists {absent} but licenses/ does not contain them")


def verify(kind: str, archive_path: str, root: str, require_model_docs: bool) -> int:
    if kind not in ("apk", "aab"):
        fail(f"unknown artifact kind {kind!r}")
    if not os.path.isfile(archive_path):
        print(f"FAIL: {archive_path} does not exist", file=sys.stderr)
        return 1

    check_license_dir_is_fully_listed(root)
    expected = expected_entries(kind, root, require_model_docs)

    try:
        archive = zipfile.ZipFile(archive_path)
    except (zipfile.BadZipFile, OSError) as error:
        print(f"FAIL: {archive_path} is not a readable zip: {error}", file=sys.stderr)
        return 1

    failures: list[str] = []
    try:
        with archive:
            entries = [info for info in archive.infolist() if not info.is_dir()]
            names = {info.filename for info in entries}
            for entry_path, source_rel in expected:
                source = read_bytes(root, source_rel)
                if entry_path not in names:
                    # Same basename elsewhere in the archive is a misplaced copy,
                    # which is a delivery failure, not an absent document.
                    strays = [
                        info.filename
                        for info in entries
                        if posixpath.basename(info.filename) == posixpath.basename(entry_path)
                    ]
                    if strays:
                        failures.append(
                            f"{kind.upper()} places {source_rel} at "
                            f"{', '.join(strays)}, but the only valid delivery path is "
                            f"{entry_path}"
                        )
                    else:
                        failures.append(f"{kind.upper()} does not contain {entry_path}")
                    continue

                info = archive.getinfo(entry_path)
                if info.file_size != len(source):
                    failures.append(
                        f"{kind.upper()} packages {entry_path} at {info.file_size} bytes "
                        f"but the source document is {len(source)} bytes (truncated or stale)"
                    )
                    continue
                try:
                    packaged = archive.read(entry_path)
                except (
                    zipfile.BadZipFile,
                    zipfile.LargeZipFile,
                    OSError,
                    EOFError,
                    ValueError,
                    RuntimeError,
                ) as error:
                    # Fail closed with a diagnosis: a corrupt entry must not
                    # escape as an uncaught traceback or pass silently.
                    failures.append(
                        f"{kind.upper()} entry {entry_path} could not be read to "
                        f"completion: {type(error).__name__}: {error}"
                    )
                    continue
                if sha256_of(packaged) != sha256_of(source):
                    failures.append(
                        f"{kind.upper()} packages {entry_path} with content that is not "
                        f"byte-identical to {source_rel} (stale or edited copy)"
                    )
                    continue
                print(
                    f"  {kind.upper()} {os.path.basename(archive_path)}: OK {entry_path} "
                    f"({len(packaged)} bytes, exact copy of {source_rel})"
                )
    except (
        zipfile.BadZipFile,
        zipfile.LargeZipFile,
        OSError,
        EOFError,
        ValueError,
        RuntimeError,
    ) as error:
        print(
            f"FAIL: {archive_path} could not be read to completion: "
            f"{type(error).__name__}: {error}",
            file=sys.stderr,
        )
        return 1

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--kind", choices=("apk", "aab"), required=True)
    parser.add_argument("--archive", required=True)
    parser.add_argument(
        "--root",
        default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        help="repository root holding LICENSE, THIRD_PARTY_NOTICES.md, licenses/",
    )
    parser.add_argument(
        "--require-model-docs",
        action="store_true",
        help=(
            "also require MODEL_LICENSE.md and MODEL_NOTICE.md next to the model "
            "(release artifacts; a debug APK ships without Model Materials)"
        ),
    )
    args = parser.parse_args()
    return verify(args.kind, args.archive, args.root, args.require_model_docs)


if __name__ == "__main__":
    sys.exit(main())
