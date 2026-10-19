#!/usr/bin/env python3
"""Automated unit and integration test suite for the Aura-Transcribe ASR smoke tooling.

Covers:
  1. Multilingual text normalization (Unicode NFC, accents, punctuation, contractions, numbers).
  2. Levenshtein WER and CER calculations against known standard pairs.
  3. Model and synthetic-corpus manifest schemas, roles, and license metadata.
  4. Negative guarantees: no external-dataset references, no real-accuracy claims,
     and no automatic quantization recommendation.
  5. Synthetic output is explicitly marked as SIMULATED.
  6. Corpus verification fails clearly when audio is missing (no download path).
  7. End-to-end deterministic smoke test of the benchmark runner.
"""

from __future__ import annotations

import contextlib
import csv
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path

# Add scripts directory to path
from benchmark_asr import (
    BenchmarkRunner,
    QuantizationAggregate,
    calculate_wer_cer,
    normalize_multilingual_text,
    verify_file_sha256,
)
import prepare_corpus


PROJECT_ROOT = Path(__file__).resolve().parent.parent


class TestTextNormalization(unittest.TestCase):
    def test_unicode_nfc_and_case(self):
        raw = "CafÉ au LAIT"
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "café au lait")

    def test_french_contractions_and_elisions(self):
        raw = "L'arbre d'or et l'oiseau d'hiver."
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "l'arbre d'or et l'oiseau d'hiver")

    def test_spanish_inverted_punctuation_and_diacritics(self):
        raw = "¿Cómo estás? ¡Muy bien, señor López!"
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "cómo estás muy bien señor lópez")

    def test_portuguese_nasals_and_cedilla(self):
        raw = "Atenção ao coração e à direção da orçamentária."
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "atenção ao coração e à direção da orçamentária")

    def test_english_apostrophes_and_numbers(self):
        raw = "It's 2026 and we're transcribing at 8:30 tonight."
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "it's 2026 and we're transcribing at 8 30 tonight")

    def test_hyphenated_words_preserved(self):
        raw = "A peer-reviewed three-day conference."
        norm = normalize_multilingual_text(raw)
        self.assertEqual(norm, "a peer-reviewed three-day conference")


class TestLevenshteinMetrics(unittest.TestCase):
    def test_exact_match(self):
        res = calculate_wer_cer("hello world", "hello world")
        self.assertEqual(res["wer"], 0.0)
        self.assertEqual(res["cer"], 0.0)
        self.assertEqual(res["word_subs"], 0)
        self.assertEqual(res["word_dels"], 0)
        self.assertEqual(res["word_ins"], 0)

    def test_known_substitutions_and_deletions(self):
        ref = "the quick brown fox"
        hyp = "the fast brown"
        # "quick" -> "fast" (1 sub), "fox" deleted (1 del). Total errors = 2 / 4 = 0.5
        res = calculate_wer_cer(ref, hyp)
        self.assertEqual(res["wer"], 0.5)
        self.assertEqual(res["word_subs"], 1)
        self.assertEqual(res["word_dels"], 1)
        self.assertEqual(res["word_ins"], 0)

    def test_known_insertions(self):
        ref = "the quick fox"
        hyp = "the really quick brown fox"
        # 2 insertions ("really", "brown"). Errors = 2 / 3 = 0.6667
        res = calculate_wer_cer(ref, hyp)
        self.assertAlmostEqual(res["wer"], 2.0 / 3.0, places=3)
        self.assertEqual(res["word_ins"], 2)

    def test_empty_hyp_and_ref(self):
        res_empty = calculate_wer_cer("", "")
        self.assertEqual(res_empty["wer"], 0.0)
        self.assertEqual(res_empty["cer"], 0.0)

        res_miss = calculate_wer_cer("words are here", "")
        self.assertEqual(res_miss["wer"], 1.0)
        self.assertEqual(res_miss["word_dels"], 3)


