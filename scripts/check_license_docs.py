#!/usr/bin/env python3
"""Check license metadata and distributable notice copies for Aura Transcribe.

Policy encoded here (see ``LICENSE_AUDIT.md``):

* the application source code is **MIT** (root ``LICENSE``), unchanged from
  upstream, and every publication surface says so;
* the bundled Nemotron GGUF is **OpenMDW-1.1**, never described as MIT, and its
  agreement plus the upstream ``NOTICE`` travel with the model;
* the GGUF download is pinned to the verified immutable Hugging Face revision
  and to the reviewed SHA-256 rather than ``resolve/main``;
* no CC-BY-4.0 claim survives anywhere (the model was never CC-BY-4.0);
* no audio fixture is distributed.

The gate is fail-closed: a missing file, a stale license declaration, a
contradictory model notice or a drifting asset copy aborts with exit status 1.
It only inspects static repository data; the resolved dependency inventories are
produced separately by ``scripts/report_cargo_licenses.py`` and the Gradle
license report.

Passing this gate proves that the documents are present, consistent and pinned.
It is not a legal determination and does not certify compatibility.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# Verified 2026-10-19 against the Hugging Face API: the repository head is this
# commit and the Q5_K_M LFS object id equals MODEL_SHA256. Pinning the URL to a
# commit means a later upstream push cannot silently change what this build
# downloads; the SHA-256 check then proves the bytes.
PINNED_GGUF_REVISION = "53ce288f69326b415aeef030cfcb02a08a8096dd"
MODEL_SHA256 = "86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65"
MODEL_REPO = "handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf"
# The OpenMDW-1.1 agreement exactly as published in the pinned GGUF repository
# (`LICENSE`, 2619 bytes). Pinning the digest means a well-meaning "cleanup"
# of this file cannot silently ship a modified agreement.
OPENMDW_TEXT_SHA256 = "2ab44b68365473c112f5092211a38f231cb23e50de68b75a13369adbd76a74df"

READMES = [
    "README.md", "README_es.md", "README_de.md",
    "README_fr.md", "README_it.md", "README_zh.md",
]
LICENSE_TEXTS = [
    "UPSTREAM-MIT.txt", "transcribe-cpp-MIT.txt",
    "ggml-MIT.txt", "Apache-2.0.txt",
    "LLVM-exception.txt", "libc++-NOTICE.txt",
    "OpenMDW-1.1.txt",
]
MIT_BADGE = (
    "[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)"
)
# Phrases that must never describe the application code or the model again.
FORBIDDEN_CLAIMS = (
    "GPL-3.0",
    "GPLv3",
    "GNU General Public License",
    "CC-BY 4.0",
    "CC BY 4.0",
    "CC-BY-4.0",
    "creativecommons.org/licenses/by/4.0",
)


class LicenseError(SystemExit):
    pass


def fail(message: str) -> None:
    print(f"LICENSE CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def normalize(text: str) -> str:
    """Collapse whitespace so phrase checks survive re-wrapping."""
    return re.sub(r"\s+", " ", text)


def read(root: Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_text(encoding="utf-8")


def read_bytes(root: Path, rel: str) -> bytes:
    path = root / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_bytes()


def same_file(root: Path, first: str, second: str) -> None:
    if read_bytes(root, first) != read_bytes(root, second):
        fail(f"license/notices copy differs: {first} != {second}")


def cargo_license(root: Path, rel: str) -> str:
    match = re.search(r'(?m)^license\s*=\s*"([^"]+)"\s*$', read(root, rel))
    if not match:
        fail(f"{rel} does not declare a license expression")
    return match.group(1)


def require_phrase(where: str, haystack: str, phrase: str) -> None:
    if phrase not in haystack:
        fail(f"{where} is missing {phrase!r}")


def reject_forbidden(where: str, text: str, extra: tuple[str, ...] = ()) -> None:
    flat = normalize(text)
    for claim in FORBIDDEN_CLAIMS + extra:
        if claim in flat:
            fail(f"{where} still declares {claim!r}")


def check_application_license(root: Path) -> None:
    license_text = read(root, "LICENSE")
    require_phrase("LICENSE", license_text, "MIT License")
    require_phrase("LICENSE", license_text, "Copyright (c) 2025 Noah Mühl")
    require_phrase("LICENSE", license_text, "Permission is hereby granted, free of charge")
    require_phrase("LICENSE", license_text, 'THE SOFTWARE IS PROVIDED "AS IS"')
    reject_forbidden("LICENSE", license_text)

    for rel in READMES:
        content = read(root, rel)
        require_phrase(rel, content, MIT_BADGE)
        reject_forbidden(rel, content)
        for link in ("THIRD_PARTY_NOTICES.md", "MODEL_LICENSE.md"):
            require_phrase(rel, content, link)

    if cargo_license(root, "Cargo.toml") != "MIT":
        fail('Cargo.toml must declare license = "MIT"')
    if cargo_license(root, "crates/aura-core/Cargo.toml") != "MIT":
        fail('crates/aura-core/Cargo.toml must declare license = "MIT"')

    about = read(root, "ABOUT.md")
    require_phrase("ABOUT.md", about, "MIT License")
    reject_forbidden("ABOUT.md", about)

    store = read(root, "fastlane/metadata/android/en-US/full_description.txt")
    require_phrase("the English store description", store, "MIT License")
    reject_forbidden("store description", store)

    fastfile = read(root, "fastlane/Fastfile")
    require_phrase("fastlane/Fastfile", fastfile, "License: MIT")
    require_phrase(
        "fastlane/Fastfile", fastfile, "github.com/marodriguezd/Aura-Transcribe"
    )
    if "github.com/marodriguezd/android_transcribe_app" in fastfile:
        fail("Fastlane still points at the unrelated/old repository URL")
    reject_forbidden("fastlane/Fastfile", fastfile)


def check_model_documents(root: Path) -> None:
    model_license = read(root, "MODEL_LICENSE.md")
    require_phrase("MODEL_LICENSE.md", model_license, "OpenMDW-1.1")
    require_phrase("MODEL_LICENSE.md", model_license, "Model Materials")
    require_phrase(
        "MODEL_LICENSE.md",
        normalize(model_license),
        "If you distribute any portion of the Model Materials",
    )
    require_phrase("MODEL_LICENSE.md", model_license, MODEL_SHA256)
    require_phrase("MODEL_LICENSE.md", model_license, PINNED_GGUF_REVISION)
    # The model must never be presented as MIT, and the gate must catch a
    # regression to the CC-BY-4.0 claim this repository used to publish.
    require_phrase(
        "MODEL_LICENSE.md",
        normalize(model_license),
        "The model weights are not MIT-licensed",
    )
    reject_forbidden("MODEL_LICENSE.md", model_license)

    notice = normalize(read(root, "MODEL_NOTICE.md"))
    require_phrase(
        "MODEL_NOTICE.md", notice, "Modifications: handy-computer converted"
    )
    require_phrase("MODEL_NOTICE.md", notice, "24b151a")
    require_phrase("MODEL_NOTICE.md", notice, "not retrained or fine-tuned")
    require_phrase("MODEL_NOTICE.md", notice, PINNED_GGUF_REVISION)

    notices = read(root, "THIRD_PARTY_NOTICES.md")
    flat = normalize(notices)
    for phrase in ("OpenMDW-1.1", "MODEL_NOTICE.md", "separately licensed data asset"):
        require_phrase("THIRD_PARTY_NOTICES.md", flat, phrase)
    for name in LICENSE_TEXTS:
        require_phrase("THIRD_PARTY_NOTICES.md", notices, name)
    reject_forbidden("THIRD_PARTY_NOTICES.md", notices, extra=("bench.wav", "FLEURS"))

    # The agreement must be the upstream file, not a retyped paraphrase.
    agreement = read_bytes(root, "licenses/OpenMDW-1.1.txt")
    actual = hashlib.sha256(agreement).hexdigest()
    if actual != OPENMDW_TEXT_SHA256:
        fail(
            "licenses/OpenMDW-1.1.txt is not the verbatim upstream agreement: "
            f"sha256 {actual} != {OPENMDW_TEXT_SHA256}"
        )
    agreement_text = agreement.decode("utf-8")
    require_phrase(
        "licenses/OpenMDW-1.1.txt",
        agreement_text,
        "OpenMDW License Agreement, version 1.1 (OpenMDW-1.1)",
    )

    upstream_mit = read(root, "licenses/UPSTREAM-MIT.txt")
    require_phrase("licenses/UPSTREAM-MIT.txt", upstream_mit, "Copyright (c) 2025 Noah Mühl")
    require_phrase("licenses/UPSTREAM-MIT.txt", upstream_mit, "Permission is hereby granted")

    llvm_exception = read(root, "licenses/LLVM-exception.txt")
    for phrase in (
        "Apache License",
        "Apache License v2.0 with LLVM Exceptions",
        "LLVM Exceptions to the Apache 2.0 License",
        "Version 2.0, January 2004",
    ):
        require_phrase("licenses/LLVM-exception.txt", llvm_exception, phrase)

    libcxx_notice = read(root, "licenses/libc++-NOTICE.txt")
    for phrase in (
        "The libc++ library is dual licensed",
        "University of Illinois/NCSA",
        "Copyright (c) 2009-2019 by the contributors listed in CREDITS.TXT",
        "Permission is hereby granted",
    ):
        require_phrase("licenses/libc++-NOTICE.txt", libcxx_notice, phrase)


def check_packaged_copies(root: Path) -> None:
    same_file(root, "LICENSE", "app/src/main/assets/LICENSE")
    same_file(root, "THIRD_PARTY_NOTICES.md", "app/src/main/assets/THIRD_PARTY_NOTICES.md")
    for name in LICENSE_TEXTS:
        same_file(root, f"licenses/{name}", f"app/src/main/assets/licenses/{name}")
    same_file(root, "MODEL_LICENSE.md", "model_assets/src/main/assets/MODEL_LICENSE.md")
    same_file(root, "MODEL_NOTICE.md", "model_assets/src/main/assets/MODEL_NOTICE.md")


def check_model_pin(root: Path) -> None:
    """The bundled model must come from an immutable revision, by hash."""
    gradle = read(root, "app/build.gradle.kts")
    # Report the unpinned URL before the missing pin: the actionable defect is
    # "follows a moving branch", not "the constant changed".
    if f"{MODEL_REPO}/resolve/main" in gradle:
        fail(
            "app/build.gradle.kts still downloads the bundled model from "
            "resolve/main; pin it to a verified commit"
        )
    require_phrase("app/build.gradle.kts", gradle, MODEL_SHA256)
    require_phrase("app/build.gradle.kts", gradle, "/resolve/" + PINNED_GGUF_REVISION)

    main_activity = read(root, "app/src/main/java/dev/notune/transcribe/MainActivity.java")
    if f"{MODEL_REPO}/resolve/main" in main_activity:
        fail(
            "MainActivity.java still downloads the bundled model from resolve/main; "
            "pin it to a verified commit"
        )
    require_phrase(
        "MainActivity.java", main_activity, "/resolve/" + PINNED_GGUF_REVISION
    )

    models_json = read(root, "benchmark/manifests/models.json")
    if f"{MODEL_REPO}/resolve/main/" in models_json:
        fail("benchmark/manifests/models.json still uses resolve/main for the model")


def check_absent_audio(root: Path) -> None:
    assets = root / "app/src/main/assets"
    if (assets / "bench.wav").exists():
        fail("app/src/main/assets/bench.wav must not be distributed")
    for pattern in ("*.wav", "*.mp3", "*.ogg", "*.m4a", "*.flac"):
        found = sorted(p.name for p in assets.rglob(pattern)) if assets.is_dir() else []
        if found:
            fail(f"audio fixture(s) must not be bundled in app assets: {found}")

    gradle = read(root, "app/build.gradle.kts")
    if "testImplementation(libs.json)" not in gradle:
        fail("org.json test dependency declaration changed; re-audit its license")
    if re.search(r"(?m)^\s*implementation\(libs\.json\)", gradle):
        fail("org.json must not be moved into the shipped runtime without re-audit")
    require_phrase(
        "app/build.gradle.kts", gradle, "com.github.jk1.dependency-license-report"
    )
    require_phrase("app/build.gradle.kts", gradle, "releaseRuntimeClasspath")


def check_benchmark_manifest(root: Path) -> None:
    path = root / "benchmark/manifests/corpus.json"
    if not path.is_file():
        fail("required file is missing: benchmark/manifests/corpus.json")
    try:
        corpus = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f"benchmark/manifests/corpus.json is not valid JSON: {exc}")
    if not isinstance(corpus, dict):
        fail("benchmark/manifests/corpus.json must contain a JSON object")

    # The synthetic corpus is generated by this repository's own script, so it
    # carries the application license. A copyleft or CC label here was the
    # leftover of the withdrawn GPL migration.
    declared = corpus.get("license")
    if declared != "MIT":
        fail(
            "benchmark/manifests/corpus.json must declare the project's MIT "
            f"license for its own generated corpus, found {declared!r}"
        )

    lowered = json.dumps(corpus, ensure_ascii=False).lower()
    for phrase in (
        "cc-by-4.0",
        "cc by 4.0",
        "creativecommons.org/licenses/by/4.0",
    ):
        if phrase in lowered:
            fail(f"benchmark/manifests/corpus.json still declares {phrase!r}")
    metadata = json.dumps(
        {k: v for k, v in corpus.items() if k != "clips"}, ensure_ascii=False
    ).lower()
    if "fleurs" in metadata:
        fail("benchmark/manifests/corpus.json still declares the FLEURS dataset")


def run(root: Path) -> None:
    check_application_license(root)
    check_model_documents(root)
    check_packaged_copies(root)
    check_model_pin(root)
    check_absent_audio(root)
    check_benchmark_manifest(root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: the root that contains this script)",
    )
    args = parser.parse_args(argv)

    run(args.root.resolve())
    print("License metadata and packaged notice copies are consistent.")
    print("Scope: MIT application code + OpenMDW-1.1 model, both documented.")
    print(
        "Reminder: this check does not replace a complete resolved-dependency "
        "SBOM/license scan or a legal review."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
