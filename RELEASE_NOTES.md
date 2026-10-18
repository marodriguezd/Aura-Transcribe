# Aura Transcribe v1.0.1 — Audio Routing Correctness & Release Integrity

Aura Transcribe 1.0.1 is a corrective hardening release focused on audio routing accuracy, release integrity, and repository hygiene.

Audio routing: output-only LE Audio speakers (`TYPE_BLE_SPEAKER`) are no longer accepted as microphone candidates anywhere; only input-capable devices can be selected, so the app no longer routes dictation to a speaker that cannot capture. Routing state is now reported truthfully — the app never claims an active or pre-warmed Bluetooth route when the platform rejected it — and the audio mode in effect before recording is saved and restored instead of being forced to normal. The explicit "Bluetooth only" input mode now fails with a clear, localized message instead of silently capturing from the built-in microphone, while AUTO mode keeps its documented built-in fallback.

Release integrity: the production signing certificate of the previously published release artifacts is now pinned in the repository, and the release pipeline fails if the release keystore, the APK, or the AAB signer deviates from that trusted fingerprint. A new repository-hygiene gate keeps agent-session state, build outputs, and secret-shaped tokens out of version control, and a branch ruleset now protects `main` with required CI checks and no force pushes.

Also included: corrected Android 14+ media-projection ordering documentation, and regression tests proving the API key never follows a cross-host HTTP redirect.
