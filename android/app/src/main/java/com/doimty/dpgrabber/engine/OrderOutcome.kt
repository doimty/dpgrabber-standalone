package com.doimty.dpgrabber.engine

import org.json.JSONObject

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
