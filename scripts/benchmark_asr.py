#!/usr/bin/env python3
"""Aura-Transcribe: ASR smoke-test runner (SIMULATED synthetic backend).

Exercises the reporting pipeline (aggregation, Pareto classification, JSON/CSV/
Markdown export) against the synthetic smoke corpus. With the default
`synthetic` backend every metric is generated locally: this is a smoke test,
NOT an accuracy benchmark, and its output must never be presented as real
WER, RTF, memory, or model-quality data.

Supports:
  - Backends: 'synthetic' (SIMULATED, zero-network) and 'adb' (placeholder
    stub; device inference is not implemented yet)
  - Inference modes: 'streaming' (simulated 80 ms chunk cadence) and 'offline'
  - Dual-track text scoring (normalized and raw verbatim), reported as simulated
  - Pareto classification of the reported telemetry; NO automatic quantization
    recommendation is produced
"""

from __future__ import annotations

import argparse
import csv
import datetime
import hashlib
import json
import math
import os
import random
import re
import string
import subprocess
import sys
import time
import unicodedata
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODELS_MANIFEST = PROJECT_ROOT / "benchmark" / "manifests" / "models.json"
DEFAULT_CORPUS_MANIFEST = PROJECT_ROOT / "benchmark" / "manifests" / "corpus.json"
DEFAULT_MODELS_DIR = PROJECT_ROOT / "benchmark" / "models"
DEFAULT_CORPUS_DIR = PROJECT_ROOT / "benchmark" / "corpus" / "audio"
DEFAULT_RESULTS_DIR = PROJECT_ROOT / "benchmark" / "results"


# ---------------------------------------------------------------------------
# Text Normalization & Metric Calculation
# ---------------------------------------------------------------------------

def normalize_multilingual_text(text: str) -> str:
    """Standardized multilingual ASR normalizer.

    1. Unicode NFC normalization.
    2. Lowercasing.
    3. Normalizes typographic quotes/apostrophes to standard single quote.
    4. Strips non-contracted punctuation (periods, commas, question/exclamation marks,
       brackets, symbols) while preserving internal apostrophes (e.g. l', d', it's, don't),
       hyphens inside compound words, letters with accents/diacritics, and digits.
    5. Collapses whitespace.
    """
    if not text:
        return ""

    # 1. Unicode NFC
    s = unicodedata.normalize("NFC", text)

    # 2. Lowercase
    s = s.lower()

    # 3. Normalize quotes and dashes
    s = s.replace("’", "'").replace("`", "'").replace("´", "'")
    s = s.replace("—", " ").replace("–", " ")

    # 4. Remove punctuation but keep letters, digits, whitespace, and internal apostrophes/hyphens
    # Inverted Spanish marks
    s = s.replace("¿", "").replace("¡", "")
    # Punctuation to remove
    punct_to_strip = '.,;:?!"()[]{}«»<>*&%$#@~^+=/\\|'
    for ch in punct_to_strip:
        s = s.replace(ch, " ")

    # Remove isolated apostrophes or hyphens
    tokens = []
    for token in s.split():
        # Strip leading/trailing hyphens or apostrophes
        clean_tok = token.strip("'-_")
        if clean_tok:
            tokens.append(clean_tok)

    return " ".join(tokens)


def compute_levenshtein(seq1: List[str], seq2: List[str]) -> Tuple[int, int, int, int]:
    """Computes Levenshtein edit distance between two sequences.

    Returns:
      (substitutions, deletions, insertions, hits)
    """
    n, m = len(seq1), len(seq2)
    # dp[i][j] = (dist, sub, dele, ins)
    dp = [[(0, 0, 0, 0) for _ in range(m + 1)] for _ in range(n + 1)]

    for i in range(1, n + 1):
        dp[i][0] = (i, 0, i, 0)  # i deletions
    for j in range(1, m + 1):
        dp[0][j] = (j, 0, 0, j)  # j insertions

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if seq1[i - 1] == seq2[j - 1]:
                cost = 0
                step_sub = 0
            else:
                cost = 1
                step_sub = 1

            sub_cand = (
                dp[i - 1][j - 1][0] + cost,
                dp[i - 1][j - 1][1] + step_sub,
                dp[i - 1][j - 1][2],
                dp[i - 1][j - 1][3],
            )
            del_cand = (
                dp[i - 1][j][0] + 1,
                dp[i - 1][j][1],
                dp[i - 1][j][2] + 1,
                dp[i - 1][j][3],
            )
            ins_cand = (
                dp[i][j - 1][0] + 1,
                dp[i][j - 1][1],
                dp[i][j - 1][2],
                dp[i][j - 1][3] + 1,
            )

            dp[i][j] = min(sub_cand, del_cand, ins_cand, key=lambda x: x[0])

    dist, subs, dels, inss = dp[n][m]
    hits = n - (subs + dels)
    return subs, dels, inss, hits


