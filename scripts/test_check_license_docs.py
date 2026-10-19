#!/usr/bin/env python3
"""Negative tests for ``scripts/check_license_docs.py``.

Each test builds a throw-away copy of the repository's licensing surface, then
introduces exactly one defect and asserts that the gate fails with the
defect-specific message. The baseline test asserts the unmodified copy passes,
so a gate that simply always failed would not survive this suite.

Run with ``python3 scripts/test_check_license_docs.py`` (also part of CI).
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GATE = REPO / "scripts" / "check_license_docs.py"

# The licensing surface the gate reads: documents, manifests and the files whose
# copies must stay byte-identical.
FILES = [
    "LICENSE",
    "README.md",
    "README_es.md",
    "README_de.md",
    "README_fr.md",
    "README_it.md",
    "README_zh.md",
    "ABOUT.md",
    "Cargo.toml",
    "crates/aura-core/Cargo.toml",
    "THIRD_PARTY_NOTICES.md",
    "MODEL_LICENSE.md",
    "MODEL_NOTICE.md",
    "LICENSE_AUDIT.md",
    "fastlane/Fastfile",
    "fastlane/metadata/android/en-US/full_description.txt",
    "benchmark/manifests/corpus.json",
    "benchmark/manifests/models.json",
    "app/build.gradle.kts",
    "app/src/main/java/dev/notune/transcribe/MainActivity.java",
    "app/src/main/assets/LICENSE",
    "app/src/main/assets/THIRD_PARTY_NOTICES.md",
    "model_assets/src/main/assets/MODEL_LICENSE.md",
    "model_assets/src/main/assets/MODEL_NOTICE.md",
]
DIRS = ["licenses", "app/src/main/assets/licenses"]
PIN = "53ce288f69326b415aeef030cfcb02a08a8096dd"


def build_fixture(target: Path) -> Path:
    for rel in FILES:
        src = REPO / rel
        if not src.is_file():
            raise SystemExit(f"fixture source missing: {rel}")
        dst = target / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
    for rel in DIRS:
        shutil.copytree(REPO / rel, target / rel)
    return target


class GateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = build_fixture(Path(self._tmp.name) / "repo")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # -- helpers ---------------------------------------------------------
    def run_gate(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GATE), "--root", str(self.root)],
            capture_output=True,
            text=True,
        )

    def rewrite(self, rel: str, old: str, new: str) -> None:
        path = self.root / rel
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text, f"{rel} does not contain the expected fragment")
        path.write_text(text.replace(old, new), encoding="utf-8")

    def assert_rejected(self, expected: str) -> None:
        result = self.run_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"gate should have failed but did not.\nstdout={result.stdout}",
        )
        self.assertIn(expected, result.stderr)

    def sync_copy(self, rel: str) -> None:
        source = {
            "app/src/main/assets/THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
            "app/src/main/assets/LICENSE": "LICENSE",
            "model_assets/src/main/assets/MODEL_LICENSE.md": "MODEL_LICENSE.md",
            "model_assets/src/main/assets/MODEL_NOTICE.md": "MODEL_NOTICE.md",
        }[rel]
        shutil.copyfile(self.root / source, self.root / rel)

    # -- baseline --------------------------------------------------------
    def test_unmodified_tree_passes(self) -> None:
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("consistent", result.stdout)

    # -- application license --------------------------------------------
    def test_gpl_root_license_rejected(self) -> None:
        (self.root / "LICENSE").write_text(
            "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n",
            encoding="utf-8",
        )
        self.sync_copy("app/src/main/assets/LICENSE")
        self.assert_rejected("LICENSE is missing 'MIT License'")

    def test_missing_upstream_copyright_rejected(self) -> None:
        self.rewrite("LICENSE", "Copyright (c) 2025 Noah Mühl", "Copyright (c) Someone Else")
        self.sync_copy("app/src/main/assets/LICENSE")
        self.assert_rejected("Copyright (c) 2025 Noah")

    def test_cargo_license_mismatch_rejected(self) -> None:
        self.rewrite("Cargo.toml", 'license = "MIT"', 'license = "GPL-3.0-only"')
        self.assert_rejected("Cargo.toml must declare license = \"MIT\"")

    def test_core_cargo_license_mismatch_rejected(self) -> None:
        self.rewrite(
            "crates/aura-core/Cargo.toml", 'license = "MIT"', 'license = "Apache-2.0"'
        )
        self.assert_rejected("crates/aura-core/Cargo.toml must declare license")

    # -- publication surfaces -------------------------------------------
    def test_stale_badge_in_one_locale_rejected(self) -> None:
        self.rewrite(
            "README_es.md",
            "[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)",
            "[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)",
        )
        self.assert_rejected("README_es.md is missing '[![License: MIT]")

    def test_cc_by_claim_rejected(self) -> None:
        path = self.root / "README.md"
        path.write_text(
            path.read_text(encoding="utf-8") + "\nModel licensed under CC-BY 4.0.\n",
            encoding="utf-8",
        )
        self.assert_rejected("still declares 'CC-BY 4.0'")

    def test_about_without_mit_rejected(self) -> None:
        self.rewrite("ABOUT.md", "MIT License", "proprietary terms")
        self.assert_rejected("ABOUT.md is missing 'MIT License'")

    def test_store_description_without_mit_rejected(self) -> None:
        self.rewrite(
            "fastlane/metadata/android/en-US/full_description.txt",
            "MIT License",
            "a source-available license",
        )
        self.assert_rejected("store description is missing 'MIT License'")

    def test_fastfile_old_repository_url_rejected(self) -> None:
        path = self.root / "fastlane/Fastfile"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nRepo: https://github.com/marodriguezd/android_transcribe_app.git\n",
            encoding="utf-8",
        )
        self.assert_rejected("unrelated/old repository URL")

    # -- model documents -------------------------------------------------
    def test_model_claimed_as_mit_rejected(self) -> None:
        self.rewrite(
            "MODEL_LICENSE.md",
            "The model weights are not MIT-licensed.",
            "The model weights are MIT-licensed.",
        )
        self.sync_copy("model_assets/src/main/assets/MODEL_LICENSE.md")
        self.assert_rejected("The model weights are not MIT-licensed")

    def test_model_license_without_pinned_revision_rejected(self) -> None:
        self.rewrite("MODEL_LICENSE.md", PIN, "main")
        self.sync_copy("model_assets/src/main/assets/MODEL_LICENSE.md")
        self.assert_rejected("MODEL_LICENSE.md is missing '53ce288f")

    def test_model_notice_without_upstream_text_rejected(self) -> None:
        self.rewrite(
            "MODEL_NOTICE.md",
            "Modifications: handy-computer converted",
            "Modified by somebody",
        )
        self.sync_copy("model_assets/src/main/assets/MODEL_NOTICE.md")
        self.assert_rejected("MODEL_NOTICE.md is missing 'Modifications: handy-computer")

    def test_drifted_model_notice_copy_rejected(self) -> None:
        with (self.root / "model_assets/src/main/assets/MODEL_NOTICE.md").open(
            "a", encoding="utf-8"
        ) as handle:
            handle.write("\nlocal edit\n")
        self.assert_rejected("license/notices copy differs")

    def test_missing_model_notice_rejected(self) -> None:
        (self.root / "MODEL_NOTICE.md").unlink()
        self.assert_rejected("required file is missing: MODEL_NOTICE.md")

    def test_notices_without_license_texts_rejected(self) -> None:
        self.rewrite(
            "THIRD_PARTY_NOTICES.md", "libc++-NOTICE.txt", "some other file"
        )
        self.sync_copy("app/src/main/assets/THIRD_PARTY_NOTICES.md")
        self.assert_rejected("THIRD_PARTY_NOTICES.md is missing 'libc++-NOTICE.txt'")

    def test_notices_without_separate_model_scope_rejected(self) -> None:
        self.rewrite(
            "THIRD_PARTY_NOTICES.md",
            "separately licensed data asset",
            "ordinary component",
        )
        self.sync_copy("app/src/main/assets/THIRD_PARTY_NOTICES.md")
        self.assert_rejected("separately licensed data asset")

    def test_upstream_notice_removed_rejected(self) -> None:
        self.rewrite(
            "licenses/UPSTREAM-MIT.txt", "Copyright (c) 2025 Noah Mühl", "Copyright"
        )
        shutil.copyfile(
            self.root / "licenses/UPSTREAM-MIT.txt",
            self.root / "app/src/main/assets/licenses/UPSTREAM-MIT.txt",
        )
        self.assert_rejected("licenses/UPSTREAM-MIT.txt is missing")

    # -- supply chain ----------------------------------------------------
    def test_unpinned_build_url_rejected(self) -> None:
        self.rewrite(
            "app/build.gradle.kts",
            f"/resolve/{PIN}",
            "/resolve/main",
        )
        self.assert_rejected("still downloads the bundled model from resolve/main")

    def test_unpinned_runtime_url_rejected(self) -> None:
        self.rewrite(
            "app/src/main/java/dev/notune/transcribe/MainActivity.java",
            f"/resolve/{PIN}/",
            "/resolve/main/",
        )
        self.assert_rejected("MainActivity.java still downloads the bundled model")

    def test_model_hash_change_rejected(self) -> None:
        self.rewrite(
            "app/build.gradle.kts",
            "86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65",
            "0" * 64,
        )
        self.assert_rejected("app/build.gradle.kts is missing '86429e8c")

    def test_unpinned_benchmark_manifest_rejected(self) -> None:
        self.rewrite(
            "benchmark/manifests/models.json",
            f"/resolve/{PIN}/",
            "/resolve/main/",
        )
        self.assert_rejected("models.json still uses resolve/main")

    # -- removed material ------------------------------------------------
    def test_bundled_audio_fixture_rejected(self) -> None:
        (self.root / "app/src/main/assets/bench.wav").write_bytes(b"RIFF")
        self.assert_rejected("bench.wav must not be distributed")

    def test_other_bundled_audio_rejected(self) -> None:
        (self.root / "app/src/main/assets/sample.mp3").write_bytes(b"ID3")
        self.assert_rejected("audio fixture(s) must not be bundled")

    def test_fleurs_corpus_rejected(self) -> None:
        path = self.root / "benchmark/manifests/corpus.json"
        corpus = path.read_text(encoding="utf-8")
        path.write_text(
            corpus.replace('"clips"', '"dataset": "FLEURS",\n  "clips"'), encoding="utf-8"
        )
        self.assert_rejected("still declares the FLEURS dataset")

    def test_json_runtime_dependency_rejected(self) -> None:
        self.rewrite(
            "app/build.gradle.kts",
            "testImplementation(libs.json)",
            "implementation(libs.json)",
        )
        self.assert_rejected("test dependency declaration changed")


if __name__ == "__main__":
    unittest.main()
