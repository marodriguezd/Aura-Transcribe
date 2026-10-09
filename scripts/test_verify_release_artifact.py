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
        self.last_stderr = ""

    def tearDown(self) -> None:
        shutil.rmtree(self.workdir, ignore_errors=True)

    def archive(self, name: str, entries: dict[str, bytes]) -> str:
        return make_archive(os.path.join(self.workdir, name), entries)

    def run_verifier(self, kind: str, archive: str, allow_model_less: bool = False) -> int:
        # Silence the verifier's progress output; the return code is the assertion.
        # The captured stderr is kept so a test can additionally assert that a
        # failure carries a diagnostic instead of an uncaught traceback.
        stdout, stderr = sys.stdout, sys.stderr
        captured_out, captured_err = io.StringIO(), io.StringIO()
        sys.stdout, sys.stderr = captured_out, captured_err
        try:
            code = verifier.verify(kind, archive, self.model_dir, allow_model_less)
        finally:
            sys.stdout, sys.stderr = stdout, stderr
        self.last_stderr = captured_err.getvalue()
        return code

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

    # --------------------------------------------------------------- strict paths
    # The layouts below are what the packaging logic actually produces (see
    # `modelPackFiles` and the asset-pack wiring in `app/build.gradle.kts`):
    # APK -> assets/builtin-model/<name>, AAB -> model_assets/assets/builtin-model/<name>.
    # A suffix match must NOT be enough: every archive here ends with the
    # expected suffix yet is at the wrong place for its artifact kind.

    def test_apk_with_nested_path_matching_the_expected_suffix_fails(self) -> None:
        archive = self.archive(
            "apk-nested.zip",
            {"prefix/deeper/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES},
        )
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_apk_with_the_asset_pack_path_fails(self) -> None:
        # The :model_assets path is AAB-only delivery; an APK carrying the model
        # there would never be read by the runtime.
        archive = self.archive(
            "apk-wrong-module.zip",
            {"model_assets/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES},
        )
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_aab_with_the_pack_path_nested_inside_an_extra_directory_fails(self) -> None:
        # Starts with model_assets/ AND ends with the expected suffix — still not
        # the exact pack path, so it must be rejected.
        archive = self.archive(
            "aab-nested-pack.zip",
            {"model_assets/extra/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES},
        )
        self.assertEqual(1, self.run_verifier("aab", archive))

    def test_apk_with_valid_copy_plus_unexpected_extra_copy_fails(self) -> None:
        # An otherwise perfect delivery accompanied by a second copy that does
        # not even match the suffix is still duplicate delivery.
        archive = self.archive(
            "apk-dup-stray.zip",
            {
                "assets/builtin-model/" + MODEL_NAME: MODEL_BYTES,
                "stray/" + MODEL_NAME: MODEL_BYTES,
            },
        )
        self.assertEqual(1, self.run_verifier("apk", archive))

    def test_model_less_opt_in_does_not_cover_a_misplaced_model(self) -> None:
        # The escape hatch is for artifacts with NO model at all; a model parked
        # at a wrong path is a packaging bug and must fail even with the opt-in.
        archive = self.archive(
            "apk-nested-hatch.zip",
            {"prefix/assets/builtin-model/" + MODEL_NAME: MODEL_BYTES},
        )
        self.assertEqual(1, self.run_verifier("apk", archive, allow_model_less=True))

    # ------------------------------------------------------- fail-closed reads

    @staticmethod
    def corrupt_first_entry_data(path: str, entry_name: str) -> None:
        """Flip a byte *inside* the stored entry so its CRC no longer matches."""
        with open(path, "r+b") as handle:
            raw = bytearray(handle.read())
        name = entry_name.encode("utf-8")
        pos = raw.find(name)  # first occurrence is the local file header
        assert pos >= 30, "entry name not found in the archive"
        fnlen = int.from_bytes(raw[pos - 4 : pos - 2], "little")
        extralen = int.from_bytes(raw[pos - 2 : pos], "little")
        data_off = pos + fnlen + extralen
        raw[data_off] ^= 0xFF
        with open(path, "r+b") as handle:
            handle.write(bytes(raw))

    def test_error_raised_while_reading_an_entry_fails_closed_with_a_diagnostic(
        self,
    ) -> None:
        entry = "assets/builtin-model/" + MODEL_NAME
        archive = self.archive("apk-entry-corrupt.zip", {entry: MODEL_BYTES})
        self.corrupt_first_entry_data(archive, entry)
        # zipfile raises BadZipFile (bad CRC) from inside the entry read; that
        # must surface as a verified FAIL, not as an uncaught traceback.
        self.assertEqual(1, self.run_verifier("apk", archive))
        self.assertIn("FAIL:", self.last_stderr)

    def test_truncated_zip_fails(self) -> None:
        archive = self.archive(
            "apk-truncated.zip", {"assets/builtin-model/" + MODEL_NAME: MODEL_BYTES}
        )
        with open(archive, "rb") as handle:
            raw = handle.read()
        with open(archive, "wb") as handle:
            handle.write(raw[: len(raw) // 2])
        self.assertEqual(1, self.run_verifier("apk", archive))
        self.assertIn("FAIL:", self.last_stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
