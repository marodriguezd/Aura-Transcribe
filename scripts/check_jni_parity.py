#!/usr/bin/env python3
"""JNI parity gate for Aura Transcribe.

Verifies both directions of the Java <-> Rust bridge:

* every ``native`` method declared under ``app/src/main/java`` has a matching
  exported symbol in ``src/**/*.rs``;
* every ``#[no_mangle]`` ``pub unsafe extern "system" fn Java_...`` export is
  consumed by a Java declaration.

Expected symbol (JNI short name): ``Java_`` + mangled package/class path
(``.`` -> ``_``, ``_`` -> ``_1``, ``$`` -> ``_00024`` for nested classes) +
``_`` + mangled method name. Overloaded native methods (same name twice in one
class) require the long form with a ``__<argtypes>`` suffix: the gate requires
at least one suffixed export per declaration, but deliberately does not
re-implement JNI argument-type mangling.

Fail-closed altitude: exports gated by a ``#[cfg(...)]`` that requires ``test``
do not count as shipped; and every ``native`` keyword in the stripped Java
source must parse into a declaration — otherwise the gate exits 1 instead of
silently verifying less than it scanned.

Exit status: 0 = parity, 1 = mismatch (CI red).
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Rust exports intentionally left without a Java ``native`` declaration. Keep
# empty unless a genuine exception exists (e.g. ``JNI_OnLoad``); every entry
# needs a one-line justification here.
ALLOWLIST: set[str] = set()


class GateError(Exception):
    """Structural problem (missing source tree) that must fail the gate."""


@dataclass(frozen=True)
class JavaDecl:
    symbol: str
    package: str
    class_path: str
    method: str
    file: str
    line: int


@dataclass(frozen=True)
class RustExport:
    name: str
    file: str
    line: int


PACKAGE_RE = re.compile(r"^\s*package\s+([\w.$]+)\s*;", re.MULTILINE)

NATIVE_KEYWORD_RE = re.compile(r"\bnative\b")
IDENTIFIER_TAIL_RE = re.compile(r"([A-Za-z_$][\w$]*)\s*$")

CLASS_RE = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")

# Matched against stripped code, where string literals (including the
# ``"system"`` ABI) are blanked; the ABI is verified against the original text
# slice so a signature inside a string literal can never become an export.
RUST_SIG_RE = re.compile(
    r"pub\s+(?:unsafe\s+)?extern\s+fn\s+(Java_[A-Za-z0-9_]+)\s*\("
)
RUST_ABI = '"system"'


def jni_mangle(name: str) -> str:
    """Mangle dotted class path or identifier into JNI short-name form."""
    out = []
    for ch in name:
        if ch == "_":
            out.append("_1")
        elif ch == "$":
            out.append("_00024")
        elif ch == ".":
            out.append("_")
        elif ch == "\u0000":
            out.append("_0")
        elif ord(ch) > 0x7F:
            out.append(f"_0{ord(ch):04x}")
        else:
            out.append(ch)
    return "".join(out)


def jni_symbol(package: str, class_path: str, method: str) -> str:
    full = f"{package}.{class_path}" if package else class_path
    return f"Java_{jni_mangle(full)}_{jni_mangle(method)}"


def strip_comments_and_strings(text: str) -> str:
    """Blank comments and literals; keeps every offset and newline intact."""
    out: list[str] = []
    i, n = 0, len(text)
    state = None  # None | line | block | string | char | textblock
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state is None:
            if ch == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                if text[i : i + 3] == '"""':
                    state = "textblock"
                    out.append("   ")
                    i += 3
                    continue
                state = "string"
                out.append(" ")
            elif ch == "'":
                state = "char"
                out.append(" ")
            else:
                out.append(ch)
        elif state == "line":
            if ch == "\n":
                state = None
                out.append("\n")
            else:
                out.append(" ")
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = None
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
        elif state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if ch == "\\":
                out.append("  " if i + 1 < n else " ")
                i += 2
                continue
            if ch == quote:
                state = None
                out.append(" ")
            else:
                out.append("\n" if ch == "\n" else " ")
                if ch == "\n":
                    state = None  # defensive: Java literals cannot span lines
        else:  # textblock
            if text[i : i + 3] == '"""':
                state = None
                out.append("   ")
                i += 3
                continue
            out.append("\n" if ch == "\n" else " ")
        i += 1
    return "".join(out)


