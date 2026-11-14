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
    Session,
    aes_decrypt_b64,
    aes_encrypt_b64,
    extract_clerk,
    normalize_ms,
)
from engine import (
    Engine,
    OrderFilter,
    classify_confirm,
    confirm_budget_seconds,
    reconcile_order,
    terminal_reason,
)


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
        # md5("secret"): the platform compares the digest, not the plaintext
        self.assertEqual(body["password"], "5ebe2294ecd0e0f08eab7690d2a6ee69")

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


class ConfirmLogicTests(unittest.TestCase):
    """Confirm state machine pure helpers (DPGConfirmationTask)."""

    def test_budget_uses_window_with_create_time(self):
        order = {"createTime": 1760000000000}
        self.assertEqual(confirm_budget_seconds(order, 20, 20), 20.0)
        self.assertEqual(confirm_budget_seconds(order, 30, 20), 30.0)

    def test_budget_uses_fallback_without_create_time(self):
        self.assertEqual(confirm_budget_seconds({}, 30, 20), 20.0)
        self.assertEqual(confirm_budget_seconds({"createTime": 0}, 30, 20), 20.0)

    def test_budget_seconds_timestamps_normalized(self):
        # createTime in seconds still counts as present.
        self.assertEqual(
            confirm_budget_seconds({"createTime": 1760000000}, 30, 20), 30.0
        )

    def test_budget_invalid_value_falls_back_to_20(self):
        self.assertEqual(confirm_budget_seconds({"createTime": 1}, 0, 0), 20.0)
        self.assertEqual(confirm_budget_seconds({"createTime": 1}, -5, 20), 20.0)

    def test_classify_code_zero_wins(self):
        self.assertEqual(classify_confirm({"code": 0, "msg": "已被抢"}), ("ok", ""))

    def test_classify_confirmed_words(self):
        for msg in ("已接单", "已确认", "接单成功", "确认成功"):
            self.assertEqual(classify_confirm({"code": 2, "msg": msg})[0], "ok")

    def test_classify_terminal_words_and_reasons(self):
        cases = [
            ("已被其他人抢走", "被其他人抢走"),
            ("订单已取消", "已取消"),
            ("订单不存在", "订单不存在"),
            ("无确认权限", "无确认权限"),
            ("订单已失效", "订单已失效"),
            ("确认超时", "确认超时"),
            ("取消", "已取消"),
        ]
        for msg, reason in cases:
            outcome, got = classify_confirm({"code": 2, "msg": msg})
            self.assertEqual(outcome, "fail")
            self.assertEqual(got, reason)

    def test_classify_other_code_retries(self):
        self.assertEqual(classify_confirm({"code": 2, "msg": "请稍后再试"})[0], "retry")
        self.assertEqual(classify_confirm({"code": 5, "msg": ""})[0], "retry")

    def test_terminal_reason_empty_message(self):
        self.assertEqual(terminal_reason(""), "平台拒绝确认")
        self.assertEqual(terminal_reason(None), "平台拒绝确认")

    def test_reconcile_status_2_is_ok(self):
        self.assertEqual(reconcile_order({"status": 2}, 5)[0], "ok")
        self.assertEqual(reconcile_order({"status": 3}, 5)[0], "ok")

    def test_reconcile_confirmed_message_is_ok(self):
        self.assertEqual(
            reconcile_order({"status": 0, "msg": "已接单"}, 5)[0], "ok"
        )

    def test_reconcile_clerk_mismatch_is_fail(self):
        verdict, reason = reconcile_order(
            {"status": 2, "clerkId": 99}, 5
        )
        self.assertEqual((verdict, reason), ("fail", "被其他人抢走"))

    def test_reconcile_same_clerk_is_ok(self):
        self.assertEqual(
            reconcile_order({"status": 2, "clerkId": 5}, 5)[0], "ok"
        )

    def test_reconcile_no_clerk_check_when_unknown(self):
        # Own clerk id unknown (0): a confirmed status is still ok.
        self.assertEqual(
            reconcile_order({"status": 2, "clerkId": 99}, 0)[0], "ok"
        )

    def test_reconcile_terminal_and_status(self):
        self.assertEqual(
            reconcile_order({"status": -1}, 5)[0], "fail"
        )
        self.assertEqual(
            reconcile_order({"status": 0, "msg": "订单已取消"}, 5)[0], "fail"
        )

    def test_reconcile_status_0_is_pending(self):
        self.assertEqual(reconcile_order({"status": 0}, 5)[0], "pending")
        self.assertEqual(reconcile_order({}, 5)[0], "pending")

    def test_reconcile_other_status_is_unknown(self):
        self.assertEqual(reconcile_order({"status": 9}, 5)[0], "unknown")

    def test_reconcile_picks_payload_with_status_or_msg(self):
        # The order object wins over the top level.
        resp = {"status": 0, "order": {"status": 2, "clerkId": 5}}
        self.assertEqual(reconcile_order(resp, 5)[0], "ok")
        resp = {"status": 0, "data": {"order": {"status": -1}}}
        self.assertEqual(reconcile_order(resp, 5)[0], "fail")
        resp = {"status": 0, "data": {"status": 3}}
        self.assertEqual(reconcile_order(resp, 5)[0], "ok")


