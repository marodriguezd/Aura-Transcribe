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


def post_multipart(url: str, fields: dict, files: dict = None, timeout: int = 45, max_retries: int = 3):
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

    for attempt in range(1, max_retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                resp_data = resp.read().decode("utf-8", errors="replace")
                return json.loads(resp_data)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            print(f"[send_telegram] Attempt {attempt} failed with HTTP {e.code}: {err_body}", file=sys.stderr)
            if e.code in (429, 500, 502, 503, 504) and attempt < max_retries:
                sleep_sec = 2 ** attempt
                print(f"[send_telegram] Retrying in {sleep_sec}s...", file=sys.stderr)
                time.sleep(sleep_sec)
                continue
            raise
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"[send_telegram] Attempt {attempt} connection error: {e}", file=sys.stderr)
            if attempt < max_retries:
                sleep_sec = 2 ** attempt
                print(f"[send_telegram] Retrying in {sleep_sec}s...", file=sys.stderr)
                time.sleep(sleep_sec)
                continue
            raise


def send_document(token: str, chat_id: str, apk_path: str, version: str, sha: str, commit_msg: str):
    url = f"https://api.telegram.org/bot{token}/sendDocument"

    safe_ver = html.escape(version.strip())
    safe_sha = html.escape(sha.strip())
    safe_msg = html.escape(commit_msg.strip())

    header = f"✨ <b>Aura Transcribe v{safe_ver}</b> (<code>{safe_sha}</code>)\n\n"
    max_msg_len = MAX_CAPTION_LENGTH - len(header) - 10
    if len(safe_msg) > max_msg_len:
        safe_msg = safe_msg[:max_msg_len] + "..."

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
    res = post_multipart(url, fields, files)
    if res.get("ok"):
        print("[send_telegram] Successfully sent APK to Telegram!")
    else:
        print(f"[send_telegram] Telegram API error: {res}", file=sys.stderr)
        sys.exit(1)


def send_failure_alert(token: str, chat_id: str, sha: str, log_files_str: str):
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
                    found_errs.append(f"<b>[{html.escape(lf)}]</b>\n<pre>{html.escape(extracted)}</pre>")
            except Exception as e:
                print(f"[send_telegram] Error reading log {lf}: {e}", file=sys.stderr)

    safe_sha = html.escape(sha.strip())
    header = f"🚨 <b>CI Workflow Failed</b> (<code>{safe_sha}</code>)\n\n"
    body_text = "\n\n".join(found_errs) if found_errs else "<i>No detailed error log found</i>"

    full_msg = header + body_text
    if len(full_msg) > MAX_MSG_LENGTH:
        full_msg = full_msg[: MAX_MSG_LENGTH - 20] + "...</pre>"

    fields = {
        "chat_id": chat_id,
        "text": full_msg,
        "parse_mode": "HTML",
    }

    print(f"[send_telegram] Sending failure alert for {safe_sha}...")
    res = post_multipart(url, fields)
    if res.get("ok"):
        print("[send_telegram] Successfully sent failure alert to Telegram!")
    else:
        print(f"[send_telegram] Telegram API error: {res}", file=sys.stderr)


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
    elif args.mode == "alert":
        send_failure_alert(args.token, args.chat_id, args.sha, args.logs)

    return 0


if __name__ == "__main__":
    sys.exit(main())
