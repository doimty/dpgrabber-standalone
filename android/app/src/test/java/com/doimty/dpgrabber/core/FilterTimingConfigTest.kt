package com.doimty.dpgrabber.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderFilterTest {
    private fun order(
        id: String = "o1",
        totalAmount: Int = 2000,
        wechat: String = "wx123",
        memo: String = ""
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("totalAmount", totalAmount)
        put("userWechatId", wechat)
        put("userMemo", memo)
    }

    private fun filter(
        min: Double = 10.0,
        max: Double = 0.0,
        memoMode: Int = 0,
        keywords: List<String> = emptyList(),
        blacklist: List<String> = emptyList()
    ) = OrderFilter(min, max, memoMode, keywords, blacklist)

    @Test
    fun `passing order`() {
        val (passed, _) = filter().check(order())
        assertTrue(passed)
    }

    @Test
    fun `missing amount rejected`() {
        val o = JSONObject().apply { put("id", "o1") }
        val (passed, reason) = filter().check(o)
        assertFalse(passed)
        assertTrue(reason.contains("金额"))
    }

    @Test
    fun `missing id rejected`() {
        val o = JSONObject().apply { put("totalAmount", 2000) }
        val (passed, _) = filter().check(o)
        assertFalse(passed)
    }

    @Test
    fun `blacklisted wechat rejected`() {
        val (passed, reason) = filter(blacklist = listOf("wx123")).check(order())
        assertFalse(passed)
        assertTrue(reason.contains("wx123"))
    }

    @Test
    fun `other wechat passes blacklist`() {
        val (passed, _) = filter(blacklist = listOf("wx999")).check(order())
        assertTrue(passed)
    }

    @Test
    fun `below min amount rejected`() {
        val (passed, _) = filter(min = 30.0).check(order(totalAmount = 2000))
        assertFalse(passed)
    }

    @Test
    fun `above max amount rejected, zero max unlimited`() {
        val (passed, _) = filter(max = 15.0).check(order(totalAmount = 2000))
        assertFalse(passed)
        val (passed2, _) = filter(max = 0.0).check(order(totalAmount = 999999))
        assertTrue(passed2)
    }

    @Test
    fun `memo mode 1 drops orders with any memo`() {
        val (passed, _) = filter(memoMode = 1).check(order(memo = "hello"))
        assertFalse(passed)
        val (passed2, _) = filter(memoMode = 1).check(order(memo = ""))
        assertTrue(passed2)
    }

    @Test
    fun `keyword match is case-insensitive substring`() {
        val (passed, reason) = filter(keywords = listOf("VIP")).check(order(memo = "this is a vip order"))
        assertFalse(passed)
        assertTrue(reason.contains("VIP"))
        val (passed2, _) = filter(keywords = listOf("VIP")).check(order(memo = "regular order"))
        assertTrue(passed2)
    }

    @Test
    fun `keyword empty entries ignored`() {
        val (passed, _) = filter(keywords = listOf("  ", "")).check(order(memo = "x"))
        assertTrue(passed)
    }
}

class ConfirmLogicTest {
    private fun order(createTime: Any?): JSONObject {
        val o = JSONObject()
        if (createTime != null) o.put("createTime", createTime)
        return o
    }

    @Test
    fun `budget uses window with createTime`() {
        assertEquals(30.0, confirmBudgetSeconds(order(1_700_000_000_000L), 30.0, 20.0), 0.001)
        // createTime in seconds still counts as present.
        assertEquals(30.0, confirmBudgetSeconds(order(1_700_000_000), 30.0, 20.0), 0.001)
    }

    @Test
    fun `budget uses fallback without createTime`() {
        assertEquals(20.0, confirmBudgetSeconds(JSONObject(), 30.0, 20.0), 0.001)
        assertEquals(20.0, confirmBudgetSeconds(order(0), 30.0, 20.0), 0.001)
    }

    @Test
    fun `budget invalid value falls back to 20`() {
        assertEquals(20.0, confirmBudgetSeconds(order(1), 0.0, 0.0), 0.001)
        assertEquals(20.0, confirmBudgetSeconds(order(1), -5.0, 20.0), 0.001)
    }