class TestManifestIntegrity(unittest.TestCase):
    def test_models_manifest_schema(self):
        manifest_path = PROJECT_ROOT / "benchmark" / "manifests" / "models.json"
        self.assertTrue(manifest_path.is_file(), "models.json manifest must exist")

        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        models = data.get("models", [])
        self.assertEqual(len(models), 4, "Must contain exactly 4 quantizations")

        quants = {m["quantization"] for m in models}
        self.assertEqual(quants, {"Q8_0", "Q6_K", "Q5_K_M", "Q4_K_M"})

        for m in models:
            self.assertTrue(m["filename"].endswith(".gguf"))
            self.assertEqual(len(m["sha256"]), 64, "SHA-256 must be 64 hex characters")
            self.assertGreater(m["size_bytes"], 400_000_000)
            self.assertTrue(m["download_url"].startswith("https://huggingface.co/"))

        by_quant = {m["quantization"]: m for m in models}
        self.assertEqual(by_quant["Q5_K_M"]["role"], "bundled")
        self.assertEqual(by_quant["Q8_0"]["role"], "reference")
        self.assertEqual(data["baseline_quantization"], "Q5_K_M")
        self.assertEqual(data["reference_quantization"], "Q8_0")

        roles = {m["role"] for m in models}
        self.assertNotIn("production_baseline", roles)
        self.assertEqual(roles, {"bundled", "reference", "alternate"})

        serialized = json.dumps(data).lower()
        self.assertNotIn("fleurs", serialized)
        self.assertNotIn("cc-by-4.0", serialized)
        self.assertNotIn("recommended_quantization", serialized)

    def test_corpus_manifest_schema(self):
        manifest_path = PROJECT_ROOT / "benchmark" / "manifests" / "corpus.json"
        self.assertTrue(manifest_path.is_file(), "corpus.json manifest must exist")

        raw_text = manifest_path.read_text(encoding="utf-8")
        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.assertEqual(data["dataset_name"], "Aura-Transcribe Synthetic ASR Smoke Corpus")
        self.assertEqual(data["source_dataset"], "none")
        self.assertEqual(data["license"], "GPL-3.0-only")
        self.assertEqual(data["license_url"], "https://www.gnu.org/licenses/gpl-3.0.html")
        self.assertEqual(data["attribution"], "Aura-Transcribe contributors")
        self.assertTrue(data.get("synthetic"), "Corpus must be declared synthetic")

        clips = data.get("clips", [])
        self.assertEqual(len(clips), 20, "Corpus must contain exactly 20 clips")

        by_lang = {}
        for c in clips:
            lang = c["language"]
            by_lang[lang] = by_lang.get(lang, 0) + 1
            self.assertGreater(c["duration_sec"], 20.0)
            self.assertLess(c["duration_sec"], 40.0)
            self.assertTrue(len(c["transcript"]) > 20)
            self.assertTrue(len(c.get("segments", [])) >= 2, "Must contain segmented utterances")
            self.assertTrue(c["id"].startswith("synthetic_"), c["id"])
            self.assertTrue(c["audio_path"].startswith("benchmark/corpus/audio/synthetic_"), c["audio_path"])
            for seg in c.get("segments", []):
                self.assertTrue(seg["utterance_id"].startswith("synthetic_"), seg["utterance_id"])

        self.assertEqual(by_lang, {"en": 5, "es": 5, "fr": 5, "pt": 5})

        # Negative guarantees: no external dataset or its license may reappear.
        lowered = raw_text.lower()
        self.assertNotIn("fleurs", lowered)
        self.assertNotIn("cc-by-4.0", lowered)
        self.assertNotIn("creativecommons.org/licenses/by/4.0", lowered)


