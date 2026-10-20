# Aura Transcribe v1.0.0

This is the corrected rebuild of the v1.0.0 release (versionCode 51). It replaces binaries that carried the bundled model weights but none of the license texts the distribution requires. Every APK and App Bundle produced from this commit ships the complete license and notice set alongside the application: the MIT license with its upstream copyright notice, the third-party notices file, the full set of verbatim third-party license texts, and the bundled model's agreement and notice of origin, each byte-identical to the repository sources and placed at its exact delivery path.

A release gate now opens the signed artifacts and fails closed unless every required document is present exactly once: model bytes are checked against the pinned digest, the signer against the pinned production certificate, and native libraries against the 16 KB alignment requirement. The upstream notice and the native license texts are pinned by digest, so a drifted copy cannot ship, and copyleft expressions cannot pass the dependency inventory without explicit review.

Functionally identical to the originally published build: same recognition engine, input surfaces, latency and memory behavior. Copies of the earlier binaries already downloaded cannot be recalled; the licensing audit records the remediation and its limits.
