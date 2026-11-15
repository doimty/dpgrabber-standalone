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
    private val onStateChange: (Boolean) -> Unit = {},
    private val configSnapshot: () -> AppConfig = { config },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val onOutcome: (OrderOutcome) -> Unit = {},
    private val onSuccess: () -> Unit = {},
    private val blockedOrderIds: Set<String> = emptySet(),
    private val isPersistentlyBlocked: (String, String) -> Boolean = { _, _ -> false },
    private val monotonicNs: () -> Long = { System.nanoTime() },
    private val onAttemptStarted: (OrderOutcome) -> Unit = {}
) {
    @Volatile
    var running: Boolean = false
        private set

    private val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null
    private val seen = LinkedHashMap<String, Long>()
    private val seenTtlMs: Long = (config.seenTtlSec * 1000).toLong()
    private var consecutiveFailures = 0
    private val runId = java.util.UUID.randomUUID().toString()
    private val completedOrders = mutableSetOf<String>()
    private var outcomeType = OutcomeType.UNKNOWN
    private var outcomeReason = "结果未知"
    private var submitted = false
    private var attemptRecord: OrderOutcome? = null

    private fun outcome(type: OutcomeType, reason: String = "") {
        outcomeType = type
        outcomeReason = reason
    }

    /** Clerk info fetched at start (best effort, display only). */
    @Volatile
    var clerk: JSONObject? = null
        private set

    fun start(phone: String, password: String): Boolean {
        if (running || thread?.isAlive == true) return false
        client.resumeRequests()
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
        client.cancel()
        thread?.interrupt()
    }

    /** 仅供后台调用，主线程不可等待工作线程退出。 */
    fun awaitStopped(timeoutMs: Long = 5000): Boolean {
        thread?.join(timeoutMs)
        return thread?.isAlive != true
    }

    /** Interruptible sleep. Returns false when asked to stop. */
    fun sleepInterruptible(seconds: Double): Boolean {
        val deadline = monotonicNs() + (seconds * 1_000_000_000L).toLong()
        while (!stopFlag.get()) {
            val remaining = deadline - monotonicNs()
            if (remaining <= 0) return true
            try {
                sleeper(minOf(100, remaining / 1_000_000).coerceAtLeast(1))
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
            val session = client.session ?: client.sessionForAccount(phone, password, config.shopId)
            if (stopFlag.get()) return
            if (configSnapshot().onlineAfterLogin) client.updateStatus(1, false)
            if (stopFlag.get()) return
            log(LogLevel.SUCCESS, "登录成功，店铺 sid=${session.sid}")
            try {
                val clerkObj = ApiClient.extractClerk(client.clerkGet(session))
                if (clerkObj != null) {
                    clerk = clerkObj
                    log(
                        LogLevel.INFO,
                        "当前店员信息已读取"
                    )
                }
            } catch (e: ApiError) {
                log(LogLevel.ERROR, "店员身份未能核实，停止监听")
                return
            }
            if (ownClerkId() <= 0) throw ApiError(ApiError.Kind.AUTH, "无法确认店员身份")
            pollLoop(phone, password)
        } catch (e: ApiError) {
            if (e.kind != ApiError.Kind.CANCELLED) log(LogLevel.ERROR, "启动失败：${e.messageText}")
        } catch (_: Exception) {
            log(LogLevel.ERROR, "任务异常停止，请核对历史中的未知结果")
        } finally {
            running = false
            onStateChange(false)
            log(LogLevel.INFO, "监听已停止")
        }
    }

    private fun refreshSession(phone: String, password: String): Boolean {
        return try {
            if (stopFlag.get()) return false
            val previousSid = client.session?.sid
            val session = client.sessionForAccount(phone, password, config.shopId)
            if (stopFlag.get()) return false
            if (previousSid != session.sid) { seen.clear(); completedOrders.clear() }
            clerk = null
            clerk = ApiClient.extractClerk(client.clerkGet(session))
                ?: throw ApiError(ApiError.Kind.AUTH, "刷新后缺少有效店员身份")
            if (stopFlag.get()) return false
            log(LogLevel.SUCCESS, "会话已重新建立，店铺 sid=${session.sid}")
            true
        } catch (e: ApiError) {
            log(LogLevel.ERROR, "重新登录失败：${e.messageText}")
            false
        }
    }

    private fun pollLoop(phone: String, password: String) {
        var runStarted = monotonicNs()
        log(LogLevel.INFO, "开始监听新订单")
        while (!stopFlag.get()) {
            val current = configSnapshot()
            val interval = (current.pollIntervalSec + kotlin.random.Random.nextDouble(-1.0, 1.0) * current.pollJitterSec).coerceAtLeast(0.1)
            val timeout = current.requestTimeoutSec
            val filter = orderFilterFromConfig(current)
            if (current.runDurationSec > 0 && monotonicNs() - runStarted >= current.runDurationSec * 1_000_000_000) {
                log(LogLevel.INFO, "进入定时休息")
                if (!sleepInterruptible(current.restDurationSec)) return
                runStarted = monotonicNs()
            }
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
                        if (!sleepInterruptible(minOf(60.0, interval * (1 shl minOf(consecutiveFailures, 6))))) return
                        continue
                    }
                }
            }
            if (stopFlag.get()) return
            consecutiveFailures = 0
            if (orders.length() == 0) {
                if (!sleepInterruptible(interval)) return
                continue
            }
            for (index in 0 until orders.length()) {
                if (stopFlag.get()) return
                val order = orders.optJSONObject(index) ?: continue
                val orderId = orderIdOf(order.toMap())
                if (orderId.isEmpty() || isSeen(orderId) || orderId in completedOrders || orderId in blockedOrderIds || isPersistentlyBlocked(client.session?.sid ?: "", orderId)) continue
                markSeen(orderId)
                val latest = configSnapshot()
                val latestFilter = orderFilterFromConfig(latest)
                if (!latestFilter.check(order).first) {
                    log(LogLevel.INFO, "订单不符合本地过滤条件")
                    continue
                }
                val amount = amountYuanFromCents(order.opt("totalAmount"))
                log(LogLevel.INFO, "检测到新订单：金额 %.2f 元".format(amount))
                if (handleOrder(order, orderId, phone, password)) return
                break // 每轮只串行确认一单，但首项被过滤或已处理不会遮挡后续订单。
            }
            if (!sleepInterruptible(interval)) return
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
        if (stopFlag.get()) return ReconcileResult(ReconcileVerdict.UNKNOWN, "已停止")
        return try {
            val response = client.orderGet(orderId, timeoutSec = 15.0)
            reconcileOrder(response, ownClerkId())
        } catch (e: ApiError) {
            log(LogLevel.WARNING, "订单状态查询失败：${e.messageText}")
            ReconcileResult(ReconcileVerdict.UNKNOWN, "")
        }
    }

    private fun grabSuccess(order: JSONObject, orderId: String, confirmCalls: Int): Boolean {
        if (stopFlag.get()) return false
        outcome(OutcomeType.SUCCESS)
        return configSnapshot().autoStopAfterGrab
    }

    private fun sleepRetry(deadlineMonoNs: Long): Boolean {
        val delaySec = config.confirmRetryDelaySec
        val remainingSec = (deadlineMonoNs - monotonicNs()) / 1_000_000_000.0
        return sleepInterruptible(minOf(delaySec, maxOf(0.0, remainingSec)))
    }

    /**
     * One confirm task (DPGConfirmationTask Init -> Loop -> Final /
     * Readback -> Done). Starts immediately; loops confirm until the
     * window deadline. Returns true when the grab succeeded (the caller
     * stops the whole listen loop).
     */
    fun handleOrder(order: JSONObject, orderId: String, phone: String, password: String): Boolean {
        if (stopFlag.get()) return false
        val current = configSnapshot()
        val waitSec = com.doimty.dpgrabber.core.confirmWaitSeconds(order, client.session?.sid ?: config.shopId.toString(), current, nowEpochMs())
        if (!sleepInterruptible(waitSec)) return false
        if (!orderFilterFromConfig(configSnapshot()).check(order).first) return false
        val attempt = java.util.UUID.randomUUID().toString()
        val accountKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(phone.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        attemptRecord = OrderOutcome(accountKey, client.session?.sid ?: config.shopId.toString(),
            orderId, runId, attempt, nowEpochMs(), OutcomeType.UNKNOWN, "请求可能已提交，待核对", order)
        submitted = false
        outcome(OutcomeType.UNKNOWN, "确认结果未知；禁止自动重发")
        try {
            return handleOrderAttempt(order, orderId, phone, password)
        } finally {
            if (submitted) {
                completedOrders.add(orderId)
                if (stopFlag.get()) outcome(OutcomeType.UNKNOWN, "停止时请求已在途，待核对")
                try {
                    onOutcome(checkNotNull(attemptRecord).copy(timestampMs = nowEpochMs(), type = outcomeType, reason = outcomeReason))
                } catch (error: Exception) {
                    stop()
                    throw error
                }
                if (outcomeType == OutcomeType.SUCCESS) {
                    log(LogLevel.SUCCESS, "抢单成功，已保存历史，请去查看订单")
                    onSuccess()
                    if (configSnapshot().autoStopAfterGrab) stopFlag.set(true)
                }
            }
        }
    }

    private fun handleOrderAttempt(order: JSONObject, orderId: String, phone: String, password: String): Boolean {
        val budget = confirmBudgetSeconds(
            order, config.randomConfirmWindowSec, config.randomConfirmFallbackSec
        )
        val startedMonoNs = monotonicNs()
        val deadlineMonoNs = startedMonoNs + (budget * 1_000_000_000L).toLong()
        val amount = amountYuanFromCents(order.opt("totalAmount"))
        val number = "***" + orderId.takeLast(4)
        log(LogLevel.INFO, "尝试接单 $number ￥%.2f".format(amount))
        var confirmCalls = 0
        while (!stopFlag.get()) {
            val remainingNs = deadlineMonoNs - monotonicNs()
            if (remainingNs <= 0) break
            val timeoutSec = minOf(
                config.requestTimeoutSec,
                maxOf(0.1, remainingNs / 1_000_000_000.0)
            )
            val result: JSONObject
            if (!submitted) {
                try { onAttemptStarted(checkNotNull(attemptRecord)) }
                catch (error: Exception) { stop(); throw error }
            }
            try {
                confirmCalls++
                submitted = true
                result = client.orderConfirm(orderId, timeoutSec = timeoutSec)
            } catch (e: ApiError) {
                when (e.kind) {
                    ApiError.Kind.AUTH -> {
                        outcome(OutcomeType.FAILED, "认证失败")
                        releaseSeen(orderId)
                        log(LogLevel.WARNING, "确认认证失败，等待账号单飞刷新: $number")
                        if (!refreshSession(phone, password)) stopFlag.set(true)
                        return false
                    }
                    ApiError.Kind.IP -> {
                        outcome(OutcomeType.RISK, "平台风控暂停")
                        releaseSeen(orderId)
                        log(LogLevel.WARNING, "确认触发IP风控，账号共享暂停60s，不重登录")
                        sleepInterruptible(60.0)
                        return false
                    }
                    ApiError.Kind.CANCELLED -> return false
                    ApiError.Kind.PERMISSION -> {
                        outcome(OutcomeType.FAILED, "店员权限拒绝")
                        return false
                    }
                    else -> {
                        // No response (network / undecodable): verify
                        // instead of blind retry.
                        val checked = readback(orderId)
                        when (checked.verdict) {
                            ReconcileVerdict.OK ->
                                return grabSuccess(order, orderId, confirmCalls)
                            ReconcileVerdict.FAIL -> {
                                outcome(if (checked.reason == "被其他人抢走") OutcomeType.SKIPPED else OutcomeType.FAILED, checked.reason)
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
            if (stopFlag.get()) return false
            val classification = classifyConfirm(result)
            when (classification.outcome) {
                ConfirmOutcome.OK ->
                    return grabSuccess(order, orderId, confirmCalls)
                ConfirmOutcome.FAIL -> {
                    outcome(if (classification.reason == "被其他人抢走") OutcomeType.SKIPPED else OutcomeType.FAILED, classification.reason)
                    log(
                        LogLevel.WARNING,
                        "✗ 确认终态失败 $number: ${classification.reason}"
                    )
                    return false
                }
                ConfirmOutcome.VERIFY -> {
                    val checked = readback(orderId)
                    if (stopFlag.get()) return false
                    return when (checked.verdict) {
                        ReconcileVerdict.OK -> grabSuccess(order, orderId, confirmCalls)
                        ReconcileVerdict.FAIL -> {
                            outcome(if (checked.reason == "被其他人抢走") OutcomeType.SKIPPED else OutcomeType.FAILED, checked.reason)
                            false
                        }
                        ReconcileVerdict.PENDING, ReconcileVerdict.UNKNOWN -> {
                            outcome(OutcomeType.UNKNOWN, "返回文案不确定，未再次提交；请只读核验")
                            false
                        }
                    }
                }
                ConfirmOutcome.RETRY -> {
                    log(
                        LogLevel.INFO,
                        "[confirm] retry $number confirm_calls=$confirmCalls " +
                            "平台要求稍后再试"
                    )
                    if (!sleepRetry(deadlineMonoNs)) return false
                }
            }
        }
        if (stopFlag.get()) return false
        // 只有预算耗尽且本轮仍有效时才允许最终回读。
        val final = readback(orderId)
        return when (final.verdict) {
            ReconcileVerdict.OK ->
                grabSuccess(order, orderId, confirmCalls)
            ReconcileVerdict.FAIL -> {
                outcome(if (final.reason == "被其他人抢走") OutcomeType.SKIPPED else OutcomeType.FAILED, final.reason)
                log(
                    LogLevel.WARNING,
                    "✗ 窗口结束，订单已不可确认: $number confirm_calls=$confirmCalls"
                )
                false
            }
            ReconcileVerdict.PENDING -> {
                outcome(OutcomeType.UNKNOWN, "确认预算耗尽，待人工核对")
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
