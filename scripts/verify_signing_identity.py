#!/usr/bin/env python3
"""Independent verification of the release signing identity (2026-10 audit, Phase 3).

Compares one or more *observed* signer-certificate SHA-256 fingerprints (as
extracted by ``apksigner verify --print-certs`` or ``keytool -printcert``) against
the pinned trust anchor in ``keystore/release-cert.sha256``.

Design rules:
  * The expected fingerprint is NEVER derived from the artifact under test —
    that would be circular. It comes only from the committed trust anchor.
  * All observed fingerprints must be pinned. Multiple pinned entries are
    supported as a rotation transition window (documented policy decision made
    in a reviewed PR, never by weakening the workflow check).
  * Malformed input fails closed with a diagnosis; nothing is silently ignored.

Exit status: 0 when every observed fingerprint is pinned, 1 otherwise.
"""

import argparse
import re
import sys

HEX64_RE = re.compile(r"^[0-9a-f]{64}$")

DEFAULT_PINNED_PATH = "keystore/release-cert.sha256"


class PinnedAnchorError(Exception):
    """The pinned trust-anchor file is missing, empty or malformed."""


def normalize_fingerprint(raw):
    """Normalizes a hex SHA-256 fingerprint to bare lowercase hex.

    Accepts optional colons (keytool's ``AA:BB:...`` form) and mixed case.
    Raises ValueError when the value cannot be a SHA-256 fingerprint.
    """
    if raw is None:
        raise ValueError("fingerprint is None")
    value = raw.strip().replace(":", "").replace(" ", "").lower()
    if not HEX64_RE.match(value):
        raise ValueError(
            "not a 64-hex-digit SHA-256 fingerprint: %r" % (raw,)
        )
    return value


def load_pinned(path):
    """Loads the pinned fingerprints from ``path``.

    Returns a non-empty set of normalized fingerprints.
    Raises PinnedAnchorError when the file is missing, empty or malformed.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            raw_lines = handle.read().splitlines()
    except OSError as exc:
        raise PinnedAnchorError(
            "cannot read the pinned signing identity %r: %s" % (path, exc)
        )

    pinned = set()
    for lineno, line in enumerate(raw_lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        try:
            pinned.add(normalize_fingerprint(stripped))
        except ValueError as exc:
            raise PinnedAnchorError(
                "%s:%d: malformed pinned fingerprint: %s" % (path, lineno, exc)
            )
    if not pinned:
        raise PinnedAnchorError(
            "%s: no fingerprints pinned — refusing to accept any signer" % path
        )
    return pinned


def check(pinned, observed):
    """Verifies every observed fingerprint is pinned.

    ``pinned`` is a set of normalized fingerprints; ``observed`` an iterable of
    raw fingerprint strings (colons/case tolerated).

    Returns (ok, report_lines). Fails closed on malformed observed input.
    """
    report = []
    pinned = set(pinned)
    observed_list = list(observed)
    if not observed_list:
        return False, ["no observed fingerprints were provided"]

    ok = True
    normalized_observed = []
    for raw in observed_list:
        try:
            normalized_observed.append(normalize_fingerprint(raw))
        except ValueError as exc:
            ok = False
            report.append("malformed observed fingerprint: %s" % exc)

    for value in normalized_observed:
        if value in pinned:
            report.append("OK: observed signer certificate %s is pinned" % value)
        else:
            ok = False
            report.append(
                "MISMATCH: observed signer certificate %s is NOT pinned in the "
                "trust anchor" % value
            )

    distinct = sorted(set(normalized_observed))
    if len(distinct) > 1:
        ok = False
        report.append(
            "MISMATCH: the observed artifacts are signed by %d DIFFERENT "
            "certificates: %s" % (len(distinct), ", ".join(distinct))
        )
    if len(pinned) > 1:
        report.append(
            "note: the trust anchor pins %d fingerprints (rotation transition "
            "window); all of them are legitimate by policy" % len(pinned)
        )
    return ok, report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify observed release signer certificates against the "
        "pinned trust anchor."
    )
    parser.add_argument(
        "--pinned",
        default=DEFAULT_PINNED_PATH,
        help="path to the pinned fingerprints file (default: %s)" % DEFAULT_PINNED_PATH,
    )
    parser.add_argument(
        "--observed",
        action="append",
        default=[],
        metavar="SHA256",
        help="an observed signer-certificate SHA-256 (repeatable; colons and "
        "case are tolerated)",
    )
    args = parser.parse_args(argv)

    try:
        pinned = load_pinned(args.pinned)
    except PinnedAnchorError as exc:
        print("::error::%s" % exc)
        return 1

    ok, report = check(pinned, args.observed)
    for line in report:
        print(line)
    if not ok:
        print(
            "::error::release signing identity verification FAILED — the artifact "
            "signer does not match the pinned production certificate. If this is "
            "an intentional certificate rotation, update %s through a reviewed "
            "PR (see the file header) instead of bypassing this gate."
            % args.pinned
        )
        return 1
    print("OK: release signing identity verified against %s." % args.pinned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
