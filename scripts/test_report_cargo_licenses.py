#!/usr/bin/env python3
"""Pure tests for the Cargo license inventory helpers.

These tests exercise only the classification functions; they never invoke
Cargo, touch the network or read the repository's resolved graph.
"""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("report_cargo_licenses.py")
SPEC = importlib.util.spec_from_file_location("report_cargo_licenses", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class LicenseTokensTest(unittest.TestCase):
    def test_simple_disjunction(self) -> None:
        self.assertEqual(
            report.license_tokens("MIT OR Apache-2.0"),
            {"MIT", "Apache-2.0"},
        )

    def test_with_exception(self) -> None:
        self.assertEqual(
            report.license_tokens("Apache-2.0 WITH LLVM-exception"),
            {"Apache-2.0", "LLVM-exception"},
        )

    def test_parentheses_and_slash(self) -> None:
        self.assertEqual(
            report.license_tokens("(MIT OR Apache-2.0) AND BSD-3-Clause"),
            {"MIT", "Apache-2.0", "BSD-3-Clause"},
        )
        self.assertEqual(
            report.license_tokens("MIT/Apache-2.0"),
            {"MIT", "Apache-2.0"},
        )

    def test_operators_are_not_identifiers(self) -> None:
        self.assertEqual(
            report.license_tokens("MIT AND Apache-2.0 OR Zlib"),
            {"MIT", "Apache-2.0", "Zlib"},
        )

    def test_empty_expression_has_no_tokens(self) -> None:
        self.assertEqual(report.license_tokens("   "), set())


class ClassifyLicenseTest(unittest.TestCase):
    def test_known_expression_is_declared(self) -> None:
        self.assertEqual(
            report.classify_license(
                "MIT OR Apache-2.0", None, report.AUDITED_LICENSE_IDS
            ),
            "declared",
        )

    def test_project_dual_expression_is_known(self) -> None:
        self.assertEqual(
            report.classify_license(
                "MIT AND GPL-3.0-only", None, report.AUDITED_LICENSE_IDS
            ),
            "declared",
        )

    def test_license_file_only_is_distinct_from_missing(self) -> None:
        self.assertEqual(
            report.classify_license(None, "LICENSE", report.AUDITED_LICENSE_IDS),
            "license-file",
        )

    def test_neither_field_is_missing(self) -> None:
        self.assertEqual(
            report.classify_license(None, None, report.AUDITED_LICENSE_IDS),
            "missing",
        )

    def test_unlisted_identifier_is_unknown(self) -> None:
        self.assertEqual(
            report.classify_license(
                "Proprietary", None, report.AUDITED_LICENSE_IDS
            ),
            "unknown",
        )

    def test_unknown_identifier_inside_expression_is_unknown(self) -> None:
        self.assertEqual(
            report.classify_license(
                "MIT OR LicenseRef-Custom", None, report.AUDITED_LICENSE_IDS
            ),
            "unknown",
        )

    def test_placeholder_is_unknown(self) -> None:
        for placeholder in ("NOASSERTION", "UNKNOWN", "UNLICENSED", "NONE"):
            with self.subTest(placeholder=placeholder):
                self.assertEqual(
                    report.classify_license(
                        placeholder, None, report.AUDITED_LICENSE_IDS
                    ),
                    "unknown",
                )

    def test_blank_expression_with_license_file_is_unknown(self) -> None:
        self.assertEqual(
            report.classify_license("   ", "LICENSE", report.AUDITED_LICENSE_IDS),
            "unknown",
        )

    def test_allowlist_extension_accepts_reviewed_identifier(self) -> None:
        allowed = set(report.AUDITED_LICENSE_IDS) | {"LicenseRef-Custom"}
        self.assertEqual(
            report.classify_license("MIT OR LicenseRef-Custom", None, allowed),
            "declared",
        )


if __name__ == "__main__":
    unittest.main()
