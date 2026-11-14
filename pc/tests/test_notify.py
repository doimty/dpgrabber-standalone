"""Push channel tests: routing, payload shape, and failures never raise."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from urllib.parse import parse_qs

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from notify import Notifier


class Recorder:
    def __init__(self, status=200, text='{"code":0}'):
        self.calls = []
        self.status = status
        self.text = text

    def __call__(self, url, body, headers, timeout):
        self.calls.append((url, body.decode("utf-8"), dict(headers), timeout))
        return self.status, self.text


class NotifyTests(unittest.TestCase):
    def test_disabled_sends_nothing(self):
        rec = Recorder()
        n = Notifier({"notify_enabled": False, "notify_key": "k"}, post=rec)
        self.assertFalse(n.send("t", "b"))
        self.assertEqual(rec.calls, [])

    def test_serverchan_form_payload(self):
        rec = Recorder(text='{"code":0,"message":""}')
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "serverchan", "notify_key": "SCT123"},
            post=rec,
        )
        self.assertTrue(n.send("抢单成功", "订单 1"))
        url, body, headers, _ = rec.calls[0]
        self.assertIn("sctapi.ftqq.com/SCT123.send", url)
        form = parse_qs(body)
        self.assertEqual(form["title"], ["抢单成功"])
        self.assertEqual(form["desp"], ["订单 1"])
        self.assertIn("x-www-form-urlencoded", headers["Content-Type"])

    def test_pushplus_json_payload(self):
        rec = Recorder(text='{"code":200,"msg":"ok"}')
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "pushplus", "notify_key": "tok"},
            post=rec,
        )
        self.assertTrue(n.send("t", "b"))
        url, body, headers, _ = rec.calls[0]
        self.assertIn("pushplus.plus/send", url)
        self.assertEqual(json.loads(body)["token"], "tok")
        self.assertEqual(headers["Content-Type"], "application/json")

    def test_webhook_needs_http_url(self):
        rec = Recorder()
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "webhook", "notify_webhook": "ftp://x"},
            post=rec,
        )
        self.assertFalse(n.send("t", "b"))
        self.assertEqual(rec.calls, [])

    def test_webhook_posts_json(self):
        rec = Recorder(status=204, text="")
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "webhook",
             "notify_webhook": "https://example.test/hook"},
            post=rec,
        )
        self.assertTrue(n.send("t", "b"))
        self.assertEqual(json.loads(rec.calls[0][1]), {"title": "t", "body": "b"})

    def test_transport_error_is_swallowed(self):
        def boom(*a, **k):
            raise OSError("network down")

        n = Notifier(
            {"notify_enabled": True, "notify_provider": "serverchan", "notify_key": "k"},
            post=boom,
        )
        self.assertFalse(n.send("t", "b"))

    def test_rejected_response_is_false(self):
        rec = Recorder(status=200, text='{"code":400,"message":"bad key"}')
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "serverchan", "notify_key": "k"},
            post=rec,
        )
        self.assertFalse(n.send("t", "b"))

    def test_unknown_provider_falls_back(self):
        rec = Recorder()
        n = Notifier(
            {"notify_enabled": True, "notify_provider": "nope", "notify_key": "k"}, post=rec
        )
        self.assertEqual(n.provider, "serverchan")


if __name__ == "__main__":
    unittest.main()
