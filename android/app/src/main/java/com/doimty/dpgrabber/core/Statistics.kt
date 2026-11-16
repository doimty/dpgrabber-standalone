package com.doimty.dpgrabber.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class WeekRange(val monday: String, val today: String)

fun platformWeekRange(nowMs: Long): WeekRange {
    val zone = TimeZone.getTimeZone("Asia/Shanghai")
    val calendar = Calendar.getInstance(zone, Locale.ROOT).apply { timeInMillis = nowMs }
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
    val today = format.format(calendar.time)
    val daysFromMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
    return WeekRange(format.format(calendar.time), today)
}

/** 本周三项指标的结构化取值，供「我的」页身份卡直接使用。 */
data class WeekMetrics(val orders: Long?, val feeRate: Double?, val orderRate: Double?)

/** 统计接口的负载可能挂在 list、data 或顶层，取值顺序与 [statisticsText] 一致。 */
private fun statisticsPayload(response: JSONObject): JSONObject =
    response.optJSONObject("list") ?: response.optJSONObject("data") ?: response

/** 非有限值或负值一律视为缺失，避免把 -1/NaN 当成真实指标展示。 */
private fun metricNumber(payload: JSONObject, key: String): Double? =
    payload.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

private fun metricRatio(payload: JSONObject, numerator: String, denominator: String): Double? {
    val a = metricNumber(payload, numerator)
    val b = metricNumber(payload, denominator)
    return if (a != null && b != null && b > 0) a / b else null
}

fun weekMetrics(response: JSONObject): WeekMetrics {
    val payload = statisticsPayload(response)
    return WeekMetrics(
        orders = metricNumber(payload, "orderCount")?.toLong(),
        feeRate = metricRatio(payload, "continuedAmount", "orderAmount"),
        orderRate = metricRatio(payload, "continuedCount", "orderCount")
    )
}

fun statisticsText(response: JSONObject): String {
    val data = statisticsPayload(response)
    fun number(key: String): Double? = metricNumber(data, key)
    val metrics = weekMetrics(response)
    fun rate(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "—"
    return "本周订单 ${number("orderCount")?.toLong() ?: "—"} 单\n" +
        "续费率（金额比）${rate(metrics.feeRate)}\n" +
        "续单率（单数比）${rate(metrics.orderRate)}\n" +
        "续客率：口径待确认；原始续客数 ${number("continuedUserCount") ?: "—"}"
}
