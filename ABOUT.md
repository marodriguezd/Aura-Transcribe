# About Aura Transcribe

> **Aura Transcribe** is a privacy-first, on-device speech recognition and AI post-processing system for Android.

## Key Facts
- **Application ID:** `com.auratranscribe.app` (fork of `dev.notune.transcribe`).
- **Core Engine:** Safe Rust (`cdylib`, 2021 edition) + `transcribe-cpp` (ARM64 whisper.cpp/GGML with NEON SIMD intrinsics `+dotprod+fp16`).
- **Platform:** Android 8.0+ (API 26) up to Android 17 (API 35+ ready), target ABI `arm64-v8a`, 16KB memory page size aligned.
- **Architecture:** Main application process + isolated `:ime` keyboard process communicating exclusively via atomic marker files in `filesDir()` (`MarkerFileHelper`).
- **Phonetic Correction:** Pure Rust decoupled crate (`crates/aura-core`) with Spanish/English Metaphone keys + $O(1)$ precomputed bigram cosine similarity.
- **AI Post-Processing:** Local on-device (S1-mini GGUF via `transcribe-cpp`) and remote OpenAI/Groq/Ollama APIs with privacy-safe redaction in release builds.
- **System Integration:** 6 surfaces — Keyboard IME (`RustInputMethodService`), Voice Recognition Service (`VoiceRecognitionService`), Popup (`RecognizeActivity`), Floating Dictation Overlay (`FloatingOverlayService` + `FloatingDictationAccessibilityService`), Live Subtitles (`LiveSubtitleService` + Google ML Kit), and File Transcription (`TranscribeFileActivity`).

For the complete implementation contract, JNI handshakes, and developer rules, refer to **[`AGENTS.md`](AGENTS.md)**.
