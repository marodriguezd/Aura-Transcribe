#!/usr/bin/env python3
"""Produce a license inventory from Cargo's resolved dependency graph.

This reports license expressions declared by packages in the resolved graph.
It is intentionally strict about missing declarations: a package with neither
`license` nor `license-file` metadata fails the command rather than silently
passing an incomplete inventory.

Two validation levels are available:

* By default, packages whose `license` expression contains an identifier that
  is not in the audited allowlist are reported under "Unrecognized license
  expressions" and the command still succeeds, so the report remains a useful
  review artifact. Placeholder declarations (``NOASSERTION``, ``UNKNOWN``,
  ``NONE``, ``UNLICENSED``) are always treated as unrecognized.
* With ``--fail-on-unknown`` those entries make the command exit non-zero.
  This is the mode to enable once every identifier has been reviewed.

``--allowed-license`` extends the allowlist for identifiers that have been
reviewed and accepted. The default allowlist is the reviewed set for this
repository's Cargo graph plus common permissive licenses; it is a gate
configuration, not a legal determination. The report counts are always
computed from the resolved graph, never hard-coded.

The classification helpers are pure and covered by
``scripts/test_report_cargo_licenses.py`` (no network or Cargo invocation).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

LICENSE_OPERATORS = frozenset({"AND", "OR", "WITH"})
UNRECOGNIZED_LICENSE_MARKERS = frozenset(
    {"NOASSERTION", "UNKNOWN", "UNLICENSED", "NONE"}
)

# SPDX identifiers accepted for the repository's reviewed Cargo graph plus
# common permissive identifiers. Extend via --allowed-license after review.
#
# `GPL-3.0-only` deliberately has no entry here (2026-10 definitive licensing
# decision: the application stays MIT and never migrates to GPL-3.0-only; see
# LICENSE_AUDIT.md). A pre-approved GPL identifier would let a copyleft
# dependency silently pass the inventory and conflict with the MIT grant over
# the combined binary; such an expression must surface as "unrecognized" and be
# reviewed explicitly instead.
AUDITED_LICENSE_IDS = frozenset({
    "0BSD",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "BSL-1.0",
    "CC0-1.0",
    "ISC",
    "MIT",
    "MIT-0",
    "MPL-2.0",
    "Unicode-3.0",
    "Unlicense",
    "Zlib",
})


def license_tokens(expression: str) -> set[str]:
    """Return the SPDX-like identifiers in a Cargo license expression."""
    return {
        token
        for token in re.split(r"[^A-Za-z0-9.+-]+", expression)
        if token and token not in LICENSE_OPERATORS
    }


def classify_license(
    license_expr: str | None,
    license_file: str | None,
    known_licenses: frozenset[str] | set[str],
) -> str:
    """Classify a package declaration.

    Returns ``missing`` (neither field), ``license-file`` (only a file),
    ``unknown`` (an unrecognized expression or placeholder) or ``declared``.
    """
    if license_expr:
        tokens = license_tokens(license_expr)
        if (
            not tokens
            or tokens & UNRECOGNIZED_LICENSE_MARKERS
            or not tokens <= known_licenses
        ):
            return "unknown"
        return "declared"
    if license_file:
        return "license-file"
    return "missing"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--fail-on-unknown",
        action="store_true",
        help="exit non-zero when a license expression is not in the allowlist",
    )
    parser.add_argument(
        "--allowed-license",
        action="append",
        default=[],
        metavar="ID",
        help="add a reviewed SPDX identifier to the allowlist (repeatable)",
    )
    args = parser.parse_args()

    allowed_licenses = AUDITED_LICENSE_IDS | set(args.allowed_license)

    result = subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--locked"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        return result.returncode

    try:
        metadata = json.loads(result.stdout)
        resolved = metadata["resolve"]["nodes"]
        packages = {package["id"]: package for package in metadata["packages"]}
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        print(f"Invalid cargo metadata response: {exc}", file=sys.stderr)
        return 2

    resolved_ids = {node["id"] for node in resolved}
    for member in metadata.get("workspace_members", []):
        resolved_ids.add(member)

    entries = []
    missing = []
    unknown = []
    for package_id in sorted(resolved_ids):
        package = packages.get(package_id)
        if package is None:
            missing.append((package_id, "package details missing from cargo metadata"))
            continue

        license_expr = package.get("license")
        license_file = package.get("license_file")
        status = classify_license(license_expr, license_file, allowed_licenses)
        if status == "missing":
            license_info = "MISSING LICENSE METADATA"
            missing.append((f'{package["name"]} {package["version"]}', package_id))
        elif status == "unknown":
            detail = str(license_expr).replace("|", "\\|").replace("\n", " ")
            license_info = f"UNRECOGNIZED LICENSE EXPRESSION: {detail}"
            unknown.append((f'{package["name"]} {package["version"]}', detail))
        elif status == "license-file":
            license_info = f"License file: {license_file}".replace("|", "\\|")
        else:
            license_info = license_expr.replace("|", "\\|").replace("\n", " ")

        source = package.get("source") or "workspace"
        repository = package.get("repository") or package.get("homepage") or ""
        entries.append({
            "name": package["name"],
            "version": package["version"],
            "source": source,
            "license": license_info,
            "repository": repository,
            "package_id": package_id,
        })

    report_path = args.output
    report_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Rust dependency license inventory",
        "",
        "Generated by `scripts/report_cargo_licenses.py` using `cargo metadata --locked`.",
        "",
        "This inventory covers packages in Cargo's resolved dependency graph, including transitive and target-specific entries recorded by Cargo. License strings are package metadata, not a legal determination; inspect each license file for redistribution obligations.",
        "",
        f"Resolved package entries: **{len(entries)}**",
        f"Entries missing both `license` and `license-file` metadata: **{len(missing)}**",
        f"Entries with unrecognized license expressions: **{len(unknown)}**",
        "",
        "| Package | Version | Declared license / file | Source |",
        "| --- | --- | --- | --- |",
    ]
    for entry in entries:
        source = entry["source"].replace("|", "\\|")
        repo_url = entry["repository"]
        name = entry["name"]
        if repo_url:
            name = f'[{name}]({repo_url.replace(")", "%29")})'
        lines.append(
            f'| {name} | {entry["version"]} | {entry["license"]} | {source} |'
        )

    if missing:
        lines.extend([
            "",
            "## Missing license metadata",
            "",
            "These entries need manual source-level license verification before release:",
            "",
        ])
        lines.extend(f"- `{name}` — `{detail}`" for name, detail in missing)

    if unknown:
        lines.extend([
            "",
            "## Unrecognized license expressions",
            "",
            "Review these declarations and add the reviewed identifier with "
            "`--allowed-license`, or replace the dependency:",
            "",
        ])
        lines.extend(f"- `{name}` — `{detail}`" for name, detail in unknown)

    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote Cargo license inventory to {report_path}")
    print(
        f"Resolved entries: {len(entries)}; missing license metadata: {len(missing)}; "
        f"unrecognized license expressions: {len(unknown)}"
    )

    if missing:
        print("Cargo license inventory is incomplete; review the generated report.", file=sys.stderr)
        return 1
    if unknown and args.fail_on_unknown:
        print(
            "Cargo license inventory contains unrecognized license expressions "
            "and --fail-on-unknown is set.",
            file=sys.stderr,
        )
        return 1
    if unknown:
        print(
            "Cargo license inventory contains unrecognized license expressions; "
            "review the generated report or pass --fail-on-unknown to enforce it.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
