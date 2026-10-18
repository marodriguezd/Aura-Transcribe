#!/usr/bin/env python3
"""Unit tests for send_telegram.py logic, escaping, truncation and failure paths."""
import io
import os
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import send_telegram


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def read(self):
        return self._payload.encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _transport(payload='{"ok": true}', exc=None, calls=None):
    def transport(req, timeout=None):
        if calls is not None:
            calls["n"] = calls.get("n", 0) + 1
        if exc is not None:
            if isinstance(exc, list):
                # raise on first N calls, then succeed
                if calls["n"] <= exc[0]:
                    raise exc[1]
            else:
                raise exc
        return _FakeResponse(payload)
    return transport


class TestTruncation(unittest.TestCase):
    def test_caption_no_truncation_at_boundary(self):
        max_msg_len = 100
        msg = "A" * max_msg_len
        self.assertEqual(send_telegram.truncate_caption_message(msg, max_msg_len), msg)

    def test_caption_truncation_one_over_boundary(self):
        max_msg_len = 100
        msg = "A" * (max_msg_len + 1)
        res = send_telegram.truncate_caption_message(msg, max_msg_len)
        self.assertEqual(res, "A" * max_msg_len + "...")
        self.assertEqual(len(res), max_msg_len + 3)

    def test_caption_truncation_large(self):
        long_msg = "A" * 2000
        header = "hdr"
        max_msg_len = send_telegram.MAX_CAPTION_LENGTH - len(header) - 10
        truncated = send_telegram.truncate_caption_message(long_msg, max_msg_len)
        caption = header + truncated
        self.assertLessEqual(len(caption), send_telegram.MAX_CAPTION_LENGTH)

    def test_alert_no_truncation_at_boundary(self):
        msg = "B" * send_telegram.MAX_MSG_LENGTH
        self.assertEqual(send_telegram.truncate_alert_message(msg), msg)

    def test_alert_truncation_one_over_boundary(self):
        msg = "B" * (send_telegram.MAX_MSG_LENGTH + 1)
        res = send_telegram.truncate_alert_message(msg)
        self.assertTrue(res.endswith("...</pre>"))
        self.assertLessEqual(len(res), send_telegram.MAX_MSG_LENGTH + 10)

    def test_generic_truncate_text(self):
        self.assertEqual(send_telegram.truncate_text("hello", 10), "hello")
        self.assertEqual(send_telegram.truncate_text("hello world", 5), "hello...")


class TestEscaping(unittest.TestCase):
    def test_escape_html_basic(self):
        unsafe = "feat: add <xml> support & fix 'quotes' & \"double\""
        escaped = send_telegram.escape_html(unsafe)
        self.assertNotIn("<xml>", escaped)
        self.assertIn("&lt;xml&gt;", escaped)
        self.assertIn("&amp;", escaped)

    def test_aliases_match(self):
        s = "a < b"
        self.assertEqual(send_telegram.html_escape(s), send_telegram.escape_html(s))
        self.assertEqual(send_telegram.sanitize_html(s), send_telegram.escape_html(s))

    def test_alert_uses_escaping_in_production(self):
        captured = {}

        def fake_post(url, fields, files=None, **kw):
            captured.update(fields)
            return {"ok": True}

        orig = send_telegram.post_multipart
        send_telegram.post_multipart = fake_post
        try:
            with tempfile.TemporaryDirectory() as td:
                log = os.path.join(td, "build.log")
                with open(log, "w") as f:
                    f.write("error: <bad> & stuff\n")
                send_telegram.send_failure_alert("tok", "chat", "sha", log, transport=None)
        finally:
            send_telegram.post_multipart = orig

        self.assertIn("&lt;bad&gt;", captured["text"])
        self.assertIn("&amp;", captured["text"])


class TestRetryBackoffDecisions(unittest.TestCase):
    def test_should_retry_http_retryable(self):
        for code in (429, 500, 502, 503, 504):
            self.assertTrue(send_telegram.should_retry_http(code, 1, 3))
            self.assertTrue(send_telegram.is_retryable_http_status(code))

    def test_should_retry_http_non_retryable(self):
        for code in (400, 401, 403, 404):
            self.assertFalse(send_telegram.should_retry_http(code, 1, 3))
            self.assertFalse(send_telegram.is_retryable_http_status(code))

    def test_should_retry_http_attempt_limit(self):
        self.assertFalse(send_telegram.should_retry_http(429, 3, 3))
        self.assertTrue(send_telegram.should_retry_http(429, 2, 3))

    def test_should_retry_network(self):
        self.assertTrue(send_telegram.should_retry_network(1, 3))
        self.assertTrue(send_telegram.should_retry_network(2, 3))
        self.assertFalse(send_telegram.should_retry_network(3, 3))

    def test_backoff_seconds(self):
        self.assertEqual(send_telegram.backoff_seconds(1), 2)
        self.assertEqual(send_telegram.backoff_seconds(2), 4)
        self.assertEqual(send_telegram.backoff_seconds(3), 8)

    def test_post_multipart_retries_http_429_then_succeeds(self):
        calls = {"n": 0}
        transport = _transport(payload='{"ok": true}', calls=calls)
        # make first call raise 429
        orig = transport

        def flaky(req, timeout=None):
            calls["n"] += 1
            if calls["n"] == 1:
                raise urllib.error.HTTPError("u", 429, "rate", {}, io.BytesIO(b""))
            return _FakeResponse('{"ok": true}')

        send_telegram.time.sleep = lambda _: None
        try:
            res = send_telegram.post_multipart("http://x", {"a": "b"}, transport=flaky, max_retries=3)
        finally:
            pass
        self.assertEqual(res, {"ok": True})
        self.assertEqual(calls["n"], 2)

    def test_post_multipart_retries_network_then_succeeds(self):
        calls = {"n": 0}

        def flaky(req, timeout=None):
            calls["n"] += 1
            if calls["n"] == 1:
                raise urllib.error.URLError("boom")
            return _FakeResponse('{"ok": true}')

        send_telegram.time.sleep = lambda _: None
        res = send_telegram.post_multipart("http://x", {"a": "b"}, transport=flaky, max_retries=3)
        self.assertEqual(res, {"ok": True})
        self.assertEqual(calls["n"], 2)


class TestFailurePath(unittest.TestCase):
    def test_ok_false_returns_false(self):
        transport = _transport(payload='{"ok": false}')
        result = send_telegram.send_failure_alert("tok", "chat", "sha", "", transport=transport)
        self.assertFalse(result)

    def test_success_returns_true(self):
        transport = _transport(payload='{"ok": true}')
        result = send_telegram.send_failure_alert("tok", "chat", "sha", "", transport=transport)
        self.assertTrue(result)

    def test_network_error_returns_false(self):
        transport = _transport(exc=urllib.error.URLError("boom"))
        result = send_telegram.send_failure_alert("tok", "chat", "sha", "", transport=transport)
        self.assertFalse(result)

    def test_http_error_returns_false(self):
        def transport(req, timeout=None):
            raise urllib.error.HTTPError("u", 500, "err", {}, io.BytesIO(b""))
        send_telegram.time.sleep = lambda _: None
        result = send_telegram.send_failure_alert("tok", "chat", "sha", "", transport=transport)
        self.assertFalse(result)


if __name__ == "__main__":
    unittest.main()
