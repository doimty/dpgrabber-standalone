package com.doimty.dpgrabber.core

import org.json.JSONObject

/**
 * Precise grab time (PROTOCOL.md section 8.1, PC tool `get_new_order_title`).
 *
 * grab_time = reTime + confirmCountDown; wait = grab_time - now.
 * `createTime` is only informational and is not used in the math.
 */
data class GrabWait(val waitSeconds: Double, val grabEpochMs: Long?, val reason: Reason) {
    enum class Reason {
        /** Fields present; wait is the time to wait. */
        OK,
        /** reTime / confirmCountDown missing; grab immediately. */
        MISSING_FIELDS,
        /** Grab time already passed; grab immediately. */
        ALREADY_OPEN
    }
}

fun computeGrabWait(order: JSONObject?, nowEpochSec: Double): GrabWait {
    if (order == null) return GrabWait(0.0, null, GrabWait.Reason.MISSING_FIELDS)
    val reMs = normalizeMs(order.opt("reTime"))
    val countdown = when (val raw = order.opt("confirmCountDown")) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    } ?: return GrabWait(0.0, null, GrabWait.Reason.MISSING_FIELDS)
    if (reMs <= 0) return GrabWait(0.0, null, GrabWait.Reason.MISSING_FIELDS)
    val grabMs = reMs + (countdown * 1000).toLong()
    val wait = grabMs / 1000.0 - nowEpochSec
    if (wait <= 0) return GrabWait(0.0, grabMs, GrabWait.Reason.ALREADY_OPEN)
    return GrabWait(wait, grabMs, GrabWait.Reason.OK)
}
