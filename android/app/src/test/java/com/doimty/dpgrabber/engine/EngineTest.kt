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
 * The fake clock is fixed so the de-dup math is deterministic; confirm
 * windows use small real-time values so deadline tests run fast.
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
        // One passing order: the confirm task starts immediately and the
        // first confirm succeeds; no order/get is needed on this path.
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptJson("/s/c/order/confirm", TestKit.plain("code" to 0))
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after a successful grab", awaitStop(engine))
        val confirmCalls = transport.calls.filter { it.url.contains("/s/c/order/confirm") }
        assertEquals(1, confirmCalls.size)
        assertTrue(confirmCalls[0].url.contains("id=order-1"))
        assertTrue("no detail fetch on the fast path",
            transport.calls.none { it.url.contains("/s/c/order/get") })
        assertTrue(logs.any { it.contains("抢单成功") })
        assertTrue(logs.any { it.contains("登录成功") })
    }

    @Test
    fun `confirm retry then success`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        var confirms = 0
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            confirms++
            val body = if (confirms == 1) {
                TestKit.plain("code" to 2, "msg" to "请稍后再试")
            } else {
                TestKit.plain("code" to 0)
            }
            FakeTransport.Scripted(200, body.toString().toByteArray(Charsets.UTF_8))
        }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after a successful grab", awaitStop(engine))
        assertEquals(2, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertTrue(logs.any { it.contains("抢单成功") })
    }

    @Test
    fun `auth failure in confirm triggers re-login and a new task`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        var confirms = 0
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            confirms++
            val body = if (confirms == 1) {
                TestKit.plain("code" to 401)
            } else {
                TestKit.plain("code" to 0)
            }
            FakeTransport.Scripted(200, body.toString().toByteArray(Charsets.UTF_8))
        }
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after a successful grab", awaitStop(engine))
        val loginCalls = transport.calls.filter { it.url.contains("/s/duopei-account/login") }
        assertTrue("login should have been called at least twice", loginCalls.size >= 2)
        assertTrue(logs.any { it.contains("确认认证失败") })
        assertTrue(logs.any { it.contains("抢单成功") })
        assertEquals(2, transport.calls.count { it.url.contains("/s/c/order/confirm") })
    }

    @Test
    fun `network error on confirm reads back and succeeds`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        var confirms = 0
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            confirms++
            FakeTransport.Scripted(500, "{}".toByteArray(Charsets.UTF_8))
        }
        // Readback: confirmed (status 2, same clerk id 7 as the login stub).
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 2, "clerkId" to 7)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after readback success", awaitStop(engine))
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/get") })
        assertTrue(logs.any { it.contains("抢单成功") })
    }

    @Test
    fun `readback pending returns to the loop and retries`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        var confirms = 0
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            confirms++
            if (confirms == 1) {
                FakeTransport.Scripted(500, "{}".toByteArray(Charsets.UTF_8))
            } else {
                FakeTransport.Scripted(
                    200,
                    TestKit.plain("code" to 0).toString().toByteArray(Charsets.UTF_8)
                )
            }
        }
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 0)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after a successful grab", awaitStop(engine))
        assertEquals(2, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/get") })
        assertTrue(logs.any { it.contains("抢单成功") })
    }

    @Test
    fun `readback unknown stops blind retry`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            FakeTransport.Scripted(500, "{}".toByteArray(Charsets.UTF_8))
        }
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 9, "msg" to "奇怪")
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        Thread.sleep(600)
        assertTrue("engine should still be running after unknown readback", engine.running)
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertTrue(logs.any { it.contains("停止盲重试") })
        engine.stop()
        assertTrue(awaitStop(engine))
    }

    @Test
    fun `readback clerk mismatch fails as taken by someone else`() {
        val transport = FakeTransport()
        loginScripts(transport)
        val orders = JSONArray().apply { put(orderJson()) }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptDynamic("/s/c/order/confirm") { _ ->
            FakeTransport.Scripted(500, "{}".toByteArray(Charsets.UTF_8))
        }
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 2, "clerkId" to 99)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = engineFor(transport, logs = logs)
        assertTrue(engine.start("13800138000", "secret"))
        Thread.sleep(600)
        assertTrue("engine should still be running after readback fail", engine.running)
        assertEquals(1, transport.calls.count { it.url.contains("/s/c/order/confirm") })
        assertTrue(logs.any { it.contains("被其他人抢走") })
        engine.stop()
        assertTrue(awaitStop(engine))
    }

    @Test
    fun `deadline pending releases the order for a new task`() {
        val transport = FakeTransport()
        loginScripts(transport)
        // createTime present, so the 0.3s window (not the fallback) applies.
        val orders = JSONArray().apply {
            put(orderJson().apply { put("createTime", 1760000000000) })
        }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptJson(
            "/s/c/order/confirm",
            TestKit.plain("code" to 2, "msg" to "稍后再试")
        )
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 0)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val config = AppConfig(
            pollIntervalSec = 0.05,
            randomConfirmWindowSec = 0.3,
            confirmRetryDelaySec = 0.05
        )
        val engine = engineFor(transport, config, logs)
        assertTrue(engine.start("13800138000", "secret"))
        Thread.sleep(1500)
        assertTrue("engine should still be running", engine.running)
        engine.stop()
        assertTrue(awaitStop(engine))
        assertTrue(logs.any { it.contains("本轮确认预算耗尽") })
        // The order was released, so a later poll started a new task.
        assertTrue(
            "expected at least two confirm tasks after release",
            logs.count { it.contains("尝试接单") } >= 2
        )
        assertTrue(transport.calls.count { it.url.contains("/s/c/order/confirm") } > 1)
    }

    @Test
    fun `deadline final readback ok succeeds`() {
        val transport = FakeTransport()
        loginScripts(transport)
        // createTime present, so the 0.3s window (not the fallback) applies.
        val orders = JSONArray().apply {
            put(orderJson().apply { put("createTime", 1760000000000) })
        }
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain("code" to 0, "list" to orders)
        )
        transport.scriptJson(
            "/s/c/order/confirm",
            TestKit.plain("code" to 2, "msg" to "稍后再试")
        )
        transport.scriptJson(
            "/s/c/order/get",
            TestKit.plain("code" to 0, "status" to 2, "clerkId" to 7)
        )
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val config = AppConfig(
            pollIntervalSec = 0.05,
            randomConfirmWindowSec = 0.3,
            confirmRetryDelaySec = 0.05
        )
        val engine = engineFor(transport, config, logs)
        assertTrue(engine.start("13800138000", "secret"))
        assertTrue("engine should stop after final readback success", awaitStop(engine))
        assertTrue(transport.calls.count { it.url.contains("/s/c/order/confirm") } > 1)
        assertTrue(logs.any { it.contains("抢单成功") })
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
        assertTrue("no readback on a terminal reply",
            transport.calls.none { it.url.contains("/s/c/order/get") })
        assertTrue(logs.any { it.contains("被其他人抢走") })
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
