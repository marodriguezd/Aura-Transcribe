# Model NOTICE of Modifications

**Artifact:** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`
**SHA-256:** `86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65`
**Base model:** [nvidia/nemotron-3.5-asr-streaming-0.6b](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) by NVIDIA Corporation
**GGUF distribution:** [handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) at revision `53ce288f69326b415aeef030cfcb02a08a8096dd`

---

## Upstream `NOTICE`, reproduced verbatim

This is the complete `NOTICE` file published with the GGUF conversion
([source](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/53ce288f69326b415aeef030cfcb02a08a8096dd/NOTICE)).
Line breaks are those of the original file; the text is otherwise unmodified.

```text
Modifications: handy-computer converted
https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b at commit
24b151a to GGUF for transcribe.cpp
(https://github.com/handy-computer/transcribe.cpp). Conversion repackages the
weights, renaming and re-laying-out tensors; the quantized files (Q8_0, Q6_K,
Q5_K_M, Q4_K_M) also store them at reduced numeric precision. The weights were
not retrained or fine-tuned.
```

## What this means for recipients

- The GGUF is a **converted, quantized repackaging** of the NVIDIA base model at
  upstream commit `24b151a`. Weights were neither retrained nor fine-tuned; only
  tensor layout and numeric precision changed.
- The conversion is attributed to `handy-computer` and to the
  `transcribe.cpp` toolchain, not to this repository. Aura Transcribe does not
  claim authorship of the model or of the conversion.
- This notice must travel with the model. It is packaged at
  `assets/MODEL_NOTICE.md` (APK) and
  `model_assets/assets/MODEL_NOTICE.md` (AAB), next to the GGUF itself, and the
  build/CI gates reject an artifact without it.

The model is governed by OpenMDW-1.1, not MIT. The full agreement is reproduced
in [`MODEL_LICENSE.md`](MODEL_LICENSE.md).
