"""DPGrabber PC - listening engine.

One worker thread: poll -> filter -> precise grab time -> submit
(PROTOCOL.md sections 6-8, following the PC reference tool's flow, with
the DPGrabber additions noted below).

Flow per round:
1. GET /s/c/order/randomList (page 1, 20 orders).
2. No orders -> wait ``poll_interval_sec`` and poll again.
3. Take only the first order (reference tool behaviour), de-dup by id
   (seen set, TTL from config; DPGrabber addition, the reference tool has
   no de-dup).
4. Filters, in order (section 7.1): missing amount/id, blacklist
   WeChat id, min amount, max amount (0 = unlimited), memo mode,
   memo keywords (case-insensitive substring).
5. Passed -> precise grab time (section 8.1): POST /s/c/order/get,
   ``grab_time = reTime + confirmCountDown``; wait in 0.1 s steps
   (stoppable); then the user delay in 1 s steps (stoppable); then
   POST /s/c/order/confirm once. Success stops the whole listen loop.
6. On 401/403 the session is re-established with the saved account
   (manual-mode refresh, section 3.2 step 5); on IP control the loop
   pauses 60 s; other errors wait ``poll_interval_sec`` and continue.

All progress is reported through a ``log(level, message)`` callback and an
optional ``speak(text)`` callback; the engine never touches the UI
directly. ``time`` is injected as a callable returning epoch seconds so
the precise-wait math is unit-testable.
"""

from __future__ import annotations

import threading
import time

from dpapi import ApiClient, ApiError, amount_yuan_from_cents, extract_clerk, normalize_ms


def compute_grab_wait(order: dict, now_epoch_sec: float):
    """Precise grab time (PROTOCOL.md section 8.1).

    ``grab_time = reTime + confirmCountDown``; wait = grab_time - now.
    Returns ``(wait_seconds, grab_epoch_ms, reason)`` where reason is
    ``"ok"`` (fields present), ``"missing_fields"`` (wait skipped, grab
    immediately) or ``"already_open"`` (grab time already passed).
    ``createTime`` is only informational and is not used in the math.
    """
    try:
        re_ms = normalize_ms(order["reTime"])
        countdown = float(order["confirmCountDown"])
    except (KeyError, TypeError, ValueError):
        return 0.0, None, "missing_fields"
    if re_ms <= 0:
        return 0.0, None, "missing_fields"
    grab_ms = re_ms + int(countdown * 1000)
    wait = grab_ms / 1000.0 - now_epoch_sec
    if wait <= 0:
        return 0.0, grab_ms, "already_open"
    return wait, grab_ms, "ok"


def order_id_of(order) -> str:
    if isinstance(order, dict):
        value = order.get("id")
        return str(value) if value is not None else ""
    return ""


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
                 now=time.time):
        self.client = client
        self.cfg = cfg
        self.log = log  # log(level, message); level in info/warning/error/success/debug
        self.speak = speak or (lambda text: None)
        self.now = now
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.running = False
        self._seen: dict[str, float] = {}
        self._seen_ttl = float(cfg.get("seen_ttl_sec", 60))
        self._consecutive_failures = 0
        self.clerk: dict | None = None

    # -- lifecycle ------------------------------------------------------

    def start(self, phone: str, password: str) -> bool:
        if self.running:
            return False
        self.stop_event.clear()
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

    # -- main loop --------------------------------------------------------

    def _run(self, phone: str, password: str):
        try:
            self.log("info", "正在登录…")
            session = self.client.session_for_account(
                phone, password, int(self.cfg.get("shop_id", 92))
            )
            self.log("success", "登录成功，店铺 sid=%s" % session.sid)
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

    def _poll_loop(self, phone: str, password: str):
        interval = float(self.cfg.get("poll_interval_sec", 0.5))
        page_size = 20
        timeout = float(self.cfg.get("request_timeout_sec", 15))
        order_filter = OrderFilter(
            self.cfg.get("min_amount", 10.0),
            self.cfg.get("max_amount", 0.0),
            self.cfg.get("memo_mode", 0),
            self.cfg.get("memo_keywords", []),
            self.cfg.get("blacklist", []),
        )
        self.log("info", "开始监听新订单")
        while not self.stop_event.is_set():
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
                if not self._sleep(interval):
                    return
                continue
            self._consecutive_failures = 0
            if not orders:
                if not self._sleep(interval):
                    return
                continue
            order = orders[0]
            order_id = order_id_of(order)
            if self._is_seen(order_id):
                if not self._sleep(interval):
                    return
                continue
            self._mark_seen(order_id)
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
            self._handle_order(order, order_id)

    def _handle_order(self, order: dict, order_id: str):
        # 1. Precise grab time (section 8.1).
        wait, grab_ms, reason = 0.0, None, "missing_fields"
        try:
            detail = self.client.order_get(order_id, timeout=10)
            if detail.get("code") != 0:
                self.log("warning", "订单详情读取失败：%s" % (detail.get("msg") or ""))
                return
            inner = detail.get("order") if isinstance(detail.get("order"), dict) else detail
            wait, grab_ms, reason = compute_grab_wait(inner, self.now())
            create_ms = normalize_ms(inner.get("createTime"))
            if create_ms:
                self.log(
                    "debug",
                    "订单创建时间：%s"
                    % time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(create_ms / 1000.0)),
                )
            if reason == "ok":
                self.log(
                    "info",
                    "准确抢单时间：%s，等待 %.1f 秒"
                    % (
                        time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(grab_ms / 1000.0)),
                        wait,
                    ),
                )
        except ApiError as exc:
            if exc.kind == "auth":
                self.log("warning", "登录态失效，订单详情未读取")
                return
            self.log("warning", "订单详情读取失败：%s" % exc.message)
            return
        if wait > 0 and not self._sleep(wait):
            return
        # 2. User delay (reference tool: 1 s steps, stoppable).
        user_delay = int(self.cfg.get("user_delay", 0))
        if user_delay > 0:
            self.log("info", "等待用户延时 %d 秒" % user_delay)
            if not self._sleep(user_delay):
                return
        # 3. Submit once (no retry in the reference flow).
        try:
            result = self.client.order_confirm(order_id, timeout=10)
        except ApiError as exc:
            self.log("error", "抢单请求失败：%s" % exc.message)
            return
        if result.get("code") == 0:
            self.log("success", "抢单成功，请去查看订单，金额 %.2f 元" % amount_yuan_from_cents(order.get("totalAmount")))
            self.speak("抢单成功")
            self.stop()
        else:
            self.log("warning", "抢单未成功：%s" % (result.get("msg") or result.get("message") or ""))
            self.speak("抢单未成功")
