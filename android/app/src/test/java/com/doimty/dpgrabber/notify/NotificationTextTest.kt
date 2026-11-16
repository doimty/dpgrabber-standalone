package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.engine.HistoryFormatter
import com.doimty.dpgrabber.engine.OrderOutcome
import com.doimty.dpgrabber.engine.OutcomeType
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private fun outcome(
        type: OutcomeType,
        orderId: String = "order-12345678",
        amount: Any? = 2500,
        extra: JSONObject.() -> Unit = {}
    ): OrderOutcome {
        val order = JSONObject().apply {
            if (amount != null) put("totalAmount", amount)
            extra()
        }
        return OrderOutcome("key", "92", orderId, "run", "attempt", 1_700_000_000_000L, type, "", order)
    }

    @Test
    fun `five outcome types use readable label in title and body`() {
        for (type in OutcomeType.values()) {
            val o = outcome(type)
            val label = HistoryFormatter.outcomeLabel(type)
            assertTrue(NotificationText.title(o).contains(label))
            assertTrue(NotificationText.body(o).contains("结果：$label"))
        }
    }

    @Test
    fun `body contains amount masked order number and beijing time`() {
        val o = outcome(OutcomeType.SUCCESS, orderId = "order-12345678", amount = 2500)
        val body = NotificationText.body(o)
        assertTrue(body.contains("金额：¥25.00"))
        assertTrue(body.contains("订单号：***5678"))
        assertTrue(body.contains("时间（北京时间）："))
        assertFalse(body.contains("时间未提供"))
    }

    @Test
    fun `missing amount falls back to placeholder`() {
        val body = NotificationText.body(outcome(OutcomeType.FAILED, amount = null))
        assertTrue(body.contains("金额：金额未提供"))
    }

    @Test
    fun `long order id is masked never emitted in full`() {
        val full = "123456789012345678901234567890"
        val body = NotificationText.body(outcome(OutcomeType.SUCCESS, orderId = full))
        assertTrue(body.contains("***7890"))
        assertFalse(body.contains(full))
    }

    @Test
    fun `body never leaks wechat memo full order number phone or token`() {
        val secrets = listOf("wx-secret-client", "备注里的私密信息", "13800138000", "bearer-token-xyz", "PLATFORM-FULL-NO-9999")
        val o = outcome(OutcomeType.SUCCESS, orderId = "abcdefgh", amount = 4200) {
            put("userWechatId", "wx-secret-client")
            put("userMemo", "备注里的私密信息")
            put("no", "PLATFORM-FULL-NO-9999")
            put("phone", "13800138000")
            put("token", "bearer-token-xyz")
        }
        val text = NotificationText.title(o) + "\n" + NotificationText.body(o)
        secrets.forEach { assertFalse("不应出现 $it", text.contains(it)) }
        assertTrue(text.contains("订单号：***efgh"))
    }

    @Test
    fun `short order id still uses masked prefix format`() {
        val body = NotificationText.body(outcome(OutcomeType.RISK, orderId = "ab"))
        assertTrue(body.contains("订单号：***ab"))
    }
}
