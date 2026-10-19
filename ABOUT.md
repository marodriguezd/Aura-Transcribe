# About Aura Transcribe

**Aura Transcribe** is an open-source, on-device speech-to-text application for Android designed for private, low-latency dictation and transcription. It runs speech recognition locally on the device using safe Rust, ARM NEON SIMD optimizations, and a quantized Nemotron streaming ASR model running on GGML kernels.

The application integrates across Android through multiple surfaces: a system voice typing keyboard (IME), a voice recognition service, a dictation popup, an optional floating overlay with accessibility-assisted auto-paste, real-time live subtitles for device audio, and offline audio file transcription. It supports dynamic audio routing across internal microphones, USB, and Bluetooth headsets.

Speech recognition operates entirely offline; audio recordings never leave the device. Users can optionally configure external OpenAI-compatible services for text post-processing (formatting and cleanup), which transmits only transcribed text to the chosen provider.

Aura Transcribe is an independent fork of [Offline Voice Input](https://github.com/notune/android_transcribe_app) (v0.1.18) by Noah Mühl, and the application source code is distributed under the MIT License. Other third-party materials retain the terms described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The bundled Nemotron speech model is **not** MIT: it is a separately licensed data asset under the OpenMDW License Agreement 1.1, reproduced in [MODEL_LICENSE.md](MODEL_LICENSE.md).
