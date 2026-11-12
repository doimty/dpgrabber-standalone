package com.doimty.dpgrabber.engine

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.OrderFilter
import com.doimty.dpgrabber.core.amountYuanFromCents
import com.doimty.dpgrabber.core.computeGrabWait
import com.doimty.dpgrabber.core.normalizeMs
import com.doimty.dpgrabber.core.orderFilterFromConfig
import com.doimty.dpgrabber.core.orderIdOf
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
 * One worker thread: poll -> filter -> precise grab time -> submit.
 *
 * Flow per round:
 * 1. GET /s/c/order/randomList (page 1, 20 orders).
 * 2. No orders -> wait poll interval and poll again.
 * 3. Take only the first order, de-dup by id (seen set, TTL from config).
 * 4. Filters, in order (section 7.1): missing amount/id, blacklist
 *    WeChat id, min amount, max amount (0 = unlimited), memo mode,
 *    memo keywords (case-insensitive substring).
 * 5. Passed -> precise grab time (section 8.1): POST /s/c/order/get,
 *    grab_time = reTime + confirmCountDown; wait in 0.1 s steps
 *    (stoppable); then the user delay in 1 s steps (stoppable); then
 *    POST /s/c/order/confirm once. Success stops the whole listen loop.
 * 6. On 401/403 the session is re-established with the saved account
 *    (manual-mode refresh, section 3.2 step 5); on IP control the loop
 *    pauses 60 s; other errors wait one poll interval and continue.
 *
 * Pure Kotlin: no Android framework use. Progress is reported through
 * [log] and [onStateChange]. `nowEpochMs` is injectable so the precise
 * wait math is unit-testable.
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
            if (handleOrder(order, orderId)) return
        }
    }

    /**
     * Precise grab for one passing order. Returns true when the grab
     * succeeded (the caller stops the whole listen loop).
     */
    fun handleOrder(order: JSONObject, orderId: String): Boolean {
        // 1. Precise grab time (section 8.1).
        var wait = 0.0
        var grabMs: Long? = null
        var reason = com.doimty.dpgrabber.core.GrabWait.Reason.MISSING_FIELDS
        try {
            val detail = client.orderGet(orderId, timeoutSec = 10.0)
            if (detail.optInt("code", -1) != 0) {
                log(LogLevel.WARNING, "订单详情读取失败：${detail.optString("msg", "")}")
                return false
            }
            val inner = detail.optJSONObject("order") ?: detail
            val grab = computeGrabWait(inner, nowEpochMs() / 1000.0)
            wait = grab.waitSeconds
            grabMs = grab.grabEpochMs
            reason = grab.reason
            val createMs = normalizeMs(inner.opt("createTime"))
            if (createMs > 0) {
                log(LogLevel.DEBUG, "订单创建时间：${formatTime(createMs)}")
            }
            if (reason == com.doimty.dpgrabber.core.GrabWait.Reason.OK && grabMs != null) {
                log(LogLevel.INFO, "准确抢单时间：${formatTime(grabMs)}，等待 %.1f 秒".format(wait))
            }
        } catch (e: ApiError) {
            if (e.kind == ApiError.Kind.AUTH) {
                log(LogLevel.WARNING, "登录态失效，订单详情未读取")
                return false
            }
            log(LogLevel.WARNING, "订单详情读取失败：${e.messageText}")
            return false
        }
        if (wait > 0 && !sleepInterruptible(wait)) return false
        // 2. User delay (reference tool: 1 s steps, stoppable).
        if (config.userDelaySec > 0) {
            log(LogLevel.INFO, "等待用户延时 ${config.userDelaySec} 秒")
            if (!sleepInterruptible(config.userDelaySec.toDouble())) return false
        }
        // 3. Submit once (no retry in the reference flow).
        return try {
            val result = client.orderConfirm(orderId, timeoutSec = 10.0)
            if (result.optInt("code", -1) == 0) {
                log(
                    LogLevel.SUCCESS,
                    "抢单成功，请去查看订单，金额 %.2f 元".format(
                        amountYuanFromCents(order.opt("totalAmount"))
                    )
                )
                stopFlag.set(true)
                true
            } else {
                log(
                    LogLevel.WARNING,
                    "抢单未成功：${result.optString("msg", result.optString("message", ""))}"
                )
                false
            }
        } catch (e: ApiError) {
            log(LogLevel.ERROR, "抢单请求失败：${e.messageText}")
            false
        }
    }

    private fun formatTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(epochMs))
    }
}
