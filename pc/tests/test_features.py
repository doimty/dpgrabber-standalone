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
        for min_w, max_w in ((0, 0), (3, 5), (5, 7), (3, 100)):
            cfg = cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG,
                                       confirm_wait_min=min_w,
                                       confirm_wait_max=max_w))
            self.assertEqual(cfg["confirm_wait_min"], float(min_w))
            self.assertEqual(cfg["confirm_wait_max"], float(max_w))

    def test_confirm_wait_invalid(self):
        bad_cases = [
            # min < 3 while max > 0
            {"confirm_wait_min": 1, "confirm_wait_max": 5},
            {"confirm_wait_min": 2.9, "confirm_wait_max": 5},
            {"confirm_wait_min": 0, "confirm_wait_max": 5},
            # max - min < 2
            {"confirm_wait_min": 3, "confirm_wait_max": 4},
            {"confirm_wait_min": 3, "confirm_wait_max": 4.5},
            # negative / inverted
            {"confirm_wait_min": -1, "confirm_wait_max": 0},
            {"confirm_wait_min": 0, "confirm_wait_max": -1},
            {"confirm_wait_min": 5, "confirm_wait_max": 3},
        ]
        for over in bad_cases:
            with self.assertRaises(cfgmod.ConfigError, msg=str(over)):
                cfgmod.validate(dict(cfgmod.DEFAULT_CONFIG, **over))

    def test_confirm_wait_error_helper(self):
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 0, "confirm_wait_max": 0}))
        self.assertIsNone(cfgmod.confirm_wait_error({"confirm_wait_min": 3, "confirm_wait_max": 5}))
        self.assertIsNotNone(cfgmod.confirm_wait_error({"confirm_wait_min": 1, "confirm_wait_max": 5}))

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

        engine = Engine(stub, cfg, log, rng=FakeRng(0.05))
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
        cfg = _test_engine_cfg(confirm_wait_min=1, confirm_wait_max=5)
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


if __name__ == "__main__":
    unittest.main()
