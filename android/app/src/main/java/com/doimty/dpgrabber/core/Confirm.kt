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
val QUALIFICATION_WORDS = listOf("续费率", "达标", "资质", "资格", "额度", "次数不足", "上限", "本周订单")
val TERMINAL_WORDS = listOf("取消", "已被", "不存在", "无权", "权限", "失效", "超时", "跑走", "跑掉") + QUALIFICATION_WORDS

/** Human-readable reason for a terminal confirm failure (DPGTerminalReason). */
fun terminalReason(message: String?): String {
    val text = message ?: ""
    if (QUALIFICATION_WORDS.any { text.contains(it) }) return "接单资质不满足"
    for (word in listOf("已被", "被其他", "他人抢", "别人抢", "抢走", "跑走", "跑掉")) {
        if (text.contains(word)) return "被其他人抢走"
    }
    if (text.contains("取消")) return "已取消"
    if (text.contains("不存在")) return "订单不存在"
    if (text.contains("无权") || text.contains("权限")) return "无确认权限"
    if (text.contains("失效")) return "订单已失效"
    if (text.contains("超时")) return "确认超时"
    if (listOf("资质", "资格", "不满足", "不符合").any { text.contains(it) }) return "接单资质不满足"
    return "平台拒绝确认"
}

/** Outcome of classifying one confirm response. */
enum class ConfirmOutcome {
    /** code 0 or a confirmed message. */
    OK,
    /** A terminal message; [reason] explains it. */
    FAIL,
    /** Success-like wording with a nonzero code requires readback, not another write. */
    VERIFY,
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
    if (TERMINAL_WORDS.any { message.contains(it) }) {
        return ConfirmClassification(ConfirmOutcome.FAIL, terminalReason(message))
    }
    val negative = listOf("未确认成功", "未接单", "未成功", "没有确认成功", "没有接单成功", "尚未确认", "尚未接单").any { message.contains(it) }
    if (code == 0 && !negative) return ConfirmClassification(ConfirmOutcome.OK, "")
    if (!negative && CONFIRMED_WORDS.any { message.contains(it) }) {
        return ConfirmClassification(ConfirmOutcome.VERIFY, "返回码未确认成功，需回查归属")
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

fun confirmWaitSeconds(order: JSONObject, sid: String, config: AppConfig, nowMs: Long): Double {
    val wait = config.confirmWaitByShop[sid] ?: config.globalWaitSec
    val created = normalizeMs(order.opt("createTime"))
    val age = if (created > 0) maxOf(0.0, (nowMs - created) / 1000.0) else 0.0
    return maxOf(0.0, wait - age)
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
    val status = payload.opt("status")?.let { ApiClient.parseCode(JSONObject().put("code", it)) }
    if (TERMINAL_WORDS.any { message.contains(it) } || status == -1) return ReconcileResult(ReconcileVerdict.FAIL, terminalReason(message))
    val negative = listOf("未确认成功", "未接单", "未成功", "没有确认成功", "尚未确认", "尚未接单").any { message.contains(it) }
    if (negative) return ReconcileResult(ReconcileVerdict.UNKNOWN, "订单状态与文案未能确认成功")
    val confirmed = CONFIRMED_WORDS.any { message.contains(it) } || status == 2 || status == 3
    if (confirmed) {
        val clerkId = payload.opt("clerkId")?.let { ApiClient.parseCode(JSONObject().put("code", it)) } ?: 0
        if (ownClerkId <= 0 || clerkId <= 0) return ReconcileResult(ReconcileVerdict.UNKNOWN, "订单已接单，但无法核实归属")
        if (clerkId != ownClerkId) {
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
