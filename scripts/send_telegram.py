#!/usr/bin/env python3
"""Robust Telegram Bot notification and document delivery client.

Handles:
- Safe HTML escaping of commit messages and captions.
- Enforcing Telegram's 1024-character caption limit.
- Automatic exponential backoff retries for network glitches or rate limits (HTTP 429 / 5xx).
- Streaming multipart file uploads for APK artifacts.
- Extraction of concise compiler/test error logs for failure alerts.
"""
import argparse
import html
import json
import mimetypes
import os
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid

MAX_CAPTION_LENGTH = 1024
MAX_MSG_LENGTH = 4096

# Retryable HTTP statuses (rate limit + transient server errors).
RETRYABLE_HTTP_STATUSES = (429, 500, 502, 503, 504)


def is_retryable_http_status(status_code):
    """Pure decision: whether an HTTP status code is worth retrying."""
    return status_code in RETRYABLE_HTTP_STATUSES


def should_retry_http(status_code, attempt, max_retries):
    """Pure decision: retry an HTTP failure given the status, attempt and limit."""
    return is_retryable_http_status(status_code) and attempt < max_retries


def should_retry_network(attempt, max_retries):
    """Pure decision: retry a network/timeout failure given attempt and limit."""
    return attempt < max_retries


def backoff_seconds(attempt):
    """Pure decision: exponential backoff (2 ** attempt) used by post_multipart."""
    return 2 ** attempt


def escape_html(text):
    """HTML escaping actually used in production (delegates to html.escape)."""
    return html.escape(text)


# Backwards/alternative aliases for the same escaping helper.
html_escape = escape_html
sanitize_html = escape_html


def truncate_text(text, limit, suffix="..."):
    """Truncate text to at most ``limit`` chars, appending ``suffix`` when over."""
    if len(text) <= limit:
        return text
    return text[:limit] + suffix


def truncate_caption_message(safe_msg, max_msg_len):
    """Document/caption truncation exactly as used in send_document."""
    if len(safe_msg) > max_msg_len:
        safe_msg = safe_msg[:max_msg_len] + "..."
    return safe_msg


def truncate_alert_message(full_msg):
    """Message truncation exactly as used in send_failure_alert."""
    if len(full_msg) > MAX_MSG_LENGTH:
        full_msg = full_msg[:MAX_MSG_LENGTH - 20] + "...</pre>"
    return full_msg


# Aliases kept for callers/tests that prefer a generic name.
truncate = truncate_text
truncate_caption = truncate_caption_message
truncate_message = truncate_alert_message


def post_multipart(url: str, fields: dict, files: dict = None, timeout: int = 45,
                   max_retries: int = 3, transport=None):
    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    body = bytearray()

    for k, v in fields.items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode("utf-8"))
        body.extend(str(v).encode("utf-8"))
        body.extend(b"\r\n")

    if files:
        for k, filepath in files.items():
            if not os.path.exists(filepath):
                raise FileNotFoundError(f"File not found: {filepath}")
            filename = os.path.basename(filepath)
            mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
            body.extend(f"--{boundary}\r\n".encode("utf-8"))
            body.extend(f'Content-Disposition: form-data; name="{k}"; filename="{filename}"\r\n'.encode("utf-8"))
            body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode("utf-8"))
            with open(filepath, "rb") as f:
                body.extend(f.read())
            body.extend(b"\r\n")

    body.extend(f"--{boundary}--\r\n".encode("utf-8"))

    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "User-Agent": "AuraTranscribe-CI-Bot/1.0",
        },
    )

    opener = transport if transport is not None else urllib.request.urlopen

    for attempt in range(1, max_retries + 1):
        try:
            with opener(req, timeout=timeout) as resp:
                resp_data = resp.read().decode("utf-8", errors="replace")
                return json.loads(resp_data)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            print(f"[send_telegram] Attempt {attempt} failed with HTTP {e.code}: {err_body}", file=sys.stderr)
            if should_retry_http(e.code, attempt, max_retries):
                sleep_sec = backoff_seconds(attempt)
                print(f"[send_telegram] Retrying in {sleep_sec}s...", file=sys.stderr)
                time.sleep(sleep_sec)
                continue
            raise
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"[send_telegram] Attempt {attempt} connection error: {e}", file=sys.stderr)
            if should_retry_network(attempt, max_retries):
                sleep_sec = backoff_seconds(attempt)
                print(f"[send_telegram] Retrying in {sleep_sec}s...", file=sys.stderr)
                time.sleep(sleep_sec)
                continue
            raise


