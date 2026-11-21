# Aura Transcribe v1.0.3

Aura Transcribe 1.0.3 delivers a major architectural upgrade to live subtitles and on-device subtitle translation for real-time audio and video playback.

In the native Rust engine, live subtitles now stream through cache-aware encoder sessions at 80 ms frame ticks whenever a streaming model is active, emitting words with minimal latency and constant per-frame compute cost while preserving the batched fallback pipeline for non-streaming models. A rolling-window adaptive noise-floor voice activity detector separates foreground speech from steady background music, paired with hybrid segmentation that finalizes utterances cleanly on sentence and clause punctuation plus short prosodic pauses.

For live translation, subtitles now render exclusively in the target language without flashing untranslated source characters. A sliding context window pairs the previous sentence with the active clause and an in-memory cache so distant language pairs such as Chinese to Spanish preserve subject, tense, and gender agreement through on-device translation. Source language resolution adds a fast function-word lexicon, session memory, an audio-language selector localized across all eight languages, background language-pack pre-warming, prefix agreement stabilization, partial opacity hierarchy, and a monotonic scroll window.
