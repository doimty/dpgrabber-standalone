package com.doimty.dpgrabber.engine

import com.doimty.dpgrabber.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** 全部请求为内存脚本，不连接业务服务。 */
class EngineTest {
    private fun transport(): FakeTransport = FakeTransport().apply {
        script("/login", 200, """{"code":0,"data":{"token":"base","shopId":105}}""")
        script("/list-shop-account", 200, """{"code":0,"data":[{"accountId":"a","shopId":92}]}""")
        script("/switch-account", 200, """{"code":0,"data":{"token":"shop"}}""")
        script("/clerk/get", 200, """{"code":0,"clerk":{"id":7}}""")
        script("/randomList", 200, """{"code":0,"list":[{"id":"order-1","totalAmount":2500,"userWechatId":"private-client"}]}""")
    }
    private val config = AppConfig(pollIntervalSec = 0.01, pollJitterSec = 0.0,
        confirmWaitByShop = emptyMap(), confirmRetryDelaySec = 0.0)

    @Test fun `成功仅一次历史并停止`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":0}""")
        val done = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val logs = CopyOnWriteArrayList<String>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, text -> logs.add(text) },
            onOutcome = { outcomes.add(it) }, onStateChange = { if (!it) done.countDown() })
        assertTrue(engine.start("10000000000", "test"))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(listOf(OutcomeType.SUCCESS), outcomes.map { it.type })
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        assertTrue(logs.none { it.contains("private-client") })
    }

    @Test fun `复用已有会话不再次登录`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":0}""")
        val client = TestKit.client(transport).apply { session = Session("shared", "92") }
        val done = CountDownLatch(1)
        val engine = ListenEngine(client, config, { _, _ -> }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertTrue(transport.calls.none { it.url.contains("/login") })
    }

    @Test fun `停止期间迟到的确认不能记成功或发最终回读`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val transport = transport()
        transport.scriptDynamic("/confirm") {
            entered.countDown()
            try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            FakeTransport.Scripted(200, """{"code":0}""".toByteArray())
        }
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            onStateChange = { if (!it) finished.countDown() }, onOutcome = { outcomes.add(it) })
        engine.start("10000000000", "test")
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        engine.stop()
        release.countDown()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(listOf(OutcomeType.UNKNOWN), outcomes.map { it.type })
        assertTrue(transport.calls.none { it.url.contains("/order/get") })
    }

    @Test fun `传输取消会调用cancelAll且停止后不再发请求`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val done = CountDownLatch(1)
        val transport = object : HttpTransport {
            override fun cancelAll() { cancelled.countDown() }
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, timeoutMs: Long): HttpResponse {
                entered.countDown()
                try { cancelled.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                return HttpResponse(200, """{"code":0}""".toByteArray())
            }
        }
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
    }

    @Test fun `未知结果不会跨轮次盲目重发`() {
        val transport = transport()
        transport.script("/confirm", 500, "{}")
        transport.script("/order/get", 200, """{"code":0,"data":{}}""")
        val recorded = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> }, onOutcome = { outcomes.add(it); recorded.countDown() })
        engine.start("10000000000", "test")
        assertTrue(recorded.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertEquals(OutcomeType.UNKNOWN, outcomes.single().type)
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
    }

    @Test fun `成功历史持久化完成才通知且失败时停止不接下一单`() {
        for (failPersistence in listOf(false, true)) {
            val transport = transport()
            transport.script("/confirm", 200, """{"code":0}""")
            val events = CopyOnWriteArrayList<String>()
            val done = CountDownLatch(1)
            val engine = ListenEngine(TestKit.client(transport), config.copy(autoStopAfterGrab = !failPersistence), { _, _ -> },
                onAttemptStarted = { events.add("unknown") },
                onOutcome = { events.add("persist"); if (failPersistence) throw IllegalStateException("模拟磁盘失败") },
                onSuccess = { events.add("notify") }, onStateChange = { if (!it) done.countDown() })
            engine.start("10000000000", "test")
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(engine.awaitStopped())
            assertEquals(if (failPersistence) listOf("unknown", "persist") else listOf("unknown", "persist", "notify"), events.toList())
            assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        }
    }

    @Test fun `预写unknown失败不发送确认`() {
        val transport = transport()
        val done = CountDownLatch(1)
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            onAttemptStarted = { throw IllegalStateException("模拟磁盘失败") },
            onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertTrue(transport.calls.none { it.url.contains("/confirm") })
    }

    @Test fun `确认明确稍后再试可重试且网络未知用回读确认归属`() {
        for (networkFailure in listOf(false, true)) {
            val transport = transport()
            var confirms = 0
            transport.scriptDynamic("/confirm") {
                confirms++
                FakeTransport.Scripted(if (networkFailure) 500 else 200,
                    (if (networkFailure) "{}" else if (confirms == 1) """{"code":2,"msg":"稍后再试"}""" else """{"code":0}""").toByteArray())
            }
            transport.script("/order/get", 200, """{"code":0,"status":2,"clerkId":7}""")
            val done = CountDownLatch(1)
            val outcomes = CopyOnWriteArrayList<OrderOutcome>()
            val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
                onOutcome = { outcomes.add(it) }, onStateChange = { if (!it) done.countDown() })
            engine.start("10000000000", "test")
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(engine.awaitStopped())
            assertEquals(OutcomeType.SUCCESS, outcomes.single().type)
            assertEquals(if (networkFailure) 1 else 2, confirms)
            assertEquals(if (networkFailure) 1 else 0, transport.calls.count { it.url.contains("/order/get") })
        }
    }

    @Test fun `过滤与持久阻断均不发送确认`() {
        for (blocked in listOf(false, true)) {
            val transport = transport()
            val checked = CountDownLatch(1)
            transport.scriptDynamic("/randomList") {
                checked.countDown()
                FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"o","totalAmount":2500}]}""".toByteArray())
            }
            val engine = ListenEngine(TestKit.client(transport), config.copy(minAmount = if (blocked) 0.0 else 100.0), { _, _ -> },
                isPersistentlyBlocked = { _, _ -> blocked })
            engine.start("10000000000", "test")
            assertTrue(checked.await(3, TimeUnit.SECONDS))
            engine.stop()
            assertTrue(engine.awaitStopped())
            assertTrue(transport.calls.none { it.url.contains("/confirm") })
        }
    }

    @Test fun `假时钟店铺等待无需真实sleep`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":0}""")
        val elapsed = java.util.concurrent.atomic.AtomicLong(0)
        val done = CountDownLatch(1)
        val engine = ListenEngine(TestKit.client(transport), config.copy(confirmWaitByShop = mapOf("92" to 5.0)), { _, _ -> },
            nowEpochMs = { 1700000000000L + elapsed.get() },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) },
            onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertTrue(elapsed.get() >= 5000)
    }

    @Test fun `轮询认证失效刷新会话同步新店员归属`() {
        val transport = transport()
        val loginCount = java.util.concurrent.atomic.AtomicInteger()
        transport.scriptDynamic("/login") {
            val count = loginCount.incrementAndGet()
            FakeTransport.Scripted(200, """{"code":0,"data":{"token":"base-$count","shopId":105}}""".toByteArray())
        }
        transport.scriptDynamic("/list-shop-account") {
            val shop = if (loginCount.get() == 1) 92 else 105
            FakeTransport.Scripted(200, """{"code":0,"data":[{"accountId":"a","shopId":$shop}]}""".toByteArray())
        }
        transport.scriptDynamic("/clerk/get") { call ->
            val clerk = if (call.headers["sid"] == "92") 7 else 8
            FakeTransport.Scripted(200, """{"code":0,"clerk":{"id":$clerk}}""".toByteArray())
        }
        val polls = java.util.concurrent.atomic.AtomicInteger()
        transport.scriptDynamic("/randomList") {
            if (polls.incrementAndGet() == 1) FakeTransport.Scripted(401, """{"code":401}""".toByteArray())
            else FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"o","totalAmount":2500}]}""".toByteArray())
        }
        transport.script("/confirm", 500, "{}")
        transport.script("/order/get", 200, """{"code":0,"status":2,"clerkId":8}""")
        val done = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            onOutcome = { outcomes.add(it) }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(2, loginCount.get())
        assertEquals("105", outcomes.single().shopId)
        assertEquals(OutcomeType.SUCCESS, outcomes.single().type)
    }

    @Test fun `预算耗尽最终回读成功或pending保留unknown不重复发送`() {
        for (confirmed in listOf(false, true)) {
            val transport = transport()
            transport.script("/confirm", 200, """{"code":2,"msg":"稍后再试"}""")
            transport.script("/order/get", 200, if (confirmed) """{"code":0,"status":2,"clerkId":7}""" else """{"code":0,"status":0}""")
            val elapsed = java.util.concurrent.atomic.AtomicLong()
            val recorded = CountDownLatch(1)
            val outcomes = CopyOnWriteArrayList<OrderOutcome>()
            val engine = ListenEngine(TestKit.client(transport), config.copy(randomConfirmFallbackSec = 0.3, confirmRetryDelaySec = 0.1), { _, _ -> },
                monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) },
                onOutcome = { outcomes.add(it); recorded.countDown() })
            engine.start("10000000000", "test")
            assertTrue(recorded.await(3, TimeUnit.SECONDS))
            if (!confirmed) engine.stop()
            assertTrue(engine.awaitStopped())
            assertEquals(if (confirmed) OutcomeType.SUCCESS else OutcomeType.UNKNOWN, outcomes.single().type)
            assertEquals(1, transport.calls.count { it.url.contains("/order/get") })
            assertTrue(transport.calls.count { it.url.contains("/confirm") } > 1)
        }
    }

    @Test fun `autoStop关闭成功后继续监听且定时休息可取消`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":0}""")
        val success = CountDownLatch(1)
        val rest = CountDownLatch(1)
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val engine = ListenEngine(TestKit.client(transport), config.copy(autoStopAfterGrab = false, runDurationSec = 0.1, restDurationSec = 5.0),
            { _, text -> if (text.contains("定时休息")) rest.countDown() },
            nowEpochMs = { 1700000000000L + elapsed.get() }, monotonicNs = { elapsed.get() * 1000000 },
            sleeper = { elapsed.addAndGet(it) }, onSuccess = { success.countDown() })
        engine.start("10000000000", "test")
        assertTrue(success.await(3, TimeUnit.SECONDS))
        assertTrue(rest.await(3, TimeUnit.SECONDS))
        assertTrue(engine.running)
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
    }

    @Test fun `队首被屏蔽不遮挡后续订单且等待期间新黑名单生效`() {
        val transport = transport()
        transport.scriptDynamic("/randomList") {
            FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"filtered","totalAmount":500},{"id":"second","totalAmount":2500}]}""".toByteArray())
        }
        transport.script("/confirm", 200, """{"code":0}""")
        val done = CountDownLatch(1)
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        val confirm = transport.calls.single { it.url.contains("/confirm") }
        assertTrue(confirm.url.contains("id=second"))
        assertFalse(confirm.url.contains("filtered"))
    }

    @Test fun `非零成功文案只回查一次未知不重发`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":2,"msg":"确认成功"}""")
        transport.script("/order/get", 200, """{"code":0,"status":2}""")
        val recorded = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> }, onOutcome = { outcomes.add(it); recorded.countDown() })
        engine.start("10000000000", "test")
        assertTrue(recorded.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertEquals(OutcomeType.UNKNOWN, outcomes.single().type)
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        assertEquals(1, transport.calls.count { it.url.contains("/order/get") })
    }

    @Test fun `被他人接走记录skipped且资质拒绝failed`() {
        for ((message, expected) in listOf("已被其他人抢走" to OutcomeType.SKIPPED, "资质不符合" to OutcomeType.FAILED)) {
            val transport = transport()
            transport.script("/confirm", 200, JSONObject().put("code", 1).put("msg", message).toString())
            val recorded = CountDownLatch(1)
            val outcomes = CopyOnWriteArrayList<OrderOutcome>()
            val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> }, onOutcome = { outcomes.add(it); recorded.countDown() })
            engine.start("10000000000", "test")
            assertTrue(recorded.await(3, TimeUnit.SECONDS))
            engine.stop()
            assertTrue(engine.awaitStopped())
            assertEquals(expected, outcomes.single().type)
            assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        }
    }
}
