#!/usr/bin/env python3
"""Informational micro-benchmarks of the *reference implementations* of some
algorithms the app implements in Rust.

SCOPE — read this before citing any number printed below:

  * This script times small **pure-Python stand-ins**. It does not execute the
    app, the Rust engine, cpal, ggml, or any Android code, so its timings say
    nothing about device performance, battery, or real-time audio latency.
  * What it *can* prove is **correctness of the optimisations**: every benchmark
    below cross-checks its fast implementation against the naive one (or against
    a mathematical truth), and the run fails if they disagree. That is the gate
    CI actually needs; the millisecond figures are diagnostics for a human.
  * Real performance evidence requires a device: the `:benchmark` module
    (Macrobenchmark / Baseline Profile) or the in-app native ASR benchmark.
    Neither can run in CI (no arm64 device or emulator).

Covers:
  1. RMS / audio energy (vector sum vs scalar loop, checked against sin RMS = 1/sqrt(2)).
  2. Sliding-window quietest split (sliding block energy vs a full naive scan).
  3. Phonetic-corrector bigram cosine (precomputed norms vs dynamic allocation).
  4. Banded early-exit Levenshtein (vs the full quadratic matrix).
"""

import json
import math
import sys
import time


class CheckFailure(Exception):
    """A reference implementation disagreed with its naive counterpart."""


def benchmark_rms():
    """Implementation agreement plus two mathematical truths.

    The timing signal is a sine (representative of real audio). The *assertions*
    are made on a square wave, whose RMS is exactly its amplitude, and on silence,
    whose RMS is exactly zero: a sampled sine only approximates 1/sqrt(2) over a
    non-integer number of periods, so asserting against that ideal would fail for
    a reason that has nothing to do with the code.
    """
    data = [math.sin(i * 0.05) * 0.5 for i in range(16000)]
    square = [0.5 if i % 2 == 0 else -0.5 for i in range(16000)]
    expected = 0.5

    start = time.perf_counter()
    for _ in range(200):
        total = 0.0
        for value in data:
            total += value * value
        rms_scalar = math.sqrt(total / len(data))
    scalar_ms = (time.perf_counter() - start) * 1000.0 / 200.0

    start = time.perf_counter()
    for _ in range(200):
        total = sum(value * value for value in data)
        rms_vector = math.sqrt(total / len(data))
    vector_ms = (time.perf_counter() - start) * 1000.0 / 200.0

    if abs(rms_scalar - rms_vector) > 1e-9:
        raise CheckFailure(
            f"RMS implementations disagree: scalar={rms_scalar!r} vector={rms_vector!r}"
        )

    square_rms = math.sqrt(sum(value * value for value in square) / len(square))
    if abs(square_rms - expected) > 1e-12:
        raise CheckFailure(
            f"RMS of a +/-0.5 square wave must be exactly {expected}, got {square_rms!r}"
        )
    empty_rms = math.sqrt(sum(0.0 for _ in range(16)) / 16)
    if empty_rms != 0.0:
        raise CheckFailure(f"RMS of silence must be exactly 0, got {empty_rms!r}")

    return {
        "samples": len(data),
        "scalar_ms": round(scalar_ms, 4),
        "vector_ms": round(vector_ms, 4),
        "rms_val": round(rms_vector, 6),
        "square_wave_rms": round(square_rms, 6),
        "expected_square_rms": expected,
    }


def benchmark_sliding_split():
    """The sliding block-sum scan must find the same quietest window as the naive
    full scan — that is the only thing that makes the optimisation safe."""
    data = [math.sin(i * 0.01) * 0.3 for i in range(160000)]
    # Carve an unmistakable silent region at ~60% so the answer is known a priori.
    silent_at = 96000
    for index in range(silent_at, silent_at + 3200):
        data[index] = 0.0
    window = 1600
    step = 800
    rounds = 5

    naive_start = time.perf_counter()
    best_energy = float("inf")
    best_position = len(data)
    for _ in range(rounds):
        best_energy = float("inf")
        best_position = len(data)
        for start_index in range(0, len(data) - window, step):
            energy = sum(data[i] * data[i] for i in range(start_index, start_index + window))
            if energy < best_energy:
                best_energy = energy
                best_position = start_index + window // 2
    naive_ms = (time.perf_counter() - naive_start) * 1000.0 / rounds

    sliding_start = time.perf_counter()
    sliding_energy = 0.0
    sliding_best_energy = 0.0
    sliding_best_position = 0
    for _ in range(rounds):
        sliding_energy = sum(value * value for value in data[0:window])
        sliding_best_energy = sliding_energy
        sliding_best_position = window // 2
        index = 0
        while index + window + step <= len(data):
            leaving = sum(value * value for value in data[index:index + step])
            entering = sum(value * value for value in data[index + window:index + window + step])
            sliding_energy = max(0.0, sliding_energy - leaving + entering)
            if sliding_energy < sliding_best_energy:
                sliding_best_energy = sliding_energy
                sliding_best_position = index + step + window // 2
            index += step
    sliding_ms = (time.perf_counter() - sliding_start) * 1000.0 / rounds

    if abs(best_energy - sliding_best_energy) > 1e-6:
        raise CheckFailure(
            "sliding block energy disagrees with the naive scan: "
            f"{sliding_best_energy!r} vs {best_energy!r}"
        )
    if abs(sliding_best_energy) > 1e-6 or not (
        silent_at <= sliding_best_position <= silent_at + 3200
    ):
        raise CheckFailure(
            f"the quietest window should be inside the silent region at {silent_at}, "
            f"got position {sliding_best_position} with energy {sliding_best_energy!r}"
        )

    return {
        "naive_scan_ms": round(naive_ms, 4),
        "sliding_scan_ms": round(sliding_ms, 4),
        "detected_position": sliding_best_position,
        "expected_silence_at": silent_at,
    }


