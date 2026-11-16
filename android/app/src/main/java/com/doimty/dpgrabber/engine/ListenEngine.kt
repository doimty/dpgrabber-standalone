package com.doimty.dpgrabber.engine

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.ConfirmOutcome
import com.doimty.dpgrabber.core.ItemSwitchScope
import com.doimty.dpgrabber.core.accountKeyForPhone
import com.doimty.dpgrabber.core.orderItemIds
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

/** 同一类业务拒绝连续达到该次数即熔断停止，避免无限撞墙。 */
private const val REJECT_STOP_THRESHOLD = 5

/** 传输层失败的硬退避阶梯（秒）：首次 5 秒，之后 10/20/30，封顶 30。 */
private val TRANSPORT_BACKOFF_SEC = doubleArrayOf(5.0, 10.0, 20.0, 30.0)

/**
 * 平台原因里出现这些词即按“店铺不在线”处理。
 * 与 core/Confirm.kt 的 QUALIFICATION_WORDS 同一思路（命中即分类），
 * 但那张表只覆盖资质类词，不含上线状态，故在此单列三个最小词。
 */
private val OFFLINE_STATUS_WORDS = listOf("未上线", "请先上线", "不在线")

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
    /** 宿主报告的致命故障（如历史写入失败）：只停止，不影响正常收尾通知。 */
    private val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null
    private val seen = LinkedHashMap<String, Long>()
    private val seenTtlMs: Long = (config.seenTtlSec * 1000).toLong()
    private var consecutiveFailures = 0
    /** 相同轮询错误仅首次及每10次摘要，避免短周期刷屏。 */
    private var lastFailureLog: String? = null
    private var sameFailureLogCount = 0
    private var networkFailureCount = 0
    private var busyCooldownPending = false
    private var missingItemTypeWarned = false
    /** 同一类业务拒绝（code!=0 且 messageText 未变）的连续次数，用于熔断。 */
    private var sameRejectStreak = 0
    private var lastRejectText: String? = null
    /** 传输层失败（连不上/被掐断）的退避档位：5s → 10s → 20s → 30s。 */
    private var transportBackoffStep = 0
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
        aborted.set(false)
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

    /**
     * 宿主侧致命故障的安全出口（历史写入失败等）。
     *
     * 只置标志：循环在下一个检查点退出，本轮已提交的请求不再重发，
     * 也不会再走到“抢单成功”的通知分支。不抛异常、不取消 transport，
     * 因此在工作线程内部调用同样安全。
     */
    fun abort() {
        aborted.set(true)
        stopFlag.set(true)
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
        } catch (error: Throwable) {
            // 工作线程的最终兜底：任何 Throwable（含 Error、第三方库的
            // 运行时异常）都收敛成“停止 + 脱敏日志”。未捕获异常会直接
            // 杀死进程，因此这里绝不允许向外抛。
            safeLog(LogLevel.ERROR, stopReason(error))
        } finally {
            running = false
            onStateChange(false)
            safeLog(LogLevel.INFO, "监听已停止")
        }
    }

    /** 日志回调本身也不能让异常逃出工作线程。 */
    private fun safeLog(level: LogLevel, text: String) {
        try { log(level, text) } catch (_: Throwable) { }
    }

    /**
     * 相同错误仅首次及每10次摘要；错误分类变化立即记一条。
     * 轮询恢复后清空去重键；不改变现有熔断、退避间隔与时序。
     */
    private fun logFailureOnce(level: LogLevel, text: String) {
        sameFailureLogCount = if (text == lastFailureLog) sameFailureLogCount + 1 else 1
        lastFailureLog = text
        if (sameFailureLogCount == 1) log(level, text)
        else if (sameFailureLogCount % 10 == 0) log(level, "同类轮询错误已连续发生${sameFailureLogCount}次，仍在退避重试")
    }

    /**
     * 熔断文案：能从平台原因里认出店铺状态问题就给更具体的指引，
     * 否则给含 code 的通用文案；两者都只说分类，不含订单号/手机号/令牌。
     */
    private fun rejectStopMessage(text: String): String {
        if (OFFLINE_STATUS_WORDS.any { text.contains(it) }) {
            return "店铺未上线，抢单请求被拒绝：已停止监听。请先在工作台点「上线」。"
        }
        val code = Regex("code=(-?\\d+)").find(text)?.groupValues?.get(1)
        val suffix = if (code == null) "" else "（code=$code）"
        return "平台连续拒绝 $REJECT_STOP_THRESHOLD 次$suffix：已停止监听。" +
            "可能是账号在其他设备登录、店铺未上线或操作过于频繁，请核对后重新开始。"
    }

    /** 只输出脱敏的故障分类，绝不带订单号、手机号或令牌。 */
    private fun stopReason(error: Throwable): String = when (error) {
        is InterruptedException -> "任务被中断，已停止"
        is SecurityException -> "缺少系统权限，任务已停止"
        is java.io.IOException -> "本地读写失败，任务已停止，请核对历史中的未知结果"
        is OutOfMemoryError -> "内存不足，任务已停止，请核对历史中的未知结果"
        is Error -> "运行时故障，任务已停止，请核对历史中的未知结果"
        else -> "任务异常停止，请核对历史中的未知结果"
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
                    ApiError.Kind.NETWORK -> {
                        networkFailureCount++
                        // 传输层：根本拿不到业务响应（TLS EOF/超时/拒绝连接），
                        // 高频重试只会继续撞限流，故用更硬的 5/10/20/30 秒退避。
                        val delaySec = TRANSPORT_BACKOFF_SEC[minOf(transportBackoffStep, TRANSPORT_BACKOFF_SEC.size - 1)]
                        if (transportBackoffStep < TRANSPORT_BACKOFF_SEC.size - 1) transportBackoffStep++
                        // 文案不带变化中的秒数，确保相同故障可以合并计数。
                        logFailureOnce(LogLevel.WARNING,
                            "网络连接中断，正在退避重试（最长 30 秒）：${e.messageText}")
                        if (!sleepInterruptible(delaySec)) return
                        continue
                    }
                    else -> {
                        consecutiveFailures++
                        // 业务拒绝：同一类拒绝连续 5 次就停止，避免无限撞墙。
                        val text = e.messageText
                        sameRejectStreak = if (text == lastRejectText) sameRejectStreak + 1 else 1
                        lastRejectText = text
                        if (sameRejectStreak >= REJECT_STOP_THRESHOLD) {
                            log(LogLevel.ERROR, rejectStopMessage(text))
                            // 安全停止：只退出轮询，由 run() 的 finally 收尾，
                            // 不抛异常、不额外 cancel（本轮没有在途请求）。
                            return
                        }
                        logFailureOnce(LogLevel.WARNING,
                            "抢单请求被平台拒绝：$text")
                        if (!sleepInterruptible(minOf(60.0, interval * (1 shl minOf(consecutiveFailures, 6))))) return
                        continue
                    }
                }
            }
            if (stopFlag.get()) return
            consecutiveFailures = 0
            // 轮询恢复正常：清掉去重键与熔断/退避计数，下次同样的失败会重新记并重新计数。
            if (networkFailureCount > 0) {
                log(LogLevel.INFO, "网络已恢复，此前连续失败${networkFailureCount}次")
                networkFailureCount = 0
            }
            lastFailureLog = null
            sameFailureLogCount = 0
            sameRejectStreak = 0
            lastRejectText = null
            transportBackoffStep = 0
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
                val latestFilter = orderFilterFromConfig(latest, itemScope(phone))
                if (latestFilter.enabledItems.isNotEmpty() && orderItemIds(order).isEmpty() && !missingItemTypeWarned) {
                    missingItemTypeWarned = true
                    log(LogLevel.WARNING, "订单缺少可识别的类型，类型联动暂不限制；名称仅映射已知语音/视频/游戏，特殊店铺归属待核验")
                }
                if (!latestFilter.check(order).first) {
                    log(LogLevel.INFO, "订单不符合本地过滤条件")
                    continue
                }
                val amount = amountYuanFromCents(order.opt("totalAmount"))
                log(LogLevel.INFO, "检测到新订单：金额 %.2f 元".format(amount))
                if (handleOrder(order, orderId, phone, password)) return
                if (busyCooldownPending) {
                    busyCooldownPending = false
                    val cooldown = configSnapshot().busyCooldownSec
                    log(LogLevel.INFO, "同时接单名额已满，已记录跳过，冷却${cooldown}秒后继续监听")
                    if (!sleepInterruptible(cooldown.toDouble())) return
                }
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
    private fun itemScope(phone: String) = ItemSwitchScope(accountKeyForPhone(phone), client.session?.sid.orEmpty())

    fun handleOrder(order: JSONObject, orderId: String, phone: String, password: String): Boolean {
        busyCooldownPending = false
        if (stopFlag.get() || orderId in completedOrders || orderId in blockedOrderIds ||
            isPersistentlyBlocked(client.session?.sid.orEmpty(), orderId)) return false
        val current = configSnapshot()
        val waitSec = com.doimty.dpgrabber.core.confirmWaitSeconds(order, client.session?.sid ?: config.shopId.toString(), current, nowEpochMs())
        if (!sleepInterruptible(waitSec)) return false
        if (!orderFilterFromConfig(configSnapshot(), itemScope(phone)).check(order).first) return false
        val attempt = java.util.UUID.randomUUID().toString()
        val accountKey = accountKeyForPhone(phone)
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
                if (outcomeType == OutcomeType.SUCCESS && !aborted.get()) {
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
            if (!orderFilterFromConfig(configSnapshot(), itemScope(phone)).check(order).first) return false
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
                                outcome(OutcomeType.UNKNOWN, "请求响应未知，回查仍待确认；禁止自动重发")
                                return false
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
                ConfirmOutcome.BUSY -> {
                    outcome(OutcomeType.SKIPPED, classification.reason)
                    busyCooldownPending = true
                    log(LogLevel.WARNING, classification.reason)
                    return false
                }
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
