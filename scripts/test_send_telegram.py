#!/usr/bin/env python3
"""Unit tests for send_telegram.py logic and HTML sanitization."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import send_telegram


class TestSendTelegram(unittest.TestCase):

    def test_caption_length_truncation(self):
        long_msg = "A" * 2000
        safe_ver = "0.2.2"
        safe_sha = "abc1234"
        header = f"✨ <b>Aura Transcribe v{safe_ver}</b> (<code>{safe_sha}</code>)\n\n"
        max_msg_len = send_telegram.MAX_CAPTION_LENGTH - len(header) - 10

        truncated = long_msg[:max_msg_len] + "..."
        caption = header + truncated
        self.assertLessEqual(len(caption), send_telegram.MAX_CAPTION_LENGTH)

    def test_html_escape(self):
        unsafe_msg = "feat: add <xml> support & fix 'quotes' & \"double\""
        escaped = send_telegram.html.escape(unsafe_msg)
        self.assertNotIn("<xml>", escaped)
        self.assertIn("&lt;xml&gt;", escaped)
        self.assertIn("&amp;", escaped)


if __name__ == "__main__":
    unittest.main()
