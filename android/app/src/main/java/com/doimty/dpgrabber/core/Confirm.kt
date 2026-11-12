package com.doimty.dpgrabber.core

import org.json.JSONObject

/**
 * Confirm task (DPGConfirmationTask, DPGEngine.m). Pure helpers, shared
 * semantics with the PC / iOS engines.
 *
 * A passing order is confirmed immediately inside a window (no precise
 * wait, no reTime / confirmCountDown): POST /s/c/order/confirm is
 * retried every [AppConfig.confirmRetryDelaySec] until the window
 * deadline, then verified once more with GET /s/c/order/get.
 */

/** A confirm reply whose message contains one of these means success. */
val CONFIRMED_WORDS = listOf("已接单", "已确认", "接单成功", "确认成功")

/** A confirm reply whose message contains one of these is terminally failed. */
val TERMINAL_WORDS = listOf("取消", "已被", "不存在", "无权", "权限", "失效", "超时")

/** Human-readable reason for a terminal confirm failure (DPGTerminalReason). */
fun terminalReason(message: String?): String {
    val text = message ?: ""
    for (word in listOf("已被", "被其他", "他人抢", "别人抢", "抢走")) {
        if (text.contains(word)) return "被其他人抢走"
    }
    if (text.contains("取消")) return "已取消"
    if (text.contains("不存在")) return "订单不存在"
    if (text.contains("无权") || text.contains("权限")) return "无确认权限"
    if (text.contains("失效")) return "订单已失效"
    if (text.contains("超时")) return "确认超时"
    return if (text.isNotEmpty()) text else "平台拒绝确认"
}

/** Outcome of classifying one confirm response. */
enum class ConfirmOutcome {
    /** code 0 or a confirmed message. */
    OK,
    /** A terminal message; [reason] explains it. */
    FAIL,
    /** Anything else: retry until the deadline. */
    RETRY
}

data class ConfirmClassification(val outcome: ConfirmOutcome, val reason: String)

/**
 * Classify one confirm response (DPGEngine.m stepLoop): code 0 wins over
 * the message text, then confirmed words, then terminal words.
 */
fun classifyConfirm(result: JSONObject?): ConfirmClassification {
    if (result == null) return ConfirmClassification(ConfirmOutcome.RETRY, "")
    val code = ApiClient.parseCode(result)
    val message = result.optString("msg", result.optString("message", ""))
    if (code == 0) return ConfirmClassification(ConfirmOutcome.OK, "")
    if (CONFIRMED_WORDS.any { message.contains(it) }) {
        return ConfirmClassification(ConfirmOutcome.OK, "")
    }
    if (TERMINAL_WORDS.any { message.contains(it) }) {
        return ConfirmClassification(ConfirmOutcome.FAIL, terminalReason(message))
    }
    return ConfirmClassification(ConfirmOutcome.RETRY, message)
}

/**
 * Confirm window budget in seconds (deadlineForOrderAtWall, random
 * kind): the window when the order has a createTime, the fallback
 * window when it does not; 20 s when the configured value is invalid.
 */
fun confirmBudgetSeconds(order: JSONObject?, windowSec: Double, fallbackSec: Double): Double {
    val createMs = if (order == null) 0L else normalizeMs(order.opt("createTime"))
    val budget = if (createMs > 0) windowSec else fallbackSec
    if (!budget.isFinite() || budget <= 0) return 20.0
    return budget
}

/** Verdict of one order/get verification. */
enum class ReconcileVerdict {
    /** Confirmed (status 2/3 or a confirmed message). */
    OK,
    /** Terminally failed. */
    FAIL,
    /** Still pending (status 0). */
    PENDING,
    /** Cannot tell; stop blind retry. */
    UNKNOWN
}

data class ReconcileResult(val verdict: ReconcileVerdict, val reason: String)

/**
 * Verify a confirm result with one order/get response
 * (reconcileWithSession, DPGEngine.m).
 *
 * The payload is the first dict among response["order"],
 * response["data"]["order"], response["data"] and the response itself
 * that carries a "status" or "msg" field.
 */
fun reconcileOrder(response: JSONObject?, ownClerkId: Int): ReconcileResult {
    val payload = pickReconcilePayload(response) ?: JSONObject()
    val message = payload.optString("msg", payload.optString("message", "")).ifEmpty {
        response?.optString("msg", "") ?: ""
    }
    val status = payload.opt("status")?.let { raw ->
        when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
    } ?: 0
    val confirmed = CONFIRMED_WORDS.any { message.contains(it) } || status == 2 || status == 3
    if (confirmed) {
        val clerkId = payload.opt("clerkId")?.let { raw ->
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: 0
                else -> 0
            }
        } ?: 0
        if (ownClerkId > 0 && clerkId > 0 && clerkId != ownClerkId) {
            return ReconcileResult(ReconcileVerdict.FAIL, "被其他人抢走")
        }
        return ReconcileResult(ReconcileVerdict.OK, "")
    }
    if (TERMINAL_WORDS.any { message.contains(it) } || status == -1) {
        return ReconcileResult(ReconcileVerdict.FAIL, terminalReason(message))
    }
    if (status == 0) return ReconcileResult(ReconcileVerdict.PENDING, "")
    return ReconcileResult(ReconcileVerdict.UNKNOWN, message)
}

private fun pickReconcilePayload(response: JSONObject?): JSONObject? {
    if (response == null) return null
    val order = response.optJSONObject("order")
    if (order != null && (order.has("status") || order.has("msg"))) return order
    val data = response.optJSONObject("data")
    if (data != null) {
        val dataOrder = data.optJSONObject("order")
        if (dataOrder != null && (dataOrder.has("status") || dataOrder.has("msg"))) {
            return dataOrder
        }
        if (data.has("status") || data.has("msg")) return data
    }
    return response
}