class StubApiClient:
    """Scripted stand-in for ApiClient for Engine tests.

    Each method consumes one scripted entry; running out raises
    AssertionError so unexpected calls fail the test loudly.
    """

    def __init__(self, polls, confirms, gets=None, clerk_id=5):
        self.polls = list(polls)
        self.confirms = list(confirms)
        self.gets = list(gets or [])
        self.clerk_id = clerk_id
        self.login_calls = 0
        self.confirm_calls = 0
        self.get_calls = 0
        self.session = Session(token="tok-92", sid="92")

    def session_for_account(self, phone, password, shop_id=92):
        self.login_calls += 1
        return self.session

    def clerk_get(self, session):
        return {"clerk": {"id": self.clerk_id, "shopId": 92, "name": "店员"}}

    def random_list(self, page_size=20, timeout=15):
        if self.polls:
            return self.polls.pop(0)
        return []

    def order_get(self, order_id, timeout=15):
        self.get_calls += 1
        if not self.gets:
            raise AssertionError("unexpected order_get for %s" % order_id)
        entry = self.gets.pop(0)
        if isinstance(entry, Exception):
            raise entry
        return entry

    def order_confirm(self, order_id, timeout=10):
        self.confirm_calls += 1
        if not self.confirms:
            raise AssertionError("unexpected order_confirm for %s" % order_id)
        entry = self.confirms.pop(0)
        if isinstance(entry, Exception):
            raise entry
        return entry


def _test_engine_cfg(**over):
    cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
    cfg.update({
        "poll_interval_sec": 0.05,
        "random_confirm_window_sec": 1.0,
        "random_confirm_fallback_sec": 1.0,
        "confirm_retry_delay_sec": 0.05,
        "seen_ttl_sec": 60,
    })
    cfg.update(over)
    return cfg


def _wait_stopped(engine, timeout=10.0):
    import time as _time

    deadline = _time.monotonic() + timeout
    while engine.running and _time.monotonic() < deadline:
        _time.sleep(0.02)
    return not engine.running


