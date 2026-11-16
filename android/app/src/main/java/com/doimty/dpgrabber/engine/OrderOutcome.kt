package com.doimty.dpgrabber.engine

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class OutcomeType { SUCCESS, FAILED, RISK, UNKNOWN, SKIPPED }

data class OrderOutcome(
    val accountKey: String,
    val shopId: String,
    val orderId: String,
    val runId: String,
    val attemptId: String,
    val timestampMs: Long,
    val type: OutcomeType,
    val reason: String,
    val order: JSONObject
) {
    val key: String get() = "$accountKey/$shopId/$orderId/$runId/$attemptId"
}

/** 纯历史展示格式化，避免把 JSON 数字转为 Double 后丢失长订单号精度。 */
object HistoryFormatter {
    private val beijing = TimeZone.getTimeZone("Asia/Shanghai")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = beijing }

    fun outcomeLabel(type: OutcomeType): String = when (type) {
        OutcomeType.SUCCESS -> "成功"
        OutcomeType.FAILED -> "失败"
        OutcomeType.RISK -> "风控"
        OutcomeType.UNKNOWN -> "未知"
        OutcomeType.SKIPPED -> "跳过"
    }

    fun timestamp(ms: Long): String = if (ms <= 0L) "时间未提供" else synchronized(dateFormat) { dateFormat.format(Date(ms)) }

    fun text(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        if (value !is String && value !is Byte && value !is Short && value !is Int && value !is Long && value !is java.math.BigInteger) return ""
        val text = value.toString()
        return if (text.trim().lowercase(Locale.ROOT) in setOf("", "null", "nan", "infinity", "+infinity", "-infinity")) "" else text
    }

    fun orderNumber(order: JSONObject, fallback: String): String =
        text(order.opt("no")).ifBlank { text(fallback) }.ifBlank { "订单号未提供" }

    fun wechatValue(order: JSONObject): String = text(order.opt("userWechatId")).trim()
    fun wechat(order: JSONObject): String = wechatValue(order).ifBlank { "未提供微信号" }
    fun memoValue(order: JSONObject): String = text(order.opt("userMemo") as? String)
    fun memo(order: JSONObject): String = memoValue(order).ifBlank { "无备注" }

    fun details(order: JSONObject, orderId: String, shop: String, timeMs: Long, outcome: OutcomeType, reason: String): String =
        listOfNotNull(
            "处理结果：${outcomeLabel(outcome)}",
            "金额：${amount(order)}",
            "处理时间（北京时间）：${timestamp(timeMs)}",
            com.doimty.dpgrabber.core.normalizeMs(order.opt("createTime")).takeIf { it > 0L }
                ?.let { "下单时间（北京时间）：${timestamp(it)}" },
            "店铺：${text(shop).ifBlank { "未提供" }}",
            "订单号：${orderNumber(order, orderId)}",
            "订单ID：${text(orderId).ifBlank { "未提供" }}",
            "微信号：${wechat(order)}",
            "备注：${memo(order)}",
            "原因：${text(reason).ifBlank { "未提供" }}"
        ).joinToString("\n")

    fun amount(order: JSONObject): String {
        val raw = order.opt("totalAmount")
        if (raw == null || raw == JSONObject.NULL) return "金额未提供"
        val cents = when (raw) {
            is Number -> raw.toString().toBigDecimalOrNull()
            is String -> raw.trim().toBigDecimalOrNull()
            else -> null
        } ?: return "金额未提供"
        if (cents.signum() < 0 || cents.precision() > 24 || kotlin.math.abs(cents.scale().toLong()) > 12) return "金额未提供"
        return try {
            "¥" + cents.divide(BigDecimal(100), 2, RoundingMode.HALF_UP).toPlainString()
        } catch (_: ArithmeticException) {
            "金额未提供"
        }
    }
}
