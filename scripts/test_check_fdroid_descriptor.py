#!/usr/bin/env python3
"""Negative tests for ``scripts/check_fdroid_descriptor.py``.

Each test removes exactly one guard from a fixture and requires the specific
rejection, so a gate that always passed cannot survive this suite. The final test
runs the gate against the real repository.

Run with ``python3 scripts/test_check_fdroid_descriptor.py`` (also part of CI).
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GATE = REPO / "scripts" / "check_fdroid_descriptor.py"

TEMPLATE = """\
Categories:
  - Multimedia
License: MIT
AuthorName: Aura Transcribe Contributors
SourceCode: https://github.com/marodriguezd/Aura-Transcribe
IssueTracker: https://github.com/marodriguezd/Aura-Transcribe/issues
Changelog: https://github.com/marodriguezd/Aura-Transcribe/releases

AutoName: Aura Transcribe
Summary: Offline speech-to-text
Description: |
  A description.

Disabled: >-
  Not buildable offline: the release variant carries the 560 MB GGUF, the
  buildserver has no network, and cargo-ndk is not installed.

RepoType: git
Repo: https://github.com/marodriguezd/Aura-Transcribe.git

Builds:
  - versionName: '@@VERSION_NAME@@'
    versionCode: @@VERSION_CODE@@
    commit: '@@COMMIT_SHA@@'
    gradle:
      - assembleRelease
    prebuild:
      - export ANDROID_NDK_HOME=$$NDK$$
    ndk: 28.2.13676358

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: '@@VERSION_NAME@@'
CurrentVersionCode: @@VERSION_CODE@@
"""

FASTFILE = """\
lane :fdroid_export do
  template_path = File.expand_path("fdroid/com.auratranscribe.app.yml", __dir__)
  fdroid_yaml = File.read(template_path).gsub("@@VERSION_NAME@@", "1.0.0")
  if fdroid_yaml.include?("@@")
    UI.user_error!("Unsubstituted placeholder")
  end
end
"""


class GateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "fastlane" / "fdroid").mkdir(parents=True)
        (self.root / "app").mkdir()
        (self.root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
        self.template = self.root / "fastlane/fdroid/com.auratranscribe.app.yml"
        self.fastfile = self.root / "fastlane/Fastfile"
        self.template.write_text(TEMPLATE, encoding="utf-8")
        self.fastfile.write_text(FASTFILE, encoding="utf-8")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_gate(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GATE), "--root", str(self.root)],
            capture_output=True,
            text=True,
        )

    def rewrite(self, path: Path, old: str, new: str) -> None:
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")

    def assert_rejected(self, expected: str) -> None:
        result = self.run_gate()
        self.assertEqual(result.returncode, 1, f"gate should fail\n{result.stdout}")
        self.assertIn(expected, result.stderr)

    # -- baseline --------------------------------------------------------
    def test_baseline_passes(self) -> None:
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)

    # -- recipe defects --------------------------------------------------
    def test_subdir_reintroduced_rejected(self) -> None:
        self.rewrite(self.template, "    versionCode:", "    subdir: app\n    versionCode:")
        self.assert_rejected("the descriptor sets `subdir:`")

    def test_app_wrapper_present_rejected(self) -> None:
        (self.root / "app" / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
        self.assert_rejected("app/gradlew exists")

    def test_missing_root_wrapper_rejected(self) -> None:
        (self.root / "gradlew").unlink()
        self.assert_rejected("gradlew is missing at the repository root")

    def test_network_step_rejected(self) -> None:
        self.rewrite(
            self.template,
            "      - export ANDROID_NDK_HOME=$$NDK$$",
            "      - export ANDROID_NDK_HOME=$$NDK$$\n      - curl -L https://example.invalid/model.gguf -o model.gguf",
        )
        self.assert_rejected("buildserver is offline")

    def test_cargo_install_rejected(self) -> None:
        self.rewrite(
            self.template,
            "      - export ANDROID_NDK_HOME=$$NDK$$",
            "      - export ANDROID_NDK_HOME=$$NDK$$\n      - cargo install cargo-ndk",
        )
        self.assert_rejected("cargo install")

    def test_missing_assemble_release_rejected(self) -> None:
        self.rewrite(self.template, "      - assembleRelease\n", "      - assembleDebug\n")
        self.assert_rejected("must request `assembleRelease`")

    def test_wrong_ndk_rejected(self) -> None:
        self.rewrite(self.template, "ndk: 28.2.13676358", "ndk: 27.0.0")
        self.assert_rejected("must pin `ndk:")

    # -- honesty ---------------------------------------------------------
    def test_disabled_removed_rejected(self) -> None:
        text = self.template.read_text(encoding="utf-8")
        start = text.index("Disabled: >-")
        end = text.index("RepoType:")
        self.template.write_text(text[:start] + text[end:], encoding="utf-8")
        self.assert_rejected("no `Disabled:` field")

    def test_disabled_reason_without_cargo_ndk_rejected(self) -> None:
        self.rewrite(
            self.template,
            "and cargo-ndk is not installed.",
            "and the build image is old.",
        )
        self.assert_rejected("does not mention 'cargo-ndk'")

    def test_disabled_reason_without_model_rejected(self) -> None:
        self.rewrite(self.template, "carries the 560 MB GGUF", "is large")
        self.assert_rejected("does not mention 'gguf'")

    def test_license_changed_rejected(self) -> None:
        self.rewrite(self.template, "License: MIT", "License: GPL-3.0-only")
        self.assert_rejected("must declare `License: MIT`")

    def test_wrong_repo_rejected(self) -> None:
        self.rewrite(
            self.template,
            "Repo: https://github.com/marodriguezd/Aura-Transcribe.git",
            "Repo: https://github.com/marodriguezd/android_transcribe_app.git",
        )
        self.assert_rejected("points at the wrong repository")

    # -- placeholders ----------------------------------------------------
    def test_missing_placeholder_rejected(self) -> None:
        self.rewrite(self.template, "@@COMMIT_SHA@@", "HEAD")
        self.assert_rejected("placeholder @@COMMIT_SHA@@ is missing")

    # -- lane wiring -----------------------------------------------------
    def test_lane_not_reading_template_rejected(self) -> None:
        self.fastfile.write_text("lane :fdroid_export do\nend\n", encoding="utf-8")
        self.assert_rejected("does not read the committed template")

    def test_lane_heredoc_reintroduced_rejected(self) -> None:
        with self.fastfile.open("a", encoding="utf-8") as handle:
            handle.write('fdroid_yaml = <<~YAML\n  License: MIT\nYAML\n')
        self.assert_rejected("Ruby heredoc")

    # -- the real repository ---------------------------------------------
    def test_repository_descriptor_passes(self) -> None:
        result = subprocess.run(
            [sys.executable, str(GATE)],
            capture_output=True,
            text=True,
            cwd=str(REPO),
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
