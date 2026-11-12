"""Smoke/unit tests for DPGrabber PC. Offline only: every test uses a stub
transport or pure functions. No real request is sent, no real account is
used.

Run:  python3 -m unittest discover -s tests -v   (from the pc/ directory)
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config as cfgmod
from dpapi import (
    ApiClient,
    ApiError,
    aes_decrypt_b64,
    aes_encrypt_b64,
    extract_clerk,
    normalize_ms,
)
from engine import OrderFilter, compute_grab_wait


class FakeResponse:
    def __init__(self, status_code=200, body: bytes = b"{}"):
        self.status_code = status_code
        self.content = body


class StubTransport:
    """Records requests; returns scripted responses in order."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, headers=None, data=None, timeout=None, verify=None):
        self.calls.append(
            {"method": method, "url": url, "headers": dict(headers or {}),
             "data": data, "timeout": timeout, "verify": verify}
        )
        if not self.responses:
            raise AssertionError("stub transport ran out of responses")
        return self.responses.pop(0)


def _json_body(obj) -> bytes:
    return json.dumps(obj).encode("utf-8")


def make_client(responses, **overrides):
    cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
    return ApiClient(
        aes_key_hex=overrides.get("aes_key_hex", cfg["aes_key_hex"]),
        aes_iv_hex=overrides.get("aes_iv_hex", cfg["aes_iv_hex"]),
        aes_key_hex_alt=overrides.get("aes_key_hex_alt", cfg["aes_key_hex_alt"]),
        aes_iv_hex_alt=overrides.get("aes_iv_hex_alt", cfg["aes_iv_hex_alt"]),
        verify_tls=True,
        http=StubTransport(responses),
    )


