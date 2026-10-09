#!/usr/bin/env python3
"""Independent verification of a packaged release APK or app bundle.

Why this exists on top of the Gradle gates (`verifyReleaseApkModel`,
`verifyReleaseBundleModel`): a release pipeline should not rest on a single
implementation of its most important invariant. The Gradle gates compare the
*entry size* and match the asset path by suffix; this script is a second,
deliberately different check that also compares the **content hash** and the
**delivery location**, and it can be run by hand against any artifact.

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
import sys
import zipfile

ASSET_SUBDIR = "assets/builtin-model/"
CHUNK = 1024 * 1024


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

    with archive:
        entries = [info for info in archive.infolist() if not info.is_dir()]
        total_entries = len(entries)

        for model_name in names:
            suffix = ASSET_SUBDIR + model_name
            matches = [info for info in entries if info.filename.endswith(suffix)]
            model_entries += len(matches)

            if not matches:
                if allow_model_less:
                    print(
                        f"WARNING: {archive_path} does not contain {suffix} — allowed by "
                        f"--allow-model-less. THIS ARTIFACT IS NOT DISTRIBUTABLE."
                    )
                    continue
                failures.append(f"{kind.upper()} does not contain {suffix}")
                continue

            if len(matches) > 1:
                failures.append(
                    f"{kind.upper()} contains {model_name} {len(matches)} times "
                    f"({', '.join(info.filename for info in matches)}) — the model must be "
                    f"delivered exactly once"
                )
                continue

            entry = matches[0]

            # An AAB must deliver the model through the :model_assets asset pack, and
            # a copy in the base module as well would ship ~750 MB to the user twice.
            if kind == "aab" and not entry.filename.startswith("model_assets/"):
                failures.append(
                    f"AAB packages {model_name} at {entry.filename}, which is not inside the "
                    f":model_assets asset pack"
                )
                continue

            if entry.file_size != expected[model_name]:
                failures.append(
                    f"{kind.upper()} packages {model_name} at {entry.file_size} bytes but the "
                    f"source asset is {expected[model_name]} bytes (truncated or stale)"
                )
                continue

            found = sha256_of_zip_entry(archive, entry.filename)
            if found != hashes[model_name]:
                failures.append(
                    f"{kind.upper()} packages a {model_name} whose SHA-256 is {found} but the "
                    f"source asset is {hashes[model_name]}"
                )
                continue

            print(
                f"  {kind.upper()} {os.path.basename(archive_path)}: OK {entry.filename} "
                f"({entry.file_size} bytes, sha256 {found[:16]}…)"
            )

        # Duplicate-delivery guard that does not depend on which module the copy
        # landed in: an AAB must contain the model in the asset pack and nowhere
        # else, so any second copy under base/ is a packaging mistake.
        if kind == "aab":
            stray = [
                info.filename
                for info in entries
                if info.filename.startswith("base/") and ASSET_SUBDIR in info.filename
            ]
            if stray:
                failures.append(
                    "AAB base module also contains the bundled model "
                    f"({', '.join(stray)}); it must be delivered only by :model_assets"
                )

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
