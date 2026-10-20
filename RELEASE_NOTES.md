# Aura Transcribe v1.0.0

Aura Transcribe 1.0.0 is the first stable release of an offline voice-to-text application for Android. Recognition runs entirely on the device: no audio and no transcript leaves the phone unless optional AI post-processing is explicitly enabled, and it is off by default.

The app plugs into the surfaces keyboards and other applications already use: the system voice dialog, the Android SpeechRecognizer service, and a full input-method keyboard. It also offers a floating dictation overlay, live subtitles over system audio, and transcription of shared audio files.

Optional post-processing sends the final transcript to any OpenAI-compatible provider the user configures, with the API key encrypted at rest by a hardware-backed keystore. The transcript is sent once, only after recognition ends, and the raw text is always delivered when a request fails, times out or is cancelled. Live subtitles can be translated on the device.

The release ships the bundled recognition model together with its complete licence and notice set. Signed artifacts are built and verified by an automated release pipeline: the signer certificate and the model digest are pinned, native libraries are checked for 16 KB page alignment, and every required document must sit at its exact delivery path or the build stops before publishing.

The interface is available in eight languages. On-device AI post-processing is not part of this build; cloud providers are.
