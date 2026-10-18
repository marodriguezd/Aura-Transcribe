#!/usr/bin/env python3
"""Translation parity gate for android_transcribe_app.

Mirrors Handy-Android's `scripts/check-translations.ts` philosophy (AGENTS.md
§4.2 / i18n rule: mark non-translatable strings with `translatable="false"`).
Every string in the base `values/strings.xml` that is *translatable* must also
appear in each `values-XX/strings.xml` locale. Intentionally-untranslated
strings (`translatable="false"`) are excluded, so they are not reported as gaps.

Exit status: 0 = all locales complete, 1 = at least one gap (CI red).
"""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"


import re

PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[sdf]|\{[^}]+\}")


def trans_values(path):
    root = ET.parse(path).getroot()
    values = {}
    for s in root.findall("string"):
        name = s.get("name")
        if name is None:
            continue
        trans = s.get("translatable", "true")
        if trans in ("false", "0"):
            continue
        values[name] = "".join(s.itertext())
    return values


def trans_names(path):
    return list(trans_values(path).keys())


def placeholders_of(text):
    return sorted(PLACEHOLDER_RE.findall(text or ""))


def main():
    base = RES / "values" / "strings.xml"
    base_values = trans_values(base)
    base_names = sorted(set(base_values.keys()))
    print(f"base translatable strings: {len(base_names)}")

    # Locale dirs look like values-de, values-es-rES. Configuration qualifiers
    # such as values-night (dark theme) or values-sw600dp are NOT locales and
    # must be skipped — they legitimately lack strings.xml.
    locale_re = re.compile(r"^values-[a-z]{2}(-r[A-Z]{2})?$")
    locales = sorted(
        p for p in RES.iterdir()
        if p.is_dir() and locale_re.match(p.name)
    )
    status = 0
    for loc in locales:
        f = loc / "strings.xml"
        if not f.exists():
            print(f"FAIL {loc.name}: missing strings.xml")
            status = 1
            continue
        cur_values = trans_values(f)
        names = sorted(set(cur_values.keys()))
        missing = sorted(set(base_names) - set(names))
        extra = sorted(set(names) - set(base_names))
        blanks = sorted(n for n, v in cur_values.items() if not (v or "").strip())
        echo_keys = sorted(
            n for n in names
            if n in base_values and (cur_values[n] or "").strip() == (base_values[n] or "").strip() and len((base_values[n] or "").strip()) > 1
        )
        ph_mismatch = sorted(
            n for n in names
            if n in base_values and placeholders_of(cur_values[n]) != placeholders_of(base_values[n])
        )
        hard_fail = bool(missing or extra or blanks or ph_mismatch)
        if not hard_fail and not echo_keys:
            print(f"ok   {loc.name}: all {len(names)} translatable strings present")
        else:
            print(f"{'FAIL' if hard_fail else 'WARN'} {loc.name}:")
            if missing:
                print("  missing:")
                for m in missing:
                    print(f"    - {m}")
            if extra:
                print("  extra (not in base):")
                for e in extra:
                    print(f"    - {e}")
            if blanks:
                print("  blank values:")
                for b in blanks[:10]:
                    print(f"    - {b}")
            if ph_mismatch:
                print("  placeholder mismatch vs base:")
                for p in ph_mismatch[:10]:
                    print(f"    - {p}: base={placeholders_of(base_values[p])} cur={placeholders_of(cur_values[p])}")
            if echo_keys:
                print("  WARN untranslated copies of English (review, not fatal):")
                for u in echo_keys[:10]:
                    print(f"    - {u}")
            if hard_fail:
                status = 1

    print("-" * 60)
    if status:
        print(f"\n[CHECK-TRANSLATIONS] FAIL: parity gap in {len(locales)} locale(s)")
        sys.exit(1)
    print(f"\n[CHECK-TRANSLATIONS] PASS: all {len(locales)} locales complete")
    sys.exit(0)


if __name__ == "__main__":
    main()