def benchmark_corrector_bigrams():
    """Precomputing term norms must not change any similarity value."""
    terms = ["Madrid", "Barcelona", "Valencia", "Sevilla", "Zaragoza", "Malaga",
             "Murcia", "Palma", "Bilbao", "Alicante"]
    word = "Madriz"

    def bigrams(text):
        chars = list(text.lower())
        counts = {}
        for index in range(len(chars) - 1):
            key = chars[index] + chars[index + 1]
            counts[key] = counts.get(key, 0) + 1
        return counts

    start = time.perf_counter()
    dynamic_scores = []
    for _ in range(2000):
        dynamic_scores = []
        word_map = bigrams(word)
        word_norm = math.sqrt(sum(value * value for value in word_map.values()))
        for term in terms:
            term_map = bigrams(term)
            term_norm = math.sqrt(sum(value * value for value in term_map.values()))
            dot = sum(word_map[key] * term_map[key] for key in word_map if key in term_map)
            dynamic_scores.append(
                dot / (word_norm * term_norm) if word_norm > 0 and term_norm > 0 else 0.0
            )
    dynamic_ms = (time.perf_counter() - start) * 1000.0 / 2000.0

    precomputed = []
    for term in terms:
        term_map = bigrams(term)
        precomputed.append((term_map, math.sqrt(sum(v * v for v in term_map.values()))))

    start = time.perf_counter()
    precomputed_scores = []
    for _ in range(2000):
        precomputed_scores = []
        word_map = bigrams(word)
        word_norm = math.sqrt(sum(value * value for value in word_map.values()))
        for term_map, term_norm in precomputed:
            dot = sum(word_map[key] * term_map[key] for key in word_map if key in term_map)
            precomputed_scores.append(
                dot / (word_norm * term_norm) if word_norm > 0 and term_norm > 0 else 0.0
            )
    precomputed_ms = (time.perf_counter() - start) * 1000.0 / 2000.0

    if any(
        abs(dynamic - fast) > 1e-12
        for dynamic, fast in zip(dynamic_scores, precomputed_scores)
    ):
        raise CheckFailure(
            "precomputed bigram norms changed the similarity values: "
            f"{precomputed_scores} vs {dynamic_scores}"
        )
    if terms[0].lower() != "madrid" or "madriz" == "madrid":
        raise CheckFailure("test fixture changed unexpectedly")

    return {
        "dynamic_ms": round(dynamic_ms, 4),
        "precomputed_ms": round(precomputed_ms, 4),
        "top_match": terms[max(range(len(terms)), key=lambda i: dynamic_scores[i])],
        "best_score": round(max(dynamic_scores), 6),
    }


