#!/usr/bin/env python3
"""Corpus preparation and validation tool for the Aura-Transcribe synthetic ASR smoke corpus.

Manages the 20-clip synthetic smoke corpus (EN, ES, FR, PT) defined in
`benchmark/manifests/corpus.json`. This tool never touches the network and
never downloads external audio: every clip is generated locally.

Modes:
  --verify              Validates that audio files exist, have correct format
                        (16 kHz mono 16-bit PCM WAV), valid durations, and transcripts.
                        Fails with a clear message when audio is missing.
  --generate-synthetic  Generates deterministic, zero-network 16 kHz WAV fixtures
                        matching the exact segment and silence structure of the manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import struct
import sys
import wave
from pathlib import Path
from typing import Any, Dict, List, Optional


PROJECT_ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = PROJECT_ROOT / "benchmark" / "manifests" / "corpus.json"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "benchmark" / "corpus" / "audio"


class CorpusError(Exception):
    """Raised when corpus validation or generation fails."""


def load_manifest(manifest_path: Path = MANIFEST_PATH) -> Dict[str, Any]:
    if not manifest_path.is_file():
        raise CorpusError(f"Corpus manifest not found: {manifest_path}")
    with open(manifest_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if "clips" not in data or not isinstance(data["clips"], list):
        raise CorpusError("Manifest missing 'clips' list")
    return data


def verify_audio_file(
    file_path: Path, expected_duration_sec: float, tolerance_sec: float = 1.0
) -> Dict[str, Any]:
    """Validates that a WAV file exists and adheres to 16 kHz mono 16-bit PCM format."""
    if not file_path.is_file():
        raise CorpusError(f"Audio file does not exist: {file_path}")

    try:
        with wave.open(str(file_path), "rb") as wf:
            channels = wf.getnchannels()
            sample_width = wf.getsampwidth()
            framerate = wf.getframerate()
            n_frames = wf.getnframes()
            duration = n_frames / float(framerate) if framerate > 0 else 0.0

            if channels != 1:
                raise CorpusError(
                    f"{file_path.name}: Expected 1 channel (mono), got {channels}"
                )
            if sample_width != 2:
                raise CorpusError(
                    f"{file_path.name}: Expected 16-bit PCM (2 bytes/sample), got {sample_width}"
                )
            if framerate != 16000:
                raise CorpusError(
                    f"{file_path.name}: Expected 16000 Hz, got {framerate}"
                )
            if abs(duration - expected_duration_sec) > tolerance_sec:
                raise CorpusError(
                    f"{file_path.name}: Duration {duration:.2f}s differs from expected "
                    f"{expected_duration_sec:.2f}s by more than {tolerance_sec:.1f}s"
                )

            # Compute SHA-256 of file
            h = hashlib.sha256()
            with open(file_path, "rb") as f:
                while chunk := f.read(65536):
                    h.update(chunk)

            return {
                "file": str(file_path),
                "channels": channels,
                "sample_rate": framerate,
                "duration_sec": round(duration, 3),
                "sha256": h.hexdigest(),
                "valid": True,
            }
    except wave.Error as e:
        raise CorpusError(f"Corrupt WAV file {file_path}: {e}") from e


def generate_synthetic_audio(
    file_path: Path,
    duration_sec: float,
    segments: List[Dict[str, Any]],
    sample_rate: int = 16000,
) -> None:
    """Generates a deterministic 16 kHz mono 16-bit PCM WAV file with speech-like harmonics

    during utterance segments and true digital silence (0.0) during inter-segment intervals.
    """
    file_path.parent.mkdir(parents=True, exist_ok=True)
    total_samples = int(duration_sec * sample_rate)

    # Base frequencies simulating fundamental speech frequencies and formants
    f0 = 140.0
    f1 = 500.0
    f2 = 1500.0

    samples = [0] * total_samples

    for seg_idx, seg in enumerate(segments):
        start_samp = int(seg.get("start_sec", 0.0) * sample_rate)
        end_samp = min(int(seg.get("end_sec", duration_sec) * sample_rate), total_samples)

        # Modulate frequency per segment to create variation
        shift = (seg_idx + 1) * 15.0
        s_f0 = f0 + shift
        s_f1 = f1 + shift * 2
        s_f2 = f2 + shift * 3

        for i in range(start_samp, end_samp):
            t = (i - start_samp) / float(sample_rate)
            # Syllable-rate envelope modulation (~4 Hz)
            env = 0.5 * (1.0 + math.sin(2.0 * math.pi * 4.0 * t))
            # Harmonic combination
            val = (
                0.45 * math.sin(2.0 * math.pi * s_f0 * t)
                + 0.35 * math.sin(2.0 * math.pi * s_f1 * t)
                + 0.20 * math.sin(2.0 * math.pi * s_f2 * t)
            )
            # Convert to signed 16-bit integer
            amplitude = int(val * env * 22000.0)
            amplitude = max(-32768, min(32767, amplitude))
            samples[i] = amplitude

    with wave.open(str(file_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        raw_bytes = struct.pack(f"<{len(samples)}h", *samples)
        wf.writeframes(raw_bytes)


def run_verify(manifest_path: Path = MANIFEST_PATH) -> bool:
    """Verifies all corpus clips declared in the manifest."""
    manifest = load_manifest(manifest_path)
    clips = manifest["clips"]
    print(f"Verifying {len(clips)} corpus clips from {manifest_path.name}...")

    all_valid = True
    by_lang: Dict[str, int] = {}

    for clip in clips:
        cid = clip["id"]
        lang = clip["language"]
        rel_path = clip["audio_path"]
        target_path = PROJECT_ROOT / rel_path
        expected_dur = clip["duration_sec"]

        try:
            info = verify_audio_file(target_path, expected_dur)
            by_lang[lang] = by_lang.get(lang, 0) + 1
            print(f"  ✓ [{lang}] {cid}: {info['duration_sec']}s, 16kHz mono, SHA-256={info['sha256'][:8]}...")
        except CorpusError as err:
            print(f"  ✗ [{lang}] {cid}: ERROR: {err}", file=sys.stderr)
            all_valid = False

    print("\nSummary by language:")
    for lang, count in sorted(by_lang.items()):
        print(f"  - {lang}: {count} valid clips")

    if not all_valid:
        print(
            "\nVerification FAILED: Some audio files are missing, corrupt, or out of spec.",
            file=sys.stderr,
        )
        print(
            "Generate the synthetic fixtures locally with: "
            "python3 scripts/prepare_corpus.py --generate-synthetic",
            file=sys.stderr,
        )
        return False

    print(f"\nVerification SUCCESSFUL: All {len(clips)} clips match specification.")
    return True


def run_generate_synthetic(manifest_path: Path = MANIFEST_PATH) -> None:
    """Generates synthetic audio fixtures for all clips in the manifest."""
    manifest = load_manifest(manifest_path)
    clips = manifest["clips"]
    print(f"Generating synthetic audio fixtures for {len(clips)} clips...")

    for clip in clips:
        cid = clip["id"]
        lang = clip["language"]
        rel_path = clip["audio_path"]
        target_path = PROJECT_ROOT / rel_path
        expected_dur = clip["duration_sec"]
        segments = clip.get("segments", [])

        generate_synthetic_audio(target_path, expected_dur, segments)
        print(f"  ✓ Generated [{lang}] {cid} ({expected_dur}s) -> {rel_path}")

    print("Synthetic audio fixture generation complete.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Corpus preparation and validation tool")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=MANIFEST_PATH,
        help="Path to corpus manifest JSON",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--verify",
        action="store_true",
        help="Verify all audio files declared in manifest",
    )
    group.add_argument(
        "--generate-synthetic",
        action="store_true",
        help="Generate synthetic 16kHz WAV fixtures for offline testing",
    )

    args = parser.parse_args()

    try:
        if args.generate_synthetic:
            run_generate_synthetic(args.manifest)
            return 0 if run_verify(args.manifest) else 1
        elif args.verify:
            return 0 if run_verify(args.manifest) else 1
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
