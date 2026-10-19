#!/usr/bin/env python3
"""Negative tests for ``scripts/check_release_workflow.py``.

A gate that always passed would be worthless, so every guard it protects gets a
mutation that removes it. The final test runs the gate against the real
``.github/workflows/android_release.yml``.

Run with ``python3 scripts/test_check_release_workflow.py`` (also part of CI).
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GATE = REPO / "scripts" / "check_release_workflow.py"
WORKFLOW = REPO / ".github/workflows/android_release.yml"

BASELINE = """\
name: Synthetic

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:
    inputs:
      validation_only:
        description: 'build only'
        type: boolean
        default: true

permissions:
  contents: read

jobs:
  build-release:
    name: build
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4

    - name: Report release mode
      run: |
        echo "RELEASE_MODE=validation-only" >> "$GITHUB_ENV"

    - name: Upload APK Artifact
      if: startsWith(github.ref, 'refs/tags/v')
      uses: actions/upload-artifact@v4
      with:
        name: apk
        path: app.apk

    - name: Upload AAB Artifact
      if: startsWith(github.ref, 'refs/tags/v')
      uses: actions/upload-artifact@v4
      with:
        name: aab
        path: app.aab

    - name: Upload validation report
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: report
        path: report.txt

    - name: Create Release
      if: startsWith(github.ref, 'refs/tags/v') && (github.event_name == 'push' || inputs.validation_only == false)
      uses: softprops/action-gh-release@v2
      with:
        name: release
        tag_name: v${{ env.VERSION_NAME }}
"""


class GateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.path = Path(self._tmp.name) / "android_release.yml"
        self.path.write_text(BASELINE, encoding="utf-8")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_gate(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GATE), "--workflow", str(self.path)],
            capture_output=True,
            text=True,
        )

    def rewrite(self, old: str, new: str) -> None:
        text = self.path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        self.path.write_text(text.replace(old, new), encoding="utf-8")

    def assert_rejected(self, expected: str) -> None:
        result = self.run_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"gate should have failed.\nstdout={result.stdout}",
        )
        self.assertIn(expected, result.stderr)

    # -- baseline --------------------------------------------------------
    def test_baseline_passes(self) -> None:
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("guards are intact", result.stdout)

    # -- dispatch input --------------------------------------------------
    def test_validation_only_defaulting_to_false_rejected(self) -> None:
        self.rewrite("        default: true", "        default: false")
        self.assert_rejected("must default to true")

    def test_missing_validation_only_input_rejected(self) -> None:
        self.rewrite("      validation_only:\n", "      something_else:\n")
        self.assert_rejected("no `validation_only` input")

    def test_input_not_boolean_rejected(self) -> None:
        self.rewrite("        type: boolean", "        type: string")
        self.assert_rejected("must be declared as a boolean input")

    def test_missing_dispatch_trigger_rejected(self) -> None:
        self.rewrite("  workflow_dispatch:\n", "  pull_request:\n")
        self.assert_rejected("workflow_dispatch is missing")

    # -- publish guard ---------------------------------------------------
    def test_ungated_release_step_rejected(self) -> None:
        self.rewrite(
            "      if: startsWith(github.ref, 'refs/tags/v') && "
            "(github.event_name == 'push' || inputs.validation_only == false)\n",
            "",
        )
        self.assert_rejected("has no `if:` guard")

    def test_guard_without_validation_input_rejected(self) -> None:
        self.rewrite(
            "      if: startsWith(github.ref, 'refs/tags/v') && "
            "(github.event_name == 'push' || inputs.validation_only == false)\n",
            "      if: startsWith(github.ref, 'refs/tags/v')\n",
        )
        self.assert_rejected("is not the reviewed expression")

    def test_guard_without_tag_check_rejected(self) -> None:
        self.rewrite(
            "      if: startsWith(github.ref, 'refs/tags/v') && "
            "(github.event_name == 'push' || inputs.validation_only == false)\n",
            "      if: github.event_name == 'push' || inputs.validation_only == false\n",
        )
        self.assert_rejected("is not the reviewed expression")

    def test_second_publisher_rejected(self) -> None:
        self.rewrite(
            "    - name: Create Release\n",
            "    - name: Create Release Again\n"
            "      uses: softprops/action-gh-release@v2\n"
            "      with:\n"
            "        tag_name: v${{ env.VERSION_NAME }}\n\n"
            "    - name: Create Release\n",
        )
        self.assert_rejected("exactly one softprops/action-gh-release step")

    def test_missing_tag_name_rejected(self) -> None:
        self.rewrite("        tag_name: v${{ env.VERSION_NAME }}\n", "")
        self.assert_rejected("must keep `tag_name")

    def test_publish_command_in_run_block_rejected(self) -> None:
        self.rewrite(
            '        echo "RELEASE_MODE=validation-only" >> "$GITHUB_ENV"\n',
            '        echo "RELEASE_MODE=validation-only" >> "$GITHUB_ENV"\n'
            "        git tag v9.9.9\n",
        )
        self.assert_rejected("runs a publishing command")

    # -- artifact retention ---------------------------------------------
    def test_ungated_apk_upload_rejected(self) -> None:
        self.rewrite(
            "    - name: Upload APK Artifact\n"
            "      if: startsWith(github.ref, 'refs/tags/v')\n",
            "    - name: Upload APK Artifact\n",
        )
        self.assert_rejected("Upload APK Artifact is not limited to tag refs")

    def test_ungated_aab_upload_rejected(self) -> None:
        self.rewrite(
            "    - name: Upload AAB Artifact\n"
            "      if: startsWith(github.ref, 'refs/tags/v')\n",
            "    - name: Upload AAB Artifact\n",
        )
        self.assert_rejected("Upload AAB Artifact is not limited to tag refs")

    def test_report_upload_not_always_rejected(self) -> None:
        self.rewrite(
            "    - name: Upload validation report\n      if: always()\n",
            "    - name: Upload validation report\n",
        )
        self.assert_rejected("must be uploaded unconditionally")

    def test_mode_step_losing_marker_rejected(self) -> None:
        self.rewrite(
            '        echo "RELEASE_MODE=validation-only" >> "$GITHUB_ENV"\n',
            "        true\n",
        )
        self.assert_rejected("no longer records RELEASE_MODE")

    # -- the real file ---------------------------------------------------
    def test_repository_workflow_passes(self) -> None:
        result = subprocess.run(
            [sys.executable, str(GATE), "--workflow", str(WORKFLOW)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