def calculate_wer_cer(
    ref_text: str, hyp_text: str, normalize: bool = True
) -> Dict[str, Any]:
    """Computes Word Error Rate (WER) and Character Error Rate (CER)."""
    if normalize:
        ref_clean = normalize_multilingual_text(ref_text)
        hyp_clean = normalize_multilingual_text(hyp_text)
    else:
        ref_clean = ref_text.strip()
        hyp_clean = hyp_text.strip()

    ref_words = ref_clean.split()
    hyp_words = hyp_clean.split()

    # Word-level edit distance
    n_words = len(ref_words)
    if n_words == 0:
        w_sub, w_del, w_ins, w_hits = 0, 0, len(hyp_words), 0
        wer = 1.0 if hyp_words else 0.0
    else:
        w_sub, w_del, w_ins, w_hits = compute_levenshtein(ref_words, hyp_words)
        wer = (w_sub + w_del + w_ins) / float(n_words)

    # Character-level edit distance
    ref_chars = list(ref_clean)
    hyp_chars = list(hyp_clean)
    n_chars = len(ref_chars)
    if n_chars == 0:
        c_sub, c_del, c_ins, c_hits = 0, 0, len(hyp_chars), 0
        cer = 1.0 if hyp_chars else 0.0
    else:
        c_sub, c_del, c_ins, c_hits = compute_levenshtein(ref_chars, hyp_chars)
        cer = (c_sub + c_del + c_ins) / float(n_chars)

    return {
        "wer": round(wer, 4),
        "cer": round(cer, 4),
        "ref_words": n_words,
        "hyp_words": len(hyp_words),
        "word_subs": w_sub,
        "word_dels": w_del,
        "word_ins": w_ins,
        "ref_chars": n_chars,
        "hyp_chars": len(hyp_chars),
        "char_subs": c_sub,
        "char_dels": c_del,
        "char_ins": c_ins,
        "ref_clean": ref_clean,
        "hyp_clean": hyp_clean,
    }


# ---------------------------------------------------------------------------
# Data Models
# ---------------------------------------------------------------------------

@dataclass
class ClipResult:
    clip_id: str
    language: str
    quantization: str
    mode: str  # 'streaming' or 'offline'
    audio_duration_sec: float
    wall_time_sec: float
    rtf: float
    normalized_wer: float
    normalized_cer: float
    raw_wer: float
    raw_cer: float
    reference_raw: str
    hypothesis_raw: str
    time_to_first_partial_sec: Optional[float] = None
    partial_count: int = 0
    stability_score: float = 1.0  # 1.0 = perfect stability
    peak_rss_mb: float = 0.0
    success: bool = True
    error_message: Optional[str] = None
    simulated: bool = True


@dataclass
class QuantizationAggregate:
    quantization: str
    role: str
    filename: str
    size_bytes: int
    size_mib: float
    size_delta_percent: float  # vs Q8_0
    sha256: str
    clips_evaluated: int
    clips_failed: int
    # Streaming metrics
    streaming_normalized_wer: float
    streaming_normalized_cer: float
    streaming_raw_wer: float
    streaming_raw_cer: float
    streaming_rtf_median: float
    streaming_rtf_p90: float
    streaming_ttfp_median_sec: float
    streaming_stability_median: float
    # Offline metrics
    offline_normalized_wer: float
    offline_normalized_cer: float
    offline_rtf_median: float
    # Quality degradation vs Q8_0
    relative_wer_degradation_percent: float
    absolute_wer_delta: float
    # Per-language WER breakdown
    language_wer: Dict[str, float]
    # Resource
    peak_rss_mb_median: float
    model_load_latency_sec: float
    simulated: bool = True


# ---------------------------------------------------------------------------
# Model Download & Verification
# ---------------------------------------------------------------------------

def verify_file_sha256(path: Path, expected_sha256: str) -> bool:
    if not path.is_file():
        return False
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower() == expected_sha256.lower()


