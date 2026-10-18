# Aura Transcribe v0.3.4 — Hardening Pass

`versionCode 49` (`v0.3.4`) — Stability and release-readiness hardening across the native bridge, overlay capture, subtitles, and CI gates. No new user-facing features.

### Key Highlights
- **Overlay single-capture:** removed the redundant `pushAudioDirect` path so the floating overlay consumes the shared cpal stream only, eliminating duplicate microphone capture.
- **Terminal-error cleanup:** recording sessions reset their state on terminal errors instead of staying stuck in "Processing".
- **16 KB alignment gate:** `checkNativeAlignment` is now self-sufficient and `verifyNativeAlignmentGate` exercises the real verdict logic against synthetic inputs.
- **Subtitle teardown + JNI safety:** fixed teardown ordering for subtitle resources and clear JNI exceptions at the boundary.
- **JNI parity gate:** added `check_jni_parity.py` to catch Java `native` declarations whose Rust exports drifted.
- **Test infra and docs:** corrected flaky harness assumptions and synced README/AGENTS factual drift.
