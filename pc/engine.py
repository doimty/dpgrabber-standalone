"""DPGrabber PC - listening engine.

One worker thread: poll -> filter -> confirm task -> submit
(PROTOCOL.md sections 6-8, following DPGrabber's own
DPGConfirmationTask state machine, DPGEngine.m).

Flow per round:
1. GET /s/c/order/randomList (page 1, 20 orders).
2. No orders -> wait ``poll_interval_sec`` and poll again.
3. Take only the first order, de-dup by id (seen set, TTL from config).
4. Filters, in order (section 7.1): missing amount/id, blacklist
   WeChat id, min amount, max amount (0 = unlimited), memo mode,
   memo keywords (case-insensitive substring).
5. Passed -> confirm task (section 8.2): the task starts immediately
   (no precise wait, no reTime / confirmCountDown, no user delay) and
   loops POST /s/c/order/confirm until the window deadline
   (``random_confirm_window_sec``, default 20 s from the task start, or
   ``random_confirm_fallback_sec`` when the order has no createTime),
   retrying every ``confirm_retry_delay_sec`` (default 0.5 s). Each
   response is classified: code 0 or a confirmed message -> success
   (stops the whole listen loop); a terminal message -> fail; 401/403
   -> re-login; IP control -> pause 60 s; no response -> verify with
   GET /s/c/order/get (readback) instead of blind retry. At the
   deadline the order is verified once more; still pending -> released
   back to polling so a later round can pick it up again.
6. On 401/403 the session is re-established with the saved account
   (manual-mode refresh, section 3.2 step 5); on IP control the loop
   pauses 60 s; other errors wait ``poll_interval_sec`` and continue.

All progress is reported through a ``log(level, message)`` callback and an
optional ``speak(text)`` callback; the engine never touches the UI
directly. ``now`` (wall clock) and ``monotonic`` are injected so the
deadline math is unit-testable.
"""

from __future__ import annotations

import random
import threading
import time

from config import confirm_wait_error
from dpapi import ApiClient, ApiError, amount_yuan_from_cents, extract_clerk, normalize_ms


# ---------------------------------------------------------------------------
# Confirm task (DPGConfirmationTask, DPGEngine.m). Pure helpers, shared
# semantics with the Android / iOS engines.
# ---------------------------------------------------------------------------

#: A confirm reply whose message contains one of these means success.
CONFIRMED_WORDS = ("已接单", "已确认", "接单成功", "确认成功")
#: A confirm reply whose message contains one of these is terminally failed.
#: Qualification / quota refusals must end the task immediately instead of
#: burning the whole confirm window: measured on autoduopei.cc (2026-11-14),
#: the platform answers "本周订单8单，当前续费率22%，需要达标续费率30%…" and
#: retrying cannot change the standing of the account.
QUALIFICATION_WORDS = ("续费率", "达标", "资质", "额度", "次数不足", "上限", "本周订单")

TERMINAL_WORDS = (
    "取消", "已被", "不存在", "无权", "权限", "失效", "超时",
    # 平台原话「哎呀订单好像跑走了，再试试~」：订单已经不在了，重试只会拿到
    # 同一句，属于终态（实测 2026-11-14，店铺 50）。
    "跑走", "跑掉",
) + QUALIFICATION_WORDS


def terminal_reason(message) -> str:
    """Human-readable reason for a terminal confirm failure
    (DPGTerminalReason, DPGEngine.m)."""
    text = str(message or "")
    # A qualification refusal is the platform's own wording about this
    # account, so pass it through untouched: the log and the history then show
    # the real reason instead of a rewritten label.
    if any(word in text for word in QUALIFICATION_WORDS):
        return text if text else "平台拒绝（资质/额度）"
    for word in ("已被", "被其他", "他人抢", "别人抢", "抢走"):
        if word in text:
            return "被其他人抢走"
    if "取消" in text:
        return "已取消"
    if "不存在" in text:
        return "订单不存在"
    if "无权" in text or "权限" in text:
        return "无确认权限"
    if "失效" in text:
        return "订单已失效"
    if "超时" in text:
        return "确认超时"
    return text if text else "平台拒绝确认"