def strip_rust_literals_and_comments(text: str) -> str:
    """Blank Rust comments and literals (block comments nest); keeps offsets.

    Strings must be blanked because ``#[no_mangle]`` inside a string literal
    must never be read as a real attribute.
    """
    out: list[str] = []
    i, n = 0, len(text)
    depth = 0  # block-comment nesting
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if depth > 0:
            if ch == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 2
                continue
            if ch == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if ch == "/" and nxt == "*":
            depth = 1
            out.append("  ")
            i += 2
            continue
        raw = re.match(r"(?:br|r)(?P<hashes>#*)\"", text[i:])
        if raw and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] == "_")):
            hashes = raw.group("hashes")
            close = text.find('"' + hashes, i + raw.end())
            end = n if close < 0 else close + len(hashes) + 1
            for k in range(i, end):
                out.append("\n" if text[k] == "\n" else " ")
            i = end
            continue
        if ch == '"':
            out.append(" ")
            i += 1
            while i < n:
                current = text[i]
                if current == "\\":
                    out.append("  " if i + 1 < n else " ")
                    i += 2
                    continue
                if current == '"':
                    out.append(" ")
                    i += 1
                    break
                out.append("\n" if current == "\n" else " ")
                i += 1
            continue
        if ch == "'":
            after = text[i + 1] if i + 1 < n else ""
            if (after.isalpha() or after == "_") and i + 2 < n and text[i + 2] != "'":
                out.append("'")  # lifetime like 'a / 'static / '_
                i += 1
                continue
            j = i + 1
            closed = False
            while j < n:
                current = text[j]
                if current == "\\":
                    j += 2
                    continue
                if current == "'":
                    j += 1
                    closed = True
                    break
                if current == "\n":
                    break
                j += 1
            if closed:
                for k in range(i, j):
                    out.append(" ")
                i = j
                continue
            out.append("'")
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _matching_paren(code: str, open_paren: int) -> int:
    depth = 0
    for i in range(open_paren, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _brace_pairs(code: str) -> dict[int, int]:
    stack: list[int] = []
    pairs: dict[int, int] = {}
    for i, ch in enumerate(code):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            pairs[stack.pop()] = i
    return pairs


def _split_top_level(text: str) -> list[str]:
    parts: list[str] = []
    depth, start = 0, 0
    for i, ch in enumerate(text):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
    parts.append(text[start:])
    return parts


def cfg_requires_test(expr: str) -> bool:
    """True when ``#[cfg(expr)]`` is only compiled with ``cfg(test)``.

    ``test`` counts as required when it appears positively (``cfg(test)``,
    ``cfg(any(test, feature = "x"))``, ``cfg(all(test, ...))``); a ``not(...)``
    wrapper always means the item is NOT test-only (``cfg(not(test))`` ships in
    normal builds).
    """
    expr = expr.strip()
    if not expr:
        return False
    call = re.match(r"(not|any|all)\s*\(", expr)
    if call and expr.endswith(")"):
        inner = expr[call.end() : -1]
        if call.group(1) == "not":
            return False
        return any(cfg_requires_test(part) for part in _split_top_level(inner))
    return expr == "test"


def _item_scope_end(code: str, pos: int, brace_pairs: dict[int, int]) -> int:
    """End offset of the item that follows ``pos`` (body close or ``;``)."""
    depth = 0
    i = pos
    while i < len(code):
        ch = code[i]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            if depth > 0:
                depth -= 1
        elif ch == ";" and depth == 0:
            return i
        elif ch == "{" and depth == 0:
            return brace_pairs.get(i, i)
        elif ch == "}" and depth == 0:
            return i
        i += 1
    return len(code) - 1


def test_cfg_scopes(code: str) -> list[tuple[int, int]]:
    """(start, end) spans whose item is gated by a test-requiring cfg."""
    brace_pairs = _brace_pairs(code)
    scopes: list[tuple[int, int]] = []
    for match in re.finditer(r"#\[\s*cfg\s*\(", code):
        close = _matching_paren(code, match.end() - 1)
        if close < 0 or not cfg_requires_test(code[match.end() : close]):
            continue
        attr_end = code.find("]", close)
        if attr_end < 0:
            attr_end = close
        scopes.append((match.start(), _item_scope_end(code, attr_end + 1, brace_pairs)))
    return scopes


def class_frames(code: str) -> list[tuple[str, int, int]]:
    """(simple name, body start, body end) for every type body in the file."""
    frames: list[tuple[str, int, int]] = []
    stack: list[tuple[str, int, int]] = []  # (name, start, brace depth)
    pending: str | None = None
    depth = 0
    for match in re.finditer(r"[{}]|" + CLASS_RE.pattern, code):
        token = match.group(0)
        if token == "{":
            if pending is not None:
                stack.append((pending, match.start(), depth))
                pending = None
            depth += 1
        elif token == "}":
            depth -= 1
            if stack and stack[-1][2] == depth:
                name, start, _ = stack.pop()
                frames.append((name, start, match.end()))
        else:
            pending = match.group(1)
    return frames


def enclosing_class(offset: int, frames: list[tuple[str, int, int]]) -> str | None:
    chain = sorted(
        (f for f in frames if f[1] <= offset <= f[2]), key=lambda f: f[1]
    )
    if not chain:
        return None
    return "$".join(f[0] for f in chain)


def line_of(code: str, offset: int) -> int:
    return code.count("\n", 0, offset) + 1


def relative(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def parse_native_declaration(code: str, native_pos: int) -> tuple[str, int] | None:
    """Parse the declaration whose ``native`` keyword starts at ``native_pos``.

    Returns ``(method_name, end_offset)`` or ``None`` when the token does not
    form a declaration this gate understands. Tolerates modifiers and
    annotation tokens between ``native`` and the return type
    (``private native @Deprecated void foo();``), generic/array return types,
    annotation arguments in the parameter list and a ``throws`` clause. A
    declaration whose signature is split across lines is rejected on purpose:
    the caller's keyword cross-check then fails closed instead of verifying
    less than it scanned.
    """
    i = native_pos + len("native")
    n = len(code)
    depth = 0
    end = None
    for j in range(i, n):
        ch = code[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return None
            depth -= 1
        elif ch == ";" and depth == 0:
            end = j
            break
        elif ch == "\n" and depth == 0:
            return None
    if end is None:
        return None
    segment = code[i:end]
    # The parameter list is the last top-level '(' before the ';'; annotation
    # parentheses that precede it do not survive as the last match.
    param_paren = None
    depth = 0
    for k, ch in enumerate(segment):
        if ch == "(":
            if depth == 0:
                param_paren = k
            depth += 1
        elif ch == ")":
            if depth == 0:
                return None
            depth -= 1
    if param_paren is None or depth != 0:
        return None
    name_match = IDENTIFIER_TAIL_RE.search(segment[:param_paren])
    if name_match is None:
        return None
    return name_match.group(1), end


def scan_java(root: Path) -> tuple[list[JavaDecl], list[tuple[str, int]]]:
    java_root = root / "app" / "src" / "main" / "java"
    if not java_root.is_dir():
        raise GateError(f"Java source tree not found: {java_root}")
    java_files = sorted(java_root.rglob("*.java"))
    if not java_files:
        raise GateError(f"no .java files under {java_root}")
    decls: list[JavaDecl] = []
    unparsed: list[tuple[str, int]] = []
    for path in java_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        code = strip_comments_and_strings(text)
        pkg_match = PACKAGE_RE.search(code)
        package = pkg_match.group(1) if pkg_match else ""
        frames = class_frames(code)
        consumed_until = -1
        for match in NATIVE_KEYWORD_RE.finditer(code):
            if match.start() < consumed_until:
                continue
            parsed = parse_native_declaration(code, match.start())
            if parsed is None:
                unparsed.append((relative(path, root), line_of(code, match.start())))
                continue
            method, end = parsed
            consumed_until = end
            class_path = enclosing_class(match.start(), frames)
            if class_path is None:
                raise GateError(f"native declaration outside a class: {path}")
            decls.append(
                JavaDecl(
                    symbol=jni_symbol(package, class_path, method),
                    package=package,
                    class_path=class_path,
                    method=method,
                    file=relative(path, root),
                    line=line_of(code, match.start()),
                )
            )
    return decls, unparsed


def scan_rust(root: Path) -> list[RustExport]:
    src_root = root / "src"
    if not src_root.is_dir():
        raise GateError(f"Rust source tree not found: {src_root}")
    exports: list[RustExport] = []
    for path in sorted(src_root.rglob("*.rs")):
        text = path.read_text(encoding="utf-8", errors="replace")
        code = strip_rust_literals_and_comments(text)
        scopes = test_cfg_scopes(code)
        for match in RUST_SIG_RE.finditer(code):
            if RUST_ABI not in text[match.start() : match.end()]:
                continue
            # Test-only code is not shipped: a Java declaration that can only
            # be satisfied by a cfg(test) export is a real UnsatisfiedLinkError.
            if any(start <= match.start() <= end for start, end in scopes):
                continue
            # The symbol only exists if the attribute sits between the previous
            # item's closing brace and this signature (literals are stripped).
            window = code[code.rfind("}", 0, match.start()) + 1 : match.start()]
            if "#[no_mangle]" not in window:
                continue
            exports.append(
                RustExport(
                    name=match.group(1),
                    file=relative(path, root),
                    line=line_of(code, match.start()),
                )
            )
    return exports


def load_allowlist(path: Path | None) -> set[str]:
    entries = set(ALLOWLIST)
    if path is not None:
        if not path.is_file():
            raise GateError(f"allowlist file not found: {path}")
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                entries.add(line)
    return entries


def run_gate(root: Path, allowlist_path: Path | None = None) -> int:
    try:
        decls, unparsed = scan_java(root)
        exports = scan_rust(root)
        allowlist = load_allowlist(allowlist_path)
    except GateError as exc:
        print(f"ERROR: {exc}")
        print("FAIL: JNI parity gate could not run")
        return 1

    print(
        f"Scanned {len(decls)} Java native declaration(s) "
        f"and {len(exports)} Rust JNI export(s)."
    )

    errors: list[str] = []

    # Fail closed: a `native` keyword the parser did not turn into a
    # declaration must never be silently skipped (that is how a declaration
    # gap would smuggle an UnsatisfiedLinkError past this gate).
    if unparsed:
        errors.append(
            f"ERROR: {len(unparsed)} unparsed `native` declaration(s) — "
            f"extend the gate parser"
        )
        for file_name, line in sorted(unparsed):
            errors.append(
                f"ERROR: {file_name}:{line}: `native` token without a parsed "
                f"declaration"
            )

    export_names = {export.name for export in exports}
    expected = {decl.symbol for decl in decls}

    # Overloaded methods: javac/JNI requires the __<argtypes> long form, the
    # short symbol is not linkable. Group by declaring class + method name.
    groups: dict[tuple[str, str, str], list[JavaDecl]] = {}
    for decl in decls:
        groups.setdefault((decl.package, decl.class_path, decl.method), []).append(decl)

    consumed: set[str] = {symbol for symbol in expected if symbol in export_names}
    for key, members in sorted(groups.items()):
        if len(members) < 2:
            continue
        package, class_path, method = key
        prefix = jni_symbol(package, class_path, method) + "__"
        matching = sorted(name for name in export_names if name.startswith(prefix))
        # One suffixed export per overloaded declaration is the minimum; extra
        # matching exports are NOT consumed, so they surface as orphans below.
        consumed.update(matching[: len(members)])
        if len(matching) < len(members):
            full = f"{package}.{class_path}" if package else class_path
            errors.append(
                f"ERROR: overloaded native method {full}.{method} has "
                f"{len(members)} declaration(s) but only {len(matching)} "
                f"__-suffixed JNI export(s) ({prefix}<argtypes>)"
            )

    for decl in sorted(decls, key=lambda d: (d.file, d.line)):
        if decl.symbol in export_names:
            continue
        if (decl.package, decl.class_path, decl.method) in groups:
            key = (decl.package, decl.class_path, decl.method)
            if len(groups[key]) >= 2:
                continue  # covered by the overload decision above
        errors.append(
            f"ERROR: Java native declaration without Rust export: "
            f"{decl.symbol} ({decl.file}:{decl.line})"
        )

    for export in sorted(exports, key=lambda e: (e.file, e.line)):
        if export.name in consumed or export.name in allowlist:
            continue
        errors.append(
            f"ERROR: Rust export without Java native declaration: "
            f"{export.name} ({export.file}:{export.line})"
        )

    if errors:
        for message in errors:
            print(message)
        print(f"FAIL: {len(errors)} mismatch(es)")
        return 1

    print(f"OK ({len(decls)} declarations verified)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="repository root to scan (default: this script's repo root)",
    )
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=None,
        help=(
            "file with one Rust JNI export name per line that may exist without "
            "a Java native declaration (# comments allowed)"
        ),
    )
    args = parser.parse_args(argv)
    return run_gate(args.root, args.allowlist)


if __name__ == "__main__":
    sys.exit(main())
