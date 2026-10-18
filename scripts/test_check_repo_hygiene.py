#!/usr/bin/env python3
"""Tests for scripts/check_repo_hygiene.py (2026-10 corrective audit, Phase 4).

Uses synthetic files and a throwaway git repository in a temp directory.
No real project file, credential or network access is involved.
"""

import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import check_repo_hygiene as hyg  # noqa: E402


class ForbiddenPathTest(unittest.TestCase):
    def test_agent_state_paths_are_forbidden(self):
        for path in (
            ".opencode/goals/state.json.lock",
            ".omg/state/checkpoint.md",
            ".ai_context/context.md",
            ".agents/skills/x/SKILL.md",
            ".freebuff/session.json",
            ".pytest_cache/v/cache/nodeids",
        ):
            self.assertIsNotNone(hyg.is_forbidden_path(path), path)

    def test_build_outputs_are_forbidden(self):
        for path in (
            "build/foo",
            "app/build/outputs/apk/x.apk",
            ".gradle/8.11/cache.bin",
            "target/release/libfoo.rlib",
            "crates/aura-core/target/debug/deps/foo",
            "app/src/main/jniLibs/arm64-v8a/lib.so",
        ):
            self.assertIsNotNone(hyg.is_forbidden_path(path), path)

    def test_artifacts_and_models_are_forbidden(self):
        for path in (
            "model.gguf",
            "app-release.apk",
            "app-release.aab",
            "app-release.apk.idsig",
            "local.properties",
            "release.keystore",
            "my.jks",
            "creds.p12",
            "model_assets/src/main/assets/builtin-model/model.gguf",
        ):
            self.assertIsNotNone(hyg.is_forbidden_path(path), path)

    def test_legitimate_paths_are_allowed(self):
        for path in (
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "build.gradle.kts",
            "build.rs",
            "keystore/debug.keystore",
            "keystore/release-cert.sha256",
            "scripts/check_repo_hygiene.py",
            "fastlane/metadata/android/en-US/images/icon.png",
            "benchmark/src/main/AndroidBenchmark.kt",
        ):
            self.assertIsNone(hyg.is_forbidden_path(path), path)


class KeystorePolicyTest(unittest.TestCase):
    def test_debug_keystore_allowed(self):
        self.assertFalse(hyg.is_keystore_violation("keystore/debug.keystore"))

    def test_other_keystores_forbidden(self):
        for path in (
            "release.keystore",
            "keystore/release.keystore",
            "other/my.jks",
            "signing.p12",
            "cert.pem",
        ):
            self.assertTrue(hyg.is_keystore_violation(path), path)

    def test_non_keystore_files_are_not_flagged(self):
        self.assertFalse(hyg.is_keystore_violation("app/build.gradle.kts"))
        self.assertFalse(hyg.is_keystore_violation("keystore/release-cert.sha256"))


class SecretScanTest(unittest.TestCase):
    def test_detects_github_token(self):
        self.assertIsNotNone(hyg.find_secret("token = ghp_" + "a" * 36))

    def test_detects_aws_key(self):
        self.assertIsNotNone(hyg.find_secret("aws_key = AKIA" + "B" * 16))

    def test_detects_private_key_block(self):
        self.assertIsNotNone(
            hyg.find_secret("-----BEGIN OPENSSH PRIVATE KEY-----")
        )

    def test_allows_ordinary_source(self):
        self.assertIsNone(
            hyg.find_secret(
                "String apiKey = null; // never logged\nsettings.getApiKey();"
            )
        )

    def test_suppression_marker_allows_fixtures(self):
        line = 'String fake = "sk-%s"; // hygiene-ignore:fake-token' % ("a" * 40)
        self.assertIsNone(hyg.find_secret(line))

    def test_suppression_marker_is_scoped_to_its_line(self):
        text = 'String fake = "sk-%s"; // hygiene-ignore:fake-token\nval b = "sk-%s"\n' % (
            "a" * 40,
            "b" * 40,
        )
        self.assertIsNotNone(hyg.find_secret(text))


class RunChecksTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="hygiene-test-")
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def _track(self, relpath, content=b"hello\n"):
        full = os.path.join(self.root, relpath)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as handle:
            handle.write(content)
        subprocess.run(["git", "add", "-f", relpath], cwd=self.root, check=True)

    def test_clean_repo_passes(self):
        self._track("README.md", b"# title\n")
        self._track("app/src/main/java/Demo.java", b"class Demo {}\n")
        self.assertEqual(hyg.run_checks(self.root), [])

    def test_tracked_agent_state_is_reported(self):
        self._track(".opencode/goals/state.json", b'{"goals": []}\n')
        violations = hyg.run_checks(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn(".opencode", violations[0])

    def test_tracked_secret_is_reported(self):
        self._track("notes.md", ("leak ghp_%s\n" % ("c" * 36)).encode())
        violations = hyg.run_checks(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn("possible committed secret", violations[0])

    def test_oversized_file_is_reported(self):
        big = b"x" * (hyg.SIZE_CAP_BYTES + 1)
        self._track("big.bin", big)
        violations = hyg.run_checks(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn("over the", violations[0])

    def test_binary_files_skip_secret_scan_but_not_size(self):
        # PNG-like binary with a NUL byte must not be string-scanned.
        self._track("image.png", b"\x89PNG\x00\x01\x02binary")
        self.assertEqual(hyg.run_checks(self.root), [])

    def test_disallowed_keystore_is_reported(self):
        self._track("keystore/release.keystore", b"fake-keystore-bytes")
        violations = hyg.run_checks(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn("keystore policy", violations[0])

    def test_multiple_violations_all_reported(self):
        self._track(".omg/state.json", b"{}\n")
        self._track("local.properties", b"sdk.dir=/tmp\n")
        violations = hyg.run_checks(self.root)
        self.assertEqual(len(violations), 2)


if __name__ == "__main__":
    unittest.main()
