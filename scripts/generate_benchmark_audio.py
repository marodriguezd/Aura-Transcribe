#!/usr/bin/env python3
"""Generate the in-app English benchmark WAV from an original text fixture."""
from __future__ import annotations
import argparse
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRANSCRIPT = ROOT / "benchmark" / "fixtures" / "benchmark-transcript.txt"

def generate(output: Path) -> None:
    espeak = shutil.which("espeak")
    ffmpeg = shutil.which("ffmpeg")
    missing = [name for name, exe in (("espeak", espeak), ("ffmpeg", ffmpeg)) if exe is None]
    if missing:
        raise RuntimeError("Missing build tools: " + ", ".join(missing) + ". Install eSpeak and FFmpeg.")
    if not TRANSCRIPT.is_file():
        raise RuntimeError("Benchmark transcript is missing: " + str(TRANSCRIPT))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="aura-benchmark-") as work:
        raw = Path(work) / "speech-22050.wav"
        subprocess.run([espeak, "-v", "en-us", "-s", "155", "-a", "100", "-p", "48",
                        "-w", str(raw), "-f", str(TRANSCRIPT)], check=True, capture_output=True, text=True)
        subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw),
                        "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav", str(output)],
                       check=True, capture_output=True, text=True)
    with wave.open(str(output), "rb") as wav:
        fmt = (wav.getnchannels(), wav.getsampwidth(), wav.getframerate())
        frames, rate = wav.getnframes(), wav.getframerate()
        pcm = wav.readframes(frames)
        duration = frames / rate if rate else 0
    if fmt != (1, 2, 16000):
        raise RuntimeError("Generated sample must be mono, 16-bit PCM at 16000 Hz")
    if not 9.0 <= duration <= 12.0:
        raise RuntimeError("Generated sample duration is outside 9-12 seconds: %.2f" % duration)
    if not pcm or not any(pcm):
        raise RuntimeError("Generated WAV contains no non-zero audio samples")
    print("Generated synthetic-English speech WAV: %s (%.2f s)" % (output, duration))

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="output WAV path")
    args = parser.parse_args()
    try:
        generate(args.output)
    except (OSError, RuntimeError, subprocess.CalledProcessError, wave.Error) as exc:
        detail = getattr(exc, "stderr", None)
        parser.exit(1, "ERROR: could not generate benchmark WAV: %s\n" %
                    (detail.strip() if isinstance(detail, str) and detail.strip() else str(exc)))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
