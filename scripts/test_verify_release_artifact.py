#!/usr/bin/env python3
"""Self-test for scripts/verify_release_artifact.py.

Runs with the standard library only (no pytest), matching
`scripts/test_send_telegram.py`, so CI can run it with plain `python3`.

The point of these cases is the *rejection* behaviour: the verifier is the last
thing standing between a broken packaging change and a published artifact, so
every way the bundled model can go missing, move or be duplicated has to be
demonstrated to fail — not just the happy path.
"""

from __future__ import annotations

import os
import io
import shutil
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_release_artifact as verifier  # noqa: E402

MODEL_NAME = "synthetic-model.gguf"
MODEL_BYTES = bytes((index % 251) for index in range(4096))


def make_archive(path: str, entries: dict[str, bytes]) -> str:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return path


class VerifyReleaseArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workdir = tempfile.mkdtemp(prefix="verify-artifact-test-")
        self.model_dir = os.path.join(self.workdir, "builtin-model")
        os.makedirs(self.model_dir)
        with open(os.path.join(self.model_dir, MODEL_NAME), "wb") as handle:
            handle.write(MODEL_BYTES)

    def tearDown(self) -> None:
        shutil.rmtree(self.workdir, ignore_errors=True)

    def archive(self, name: str, entries: dict[str, bytes]) -> str:
        return make_archive(os.path.join(self.workdir, name), entries)

    def run_verifier(self, kind: str, archive: str, allow_model_less: bool = False) -> int:
        # Silence the verifier's progress output; the return code is the assertion.
        stdout, stderr = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = io.StringIO(), io.StringIO()
        try:
            return verifier.verify(kind, archive, self.model_dir, allow_model_less)
        finally:
            sys.stdout, sys.stderr = stdout, stderr

    def test_apk_with_model_in_base_module_passes(self) -> None:
        archive = self.archive(
            "apk-ok.zip", {"assets/builtin-model/" + MODEL_NAME: MODEL_BYTES}
        )
        self.assertEqual(0, self.run_verifier("apk", archive))

    def test_aab_with_model_only_in_asset_pack_passes(self) -> None:
        archive = self.archive(
            "aab-ok.zip",
            {"model_assets/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES},
        )
        self.assertEqual(0, self.run_verifier("aab", archive))

    def test_apk_without_model_fails(self) -> None:
        archive = self.archive("apk-empty.zip", {"assets/unrelated.txt": b"x"})
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_aab_without_model_fails(self) -> None:
        archive = self.archive("aab-empty.zip", {"base/other.txt": b"x"})
        self.assertEqual(1, self.run_verifier("aab", archive))

    def test_missing_archive_fails(self) -> None:
        self.assertEqual(
            1, self.run_verifier("apk", os.path.join(self.workdir, "absent.apk"))
        )

    def test_missing_model_source_dir_fails_instead_of_passing_vacuously(self) -> None:
        archive = self.archive(
            "apk-ok.zip", {"assets/builtin-model/" + MODEL_NAME: MODEL_BYTES}
        )
        stdout, stderr = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = io.StringIO(), io.StringIO()
        try:
            code = verifier.verify("apk", archive, os.path.join(self.workdir, "nope"), False)
        finally:
            sys.stdout, sys.stderr = stdout, stderr
        self.assertEqual(1, code)

    def test_model_outside_the_asset_directory_fails(self) -> None:
        archive = self.archive("misplaced.zip", {"assets/model/" + MODEL_NAME: MODEL_BYTES})
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_aab_model_in_the_base_module_fails(self) -> None:
        archive = self.archive(
            "aab-base-only.zip", {"base/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES}
        )
        self.assertEqual(1, self.run_verifier("aab", archive))

    def test_duplicate_delivery_fails(self) -> None:
        archive = self.archive(
            "aab-dup.zip",
            {
                "model_assets/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES,
                "base/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES,
            },
        )
        self.assertEqual(1, self.run_verifier("aab", archive))

    def test_truncated_model_fails(self) -> None:
        archive = self.archive(
            "truncated.zip", {"assets/builtin-model/" + MODEL_NAME: MODEL_BYTES[:64]}
        )
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_corrupted_model_fails_on_the_content_hash(self) -> None:
        corrupted = bytearray(MODEL_BYTES)
        corrupted[0] ^= 0xFF
        archive = self.archive(
            "corrupted.zip", {"assets/builtin-model/" + MODEL_NAME: bytes(corrupted)}
        )
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_unreadable_archive_fails(self) -> None:
        path = os.path.join(self.workdir, "not-a-zip.apk")
        with open(path, "wb") as handle:
            handle.write(b"this is not a zip file")
        self.assertEqual(1, self.run_verifier("apk", path))

    def test_model_less_archive_only_passes_with_the_explicit_opt_in(self) -> None:
        archive = self.archive("empty.zip", {"assets/unrelated.txt": b"x"})
        self.assertEqual(1, self.run_verifier("apk", archive, allow_model_less=False))
        self.assertEqual(0, self.run_verifier("apk", archive, allow_model_less=True))

    def test_every_declared_model_must_be_present(self) -> None:
        second = "synthetic-second-model.gguf"
        with open(os.path.join(self.model_dir, second), "wb") as handle:
            handle.write(b"second")
        archive = self.archive(
            "partial.zip", {"assets/builtin-model/" + MODEL_NAME: MODEL_BYTES}
        )
        self.assertEqual(1, self.run_verifier("apk", archive))


if __name__ == "__main__":
    unittest.main(verbosity=2)
