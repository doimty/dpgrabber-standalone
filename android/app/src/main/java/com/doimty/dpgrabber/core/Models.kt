package com.doimty.dpgrabber.core

/**
 * Pure-Kotlin core models (no Android framework dependency).
 * Mirrors the PC implementation (dpapi.py) and PROTOCOL.md.
 */

/** One failure of an API call. */
class ApiError(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** 401/403 — login state invalid. */
        AUTH,
        /** 明确的候选店铺店员权限拒绝，不代表账号会话失效。 */
        PERMISSION,
        /** Business code 1 with "IP" in msg — IP control. */
        IP,
        /** Other business error or malformed response. */
        API,
        /** Transport failure / HTTP >= 400 (other than 401/403). */
        NETWORK,
        /** Caller asked to stop. */
        CANCELLED
    }

    val messageText: String = message
}

/** A logged-in session: token + shop id. Memory only, never persisted. */
data class Session(val token: String, val sid: String = "50")

/** Order amount in cents -> yuan (DPGAmountFromCents). */
fun amountYuanFromCents(value: Any?): Double {
    return when (value) {
        is Number -> value.toDouble() / 100.0
        is String -> value.toDoubleOrNull()?.div(100.0) ?: 0.0
        else -> 0.0
    }
}

/**
 * Normalize a timestamp to integer milliseconds (DPGCompat.m
 * DPGNormalizeMilliseconds): <= 0 -> 0; < 1e12 is seconds -> *1000;
 * otherwise already milliseconds.
 */
fun normalizeMs(value: Any?): Long {
    val num = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: return 0L
        else -> return 0L
    }
    if (num <= 0.0) return 0L
    if (num < 1e12) return (num * 1000).toLong()
    return num.toLong()
}

/** Order id as a string (numbers are accepted and stringified). */
fun orderIdOf(order: Any?): String {
    if (order !is Map<*, *>) return ""
    val value = order["id"] ?: return ""
    return value.toString()
}
