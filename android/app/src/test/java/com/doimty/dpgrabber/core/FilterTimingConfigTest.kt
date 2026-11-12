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

class GrabTimingTest {
    private fun order(reTime: Any?, countdown: Any?): JSONObject {
        val o = JSONObject()
        if (reTime != null) o.put("reTime", reTime)
        if (countdown != null) o.put("confirmCountDown", countdown)
        return o
    }

    @Test
    fun `grab time is reTime plus countdown`() {
        // reTime = 1_700_000_000_000 ms, countdown 5 s, now = reTime/1000 - 3 s
        val grab = computeGrabWait(order(1_700_000_000_000L, 5), 1_700_000_000.0 - 3.0)
        assertEquals(8.0, grab.waitSeconds, 0.001)
        assertEquals(1_700_000_005_000L, grab.grabEpochMs)
        assertEquals(GrabWait.Reason.OK, grab.reason)
    }

    @Test
    fun `already open when grab time passed`() {
        val grab = computeGrabWait(order(1_700_000_000_000L, 5), 1_700_000_010.0)
        assertEquals(0.0, grab.waitSeconds, 0.001)
        assertEquals(GrabWait.Reason.ALREADY_OPEN, grab.reason)
    }

    @Test
    fun `missing reTime grabs immediately`() {
        val grab = computeGrabWait(order(null, 5), 1_700_000_000.0)
        assertEquals(0.0, grab.waitSeconds, 0.001)
        assertEquals(GrabWait.Reason.MISSING_FIELDS, grab.reason)
    }

    @Test
    fun `missing countdown grabs immediately`() {
        val grab = computeGrabWait(order(1_700_000_000_000L, null), 1_700_000_000.0)
        assertEquals(GrabWait.Reason.MISSING_FIELDS, grab.reason)
    }

    @Test
    fun `seconds timestamps are normalized to ms`() {
        // reTime given in seconds (1.7e9) -> normalized to ms.
        val grab = computeGrabWait(order(1_700_000_000, 5), 1_700_000_000.0 - 3.0)
        assertEquals(8.0, grab.waitSeconds, 0.001)
    }
}

class ConfigTest {
    @Test
    fun `defaults match the PC config`() {
        val cfg = AppConfig.defaults()
        assertEquals(92, cfg.shopId)
        assertEquals(10.0, cfg.minAmount, 0.001)
        assertEquals(0.0, cfg.maxAmount, 0.001)
        assertEquals(0, cfg.userDelaySec)
        assertEquals(0.5, cfg.pollIntervalSec, 0.001)
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
            phone = "13800138000", shopId = 55, minAmount = 5.0,
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
