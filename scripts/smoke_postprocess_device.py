#!/usr/bin/env python3
"""Run the real post-processing smoke test on a connected Android device.

The runner intentionally lives outside the APK. It drives the public MainActivity
UI, which is the correct way to reach the non-exported settings Activity, then
uses the real file-transcription intent. Credentials are accepted only through
an environment variable and are never printed, persisted, or passed as a CLI
argument.

Example:
    GROQ_API_KEY='...' python3 scripts/smoke_postprocess_device.py \
        --serial 192.168.1.45:39803 \
        --apk app/build/outputs/apk/debug/app-debug.apk

By default the app and the temporary audio file are removed in a finally block.
Use --keep-installed only when debugging a failed run; it deliberately leaves
the API key and marker configuration on the device and should never be used on
a shared phone.
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, Optional


# The runtime package an `am`/`pm` command targets is the *applicationId*.
# Components, on the other hand, are named by their Java class, which lives in
# the `dev.notune.transcribe` namespace. Both appear in the lines below and they
# are deliberately different strings.
PACKAGE = "com.auratranscribe.app"

# Resource ids are resolved by the runtime package too, NOT by the Java
# namespace: `dev.notune.transcribe:id/...` never matches anything on a device
# because the app is installed as com.auratranscribe.app. Every resource lookup
# below is built from this prefix so the two can never drift apart again.
RID_PREFIX = f"{PACKAGE}:id/"

MAIN_ACTIVITY = f"{PACKAGE}/dev.notune.transcribe.MainActivity"
TRANSCRIBE_ACTIVITY = f"{PACKAGE}/dev.notune.transcribe.TranscribeFileActivity"
REMOTE_AUDIO = f"/sdcard/Android/data/{PACKAGE}/files/freebuff-qa-bench.wav"
QA_PROMPT = (
    "Return only the corrected transcript. Preserve meaning and punctuation. "
    "Append the exact marker QA_POSTPROCESS_OK at the end. Transcript: ${output}"
)


class SmokeError(RuntimeError):
    """An actionable smoke-test failure."""


class UiDumpTransientError(SmokeError):
    """The hierarchy is temporarily unavailable while an Activity starts."""


class Adb:
    def __init__(self, serial: str, verbose: bool = False) -> None:
        self.serial = serial
        self.verbose = verbose

    def run(
        self,
        *args: str,
        check: bool = True,
        timeout: float = 30,
        capture: bool = True,
    ) -> str:
        command = ["adb", "-s", self.serial, *args]
        if self.verbose:
            # Never print arguments: a future command may contain a credential.
            print(f"  adb {args[0] if args else ''}")
        result = subprocess.run(
            command,
            text=True,
            capture_output=capture,
            timeout=timeout,
            check=False,
        )
        if check and result.returncode != 0:
            detail = (result.stderr or result.stdout or "adb command failed").strip()
            raise SmokeError(f"adb {args[0] if args else 'command'} failed: {detail[:300]}")
        return (result.stdout or "").strip()

    def shell(self, *args: str, **kwargs: object) -> str:
        return self.run("shell", *args, **kwargs)

    def tap(self, bounds: tuple[int, int, int, int]) -> None:
        left, top, right, bottom = bounds
        self.shell(
            "input",
            "tap",
            str((left + right) // 2),
            str((top + bottom) // 2),
        )

    def input_text(self, value: str) -> None:
        # Android's input command uses %s for spaces. This is the only point
        # where the credential crosses into adb. The runner never logs the
        # command, and callers must pass the key from an environment variable.
        # `input text` uses %s as its space escape. Quote the resulting
        # argument for the *device* shell as well: `${output}`, `$`, braces,
        # quotes and punctuation must reach Android literally. Passing a
        # subprocess list alone only protects the host shell; adb still sends
        # a command line to the device shell.
        if any(ord(char) < 0x20 or char in "\n\r\t" for char in value):
            raise SmokeError("input value contains unsupported control characters")
        encoded = value.replace("%", "%25").replace(" ", "%s")
        self.shell("input", "text", shlex.quote(encoded))

    def dump_ui(self) -> ET.Element:
        self.shell("uiautomator", "dump", "/sdcard/freebuff-qa-ui.xml", check=False)
        xml = self.shell("cat", "/sdcard/freebuff-qa-ui.xml", check=False)
        if not xml or "<hierarchy" not in xml:
            raise UiDumpTransientError("could not obtain a UIAutomator hierarchy")
        try:
            return ET.fromstring(xml)
        except ET.ParseError as exc:
            raise UiDumpTransientError(f"invalid UIAutomator XML: {exc}") from exc

    def activity(self) -> str:
        output = self.shell(
            "dumpsys",
            "activity",
            "activities",
            check=False,
        )
        for line in output.splitlines():
            if "mResumedActivity" in line or "mFocusedApp" in line:
                return line.strip()
        return ""


class Ui:
    def __init__(self, root: ET.Element) -> None:
        self.root = root

    def nodes(
        self,
        *,
        resource_id: Optional[str] = None,
        text: Optional[str] = None,
        contains: Optional[str] = None,
    ) -> Iterable[ET.Element]:
        for node in self.root.iter("node"):
            if resource_id is not None and node.attrib.get("resource-id") != resource_id:
                continue
            node_text = node.attrib.get("text", "")
            if text is not None and node_text != text:
                continue
            if contains is not None and contains not in node_text:
                continue
            yield node

    def first(self, **kwargs: object) -> Optional[ET.Element]:
        return next(iter(self.nodes(**kwargs)), None)

    @staticmethod
    def bounds(node: ET.Element) -> tuple[int, int, int, int]:
        raw = node.attrib.get("bounds", "")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
        if not match:
            raise SmokeError(f"node has invalid bounds: {raw!r}")
        return tuple(int(value) for value in match.groups())  # type: ignore[return-value]


def wait_until(
    adb: Adb,
    predicate,
    description: str,
    timeout: float,
    interval: float = 2.0,
):
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except UiDumpTransientError as exc:
            # The hierarchy can be unavailable for a short period while an
            # Activity or popup is starting; retry and retain the last detail.
            last_error = str(exc)
        except SmokeError:
            # A missing control/container is an actionable test failure, not a
            # transient Activity-start race. Preserve the useful diagnosis.
            raise
        except Exception as exc:  # UI can be transient while Activity starts.
            last_error = str(exc)
        time.sleep(interval)
    suffix = f" ({last_error})" if last_error else ""
    raise SmokeError(f"timed out waiting for {description}{suffix}")


def scroll_until(
    adb: Adb,
    resource_id: str,
    timeout: float = 20,
) -> ET.Element:
    def find():
        root = adb.dump_ui()
        ui = Ui(root)
        node = ui.first(resource_id=resource_id)
        if node is not None:
            return node

        # Swipe inside the actual visible ScrollView rather than relying on a
        # device-specific coordinate. This keeps working when the IME resizes
        # the settings viewport or when a device has a different resolution.
        scroll_nodes = [
            candidate for candidate in root.iter("node")
            if candidate.attrib.get("scrollable") == "true"
        ]
        if not scroll_nodes:
            raise SmokeError("target control is absent and no scrollable container is visible")
        scroll = max(
            scroll_nodes,
            key=lambda candidate: (
                Ui.bounds(candidate)[2] - Ui.bounds(candidate)[0]
            ) * (Ui.bounds(candidate)[3] - Ui.bounds(candidate)[1]),
        )
        left, top, right, bottom = Ui.bounds(scroll)
        x = (left + right) // 2
        start_y = max(top + 40, bottom - 80)
        end_y = min(bottom - 40, top + 80)
        if start_y <= end_y:
            raise SmokeError("scrollable container is too small to scroll")
        adb.shell(
            "input", "swipe", str(x), str(start_y), str(x), str(end_y), "650"
        )
        return None

    return wait_until(adb, find, f"UI control {resource_id}", timeout)


def tap_resource(adb: Adb, resource_id: str, timeout: float = 20) -> None:
    node = scroll_until(adb, resource_id, timeout)
    adb.tap(Ui.bounds(node))


def clear_field(adb: Adb, resource_id: str, timeout: float = 20) -> None:
    node = scroll_until(adb, resource_id, timeout)
    adb.tap(Ui.bounds(node))
    adb.shell("input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(160):
        adb.shell("input", "keyevent", "KEYCODE_DEL")


def visible_text(adb: Adb, resource_id: str) -> str:
    node = Ui(adb.dump_ui()).first(resource_id=resource_id)
    return node.attrib.get("text", "") if node is not None else ""


def choose_provider(adb: Adb, provider_label: str) -> None:
    tap_resource(adb, RID_PREFIX + "dropdown_provider")
    # AutoCompleteTextView renders its popup into the UI hierarchy. Selecting
    # the exact row avoids the previous failure where typing "Groq" left the
    # field on the previously selected OpenRouter item.
    def find_row():
        ui = Ui(adb.dump_ui())
        # The field itself may also have this text. Prefer the popup row, which
        # is clickable; this prevents a no-op tap on the already-selected field.
        for node in ui.nodes(text=provider_label):
            if (
                node.attrib.get("clickable") == "true"
                and node.attrib.get("resource-id")
                != RID_PREFIX + "dropdown_provider"
                and node.attrib.get("class") != "android.widget.AutoCompleteTextView"
            ):
                return node
        return None

    row = wait_until(adb, find_row, f"provider row {provider_label}", 10, 0.5)
    adb.tap(Ui.bounds(row))
    actual = visible_text(adb, RID_PREFIX + "dropdown_provider")
    if actual != provider_label:
        raise SmokeError(f"provider selection did not stick (got {actual!r})")


def set_model(adb: Adb, model: str) -> None:
    clear_field(adb, RID_PREFIX + "edit_model")
    adb.input_text(model)
    actual = visible_text(adb, RID_PREFIX + "edit_model")
    if actual != model:
        raise SmokeError("model field did not contain the requested model")


def set_api_key(adb: Adb, key: str) -> None:
    clear_field(adb, RID_PREFIX + "edit_api_key")
    adb.input_text(key)
    # Password fields are legitimately redacted by some UIAutomator/OEM
    # implementations. Do not inspect or compare the secret in the hierarchy;
    # the authenticated provider call below is the meaningful verification.


def set_qa_prompt(adb: Adb) -> None:
    # The prompt is deterministic and includes ${output}; this lets the final
    # assertion distinguish a real provider response from raw-text fallback.
    clear_field(adb, RID_PREFIX + "edit_prompt")
    adb.input_text(QA_PROMPT)
    actual = visible_text(adb, RID_PREFIX + "edit_prompt")
    if "QA_POSTPROCESS_OK" not in actual or "${output}" not in actual:
        raise SmokeError("QA prompt was not entered completely")


def enable_postprocessing(adb: Adb) -> None:
    node = scroll_until(adb, RID_PREFIX + "switch_pp_enabled")
    if node.attrib.get("checked") != "true":
        adb.tap(Ui.bounds(node))
    # Always re-dump after tapping. MaterialSwitch does not expose its checked
    # state through text, and retaining the pre-tap XML was the source of a
    # false negative in the first device automation attempt.
    node = Ui(adb.dump_ui()).first(
        resource_id=RID_PREFIX + "switch_pp_enabled"
    )
    if node is None or node.attrib.get("checked") != "true":
        raise SmokeError("post-processing switch did not become enabled")


def save_settings(adb: Adb, provider_label: str, model: str) -> None:
    # Validate the values again immediately before saving. This catches a
    # provider popup tap that visually looked successful but did not trigger
    # AutoCompleteTextView's item-click callback.
    actual_provider = visible_text(adb, RID_PREFIX + "dropdown_provider")
    actual_model = visible_text(adb, RID_PREFIX + "edit_model")
    if actual_provider != provider_label:
        raise SmokeError(f"provider changed before save (got {actual_provider!r})")
    if actual_model != model:
        raise SmokeError("model changed before save")

    # scroll_until locates the bottom button even when the keyboard has
    # resized the viewport. Do not send BACK here: if the keyboard is already
    # hidden, BACK would close the private settings Activity instead of merely
    # dismissing the IME, which caused an intermittent save failure in manual
    # automation.
    tap_resource(adb, RID_PREFIX + "btn_save")
    wait_until(
        adb,
        lambda: "MainActivity" in adb.activity(),
        "return to MainActivity after saving settings",
        10,
        0.5,
    )


def wait_for_model_ready(adb: Adb, timeout: float) -> None:
    adb.run("shell", "am", "start", "-n", MAIN_ACTIVITY)

    def ready_or_download():
        ui = Ui(adb.dump_ui())
        ready = ui.first(contains="Ready")
        if ready is not None:
            return True
        # Android's standard AlertDialog button has a stable resource ID even
        # when the app locale changes. Fall back to known translations only for
        # OEM/framework variants that omit the ID from the accessibility dump.
        button = ui.first(resource_id="android:id/button1")
        if button is None:
            for label in ("Download", "Descargar"):
                button = ui.first(text=label)
                if button is not None:
                    break
        if button is not None:
            adb.tap(Ui.bounds(button))
        return False

    wait_until(adb, ready_or_download, "speech engine Ready", timeout, 3.0)


def push_audio(adb: Adb, audio: Path) -> None:
    if not audio.is_file():
        raise SmokeError(f"audio file does not exist: {audio}")
    # Android 10+ scoped storage permits the app to access its own external
    # app-specific directory without broad storage permissions. The VIEW URI
    # below therefore avoids the unreliable public Download/file:// path.
    adb.shell("mkdir", "-p", f"/sdcard/Android/data/{PACKAGE}/files")
    adb.run("push", str(audio), REMOTE_AUDIO, timeout=60)
    adb.shell("test", "-s", REMOTE_AUDIO)


def run_file_transcription(adb: Adb, timeout: float) -> tuple[bool, bool]:
    # The Activity accepts VIEW audio/*; app-specific external storage is
    # readable by this package without a storage permission grant.
    adb.run(
        "shell",
        "am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-t",
        "audio/wav",
        "-d",
        f"file://{REMOTE_AUDIO}",
        "-n",
        TRANSCRIBE_ACTIVITY,
    )
    refining_seen = False
    result_seen = False
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ui = Ui(adb.dump_ui())
        status = ui.first(resource_id=RID_PREFIX + "txt_status")
        status_text = status.attrib.get("text", "") if status is not None else ""
        if "Refin" in status_text:
            refining_seen = True
        result_area = ui.first(resource_id=RID_PREFIX + "result_area")
        result = ui.first(resource_id=RID_PREFIX + "txt_result")
        result_text = result.attrib.get("text", "") if result is not None else ""
        result_visible = (
            result_area is not None
            and result_area.attrib.get("visible-to-user") == "true"
        )
        if "QA_POSTPROCESS_OK" in result_text and result_visible:
            result_seen = True
            break
        # Refining can be shorter than a normal polling interval on a fast
        # provider. Poll the hierarchy frequently enough to observe that
        # final-only stage while keeping the timeout bounded.
        time.sleep(0.35)
    return refining_seen, result_seen


def check_logcat(adb: Adb) -> tuple[int, int]:
    output = adb.run("logcat", "-d", "-v", "brief", "-t", "1200", check=False)
    fatal = sum("FATAL EXCEPTION" in line or "ANR in" in line for line in output.splitlines())
    pp_errors = sum(
        "PostProcessor" in line
        and any(word in line for word in ("API call failed", "Parse error", "API Error"))
        for line in output.splitlines()
    )
    return fatal, pp_errors


def uninstall(adb: Adb, require_success: bool = False) -> None:
    adb.shell("am", "force-stop", PACKAGE, check=False)
    output = adb.run("uninstall", PACKAGE, check=False)
    if require_success and "Success" not in output:
        raise SmokeError(f"uninstall did not succeed: {output[:200]}")
    if require_success and adb.shell("pm", "path", PACKAGE, check=False):
        raise SmokeError("package still exists after required uninstall")
    adb.shell("rm", "-f", REMOTE_AUDIO, check=False)



def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit adb serial")
    parser.add_argument("--apk", type=Path, required=True, help="debug APK to install")
    parser.add_argument("--audio", type=Path, help="WAV to transcribe (default: assets/bench.wav)")
    parser.add_argument("--provider", default="Groq", help="visible provider label")
    parser.add_argument("--model", default="openai/gpt-oss-120b")
    parser.add_argument("--api-key-env", default="GROQ_API_KEY")
    parser.add_argument("--model-timeout", type=float, default=900)
    parser.add_argument("--transcription-timeout", type=float, default=180)
    parser.add_argument(
        "--clean-install",
        action="store_true",
        help="allow uninstalling an existing package before the test; destructive",
    )
    parser.add_argument("--keep-installed", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    key = os.environ.get(args.api_key_env, "")
    if not key:
        print(f"ERROR: set {args.api_key_env} in the environment; never pass credentials as CLI arguments", file=sys.stderr)
        return 2
    if not args.apk.is_file():
        print(f"ERROR: APK not found: {args.apk}", file=sys.stderr)
        return 2
    audio = args.audio or Path(__file__).resolve().parents[1] / "app/src/main/assets/bench.wav"
    adb = Adb(args.serial, args.verbose)
    success = False
    installed_before = bool(adb.shell("pm", "path", PACKAGE, check=False))
    if installed_before and not args.clean_install:
        print(
            "ERROR: package is already installed; pass --clean-install only on a "
            "dedicated QA device because cleanup uninstalls it",
            file=sys.stderr,
        )
        return 2
    try:
        print("[1/7] Installing debug APK")
        if installed_before and args.clean_install:
            uninstall(adb, require_success=True)
        adb.run("install", "-r", str(args.apk), timeout=120)
        print("[2/7] Waiting for the speech engine")
        wait_for_model_ready(adb, args.model_timeout)
        print("[3/7] Opening post-processing settings through MainActivity")
        tap_resource(adb, RID_PREFIX + "btn_post_process")
        print("[4/7] Configuring provider/model/key through resource IDs")
        choose_provider(adb, args.provider)
        set_model(adb, args.model)
        set_api_key(adb, key)
        set_qa_prompt(adb)
        enable_postprocessing(adb)
        save_settings(adb, args.provider, args.model)
        print("[5/7] Running real file-transcription flow")
        adb.run("logcat", "-c", check=False)
        push_audio(adb, audio)
        refining_seen, result_seen = run_file_transcription(adb, args.transcription_timeout)
        fatal, pp_errors = check_logcat(adb)
        print(f"  refining_status_seen={refining_seen}")
        print(f"  final_result_visible_with_marker={result_seen}")
        print(f"  fatal_or_anr_count={fatal}")
        print(f"  postprocessor_error_count={pp_errors}")
        # `Refining...` is transient and may be gone before a 350 ms poll on a
        # fast provider. The durable assertion is the visible final result with
        # the deterministic QA marker, plus an error-free provider path.
        success = result_seen and fatal == 0 and pp_errors == 0
        if not success:
            raise SmokeError("the app post-processing smoke assertions did not all pass")
        print("[6/7] Smoke assertions passed")
        return 0
    except (SmokeError, subprocess.TimeoutExpired) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        if not args.keep_installed:
            print("[7/7] Removing temporary app state")
            try:
                uninstall(adb)
            except (SmokeError, subprocess.TimeoutExpired) as cleanup_error:
                # Do not hide the original assertion/provider failure. Emit a
                # sanitized cleanup warning so CI still makes the residue
                # actionable without exposing marker contents.
                print(f"WARNING: cleanup failed: {cleanup_error}", file=sys.stderr)
        else:
            print("[7/7] Keeping installation (--keep-installed); credential and markers remain on device")


if __name__ == "__main__":
    raise SystemExit(main())
