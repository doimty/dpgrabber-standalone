package com.doimty.dpgrabber.engine

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.ConfirmOutcome
import com.doimty.dpgrabber.core.OrderFilter
import com.doimty.dpgrabber.core.ReconcileResult
import com.doimty.dpgrabber.core.ReconcileVerdict
import com.doimty.dpgrabber.core.amountYuanFromCents
import com.doimty.dpgrabber.core.classifyConfirm
import com.doimty.dpgrabber.core.confirmBudgetSeconds
import com.doimty.dpgrabber.core.orderFilterFromConfig
import com.doimty.dpgrabber.core.orderIdOf
import com.doimty.dpgrabber.core.reconcileOrder
import com.doimty.dpgrabber.core.toMap
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log levels, same set as the PC engine. */
enum class LogLevel { INFO, WARNING, ERROR, SUCCESS, DEBUG }

/**
 * Listening engine (PROTOCOL.md sections 6-8, mirroring PC engine.py).
 *
 * One worker thread: poll -> filter -> confirm task -> submit.
 *
 * Flow per round:
 * 1. GET /s/c/order/randomList (page 1, 20 orders).
 * 2. No orders -> wait poll interval and poll again.
 * 3. Take only the first order, de-dup by id (seen set, TTL from config).
 * 4. Filters, in order (section 7.1): missing amount/id, blacklist
 *    WeChat id, min amount, max amount (0 = unlimited), memo mode,
 *    memo keywords (case-insensitive substring).
 * 5. Passed -> confirm task (section 8.2, DPGConfirmationTask): the task
 *    starts immediately (no precise wait, no reTime / confirmCountDown,
 *    no user delay) and loops POST /s/c/order/confirm until the window
 *    deadline (random_confirm_window_sec, default 20 s from the task
 *    start, or random_confirm_fallback_sec when the order has no
 *    createTime), retrying every confirm_retry_delay_sec (default
 *    0.5 s). Each response is classified: code 0 or a confirmed message
 *    -> success (stops the whole listen loop); a terminal message ->
 *    fail; 401/403 -> re-login; IP control -> pause 60 s; no response ->
 *    verify with GET /s/c/order/get (readback) instead of blind retry.
 *    At the deadline the order is verified once more; still pending ->
 *    released back to polling so a later round can pick it up again.
 * 6. On 401/403 the session is re-established with the saved account
 *    (manual-mode refresh, section 3.2 step 5); on IP control the loop
 *    pauses 60 s; other errors wait one poll interval and continue.
 *
 * Pure Kotlin: no Android framework use. Progress is reported through
 * [log] and [onStateChange]. `nowEpochMs` is injectable so the de-dup
 * math is unit-testable.
 */