def classify_confirm(result: dict):
    """Classify one confirm response: ("ok" | "fail" | "retry", reason).

    code == 0 wins over the message text; a confirmed message wins over
    a terminal message only when the code is not 0 (DPGEngine.m stepLoop
    checks code 0 first, then confirmed words, then terminal words).
    """
    if not isinstance(result, dict):
        return "retry", ""
    code = result.get("code")
    try:
        code = int(code)
    except (TypeError, ValueError):
        code = None
    message = str(result.get("msg") or result.get("message") or "")
    if code == 0:
        return "ok", ""
    if any(word in message for word in CONFIRMED_WORDS):
        return "ok", ""
    if any(word in message for word in TERMINAL_WORDS):
        return "fail", terminal_reason(message)
    return "retry", message


def confirm_wait_for_order(cfg: dict, order: dict, shop_id=None, rng=None,
                           now_ms: int | None = None) -> float:
    """Seconds to wait before the FIRST confirm, measured from createTime.

    The platform's countdown starts when the order is created; a blind wait
    starts when we happen to poll it, so a 0.5 s poll (0.25-0.75 with jitter)
    silently shifts every attempt by up to three quarters of a second. With a
    3 s shop rule plus a 2.8 s wait that lands at ~3.3 s after creation — late
    enough for the order to be gone ("订单好像跑走了").

    Returns the configured wait (which already includes the per-shop offset)
    minus the time already elapsed since createTime, never below 0. Falls back
    to the blind wait when the order carries no createTime.
    """
    table = (cfg or {}).get("confirm_wait_by_shop") or {}
    override = None
    if isinstance(table, dict) and shop_id is not None:
        try:
            override = table.get(str(int(shop_id)))
        except (TypeError, ValueError):
            override = None
    if override is not None:
        try:
            target = float(override)
        except (TypeError, ValueError):
            target = confirm_wait_seconds(cfg, shop_id, rng)
    else:
        target = confirm_wait_seconds(cfg, shop_id, rng)
    if target <= 0:
        return 0.0
    create_ms = normalize_ms(order.get("createTime")) if isinstance(order, dict) else 0
    if create_ms <= 0:
        return target
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    elapsed = max(0.0, (now - create_ms) / 1000.0)
    return max(0.0, target - elapsed)


def confirm_budget_seconds(order: dict, window_sec: float, fallback_sec: float) -> float:
    """Confirm window budget in seconds (deadlineForOrderAtWall, random
    kind): the window when the order has a createTime, the fallback
    window when it does not; 20 s when the configured value is invalid."""
    create_ms = normalize_ms(order.get("createTime")) if isinstance(order, dict) else 0
    budget = window_sec if create_ms > 0 else fallback_sec
    try:
        budget = float(budget)
    except (TypeError, ValueError):
        budget = 0.0
    if budget <= 0:
        budget = 20.0
    return budget


def reconcile_order(response: dict, own_clerk_id: int):
    """Verify a confirm result with one order/get response
    (reconcileWithSession, DPGEngine.m). Returns
    ("ok" | "fail" | "pending" | "unknown", reason).

    The payload is the first dict among response["order"],
    response["data"]["order"], response["data"] and the response itself
    that carries a "status" or "msg" field.
    """
    payload = None
    if isinstance(response, dict):
        candidates = []
        order = response.get("order")
        candidates.append(order if isinstance(order, dict) else None)
        data = response.get("data")
        if isinstance(data, dict):
            candidates.append(data.get("order") if isinstance(data.get("order"), dict) else None)
            candidates.append(data)
        candidates.append(response)
        for cand in candidates:
            if isinstance(cand, dict) and ("status" in cand or "msg" in cand):
                payload = cand
                break
        if payload is None:
            payload = response
    else:
        payload = {}
    message = str(payload.get("msg") or payload.get("message") or "")
    if not message and isinstance(response, dict):
        message = str(response.get("msg") or "")
    try:
        status = int(payload.get("status"))
    except (TypeError, ValueError):
        status = 0
    confirmed = any(word in message for word in CONFIRMED_WORDS) or status in (2, 3)
    if confirmed:
        try:
            clerk_id = int(payload.get("clerkId"))
        except (TypeError, ValueError):
            clerk_id = 0
        if own_clerk_id > 0 and clerk_id > 0 and clerk_id != own_clerk_id:
            return "fail", "被其他人抢走"
        return "ok", ""
    if any(word in message for word in TERMINAL_WORDS) or status == -1:
        return "fail", terminal_reason(message)
    if status == 0:
        return "pending", ""
    return "unknown", message


