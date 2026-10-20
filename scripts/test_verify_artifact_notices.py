#!/usr/bin/env python3
"""Self-test for scripts/verify_artifact_notices.py.

Runs with the standard library only (no pytest), like the other ``test_*``
suites in this directory. The point is the *rejection* behaviour: the published
v1.0.0 artifacts shipped the bundled model without a single license text, so
every way the notice set can go missing, move, go stale or be unreadable has to
demonstrably fail — not just the happy path.

Each case builds a throw-away source root and synthetic archives; nothing here
reads the real repository documents, so the suite cannot rot with them.
"""

from __future__ import annotations

import io
import os
import shutil
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_artifact_notices as verifier  # noqa: E402

LICENSE_BYTES = b"MIT License\n\nCopyright (c) 2025 Test\n"
NOTICES_BYTES = b"# Third-Party Notices\n"
MODEL_LICENSE_BYTES = b"# Model License\n"
MODEL_NOTICE_BYTES = b"# Model NOTICE\n"


class VerifyArtifactNoticesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workdir = tempfile.mkdtemp(prefix="verify-notices-test-")
        self.root = os.path.join(self.workdir, "repo")
        os.makedirs(os.path.join(self.root, "licenses"))
        self.write("LICENSE", LICENSE_BYTES)
        self.write("THIRD_PARTY_NOTICES.md", NOTICES_BYTES)
        self.write("MODEL_LICENSE.md", MODEL_LICENSE_BYTES)
        self.write("MODEL_NOTICE.md", MODEL_NOTICE_BYTES)
        for name in verifier.LICENSE_TEXTS:
            self.write(f"licenses/{name}", f"text of {name}\n".encode())

    def tearDown(self) -> None:
        shutil.rmtree(self.workdir, ignore_errors=True)

    def write(self, rel: str, data: bytes) -> None:
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(data)

    def read(self, rel: str) -> bytes:
        with open(os.path.join(self.root, rel), "rb") as handle:
            return handle.read()

    def archive(self, name: str, entries: dict[str, bytes]) -> str:
        path = os.path.join(self.workdir, name)
        with zipfile.ZipFile(path, "w") as handle:
            for entry, content in entries.items():
                handle.writestr(entry, content)
        return path

    def apk_entries(self, **overrides: bytes) -> dict[str, bytes]:
        entries = {
            "assets/LICENSE": self.read("LICENSE"),
            "assets/THIRD_PARTY_NOTICES.md": self.read("THIRD_PARTY_NOTICES.md"),
        }
        for name in verifier.LICENSE_TEXTS:
            entries["assets/licenses/" + name] = self.read("licenses/" + name)
        entries["assets/MODEL_LICENSE.md"] = self.read("MODEL_LICENSE.md")
        entries["assets/MODEL_NOTICE.md"] = self.read("MODEL_NOTICE.md")
        entries.update(overrides)
        return entries

    def aab_entries(self, **overrides: bytes) -> dict[str, bytes]:
        entries = {
            "base/assets/LICENSE": self.read("LICENSE"),
            "base/assets/THIRD_PARTY_NOTICES.md": self.read("THIRD_PARTY_NOTICES.md"),
        }
        for name in verifier.LICENSE_TEXTS:
            entries["base/assets/licenses/" + name] = self.read("licenses/" + name)
        entries["model_assets/assets/MODEL_LICENSE.md"] = self.read("MODEL_LICENSE.md")
        entries["model_assets/assets/MODEL_NOTICE.md"] = self.read("MODEL_NOTICE.md")
        entries.update(overrides)
        return entries

    def run_verifier(
        self, kind: str, archive: str, require_model_docs: bool = False
    ) -> int:
        stdout, stderr = sys.stdout, sys.stderr
        captured_out, captured_err = io.StringIO(), io.StringIO()
        sys.stdout, sys.stderr = captured_out, captured_err
        try:
            code = verifier.verify(kind, archive, self.root, require_model_docs)
        except SystemExit as exit_:
            # fail() reports through SystemExit(1); the CLI exits non-zero all
            # the same, so the test harness counts it as a failed verification.
            code = exit_.code if isinstance(exit_.code, int) else 1
        finally:
            sys.stdout, sys.stderr = stdout, stderr
        self.last_stderr = captured_err.getvalue()
        return code

    # -- happy paths ----------------------------------------------------
    def test_apk_with_full_notice_set_passes(self) -> None:
        archive = self.archive("ok.apk", self.apk_entries())
        self.assertEqual(0, self.run_verifier("apk", archive, require_model_docs=True))

    def test_aab_with_full_notice_set_passes(self) -> None:
        archive = self.archive("ok.aab", self.aab_entries())
        self.assertEqual(0, self.run_verifier("aab", archive, require_model_docs=True))

    def test_debug_apk_without_model_documents_passes(self) -> None:
        entries = {
            key: value
            for key, value in self.apk_entries().items()
            if key not in ("assets/MODEL_LICENSE.md", "assets/MODEL_NOTICE.md")
        }
        archive = self.archive("debug.apk", entries)
        self.assertEqual(0, self.run_verifier("apk", archive))

    # -- missing documents ---------------------------------------------
    def test_apk_without_mit_license_fails(self) -> None:
        entries = self.apk_entries()
        del entries["assets/LICENSE"]
        archive = self.archive("no-license.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("assets/LICENSE", self.last_stderr)

    def test_apk_without_third_party_notices_fails(self) -> None:
        entries = self.apk_entries()
        del entries["assets/THIRD_PARTY_NOTICES.md"]
        archive = self.archive("no-notices.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("assets/THIRD_PARTY_NOTICES.md", self.last_stderr)

    def test_apk_without_openmdw_text_fails(self) -> None:
        entries = self.apk_entries()
        del entries["assets/licenses/OpenMDW-1.1.txt"]
        archive = self.archive("no-openmdw.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("assets/licenses/OpenMDW-1.1.txt", self.last_stderr)

    def test_aab_without_model_notice_fails_when_model_docs_required(self) -> None:
        entries = self.aab_entries()
        del entries["model_assets/assets/MODEL_NOTICE.md"]
        archive = self.archive("no-model-notice.aab", entries)
        self.assertEqual(1, self.run_verifier("aab", archive, require_model_docs=True))
        self.assertIn("model_assets/assets/MODEL_NOTICE.md", self.last_stderr)

    def test_model_documents_may_be_absent_only_without_the_flag(self) -> None:
        entries = {
            key: value
            for key, value in self.aab_entries().items()
            if not key.endswith(("MODEL_LICENSE.md", "MODEL_NOTICE.md"))
        }
        archive = self.archive("debug.aab", entries)
        self.assertEqual(1, self.run_verifier("aab", archive, require_model_docs=True))
        self.assertEqual(0, self.run_verifier("aab", archive))

    # -- misplaced copies ----------------------------------------------
    def test_misplaced_license_copy_fails(self) -> None:
        entries = self.apk_entries()
        del entries["assets/LICENSE"]
        entries["assets/legal/LICENSE"] = self.read("LICENSE")
        archive = self.archive("misplaced.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("the only valid delivery path is assets/LICENSE", self.last_stderr)

    def test_aab_model_doc_in_base_module_fails(self) -> None:
        entries = self.aab_entries()
        del entries["model_assets/assets/MODEL_LICENSE.md"]
        entries["base/assets/MODEL_LICENSE.md"] = self.read("MODEL_LICENSE.md")
        archive = self.archive("wrong-module.aab", entries)
        self.assertEqual(1, self.run_verifier("aab", archive, require_model_docs=True))
        self.assertIn("model_assets/assets/MODEL_LICENSE.md", self.last_stderr)

    # -- stale or unreadable content ------------------------------------
    def test_stale_license_copy_fails(self) -> None:
        # Same length as the source, so the rejection must come from the content
        # comparison rather than the size check.
        entries = self.apk_entries()
        stale = b"X" + LICENSE_BYTES[1:]
        self.assertEqual(len(stale), len(LICENSE_BYTES))
        entries["assets/LICENSE"] = stale
        archive = self.archive("stale.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("not byte-identical", self.last_stderr)

    def test_truncated_notices_copy_fails(self) -> None:
        entries = self.apk_entries()
        entries["assets/THIRD_PARTY_NOTICES.md"] = NOTICES_BYTES[:5]
        archive = self.archive("truncated.apk", entries)
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("truncated or stale", self.last_stderr)

    @staticmethod
    def corrupt_entry_data(path: str, entry_name: str) -> None:
        """Flip a byte *inside* the stored entry so its CRC no longer matches."""
        with open(path, "rb") as handle:
            blob = bytearray(handle.read())
        marker = entry_name.encode()
        position = blob.find(marker)
        assert position != -1
        # Local header name is followed by the stored data for STORED entries.
        data_start = position + len(marker)
        blob[data_start] ^= 0xFF
        with open(path, "wb") as handle:
            handle.write(bytes(blob))

    def test_unreadable_entry_fails_closed_with_a_diagnostic(self) -> None:
        entry = "assets/LICENSE"
        archive = self.archive("corrupt.apk", self.apk_entries())
        self.corrupt_entry_data(archive, entry)
        code = self.run_verifier("apk", archive, require_model_docs=True)
        self.assertEqual(1, code)
        self.assertIn("could not be read to completion", self.last_stderr)

    def test_unreadable_archive_fails(self) -> None:
        path = os.path.join(self.workdir, "not-a-zip.apk")
        with open(path, "wb") as handle:
            handle.write(b"this is not a zip archive")
        self.assertEqual(1, self.run_verifier("apk", path))

    def test_missing_archive_fails(self) -> None:
        self.assertEqual(
            1,
            self.run_verifier("apk", os.path.join(self.workdir, "absent.apk")),
        )

    # -- source root guards ---------------------------------------------
    def test_missing_source_document_fails_instead_of_vacuous_pass(self) -> None:
        # A missing source document must fail, not silently turn the check into
        # "nothing to compare". The archive is built first, then the source
        # copy disappears.
        archive = self.archive("ok.apk", self.apk_entries())
        os.remove(os.path.join(self.root, "MODEL_NOTICE.md"))
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))

    def test_unlisted_license_text_in_source_fails(self) -> None:
        # A new text under licenses/ must be added to LICENSE_TEXTS in the same
        # change, or it would silently never be verified inside the artifact.
        self.write("licenses/NEW-LICENSE.txt", b"new license\n")
        archive = self.archive("ok.apk", self.apk_entries())
        self.assertEqual(1, self.run_verifier("apk", archive, require_model_docs=True))
        self.assertIn("NEW-LICENSE.txt", self.last_stderr)

    def test_all_license_texts_are_required(self) -> None:
        for name in verifier.LICENSE_TEXTS:
            with self.subTest(name=name):
                entries = self.apk_entries()
                del entries["assets/licenses/" + name]
                archive = self.archive("missing-one.apk", entries)
                self.assertEqual(
                    1, self.run_verifier("apk", archive, require_model_docs=True)
                )


if __name__ == "__main__":
    unittest.main()