def benchmark_levenshtein_banded():
    """The banded early-exit variant must return exactly the same distance as the
    full matrix for every pair, including aborts."""
    pairs = [
        ("madriz", "madrid"),
        ("barselona", "barcelona"),
        ("valensia", "valencia"),
        ("sebilla", "sevilla"),
        ("saragosa", "zaragoza"),
        ("incomprensible", "comprender"),
        ("computadora", "ordenador"),
        ("telefono", "microfono"),
    ]

    def full_distance(left, right):
        rows, cols = len(left), len(right)
        matrix = [[0] * (cols + 1) for _ in range(rows + 1)]
        for i in range(rows + 1):
            matrix[i][0] = i
        for j in range(cols + 1):
            matrix[0][j] = j
        for i in range(1, rows + 1):
            for j in range(1, cols + 1):
                cost = 0 if left[i - 1] == right[j - 1] else 1
                matrix[i][j] = min(
                    matrix[i - 1][j] + 1, matrix[i][j - 1] + 1, matrix[i - 1][j - 1] + cost
                )
        return matrix[rows][cols]

    def banded_distance(left, right, band=2, limit=2):
        # Cells outside the band must be INFINITY, not a stale value from a
        # previous row: a reused array makes out-of-band neighbours look like real
        # (too small) distances, which is exactly the mistake this differential
        # check exists to catch. Rows are therefore allocated fresh and only the
        # band is filled in.
        rows, cols = len(left), len(right)
        if abs(rows - cols) > band:
            return None  # aborted: cannot be within the limit
        infinity = float("inf")
        previous = [infinity] * (cols + 1)
        previous[0] = 0
        for i in range(1, rows + 1):
            current = [infinity] * (cols + 1)
            current[0] = i
            start_j = max(1, i - band)
            end_j = min(cols, i + band)
            best = infinity
            for j in range(start_j, end_j + 1):
                cost = 0 if left[i - 1] == right[j - 1] else 1
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                best = min(best, current[j])
            if best > limit:
                return None
            previous = current
        return previous[cols]

    start = time.perf_counter()
    for _ in range(1000):
        full_results = [full_distance(left, right) for left, right in pairs]
    full_ms = (time.perf_counter() - start) * 1000.0 / 1000.0

    start = time.perf_counter()
    for _ in range(1000):
        banded_results = [banded_distance(left, right) for left, right in pairs]
    banded_ms = (time.perf_counter() - start) * 1000.0 / 1000.0

    for (left, right), full, banded in zip(pairs, full_results, banded_results):
        if full <= 2 and banded != full:
            raise CheckFailure(
                f"banded distance changed the result for ({left!r}, {right!r}): "
                f"{banded!r} vs {full!r}"
            )
        if full > 2 and banded is not None:
            raise CheckFailure(
                f"({left!r}, {right!r}) is {full} edits apart and must be rejected, got {banded!r}"
            )
    if full_distance("madriz", "madrid") != 1:
        raise CheckFailure("edit distance of madriz/madrid must be 1")

    return {
        "full_matrix_ms": round(full_ms, 4),
        "banded_early_exit_ms": round(banded_ms, 4),
        "distances": full_results,
    }


def main():
    print("=" * 72)
    print("  REFERENCE-ALGORITHM MICRO-BENCHMARKS (pure Python; not device data)")
    print("=" * 72)

    try:
        rms = benchmark_rms()
        split = benchmark_sliding_split()
        corrector = benchmark_corrector_bigrams()
        levenshtein = benchmark_levenshtein_banded()
    except CheckFailure as failure:
        print(f"\nFAIL (reference implementation disagreement): {failure}")
        return 1

    print(f"\n[1] RMS / audio energy ({rms['samples']} samples):")
    print(f"    scalar loop {rms['scalar_ms']} ms | vector sum {rms['vector_ms']} ms")
    print(f"    sine RMS {rms['rms_val']} | square-wave RMS "
          f"{rms['square_wave_rms']} (exactly {rms['expected_square_rms']}) — OK")

    print("\n[2] Quietest split point detection (160,000 samples):")
    print(f"    naive scan {split['naive_scan_ms']} ms | sliding scan {split['sliding_scan_ms']} ms")
    print(f"    sliding scan detected {split['detected_position']}, "
          f"silence is at {split['expected_silence_at']} — OK")

    print("\n[3] Phonetic bigram cosine:")
    print(f"    {corrector['dynamic_ms']} ms/query dynamic vs "
          f"{corrector['precomputed_ms']} ms/query precomputed")
    print(f"    best match for 'Madriz' is {corrector['top_match']} "
          f"({corrector['best_score']}) — OK")

    print("\n[4] Bounded Levenshtein (full matrix vs banded early exit):")
    print(f"    {levenshtein['full_matrix_ms']} ms/batch vs "
          f"{levenshtein['banded_early_exit_ms']} ms/batch")
    print(f"    distances {levenshtein['distances']} — OK")

    with open("benchmark_results.json", "w") as handle:
        json.dump(
            {
                "scope": "pure-python reference implementations; NOT device performance",
                "rms_benchmark": rms,
                "split_benchmark": split,
                "corrector_benchmark": corrector,
                "levenshtein_benchmark": levenshtein,
                "status": "PASS",
            },
            handle,
            indent=2,
        )

    print("\n" + "=" * 72)
    print("  All reference implementations agree with their naive counterparts.")
    print("  Timing figures are diagnostics — device performance needs a device.")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