def order_id_of(order) -> str:
    if isinstance(order, dict):
        value = order.get("id")
        return str(value) if value is not None else ""
    return ""


# ---------------------------------------------------------------------------
# Confirm wait + auto-wait offset (autoduopei shop-92 / auto_wait_offset).
# ---------------------------------------------------------------------------

#: Shops the auto-wait offset applies to (autoduopei /user/auto_wait_offset).
AUTO_WAIT_OFFSET_SHOPS = (50, 105, 1391)


def confirm_wait_seconds(cfg: dict, shop_id=None, rng=None) -> float:
    """Seconds to wait before the FIRST confirm attempt of a grab.

    Uniform in [confirm_wait_min, confirm_wait_max] when max > 0, else 0
    (confirm immediately). ``auto_wait_offset`` is added only when the
    shop is 50 / 105 / 1391 (no shop id available -> offset ignored);
    the result is clamped at >= 0. ``rng`` supplies ``uniform`` and
    defaults to the ``random`` module (injectable for tests)."""
    try:
        min_w = float((cfg or {}).get("confirm_wait_min", 0) or 0)
        max_w = float((cfg or {}).get("confirm_wait_max", 0) or 0)
    except (TypeError, ValueError):
        return 0.0
    if max_w <= 0:
        return 0.0
    wait = (rng or random).uniform(min_w, max_w)
    try:
        offset = float((cfg or {}).get("auto_wait_offset", 0.0) or 0.0)
    except (TypeError, ValueError):
        offset = 0.0
    if shop_id is not None:
        try:
            sid = int(shop_id)
        except (TypeError, ValueError):
            sid = None
        if sid in AUTO_WAIT_OFFSET_SHOPS:
            wait += offset
    return max(0.0, float(wait))


#: Internal confirm-task outcomes -> autoduopei history outcomes.
_OUTCOME_MAP = {
    "ok": "success",
    "fail": "failed",
    "unknown": "unknown",
    "pending": "skipped",
    "auth": "unknown",
    "ip": "risk",
    # "cancelled" maps to nothing: the engine was asked to stop, which is
    # not an order outcome, so no on_outcome call is made for it.
}

#: Fallback human-readable reasons when a return site has none.
_OUTCOME_DEFAULT_REASON = {
    "success": "",
    "failed": "平台拒绝确认",
    "unknown": "确认结果未知",
    "skipped": "本轮确认未完成，订单仍待确认",
    "risk": "IP风控",
}


class OrderFilter:
    """The section 7.1 filter chain. ``check(order)`` returns
    ``(passed, reason)``; reason is a short human-readable note."""

    def __init__(self, min_amount: float, max_amount: float, memo_mode: int,
                 memo_keywords, blacklist):
        self.min_amount = float(min_amount)
        self.max_amount = float(max_amount)
        self.memo_mode = int(memo_mode)
        self.memo_keywords = [str(k).strip() for k in (memo_keywords or []) if str(k).strip()]
        self.blacklist = set(str(b).strip() for b in (blacklist or []) if str(b).strip())

    def check(self, order: dict):
        if not isinstance(order, dict):
            return False, "订单数据异常"
        if "totalAmount" not in order:
            return False, "订单缺少金额字段"
        if not order_id_of(order):
            return False, "订单缺少 id 字段"
        amount = amount_yuan_from_cents(order.get("totalAmount"))
        wechat = str(order.get("userWechatId") or "")
        if wechat and wechat in self.blacklist:
            return False, "客户在黑名单中: %s" % wechat
        if amount < self.min_amount:
            return False, "金额 %.2f 元低于最低金额" % amount
        if self.max_amount > 0 and amount > self.max_amount:
            return False, "金额 %.2f 元高于最高金额" % amount
        memo = str(order.get("userMemo") or "")
        if self.memo_mode == 1 and memo:
            return False, "订单有备注，不符合要求"
        if memo and self.memo_keywords:
            lowered = memo.lower()
            for keyword in self.memo_keywords:
                if keyword.lower() in lowered:
                    return False, "订单备注命中关键词[%s]" % keyword
        return True, ""