def ensure_model_downloaded(
    model_meta: Dict[str, Any], models_dir: Path, download_missing: bool = False
) -> Path:
    """Verifies that the target GGUF exists and matches SHA-256.

    Never redownloads when a valid file already exists.
    """
    models_dir.mkdir(parents=True, exist_ok=True)
    filename = model_meta["filename"]
    target_path = models_dir / filename
    expected_sha256 = model_meta["sha256"]
    expected_size = model_meta["size_bytes"]

    if target_path.is_file():
        if target_path.stat().st_size == expected_size:
            if verify_file_sha256(target_path, expected_sha256):
                return target_path
            else:
                print(f"Warning: Checksum mismatch for existing {filename}, will re-fetch if allowed.")
        else:
            print(f"Warning: File size mismatch for existing {filename}.")

    if not download_missing:
        raise FileNotFoundError(
            f"Model {filename} not found or corrupted in {models_dir}. Pass --download-models to fetch."
        )

    url = model_meta["download_url"]
    print(f"Downloading {filename} from {url}...")
    temp_target = target_path.with_suffix(".tmp")
    urllib.request.urlretrieve(url, temp_target)

    if not verify_file_sha256(temp_target, expected_sha256):
        temp_target.unlink(missing_ok=True)
        raise ValueError(f"Downloaded model {filename} failed SHA-256 verification!")

    temp_target.rename(target_path)
    print(f"✓ Downloaded and verified {filename}")
    return target_path


# ---------------------------------------------------------------------------
# Execution Backends
# ---------------------------------------------------------------------------

class BenchmarkBackend:
    """Base interface for model transcription execution."""

    def load_model(self, model_meta: Dict[str, Any]) -> float:
        raise NotImplementedError

    def run_inference(
        self,
        model_meta: Dict[str, Any],
        clip: Dict[str, Any],
        audio_path: Path,
        mode: str,
    ) -> ClipResult:
        raise NotImplementedError


class SyntheticBackend(BenchmarkBackend):
    """Deterministic, zero-network SIMULATED backend for pipeline smoke tests.

    This backend does not load a model and performs no inference. The metrics
    it returns are synthetic values generated locally to exercise the
    aggregation and reporting code paths with stable output. They are NOT
    empirical measurements and must not be used to claim accuracy, speed, or
    memory behavior of any quantization.
    """

    def __init__(self, seed: int = 42):
        self.rng = random.Random(seed)

    def load_model(self, model_meta: Dict[str, Any]) -> float:
        quant = model_meta["quantization"]
        # Model loading latency correlates with file size
        base_load = 0.8
        factor = {"Q8_0": 1.0, "Q6_K": 0.85, "Q5_K_M": 0.75, "Q4_K_M": 0.65}.get(quant, 1.0)
        return round(base_load * factor, 3)

    def run_inference(
        self,
        model_meta: Dict[str, Any],
        clip: Dict[str, Any],
        audio_path: Path,
        mode: str,
    ) -> ClipResult:
        quant = model_meta["quantization"]
        ref_text = clip["transcript"]
        duration = clip["duration_sec"]
        lang = clip["language"]

        # Simulated baseline error rate by language (arbitrary, stable values)
        lang_base_wer = {"en": 0.055, "es": 0.062, "fr": 0.085, "pt": 0.078}.get(lang, 0.07)

        # Simulated quantization penalty
        quant_penalty = {
            "Q8_0": 0.0,
            "Q6_K": 0.0015,
            "Q5_K_M": 0.0035,
            "Q4_K_M": 0.0075,
        }.get(quant, 0.0)

        # Streaming penalty vs offline
        mode_penalty = 0.005 if mode == "streaming" else 0.0

        target_wer = lang_base_wer + quant_penalty + mode_penalty

        # Generate hypothesis by deterministically introducing occasional errors
        words = ref_text.split()
        hyp_words = list(words)
        error_count = int(round(len(words) * target_wer))

        # Stable pseudo-random substitution
        sub_indices = sorted(self.rng.sample(range(len(words)), min(error_count, len(words))))
        for idx in sub_indices:
            orig = hyp_words[idx]
            if len(orig) > 3:
                hyp_words[idx] = orig[:-1] + "e"  # Slight phonetic variation

        hyp_text = " ".join(hyp_words)

        # Calculate actual metrics
        norm_scores = calculate_wer_cer(ref_text, hyp_text, normalize=True)
        raw_scores = calculate_wer_cer(ref_text, hyp_text, normalize=False)

        # Simulated processing speed
        rtf_map = {"Q8_0": 0.22, "Q6_K": 0.19, "Q5_K_M": 0.17, "Q4_K_M": 0.15}
        base_rtf = rtf_map.get(quant, 0.20)
        wall_time = round(duration * base_rtf, 3)
        rtf = round(wall_time / duration, 4)

        # Streaming metrics
        ttfp = round(0.18 + (0.02 if quant == "Q8_0" else 0.0), 3) if mode == "streaming" else None
        partial_count = int(duration / 0.56) if mode == "streaming" else 0
        stability = 0.98 if quant in ("Q8_0", "Q6_K") else (0.95 if quant == "Q5_K_M" else 0.91)

        rss_map = {"Q8_0": 1180.0, "Q6_K": 1040.0, "Q5_K_M": 970.0, "Q4_K_M": 880.0}
        peak_rss = rss_map.get(quant, 1000.0)

        return ClipResult(
            clip_id=clip["id"],
            language=lang,
            quantization=quant,
            mode=mode,
            audio_duration_sec=duration,
            wall_time_sec=wall_time,
            rtf=rtf,
            normalized_wer=norm_scores["wer"],
            normalized_cer=norm_scores["cer"],
            raw_wer=raw_scores["wer"],
            raw_cer=raw_scores["cer"],
            reference_raw=ref_text,
            hypothesis_raw=hyp_text,
            time_to_first_partial_sec=ttfp,
            partial_count=partial_count,
            stability_score=stability,
            peak_rss_mb=peak_rss,
            success=True,
            simulated=True,
        )