def send_document(token: str, chat_id: str, apk_path: str, version: str, sha: str, commit_msg: str,
                  transport=None):
    url = f"https://api.telegram.org/bot{token}/sendDocument"

    safe_ver = escape_html(version.strip())
    safe_sha = escape_html(sha.strip())
    safe_msg = escape_html(commit_msg.strip())

    header = f"\u2728 <b>Aura Transcribe v{safe_ver}</b> (<code>{safe_sha}</code>)\n\n"
    max_msg_len = MAX_CAPTION_LENGTH - len(header) - 10
    safe_msg = truncate_caption_message(safe_msg, max_msg_len)

    caption = header + safe_msg

    fields = {
        "chat_id": chat_id,
        "caption": caption,
        "parse_mode": "HTML",
    }
    files = {
        "document": apk_path,
    }

    print(f"[send_telegram] Uploading {apk_path} to Telegram...")
    res = post_multipart(url, fields, files, transport=transport)
    if isinstance(res, dict) and res.get("ok"):
        print("[send_telegram] Successfully sent APK to Telegram!")
        return True
    print(f"[send_telegram] Telegram API error: {res}", file=sys.stderr)
    sys.exit(1)


def send_failure_alert(token: str, chat_id: str, sha: str, log_files_str: str, transport=None):
    url = f"https://api.telegram.org/bot{token}/sendMessage"

    log_files = [f.strip() for f in log_files_str.split(",") if f.strip()]
    found_errs = []

    for lf in log_files:
        if os.path.exists(lf) and os.path.getsize(lf) > 0:
            try:
                with open(lf, "r", encoding="utf-8", errors="ignore") as f:
                    lines = f.readlines()
                errs = [
                    l.strip()
                    for l in lines
                    if any(
                        k in l
                        for k in [
                            "error[E",
                            "error:",
                            "CMake Error",
                            "FAILED:",
                            "undefined reference",
                            "cannot find",
                            "BUILD FAILED",
                            "FAILURE: Build failed",
                        ]
                    )
                ]
                if not errs:
                    errs = [l.strip() for l in lines[-15:] if l.strip()]
                if errs:
                    extracted = "\n".join(errs[-8:])
                    found_errs.append(f"<b>[{escape_html(lf)}]</b>\n<pre>{escape_html(extracted)}</pre>")
            except Exception as e:
                print(f"[send_telegram] Error reading log {lf}: {e}", file=sys.stderr)

    safe_sha = escape_html(sha.strip())
    header = f"\U0001f6a8 <b>CI Workflow Failed</b> (<code>{safe_sha}</code>)\n\n"
    body_text = "\n\n".join(found_errs) if found_errs else "<i>No detailed error log found</i>"

    full_msg = header + body_text
    full_msg = truncate_alert_message(full_msg)

    fields = {
        "chat_id": chat_id,
        "text": full_msg,
        "parse_mode": "HTML",
    }

    print(f"[send_telegram] Sending failure alert for {safe_sha}...")
    try:
        res = post_multipart(url, fields, transport=transport)
    except Exception as e:
        print(f"[send_telegram] Failed to send failure alert: {e}", file=sys.stderr)
        return False
    if isinstance(res, dict) and res.get("ok"):
        print("[send_telegram] Successfully sent failure alert to Telegram!")
        return True
    print(f"[send_telegram] Telegram API error: {res}", file=sys.stderr)
    return False


def main():
    parser = argparse.ArgumentParser(description="Aura Transcribe Telegram CI Notifier")
    parser.add_argument("--token", required=True, help="Telegram Bot Token")
    parser.add_argument("--chat-id", required=True, help="Telegram Chat ID")
    parser.add_argument("--mode", choices=["apk", "alert"], default="apk", help="Notification mode")
    parser.add_argument("--apk", help="Path to APK file")
    parser.add_argument("--version", default="0.0.0", help="App version")
    parser.add_argument("--sha", default="", help="Git commit SHA")
    parser.add_argument("--commit-msg", default="", help="Git commit message")
    parser.add_argument("--logs", default="", help="Comma-separated log file paths for alerts")

    args = parser.parse_args()

    if not args.token or not args.chat_id:
        print("[send_telegram] Missing bot token or chat ID, skipping notification.")
        return 0

    if args.mode == "apk":
        if not args.apk or not os.path.exists(args.apk):
            print(f"[send_telegram] APK file not specified or does not exist: {args.apk}", file=sys.stderr)
            sys.exit(1)
        send_document(args.token, args.chat_id, args.apk, args.version, args.sha, args.commit_msg)
        return 0
    elif args.mode == "alert":
        ok = send_failure_alert(args.token, args.chat_id, args.sha, args.logs)
        if not ok:
            # Best-effort alert: report the delivery failure as a warning-level
            # annotation and a non-zero exit so the step is visibly red, but this
            # only ever runs under `if: failure()` so it cannot turn a green
            # build into a broken pipeline.
            print("::warning title=TelegramAlertFailed::Failure alert could not be delivered to Telegram", file=sys.stderr)
            return 1
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