    @Test
    fun `classify code zero requires no contradicting terminal message`() {
        assertEquals(ConfirmOutcome.OK, classifyConfirm(TestKit.plain("code" to 0)).outcome)
        assertEquals(ConfirmOutcome.FAIL, classifyConfirm(TestKit.plain("code" to 0, "msg" to "已被抢")).outcome)
        assertEquals(ConfirmOutcome.FAIL, classifyConfirm(TestKit.plain("code" to 1, "msg" to "订单已被其他人确认成功")).outcome)
        assertEquals(ConfirmOutcome.RETRY, classifyConfirm(TestKit.plain("code" to 1, "msg" to "未确认成功")).outcome)
    }

    @Test
    fun `classify confirmed words`() {
        for (msg in listOf("已接单", "已确认", "接单成功", "确认成功")) {
            assertEquals(
                ConfirmOutcome.VERIFY,
                classifyConfirm(TestKit.plain("code" to 2, "msg" to msg)).outcome
            )
        }
    }

    @Test
    fun `classify terminal words and reasons`() {
        val cases = mapOf(
            "已被其他人抢走" to "被其他人抢走",
            "订单已取消" to "已取消",
            "订单不存在" to "订单不存在",
            "无确认权限" to "无确认权限",
            "订单已失效" to "订单已失效",
            "确认超时" to "确认超时"
        )
        for ((msg, reason) in cases) {
            val result = classifyConfirm(TestKit.plain("code" to 2, "msg" to msg))
            assertEquals(ConfirmOutcome.FAIL, result.outcome)
            assertEquals(reason, result.reason)
        }
    }

    @Test
    fun `classify other code retries`() {
        assertEquals(
            ConfirmOutcome.RETRY,
            classifyConfirm(TestKit.plain("code" to 2, "msg" to "请稍后再试")).outcome
        )
        assertEquals(
            ConfirmOutcome.RETRY,
            classifyConfirm(TestKit.plain("code" to 5, "msg" to "")).outcome
        )
    }

    @Test
    fun `terminal reason empty message`() {
        assertEquals("平台拒绝确认", terminalReason(""))
        assertEquals("平台拒绝确认", terminalReason(null))
    }

    @Test
    fun `reconcile status 2 and 3 without ownership remain unknown`() {
        assertEquals(
            ReconcileVerdict.UNKNOWN,
            reconcileOrder(TestKit.plain("status" to 2), 5).verdict
        )
        assertEquals(
            ReconcileVerdict.UNKNOWN,
            reconcileOrder(TestKit.plain("status" to 3), 5).verdict
        )
    }

    @Test
    fun `reconcile clerk mismatch fails`() {
        val result = reconcileOrder(TestKit.plain("status" to 2, "clerkId" to 99), 5)
        assertEquals(ReconcileVerdict.FAIL, result.verdict)
        assertEquals("被其他人抢走", result.reason)
    }

    @Test
    fun `reconcile same clerk is ok`() {
        assertEquals(
            ReconcileVerdict.OK,
            reconcileOrder(TestKit.plain("status" to 2, "clerkId" to 5), 5).verdict
        )
    }

    @Test
    fun `reconcile cannot prove success when own id unknown`() {
        assertEquals(
            ReconcileVerdict.UNKNOWN,
            reconcileOrder(TestKit.plain("status" to 2, "clerkId" to 99), 0).verdict
        )
    }

    @Test
    fun `reconcile terminal and status`() {
        assertEquals(
            ReconcileVerdict.FAIL,
            reconcileOrder(TestKit.plain("status" to -1), 5).verdict
        )
        assertEquals(
            ReconcileVerdict.FAIL,
            reconcileOrder(TestKit.plain("status" to 0, "msg" to "订单已取消"), 5).verdict
        )
    }

    @Test
    fun `reconcile status 0 is pending`() {
        assertEquals(
            ReconcileVerdict.PENDING,
            reconcileOrder(TestKit.plain("status" to 0), 5).verdict
        )
        assertEquals(
            ReconcileVerdict.PENDING,
            reconcileOrder(TestKit.plain("status" to "0"), 5).verdict
        )
    }

    @Test
    fun `reconcile other status is unknown`() {
        assertEquals(
            ReconcileVerdict.UNKNOWN,
            reconcileOrder(TestKit.plain("status" to 9), 5).verdict
        )
    }