class Engine:
    """Poll loop running on a worker thread."""

    def __init__(self, client: ApiClient, cfg: dict, log, speak=None,
                 on_grab=None, on_outcome=None, on_session=None, now=time.time,
                 monotonic=time.monotonic, rng=None):
        self.client = client
        self.cfg = cfg
        self.log = log  # log(level, message); level in info/warning/error/success/debug
        self.speak = speak or (lambda text: None)
        # on_grab(order, order_id, confirm_calls): called on a confirmed
        # grab so the UI can record the order in 接单历史. Optional.
        self.on_grab = on_grab
        # on_outcome(order, outcome, reason): called exactly once per
        # order that leaves the confirm loop with a terminal outcome —
        # success ("success"), terminal failure ("failed"), IP control
        # ("risk"), unknown result ("unknown") or a released/pending
        # order ("skipped") — so the UI can record non-success outcomes
        # in 接单历史 too. Not called when the engine is stopped
        # mid-confirm ("cancelled" is not an order outcome). Optional.
        self.on_outcome = on_outcome
        self.on_session = on_session
        self.now = now  # wall clock, epoch seconds
        self._monotonic = monotonic  # monotonic clock for the confirm deadline
        self._rng = rng if rng is not None else random  # uniform() source
        self._continue_noted = False   # “继续轮询”提示只发一次的标记
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.running = False
        self._seen: dict[str, float] = {}
        self._seen_ttl = float(cfg.get("seen_ttl_sec", 60))
        self._consecutive_failures = 0
        self.clerk: dict | None = None

    # -- lifecycle ------------------------------------------------------

    def start(self, phone: str, password: str, session=None) -> bool:
        if self.running:
            return False
        wait_error = confirm_wait_error(self.cfg)
        if wait_error is not None:
            self.log("error", "确认等待配置无效，无法开始监听：%s" % wait_error)
            return False
        self.stop_event.clear()
        self._initial_session = session
        self.running = True
        self.thread = threading.Thread(
            target=self._run, args=(phone, password), daemon=True,
            name="dpgrabber-listen",
        )
        self.thread.start()
        return True

    def stop(self):
        self.stop_event.set()
        self.running = False

    # -- helpers ----------------------------------------------------------

    def _sleep(self, seconds: float) -> bool:
        """Interruptible sleep. Returns False when asked to stop."""
        deadline = time.monotonic() + max(0.0, seconds)
        while not self.stop_event.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return True
            time.sleep(min(0.1, remaining))
        return False

    def _mark_seen(self, order_id: str):
        now = self.now()
        self._seen = {k: v for k, v in self._seen.items() if now - v < self._seen_ttl}
        if order_id:
            self._seen[order_id] = now

    def _is_seen(self, order_id: str) -> bool:
        if not order_id:
            return False
        seen_at = self._seen.get(order_id)
        return seen_at is not None and self.now() - seen_at < self._seen_ttl

    def _release_seen(self, order_id: str):
        """Drop an order from the seen set so a later poll round can pick
        it up again (DPGConfirmationTask releaseForRetry)."""
        if order_id:
            self._seen.pop(order_id, None)

    def _own_clerk_id(self) -> int:
        try:
            return int((self.clerk or {}).get("id"))
        except (TypeError, ValueError):
            return 0

    def _readback(self, order_id: str):
        """One order/get verification (reconcileWithSession). Any API
        failure means the result is unknown."""
        try:
            response = self.client.order_get(order_id, timeout=15)
        except ApiError as exc:
            self.log("warning", "订单状态查询失败 id=%s：%s" % (order_id, exc.message))
            return "unknown", ""
        return reconcile_order(response, self._own_clerk_id())

    # -- main loop --------------------------------------------------------

    def _run(self, phone: str, password: str):
        try:
            session = getattr(self, "_initial_session", None)
            if session is not None and getattr(session, "token", ""):
                # the UI already logged in for this account: reuse that session
                # instead of paying for a second login + shop probe
                self.log("info", "复用已登录会话，店铺 sid=%s" % getattr(session, "sid", "?"))
            else:
                self.log("info", "正在登录…")
                session = self.client.session_for_account(
                    phone, password, int(self.cfg.get("shop_id", 92))
                )
                self.log("success", "登录成功，店铺 sid=%s" % session.sid)
            if self.on_session is not None:
                try:
                    self.on_session(session)
                except Exception:
                    pass
            try:
                clerk = extract_clerk(self.client.clerk_get(session))
                if clerk:
                    self.clerk = clerk
                    self.log(
                        "info",
                        "当前账号：%s（ID：%s）" % (clerk.get("name") or "", clerk.get("id") or ""),
                    )
            except ApiError as exc:
                self.log("warning", "店员信息读取失败，不影响监听：%s" % exc.message)
            self._poll_loop(phone, password)
        except ApiError as exc:
            self.log("error", "启动失败：%s" % exc.message)
            self.speak("启动失败")
        finally:
            self.running = False
            self.log("info", "监听已停止")

    def _refresh_session(self, phone: str, password: str) -> bool:
        try:
            session = self.client.session_for_account(
                phone, password, int(self.cfg.get("shop_id", 92))
            )
            self.log("success", "会话已重新建立，店铺 sid=%s" % session.sid)
            return True
        except ApiError as exc:
            self.log("error", "重新登录失败：%s" % exc.message)
            return False

    def _next_run_seconds(self) -> float:
        """One random run period in seconds (risk_run_min..max minutes)."""
        run_min = float(self.cfg.get("risk_run_min", 50))
        run_max = float(self.cfg.get("risk_run_max", 60))
        if run_max < run_min:
            run_max = run_min
        return max(0.0, self._rng.uniform(run_min, run_max)) * 60.0

    def _next_rest_seconds(self) -> float:
        """One random rest period in seconds (risk_rest_min..max minutes)."""
        rest_min = float(self.cfg.get("risk_rest_min", 10))
        rest_max = float(self.cfg.get("risk_rest_max", 20))
        if rest_max < rest_min:
            rest_max = rest_min
        return max(0.0, self._rng.uniform(rest_min, rest_max)) * 60.0

    def _current_shop_id(self):
        """Shop the session is actually in, not the configured preference.

        The auto-wait offset is shop-specific (50/105/1391), and the config's
        shop_id is only a *hint* that may be 0 (auto) or may have been
        overridden by the permission check during login. Reading it here meant
        the offset silently never applied: with shop_id=0 the value was never
        in AUTO_WAIT_OFFSET_SHOPS. The session sid is the real answer.
        """
        session = getattr(self.client, "session", None)
        sid = getattr(session, "sid", None)
        if sid is not None:
            try:
                return int(sid)
            except (TypeError, ValueError):
                pass
        try:
            configured = int(self.cfg.get("shop_id") or 0)
        except (TypeError, ValueError):
            return None
        return configured or None

    def _emit_outcome(self, order: dict, outcome: str, reason: str):
        """Report one terminal confirm outcome via on_outcome, mapped to
        the autoduopei outcome enum. Never breaks the grab flow."""
        if self.on_outcome is None:
            return
        mapped = _OUTCOME_MAP.get(outcome)
        if mapped is None:
            return
        final_reason = reason or _OUTCOME_DEFAULT_REASON.get(mapped, "")
        try:
            self.on_outcome(order, mapped, final_reason)
        except Exception:
            pass  # history recording must never break the grab flow

    def _poll_delay(self) -> float:
        """Sleep before the next poll, jittered like the reference tool.

        A fixed cadence is trivially fingerprintable, so the delay is
        poll_interval_sec +/- poll_jitter_sec. The competitor profile
        (0.6-1.1 s) is base 0.85 with jitter 0.25. Never below 0.1 s.
        """
        base = float(self.cfg.get("poll_interval_sec", 0.5))
        jitter = float(self.cfg.get("poll_jitter_sec", 0.25))
        if jitter <= 0:
            return max(0.1, base)
        return max(0.1, base + self._rng.uniform(-jitter, jitter))

    def _poll_loop(self, phone: str, password: str):
        page_size = 20
        timeout = float(self.cfg.get("request_timeout_sec", 15))
        # Rebuilt every poll: 右键拉黑 / 加入关键词 from the history list must
        # take effect while listening, so the filter cannot be a start-time
        # snapshot. Constructing it is a handful of list copies.
        def build_filter():
            return OrderFilter(
                self.cfg.get("min_amount", 10.0),
                self.cfg.get("max_amount", 0.0),
                self.cfg.get("memo_mode", 0),
                self.cfg.get("memo_keywords", []),
                self.cfg.get("blacklist", []),
            )
        # Risk run/rest rhythm (autoduopei risk_run/rest): when enabled,
        # poll for one random run period, rest, then resume. The rest
        # check runs only at the top of the loop, after any confirm task
        # has returned, so a confirm in progress when a run period ends
        # always finishes before the engine rests.
        run_rest_enabled = bool(self.cfg.get("run_rest_enabled", True))
        run_deadline = None
        if run_rest_enabled:
            run_deadline = self._monotonic() + self._next_run_seconds()
        self.log("info", "开始监听新订单")
        while not self.stop_event.is_set():
            if (
                run_rest_enabled
                and run_deadline is not None
                and self._monotonic() >= run_deadline
            ):
                rest = self._next_rest_seconds()
                if rest > 0:
                    self.log(
                        "info",
                        "进入休息：暂停监听约 %.1f 分钟" % (rest / 60.0),
                    )
                    if not self._sleep(rest):
                        return
                    self.log("info", "休息结束，恢复监听")
                run_deadline = self._monotonic() + self._next_run_seconds()
            try:
                orders = self.client.random_list(page_size=page_size, timeout=timeout)
            except ApiError as exc:
                if exc.kind == "auth":
                    self.log("warning", "登录态失效，尝试重新登录…")
                    if not self._refresh_session(phone, password):
                        return
                    continue
                if exc.kind == "ip":
                    self.log("warning", "IP 风控，暂停 60 秒")
                    if not self._sleep(60):
                        return
                    continue
                self._consecutive_failures += 1
                self.log("warning", "监听请求失败：%s" % exc.message)
                if not self._sleep(self._poll_delay()):
                    return
                continue
            self._consecutive_failures = 0
            if not orders:
                if not self._sleep(self._poll_delay()):
                    return
                continue
            order = orders[0]
            order_id = order_id_of(order)
            if self._is_seen(order_id):
                if not self._sleep(self._poll_delay()):
                    return
                continue
            self._mark_seen(order_id)
            order_filter = build_filter()
            amount = amount_yuan_from_cents(order.get("totalAmount"))
            self.log(
                "info",
                "检测到新订单：金额 %.2f 元，客户微信 %s"
                % (amount, order.get("userWechatId") or "未知"),
            )
            passed, reason = order_filter.check(order)
            if not passed:
                self.log("info", "订单不符合要求：%s" % reason)
                self.speak(reason)
                continue  # re-poll immediately, as the reference tool does
            self._handle_order(order, order_id, phone, password)

    def _grab_success(self, order: dict, order_id: str, confirm_calls: int):
        """Success end of a confirm task: log, announce, auto-stop.
        Returns ("ok", "") — the outcome tuple the confirm task uses."""
        number = str(order.get("no") or order_id)
        self.log(
            "success",
            "✓ 确认成功: %s (id=%s) confirm_calls=%d"
            % (number, order_id, confirm_calls),
        )
        self.log(
            "success",
            "抢单成功，请去查看订单，金额 %.2f 元"
            % amount_yuan_from_cents(order.get("totalAmount")),
        )
        self.speak("抢单成功")
        if self.on_grab is not None:
            try:
                self.on_grab(order, order_id, confirm_calls)
            except Exception:
                pass  # history recording must never break the grab flow
        if self.cfg.get("auto_stop_after_grab", True):
            self.stop()
        else:
            # 只在本次监听第一次抢到时说明一次，之后安静继续
            if not getattr(self, "_continue_noted", False):
                self._continue_noted = True
                self.log("info", "未开启「接到单后自动停止监听」，抢完继续轮询（只提示这一次）")
        return "ok", ""

    def _sleep_retry(self, deadline_mono: float) -> bool:
        """Wait one confirm retry delay (capped at the deadline).
        Returns False when asked to stop."""
        delay = float(self.cfg.get("confirm_retry_delay_sec", 0.5))
        remaining = deadline_mono - self._monotonic()
        return self._sleep(min(delay, max(0.0, remaining)))

    def _handle_order(self, order: dict, order_id: str, phone: str, password: str) -> str:
        """One confirm task (DPGConfirmationTask Init -> Loop -> Final /
        Readback -> Done). Returns the outcome
        ("ok" | "fail" | "pending" | "unknown" | "auth" | "ip" |
        "cancelled"). Every order that leaves the confirm loop with a
        terminal outcome gets exactly one on_outcome call here (via
        _emit_outcome); "cancelled" (engine stopped mid-confirm) is not
        an order outcome and reports nothing."""
        outcome, reason = self._confirm_task(order, order_id, phone, password)
        if outcome != "cancelled":
            self._emit_outcome(order, outcome, reason)
        return outcome

    def _confirm_task(self, order: dict, order_id: str, phone: str, password: str):
        """The confirm loop itself. Returns (outcome, reason): outcome is
        one of "ok" | "fail" | "pending" | "unknown" | "auth" | "ip" |
        "cancelled"; reason is a human-readable note ("" when none)."""
        window = float(self.cfg.get("random_confirm_window_sec", 20))
        fallback = float(self.cfg.get("random_confirm_fallback_sec", 20))
        budget = confirm_budget_seconds(order, window, fallback)
        started_mono = self._monotonic()
        deadline_mono = started_mono + budget
        amount = amount_yuan_from_cents(order.get("totalAmount"))
        number = str(order.get("no") or order_id)
        self.log("info", "尝试接单 %s ￥%.2f" % (number, amount))
        # Confirm wait: one wait before the FIRST confirm attempt only; 0 when
        # not configured. Measured from createTime so the polling interval
        # does not push the attempt past the shop's countdown.
        wait = confirm_wait_for_order(
            self.cfg, order, self._current_shop_id(), self._rng,
            int(self.now() * 1000) if self.now else None,
        )
        if wait > 0:
            self.log("info", "确认前等待 %.2f 秒" % wait)
            if not self._sleep(wait):
                return "cancelled", ""
        confirm_calls = 0
        while not self.stop_event.is_set():
            remaining = deadline_mono - self._monotonic()
            if remaining <= 0:
                break
            timeout = min(float(self.cfg.get("request_timeout_sec", 15)), max(0.1, remaining))
            try:
                confirm_calls += 1
                result = self.client.order_confirm(order_id, timeout=timeout)
            except ApiError as exc:
                if exc.kind == "auth":
                    self._release_seen(order_id)
                    self.log("warning", "确认认证失败，等待账号单飞刷新: %s" % number)
                    self._refresh_session(phone, password)
                    return "auth", "确认认证失败"
                if exc.kind == "ip":
                    self._release_seen(order_id)
                    self.log("warning", "确认触发IP风控，账号共享暂停60s，不重登录")
                    self._sleep(60)
                    return "ip", "IP风控"
                if exc.kind == "cancelled":
                    return "cancelled", ""
                # No response (network / undecodable): verify instead of
                # blind retry.
                verdict, reason = self._readback(order_id)
                if verdict == "ok":
                    return self._grab_success(order, order_id, confirm_calls)
                if verdict == "fail":
                    self.log("warning", "✗ 状态查询确认终态失败: %s" % reason)
                    return "fail", reason
                if verdict == "pending":
                    if not self._sleep_retry(deadline_mono):
                        return "cancelled", ""
                    continue
                self.log("warning", "⚠ 确认结果未知，停止盲重试: %s" % number)
                return "unknown", reason or "确认结果未知"
            outcome, reason = classify_confirm(result)
            if outcome == "ok":
                return self._grab_success(order, order_id, confirm_calls)
            if outcome == "fail":
                self.log("warning", "✗ 确认终态失败 %s: %s" % (number, reason))
                return "fail", reason
            self.log(
                "info",
                "[confirm] retry %s confirm_calls=%d reason=%s"
                % (number, confirm_calls, reason),
            )
            if not self._sleep_retry(deadline_mono):
                return "cancelled", ""
        if self.stop_event.is_set():
            # Stopped mid-task (e.g. while a confirm was in flight): do
            # not run the deadline readback after the stop.
            return "cancelled", ""
        # Deadline reached: one final verification (stepFinal).
        verdict, reason = self._readback(order_id)
        if verdict == "ok":
            return self._grab_success(order, order_id, confirm_calls)
        if verdict == "fail":
            self.log(
                "warning",
                "✗ 窗口结束，订单已不可确认: %s confirm_calls=%d"
                % (number, confirm_calls),
            )
            return "fail", reason or "订单已不可确认"
        if verdict == "pending":
            self._release_seen(order_id)
            self.log(
                "info",
                "↻ 本轮确认预算耗尽，订单仍待确认: %s confirm_calls=%d"
                % (number, confirm_calls),
            )
            return "pending", "本轮确认预算耗尽，订单仍待确认"
        self.log(
            "warning",
            "⚠ 窗口结束，确认结果未知: %s confirm_calls=%d"
            % (number, confirm_calls),
        )
        return "unknown", reason or "确认结果未知"
