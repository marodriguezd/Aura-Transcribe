# Aura-Transcribe: ASR Smoke-Test Tooling (Synthetic Corpus)

Reproducible, zero-network **smoke tests** for the Aura-Transcribe ASR reporting
pipeline. They exercise corpus validation, metric aggregation, and JSON/CSV/Markdown
export. They are **not** an accuracy benchmark: the corpus is synthetic, its audio
contains no speech, and the synthetic backend's metrics are **SIMULATED**.

> The Python tooling in this directory is independent of the `:benchmark` Gradle
> module. `:benchmark` contains Macrobenchmark / Baseline Profile tests and is
> built separately with `./gradlew :benchmark:assembleBenchmark`; it is not part
> of this smoke suite.

---

## 1. Scope & Model Roles

The suite reports on quantization variants of **NVIDIA Nemotron 3.5 ASR Streaming
0.6B** (`handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf`):

| Quantization | Role | Size (MiB) | Size Savings | SHA-256 (LFS OID) |
|---|---|---|---|---|
| **`Q8_0`** | Reference (comparison only) | 716.30 | 0.00% | `b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c` |
| **`Q6_K`** | Alternate (not bundled) | 592.57 | -17.27% | `4ff802c6207c4a7df23242003fd2aa849a1ab02bba6bc80c3db02e7e82606c28` |
| **`Q5_K_M`** | **Bundled** (shipped by the app) | 533.72 | -25.49% | `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65` |
| **`Q4_K_M`** | Alternate (not bundled) | 472.86 | -33.99% | `41c99fa5fb6f3d35f68e79adc3e755eca2232a8d921178bd647b71194792b8fd` |

- **Base Model**: `nvidia/nemotron-3.5-asr-streaming-0.6b` (Conformer-RNNT with cache-aware chunk streaming).
- **Base model license**: `openmdw-1.1` ([OpenMDW License](https://openmdw.ai/license/1-1/)).
- **Hardware Target**: ARM64 Android with ARMv8.2-A `dotprod` + `fp16` NEON extensions.
- **No automatic quantization recommendation is produced.** The app bundles
  `Q5_K_M`; `Q8_0` is kept only as a higher-precision comparison reference.

---

## 2. Synthetic Smoke Corpus

The corpus contains **20 generated placeholder clips** across 4 languages (5 each),
defined in [`benchmark/manifests/corpus.json`](manifests/corpus.json):

- **English (`en`)**, **Spanish (`es`)**, **French (`fr`)**, **Portuguese (`pt`)**.
- Audio: deterministic 16 kHz mono 16-bit PCM WAV, generated locally by
  `scripts/prepare_corpus.py --generate-synthetic` from the manifest's segment
  boundaries. It contains synthetic tones, not recorded speech.
- **Source dataset**: `none`. Nothing is downloaded and no third-party audio is
  redistributed. The manifest's license metadata is `GPL-3.0-only` for the
  Aura-Transcribe project; it does not describe any external dataset.
- The manifest transcripts are placeholder reference strings used to exercise the
  reporting pipeline. They are not verified human speech and must not be used to
  claim recognition accuracy or WER.

---

## 3. Execution Backends

The runner (`scripts/benchmark_asr.py`) has two backends:

1. `--backend synthetic` (default): deterministic **SIMULATED** metrics, zero
   network access. It does not load a model or run inference; it only exercises
   the aggregation and report-export paths.
2. `--backend adb`: placeholder stub. Device inference is not implemented yet and
   raises an actionable error when invoked.

The `streaming` and `offline` modes exercise the corresponding reporting paths;
they do not measure real latency, throughput, or memory.

### Dual-Track Scoring
- **Standardized Normalized**: Unicode NFC, lowercase, stripped punctuation, preserved diacritics, numbers, and contractions.
- **Raw Verbatim**: Direct case- and punctuation-sensitive matching.

These scores are computed against the synthetic placeholder transcripts and are
reported as simulated data.

---

## 4. Usage Instructions

### A. Run Automated Unit & Smoke Tests (CI Safe)
```bash
python3 scripts/test_benchmark_suite.py
```

### B. Prepare / Validate Synthetic Audio
```bash
# Generate deterministic local fixtures (no network)
python3 scripts/prepare_corpus.py --generate-synthetic

# Verify existing corpus audio format and duration specifications
# (fails with a clear message when audio is missing)
python3 scripts/prepare_corpus.py --verify
```

### C. Run the Smoke Suite
```bash
python3 scripts/benchmark_asr.py \
  --backend synthetic \
  --output-dir benchmark/results
```

### D. CLI Options
- `--quantizations`: Subset of quantizations to report on (e.g. `--quantizations Q8_0 Q5_K_M`).
- `--languages`: Subset of languages (e.g. `--languages es en`).
- `--clips`: Subset of clip IDs (e.g. `--clips synthetic_es_01 synthetic_en_01`).
- `--modes`: Reporting modes (`streaming`, `offline`).
- `--repetitions`: Number of repetitions per clip.
- `--warmup-runs`: Number of warmup passes per model.
- `--randomize-order`: Randomizes model order (irrelevant to the simulated values).
- `--output-dir`: Destination for the generated artifacts.

---

## 5. Interpreting Results

The runner writes three timestamped artifacts to `benchmark/results/`:

- `benchmark_results_<timestamp>.json`: metadata (`backend`, `simulated: true`,
  `disclaimer`, `recommendation_produced: false`) plus aggregates and per-clip records.
- `benchmark_results_<timestamp>.csv`: summary table; metric columns are prefixed
  `Sim_` and a `Simulated` column is included.
- `benchmark_report_<timestamp>.md`: begins with a **SIMULATED DATA** banner and
  includes the informational Pareto classification.

What the artifacts do **not** contain:

- Real WER / CER accuracy measurements.
- Real RTF, TTFP, or memory measurements.
- An automatic quantization recommendation or model-selection decision.

Synthetic numbers are useful only to prove that the pipeline runs deterministically
and produces well-formed outputs.
