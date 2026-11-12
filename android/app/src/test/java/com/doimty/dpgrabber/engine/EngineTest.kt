package com.doimty.dpgrabber.engine

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.FakeTransport
import com.doimty.dpgrabber.core.TestKit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine tests with a stubbed transport. No real request is ever sent.
 * The fake clock is fixed so the precise-wait math is deterministic.
 */
class EngineTest {

    private val fixedNowMs = 1_700_000_000_000L

    private fun loginScripts(transport: FakeTransport) {
        transport.scriptJson(
            "/s/duopei-account/login",
            TestKit.plain("code" to 0, "data" to JSONObject().apply { put("token", "login-token") })
        )
        val shops = JSONArray().apply {
            put(JSONObject().apply { put("shopId", 92); put("accountId", "a92") })
        }
        transport.scriptJson(
            "/s/duopei-account/list-shop-account",
            TestKit.plain("code" to 0, "data" to shops)
        )
        transport.scriptJson(
            "/s/duopei-account/switch-account",
            TestKit.plain("code" to 0, "data" to JSONObject().apply { put("token", "shop-token") })
        )
        transport.scriptJson("/s/balance/getBalance", TestKit.plain("code" to 0))
        transport.scriptJson(
            "/s/c/clerk/get",
            TestKit.plain("code" to 0, "clerk" to JSONObject().apply {
                put("id", 7); put("shopId", 92); put("name", "测试店员")
            })
        )
    }

    private fun orderJson(
        id: String = "order-1",
        totalAmount: Int = 2500,
        wechat: String = "wx123",
        memo: String = ""
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("totalAmount", totalAmount)
        put("userWechatId", wechat)
        put("userMemo", memo)
    }

    private fun engineFor(
        transport: FakeTransport,
        config: AppConfig = AppConfig(pollIntervalSec = 0.05),
        logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    ): ListenEngine {
        val client = TestKit.client(transport)
        return ListenEngine(
            client = client,
            config = config,
            log = { level, text -> logs.add("[$level] $text") },
            nowEpochMs = { fixedNowMs }
        )
    }

    /** Wait until the engine stops (or the deadline passes). */
    private fun awaitStop(engine: ListenEngine, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return !engine.running
    }

    @Test
    fun `full loop grabs a passing order and stops`() {
        val transport = FakeTransport()
        loginScripts(transport)
        // One passing order; its grab time is already past -> immediate confirm.
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain(
                "code" to 0,
                "order" to JSONObject().apply {
                    put("reTime", fixedNowMs - 10_000)
                    put("confirmCountDown", 5)
                    put("createTime", fixedNowMs - 60_000)
                }
            )
        )
        transport.scriptJson("/s/c/order/confirm", TestKit.plain("code" to 0))
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after a successful grab", awaitStop(engine))
        val confirmCalls = transport.calls.filter { it.url.contains("/s/c/order/confirm") }
        assertEquals(1, confirmCalls.size)
        assertTrue(confirmCalls[0].url.contains("id=order-1"))
        assertTrue(logs.any { it.contains("抢单成功") })
        assertTrue(logs.any { it.contains("登录成功") })
    }

    @Test
    fun `filtered order is skipped and confirm is never called`() {
        val transport = FakeTransport()
        loginScripts(transport)
        // Order below the min amount (25 yuan < min 100).
        val orders = JSONArray().apply { put(orderJson(totalAmount = 2500)) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val config = AppConfig(pollIntervalSec = 0.05, minAmount = 100.0)
        val engine = engineFor(transport, config, logs)
        assertTrue(engine.start("13800138000", "secret"))
        Thread.sleep(600)
        assertTrue("engine should still be running", engine.running)
        engine.stop()
        assertTrue(awaitStop(engine))
        assertTrue(transport.calls.none { it.url.contains("/s/c/order/confirm") })
        assertTrue(logs.any { it.contains("不符合要求") })
    }

    @Test
    fun `auth failure on poll triggers re-login and polling continues`() {
        val transport = FakeTransport()
        loginScripts(transport)
        var polls = 0
        transport.scriptDynamic("/s/c/order/randomList") { _ ->
            polls++
            if (polls == 1) {
                FakeTransport.Scripted(401, """{"code":401}""".toByteArray(Charsets.UTF_8))
            } else {
                FakeTransport.Scripted(
                    200,
                    TestKit.plain("code" to 0, "list" to JSONArray()).toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }
        }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        Thread.sleep(800)
        assertTrue("engine should still be running after re-login", engine.running)
        engine.stop()
        assertTrue(awaitStop(engine))
        val loginCalls = transport.calls.filter { it.url.contains("/s/duopei-account/login") }
        assertTrue("login should have been called at least twice", loginCalls.size >= 2)
        assertTrue(logs.any { it.contains("重新登录") || it.contains("登录态失效") })
    }

    @Test
    fun `confirm failure does not stop the engine`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain(
                "code" to 0,
                "order" to JSONObject().apply {
                    put("reTime", fixedNowMs - 10_000)
                    put("confirmCountDown", 5)
                }
            )
        )
        transport.scriptJson(
            "/s/c/order/confirm",
            TestKit.plain("code" to 1, "msg" to "已被抢走")
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        // The failed grab returns to polling; the same order is de-duped,
        // so no second confirm happens. Stop manually.
        Thread.sleep(600)
        assertTrue("engine should still be running after a failed grab", engine.running)
        engine.stop()
        assertTrue(awaitStop(engine))
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertTrue(logs.any { it.contains("抢单未成功") })
    }

    @Test
    fun `seen de-dup suppresses the same order within TTL`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson(id = "dup-1")) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        // Order fails the filter (amount 25 < min 100) so it is skipped
        // after being marked seen; a second poll must not re-log it.
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine2 = ListenEngine(
            client = client,
            config = AppConfig(pollIntervalSec = 0.05, minAmount = 100.0),
            log = { _, text -> logs.add(text) },
            nowEpochMs = { fixedNowMs }
        )
        assertTrue(engine2.start("13800138000", "secret"))
        Thread.sleep(700)
        engine2.stop()
        assertTrue(awaitStop(engine2))
        val detections = logs.count { it.contains("检测到新订单") }
        assertEquals("the same order must only be detected once within TTL", 1, detections)
    }
}
