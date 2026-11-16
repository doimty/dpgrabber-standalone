package com.doimty.dpgrabber.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortRegressionTest {
    @Test fun `设置序列化不包含任何登录凭据`() {
        val json = AppConfig(phone = "10000000000", password = "sensitive").toJson()
        assertFalse(json.has("phone"))
        assertFalse(json.has("password"))
        assertFalse(json.toString().contains("sensitive"))
    }

    @Test fun `密码按需MD5且不二次摘要`() {
        val transport = FakeTransport().apply {
            script("/login", 200, """{"code":0,"data":{"token":"t","shopId":"105"}}""")
        }
        val client = TestKit.client(transport)
        assertEquals("105", client.loginAccount("10000000000", "secret").sid)
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", JSONObject(transport.lastCall().body!!.toString(Charsets.UTF_8)).getString("password"))
        client.loginAccount("10000000000", "5EBE2294ECD0E0F08EAB7690D2A6EE69")
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", JSONObject(transport.lastCall().body!!.toString(Charsets.UTF_8)).getString("password"))
    }

    @Test fun `登录店铺sid用于店铺列表与切换且探测店员`() {
        val transport = FakeTransport().apply {
            script("/login", 200, """{"code":0,"data":{"token":"t","shopId":105}}""")
            script("/list-shop-account", 200, """{"code":0,"data":[{"shopId":92,"accountId":"a"}]}""")
            script("/switch-account", 200, """{"code":0,"data":{"token":"s"}}""")
            script("/clerk/get", 200, """{"code":0,"clerk":{"id":"7"}}""")
        }
        val client = TestKit.client(transport)
        assertEquals("92", client.sessionForAccount("10000000000", "secret").sid)
        transport.calls.filter { it.url.contains("list-shop-account") || it.url.contains("switch-account") }.forEach { assertEquals("105", it.headers["sid"]) }
        assertTrue(transport.calls.any { it.url.contains("/clerk/get") })
        assertTrue(transport.calls.none { it.url.contains("getBalance") })
    }

    @Test fun `特定店员权限拒绝才跳过候选任意401必须中止`() {
        for ((message, expected) in listOf("没有操作权限" to ApiError.Kind.PERMISSION, "login state invalid" to ApiError.Kind.AUTH)) {
            val client = TestKit.client(FakeTransport())
            try {
                client.decodeResponse(JSONObject().put("code", 401).put("msg", message).toString().toByteArray())
                fail("应拒绝")
            } catch (error: ApiError) { assertEquals(expected, error.kind) }
        }
    }

    @Test fun `六操作编码使用form日期和pageNo`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        client.updateStatus(1, false)
        assertEquals("status=1&updateAutoOnline=false", transport.lastCall().body!!.toString(Charsets.UTF_8))
        client.updateRandomOrderSwitch(true)
        assertEquals("randomOrderSwitch=true", transport.lastCall().body!!.toString(Charsets.UTF_8))
        client.getItemSwitchList()
        assertEquals("POST", transport.lastCall().method)
        assertEquals("application/x-www-form-urlencoded", transport.lastCall().contentType)
        assertEquals(0, transport.lastCall().body!!.size)
        client.updateItemSwitch(465, listOf(465, 2666))
        assertEquals("switchId=465&switchIds=%5B465%2C2666%5D", transport.lastCall().body!!.toString(Charsets.UTF_8))
        client.statisticsClerkOrder("2026-11-09", "2026-11-15")
        assertEquals("GET", transport.lastCall().method)
        assertTrue(transport.lastCall().url.endsWith("startTime=2026-11-09&endTime=2026-11-15"))
        client.orderList("2026-11-15 00:00:00", "2026-11-15 23:59:59", pageNo = 2)
        assertTrue(transport.lastCall().url.contains("pageNo=2"))
        assertFalse(transport.lastCall().url.contains("pageNum"))
        assertTrue(transport.lastCall().url.contains("2026-11-15%2000%3A00%3A00"))
    }

    @Test fun `非成功业务码不能伪造成功或空数组`() {
        val transport = FakeTransport().apply { defaultResponse = FakeTransport.Scripted(200, """{"code":7}""".toByteArray()) }
        try { TestKit.client(transport).updateStatus(1); fail("应失败") }
        catch (error: ApiError) { assertEquals(ApiError.Kind.API, error.kind) }
    }

    @Test fun `店铺等待减年龄兼容秒毫秒未来时间钳制`() {
        val now = 1700000000000L
        val config = AppConfig()
        assertEquals(3.0, confirmWaitSeconds(JSONObject().put("createTime", now - 2000), "92", config, now), 0.001)
        assertEquals(3.0, confirmWaitSeconds(JSONObject().put("createTime", (now - 2000) / 1000), "92", config, now), 0.001)
        assertEquals(0.0, confirmWaitSeconds(JSONObject().put("createTime", now - 9000), "105", config, now), 0.001)
        assertEquals(5.0, confirmWaitSeconds(JSONObject().put("createTime", now + 2000), "92", config, now), 0.001)
        assertEquals(3.0, confirmWaitSeconds(JSONObject(), "1391", config, now), 0.001)
    }

    @Test fun `缺失或非法状态不伪造pending`() {
        assertEquals(ReconcileVerdict.UNKNOWN, reconcileOrder(JSONObject(), 7).verdict)
        assertEquals(ReconcileVerdict.UNKNOWN, reconcileOrder(JSONObject().put("status", "bad"), 7).verdict)
        assertEquals(ConfirmOutcome.FAIL, classifyConfirm(JSONObject().put("code", 1).put("msg", "资质不足")).outcome)
    }

    @Test fun `迁移来源必须提供完整凭据对`() {
        assertNull(com.doimty.dpgrabber.data.CredentialStore.completePair("10000000000", null))
        assertNull(com.doimty.dpgrabber.data.CredentialStore.completePair(null, "secret"))
        assertNull(com.doimty.dpgrabber.data.CredentialStore.completePair("", "secret"))
        assertEquals("10000000000" to "secret", com.doimty.dpgrabber.data.CredentialStore.completePair("10000000000", "secret"))
    }

    @Test fun `空白密码拒绝且摘要前修剪`() {
        val transport = FakeTransport().apply { script("/login", 200, """{"code":0,"data":{"token":"t","shopId":92}}""") }
        val client = TestKit.client(transport)
        client.loginAccount(" 10000000000 ", " secret ")
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", JSONObject(transport.lastCall().body!!.toString(Charsets.UTF_8)).getString("password"))
        val before = transport.calls.size
        try { client.loginAccount("10000000000", " \t "); fail("空白密码须拒绝") }
        catch (error: ApiError) { assertEquals(ApiError.Kind.API, error.kind) }
        assertEquals(before, transport.calls.size)
    }

    @Test fun `缺失店铺身份不硬编码50`() {
        val transport = FakeTransport().apply { script("/login", 200, """{"code":0,"data":{"token":"t"}}""") }
        try { TestKit.client(transport).loginAccount("10000000000", "secret"); fail("应拒绝缺失身份") }
        catch (error: ApiError) { assertEquals(ApiError.Kind.AUTH, error.kind) }
    }

    @Test fun `候选店员401继续但基础切换401中止`() {
        for (failSwitch in listOf(false, true)) {
            val transport = FakeTransport().apply {
                script("/login", 200, """{"code":0,"data":{"token":"t","shopId":105}}""")
                script("/list-shop-account", 200, """{"code":0,"data":[{"shopId":92,"accountId":"a"},{"shopId":105,"accountId":"b"}]}""")
                scriptDynamic("/switch-account") { call ->
                    val first = call.body!!.toString(Charsets.UTF_8).contains("accountId=a")
                    FakeTransport.Scripted(if (first && failSwitch) 401 else 200,
                        (if (first && failSwitch) """{"code":401}""" else """{"code":0,"data":{"token":"${if (first) "a" else "b"}"}}""").toByteArray())
                }
                scriptDynamic("/clerk/get") { call ->
                    if (call.headers["sid"] == "92") FakeTransport.Scripted(401, """{"code":401}""".toByteArray())
                    else FakeTransport.Scripted(200, """{"code":0,"clerk":{"id":8}}""".toByteArray())
                }
            }
            if (failSwitch) {
                try { TestKit.client(transport).sessionForAccount("10000000000", "secret"); fail("切换401须中止") }
                catch (error: ApiError) { assertEquals(ApiError.Kind.AUTH, error.kind) }
                assertEquals(1, transport.calls.count { it.url.contains("switch-account") })
            } else assertEquals("105", TestKit.client(transport).sessionForAccount("10000000000", "secret").sid)
        }
    }

    @Test fun `周范围按上海周一且统计读取顶层list金额单数分别计算`() {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
        val sunday = platformWeekRange(format.parse("2026-11-15 00:01:00")!!.time)
        assertEquals(WeekRange("2026-11-09", "2026-11-15"), sunday)
        assertEquals(WeekRange("2026-11-16", "2026-11-16"), platformWeekRange(format.parse("2026-11-16 00:01:00")!!.time))
        val text = statisticsText(JSONObject("""{"list":{"orderCount":"10","continuedCount":2,"continuedAmount":400,"orderAmount":1000,"continuedUserCount":1}}"""))
        assertTrue(text.contains("40.00%"))
        assertTrue(text.contains("20.00%"))
        assertTrue(text.contains("口径待确认"))
        assertTrue(statisticsText(JSONObject()).contains("—"))
    }

    @Test fun `模糊成功否定成功与未知归属必须保守核验`() {
        assertEquals(ConfirmOutcome.VERIFY, classifyConfirm(JSONObject().put("code", 2).put("msg", "确认成功")).outcome)
        assertEquals(ConfirmOutcome.RETRY, classifyConfirm(JSONObject().put("code", 2).put("msg", "未确认成功")).outcome)
        assertEquals(ConfirmOutcome.FAIL, classifyConfirm(JSONObject().put("code", 0).put("msg", "已被其他人确认成功")).outcome)
        for (payload in listOf(JSONObject().put("status", 2), JSONObject().put("status", true).put("clerkId", 7), JSONObject().put("status", 2.5).put("clerkId", 7))) {
            assertEquals(ReconcileVerdict.UNKNOWN, reconcileOrder(payload, 7).verdict)
        }
        assertNull(ApiClient.parseCode(JSONObject().put("code", Long.MAX_VALUE)))
        assertNull(ApiClient.parseCode(JSONObject().put("code", "Infinity")))
    }

    @Test fun `未列店铺用全局等待且零值覆盖保留`() {
        val cfg = AppConfig(globalWaitSec = 2.5, confirmWaitByShop = mapOf("92" to 0.0))
        val decoded = AppConfig.fromJson(cfg.toJson()).validate()
        assertEquals(2.5, confirmWaitSeconds(JSONObject(), "999", decoded, 1700000000000L), 0.001)
        assertEquals(0.0, confirmWaitSeconds(JSONObject(), "92", decoded, 1700000000000L), 0.001)
        assertEquals(ConfirmOutcome.FAIL, classifyConfirm(JSONObject().put("code", 1).put("msg", "当前续费率24%需要达标30%")).outcome)
        assertEquals("被其他人抢走", terminalReason("订单跑走了"))
        try { AppConfig(globalWaitSec = Double.NaN).validate(); fail("必须拒绝非数值") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `取消客户端不能恢复也不再出网`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        client.cancel()
        try { client.request("GET", "/s/c/clerk/get"); fail("应取消") }
        catch (error: ApiError) { assertEquals(ApiError.Kind.CANCELLED, error.kind) }
        assertTrue(transport.calls.isEmpty())
    }
}
