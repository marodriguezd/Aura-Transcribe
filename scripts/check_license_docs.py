#!/usr/bin/env python3
"""Check license metadata and distributable notice copies for Aura Transcribe.

The gate is fail-closed: a missing file, a stale license declaration, a
contradictory corpus/model notice or a drifting asset copy aborts with exit
status 1. It only inspects static repository data; the resolved dependency
inventories are produced separately by ``scripts/report_cargo_licenses.py`` and
the Gradle license report.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
READMES = [
    "README.md", "README_es.md", "README_de.md",
    "README_fr.md", "README_it.md", "README_zh.md",
]
LICENSES = [
    "UPSTREAM-MIT.txt", "transcribe-cpp-MIT.txt",
    "ggml-MIT.txt", "Apache-2.0.txt",
    "LLVM-exception.txt", "libc++-NOTICE.txt",
]
# The root crate carries upstream-derived MIT code and fork-authored
# GPL-3.0-only code, so it must declare the combined expression. The
# standalone aura-core crate must stay GPL-3.0-only.
ROOT_CARGO_LICENSE = "MIT AND GPL-3.0-only"
CORE_CARGO_LICENSE = "GPL-3.0-only"


def fail(message: str) -> None:
    print(f"LICENSE CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_text(encoding="utf-8")


def read_bytes(rel: str) -> bytes:
    path = ROOT / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_bytes()


def same_file(first: str, second: str) -> None:
    if read_bytes(first) != read_bytes(second):
        fail(f"license/notices copy differs: {first} != {second}")


def cargo_license(rel: str) -> str:
    match = re.search(r'(?m)^license\s*=\s*"([^"]+)"\s*$', read(rel))
    if not match:
        fail(f"{rel} does not declare a license expression")
    return match.group(1)


def reject_mit_application_declaration(rel: str, content: str) -> None:
    """Fail when MIT is claimed for the application rather than upstream code."""
    for sentence in re.split(r"(?<=[.!?])\s+|\n+", content):
        if not re.search(r"\bMIT\b", sentence):
            continue
        if not re.search(r"(?i)\b(upstream|third[- ]party|inherited)\b", sentence):
            fail(f"{rel} still declares the application under MIT")


gpl = read("LICENSE")
if "GNU GENERAL PUBLIC LICENSE" not in gpl or "Version 3, 29 June 2007" not in gpl:
    fail("root LICENSE is not the full GNU GPL version 3 text")
for phrase in (
    "0. Definitions.",
    "5. Conveying Modified Source Versions.",
    "How to Apply These Terms to Your New Programs",
    "END OF TERMS AND CONDITIONS",
):
    if phrase not in gpl:
        fail(f"root GPL text is incomplete (missing {phrase!r})")

for rel in READMES:
    content = read(rel)
    if "[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)" not in content:
        fail(f"{rel} has a stale/missing license badge")
    if "THIRD_PARTY_NOTICES.md" not in content or "MODEL_LICENSE.md" not in content:
        fail(f"{rel} does not link the separate license notices")
    if "CC-BY 4.0" in content:
        fail(f"{rel} still misidentifies the Nemotron model license")

if cargo_license("Cargo.toml") != ROOT_CARGO_LICENSE:
    fail('Cargo.toml must declare license = "MIT AND GPL-3.0-only"')
if cargo_license("crates/aura-core/Cargo.toml") != CORE_CARGO_LICENSE:
    fail("crates/aura-core/Cargo.toml must declare GPL-3.0-only")

about = read("ABOUT.md")
if "GPL-3.0-only" not in about:
    fail("ABOUT.md does not describe the source-code license as GPL-3.0-only")
reject_mit_application_declaration("ABOUT.md", about)

store_description = read("fastlane/metadata/android/en-US/full_description.txt")
if "GPL-3.0-only" not in store_description:
    fail("the English store description does not declare GPL-3.0-only")
reject_mit_application_declaration(
    "fastlane/metadata/android/en-US/full_description.txt", store_description
)

fastfile = read("fastlane/Fastfile")
if "License: GPL-3.0-only" not in fastfile:
    fail("Fastlane F-Droid metadata is not synchronized")
if "github.com/marodriguezd/android_transcribe_app" in fastfile:
    fail("Fastlane still points at the unrelated/old repository URL")

if (ROOT / "app/src/main/assets/bench.wav").exists():
    fail("app/src/main/assets/bench.wav must not be distributed")

same_file("LICENSE", "app/src/main/assets/LICENSE")
same_file("THIRD_PARTY_NOTICES.md", "app/src/main/assets/THIRD_PARTY_NOTICES.md")
for rel in LICENSES:
    same_file(f"licenses/{rel}", f"app/src/main/assets/licenses/{rel}")
same_file("MODEL_LICENSE.md", "model_assets/src/main/assets/MODEL_LICENSE.md")
same_file("MODEL_NOTICE.md", "model_assets/src/main/assets/MODEL_NOTICE.md")

model_license = read("MODEL_LICENSE.md")
if "OpenMDW-1.1" not in model_license or "[MIT License](LICENSE)" in model_license:
    fail("MODEL_LICENSE.md has stale application license scope")
if "Modifications: handy-computer converted" not in model_license:
    fail(
        "MODEL_LICENSE.md is missing the exact model NOTICE "
        "'Modifications: handy-computer converted'"
    )
model_notice = read("MODEL_NOTICE.md")
if "Modifications: handy-computer converted" not in model_notice:
    fail(
        "MODEL_NOTICE.md is missing the exact model NOTICE "
        "'Modifications: handy-computer converted'"
    )

llvm_exception = read("licenses/LLVM-exception.txt")
for phrase in (
    "Apache License",
    "Apache License v2.0 with LLVM Exceptions",
    "LLVM Exceptions to the Apache 2.0 License",
    "Version 2.0, January 2004",
):
    if phrase not in llvm_exception:
        fail(f"licenses/LLVM-exception.txt is incomplete (missing {phrase!r})")

libcxx_notice = read("licenses/libc++-NOTICE.txt")
for phrase in (
    "The libc++ library is dual licensed",
    "University of Illinois/NCSA",
    "Copyright (c) 2009-2019 by the contributors listed in CREDITS.TXT",
    "Permission is hereby granted",
):
    if phrase not in libcxx_notice:
        fail(f"licenses/libc++-NOTICE.txt is incomplete (missing {phrase!r})")

gradle = read("app/build.gradle.kts")
if "testImplementation(libs.json)" not in gradle:
    fail("org.json test dependency declaration changed; re-audit its license")
if re.search(r"(?m)^\s*implementation\(libs\.json\)", gradle):
    fail("org.json dependency must not be moved into the shipped runtime without re-audit")

corpus_path = ROOT / "benchmark/manifests/corpus.json"
if not corpus_path.is_file():
    fail("required file is missing: benchmark/manifests/corpus.json")
try:
    corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
except json.JSONDecodeError as exc:
    fail(f"benchmark/manifests/corpus.json is not valid JSON: {exc}")
if not isinstance(corpus, dict):
    fail("benchmark/manifests/corpus.json must contain a JSON object")

corpus_text = json.dumps(corpus, ensure_ascii=False).lower()
for phrase in (
    "cc-by-4.0",
    "cc by 4.0",
    "creativecommons.org/licenses/by/4.0",
):
    if phrase in corpus_text:
        fail(f"benchmark/manifests/corpus.json still declares {phrase!r}")
metadata_text = json.dumps(
    {key: value for key, value in corpus.items() if key != "clips"},
    ensure_ascii=False,
).lower()
if "fleurs" in metadata_text:
    fail("benchmark/manifests/corpus.json still declares the FLEURS dataset")

notices = read("THIRD_PARTY_NOTICES.md")
for phrase in (
    "OpenMDW-1.1",
    "transcribe-cpp-MIT.txt", "ggml-MIT.txt",
    "Apache-2.0.txt", "GPL-3.0-only",
    "MODEL_NOTICE.md", "libc++-NOTICE.txt", "LLVM-exception.txt",
):
    if phrase not in notices:
        fail(f"THIRD_PARTY_NOTICES.md is missing {phrase!r}")
for stale in (
    "CC BY 4.0",
    "CC-BY-4.0",
    "bench.wav",
    "FLEURS",
):
    if stale in notices:
        fail(f"THIRD_PARTY_NOTICES.md still declares removed material {stale!r}")

upstream_mit = read("licenses/UPSTREAM-MIT.txt")
if "Copyright (c) 2025 Noah Mühl" not in upstream_mit or "Permission is hereby granted" not in upstream_mit:
    fail("the original upstream MIT notice was not preserved")

print("License metadata and packaged notice copies are consistent.")
print("Reminder: this check does not replace a complete resolved-dependency SBOM/license scan.")
