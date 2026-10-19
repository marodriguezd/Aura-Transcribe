# Device smoke scripts

## Post-processing smoke test

`scripts/smoke_postprocess_device.py` drives the **real** Android UI and the
real file-transcription flow. It is intentionally outside the APK, so release
builds do not gain a testing backdoor and no production manifest change is
needed.

The runner:

1. installs the supplied debug APK;
2. waits for the debug speech model to become `Ready`;
3. scrolls `MainActivity` until it finds the post-processing card;
4. opens the non-exported settings Activity through the real app button;
5. selects the provider by the clickable AutoComplete popup row (not by screen
   coordinates), enters the model and API key by resource ID, enables the
   feature, and saves;
6. pushes the WAV passed through the **required** `--audio` option (no audio file
   is bundled in the APK) and launches the exported audio VIEW intent;
7. asserts that the file screen displays a visible final result containing a
   deterministic QA marker, and has no fatal/ANR or PostProcessor error logs;
   the transient `Refining...` state is recorded when observed but is not a
   required assertion because a fast provider can pass through it between UI
   polls;
8. uninstalls the app and removes the temporary audio file in `finally`.

The default cleanup is deliberate: it removes the API key and all marker files
from the test device even when the test fails. Use `--keep-installed` only on a
private development phone while debugging a failure.

### Requirements

- a connected device visible through `adb devices`;
- a debug APK already built;
- Python 3.9+;
- the API key supplied through an environment variable, never as a CLI option;
- the device has network access to the selected OpenAI-compatible provider.

### Example

```bash
export GROQ_API_KEY='paste-a-short-lived-test-key-here'
python3 scripts/smoke_postprocess_device.py \
  --serial 192.168.1.45:39803 \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --audio /path/to/sample.wav
```

The script prints only test state and sanitized assertions. It never prints the
credential, Authorization header, marker contents, or the final transcript.
Do not put a real key in shell history, CI logs, a tracked file, or an APK.
Prefer a short-lived/revocable provider key and rotate it after testing.

### Provider/model overrides

The defaults target Groq and `openai/gpt-oss-120b`:

```bash
GROQ_API_KEY="$KEY" python3 scripts/smoke_postprocess_device.py \
  --serial "$ADB_SERIAL" \
  --apk "$APK" \
  --audio /path/to/sample.wav \
  --provider Groq \
  --model openai/gpt-oss-120b
```

For another preset, set the visible provider label and the matching model. The
script refuses to run without the environment-variable credential.
