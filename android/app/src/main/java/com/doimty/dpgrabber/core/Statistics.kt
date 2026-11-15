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

fun statisticsText(response: JSONObject): String {
    val data = response.optJSONObject("list") ?: response.optJSONObject("data") ?: response
    fun number(key: String): Double? = data.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun rate(numerator: String, denominator: String): String {
        val a = number(numerator)
        val b = number(denominator)
        return if (a != null && b != null && b > 0) String.format(Locale.ROOT, "%.2f%%", a / b * 100) else "—"
    }
    return "本周订单 ${number("orderCount")?.toLong() ?: "—"} 单\n" +
        "续费率（金额比）${rate("continuedAmount", "orderAmount")}\n" +
        "续单率（单数比）${rate("continuedCount", "orderCount")}\n" +
        "续客率：口径待确认；原始续客数 ${number("continuedUserCount") ?: "—"}"
}
