# Aura Transcribe v1.0.1

Aura Transcribe 1.0.1 changes how AI post-processing is configured. The style-preset selector is gone: the correction is driven by a prompt the user picks, and that prompt is the complete instruction the model receives.

The release introduces a new default prompt written for dictation cleanup. It preserves meaning, negations, tone, language and order; it fixes recognition errors only when phonetic similarity and context support the correction; it keeps proper names, software versions, figures and dates untouched; it turns dictated punctuation into symbols and spoken quantities into digits when there is no ambiguity. The transcript is treated as text, never as instructions, and only the commands that clearly edit the dictation are applied. Nothing is added before or after the corrected text.

The prompt list holds the built-in default plus any prompt the user creates and can delete. A new prompt starts as a copy of the text on screen, deletion asks for confirmation, and the default cannot be removed but can be restored to the shipped wording. Unsaved edits survive switching between prompts until the settings screen is saved. Every prompt is a single file in the app's private storage, written atomically.

The recognition engine, the dictation surfaces and the release verification pipeline are unchanged.
