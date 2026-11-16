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

    @Test fun `忙碌只确认一次预写更新同attempt且落库后才冷却`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":0,"msg":"同时接单数超过限制的10单"}""")
        val enteredCooldown = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val attempts = CopyOnWriteArrayList<OrderOutcome>()
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            onAttemptStarted = { attempts.add(it) }, onOutcome = { outcomes.add(it) },
            sleeper = { if (outcomes.isNotEmpty()) { enteredCooldown.countDown(); hold.await(3, TimeUnit.SECONDS) } })
        engine.start("10000000000", "test")
        assertTrue(enteredCooldown.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertEquals(OutcomeType.UNKNOWN, attempts.single().type)
        assertEquals(OutcomeType.SKIPPED, outcomes.single().type)
        assertEquals(attempts.single().attemptId, outcomes.single().attemptId)
        assertTrue(outcomes.single().reason.contains("10单"))
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        assertTrue(transport.calls.none { it.url.contains("/order/get") })
    }

    @Test fun `忙碌默认60秒冷却再轮询且旧单不会再次确认`() {
        val transport = transport()
        transport.script("/confirm", 200, """{"code":1,"msg":"同时接单数超过限制的3单"}""")
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val repolled = CountDownLatch(1)
        transport.scriptDynamic("/randomList") {
            if (polls.incrementAndGet() >= 3) repolled.countDown()
            FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"busy","totalAmount":2500}]}""".toByteArray())
        }
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) }, onOutcome = { outcomes.add(it) })
        engine.start("10000000000", "test")
        assertTrue(repolled.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertTrue(elapsed.get() >= 60000)
        assertEquals(OutcomeType.SKIPPED, outcomes.single().type)
        assertTrue(outcomes.single().reason.contains("3单"))
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        assertTrue(transport.calls.none { it.url.contains("/order/get") })
    }

    @Test fun `提交后网络未知即使只读pending也不得自动重发`() {
        val transport = transport()
        transport.script("/confirm", 500, "{}")
        transport.script("/order/get", 200, """{"code":0,"status":0}""")
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

    @Test fun `忙碌冷却使用热配置并继续扫描而不重发旧单`() {
        val transport = transport()
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val confirms = java.util.concurrent.atomic.AtomicInteger()
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val done = CountDownLatch(1)
        transport.scriptDynamic("/randomList") {
            val id = if (outcomes.isEmpty()) "busy" else "next"
            FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"$id","totalAmount":2500}]}""".toByteArray())
        }
        transport.scriptDynamic("/confirm") {
            val response = if (confirms.incrementAndGet() == 1) """{"code":1,"msg":"接单数超过限制"}""" else """{"code":0}"""
            FakeTransport.Scripted(200, response.toByteArray())
        }
        val engine = ListenEngine(TestKit.client(transport), config, { _, _ -> },
            configSnapshot = { config.copy(busyCooldownSec = 7) },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) },
            onOutcome = { outcomes.add(it) }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(listOf(OutcomeType.SKIPPED, OutcomeType.SUCCESS), outcomes.map { it.type })
        assertTrue(elapsed.get() >= 7000)
        assertTrue(elapsed.get() < 8000)
        assertEquals(2, confirms.get())
    }

    @Test fun `等待期间关闭订单类型提交前立即热读生效`() {
        val transport = transport()
        val key = accountKeyForPhone("10000000000")
        val initial = config.copy(confirmWaitByShop = mapOf("92" to 1.0),
            itemSwitchIds = listOf(465), itemSwitchAccountKey = key, itemSwitchShopId = "92")
        val latest = java.util.concurrent.atomic.AtomicReference(initial)
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val client = TestKit.client(transport).apply { session = Session("test", "92") }
        val engine = ListenEngine(client, initial, { _, _ -> }, configSnapshot = { latest.get() },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it); latest.set(initial.copy(itemSwitchIds = listOf(2666))) })
        engine.handleOrder(JSONObject().put("id", "o").put("totalAmount", 2500).put("itemId", 465), "o", "10000000000", "test")
        assertTrue(elapsed.get() >= 1000)
        assertTrue(transport.calls.none { it.url.contains("/confirm") })
    }

    @Test fun `重复确认前关闭类型不再写入并保留unknown`() {
        val transport = transport()
        val initial = config.copy(itemSwitchIds = listOf(465), itemSwitchAccountKey = accountKeyForPhone("10000000000"), itemSwitchShopId = "92")
        val latest = java.util.concurrent.atomic.AtomicReference(initial)
        transport.scriptDynamic("/confirm") {
            latest.set(initial.copy(itemSwitchIds = listOf(2666)))
            FakeTransport.Scripted(200, """{"code":2,"msg":"稍后再试"}""".toByteArray())
        }
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val client = TestKit.client(transport).apply { session = Session("test", "92") }
        val engine = ListenEngine(client, initial, { _, _ -> }, configSnapshot = { latest.get() }, onOutcome = { outcomes.add(it) })
        val order = JSONObject().put("id", "o").put("totalAmount", 2500).put("itemId", 465)
        engine.handleOrder(order, "o", "10000000000", "test")
        engine.handleOrder(order, "o", "10000000000", "test")
        assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        assertEquals(OutcomeType.UNKNOWN, outcomes.single().type)
    }

    @Test fun `引擎类型缓存跨账号和店铺不误过滤`() {
        for (scopeMismatch in listOf("account", "shop")) {
            val transport = transport()
            transport.script("/confirm", 200, """{"code":0}""")
            transport.scriptDynamic("/randomList") {
                FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"o","totalAmount":2500,"itemId":465}]}""".toByteArray())
            }
            val cfg = config.copy(itemSwitchIds = listOf(2666),
                itemSwitchAccountKey = accountKeyForPhone(if (scopeMismatch == "account") "10000000001" else "10000000000"),
                itemSwitchShopId = if (scopeMismatch == "shop") "105" else "92")
            val done = CountDownLatch(1)
            val engine = ListenEngine(TestKit.client(transport), cfg, { _, _ -> }, onStateChange = { if (!it) done.countDown() })
            engine.start("10000000000", "test")
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(engine.awaitStopped())
            assertEquals(1, transport.calls.count { it.url.contains("/confirm") })
        }
    }

    @Test fun `缺类型仅提示一次且保留列表后续扫描`() {
        val transport = transport()
        val logs = CopyOnWriteArrayList<String>()
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val done = CountDownLatch(1)
        transport.scriptDynamic("/randomList") {
            val id = "o-${outcomes.size}"
            FakeTransport.Scripted(200, """{"code":0,"list":[{"id":"disabled","totalAmount":2500,"itemId":2666},{"id":"$id","totalAmount":2500,"itemName":"30分钟体验"}]}""".toByteArray())
        }
        transport.script("/confirm", 200, """{"code":0}""")
        val cfg = config.copy(autoStopAfterGrab = false, itemSwitchIds = listOf(465), itemSwitchAccountKey = accountKeyForPhone("10000000000"), itemSwitchShopId = "92")
        lateinit var engine: ListenEngine
        engine = ListenEngine(TestKit.client(transport), cfg, { _, text -> logs.add(text) },
            onOutcome = { outcomes.add(it); if (outcomes.size == 2) engine.abort() }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(2, outcomes.size)
        assertEquals(1, logs.count { it.contains("订单缺少可识别的类型") })
        assertTrue(transport.calls.filter { it.url.contains("/confirm") }.none { it.url.contains("disabled") })
    }

    @Test fun `网络相同错误首次和每10次汇总并只报告一次恢复`() {
        val transport = transport()
        val polls = java.util.concurrent.atomic.AtomicInteger()
        transport.scriptDynamic("/randomList") {
            if (polls.incrementAndGet() <= 21) throw java.io.IOException("测试离线")
            FakeTransport.Scripted(200, """{"code":0,"list":[]}""".toByteArray())
        }
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val recovered = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        val engine = ListenEngine(TestKit.client(transport), config, { _, text -> logs.add(text); if (text.contains("网络已恢复")) recovered.countDown() },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) })
        engine.start("10000000000", "test")
        assertTrue(recovered.await(3, TimeUnit.SECONDS))
        engine.stop()
        assertTrue(engine.awaitStopped())
        assertEquals(1, logs.count { it.contains("网络连接中断") })
        assertEquals(2, logs.count { it.contains("同类轮询错误") })
        assertEquals(1, logs.count { it.contains("网络已恢复，此前连续失败21次") })
        assertTrue(elapsed.get() >= (5 + 10 + 20 + 18 * 30) * 1000L)
    }

    @Test fun `同类业务拒绝不刷轮次日志且保留五次熔断`() {
        val transport = transport()
        transport.scriptDynamic("/randomList") {
            FakeTransport.Scripted(200, """{"code":7,"msg":"店铺未上线"}""".toByteArray())
        }
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        val logs = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val engine = ListenEngine(TestKit.client(transport), config, { _, text -> logs.add(text) },
            monotonicNs = { elapsed.get() * 1000000 }, sleeper = { elapsed.addAndGet(it) }, onStateChange = { if (!it) done.countDown() })
        engine.start("10000000000", "test")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(engine.awaitStopped())
        assertEquals(5, transport.calls.count { it.url.contains("/randomList") })
        assertEquals(1, logs.count { it.contains("抢单请求被平台拒绝：") })
        assertEquals(1, logs.count { it.contains("店铺未上线，抢单请求被拒绝") })
        assertTrue(transport.calls.none { it.url.contains("/confirm") })
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
