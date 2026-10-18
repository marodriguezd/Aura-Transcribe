#!/usr/bin/env python3
"""Self-test for scripts/check_release_notes.py."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GATE = REPO_ROOT / "scripts" / "check_release_notes.py"


def run_gate(root: Path) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(GATE), "--root", str(root)],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
    )
    return proc.returncode, proc.stdout + proc.stderr


class ReleaseNotesGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="release-notes-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write(self, content: str) -> None:
        (self.tmp / "RELEASE_NOTES.md").write_text(content, encoding="utf-8")

    def test_missing_file_fails(self) -> None:
        code, out = run_gate(self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("missing file", out.lower())

    def test_empty_fails(self) -> None:
        self.write("")
        code, out = run_gate(self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("empty", out.lower())

    def test_too_short_fails(self) -> None:
        self.write("# Aura Transcribe v1.0.0 — Test\n\n" + " ".join(["word"] * 10))
        code, out = run_gate(self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("below the minimum", out)

    def test_too_long_fails(self) -> None:
        self.write("# Aura Transcribe v1.0.0 — Test\n\n" + " ".join(["word"] * 300))
        code, out = run_gate(self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("exceeds the maximum", out)

    def test_in_range_passes(self) -> None:
        body = " ".join(["hardening"] * 150)
        self.write("# Aura Transcribe v1.0.0 — Test\n\n" + body)
        code, out = run_gate(self.tmp)
        self.assertEqual(0, code, out)

    def test_multiple_version_headers_fails(self) -> None:
        body = " ".join(["word"] * 150)
        self.write(
            "## v1.0.0 — A\n\n" + body + "\n\n## v1.0.0 — B\n"
        )
        code, out = run_gate(self.tmp)
        self.assertEqual(1, code, out)
        self.assertIn("version header", out.lower())


if __name__ == "__main__":
    unittest.main()
