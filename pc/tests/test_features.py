"""Feature tests: the 5 autoduopei-derived features in config/engine/history.

Offline only: every test uses pure functions or a stub transport. No real
request is sent, no real account is used.

Run:  python3 -m unittest discover -s tests -v   (from the pc/ directory)
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config as cfgmod
import history as history_mod
from dpapi import ApiError, Session
from engine import Engine, confirm_wait_seconds
import engine as engine_module


# ---------------------------------------------------------------------------
# Shared stub client / helpers (mirrors tests/test_smoke.py's StubApiClient)
# ---------------------------------------------------------------------------

class StubApiClient:
    """Scripted stand-in for ApiClient for Engine tests."""

    def __init__(self, polls, confirms, gets=None, clerk_id=5, confirm_delay=0.0):
        self.polls = list(polls)
        self.confirms = list(confirms)
        self.gets = list(gets or [])
        self.clerk_id = clerk_id
        self.confirm_delay = confirm_delay
        self.login_calls = 0
        self.confirm_calls = 0
        self.get_calls = 0
        self.poll_calls = 0
        self.session = Session(token="tok-92", sid="92")

    def session_for_account(self, phone, password, shop_id=92):
        self.login_calls += 1
        return self.session

    def clerk_get(self, session):
        return {"clerk": {"id": self.clerk_id, "shopId": 92, "name": "店员"}}

    def random_list(self, page_size=20, timeout=15):
        self.poll_calls += 1
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
        if self.confirm_delay:
            time.sleep(self.confirm_delay)
        if not self.confirms:
            raise AssertionError("unexpected order_confirm for %s" % order_id)
        entry = self.confirms.pop(0)
        if isinstance(entry, Exception):
            raise entry
        return entry


class FakeRng:
    """uniform() always returns the preset value, whatever the bounds."""

    def __init__(self, value):
        self.value = value

    def uniform(self, a, b):
        return self.value


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


def _order(**over):
    order = {"id": "ord-1", "totalAmount": 2500, "userWechatId": "wx1",
             "userMemo": "", "createTime": 1760000000000}
    order.update(over)
    return order


def _wait_stopped(engine, timeout=10.0):
    deadline = time.monotonic() + timeout
    while engine.running and time.monotonic() < deadline:
        time.sleep(0.02)
    return not engine.running


def _wait_log(logs, text, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(text in message for _, message in logs):
            return True
        time.sleep(0.02)
    return False


def _log_text(logs):
    return "\n".join(message for _, message in logs)


# ---------------------------------------------------------------------------
# Feature 2/3/5 config validation + feature 4 helper
# ---------------------------------------------------------------------------

class NewConfigTests(unittest.TestCase):
    def test_new_defaults(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        self.assertEqual(cfg["confirm_wait_min"], 0.0)
        self.assertEqual(cfg["confirm_wait_max"], 0.0)
        self.assertEqual(cfg["auto_wait_offset"], 0.0)
        self.assertTrue(cfg["run_rest_enabled"])
        self.assertEqual(cfg["risk_run_min"], 50)
        self.assertEqual(cfg["risk_run_max"], 60)
        self.assertEqual(cfg["risk_rest_min"], 10)
        self.assertEqual(cfg["risk_rest_max"], 20)

    def test_confirm_wait_valid(self):
        for min_w, max_w in ((0, 0), (3, 5), (5, 7), (3, 100),
                             (5, 5), (0.5, 0.5), (1, 1), (120, 120)):
            cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG,
                                       confirm_wait_min=min_w,
                                       confirm_wait_max=max_w))
            self.assertEqual(cfg["confirm_wait_min"], float(min_w))
            self.assertEqual(cfg["confirm_wait_max"], float(max_w))

    def test_confirm_wait_invalid(self):
        bad_cases = [
            # negative / inverted / above the cap
            {"confirm_wait_min": -1, "confirm_wait_max": 0},
            {"confirm_wait_min": 0, "confirm_wait_max": -1},
            {"confirm_wait_min": 5, "confirm_wait_max": 3},
            {"confirm_wait_min": 121, "confirm_wait_max": 121},
        ]
        for over in bad_cases:
            with self.assertRaises(cfgmod.ConfigError, msg=str(over)):
                cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, **over))

    def test_confirm_wait_error_helper(self):
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 0, "confirm_wait_max": 0}))
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 3, "confirm_wait_max": 5}))
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 1, "confirm_wait_max": 5}))
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 5, "confirm_wait_max": 5}))
        self.assertIsNotNone(cfgmod.confirm_wait_error({"confirm_wait_min": 5, "confirm_wait_max": 3}))
        self.assertIsNotNone(cfgmod.confirm_wait_error({"confirm_wait_min": 121, "confirm_wait_max": 121}))

    def test_auto_wait_offset_valid(self):
        for value in (0, 0.0, 0.5, -0.5, 0.123, -0.001, 0.25):
            cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, auto_wait_offset=value))
            self.assertEqual(cfg["auto_wait_offset"], float(value))

    def test_auto_wait_offset_invalid(self):
        for value in (0.6, -0.6, 1.0, 0.1234, 0.0001, -0.5001, "abc"):
            with self.assertRaises(cfgmod.ConfigError, msg=str(value)):
                cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, auto_wait_offset=value))

    def test_run_rest_valid(self):
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG))
        self.assertEqual((cfg["risk_run_min"], cfg["risk_run_max"]), (50, 60))
        # rest 0 = no rest is allowed
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG,
                                   risk_rest_min=0, risk_rest_max=0))
        self.assertEqual((cfg["risk_rest_min"], cfg["risk_rest_max"]), (0, 0))
        cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, run_rest_enabled=False))
        self.assertFalse(cfg["run_rest_enabled"])

    def test_run_rest_invalid(self):
        bad_cases = [
            {"risk_run_min": 0},
            {"risk_run_min": -5},
            {"risk_run_min": 61, "risk_run_max": 60},
            {"risk_run_max": 10, "risk_run_min": 20},
            {"risk_rest_min": -1},
            {"risk_rest_min": 30, "risk_rest_max": 20},
            {"risk_run_min": 50.5},
            {"run_rest_enabled": "yes"},
        ]
        for over in bad_cases:
            with self.assertRaises(cfgmod.ConfigError, msg=str(over)):
                cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, **over))


class BlacklistOverwriteTests(unittest.TestCase):
    def test_empty_old_never_overwrites(self):
        self.assertFalse(cfgmod.blacklist_would_overwrite([], ["wx1"]))
        self.assertFalse(cfgmod.blacklist_would_overwrite(None, ["wx1"]))
        self.assertFalse(cfgmod.blacklist_would_overwrite([], []))

    def test_identical_is_not_overwrite(self):
        self.assertFalse(cfgmod.blacklist_would_overwrite(["wx1", "wx2"], ["wx2", "wx1"]))
        self.assertFalse(cfgmod.blacklist_would_overwrite(["wx1"], ["wx1"]))

    def test_removed_or_changed_is_overwrite(self):
        self.assertTrue(cfgmod.blacklist_would_overwrite(["wx1", "wx2"], ["wx1"]))
        self.assertTrue(cfgmod.blacklist_would_overwrite(["wx1"], ["wx2"]))
        self.assertTrue(cfgmod.blacklist_would_overwrite(["wx1"], []))
        # Adding entries also changes the stored set.
        self.assertTrue(cfgmod.blacklist_would_overwrite(["wx1"], ["wx1", "wx2"]))

    def test_blank_entries_ignored(self):
        self.assertFalse(cfgmod.blacklist_would_overwrite(["wx1", "  "], ["wx1"]))
        self.assertFalse(cfgmod.blacklist_would_overwrite(["  "], ["wx1"]))


# ---------------------------------------------------------------------------
# Feature 1: history outcome / reason
# ---------------------------------------------------------------------------

class HistoryOutcomeTests(unittest.TestCase):
    def test_normalize_missing_is_success(self):
        entry = history_mod.normalize_entry({"no": "1", "id": "1", "amount": 30.0})
        self.assertEqual(entry["outcome"], "success")
        self.assertEqual(entry["reason"], "")

    def test_normalize_keeps_non_success(self):
        entry = history_mod.normalize_entry(
            {"no": "1", "id": "1", "outcome": "risk", "reason": "IP风控"})
        self.assertEqual(entry["outcome"], "risk")
        self.assertEqual(entry["reason"], "IP风控")

    def test_normalize_unknown_outcome_value(self):
        entry = history_mod.normalize_entry({"outcome": "weird"})
        self.assertEqual(entry["outcome"], "unknown")

    def test_load_history_normalizes_old_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "dpgrabber_history.json")
            old = [{"no": "2611", "id": "156", "amount": 30.0,
                    "userWechatId": "wx", "userMemo": "", "createTime": ""}]
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(old, fh)
            entries = history_mod.load_history(path)
            self.assertEqual(entries[0]["outcome"], "success")
            self.assertEqual(entries[0]["reason"], "")

    def test_append_history_stores_outcome_and_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "dpgrabber_history.json")
            history_mod.append_history(
                {"no": "1", "id": "1", "amount": 30.0, "outcome": "failed",
                 "reason": "被其他人抢走"},
                path,
            )
            with open(path, encoding="utf-8") as fh:
                stored = json.load(fh)
            self.assertEqual(stored[0]["outcome"], "failed")
            self.assertEqual(stored[0]["reason"], "被其他人抢走")


# ---------------------------------------------------------------------------
# Features 2/3: confirm wait + offset (pure helper)
# ---------------------------------------------------------------------------

class ConfirmWaitTests(unittest.TestCase):
    def test_no_wait_when_max_zero(self):
        cfg = {"confirm_wait_min": 0, "confirm_wait_max": 0}
        self.assertEqual(confirm_wait_seconds(cfg, 92, FakeRng(99.0)), 0.0)

    def test_uniform_wait(self):
        cfg = {"confirm_wait_min": 3, "confirm_wait_max": 5}
        self.assertEqual(confirm_wait_seconds(cfg, 92, FakeRng(4.0)), 4.0)

    def test_offset_only_for_risk_shops(self):
        cfg = {"confirm_wait_min": 3, "confirm_wait_max": 5, "auto_wait_offset": 0.5}
        for shop in (50, 105, 1391):
            self.assertEqual(confirm_wait_seconds(cfg, shop, FakeRng(4.0)), 4.5)
        for shop in (92, 91, 1):
            self.assertEqual(confirm_wait_seconds(cfg, shop, FakeRng(4.0)), 4.0)
        # No shop id available -> offset ignored.
        self.assertEqual(confirm_wait_seconds(cfg, None, FakeRng(4.0)), 4.0)

    def test_offset_negative_and_clamped(self):
        cfg = {"confirm_wait_min": 3, "confirm_wait_max": 5, "auto_wait_offset": -0.5}
        self.assertEqual(confirm_wait_seconds(cfg, 50, FakeRng(4.0)), 3.5)
        # Wait + offset below zero clamps at 0.
        self.assertEqual(confirm_wait_seconds(cfg, 50, FakeRng(0.1)), 0.0)


# ---------------------------------------------------------------------------
# Feature 1: Engine on_outcome contract
# ---------------------------------------------------------------------------

class EngineOutcomeTests(unittest.TestCase):
    def _run(self, stub, cfg, rng=None):
        logs = []
        outcomes = []
        grabs = []

        def log(level, message):
            logs.append((level, message))

        engine = Engine(stub, cfg, log,
                        on_grab=lambda o, oid, n: grabs.append((o, oid, n)),
                        on_outcome=lambda o, outcome, reason: outcomes.append((outcome, reason)),
                        rng=rng)
        self.assertTrue(engine.start("13800138000", "secret"))
        return engine, logs, outcomes, grabs

    def _stop(self, engine):
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_success_calls_on_outcome_once_and_on_grab(self):
        stub = StubApiClient(polls=[[_order()]], confirms=[{"code": 0}])
        engine, logs, outcomes, grabs = self._run(
            stub, _test_engine_cfg(run_rest_enabled=False))
        self.assertTrue(_wait_stopped(engine))
        self.assertEqual(len(grabs), 1, "on_grab must still fire on success")
        self.assertEqual(outcomes, [("success", "")])

    def test_terminal_fail_calls_on_outcome_once(self):
        stub = StubApiClient(
            polls=[[_order()]], confirms=[{"code": 2, "msg": "已被其他人抢走"}])
        engine, logs, outcomes, grabs = self._run(
            stub, _test_engine_cfg(run_rest_enabled=False))
        time.sleep(0.4)
        self.assertTrue(engine.running, "engine keeps polling after terminal fail")
        self.assertEqual(outcomes, [("failed", "被其他人抢走")])
        self.assertEqual(grabs, [])
        self._stop(engine)

    def test_readback_unknown_calls_on_outcome_once(self):
        stub = StubApiClient(
            polls=[[_order()]],
            confirms=[ApiError("network", "boom"), {"code": 0}],
            gets=[{"status": 9, "msg": "奇怪"}],
        )
        engine, logs, outcomes, grabs = self._run(
            stub, _test_engine_cfg(run_rest_enabled=False))
        time.sleep(0.4)
        self.assertTrue(engine.running)
        self.assertEqual(outcomes, [("unknown", "奇怪")])
        self.assertEqual(grabs, [])
        self._stop(engine)

    def test_ip_failure_calls_on_outcome_risk(self):
        stub = StubApiClient(
            polls=[[_order()]], confirms=[ApiError("ip", "IP control")])
        engine, logs, outcomes, grabs = self._run(
            stub, _test_engine_cfg(run_rest_enabled=False))
        self.assertTrue(_wait_log(logs, "确认触发IP风控"))
        self._stop(engine)
        self.assertEqual(outcomes, [("risk", "IP风控")])
        self.assertEqual(grabs, [])

    def test_auth_failure_calls_on_outcome_unknown(self):
        stub = StubApiClient(
            polls=[[_order()]],
            confirms=[ApiError("auth", "login state invalid (code 401)")],
        )
        engine, logs, outcomes, grabs = self._run(
            stub, _test_engine_cfg(run_rest_enabled=False))
        time.sleep(0.4)
        self.assertTrue(engine.running)
        self.assertEqual(stub.login_calls, 2, "expected initial login + refresh login")
        self.assertEqual(outcomes, [("unknown", "确认认证失败")])
        self._stop(engine)

    def test_deadline_pending_calls_on_outcome_skipped(self):
        stub = StubApiClient(
            polls=[[_order()]],
            confirms=[{"code": 2, "msg": "稍后再试"}] * 200,
            gets=[{"status": 0}],
        )
        cfg = _test_engine_cfg(
            random_confirm_window_sec=0.3, confirm_retry_delay_sec=0.05,
            run_rest_enabled=False)
        engine, logs, outcomes, grabs = self._run(stub, cfg)
        self.assertTrue(_wait_log(logs, "本轮确认预算耗尽"))
        self.assertEqual(outcomes, [("skipped", "本轮确认预算耗尽，订单仍待确认")])
        self.assertEqual(grabs, [])
        self._stop(engine)


# ---------------------------------------------------------------------------
# Feature 2: confirm wait in the engine + invalid config refuses to start
# ---------------------------------------------------------------------------

class EngineConfirmWaitTests(unittest.TestCase):
    def test_wait_before_first_confirm(self):
        stub = StubApiClient(polls=[[_order()]], confirms=[{"code": 0}])
        cfg = _test_engine_cfg(
            confirm_wait_min=3, confirm_wait_max=5, run_rest_enabled=False)
        logs = []

        def log(level, message):
            logs.append((level, message))

        # 表格覆盖全局值：把 92 店的固定等待改成 0.05 秒，测试才不用真等 5 秒
        order_without_create = _order()
        order_without_create.pop("createTime", None)
        stub = StubApiClient(polls=[[order_without_create]], confirms=[{"code": 0}])
        tuned = dict(cfg)
        tuned["confirm_wait_by_shop"] = {"92": 0.05}
        engine = Engine(stub, tuned, log, rng=FakeRng(0.05))
        self.assertTrue(engine.start("13800138000", "secret"))
        self.assertTrue(_wait_stopped(engine))
        self.assertIn("确认前等待 0.05 秒", _log_text(logs))
        self.assertEqual(stub.confirm_calls, 1)

    def test_no_wait_when_disabled(self):
        stub = StubApiClient(polls=[[_order()]], confirms=[{"code": 0}])
        logs = []

        def log(level, message):
            logs.append((level, message))

        engine = Engine(stub, _test_engine_cfg(run_rest_enabled=False), log)
        self.assertTrue(engine.start("13800138000", "secret"))
        self.assertTrue(_wait_stopped(engine))
        self.assertNotIn("确认前等待", _log_text(logs))

    def test_invalid_wait_refuses_to_start(self):
        stub = StubApiClient(polls=[], confirms=[])
        cfg = _test_engine_cfg(confirm_wait_min=5, confirm_wait_max=3)
        logs = []

        def log(level, message):
            logs.append((level, message))

        engine = Engine(stub, cfg, log)
        self.assertFalse(engine.start("13800138000", "secret"))
        self.assertFalse(engine.running)
        self.assertIsNone(engine.thread)
        self.assertIn("确认等待配置无效", _log_text(logs))
        self.assertEqual(stub.login_calls, 0, "must not log in when refused")


# ---------------------------------------------------------------------------
# Feature 5: risk run/rest rhythm
# ---------------------------------------------------------------------------

class EngineRunRestTests(unittest.TestCase):
    def _run_empty(self, stub, cfg):
        logs = []

        def log(level, message):
            logs.append((level, message))

        engine = Engine(stub, cfg, log)
        self.assertTrue(engine.start("13800138000", "secret"))
        return engine, logs

    def test_disabled_never_rests(self):
        stub = StubApiClient(polls=[], confirms=[])
        engine, logs = self._run_empty(
            stub, _test_engine_cfg(run_rest_enabled=False))
        time.sleep(0.5)
        self.assertTrue(engine.running)
        self.assertNotIn("进入休息", _log_text(logs))
        self.assertNotIn("恢复监听", _log_text(logs))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_enabled_rests_then_resumes(self):
        stub = StubApiClient(polls=[], confirms=[])
        cfg = _test_engine_cfg(
            run_rest_enabled=True,
            risk_run_min=0.001, risk_run_max=0.001,  # 0.06 s run
            risk_rest_min=0.001, risk_rest_max=0.001,  # 0.06 s rest
        )
        engine, logs = self._run_empty(stub, cfg)
        self.assertTrue(_wait_log(logs, "恢复监听", timeout=5.0),
                        "expected rest -> resume transition; logs:\n%s" % _log_text(logs))
        text = _log_text(logs)
        self.assertLess(text.index("进入休息"), text.index("恢复监听"))
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)

    def test_confirm_finishes_before_rest(self):
        # Run period (0.06 s) expires while a 0.3 s confirm is in flight;
        # the confirm must still complete, and the rest must only start
        # after the confirm task returns.
        stub = StubApiClient(
            polls=[[_order()]],
            confirms=[{"code": 2, "msg": "已被其他人抢走"}],
            confirm_delay=0.3,
        )
        cfg = _test_engine_cfg(
            run_rest_enabled=True,
            risk_run_min=0.001, risk_run_max=0.001,
            risk_rest_min=0.001, risk_rest_max=0.001,
        )
        engine, logs = self._run_empty(stub, cfg)
        self.assertTrue(_wait_log(logs, "恢复监听", timeout=5.0),
                        "expected rest after confirm; logs:\n%s" % _log_text(logs))
        text = _log_text(logs)
        self.assertIn("被其他人抢走", text)
        self.assertEqual(stub.confirm_calls, 1, "confirm must run to completion")
        self.assertLess(text.index("被其他人抢走"), text.index("进入休息"),
                        "rest must start only after the confirm finishes")
        engine.stop()
        if engine.thread is not None:
            engine.thread.join(timeout=5)


class QualificationRefusalTests(unittest.TestCase):
    """autoduopei.cc 实测原文（2026-11-14）：资质类拒绝必须判终态并原样透传。"""

    MESSAGE = (
        "本周订单8单，当前续费率22%，需要达标续费率30%，"
        "可继续抢随机单，请提升续费率后，再来接随机单"
    )

    def test_qualification_refusal_is_terminal(self):
        outcome, reason = engine_module.classify_confirm({"code": 1, "msg": self.MESSAGE})
        self.assertEqual(outcome, "fail")
        self.assertEqual(reason, self.MESSAGE)

    def test_unknown_refusal_still_retries(self):
        outcome, reason = engine_module.classify_confirm(
            {"code": 1, "msg": "系统繁忙，请稍后重试"}
        )
        self.assertEqual(outcome, "retry")
        self.assertEqual(reason, "系统繁忙，请稍后重试")

    def test_order_taken_is_still_labelled(self):
        outcome, reason = engine_module.classify_confirm(
            {"code": 1, "msg": "订单已被其他人抢走"}
        )
        self.assertEqual(outcome, "fail")
        self.assertEqual(reason, "被其他人抢走")


class PollJitterTests(unittest.TestCase):
    """autoduopei.cc 的刷新节奏是 0.6-1.1 秒随机；固定节奏是可识别的特征。"""

    def _engine(self, base, jitter):
        engine = Engine.__new__(Engine)
        engine.cfg = {"poll_interval_sec": base, "poll_jitter_sec": jitter}
        engine._rng = __import__("random").Random(11)
        return engine

    def test_jitter_off_returns_the_base_interval(self):
        self.assertEqual(self._engine(0.5, 0)._poll_delay(), 0.5)

    def test_jitter_keeps_every_delay_inside_the_band(self):
        engine = self._engine(0.85, 0.25)
        delays = [engine._poll_delay() for _ in range(40)]
        self.assertGreaterEqual(min(delays), 0.6)
        self.assertLessEqual(max(delays), 1.1)
        self.assertGreater(len(set(delays)), 10, "delays look fixed")

    def test_jitter_never_goes_below_the_floor(self):
        engine = self._engine(0.05, 5)
        self.assertGreaterEqual(min(engine._poll_delay() for _ in range(20)), 0.1)


class ShopSelectionTests(unittest.TestCase):
    """优先店铺必须是「有店员权限」的那家，而不是硬编码 92。"""

    SHOPS = [
        {"accountId": "1", "shopId": 50},
        {"accountId": "2", "shopId": 92},
        {"accountId": "3", "shopId": 105},
    ]

    def test_no_preference_keeps_server_order(self):
        from dpapi import _order_shops
        ordered = _order_shops(self.SHOPS, 0)
        self.assertEqual([s["shopId"] for s in ordered], [50, 92, 105])

    def test_preference_is_only_a_hint(self):
        from dpapi import _order_shops
        ordered = _order_shops(self.SHOPS, 105)
        self.assertEqual([s["shopId"] for s in ordered], [105, 50, 92])

    def test_unknown_preference_falls_back_to_all(self):
        from dpapi import _order_shops
        ordered = _order_shops(self.SHOPS, 1391)
        self.assertEqual([s["shopId"] for s in ordered], [50, 92, 105])

    def test_auto_shop_is_allowed_in_config(self):
        cfg = cfgmod.validate({"shop_id": 0})
        self.assertEqual(cfg["shop_id"], 0)
        self.assertEqual(cfgmod.validate({})["shop_id"], 0)


class LoginBodyTests(unittest.TestCase):
    """The platform compares md5(password) hex in the "password" field."""

    def test_plaintext_is_md5_hashed(self):
        from dpapi import _login_body
        body = _login_body("16637393776", "Slov1221..")
        self.assertEqual(body["password"], "e0604ab3e568576d5e9e0d47b13ad1a0")
        self.assertNotIn("password_hash", body)

    def test_existing_digest_is_passed_through(self):
        from dpapi import _login_body
        digest = "e0604ab3e568576d5e9e0d47b13ad1a0"
        self.assertEqual(_login_body("1", digest)["password"], digest)

    def test_body_keeps_the_protocol_shape(self):
        from dpapi import _login_body
        body = _login_body("16637393776", "x")
        self.assertEqual(body["method"], "password")
        self.assertEqual(body["phoneNumber"]["mobile"], "16637393776")
        self.assertEqual(body["phoneNumber"]["mobileDial"], "86")
        self.assertEqual(sorted(body), ["method", "password", "phoneNumber"])

    def test_whitespace_is_trimmed_before_hashing(self):
        from dpapi import _login_body
        self.assertEqual(
            _login_body("1", "  Slov1221..  ")["password"],
            _login_body("1", "Slov1221..")["password"],
        )


class ConfigListAppendTests(unittest.TestCase):
    """右键拉黑：同一个微信号不能重复进列表，配置要落盘。"""

    def _app_stub(self, cfg):
        from ui import App
        app = App.__new__(App)
        app.cfg = cfg
        app.logged = []
        app.log = lambda level, msg: app.logged.append((level, msg))
        return app

    def test_appends_once_and_saves(self, ):
        import ui as ui_mod
        saved = {}
        original = ui_mod.cfgmod.save
        ui_mod.cfgmod.save = lambda cfg, **k: saved.update(cfg)
        try:
            app = self._app_stub({"blacklist": ["a"]})
            app._append_to_config_list("blacklist", "b", "已拉黑微信号")
            app._append_to_config_list("blacklist", "b", "已拉黑微信号")
            self.assertEqual(app.cfg["blacklist"], ["a", "b"])
            self.assertEqual(saved["blacklist"], ["a", "b"])
            self.assertEqual(app.logged[-1][0], "success")
        finally:
            ui_mod.cfgmod.save = original

    def test_blank_value_is_ignored(self):
        app = self._app_stub({"memo_keywords": []})
        app._append_to_config_list("memo_keywords", "   ", "已加入备注关键词")
        self.assertEqual(app.cfg["memo_keywords"], [])
        self.assertEqual(app.logged, [])

    def test_save_failure_is_reported_not_raised(self):
        import ui as ui_mod
        def boom(cfg, **k):
            raise OSError("disk full")
        original = ui_mod.cfgmod.save
        ui_mod.cfgmod.save = boom
        try:
            app = self._app_stub({"blacklist": []})
            app._append_to_config_list("blacklist", "x", "已拉黑微信号")
            self.assertEqual(app.logged[-1][0], "warning")
        finally:
            ui_mod.cfgmod.save = original


class ShopPermissionProbeTests(unittest.TestCase):
    """选店必须按「有店员权限」来：clerk/get 没结论时用业务调用兜底。"""

    def _client(self, seats, clerk_raises=True):
        from dpapi import ApiClient, Session, ApiError

        class Fake(ApiClient):
            def __init__(self):
                super().__init__("00" * 32, "00" * 16)
                self.switched = []

            def login_account(self, phone, password):
                return Session(token="base", sid="50")

            def request(self, method, path, **kw):
                if "list-shop-account" in path:
                    return {"code": 0,
                            "data": [{"accountId": "a" + s, "shopId": int(s)} for s in seats]}
                if "switch-account" in path:
                    return {"code": 0, "data": {"token": "t"}}
                return {"code": 0}

            def clerk_get(self, session=None):
                if clerk_raises:
                    raise ApiError("api", "clerk 500")
                return {"code": 0, "data": {"id": 1}}

            def random_list(self, page_size=20, timeout=15, session=None):
                if seats[session.sid] == "ok":
                    return []
                raise ApiError("api", "没有店员权限")

        return Fake()

    def test_seatless_shop_is_skipped_when_business_call_refuses(self):
        client = self._client({"50": "deny", "92": "ok"})
        notes = []
        session = client.session_for_account("p", "w", 0, on_note=notes.append)
        self.assertEqual(session.sid, "92")
        self.assertTrue(any("没有店员权限" in n for n in notes), notes)

    def test_all_denied_raises(self):
        from dpapi import ApiError
        client = self._client({"50": "deny", "92": "deny"})
        with self.assertRaises(ApiError):
            client.session_for_account("p", "w", 0, on_note=lambda n: None)

    def test_first_working_shop_wins_without_preference(self):
        client = self._client({"50": "ok", "92": "ok"})
        self.assertEqual(client.session_for_account("p", "w", 0).sid, "50")

    def test_preference_is_tried_first(self):
        client = self._client({"50": "ok", "92": "ok"})
        self.assertEqual(client.session_for_account("p", "w", 92).sid, "92")


class LoginSessionShopTests(unittest.TestCase):
    """Login 的 sid 必须来自响应里的 shopId：写死 50 会让 1391 店的账号一路 401。"""

    def _client(self, payload):
        from dpapi import ApiClient

        class Fake(ApiClient):
            def __init__(self):
                super().__init__("00" * 32, "00" * 16)

            def request(self, method, path, **kw):
                if "login" in path and "list-shop" not in path:
                    return {"code": 0, "data": payload}
                return {"code": 0, "data": {"token": "t"}}

        return Fake()

    def test_sid_comes_from_the_login_response(self):
        session = self._client({"token": "abc", "shopId": 1391}).login_account("1", "w")
        self.assertEqual(session.sid, "1391")

    def test_sid_defaults_to_50_when_absent(self):
        session = self._client({"token": "abc"}).login_account("1", "w")
        self.assertEqual(session.sid, "50")

    def test_list_shop_uses_the_session_sid(self):
        from dpapi import Session

        seen = {}

        from dpapi import ApiClient

        class Fake(ApiClient):
            def __init__(self):
                super().__init__("00" * 32, "00" * 16)

            def login_account(self, phone, password):
                return Session(token="t", sid="1391")

            def request(self, method, path, **kw):
                if "list-shop-account" in path:
                    seen["sid"] = kw.get("extra_headers", {}).get("sid")
                    return {"code": 0, "data": []}
                return {"code": 0}

            def clerk_get(self, session=None):
                return {"code": 0, "data": {"id": 1}}

            def random_list(self, page_size=20, timeout=15, session=None):
                return []

        try:
            Fake().session_for_account("1", "w")
        except Exception:
            pass
        self.assertEqual(seen.get("sid"), "1391")


class AutoWaitOffsetTests(unittest.TestCase):
    """偏移必须真的进到确认前的等待里，而且按会话所在的店铺判断。"""

    def test_offset_applies_only_to_the_three_shops(self):
        cfg = {"confirm_wait_min": 5, "confirm_wait_max": 5, "auto_wait_offset": 0.3}
        for sid in (50, 105, 1391):
            self.assertAlmostEqual(confirm_wait_seconds(cfg, sid), 5.3)
        for sid in (92, None, 0):
            self.assertAlmostEqual(confirm_wait_seconds(cfg, sid), 5.0)

    def test_offset_is_ignored_when_no_wait_is_configured(self):
        cfg = {"confirm_wait_min": 0, "confirm_wait_max": 0, "auto_wait_offset": 0.3}
        self.assertEqual(confirm_wait_seconds(cfg, 50), 0.0)

    def test_shop_id_comes_from_the_session_not_the_config(self):
        engine = Engine.__new__(Engine)
        engine.cfg = {"shop_id": 0}

        class Client:
            session = None

        client = Client()
        engine.client = client
        self.assertIsNone(engine._current_shop_id())

        class Session:
            sid = "50"

        client.session = Session()
        self.assertEqual(engine._current_shop_id(), 50)

        engine.cfg = {"shop_id": 92}
        client.session = None
        self.assertEqual(engine._current_shop_id(), 92)

    def test_confirm_task_waits_the_offset_amount(self):
        slept = []

        class Stub:
            session = None

            def order_confirm(self, order_id, timeout=15):
                return {"code": 0, "msg": "已接单"}

        from dpapi import Session

        class Session50:
            sid = "50"

        stub = Stub()
        stub.session = Session50()
        cfg = {
            "confirm_wait_min": 5, "confirm_wait_max": 5, "auto_wait_offset": 0.3,
            "random_confirm_window_sec": 20, "random_confirm_fallback_sec": 20,
            "confirm_retry_delay_sec": 0.01, "min_amount": 0, "max_amount": 0,
            "memo_mode": 0, "memo_keywords": [], "blacklist": [],
        }
        engine = Engine(stub, cfg, lambda level, msg: None)
        engine._sleep = lambda seconds: (slept.append(seconds), True)[1]
        engine._confirm_task({"no": "1", "totalAmount": 1000}, "1", "p", "w")
        self.assertTrue(slept, "nothing was awaited")
        self.assertAlmostEqual(slept[0], 5.3, places=3)


class AutoStopAfterGrabTests(unittest.TestCase):
    """接到单后是否自动停监听，必须可配置。"""

    def _engine(self, auto_stop):
        engine = Engine.__new__(Engine)
        engine.cfg = {"auto_stop_after_grab": auto_stop}
        engine.logs = []
        engine.log = lambda level, msg: engine.logs.append((level, msg))
        engine.speak = lambda text: None
        engine.stopped = False
        engine.stop = lambda: setattr(engine, "stopped", True)
        engine.on_grab = None
        return engine

    def test_on_by_default_stops_listening(self):
        engine = self._engine(True)
        outcome, _ = engine._grab_success({"no": "1", "totalAmount": 1000}, "1", 1)
        self.assertEqual(outcome, "ok")
        self.assertTrue(engine.stopped)

    def test_off_keeps_polling(self):
        engine = self._engine(False)
        outcome, _ = engine._grab_success({"no": "1", "totalAmount": 1000}, "1", 1)
        self.assertEqual(outcome, "ok")
        self.assertFalse(engine.stopped)
        self.assertTrue(any("继续轮询" in m for _, m in engine.logs))

    def test_config_defaults_to_true(self):
        self.assertTrue(cfgmod.validate({})["auto_stop_after_grab"])
        self.assertFalse(cfgmod.validate({"auto_stop_after_grab": False})["auto_stop_after_grab"])


class VanishedOrderTests(unittest.TestCase):
    """「哎呀订单好像跑走了」是终态：重试只会拿到同一句（店铺 50 实测）。"""

    def test_vanished_order_is_terminal_and_verbatim(self):
        from engine import classify_confirm
        msg = "哎呀订单好像跑走了，再试试~"
        outcome, reason = classify_confirm({"code": 1, "msg": msg})
        self.assertEqual(outcome, "fail")
        self.assertEqual(reason, msg)

    def test_countdown_message_still_retries(self):
        from engine import classify_confirm
        outcome, _ = classify_confirm({"code": 1, "msg": "未满足抢单倒计时秒数，请重新抢单"})
        self.assertEqual(outcome, "retry")


class CreateTimeWaitTests(unittest.TestCase):
    """等待时间应从订单创建时刻算，而不是从我们发现时刻算。"""

    NOW = 1_700_000_000_000

    def _cfg(self, lo=3, hi=3, offset=0.0):
        return {"confirm_wait_min": lo, "confirm_wait_max": hi, "auto_wait_offset": offset}

    def test_wait_shrinks_by_the_polling_delay(self):
        from engine import confirm_wait_for_order
        for elapsed_ms, expected in ((0, 3.0), (500, 2.5), (1000, 2.0), (2800, 0.2)):
            order = {"createTime": self.NOW - elapsed_ms}
            self.assertAlmostEqual(
                confirm_wait_for_order(self._cfg(), order, 50, None, self.NOW), expected)

    def test_wait_never_negative(self):
        from engine import confirm_wait_for_order
        order = {"createTime": self.NOW - 9000}
        self.assertEqual(confirm_wait_for_order(self._cfg(), order, 50, None, self.NOW), 0.0)

    def test_missing_create_time_falls_back_to_the_blind_wait(self):
        from engine import confirm_wait_for_order
        self.assertAlmostEqual(
            confirm_wait_for_order(self._cfg(), {"no": "1"}, 50, None, self.NOW), 3.0)

    def test_offset_is_already_inside_the_target(self):
        from engine import confirm_wait_for_order
        order = {"createTime": self.NOW - 500}
        # 3 - 0.2 = 2.8 target, minus 0.5 elapsed
        self.assertAlmostEqual(
            confirm_wait_for_order(self._cfg(offset=-0.2), order, 50, None, self.NOW), 2.3)

    def test_shops_outside_the_offset_list_are_unaffected(self):
        from engine import confirm_wait_for_order
        order = {"createTime": self.NOW - 500}
        self.assertAlmostEqual(
            confirm_wait_for_order(self._cfg(lo=5, hi=5, offset=-0.5), order, 92, None, self.NOW),
            4.5)


class PerShopWaitTests(unittest.TestCase):
    """confirm_wait_by_shop 覆盖全局等待；表外的店仍用全局值。"""

    def test_table_shop_is_fixed(self):
        from engine import confirm_wait_for_order
        cfg = {"confirm_wait_min": 3, "confirm_wait_max": 5,
               "confirm_wait_by_shop": {"92": 5.0, "50": 3.0}}
        now = 1_700_000_000_000
        self.assertAlmostEqual(
            confirm_wait_for_order(cfg, {"createTime": now}, 92, None, now), 5.0)
        self.assertAlmostEqual(
            confirm_wait_for_order(cfg, {"createTime": now}, 50, None, now), 3.0)

    def test_unknown_shop_falls_back_to_global(self):
        from engine import confirm_wait_for_order
        cfg = {"confirm_wait_min": 4, "confirm_wait_max": 4,
               "confirm_wait_by_shop": {"92": 5.0}}
        now = 1_700_000_000_000
        self.assertAlmostEqual(
            confirm_wait_for_order(cfg, {"createTime": now}, 777, None, now), 4.0)


class TableOffsetTests(unittest.TestCase):
    """店铺表 + auto_wait_offset：偏移叠加在表里的基准上（50/105/1391 才加）。"""

    def _wait(self, cfg, shop, now_ms=1_700_000_000_000):
        from engine import confirm_wait_for_order
        return confirm_wait_for_order(cfg, {"createTime": now_ms}, shop, None, now_ms)

    def test_offset_stacks_on_table_entry(self):
        cfg = {"confirm_wait_by_shop": {"50": 3.0}, "auto_wait_offset": 0.5}
        self.assertAlmostEqual(self._wait(cfg, 50), 3.5)

    def test_negative_offset_moves_earlier(self):
        cfg = {"confirm_wait_by_shop": {"50": 3.0}, "auto_wait_offset": -0.4}
        self.assertAlmostEqual(self._wait(cfg, 50), 2.6)

    def test_shop_92_keeps_table_value(self):
        cfg = {"confirm_wait_by_shop": {"92": 5.0}, "auto_wait_offset": 0.5}
        self.assertAlmostEqual(self._wait(cfg, 92), 5.0)

    def test_offset_never_makes_wait_negative(self):
        cfg = {"confirm_wait_by_shop": {"50": 1.0}, "auto_wait_offset": -3.0}
        self.assertGreaterEqual(self._wait(cfg, 50), 0.0)


class ItemTypeGateTests(unittest.TestCase):
    """关闭的接单类型不抢对应随机单（订单带类型字段时生效）。"""

    def _filter(self, enabled):
        from engine import OrderFilter
        return OrderFilter(10, 0, 0, [], [], enabled_items=enabled)

    def test_enabled_type_passes(self):
        ok, why = self._filter([465]).check({"id": "1", "totalAmount": 1000, "itemId": 465})
        self.assertTrue(ok, why)

    def test_disabled_type_is_rejected(self):
        ok, why = self._filter([465]).check({"id": "2", "totalAmount": 1000, "itemId": 2666})
        self.assertFalse(ok)
        self.assertIn("2666", why)

    def test_order_without_type_field_passes(self):
        ok, _ = self._filter([465]).check({"id": "3", "totalAmount": 1000})
        self.assertTrue(ok)

    def test_empty_list_does_not_restrict(self):
        ok, _ = self._filter([]).check({"id": "4", "totalAmount": 1000, "itemId": 2666})
        self.assertTrue(ok)

    def test_string_type_field_is_parsed(self):
        from engine import order_item_id
        self.assertEqual(order_item_id({"itemType": "2667"}), 2667)
        self.assertIsNone(order_item_id({}))


class MemoModeTests(unittest.TestCase):
    """备注检测四档：0 不检测 / 1 有备注不抢 / 2 排除关键词 / 3 只抢关键词。"""

    def _check(self, mode, keywords, memo, include=None):
        from engine import OrderFilter
        # 排除词与白名单是两份独立列表
        # mode 0 里的关键词是"老配置兼容"路径（过滤时会提升成 mode 2）
        exclude = keywords if mode in (0, 2) else []
        include = (keywords if mode == 3 else []) if include is None else include
        return OrderFilter(10, 0, mode, exclude, [],
                           memo_include_keywords=include).check(
            {"id": "1", "totalAmount": 1000, "userMemo": memo})

    def test_mode0_ignores_memo_when_no_keywords(self):
        ok, _ = self._check(0, [], "加急")
        self.assertTrue(ok)

    def test_mode0_with_keywords_keeps_old_behaviour(self):
        # 老配置：没有模式这个概念时，关键词非空就一直生效
        ok, why = self._check(0, ["加急"], "加急")
        self.assertFalse(ok)
        self.assertIn("关键词", why)

    def test_mode1_drops_any_memo(self):
        ok, why = self._check(1, [], "随便")
        self.assertFalse(ok)
        self.assertIn("备注", why)

    def test_mode2_rejects_on_keyword_hit(self):
        self.assertFalse(self._check(2, ["加急"], "加急")[0])
        self.assertTrue(self._check(2, ["加急"], "聊天")[0])

    def test_mode3_requires_keyword(self):
        self.assertTrue(self._check(3, ["会讲故事"], "会讲故事")[0])
        ok, why = self._check(3, ["会讲故事"], "随便")
        self.assertFalse(ok)
        self.assertIn("不含", why)

    def test_mode3_without_keywords_does_not_block_all(self):
        self.assertTrue(self._check(3, [], "随便")[0])

    def test_config_accepts_modes_0_to_3(self):
        import config as cfgmod
        for mode in (0, 1, 2, 3):
            cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, memo_mode=mode))
            self.assertEqual(cfg["memo_mode"], mode)

    def test_lists_are_independent(self):
        # 排除词里有"加急"，白名单里有"会讲故事"，互不串
        self.assertFalse(self._check(2, ["加急"], "加急")[0])
        self.assertTrue(self._check(2, ["加急"], "聊天")[0])
        self.assertTrue(self._check(3, ["会讲故事"], "会讲故事")[0])
        self.assertFalse(self._check(3, ["会讲故事"], "加急")[0])

    def test_include_list_does_not_leak_into_exclude(self):
        # 白名单里有"会讲故事"，排除模式不应因此拦截
        ok, _ = self._check(2, ["加急"], "会讲故事", include=["会讲故事"])
        self.assertTrue(ok)


class RealOrderSchemaTests(unittest.TestCase):
    """实测订单结构（2026-11-16）：类型信息在 item.names / itemCategory.name。"""

    REAL = {"createTime": 1763269555000, "id": 15670051, "no": "26111616455001028324",
            "totalAmount": 1000, "servicePrice": 1000, "num": 1, "type": "random",
            "status": 0, "userWechatId": "13456215657", "userMemo": "",
            "item": {"names": ["文字语音条", "30分钟"], "ids": [59245, 59246], "ratioType": 1},
            "itemCategory": {"name": "糖恋", "id": 273}}

    def test_names_are_parsed_from_nested_fields(self):
        from engine import order_item_names
        names = order_item_names(self.REAL)
        self.assertIn("文字语音条", names)
        self.assertIn("糖恋", names)

    def test_matching_is_decidable(self):
        from engine import order_matches_items
        # 「文字语音条」没有证据属于任何接单类型 → 认不出 = None = 放行
        self.assertIsNone(order_matches_items(self.REAL, [465]))
        self.assertIsNone(order_matches_items(self.REAL, [2666]))

    def test_unknown_type_is_not_dropped(self):
        from engine import OrderFilter
        ok, why = OrderFilter(10, 0, 0, [], [], enabled_items=[2666]).check(self.REAL)
        self.assertTrue(ok, why)


class ObservedItemNameTests(unittest.TestCase):
    """实测条目名（2026-11-16）：默认-语音连麦-60分钟 / 新客首单体验-30分钟文语体验单"""

    ORDER = {"id": 1, "no": "X", "totalAmount": 1000, "type": "random",
             "item": {"names": ["默认-语音连麦-60分钟", "新客首单体验-30分钟文语体验单"]},
             "itemCategory": {"name": "糖恋", "id": 273}}

    def _filter(self, enabled):
        import config as cfgmod
        from engine import OrderFilter
        return OrderFilter(10, 0, 0, [], [], enabled_items=enabled,
                           item_name_map=cfgmod.DEFAULT_CONFIG["item_name_map"])

    def test_lianmai_matches_observed_names(self):
        self.assertTrue(self._filter([465]).check(self.ORDER)[0])

    def test_video_and_game_do_not_match_voice_order(self):
        self.assertFalse(self._filter([2666]).check(self.ORDER)[0])
        self.assertFalse(self._filter([2667]).check(self.ORDER)[0])

    def test_video_order_is_not_let_through_by_voice_keywords(self):
        order = dict(self.ORDER, item={"names": ["默认-视频-30分钟"]})
        self.assertFalse(self._filter([465]).check(order)[0])
        self.assertTrue(self._filter([2666]).check(order)[0])

    def test_custom_map_overrides_defaults(self):
        from engine import OrderFilter
        filt = OrderFilter(10, 0, 0, [], [], enabled_items=[2666],
                           item_name_map={"2666": ["文语"]})
        self.assertTrue(filt.check(self.ORDER)[0])


if __name__ == "__main__":
    unittest.main()