class EngineConfirmTests(unittest.TestCase):
    def setUp(self):
        import time as _time

        self._time = _time

    def _run_engine(self, stub, cfg, wait=5.0):
        logs = []

        def log(level, message):
            logs.append((level, message))

        engine = Engine(stub, cfg, log)
        self.assertTrue(engine.start("13800138000", "secret"))
        return engine, logs

    def _order(self, **over):
        order = {"id": "ord-1", "totalAmount": 2500, "userWechatId": "wx1",
                 "userMemo": "", "createTime": 1760000000000}
        order.update(over)
        return order

    def _log_text(self, logs):
        return "\n".join(message for _, message in logs)

    def test_success_first_try_stops_engine(self):
        stub = StubApiClient(
            polls=[[self._order()]], confirms=[{"code": 0}]
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self.assertTrue(_wait_stopped(engine), "engine did not stop after grab")
        self.assertEqual(stub.confirm_calls, 1)
        self.assertEqual(stub.get_calls, 0, "no detail/readback fetch on fast success")
        self.assertIn("抢单成功", self._log_text(logs))

    def test_retry_then_success(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[{"code": 2, "msg": "请稍后再试"}, {"code": 0}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self.assertTrue(_wait_stopped(engine))
        self.assertEqual(stub.confirm_calls, 2)
        self.assertIn("抢单成功", self._log_text(logs))

    def test_terminal_fail_keeps_listening(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[{"code": 2, "msg": "已被其他人抢走"}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self._time.sleep(0.4)
        self.assertTrue(engine.running, "engine should keep polling after terminal fail")
        self.assertEqual(stub.confirm_calls, 1)
        self.assertEqual(stub.get_calls, 0)
        self.assertIn("被其他人抢走", self._log_text(logs))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_auth_failure_relogs_and_ends_task(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("auth", "login state invalid (code 401)")],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self._time.sleep(0.4)
        self.assertTrue(engine.running)
        self.assertEqual(stub.login_calls, 2, "expected initial login + refresh login")
        self.assertIn("确认认证失败", self._log_text(logs))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_ip_failure_pauses_and_ends_task(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("ip", "IP control")],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self._time.sleep(0.4)
        self.assertIn("确认触发IP风控", self._log_text(logs))
        engine.stop()
        self.assertTrue(_wait_stopped(engine))

    def test_network_error_readback_ok(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("network", "request failed: boom")],
            gets=[{"status": 2, "clerkId": 5}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self.assertTrue(_wait_stopped(engine))
        self.assertEqual(stub.confirm_calls, 1)
        self.assertEqual(stub.get_calls, 1)
        self.assertIn("抢单成功", self._log_text(logs))

    def test_readback_pending_then_retry_success(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("network", "boom"), {"code": 0}],
            gets=[{"status": 0}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self.assertTrue(_wait_stopped(engine))
        self.assertEqual(stub.confirm_calls, 2)
        self.assertEqual(stub.get_calls, 1)
        self.assertIn("抢单成功", self._log_text(logs))

    def test_readback_unknown_stops_blind_retry(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("network", "boom"), {"code": 0}],
            gets=[{"status": 9, "msg": "奇怪"}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self._time.sleep(0.4)
        self.assertTrue(engine.running, "engine should keep polling after unknown")
        self.assertEqual(stub.confirm_calls, 1, "no second confirm after unknown")
        self.assertIn("停止盲重试", self._log_text(logs))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_readback_clerk_mismatch_fails(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[ApiError("network", "boom")],
            gets=[{"status": 2, "clerkId": 99}],
        )
        engine, logs = self._run_engine(stub, _test_engine_cfg())
        self._time.sleep(0.4)
        self.assertTrue(engine.running)
        self.assertEqual(stub.confirm_calls, 1)
        self.assertIn("被其他人抢走", self._log_text(logs))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_deadline_pending_releases_order(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[{"code": 2, "msg": "稍后再试"}] * 200,
            gets=[{"status": 0}],
        )
        cfg = _test_engine_cfg(
            random_confirm_window_sec=0.3, confirm_retry_delay_sec=0.05
        )
        engine, logs = self._run_engine(stub, cfg)
        deadline = self._time.monotonic() + 5
        while "本轮确认预算耗尽" not in self._log_text(logs) and self._time.monotonic() < deadline:
            self._time.sleep(0.02)
        self.assertIn("本轮确认预算耗尽", self._log_text(logs))
        self.assertNotIn("ord-1", engine._seen, "order must be released for re-poll")
        self.assertGreater(stub.confirm_calls, 1, "expected retries inside the window")
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_deadline_final_readback_ok(self):
        stub = StubApiClient(
            polls=[[self._order()]],
            confirms=[{"code": 2, "msg": "稍后再试"}] * 200,
            gets=[{"status": 2, "clerkId": 5}],
        )
        cfg = _test_engine_cfg(
            random_confirm_window_sec=0.3, confirm_retry_delay_sec=0.05
        )
        engine, logs = self._run_engine(stub, cfg)
        self.assertTrue(_wait_stopped(engine))
        self.assertIn("抢单成功", self._log_text(logs))


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

    def test_confirm_window_defaults(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        self.assertEqual(cfg["random_confirm_window_sec"], 20)
        self.assertEqual(cfg["random_confirm_fallback_sec"], 20)
        self.assertEqual(cfg["confirm_retry_delay_sec"], 0.5)
        self.assertNotIn("user_delay", cfg)

    def test_confirm_window_ranges_rejected(self):
        for key in ("random_confirm_window_sec", "random_confirm_fallback_sec"):
            bad = dict(cfgmod.DEFAULT_CONFIG)
            bad[key] = 0.05
            with self.assertRaises(cfgmod.ConfigError):
                cfgmod.validate(bad)
            bad = dict(cfgmod.DEFAULT_CONFIG)
            bad[key] = 90000
            with self.assertRaises(cfgmod.ConfigError):
                cfgmod.validate(bad)
        bad = dict(cfgmod.DEFAULT_CONFIG)
        bad["confirm_retry_delay_sec"] = -1
        with self.assertRaises(cfgmod.ConfigError):
            cfgmod.validate(bad)
        bad = dict(cfgmod.DEFAULT_CONFIG)
        bad["confirm_retry_delay_sec"] = 61
        with self.assertRaises(cfgmod.ConfigError):
            cfgmod.validate(bad)

    def test_legacy_user_delay_dropped(self):
        old = dict(cfgmod.DEFAULT_CONFIG)
        old["user_delay"] = 3
        cfg = cfgmod.validate(old)
        self.assertNotIn("user_delay", cfg)


if __name__ == "__main__":
    unittest.main()
