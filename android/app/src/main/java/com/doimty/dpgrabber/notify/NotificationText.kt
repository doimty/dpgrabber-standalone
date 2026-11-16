package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.engine.HistoryFormatter
import com.doimty.dpgrabber.engine.OrderOutcome

/**
 * 结果通知文案。纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 *
 * 正文只包含：结果 + 金额 + 脱敏订单号 + 北京时间。
 * 订单号刻意用"***"加末四位，而不是 HistoryFormatter.orderNumber ——
 * 后者会返回平台完整单号/订单 ID，绝不允许出现在通知与 webhook 负载里。
 * 微信号、备注、账号、令牌一律不进入此处。
 */
object NotificationText {
    fun title(o: OrderOutcome): String = "接单结果：" + HistoryFormatter.outcomeLabel(o.type)

    fun body(o: OrderOutcome): String {
        val masked = "***" + o.orderId.takeLast(4)
        return listOf(
            "结果：" + HistoryFormatter.outcomeLabel(o.type),
            "金额：" + HistoryFormatter.amount(o.order),
            "订单号：" + masked,
            "时间（北京时间）：" + HistoryFormatter.timestamp(o.timestampMs)
        ).joinToString("\n")
    }
}
