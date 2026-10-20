# Aura Transcribe v1.0.1

This release corrects a distribution-compliance defect and adds the gates that keep it corrected. Every APK and App Bundle now ships the complete license and notice set alongside the application: the MIT license with its upstream copyright notice, the third-party notices file, the full set of verbatim third-party license texts, and the bundled model's agreement and notice of origin. The previously published binaries carried the model weights but none of these texts; releasing a corrected build is this repository's remediation, and what further steps may be owed for the earlier binaries is an open question for external legal counsel, documented in the licensing audit.

A new release gate opens the signed artifacts and fails closed unless every required document is present exactly once, at its exact delivery path, and byte-identical to the repository sources. The upstream notice and the native license texts are now pinned by digest, so a rewritten or drifted copy cannot ship, and copyleft license expressions can no longer pass the dependency license inventory without explicit review.

There are no functional changes: the recognition engine, input surfaces, latency and memory behavior are unchanged.
