#!/usr/bin/env python3
"""Independent verification of a packaged release APK or app bundle.

Why this exists on top of the Gradle gates (`verifyReleaseApkModel`,
`verifyReleaseBundleModel`): a release pipeline should not rest on a single
implementation of its most important invariant. The Gradle gates compare the
*entry size* at the exact expected path; this script is a second, deliberately
different check that also compares the **content hash**, and it can be run by
hand against any artifact. Both implementations agree on what constitutes a
valid package: the model is accepted only at the artifact's exact packaging
path (APK `assets/builtin-model/<name>`, AAB
`model_assets/assets/builtin-model/<name>`), and any copy anywhere else in the
archive is a failure — a suffix match is not enough.

Division of labour with `checkModels` (Gradle): `checkModels` proves the source
asset on disk has the SHA-256 declared in `modelPackFiles`. This script proves
the bytes inside the published archive are identical to that source asset. Run
both: declared -> source, and source -> artifact.

Usage:
    scripts/verify_release_artifact.py --kind apk --archive app-release.apk \\
        --model-dir model_assets/src/main/assets/builtin-model
    scripts/verify_release_artifact.py --kind aab --archive app-release.aab \\
        --model-dir model_assets/src/main/assets/builtin-model

Exit codes: 0 = verified, 1 = verification failed, 2 = usage error.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import posixpath
import sys
import zlib
import zipfile

ASSET_SUBDIR = "assets/builtin-model/"
CHUNK = 1024 * 1024


def expected_entry_path(kind: str, model_name: str) -> str:
    """The one archive path where `kind` must carry the model.

    Derived from the repository's packaging logic, not from convention:

    * APK: the release source set folds `model_assets/src/main/assets/` into the
      base module's `assets/` directory (see the `if (!isBundle)` block in
      `app/build.gradle.kts`), so the entry is `assets/builtin-model/<name>`.
    * AAB: the model lives only in the `:model_assets` asset pack
      (`dynamicDelivery = "install-time"`), and a bundle stores each module's
      assets under `<module>/assets/`, so the entry is
      `model_assets/assets/builtin-model/<name>`.
    """
    if kind == "aab":
        return "model_assets/" + ASSET_SUBDIR + model_name
    return ASSET_SUBDIR + model_name


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(CHUNK)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def sha256_of_zip_entry(archive: zipfile.ZipFile, name: str) -> str:
    digest = hashlib.sha256()
    with archive.open(name) as handle:
        while True:
            block = handle.read(CHUNK)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def source_models(model_dir: str) -> list[str]:
    """Model file names present in the source asset directory (non-recursive)."""
    if not os.path.isdir(model_dir):
        return []
    return sorted(
        name
        for name in os.listdir(model_dir)
        if os.path.isfile(os.path.join(model_dir, name)) and not name.startswith(".")
    )


def verify(kind: str, archive_path: str, model_dir: str, allow_model_less: bool) -> int:
    if not os.path.isfile(archive_path):
        print(f"FAIL: {archive_path} does not exist", file=sys.stderr)
        return 1

    names = source_models(model_dir)
    if not names:
        print(
            f"FAIL: no source model in {model_dir}. The release pipeline must fetch it "
            f"first (./gradlew downloadModels), otherwise this check would be vacuous.",
            file=sys.stderr,
        )
        return 1

    expected = {name: os.path.getsize(os.path.join(model_dir, name)) for name in names}
    hashes = {name: sha256_of(os.path.join(model_dir, name)) for name in names}

    failures: list[str] = []
    total_entries = 0
    model_entries = 0

    # Handled here rather than in main(): the failure belongs to the verification,
    # so every caller (the CLI, the test suite) gets an exit code instead of a
    # traceback.
    try:
        archive = zipfile.ZipFile(archive_path)
    except (zipfile.BadZipFile, OSError) as error:
        print(f"FAIL: {archive_path} is not a readable zip: {error}", file=sys.stderr)
        return 1

    # Reading an archive *entry* can fail as well: a corrupted entry fails its
    # CRC mid-read, a truncated one raises EOFError/zlib errors. Every such
    # failure must fail the verification with a diagnostic instead of escaping
    # as an uncaught traceback (fail closed).
    try:
        with archive:
            entries = [info for info in archive.infolist() if not info.is_dir()]
            total_entries = len(entries)

            for model_name in names:
                expected_path = expected_entry_path(kind, model_name)
                # Every entry whose file name equals the model's is a copy of it,
                # wherever it sits. Basename matching is what catches a nested or
                # stray duplicate that a suffix check would silently accept.
                copies = [
                    info
                    for info in entries
                    if posixpath.basename(info.filename) == model_name
                ]
                model_entries += len(copies)
                expected_entry = next(
                    (info for info in copies if info.filename == expected_path), None
                )

                if expected_entry is None:
                    if copies:
                        # Present but misplaced: not "missing", so the model-less
                        # opt-in must not paper over it either.
                        failures.append(
                            f"{kind.upper()} places {model_name} at "
                            f"{', '.join(info.filename for info in copies)}, but the only "
                            f"valid delivery path for this artifact is {expected_path}"
                        )
                        continue
                    if allow_model_less:
                        print(
                            f"WARNING: {archive_path} does not contain {expected_path} — "
                            f"allowed by --allow-model-less. THIS ARTIFACT IS NOT "
                            f"DISTRIBUTABLE."
                        )
                        continue
                    failures.append(
                        f"{kind.upper()} does not contain {expected_path}"
                    )
                    continue

                if len(copies) > 1:
                    failures.append(
                        f"{kind.upper()} contains {model_name} {len(copies)} times "
                        f"({', '.join(info.filename for info in copies)}) — the model must "
                        f"be delivered exactly once"
                    )
                    continue

                entry = expected_entry

                if entry.file_size != expected[model_name]:
                    failures.append(
                        f"{kind.upper()} packages {model_name} at {entry.file_size} bytes but "
                        f"the source asset is {expected[model_name]} bytes (truncated or stale)"
                    )
                    continue

                found = sha256_of_zip_entry(archive, entry.filename)
                if found != hashes[model_name]:
                    failures.append(
                        f"{kind.upper()} packages a {model_name} whose SHA-256 is {found} but "
                        f"the source asset is {hashes[model_name]}"
                    )
                    continue

                print(
                    f"  {kind.upper()} {os.path.basename(archive_path)}: OK {entry.filename} "
                    f"({entry.file_size} bytes, sha256 {found[:16]}…)"
                )

            # Directory-level guard: `assets/builtin-model/` may appear only at the
            # artifact's allowed location. This also catches undeclared leftovers
            # (e.g. a stale GGUF merged into an unexpected module) that name
            # matching alone would miss.
            allowed_prefix = expected_entry_path(kind, "")
            stray = [
                info.filename
                for info in entries
                if ASSET_SUBDIR in info.filename
                and not info.filename.startswith(allowed_prefix)
            ]
            if stray:
                failures.append(
                    f"{kind.upper()} contains the bundled-model directory outside "
                    f"{allowed_prefix} ({', '.join(stray)}) — the model must be delivered "
                    f"only at {allowed_prefix}<model>"
                )
    except (
        zipfile.BadZipFile,
        zipfile.LargeZipFile,
        OSError,
        EOFError,
        ValueError,
        RuntimeError,
        zlib.error,
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

    if model_entries == 0:
        print(
            f"WARNING: {archive_path} carries no bundled model (allowed-model-less). "
            f"Scanned {total_entries} entries; this artifact is NOT distributable."
        )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("apk", "aab"), required=True)
    parser.add_argument("--archive", required=True)
    parser.add_argument(
        "--model-dir",
        default="model_assets/src/main/assets/builtin-model",
        help="directory holding the verified source model asset(s)",
    )
    parser.add_argument(
        "--allow-model-less",
        action="store_true",
        help="downgrade a missing model to a warning (local escape-hatch runs only)",
    )
    args = parser.parse_args()

    return verify(args.kind, args.archive, args.model_dir, args.allow_model_less)


if __name__ == "__main__":
    sys.exit(main())
