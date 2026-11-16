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

class PcParityCoreTest {
    private fun order() = JSONObject().put("id", "o").put("totalAmount", 2500)
    private fun filter(vararg ids: Int) = OrderFilter(0.0, 0.0, 0, emptyList(), emptyList(), ids.toList())
    private fun reject(block: () -> Unit) {
        try { block(); org.junit.Assert.fail("应拒绝无效值") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `忙碌优先于code0及成功文案`() {
        for (code in listOf(0, 1, 2)) {
            val result = classifyConfirm(JSONObject().put("code", code).put("msg", "确认成功，但同时接单数超过限制"))
            assertEquals(ConfirmOutcome.BUSY, result.outcome)
            assertEquals("同时接单名额已满，本次跳过", result.reason)
        }
    }

    @Test fun `忙碌上限动态解析2和3和10`() {
        for (cap in listOf(2, 3, 10)) {
            val message = "同时接单数超过限制的 $cap 单"
            assertEquals(cap, busyCapFrom(message) ?: -1)
            assertTrue(classifyConfirm(JSONObject().put("code", 1).put("msg", message)).reason.contains("上限${cap}单"))
        }
    }

    @Test fun `忙碌无数字和溢出均使用安全泛化原因`() {
        for (message in listOf("接单数超过限制", "超过限制的999999999999999999999单", "超过限制的0单")) {
            assertEquals(ConfirmOutcome.BUSY, classifyConfirm(JSONObject().put("code", 0).put("msg", message)).outcome)
            assertEquals("同时接单名额已满，本次跳过", busyReason(message))
        }
    }

    @Test fun `忙碌空msg回退message且不回显敏感负载`() {
        val result = classifyConfirm(JSONObject().put("code", 0).put("msg", " ").put("message", "同时接单数超过限制的10单 private-payload"))
        assertEquals(ConfirmOutcome.BUSY, result.outcome)
        assertFalse(result.reason.contains("private-payload"))
        assertTrue(result.reason.contains("10单"))
    }

    @Test fun `新配置旧JSON缺字段兼容`() {
        val cfg = AppConfig.fromJson(JSONObject()).validate()
        assertEquals(60, cfg.busyCooldownSec)
        assertEquals(0.0, cfg.autoWaitOffset, 0.0)
        assertTrue(cfg.itemSwitchIds.isEmpty())
        assertEquals("", cfg.itemSwitchAccountKey)
        assertEquals("", cfg.itemSwitchShopId)
    }

    @Test fun `新配置持久化去重且不存手机号和凭据`() {
        val cfg = AppConfig(busyCooldownSec = 123, autoWaitOffset = -0.125,
            itemSwitchIds = listOf(465, 2666, 465), itemSwitchAccountKey = accountKeyForPhone("10000000000"), itemSwitchShopId = "92").validate()
        assertEquals(listOf(465, 2666), cfg.itemSwitchIds)
        assertEquals(cfg, AppConfig.fromJson(cfg.toJson()).validate())
        assertFalse(cfg.toJson().toString().contains("10000000000"))
    }

    @Test fun `忙碌冷却仅接受1至600整数`() {
        for (bad in listOf(0, -1, 601)) reject { AppConfig(busyCooldownSec = bad).validate() }
        for (bad in listOf(true, 2.5, "2.5", Long.MAX_VALUE)) reject { AppConfig.fromJson(JSONObject().put("busy_cooldown_sec", bad)).validate() }
        assertEquals(1, AppConfig(busyCooldownSec = 1).validate().busyCooldownSec)
        assertEquals(600, AppConfig(busyCooldownSec = 600).validate().busyCooldownSec)
    }

    @Test fun `偏移有限范围最多三小数`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.501, 0.501, 0.0001)) reject { AppConfig(autoWaitOffset = bad).validate() }
        for (good in listOf(-0.5, 0.5, -0.001, 0.125, 0.0)) assertEquals(good, AppConfig(autoWaitOffset = good).validate().autoWaitOffset, 0.0)
    }

    @Test fun `配置类型列表必须正整数且作用域不能是手机号`() {
        reject { AppConfig(itemSwitchIds = listOf(0)).validate() }
        reject { AppConfig(itemSwitchAccountKey = "10000000000").validate() }
        for (bad in listOf(true, 465.1, 465.0, "NaN", -1, Long.MAX_VALUE)) {
            reject { AppConfig.fromJson(JSONObject().put("item_switch_ids", org.json.JSONArray().put(bad))).validate() }
        }
    }

    @Test fun `已知店铺表等待叠加偏移并减年龄`() {
        val now = 1700000000000L
        for (sid in listOf("50", "105", "1391")) {
            val cfg = AppConfig(autoWaitOffset = 0.125)
            assertEquals(1.125, confirmWaitSeconds(order().put("createTime", now - 2000), sid, cfg, now), 0.000001)
        }
    }

    @Test fun `92和其他店铺不受偏移影响`() {
        val cfg = AppConfig(autoWaitOffset = -0.5, globalWaitSec = 1.0)
        assertEquals(5.0, confirmWaitSeconds(order(), "92", cfg, 1700000000000L), 0.0)
        assertEquals(1.0, confirmWaitSeconds(order(), "999", cfg, 1700000000000L), 0.0)
    }

    @Test fun `偏移全局fallback负目标夹零且年龄超出夹零`() {
        val now = 1700000000000L
        val cfg = AppConfig(confirmWaitByShop = emptyMap(), globalWaitSec = 0.2, autoWaitOffset = -0.5)
        assertEquals(0.0, confirmWaitSeconds(order(), "105", cfg, now), 0.0)
        assertEquals(0.7, confirmWaitSeconds(order(), "50", cfg.copy(autoWaitOffset = 0.5), now), 0.000001)
        assertEquals(0.0, confirmWaitSeconds(order().put("createTime", now - 2000), "1391", cfg.copy(autoWaitOffset = 0.5), now), 0.0)
    }

    @Test fun `类型ID全部候选支持且ID优先于名称`() {
        for (key in listOf("itemId", "itemSwitchId", "itemCategoryId", "typeId", "itemType", "serviceItemId", "categoryId")) {
            val value = order().put(key, "2666").put("itemName", "语音")
            assertFalse(filter(465).check(value).first)
            assertTrue(filter(2666).check(value).first)
        }
    }

    @Test fun `无效ID继续候选且不接受布尔小数和NaN`() {
        for (bad in listOf(true, 465.0, 465.5, "465.0", "NaN", Long.MAX_VALUE)) {
            val value = order().put("itemId", bad).put("typeId", 2666)
            assertEquals(setOf(2666), orderItemIds(value))
            assertFalse(filter(465).check(value).first)
        }
    }

    @Test fun `类型名称各候选支持字符串数组和字典`() {
        for (key in listOf("itemNames", "item_names", "itemName", "item_name", "items", "serviceNames", "serviceName", "itemCategoryName")) {
            for (value in listOf("视频", org.json.JSONArray().put("视频"), JSONObject().put("name", "视频"), JSONObject().put("itemName", "视频"))) {
                assertEquals(setOf(2666), orderItemIds(order().put(key, value)))
                assertFalse(filter(465).check(order().put(key, value)).first)
            }
        }
    }

    @Test fun `语音同义词和游戏已知映射且文字语音条只体现映射边界`() {
        for (name in listOf("连麦", "语音通话", "语音", "文字语音条")) assertEquals(setOf(465), orderItemIds(order().put("itemName", name)))
        assertEquals(setOf(2667), orderItemIds(order().put("itemName", "游戏")))
        assertTrue(filter(2666).check(order().put("itemName", "语音")).second.contains("已知"))
    }

    @Test fun `多项类型任一交集可抢无交集拒绝`() {
        val ids = order().put("itemId", org.json.JSONArray().put(465).put(2667))
        assertTrue(filter(2667).check(ids).first)
        assertFalse(filter(2666).check(ids).first)
        val names = order().put("items", org.json.JSONArray().put(JSONObject().put("name", "语音")).put("视频"))
        assertTrue(filter(2666).check(names).first)
        assertFalse(filter(2667).check(names).first)
    }

    @Test fun `未知缺类型体验时长不猜且空启用列表兼容不限`() {
        for (value in listOf(order(), order().put("itemName", "30分钟体验"), order().put("itemId", true))) assertTrue(filter(2666).check(value).first)
        assertTrue(filter().check(order().put("itemId", 465)).first)
    }

    @Test fun `类型缓存必须同时匹配账号摘要与店铺`() {
        val key = accountKeyForPhone("10000000000")
        val cfg = AppConfig(itemSwitchIds = listOf(2666), itemSwitchAccountKey = key, itemSwitchShopId = "92")
        val value = order().put("itemId", 465)
        assertFalse(orderFilterFromConfig(cfg, ItemSwitchScope(key, "92")).check(value).first)
        assertTrue(orderFilterFromConfig(cfg, ItemSwitchScope(accountKeyForPhone("10000000001"), "92")).check(value).first)
        assertTrue(orderFilterFromConfig(cfg, ItemSwitchScope(key, "105")).check(value).first)
        assertTrue(orderFilterFromConfig(cfg).check(value).first)
        assertTrue(orderFilterFromConfig(cfg.copy(itemSwitchAccountKey = ""), ItemSwitchScope(key, "92")).check(value).first)
    }

    @Test fun `账号摘要使用trim与HistoryStore同算法`() {
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest("10000000000".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, accountKeyForPhone(" 10000000000 "))
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