class ListenEngine(
    private val client: ApiClient,
    private val config: AppConfig,
    private val log: (LogLevel, String) -> Unit,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val onStateChange: (Boolean) -> Unit = {}
) {
    @Volatile
    var running: Boolean = false
        private set

    private val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null
    private val seen = LinkedHashMap<String, Long>()
    private val seenTtlMs: Long = (config.seenTtlSec * 1000).toLong()
    private var consecutiveFailures = 0

    /** Clerk info fetched at start (best effort, display only). */
    @Volatile
    var clerk: JSONObject? = null
        private set

    fun start(phone: String, password: String): Boolean {
        if (running) return false
        stopFlag.set(false)
        running = true
        onStateChange(true)
        thread = Thread({ run(phone, password) }, "dpgrabber-listen").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        stopFlag.set(true)
        running = false
        onStateChange(false)
        thread?.interrupt()
    }

    /** Interruptible sleep. Returns false when asked to stop. */
    fun sleepInterruptible(seconds: Double): Boolean {
        val deadline = System.nanoTime() + (seconds * 1_000_000_000L).toLong()
        while (!stopFlag.get()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return true
            try {
                Thread.sleep(minOf(100, remaining / 1_000_000).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun markSeen(orderId: String) {
        val now = nowEpochMs()
        seen.entries.removeIf { now - it.value >= seenTtlMs }
        if (orderId.isNotEmpty()) seen[orderId] = now
    }

    fun isSeen(orderId: String): Boolean {
        if (orderId.isEmpty()) return false
        val seenAt = seen[orderId] ?: return false
        return nowEpochMs() - seenAt < seenTtlMs
    }

    private fun run(phone: String, password: String) {
        try {
            log(LogLevel.INFO, "正在登录…")
            val session = client.sessionForAccount(phone, password, config.shopId)
            log(LogLevel.SUCCESS, "登录成功，店铺 sid=${session.sid}")
            try {
                val clerkObj = ApiClient.extractClerk(client.clerkGet(session))
                if (clerkObj != null) {
                    clerk = clerkObj
                    log(
                        LogLevel.INFO,
                        "当前账号：${clerkObj.optString("name", "")}（ID：${clerkObj.optInt("id", 0)}）"
                    )
                }
            } catch (e: ApiError) {
                log(LogLevel.WARNING, "店员信息读取失败，不影响监听：${e.messageText}")
            }
            pollLoop(phone, password)
        } catch (e: ApiError) {
            log(LogLevel.ERROR, "启动失败：${e.messageText}")
        } finally {
            running = false
            onStateChange(false)
            log(LogLevel.INFO, "监听已停止")
        }
    }

    private fun refreshSession(phone: String, password: String): Boolean {
        return try {
            val session = client.sessionForAccount(phone, password, config.shopId)
            log(LogLevel.SUCCESS, "会话已重新建立，店铺 sid=${session.sid}")
            true
        } catch (e: ApiError) {
            log(LogLevel.ERROR, "重新登录失败：${e.messageText}")
            false
        }
    }

    private fun pollLoop(phone: String, password: String) {
        val interval = config.pollIntervalSec
        val timeout = config.requestTimeoutSec
        val filter = orderFilterFromConfig(config)
        log(LogLevel.INFO, "开始监听新订单")
        while (!stopFlag.get()) {
            val orders = try {
                client.randomList(pageSize = 20, timeoutSec = timeout)
            } catch (e: ApiError) {
                when (e.kind) {
                    ApiError.Kind.AUTH -> {
                        log(LogLevel.WARNING, "登录态失效，尝试重新登录…")
                        if (!refreshSession(phone, password)) return
                        continue
                    }
                    ApiError.Kind.IP -> {
                        log(LogLevel.WARNING, "IP 风控，暂停 60 秒")
                        if (!sleepInterruptible(60.0)) return
                        continue
                    }
                    else -> {
                        consecutiveFailures++
                        log(LogLevel.WARNING, "监听请求失败：${e.messageText}")
                        if (!sleepInterruptible(interval)) return
                        continue
                    }
                }
            }
            consecutiveFailures = 0
            if (orders.length() == 0) {
                if (!sleepInterruptible(interval)) return
                continue
            }
            val first = orders.optJSONObject(0)
            if (first == null) {
                if (!sleepInterruptible(interval)) return
                continue
            }
            val order = first
            val orderId = orderIdOf(order.toMap())
            if (isSeen(orderId)) {
                if (!sleepInterruptible(interval)) return
                continue
            }
            markSeen(orderId)
            val amount = amountYuanFromCents(order.opt("totalAmount"))
            log(
                LogLevel.INFO,
                "检测到新订单：金额 %.2f 元，客户微信 %s".format(
                    amount, order.optString("userWechatId", "").ifEmpty { "未知" }
                )
            )
            val (passed, reason) = filter.check(order)
            if (!passed) {
                log(LogLevel.INFO, "订单不符合要求：$reason")
                continue // re-poll immediately, as the reference tool does
            }
            if (handleOrder(order, orderId, phone, password)) return
        }
    }

    /** Drop an order from the seen set so a later poll round can pick it
     * up again (DPGConfirmationTask releaseForRetry). */
    private fun releaseSeen(orderId: String) {
        if (orderId.isNotEmpty()) seen.remove(orderId)
    }

    private fun ownClerkId(): Int {
        return try {
            clerk?.optInt("id", 0) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** One order/get verification (reconcileWithSession). Any API failure
     * means the result is unknown. */
    private fun readback(orderId: String): ReconcileResult {
        return try {
            val response = client.orderGet(orderId, timeoutSec = 15.0)
            reconcileOrder(response, ownClerkId())
        } catch (e: ApiError) {
            log(LogLevel.WARNING, "订单状态查询失败 id=$orderId：${e.messageText}")
            ReconcileResult(ReconcileVerdict.UNKNOWN, "")
        }
    }

    private fun grabSuccess(order: JSONObject, orderId: String, confirmCalls: Int): Boolean {
        val number = order.optString("no", "").ifEmpty { orderId }
        log(
            LogLevel.SUCCESS,
            "✓ 确认成功: $number (id=$orderId) confirm_calls=$confirmCalls"
        )
        log(
            LogLevel.SUCCESS,
            "抢单成功，请去查看订单，金额 %.2f 元".format(
                amountYuanFromCents(order.opt("totalAmount"))
            )
        )
        stopFlag.set(true)
        return true
    }

    private fun sleepRetry(deadlineMonoNs: Long): Boolean {
        val delaySec = config.confirmRetryDelaySec
        val remainingSec = (deadlineMonoNs - System.nanoTime()) / 1_000_000_000.0
        return sleepInterruptible(minOf(delaySec, maxOf(0.0, remainingSec)))
    }

    /**
     * One confirm task (DPGConfirmationTask Init -> Loop -> Final /
     * Readback -> Done). Starts immediately; loops confirm until the
     * window deadline. Returns true when the grab succeeded (the caller
     * stops the whole listen loop).
     */
    fun handleOrder(order: JSONObject, orderId: String, phone: String, password: String): Boolean {
        val budget = confirmBudgetSeconds(
            order, config.randomConfirmWindowSec, config.randomConfirmFallbackSec
        )
        val startedMonoNs = System.nanoTime()
        val deadlineMonoNs = startedMonoNs + (budget * 1_000_000_000L).toLong()
        val amount = amountYuanFromCents(order.opt("totalAmount"))
        val number = order.optString("no", "").ifEmpty { orderId }
        log(LogLevel.INFO, "尝试接单 $number ￥%.2f".format(amount))
        var confirmCalls = 0
        while (!stopFlag.get()) {
            val remainingNs = deadlineMonoNs - System.nanoTime()
            if (remainingNs <= 0) break
            val timeoutSec = minOf(
                config.requestTimeoutSec,
                maxOf(0.1, remainingNs / 1_000_000_000.0)
            )
            val result: JSONObject
            try {
                confirmCalls++
                result = client.orderConfirm(orderId, timeoutSec = timeoutSec)
            } catch (e: ApiError) {
                when (e.kind) {
                    ApiError.Kind.AUTH -> {
                        releaseSeen(orderId)
                        log(LogLevel.WARNING, "确认认证失败，等待账号单飞刷新: $number")
                        refreshSession(phone, password)
                        return false
                    }
                    ApiError.Kind.IP -> {
                        releaseSeen(orderId)
                        log(LogLevel.WARNING, "确认触发IP风控，账号共享暂停60s，不重登录")
                        sleepInterruptible(60.0)
                        return false
                    }
                    ApiError.Kind.CANCELLED -> return false
                    else -> {
                        // No response (network / undecodable): verify
                        // instead of blind retry.
                        val checked = readback(orderId)
                        when (checked.verdict) {
                            ReconcileVerdict.OK ->
                                return grabSuccess(order, orderId, confirmCalls)
                            ReconcileVerdict.FAIL -> {
                                log(
                                    LogLevel.WARNING,
                                    "✗ 状态查询确认终态失败: ${checked.reason}"
                                )
                                return false
                            }
                            ReconcileVerdict.PENDING -> {
                                if (!sleepRetry(deadlineMonoNs)) return false
                                continue
                            }
                            ReconcileVerdict.UNKNOWN -> {
                                log(LogLevel.WARNING, "⚠ 确认结果未知，停止盲重试: $number")
                                return false
                            }
                        }
                    }
                }
            }
            val classification = classifyConfirm(result)
            when (classification.outcome) {
                ConfirmOutcome.OK ->
                    return grabSuccess(order, orderId, confirmCalls)
                ConfirmOutcome.FAIL -> {
                    log(
                        LogLevel.WARNING,
                        "✗ 确认终态失败 $number: ${classification.reason}"
                    )
                    return false
                }
                ConfirmOutcome.RETRY -> {
                    log(
                        LogLevel.INFO,
                        "[confirm] retry $number confirm_calls=$confirmCalls " +
                            "reason=${classification.reason}"
                    )
                    if (!sleepRetry(deadlineMonoNs)) return false
                }
            }
        }
        // Deadline reached: one final verification (stepFinal).
        val final = readback(orderId)
        return when (final.verdict) {
            ReconcileVerdict.OK ->
                grabSuccess(order, orderId, confirmCalls)
            ReconcileVerdict.FAIL -> {
                log(
                    LogLevel.WARNING,
                    "✗ 窗口结束，订单已不可确认: $number confirm_calls=$confirmCalls"
                )
                false
            }
            ReconcileVerdict.PENDING -> {
                releaseSeen(orderId)
                log(
                    LogLevel.INFO,
                    "↻ 本轮确认预算耗尽，订单仍待确认: $number confirm_calls=$confirmCalls"
                )
                false
            }
            ReconcileVerdict.UNKNOWN -> {
                log(
                    LogLevel.WARNING,
                    "⚠ 窗口结束，确认结果未知: $number confirm_calls=$confirmCalls"
                )
                false
            }
        }
    }

    private fun formatTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(epochMs))
    }
}
