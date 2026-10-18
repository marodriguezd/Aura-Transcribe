#!/usr/bin/env python3
"""Self-test for scripts/check_jni_parity.py.

Runs with the standard library only (no pytest), matching
`scripts/test_verify_release_artifact.py`, so CI can run it with plain
`python3`.

Cases are synthetic temp trees for the happy path plus every way the gate has
to reject: a missing export, a renamed export, an orphan export, comment/string
look-alikes, mangling (`_1`, `_00024`), overloads, and test sources that must
not be scanned. The mutation test copies the *real* sources, renames one real
export and proves the gate turns red — the same regression class that produced
the dead `pushAudioDirect` declaration.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GATE = REPO_ROOT / "scripts" / "check_jni_parity.py"


def rust_export(symbol: str) -> str:
    return (
        "#[no_mangle]\n"
        f'pub unsafe extern "system" fn {symbol}(\n'
        "    _env: JNIEnv,\n"
        "    _class: JClass,\n"
        ") {}\n"
    )


def unannotated_export(symbol: str) -> str:
    """A well-formed JNI signature without the #[no_mangle] attribute."""
    return (
        f'pub unsafe extern "system" fn {symbol}(\n'
        "    _env: JNIEnv,\n"
        "    _class: JClass,\n"
        ") {}\n"
    )


def cfg_export(symbol: str, cfg: str) -> str:
    """A JNI export inside a ``#[cfg(cfg)] mod`` scope."""
    inner = "\n".join(
        "    " + line for line in rust_export(symbol).rstrip().splitlines()
    )
    return f"#[cfg({cfg})]\nmod gated {{\n{inner}\n}}\n"


def cfg_fn_export(symbol: str, cfg: str) -> str:
    """A JNI export with a ``#[cfg(cfg)]`` attribute directly on the fn."""
    return f"#[cfg({cfg})]\n" + rust_export(symbol)


class JniParityGateTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="jni-parity-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def build_tree(
        self,
        java_files: dict[str, str],
        rust_files: dict[str, str],
        test_java_files: dict[str, str] | None = None,
    ) -> Path:
        (self.tmp / "app" / "src" / "main" / "java").mkdir(parents=True, exist_ok=True)
        (self.tmp / "src").mkdir(parents=True, exist_ok=True)
        for rel, content in java_files.items():
            path = self.tmp / "app" / "src" / "main" / "java" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        for rel, content in (test_java_files or {}).items():
            path = self.tmp / "app" / "src" / "test" / "java" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        for name, content in rust_files.items():
            path = self.tmp / "src" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return self.tmp

    def run_gate(self, root: Path, extra_args: list[str] | None = None) -> tuple[int, str]:
        proc = subprocess.run(
            [sys.executable, str(GATE), "--root", str(root)] + (extra_args or []),
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
        )
        return proc.returncode, proc.stdout + proc.stderr

    def verified_count(self, output: str) -> int:
        match = re.search(r"OK \((\d+) declarations verified\)", output)
        self.assertIsNotNone(match, f"missing OK line in:\n{output}")
        return int(match.group(1))

    def test_matching_export_passes(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n    private native void greet();\n}\n"},
            {"hello.rs": rust_export("Java_com_example_Hello_greet")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(1, self.verified_count(out))

    def test_missing_export_fails(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n    private native void greet();\n}\n"},
            {"hello.rs": "pub fn unrelated() {}\n"},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("Java_com_example_Hello_greet", out)
        self.assertIn("without Rust export", out)

    def test_renamed_export_fails(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n    private native void greet();\n}\n"},
            {"hello.rs": rust_export("Java_com_example_Hello_greetX")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("without Rust export", out)
        self.assertIn("without Java native declaration", out)

    def test_orphan_rust_export_fails(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n}\n"},
            {"ghost.rs": rust_export("Java_com_example_Ghost_haunt")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("Java_com_example_Ghost_haunt", out)
        self.assertIn("without Java native declaration", out)

    def test_comment_and_string_mentions_are_ignored(self) -> None:
        root = self.build_tree(
            {
                "com/example/Commented.java": (
                    "package com.example;\n"
                    "// private native void ghost();\n"
                    "/* static native void phantom(); */\n"
                    "public class Commented {\n"
                    '    private String note = "native void fake();";\n'
                    "    private native void real();\n"
                    "}\n"
                )
            },
            {"commented.rs": rust_export("Java_com_example_Commented_real")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(1, self.verified_count(out))

    def test_underscore_mangling_passes(self) -> None:
        root = self.build_tree(
            {"com/example/My_Class.java": "package com.example;\npublic class My_Class {\n    private native void do_it();\n}\n"},
            {"under.rs": rust_export("Java_com_example_My_1Class_do_1it")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(1, self.verified_count(out))

    def test_nested_class_dollar_mangling_passes(self) -> None:
        root = self.build_tree(
            {
                "com/example/Outer.java": (
                    "package com.example;\n"
                    "public class Outer {\n"
                    "    public static class Inner {\n"
                    "        static native void nestedNative();\n"
                    "    }\n"
                    "}\n"
                )
            },
            {"outer.rs": rust_export("Java_com_example_Outer_00024Inner_nestedNative")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(1, self.verified_count(out))

    def test_overload_with_dunder_export_passes(self) -> None:
        root = self.build_tree(
            {
                "com/example/Overload.java": (
                    "package com.example;\n"
                    "public class Overload {\n"
                    "    public native void ping(int value);\n"
                    '    public native void ping(String label);\n'
                    "}\n"
                )
            },
            {
                "overload.rs": (
                    rust_export("Java_com_example_Overload_ping__I")
                    + rust_export("Java_com_example_Overload_ping__Ljava_lang_String_2")
                )
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(2, self.verified_count(out))

    def test_overload_without_dunder_export_fails(self) -> None:
        root = self.build_tree(
            {
                "com/example/Overload.java": (
                    "package com.example;\n"
                    "public class Overload {\n"
                    "    public native void ping(int value);\n"
                    '    public native void ping(String label);\n'
                    "}\n"
                )
            },
            # The short symbol is not linkable when the method is overloaded.
            {"overload.rs": rust_export("Java_com_example_Overload_ping")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("has 2 declaration(s) but only 0", out)
        self.assertIn("__-suffixed JNI export", out)

    def test_cfg_test_export_does_not_count(self) -> None:
        root = self.build_tree(
            {
                "com/example/Hello.java": (
                    "package com.example;\n"
                    "public class Hello {\n"
                    "    private native void greet();\n"
                    "    private native void greetFn();\n"
                    "}\n"
                )
            },
            {},
        )
        gated = root / "src" / "gated.rs"
        gated.write_text(
            cfg_export("Java_com_example_Hello_greet", "test")
            + cfg_fn_export("Java_com_example_Hello_greetFn", "test"),
            encoding="utf-8",
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("Java_com_example_Hello_greet ", out)
        self.assertIn("Java_com_example_Hello_greetFn", out)
        self.assertIn("without Rust export", out)

        # The very same exports without cfg(test) are real.
        gated.write_text(
            rust_export("Java_com_example_Hello_greet")
            + rust_export("Java_com_example_Hello_greetFn"),
            encoding="utf-8",
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(2, self.verified_count(out))

    def test_cfg_any_test_export_does_not_count(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n    private native void greet();\n}\n"},
            {
                "gated.rs": cfg_export(
                    "Java_com_example_Hello_greet", 'any(test, feature = "x")'
                )
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("without Rust export", out)

    def test_non_test_cfg_export_counts(self) -> None:
        root = self.build_tree(
            {
                "com/example/Hello.java": (
                    "package com.example;\n"
                    "public class Hello {\n"
                    "    private native void greet();\n"
                    "    private native void greetFn();\n"
                    "}\n"
                )
            },
            {
                "android.rs": cfg_export(
                    "Java_com_example_Hello_greet", 'target_os = "android"'
                ),
                # not(test) ships in normal builds and must count.
                "normal.rs": cfg_fn_export(
                    "Java_com_example_Hello_greetFn", "not(test)"
                ),
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(2, self.verified_count(out))

    def test_no_mangle_inside_string_does_not_count(self) -> None:
        root = self.build_tree(
            {"com/example/Hello.java": "package com.example;\npublic class Hello {\n    private native void greet();\n}\n"},
            {
                "tricky.rs": (
                    'const NOTE: &str = "#[no_mangle]";\n'
                    'const RAW: &str = r#"#[no_mangle]"#;\n'
                    + unannotated_export("Java_com_example_Hello_greet")
                )
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("Java_com_example_Hello_greet", out)
        self.assertIn("without Rust export", out)

    def test_native_with_annotation_is_parsed(self) -> None:
        root = self.build_tree(
            {
                "com/example/Hello.java": (
                    "package com.example;\n"
                    "public class Hello {\n"
                    "    private native @Deprecated void greet();\n"
                    "}\n"
                )
            },
            {"hello.rs": rust_export("Java_com_example_Hello_greet")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(1, self.verified_count(out))

    def test_native_with_annotation_without_export_fails(self) -> None:
        root = self.build_tree(
            {
                "com/example/Hello.java": (
                    "package com.example;\n"
                    "public class Hello {\n"
                    "    private native @Deprecated void greet();\n"
                    "}\n"
                )
            },
            {"hello.rs": "pub fn unrelated() {}\n"},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("without Rust export", out)

    def test_unparsed_native_fails_closed(self) -> None:
        root = self.build_tree(
            {
                "com/example/Hello.java": (
                    "package com.example;\n"
                    "public class Hello {\n"
                    "    private native void broken()\n"
                    "}\n"
                )
            },
            {"hello.rs": "pub fn unrelated() {}\n"},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("unparsed", out)
        self.assertIn("extend the gate parser", out)

    def test_overload_with_too_few_dunder_exports_fails(self) -> None:
        root = self.build_tree(
            {
                "com/example/Overload.java": (
                    "package com.example;\n"
                    "public class Overload {\n"
                    "    public native void ping(int value);\n"
                    '    public native void ping(String label);\n'
                    "}\n"
                )
            },
            # One export cannot satisfy two overloads.
            {"overload.rs": rust_export("Java_com_example_Overload_ping__J")},
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("has 2 declaration(s) but only 1", out)

    def test_overload_with_extra_dunder_export_fails(self) -> None:
        root = self.build_tree(
            {
                "com/example/Overload.java": (
                    "package com.example;\n"
                    "public class Overload {\n"
                    "    public native void ping(int value);\n"
                    '    public native void ping(String label);\n'
                    "}\n"
                )
            },
            {
                "overload.rs": (
                    rust_export("Java_com_example_Overload_ping__I")
                    + rust_export("Java_com_example_Overload_ping__J")
                    + rust_export(
                        "Java_com_example_Overload_ping__Ljava_lang_String_2"
                    )
                )
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(1, code, out)
        self.assertIn("without Java native declaration", out)

    def test_test_sources_are_not_scanned(self) -> None:
        root = self.build_tree(
            {"com/example/Plain.java": "package com.example;\npublic class Plain {\n}\n"},
            {"lib.rs": "pub mod engine;\n"},
            test_java_files={
                "com/example/PlainTest.java": "package com.example;\npublic class PlainTest {\n    private native void ghostNative();\n}\n"
            },
        )
        code, out = self.run_gate(root)
        self.assertEqual(0, code, out)
        self.assertEqual(0, self.verified_count(out))

    def test_allowlist_file_permits_documented_orphan(self) -> None:
        root = self.build_tree(
            {"com/example/Plain.java": "package com.example;\npublic class Plain {\n}\n"},
            {"jni_onload.rs": rust_export("JNI_OnLoad_placeholder")},
        )
        allowlist = self.tmp / "allowlist.txt"
        allowlist.write_text("# documented exception\nJNI_OnLoad_placeholder\n", encoding="utf-8")
        code, out = self.run_gate(root, ["--allowlist", str(allowlist)])
        self.assertEqual(0, code, out)
        self.assertEqual(0, self.verified_count(out))

    def test_real_repo_sources_pass(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(GATE)],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
        )
        out = proc.stdout + proc.stderr
        self.assertEqual(0, proc.returncode, out)
        self.assertNotIn("FAIL", out)
        self.assertGreaterEqual(self.verified_count(out), 30)

    def test_mutation_of_real_export_fails(self) -> None:
        copy = self.tmp / "repo-copy"
        (copy / "src").mkdir(parents=True)
        shutil.copytree(
            REPO_ROOT / "app" / "src" / "main" / "java" / "dev" / "notune" / "transcribe",
            copy / "app" / "src" / "main" / "java" / "dev" / "notune" / "transcribe",
        )
        for source in (REPO_ROOT / "src").glob("*.rs"):
            shutil.copy2(source, copy / "src" / source.name)

        code, out = self.run_gate(copy)
        self.assertEqual(0, code, out)
        self.assertGreaterEqual(self.verified_count(out), 30)

        target = copy / "src" / "recognize.rs"
        original = "Java_dev_notune_transcribe_RecognizeActivity_initNative"
        mutated = original + "X"
        text = target.read_text(encoding="utf-8")
        self.assertIn(original, text)
        target.write_text(text.replace(original, mutated), encoding="utf-8")

        code, out = self.run_gate(copy)
        self.assertEqual(1, code, out)
        self.assertIn(original, out)
        self.assertIn("without Rust export", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
