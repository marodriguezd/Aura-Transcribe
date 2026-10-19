# Model NOTICE of Modifications

**Artifact:** `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf`

**Base Model:** [nvidia/nemotron-3.5-asr-streaming-0.6b](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) by NVIDIA Corporation

**Quantized GGUF Distribution:** [handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) by handy-computer

---

Modifications: handy-computer converted https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b at commit 24b151a to GGUF for transcribe.cpp (https://github.com/handy-computer/transcribe.cpp). Conversion repackages the weights, renaming and re-laying-out tensors; the quantized files (Q8_0, Q6_K, Q5_K_M, Q4_K_M) also store them at reduced numeric precision. The weights were not retrained or fine-tuned.

This NOTICE is distributed together with the OpenMDW-1.1 license in `MODEL_LICENSE.md` and the model materials.