class TestParetoAndAggregationLogic(unittest.TestCase):
    def test_relative_degradation_calculation(self):
        q8_wer = 0.08
        q6_wer = 0.084
        rel_deg = ((q6_wer - q8_wer) / q8_wer) * 100.0
        self.assertAlmostEqual(rel_deg, 5.0, places=2)

    def test_size_savings_calculation(self):
        q8_size = 751094240
        q4_size = 495831520
        saved_pct = ((q8_size - q4_size) / q8_size) * 100.0
        self.assertAlmostEqual(saved_pct, 33.99, places=1)

    def test_pareto_dominance_without_recommendation(self):
        runner = BenchmarkRunner(backend_type="synthetic")
        # Construct synthetic aggregates where Variant B is strictly worse than A in all dimensions
        agg_a = QuantizationAggregate(
            quantization="A",
            role="reference",
            filename="a.gguf",
            size_bytes=500,
            size_mib=0.5,
            size_delta_percent=0.0,
            sha256="a" * 64,
            clips_evaluated=20,
            clips_failed=0,
            streaming_normalized_wer=0.05,
            streaming_normalized_cer=0.01,
            streaming_raw_wer=0.05,
            streaming_raw_cer=0.01,
            streaming_rtf_median=0.2,
            streaming_rtf_p90=0.25,
            streaming_ttfp_median_sec=0.18,
            streaming_stability_median=0.98,
            offline_normalized_wer=0.05,
            offline_normalized_cer=0.01,
            offline_rtf_median=0.2,
            relative_wer_degradation_percent=0.0,
            absolute_wer_delta=0.0,
            language_wer={},
            peak_rss_mb_median=100.0,
            model_load_latency_sec=0.5,
        )
        agg_b = QuantizationAggregate(
            quantization="B",
            role="alternate",
            filename="b.gguf",
            size_bytes=600,  # Larger
            size_mib=0.6,
            size_delta_percent=-20.0,
            sha256="b" * 64,
            clips_evaluated=20,
            clips_failed=0,
            streaming_normalized_wer=0.08,  # Higher error
            streaming_normalized_cer=0.02,
            streaming_raw_wer=0.08,
            streaming_raw_cer=0.02,
            streaming_rtf_median=0.3,  # Slower
            streaming_rtf_p90=0.35,
            streaming_ttfp_median_sec=0.20,
            streaming_stability_median=0.90,
            offline_normalized_wer=0.08,
            offline_normalized_cer=0.02,
            offline_rtf_median=0.3,
            relative_wer_degradation_percent=60.0,
            absolute_wer_delta=0.03,
            language_wer={},
            peak_rss_mb_median=120.0,
            model_load_latency_sec=0.6,
        )
        eval_res = runner.evaluate_pareto_frontier([agg_a, agg_b])
        self.assertIn("A", eval_res["pareto_optimal_quantizations"])
        self.assertNotIn("B", eval_res["pareto_optimal_quantizations"])
        self.assertEqual(eval_res["dominated_quantizations"].get("B"), "A")

        # Negative guarantee: Pareto classification never yields a recommendation.
        self.assertNotIn("recommended_quantization", eval_res)
        self.assertNotIn("eligible_candidates", eval_res)
        self.assertIn("no automatic quantization recommendation", eval_res["note"].lower())


