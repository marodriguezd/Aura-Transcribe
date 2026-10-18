#!/usr/bin/env python3
"""Tests for scripts/verify_signing_identity.py (2026-10 corrective audit, Phase 3).

Synthetic fingerprints only: no real or private key material is involved, and
nothing here reads the actual release keystore.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_signing_identity as vsi  # noqa: E402


def fp(char):
    """A synthetic 64-hex fingerprint made of a repeated character."""
    return char * 64


class NormalizeTest(unittest.TestCase):
    def test_bare_lowercase(self):
        self.assertEqual(vsi.normalize_fingerprint(fp("a")), fp("a"))

    def test_uppercase_and_colons_normalized(self):
        raw = ":".join(["2E"] * 32)
        self.assertEqual(vsi.normalize_fingerprint(raw), "2e" * 32)

    def test_whitespace_tolerated(self):
        self.assertEqual(vsi.normalize_fingerprint("  %s \n" % fp("b")), fp("b"))

    def test_rejects_short_value(self):
        with self.assertRaises(ValueError):
            vsi.normalize_fingerprint("ab12")

    def test_rejects_non_hex(self):
        with self.assertRaises(ValueError):
            vsi.normalize_fingerprint("z" * 64)

    def test_rejects_none(self):
        with self.assertRaises(ValueError):
            vsi.normalize_fingerprint(None)


class LoadPinnedTest(unittest.TestCase):
    def _write(self, content):
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".sha256", delete=False, encoding="utf-8"
        )
        handle.write(content)
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def test_loads_skipping_comments_and_blank_lines(self):
        path = self._write(
            "# comment\n\n%s\n   \n# another comment\n" % fp("c")
        )
        self.assertEqual(vsi.load_pinned(path), {fp("c")})

    def test_multiple_entries_allowed_for_rotation(self):
        path = self._write("%s\n%s\n" % (fp("c"), ":".join(["AB"] * 32)))
        pinned = vsi.load_pinned(path)
        self.assertEqual(pinned, {fp("c"), "ab" * 32})

    def test_missing_file_fails_closed(self):
        with self.assertRaises(vsi.PinnedAnchorError):
            vsi.load_pinned("/nonexistent/release-cert.sha256")

    def test_empty_file_fails_closed(self):
        path = self._write("# only a comment\n\n")
        with self.assertRaises(vsi.PinnedAnchorError):
            vsi.load_pinned(path)

    def test_malformed_entry_fails_closed(self):
        path = self._write("%s\nnot-a-fingerprint\n" % fp("c"))
        with self.assertRaises(vsi.PinnedAnchorError):
            vsi.load_pinned(path)


class CheckTest(unittest.TestCase):
    def test_single_matching_fingerprint_passes(self):
        ok, report = vsi.check({fp("a")}, [fp("a")])
        self.assertTrue(ok)
        self.assertTrue(any("OK" in line for line in report))

    def test_colon_format_observed_matches(self):
        ok, _ = vsi.check({fp("a")}, [":".join(["AA"] * 32)])
        self.assertTrue(ok)

    def test_mismatched_fingerprint_fails(self):
        ok, report = vsi.check({fp("a")}, [fp("b")])
        self.assertFalse(ok)
        self.assertTrue(any("MISMATCH" in line for line in report))

    def test_mixed_match_and_mismatch_fails(self):
        # APK pinned, AAB signed by something else: the release must fail.
        ok, _ = vsi.check({fp("a")}, [fp("a"), fp("b")])
        self.assertFalse(ok)

    def test_two_artifacts_signed_by_different_certs_fail(self):
        ok, report = vsi.check({fp("a"), fp("b")}, [fp("a"), fp("b")])
        self.assertFalse(ok)
        self.assertTrue(any("DIFFERENT" in line for line in report))

    def test_rotation_window_with_identical_artifacts_passes(self):
        # During a rotation both old and new are pinned; both artifacts share
        # the new cert: that is a legitimate state.
        ok, _ = vsi.check({fp("a"), fp("b")}, [fp("b"), fp("b")])
        self.assertTrue(ok)

    def test_empty_observed_fails(self):
        ok, report = vsi.check({fp("a")}, [])
        self.assertFalse(ok)
        self.assertTrue(any("no observed" in line for line in report))

    def test_empty_pinned_fails(self):
        ok, _ = vsi.check(set(), [fp("a")])
        self.assertFalse(ok)

    def test_malformed_observed_fails_closed(self):
        ok, report = vsi.check({fp("a")}, ["garbage"])
        self.assertFalse(ok)
        self.assertTrue(any("malformed" in line for line in report))


class MainTest(unittest.TestCase):
    def _write_pinned(self, content):
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".sha256", delete=False, encoding="utf-8"
        )
        handle.write(content)
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def test_main_passes_on_match(self):
        path = self._write_pinned("# anchor\n%s\n" % fp("d"))
        self.assertEqual(
            vsi.main(["--pinned", path, "--observed", fp("d")]), 0
        )

    def test_main_fails_on_mismatch(self):
        path = self._write_pinned("%s\n" % fp("d"))
        self.assertEqual(
            vsi.main(["--pinned", path, "--observed", fp("e")]), 1
        )

    def test_main_fails_on_missing_pinned_file(self):
        self.assertEqual(
            vsi.main(
                ["--pinned", "/nonexistent/x.sha256", "--observed", fp("d")]
            ),
            1,
        )

    def test_main_fails_without_observed_values(self):
        path = self._write_pinned("%s\n" % fp("d"))
        self.assertEqual(vsi.main(["--pinned", path]), 1)

    def test_main_accepts_repeated_observed_flags(self):
        path = self._write_pinned("%s\n" % fp("d"))
        self.assertEqual(
            vsi.main(
                [
                    "--pinned",
                    path,
                    "--observed",
                    fp("d"),
                    "--observed",
                    ":".join(["DD"] * 32),
                ]
            ),
            0,
        )


if __name__ == "__main__":
    unittest.main()