class CryptoTests(unittest.TestCase):
    def test_hex_base64_roundtrip_primary(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        plain = json.dumps({"code": 0, "data": {"token": "t-123"}}).encode("utf-8")
        payload = aes_encrypt_b64(plain, cfg["aes_key_hex"], cfg["aes_iv_hex"])
        out = aes_decrypt_b64(payload, cfg["aes_key_hex"], cfg["aes_iv_hex"])
        self.assertEqual(json.loads(out), {"code": 0, "data": {"token": "t-123"}})

    def test_alt_key_fallback(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        plain = json.dumps({"code": 0, "list": []}).encode("utf-8")
        payload = aes_encrypt_b64(plain, cfg["aes_key_hex_alt"], cfg["aes_iv_hex_alt"])
        client = make_client([])
        decoded = client._decrypt_response_text(payload)
        self.assertEqual(decoded, {"code": 0, "list": []})

    def test_bad_key_length_rejected(self):
        with self.assertRaises(ValueError):
            aes_decrypt_b64("AAAA", "00" * 8, "00" * 16)

    def test_normalize_ms(self):
        self.assertEqual(normalize_ms(0), 0)
        self.assertEqual(normalize_ms(-5), 0)
        self.assertEqual(normalize_ms(1700000000), 1700000000000)   # seconds -> ms
        self.assertEqual(normalize_ms(1700000000000), 1700000000000)  # already ms


class EnvelopeTests(unittest.TestCase):
    def test_plain_object_response(self):
        client = make_client([])
        self.assertEqual(
            client.decode_response(_json_body({"response": {"code": 0, "data": {}}})),
            {"code": 0, "data": {}},
        )

    def test_plain_string_response(self):
        client = make_client([])
        inner = json.dumps({"code": 0, "list": [{"id": "1"}]})
        self.assertEqual(
            client.decode_response(_json_body({"response": inner})),
            {"code": 0, "list": [{"id": "1"}]},
        )

    def test_encrypted_response(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        payload = aes_encrypt_b64(
            json.dumps({"code": 0, "list": []}).encode("utf-8"),
            cfg["aes_key_hex"], cfg["aes_iv_hex"],
        )
        client = make_client([])
        self.assertEqual(
            client.decode_response(_json_body({"response": payload, "isEncrypted": True})),
            {"code": 0, "list": []},
        )

    def test_missing_code_rejected(self):
        client = make_client([])
        with self.assertRaises(ApiError) as ctx:
            client.decode_response(_json_body({"response": {"data": {}}}))
        self.assertEqual(ctx.exception.kind, "api")

    def test_auth_code(self):
        client = make_client([])
        with self.assertRaises(ApiError) as ctx:
            client.decode_response(_json_body({"code": 401}))
        self.assertEqual(ctx.exception.kind, "auth")

    def test_ip_code(self):
        client = make_client([])
        with self.assertRaises(ApiError) as ctx:
            client.decode_response(_json_body({"code": 1, "msg": "IP 受限"}))
        self.assertEqual(ctx.exception.kind, "ip")


class LoginFlowTests(unittest.TestCase):
    def _login_ok(self, token="tok-1"):
        return FakeResponse(200, _json_body({"code": 0, "data": {"token": token}}))

    def test_login_request_construction(self):
        client = make_client([self._login_ok()])
        session = client.login_account("13800138000", "secret")
        self.assertEqual(session.token, "tok-1")
        self.assertEqual(session.sid, "50")
        call = client.http.calls[0]
        self.assertEqual(call["method"], "POST")
        self.assertTrue(call["url"].endswith("/s/duopei-account/login"))
        self.assertEqual(call["headers"]["sid"], "-1")
        self.assertEqual(call["headers"]["authorization-token"], "")
        body = json.loads(call["data"].decode("utf-8"))
        self.assertEqual(body["method"], "password")
        self.assertEqual(body["phoneNumber"]["mobile"], "13800138000")
        self.assertEqual(body["phoneNumber"]["regionCode"], "CN")
        self.assertEqual(body["phoneNumber"]["mobileDial"], "86")
        self.assertEqual(body["password"], "secret")

    def test_full_session_flow(self):
        responses = [
            self._login_ok("tok-base"),
            FakeResponse(200, _json_body({
                "code": 0,
                "data": [
                    {"shopId": 91, "accountId": "a91"},
                    {"shopId": 92, "accountId": "a92"},
                ],
            })),
            FakeResponse(200, _json_body({"code": 0, "data": {"token": "tok-92"}})),
            FakeResponse(200, _json_body({"code": 0})),
        ]
        client = make_client(responses)
        session = client.session_for_account("13800138000", "secret", preferred_shop_id=92)
        self.assertEqual(session.token, "tok-92")
        self.assertEqual(session.sid, "92")
        # Shop 92 must be tried first (preferred), and the switch body is form-encoded.
        switch_call = client.http.calls[2]
        self.assertEqual(switch_call["method"], "POST")
        self.assertTrue(switch_call["url"].endswith("/s/duopei-account/switch-account"))
        self.assertEqual(switch_call["headers"]["sid"], "50")
        self.assertEqual(switch_call["data"], b"accountId=a92")
        self.assertEqual(
            switch_call["headers"]["Content-Type"],
            "application/x-www-form-urlencoded",
        )
        balance_call = client.http.calls[3]
        self.assertTrue(balance_call["url"].endswith("/s/balance/getBalance"))
        self.assertEqual(balance_call["headers"]["authorization-token"], "tok-92")
        self.assertEqual(balance_call["headers"]["sid"], "92")

    def test_login_rejected(self):
        client = make_client([FakeResponse(200, _json_body({"code": 400, "msg": "密码错误"}))])
        with self.assertRaises(ApiError) as ctx:
            client.login_account("13800138000", "wrong")
        self.assertEqual(ctx.exception.kind, "auth")

    def test_clerk_extraction(self):
        self.assertEqual(
            extract_clerk({"clerk": {"id": 7, "shopId": 92, "name": "测试"}})["name"], "测试"
        )
        self.assertIsNone(extract_clerk({"code": 0}))
        self.assertIsNone(extract_clerk({"clerk": {"id": 0}}))


class TimingTests(unittest.TestCase):
    def test_grab_time_math(self):
        order = {"createTime": 1760000000000, "reTime": 1760000010000, "confirmCountDown": 5}
        # reTime 1760000010 s + 5 s = 1760000015 s; now 1760000000 -> wait 15 s.
        wait, grab_ms, reason = compute_grab_wait(order, 1760000000.0)
        self.assertEqual(reason, "ok")
        self.assertEqual(grab_ms, 1760000015000)
        self.assertAlmostEqual(wait, 15.0)

    def test_grab_time_already_open(self):
        order = {"reTime": 1760000010000, "confirmCountDown": 5}
        wait, _, reason = compute_grab_wait(order, 1760000020.0)
        self.assertEqual((wait, reason), (0.0, "already_open"))

    def test_grab_time_missing_fields(self):
        wait, grab_ms, reason = compute_grab_wait({"id": "x"}, 1760000000.0)
        self.assertEqual((wait, grab_ms, reason), (0.0, None, "missing_fields"))
        wait, _, reason = compute_grab_wait({"reTime": 1760000010000}, 1760000000.0)
        self.assertEqual(reason, "missing_fields")

    def test_create_time_not_used(self):
        # Same reTime/countdown, different createTime -> same grab time.
        a = compute_grab_wait(
            {"createTime": 1000, "reTime": 1760000010000, "confirmCountDown": 5}, 1760000000.0
        )
        b = compute_grab_wait(
            {"createTime": 9999999999999, "reTime": 1760000010000, "confirmCountDown": 5},
            1760000000.0,
        )
        self.assertEqual(a, b)


class FilterTests(unittest.TestCase):
    def setUp(self):
        self.f = OrderFilter(
            min_amount=10.0, max_amount=100.0, memo_mode=0,
            memo_keywords=["勿扰", "test"], blacklist=["wx_bad"],
        )

    def base_order(self, **over):
        order = {"id": "o1", "totalAmount": 5000, "userWechatId": "wx_ok", "userMemo": ""}
        order.update(over)
        return order

    def test_pass(self):
        self.assertTrue(self.f.check(self.base_order())[0])

    def test_below_min(self):
        self.assertFalse(self.f.check(self.base_order(totalAmount=999))[0])

    def test_above_max(self):
        self.assertFalse(self.f.check(self.base_order(totalAmount=10001))[0])

    def test_max_zero_means_unlimited(self):
        f = OrderFilter(10.0, 0.0, 0, [], [])
        self.assertTrue(f.check(self.base_order(totalAmount=10 ** 9))[0])

    def test_blacklist(self):
        ok, reason = self.f.check(self.base_order(userWechatId="wx_bad"))
        self.assertFalse(ok)
        self.assertIn("黑名单", reason)

    def test_memo_mode_drop_any_memo(self):
        f = OrderFilter(10.0, 0.0, 1, [], [])
        self.assertFalse(f.check(self.base_order(userMemo="请快点"))[0])
        self.assertTrue(f.check(self.base_order(userMemo=""))[0])

    def test_keyword_case_insensitive_substring(self):
        ok, reason = self.f.check(self.base_order(userMemo="请勿扰，谢谢"))
        self.assertFalse(ok)
        self.assertIn("勿扰", reason)
        ok, _ = self.f.check(self.base_order(userMemo="TEST note"))
        self.assertFalse(ok)

    def test_missing_fields(self):
        self.assertFalse(self.f.check({"id": "o1"})[0])
        self.assertFalse(self.f.check({"totalAmount": 5000})[0])


class ConfigTests(unittest.TestCase):
    def test_template_has_no_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "dpgrabber_config.json")
            cfg = cfgmod.load(path)
            self.assertEqual(cfg["phone"], "")
            self.assertEqual(cfg["password"], "")
            self.assertTrue(os.path.exists(path))
            with open(path, encoding="utf-8") as fh:
                on_disk = json.load(fh)
            self.assertEqual(on_disk["phone"], "")
            self.assertEqual(on_disk["password"], "")

    def test_bad_aes_lengths_rejected(self):
        bad = dict(cfgmod.DEFAULT_CONFIG)
        bad["aes_key_hex"] = "00" * 8  # 8 bytes: neither 16 nor 32
        with self.assertRaises(cfgmod.ConfigError):
            cfgmod.validate(bad)
        bad = dict(cfgmod.DEFAULT_CONFIG)
        bad["aes_iv_hex"] = "00" * 8
        with self.assertRaises(cfgmod.ConfigError):
            cfgmod.validate(bad)

    def test_max_below_min_rejected(self):
        bad = dict(cfgmod.DEFAULT_CONFIG)
        bad["min_amount"] = 50.0
        bad["max_amount"] = 10.0
        with self.assertRaises(cfgmod.ConfigError):
            cfgmod.validate(bad)


if __name__ == "__main__":
    unittest.main()