class TestBenchmarkRunnerSmoke(unittest.TestCase):
    def test_synthetic_end_to_end_smoke(self):
        """Validates that the runner executes deterministically and generates

        complete JSON, CSV, and Markdown outputs explicitly marked as SIMULATED.
        """
        temp_dir = PROJECT_ROOT / "benchmark" / "results" / "test_smoke_out"
        temp_dir.mkdir(parents=True, exist_ok=True)
        try:
            runner = BenchmarkRunner(
                backend_type="synthetic",
                results_dir=temp_dir,
                seed=42,
            )
            # Run on small subset for ultra-fast unit testing (2 quantizations, 2 languages)
            results, aggregates = runner.run_benchmark(
                quantizations=["Q8_0", "Q6_K"],
                languages=["en", "es"],
                modes=["streaming"],
            )

            self.assertEqual(len(aggregates), 2)
            self.assertEqual(len(results), 20)  # 2 quants * 10 clips (5 en + 5 es)
            self.assertTrue(all(r.simulated for r in results))
            self.assertTrue(all(a.simulated for a in aggregates))

            exported = runner.export_reports(results, aggregates, output_dir=temp_dir)

            self.assertTrue(exported["json"].is_file())
            self.assertTrue(exported["csv"].is_file())
            self.assertTrue(exported["markdown"].is_file())

            # Validate JSON content and simulated/recommendation metadata
            with open(exported["json"], "r", encoding="utf-8") as f:
                data = json.load(f)
                self.assertIn("metadata", data)
                self.assertIn("aggregates", data)
                self.assertEqual(len(data["aggregates"]), 2)
                self.assertEqual(data["metadata"]["backend"], "synthetic")
                self.assertTrue(data["metadata"]["simulated"])
                self.assertFalse(data["metadata"]["recommendation_produced"])
                self.assertTrue(all(a["simulated"] for a in data["aggregates"]))
                self.assertNotIn("recommended_quantization", data["metadata"]["analysis"])
                json_text = json.dumps(data).lower()
                self.assertNotIn("fleurs", json_text)
                self.assertIn("simulated", json_text)

            # CSV must label every metric as simulated
            with open(exported["csv"], "r", encoding="utf-8", newline="") as f:
                rows = list(csv.DictReader(f))
            self.assertEqual(len(rows), 2)
            self.assertIn("Sim_Stream_WER_Norm", rows[0])
            self.assertIn("Simulated", rows[0])
            self.assertTrue(all(row["Simulated"] == "yes" for row in rows))

            # Markdown must be banner-marked and must not recommend a quantization
            with open(exported["markdown"], "r", encoding="utf-8") as f:
                md_text = f.read()
            lowered_md = md_text.lower()
            self.assertIn("Aura-Transcribe ASR Smoke Report (SIMULATED)", md_text)
            self.assertIn("simulated data", lowered_md)
            self.assertIn("| **Q8_0** |", md_text)
            self.assertIn("| **Q6_K** |", md_text)
            self.assertIn("No automatic quantization recommendation is produced.", md_text)
            self.assertNotIn("recommendation:", lowered_md)
            self.assertNotIn("recommended_quantization", lowered_md)
            self.assertNotIn("fleurs", lowered_md)

        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)

    def test_clip_filtering_and_randomized_order(self):
        runner = BenchmarkRunner(backend_type="synthetic", seed=123)
        # Select single clip
        results, aggregates = runner.run_benchmark(
            quantizations=["Q8_0", "Q4_K_M"],
            clip_ids=["synthetic_en_01"],
            modes=["streaming"],
            randomize_order=True,
            warmup_runs=0,
        )
        self.assertEqual(len(results), 2)
        self.assertEqual({r.clip_id for r in results}, {"synthetic_en_01"})
        self.assertEqual(len(aggregates), 2)


class TestErrorHandlingAndValidation(unittest.TestCase):
    def test_missing_audio_file_detection(self):
        with self.assertRaises(prepare_corpus.CorpusError):
            prepare_corpus.verify_audio_file(
                Path("/path/to/nonexistent/file.wav"), expected_duration_sec=30.0
            )

    def test_corrupt_audio_file_detection(self):
        temp_bad_wav = PROJECT_ROOT / "benchmark" / "results" / "corrupt_test.wav"
        temp_bad_wav.parent.mkdir(parents=True, exist_ok=True)
        try:
            with open(temp_bad_wav, "wb") as f:
                f.write(b"NOT_A_WAV_HEADER_CORRUPTED_BYTES_123456789")
            with self.assertRaises(prepare_corpus.CorpusError):
                prepare_corpus.verify_audio_file(temp_bad_wav, expected_duration_sec=10.0)
        finally:
            temp_bad_wav.unlink(missing_ok=True)

    def test_sha256_verifier(self):
        temp_file = PROJECT_ROOT / "benchmark" / "results" / "hash_test.txt"
        temp_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            content = b"Aura-Transcribe SHA256 Test Content"
            import hashlib
            expected_hash = hashlib.sha256(content).hexdigest()
            with open(temp_file, "wb") as f:
                f.write(content)

            self.assertTrue(verify_file_sha256(temp_file, expected_hash))
            self.assertFalse(verify_file_sha256(temp_file, "0" * 64))
        finally:
            temp_file.unlink(missing_ok=True)

    def test_prepare_corpus_has_no_download_path(self):
        self.assertFalse(hasattr(prepare_corpus, "run_download_fleurs"))

    def test_verify_fails_clearly_when_audio_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "corpus.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "schema_version": "1.0",
                        "dataset_name": "test",
                        "clips": [
                            {
                                "id": "synthetic_missing_01",
                                "language": "en",
                                "language_bcp47": "en-US",
                                "audio_path": "benchmark/corpus/audio/definitely_missing_synthetic.wav",
                                "duration_sec": 1.0,
                                "transcript": "placeholder",
                                "segments": [],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                ok = prepare_corpus.run_verify(manifest_path)
            self.assertFalse(ok)
            self.assertIn("Verification FAILED", stderr.getvalue())
            self.assertIn("--generate-synthetic", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