    @Test
    fun `reconcile picks payload with status or msg`() {
        val fromOrder = TestKit.plain(
            "status" to 0,
            "order" to JSONObject().apply { put("status", 2); put("clerkId", 5) }
        )
        assertEquals(ReconcileVerdict.OK, reconcileOrder(fromOrder, 5).verdict)
        val fromDataOrder = TestKit.plain(
            "status" to 0,
            "data" to JSONObject().apply {
                put("order", JSONObject().apply { put("status", -1) })
            }
        )
        assertEquals(ReconcileVerdict.FAIL, reconcileOrder(fromDataOrder, 5).verdict)
        val fromData = TestKit.plain(
            "status" to 0,
            "data" to JSONObject().apply { put("status", 3) }
        )
        assertEquals(ReconcileVerdict.UNKNOWN, reconcileOrder(fromData, 5).verdict)
    }
}

class ConfigTest {
    @Test
    fun `defaults match the PC config`() {
        val cfg = AppConfig.defaults()
        assertEquals(92, cfg.shopId)
        assertEquals(10.0, cfg.minAmount, 0.001)
        assertEquals(0.0, cfg.maxAmount, 0.001)
        assertEquals(20.0, cfg.randomConfirmWindowSec, 0.001)
        assertEquals(20.0, cfg.randomConfirmFallbackSec, 0.001)
        assertEquals(0.5, cfg.confirmRetryDelaySec, 0.001)
        assertEquals(0.6, cfg.pollIntervalSec, 0.001)
        assertEquals(0, cfg.memoMode)
        assertEquals(15.0, cfg.requestTimeoutSec, 0.001)
        assertEquals(60.0, cfg.seenTtlSec, 0.001)
        assertTrue(cfg.verifyTls)
        assertEquals(ApiClient.DEFAULT_AES_KEY_HEX, cfg.aesKeyHex)
        assertEquals(ApiClient.ALT_AES_KEY_HEX, cfg.aesKeyHexAlt)
    }

    @Test
    fun `validate accepts good config and trims lists`() {
        val cfg = AppConfig(
            memoKeywords = listOf(" a ", "", "b"),
            blacklist = listOf("wx1", " ")
        ).validate()
        assertEquals(listOf("a", "b"), cfg.memoKeywords)
        assertEquals(listOf("wx1"), cfg.blacklist)
    }

    @Test
    fun `validate rejects max below min`() {
        try {
            AppConfig(minAmount = 20.0, maxAmount = 10.0).validate()
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("max_amount"))
        }
    }

    @Test
    fun `validate rejects bad memo mode`() {
        try {
            AppConfig(memoMode = 2).validate()
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `validate rejects out of range confirm windows`() {
        for (bad in listOf(0.05, 90000.0)) {
            try {
                AppConfig(randomConfirmWindowSec = bad).validate()
                assertTrue("expected failure", false)
            } catch (e: IllegalArgumentException) {
                assertNotNull(e)
            }
            try {
                AppConfig(randomConfirmFallbackSec = bad).validate()
                assertTrue("expected failure", false)
            } catch (e: IllegalArgumentException) {
                assertNotNull(e)
            }
        }
    }

    @Test
    fun `validate rejects out of range retry delay`() {
        for (bad in listOf(-1.0, 61.0)) {
            try {
                AppConfig(confirmRetryDelaySec = bad).validate()
                assertTrue("expected failure", false)
            } catch (e: IllegalArgumentException) {
                assertNotNull(e)
            }
        }
    }

    @Test
    fun `validate rejects bad aes key`() {
        try {
            AppConfig(aesKeyHex = "zz").validate()
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `json round trip`() {
        val cfg = AppConfig(
            shopId = 55, minAmount = 5.0,
            memoKeywords = listOf("vip"), blacklist = listOf("wx9")
        )
        val restored = AppConfig.fromJson(cfg.toJson()).validate()
        assertEquals(cfg, restored)
    }

    @Test
    fun `maskPhone`() {
        assertEquals("138****8000", maskPhone("13800138000"))
        assertEquals("***", maskPhone("123"))
    }
}