class AdbDeviceBackend(BenchmarkBackend):
    """Executes inference on an attached ARM64 Android device running the Aura-Transcribe production pipeline."""

    def __init__(self, serial: Optional[str] = None):
        self.serial = serial
        self.cmd_prefix = ["adb"]
        if serial:
            self.cmd_prefix.extend(["-s", serial])
        self.package = "com.auratranscribe.app"

    def _adb(self, args: List[str]) -> str:
        res = subprocess.run(
            self.cmd_prefix + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=True,
        )
        return res.stdout

    def load_model(self, model_meta: Dict[str, Any]) -> float:
        # Pushes model and sets active_model marker
        t0 = time.perf_counter()
        # In a real device setup, sets marker in /data/data/com.auratranscribe.app/files/active_model
        return round(time.perf_counter() - t0, 3)

    def run_inference(
        self,
        model_meta: Dict[str, Any],
        clip: Dict[str, Any],
        audio_path: Path,
        mode: str,
    ) -> ClipResult:
        # For execution via adb, falls back cleanly or returns actionable error if device unavailable
        raise NotImplementedError("ADB device inference requires connected device with app installed.")


# ---------------------------------------------------------------------------
# Runner & Analysis Engine
# ---------------------------------------------------------------------------

class BenchmarkRunner:
    def __init__(
        self,
        models_manifest_path: Path = DEFAULT_MODELS_MANIFEST,
        corpus_manifest_path: Path = DEFAULT_CORPUS_MANIFEST,
        models_dir: Path = DEFAULT_MODELS_DIR,
        corpus_dir: Path = DEFAULT_CORPUS_DIR,
        results_dir: Path = DEFAULT_RESULTS_DIR,
        backend_type: str = "synthetic",
        seed: int = 42,
    ):
        self.models_manifest = self._load_json(models_manifest_path)
        self.corpus_manifest = self._load_json(corpus_manifest_path)
        self.models_dir = models_dir
        self.corpus_dir = corpus_dir
        self.results_dir = results_dir
        self.backend_type = backend_type
        self.simulated = backend_type == "synthetic"
        self.seed = seed

        if backend_type == "synthetic":
            self.backend: BenchmarkBackend = SyntheticBackend(seed=seed)
        elif backend_type == "adb":
            self.backend = AdbDeviceBackend()
        else:
            raise ValueError(f"Unknown backend: {backend_type}")

        self.results_dir.mkdir(parents=True, exist_ok=True)

    def _load_json(self, p: Path) -> Dict[str, Any]:
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)

    def run_benchmark(
        self,
        quantizations: Optional[List[str]] = None,
        languages: Optional[List[str]] = None,
        clip_ids: Optional[List[str]] = None,
        modes: Optional[List[str]] = None,
        repetitions: int = 1,
        warmup_runs: int = 1,
        randomize_order: bool = False,
        download_missing_models: bool = False,
    ) -> Tuple[List[ClipResult], List[QuantizationAggregate]]:
        clips = list(self.corpus_manifest["clips"])
        models = list(self.models_manifest["models"])

        if languages:
            clips = [c for c in clips if c["language"] in languages]
        if clip_ids:
            clips = [c for c in clips if c["id"] in clip_ids]
        if quantizations:
            models = [m for m in models if m["quantization"] in quantizations]
        if not modes:
            modes = ["streaming", "offline"]

        if randomize_order:
            rng = random.Random(self.seed)
            rng.shuffle(models)
            print(f"Randomized quantization execution order: {[m['quantization'] for m in models]}")

        all_results: List[ClipResult] = []
        load_latencies: Dict[str, float] = {}

        run_label = "SIMULATED ASR smoke run" if self.simulated else "ASR run"
        print(
            f"Starting {run_label}: {len(models)} quantizations, {len(clips)} clips, "
            f"modes={modes}, reps={repetitions}, warmup={warmup_runs}..."
        )

        for model in models:
            q = model["quantization"]
            print(f"\n--- Model {q} ({model['filename']}) ---")
            load_lat = self.backend.load_model(model)
            load_latencies[q] = load_lat
            print(f"  Model loaded in {load_lat:.3f}s")

            # Warm-up runs (if requested and clips available)
            if warmup_runs > 0 and clips:
                warmup_clip = clips[0]
                warmup_audio = PROJECT_ROOT / warmup_clip["audio_path"]
                print(f"  Performing {warmup_runs} warm-up run(s)...")
                for _ in range(warmup_runs):
                    _ = self.backend.run_inference(model, warmup_clip, warmup_audio, modes[0])

            for mode in modes:
                print(f"  Evaluating mode: {mode} ({len(clips)} clips, {repetitions} reps)...")
                for rep in range(repetitions):
                    for clip in clips:
                        audio_path = PROJECT_ROOT / clip["audio_path"]
                        if not audio_path.is_file() and not isinstance(self.backend, SyntheticBackend):
                            raise FileNotFoundError(
                                f"Corpus audio file missing: {audio_path}. Run `python3 scripts/prepare_corpus.py --verify`."
                            )
                        result = self.backend.run_inference(model, clip, audio_path, mode)
                        all_results.append(result)

        # Aggregate metrics
        aggregates = self._aggregate_results(models, clips, all_results, load_latencies)
        return all_results, aggregates

    def _aggregate_results(
        self,
        models: List[Dict[str, Any]],
        clips: List[Dict[str, Any]],
        results: List[ClipResult],
        load_latencies: Dict[str, float],
    ) -> List[QuantizationAggregate]:
        # Find Q8_0 baseline
        q8_meta = next(m for m in self.models_manifest["models"] if m["quantization"] == "Q8_0")
        q8_size = q8_meta["size_bytes"]

        q8_streaming_results = [r for r in results if r.quantization == "Q8_0" and r.mode == "streaming"]
        q8_wer_baseline = (
            sum(r.normalized_wer for r in q8_streaming_results) / len(q8_streaming_results)
            if q8_streaming_results
            else 0.05
        )

        aggregates: List[QuantizationAggregate] = []

        for model in models:
            q = model["quantization"]
            model_results = [r for r in results if r.quantization == q]
            stream_res = [r for r in model_results if r.mode == "streaming"]
            offline_res = [r for r in model_results if r.mode == "offline"]

            # Streaming WER / CER
            s_wer = sum(r.normalized_wer for r in stream_res) / len(stream_res) if stream_res else 0.0
            s_cer = sum(r.normalized_cer for r in stream_res) / len(stream_res) if stream_res else 0.0
            s_raw_wer = sum(r.raw_wer for r in stream_res) / len(stream_res) if stream_res else 0.0
            s_raw_cer = sum(r.raw_cer for r in stream_res) / len(stream_res) if stream_res else 0.0

            # Offline WER / CER
            o_wer = sum(r.normalized_wer for r in offline_res) / len(offline_res) if offline_res else 0.0
            o_cer = sum(r.normalized_cer for r in offline_res) / len(offline_res) if offline_res else 0.0

            # Speed
            rtfs = sorted([r.rtf for r in stream_res]) if stream_res else [0.0]
            rtf_med = rtfs[len(rtfs) // 2]
            p90_idx = min(int(len(rtfs) * 0.9), len(rtfs) - 1)
            rtf_p90 = rtfs[p90_idx]

            o_rtfs = sorted([r.rtf for r in offline_res]) if offline_res else [0.0]
            o_rtf_med = o_rtfs[len(o_rtfs) // 2]

            # Streaming TTFP and stability
            ttf_list = sorted([r.time_to_first_partial_sec for r in stream_res if r.time_to_first_partial_sec])
            ttfp_med = ttf_list[len(ttf_list) // 2] if ttf_list else 0.0
            stab_list = sorted([r.stability_score for r in stream_res])
            stab_med = stab_list[len(stab_list) // 2] if stab_list else 1.0

            # Per-language WER
            lang_wer: Dict[str, float] = {}
            for lang in ["en", "es", "fr", "pt"]:
                l_res = [r for r in stream_res if r.language == lang]
                if l_res:
                    lang_wer[lang] = round(sum(r.normalized_wer for r in l_res) / len(l_res), 4)

            # Degradation vs Q8_0
            abs_delta = round(s_wer - q8_wer_baseline, 4)
            rel_degrad = round((abs_delta / q8_wer_baseline) * 100.0, 2) if q8_wer_baseline > 0 else 0.0

            # Size delta
            size_delta = round(((q8_size - model["size_bytes"]) / q8_size) * 100.0, 2)

            # Peak RSS
            rss_list = sorted([r.peak_rss_mb for r in stream_res if r.peak_rss_mb > 0])
            rss_med = rss_list[len(rss_list) // 2] if rss_list else 0.0

            aggregates.append(
                QuantizationAggregate(
                    quantization=q,
                    role=model.get("role", "alternate"),
                    filename=model["filename"],
                    size_bytes=model["size_bytes"],
                    size_mib=model["size_mib"],
                    size_delta_percent=size_delta,
                    sha256=model["sha256"],
                    clips_evaluated=len(model_results),
                    clips_failed=sum(1 for r in model_results if not r.success),
                    streaming_normalized_wer=round(s_wer, 4),
                    streaming_normalized_cer=round(s_cer, 4),
                    streaming_raw_wer=round(s_raw_wer, 4),
                    streaming_raw_cer=round(s_raw_cer, 4),
                    streaming_rtf_median=round(rtf_med, 4),
                    streaming_rtf_p90=round(rtf_p90, 4),
                    streaming_ttfp_median_sec=round(ttfp_med, 3),
                    streaming_stability_median=round(stab_med, 3),
                    offline_normalized_wer=round(o_wer, 4),
                    offline_normalized_cer=round(o_cer, 4),
                    offline_rtf_median=round(o_rtf_med, 4),
                    relative_wer_degradation_percent=rel_degrad,
                    absolute_wer_delta=abs_delta,
                    language_wer=lang_wer,
                    peak_rss_mb_median=round(rss_med, 1),
                    model_load_latency_sec=load_latencies.get(q, 0.0),
                    simulated=self.simulated,
                )
            )

        return aggregates

    def evaluate_pareto_frontier(
        self, aggregates: List[QuantizationAggregate]
    ) -> Dict[str, Any]:
        """Classifies non-dominated variants on the reported metrics.

        Informational only: this suite deliberately emits NO quantization
        recommendation. With the synthetic backend the metrics are simulated,
        so they cannot justify a deployment decision anyway. The app bundles
        Q5_K_M; Q8_0 is kept as a higher-precision comparison reference.
        """
        # Variant A dominates B if A has lower/equal WER, smaller/equal size,
        # and lower/equal RTF, with at least one strictly better.
        pareto_optimal: List[str] = []
        dominated: Dict[str, str] = {}

        for a in aggregates:
            is_dominated = False
            for b in aggregates:
                if a.quantization == b.quantization:
                    continue
                # Lower is better for WER, Size, RTF
                if (
                    b.streaming_normalized_wer <= a.streaming_normalized_wer
                    and b.size_bytes <= a.size_bytes
                    and b.streaming_rtf_median <= a.streaming_rtf_median
                    and (
                        b.streaming_normalized_wer < a.streaming_normalized_wer
                        or b.size_bytes < a.size_bytes
                        or b.streaming_rtf_median < a.streaming_rtf_median
                    )
                ):
                    is_dominated = True
                    dominated[a.quantization] = b.quantization
                    break
            if not is_dominated:
                pareto_optimal.append(a.quantization)

        return {
            "note": "Informational only. No automatic quantization recommendation is produced.",
            "pareto_optimal_quantizations": pareto_optimal,
            "dominated_quantizations": dominated,
        }

    def export_reports(
        self,
        clip_results: List[ClipResult],
        aggregates: List[QuantizationAggregate],
        output_dir: Optional[Path] = None,
    ) -> Dict[str, Path]:
        out_dir = output_dir or self.results_dir
        out_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

        analysis = self.evaluate_pareto_frontier(aggregates)

        disclaimer = (
            "SIMULATED DATA: the synthetic backend generates metrics locally for "
            "pipeline smoke-testing. They are not real ASR accuracy, latency, or "
            "memory measurements and must not be reported as such."
            if self.simulated
            else "Metrics produced by a real backend; no quantization recommendation is emitted."
        )

        # 1. JSON
        json_path = out_dir / f"benchmark_results_{timestamp}.json"
        payload = {
            "metadata": {
                "generated_at": datetime.datetime.now().isoformat(),
                "backend": self.backend_type,
                "simulated": self.simulated,
                "disclaimer": disclaimer,
                "corpus_name": self.corpus_manifest.get("dataset_name", ""),
                "corpus_synthetic": bool(self.corpus_manifest.get("synthetic", False)),
                "recommendation_produced": False,
                "analysis": analysis,
            },
            "aggregates": [asdict(a) for a in aggregates],
            "clip_results": [asdict(c) for c in clip_results],
        }
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)

        # 2. CSV
        csv_path = out_dir / f"benchmark_results_{timestamp}.csv"
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "Quantization",
                "Role",
                "Size_MiB",
                "Size_Savings_Pct",
                "Sim_Stream_WER_Norm",
                "Sim_Stream_CER_Norm",
                "Sim_Stream_WER_Raw",
                "Sim_Rel_WER_Degradation_Pct",
                "Sim_WER_EN",
                "Sim_WER_ES",
                "Sim_WER_FR",
                "Sim_WER_PT",
                "Sim_RTF_Median",
                "Sim_TTFP_Median_Sec",
                "Sim_Stability",
                "Sim_Peak_RSS_MiB",
                "Sim_Load_Time_Sec",
                "Simulated",
            ])
            for a in aggregates:
                writer.writerow([
                    a.quantization,
                    a.role,
                    a.size_mib,
                    f"{a.size_delta_percent}%",
                    f"{a.streaming_normalized_wer * 100:.2f}%",
                    f"{a.streaming_normalized_cer * 100:.2f}%",
                    f"{a.streaming_raw_wer * 100:.2f}%",
                    f"{a.relative_wer_degradation_percent:+.2f}%",
                    f"{a.language_wer.get('en', 0) * 100:.2f}%",
                    f"{a.language_wer.get('es', 0) * 100:.2f}%",
                    f"{a.language_wer.get('fr', 0) * 100:.2f}%",
                    f"{a.language_wer.get('pt', 0) * 100:.2f}%",
                    a.streaming_rtf_median,
                    a.streaming_ttfp_median_sec,
                    a.streaming_stability_median,
                    a.peak_rss_mb_median,
                    a.model_load_latency_sec,
                    "yes" if self.simulated else "no",
                ])

        # 3. Markdown Report
        md_path = out_dir / f"benchmark_report_{timestamp}.md"
        sim_prefix = "Sim. " if self.simulated else ""
        title_suffix = " (SIMULATED)" if self.simulated else ""
        banner = (
            "\n> **SIMULATED DATA — synthetic backend.** The numbers below were generated "
            "locally to exercise the reporting pipeline. They are not real ASR accuracy, "
            "RTF, or memory measurements and must not be used to claim model quality. "
            "No audio was downloaded.\n"
            if self.simulated
            else ""
        )
        corpus_name = self.corpus_manifest.get("dataset_name", "unknown corpus")

        md_content = f"""# Aura-Transcribe ASR Smoke Report{title_suffix}

Generated: `{datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}`<br>
Backend: `{self.backend_type}`<br>
Corpus: `{corpus_name}` (synthetic placeholder clips)
{banner}
No automatic quantization recommendation is produced. The app bundles Q5_K_M; Q8_0 is kept as a higher-precision comparison reference.

## 1. Executive Summary & Trade-Off Matrix

| Quantization | Role | Size (MiB) | Size Saved | {sim_prefix}Norm WER (Stream) | {sim_prefix}Norm CER | {sim_prefix}Rel Degradation | {sim_prefix}RTF (Median) | {sim_prefix}TTFP (s) | {sim_prefix}Stability | {sim_prefix}Peak RSS (MiB) |
|---|---|---|---|---|---|---|---|---|---|---|
"""
        for a in aggregates:
            md_content += (
                f"| **{a.quantization}** | {a.role} | {a.size_mib:.1f} | "
                f"{a.size_delta_percent:+.1f}% | {a.streaming_normalized_wer * 100:.2f}% | "
                f"{a.streaming_normalized_cer * 100:.2f}% | {a.relative_wer_degradation_percent:+.2f}% | "
                f"{a.streaming_rtf_median:.3f} | {a.streaming_ttfp_median_sec:.3f}s | "
                f"{a.streaming_stability_median:.2f} | {a.peak_rss_mb_median:.1f} |\n"
            )

        md_content += f"""
## 2. {sim_prefix}WER Breakdown by Language

| Quantization | English (`en`) | Spanish (`es`) | French (`fr`) | Portuguese (`pt`) | Macro Avg {sim_prefix}WER | Raw Verbatim {sim_prefix}WER |
|---|---|---|---|---|---|---|
"""
        for a in aggregates:
            macro = sum(a.language_wer.values()) / max(len(a.language_wer), 1)
            md_content += (
                f"| **{a.quantization}** | {a.language_wer.get('en', 0) * 100:.2f}% | "
                f"{a.language_wer.get('es', 0) * 100:.2f}% | {a.language_wer.get('fr', 0) * 100:.2f}% | "
                f"{a.language_wer.get('pt', 0) * 100:.2f}% | {macro * 100:.2f}% | "
                f"{a.streaming_raw_wer * 100:.2f}% |\n"
            )

        md_content += f"""
## 3. Pareto Frontier (informational only)

- **Pareto Optimal Variants**: {', '.join(f'`{x}`' for x in analysis['pareto_optimal_quantizations'])}
- **Dominated Variants**: {', '.join(f'`{k}` dominated by `{v}`' for k, v in analysis['dominated_quantizations'].items()) if analysis['dominated_quantizations'] else 'None'}

No automatic quantization recommendation is produced. With the synthetic backend
these metrics are simulated, so they cannot justify a deployment decision.

## 4. Methodology & Environment
- **Corpus**: synthetic smoke corpus (20 generated placeholder clips: 5 EN, 5 ES, 5 FR, 5 PT), 16 kHz mono 16-bit PCM. No external audio is downloaded or redistributed.
- **Backend**: `synthetic` does not run a model; every metric is SIMULATED.
- **Normalizer**: Unicode NFC, lowercase, stripped outer punctuation, preserved diacritics/accents, numbers, and contractions.
- **Chunk Cadence**: Simulated 80 ms audio frame streaming matching Aura-Transcribe's `StreamCmd::Audio` pump.
- **Recommendation**: none by design.
"""
        with open(md_path, "w", encoding="utf-8") as f:
            f.write(md_content)

        print(f"\nGenerated benchmark outputs:")
        print(f"  - JSON: {json_path}")
        print(f"  - CSV:  {csv_path}")
        print(f"  - MD:   {md_path}")

        return {"json": json_path, "csv": csv_path, "markdown": md_path}


# ---------------------------------------------------------------------------
# CLI Entry Point
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Aura-Transcribe ASR smoke-test runner (synthetic metrics are SIMULATED)")
    parser.add_argument("--backend", choices=["synthetic", "adb"], default="synthetic", help="Execution backend (synthetic = simulated, zero network)")
    parser.add_argument("--quantizations", nargs="+", choices=["Q8_0", "Q6_K", "Q5_K_M", "Q4_K_M"], help="Subset of quantizations to report on")
    parser.add_argument("--languages", nargs="+", choices=["en", "es", "fr", "pt"], help="Subset of languages to evaluate")
    parser.add_argument("--clips", nargs="+", help="Subset of clip IDs to evaluate")
    parser.add_argument("--modes", nargs="+", choices=["streaming", "offline"], default=["streaming", "offline"], help="Inference modes")
    parser.add_argument("--repetitions", type=int, default=1, help="Number of repetitions per clip")
    parser.add_argument("--warmup-runs", type=int, default=1, help="Number of warmup runs per model")
    parser.add_argument("--randomize-order", action="store_true", help="Randomize execution order of models to reduce thermal bias")
    parser.add_argument("--download-models", action="store_true", help="Download missing models if absent")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_RESULTS_DIR, help="Directory to store reports")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")

    args = parser.parse_args()

    runner = BenchmarkRunner(
        backend_type=args.backend,
        results_dir=args.output_dir,
        seed=args.seed,
    )

    results, aggregates = runner.run_benchmark(
        quantizations=args.quantizations,
        languages=args.languages,
        clip_ids=args.clips,
        modes=args.modes,
        repetitions=args.repetitions,
        warmup_runs=args.warmup_runs,
        randomize_order=args.randomize_order,
        download_missing_models=args.download_models,
    )

    runner.export_reports(results, aggregates)
    return 0


if __name__ == "__main__":
    sys.exit(main())
