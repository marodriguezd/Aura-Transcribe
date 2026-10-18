#!/usr/bin/env python3
"""Check license metadata and distributable notice copies for Aura Transcribe."""
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
]


def fail(message: str) -> None:
    print(f"LICENSE CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        fail(f"required file is missing: {rel}")
    return path.read_text(encoding="utf-8")


def same_file(first: str, second: str) -> None:
    if read(first) != read(second):
        fail(f"license/notices copy differs: {first} != {second}")


gpl = read("LICENSE")
if "GNU GENERAL PUBLIC LICENSE" not in gpl or "Version 3, 29 June 2007" not in gpl:
    fail("root LICENSE is not the full GNU GPL version 3 text")
for phrase in (
    "TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION",
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

for rel in ("Cargo.toml", "crates/aura-core/Cargo.toml"):
    if not re.search(r'(?m)^license\s*=\s*"GPL-3\.0-only"\s*$', read(rel)):
        fail(f"{rel} does not declare GPL-3.0-only")

if "GPL-3.0-only" not in read("ABOUT.md"):
    fail("ABOUT.md does not describe the source-code license")
fastfile = read("fastlane/Fastfile")
if "License: GPL-3.0-only" not in fastfile:
    fail("Fastlane F-Droid metadata is not synchronized")
if "github.com/marodriguezd/android_transcribe_app" in fastfile:
    fail("Fastlane still points at the unrelated/old repository URL")

same_file("LICENSE", "app/src/main/assets/LICENSE")
same_file("THIRD_PARTY_NOTICES.md", "app/src/main/assets/THIRD_PARTY_NOTICES.md")
for rel in LICENSES:
    same_file(f"licenses/{rel}", f"app/src/main/assets/licenses/{rel}")
same_file("MODEL_LICENSE.md", "model_assets/src/main/assets/MODEL_LICENSE.md")

model_license = read("MODEL_LICENSE.md")
if "OpenMDW-1.1" not in model_license or "[MIT License](LICENSE)" in model_license:
    fail("MODEL_LICENSE.md has stale application license scope")

gradle = read("app/build.gradle.kts")
if "testImplementation(libs.json)" not in gradle:
    fail("org.json test dependency declaration changed; re-audit its license")
if re.search(r"(?m)^\s*implementation\(libs\.json\)", gradle):
    fail("org.json dependency must not be moved into the shipped runtime without re-audit")

manifest = json.loads(read("benchmark/manifests/corpus.json"))
if manifest.get("license") != "CC-BY-4.0":
    fail("FLEURS benchmark manifest license metadata changed; re-audit it")
if "creativecommons.org/licenses/by/4.0" not in manifest.get("license_url", ""):
    fail("FLEURS benchmark manifest is missing the CC BY 4.0 license URL")

notices = read("THIRD_PARTY_NOTICES.md")
for phrase in (
    "OpenMDW-1.1", "CC BY 4.0", "bench.wav",
    "transcribe-cpp-MIT.txt", "ggml-MIT.txt",
    "Apache-2.0.txt", "GPL-3.0-only",
):
    if phrase not in notices:
        fail(f"THIRD_PARTY_NOTICES.md is missing {phrase!r}")

upstream_mit = read("licenses/UPSTREAM-MIT.txt")
if "Copyright (c) 2025 Noah Mühl" not in upstream_mit or "Permission is hereby granted" not in upstream_mit:
    fail("the original upstream MIT notice was not preserved")

print("License metadata and packaged notice copies are consistent.")
print("Reminder: this check does not replace a complete resolved-dependency SBOM/license scan.")
