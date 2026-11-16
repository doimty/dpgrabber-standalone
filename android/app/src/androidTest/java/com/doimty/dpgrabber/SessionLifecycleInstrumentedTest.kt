package com.doimty.dpgrabber

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Condition
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import com.doimty.dpgrabber.core.Session
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.ui.MainActivity
import com.doimty.dpgrabber.engine.ListenEngine
import com.doimty.dpgrabber.engine.OrderOutcome
import com.doimty.dpgrabber.engine.OutcomeType
import com.doimty.dpgrabber.service.ListenService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅隔离环境执行；严格内存传输替身，不存在真实网络回退。 */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleInstrumentedTest {
    private val config = AppConfig(pollIntervalSec = 0.1, pollJitterSec = 0.0,
        confirmWaitByShop = emptyMap(), onlineAfterLogin = false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val allPaths = CopyOnWriteArrayList<String>()
    private val transports = CopyOnWriteArrayList<StrictTransport>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var serviceCase = false
    private var lifecycleCallback: ActivityLifecycleCallback? = null
    private var previousConfig: AppConfig? = null
    private var temporaryCredentials = false
    private val busyField = MainActivity::class.java.getDeclaredField("networkBusy").apply { isAccessible = true }
    private val engines = mutableListOf<ListenEngine>()
    private val factoryField = SessionRepository::class.java.getDeclaredField("transportFactory").apply {
        isAccessible = true
    }
    private val runningField = ListenService::class.java.getDeclaredField("isRunning").apply {
        isAccessible = true
    }
    private var previousFactory: Any? = null

    @Before
    fun prepare() {
        verifyNetworkIsolation()
        assertFalse("不得接管已运行的监听服务", ListenService.isRunning)
        SessionRepository.invalidate()
        previousFactory = factoryField.get(SessionRepository)
        val factory: () -> HttpTransport = {
            StrictTransport(allPaths).also { transports.add(it) }
        }
        factoryField.set(SessionRepository, factory)
    }

    @After
    fun finish() {
        if (previousFactory == null) return
        try {
            transports.forEach { it.releaseConfirm.countDown(); it.releasePoll.countDown() }
            if (serviceCase) {
                ListenService.stop(context)
                awaitCondition("真实服务必须收尾退出") { !ListenService.isRunning && !serviceInstancePresent() }
            }
            engines.forEach { it.stop() }
            engines.forEach { assertTrue("工作线程必须退出", it.awaitStopped()) }
            scenario?.close()
            lifecycleCallback?.let { callback -> instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
            } }
            if (!serviceCase) setServiceRunning(false)
            SessionRepository.invalidate()
            if (temporaryCredentials) CredentialStore(context).clearAccount()
            previousConfig?.let { CredentialStore(context).saveConfig(it) }
            transports.forEach { assertTrue("未知请求不得回退真实网络", it.unexpected.isEmpty()) }
        } finally {
            previousFactory?.let { factoryField.set(SessionRepository, it) }
        }
    }

    @Test
    fun stopAndRestartRetainsAuthenticationWithFreshTransport() {
        val first = login()
        val originalSession = checkNotNull(first.session)
        val firstTransport = transports.last()
        val firstEngine = engine(first)
        assertTrue(firstEngine.start(PHONE, PASSWORD))
        assertTrue(firstTransport.polled.await(3, TimeUnit.SECONDS))
        val revisionBeforeStop = SessionRepository.revision
        firstEngine.stop()
        assertTrue(firstEngine.awaitStopped())
        assertCancelled(first)
        assertTrue("取消传输不是注销", SessionRepository.hasLiveSession())
        assertEquals(revisionBeforeStop, SessionRepository.revision)

        val second = SessionRepository.forAccount(config, PHONE, PASSWORD)
        assertNotSame(first, second)
        assertEquals(revisionBeforeStop + 1, SessionRepository.revision)
        assertSameAuthentication(originalSession, second.session)
        assertEquals(2, transports.size)
        assertNotSame(firstTransport, transports.last())
        assertCancelled(first)
        assertNull("旧客户端不再持有仓库交出的会话", first.session)

        val secondTransport = transports.last()
        val secondEngine = engine(second)
        assertTrue(secondEngine.start(PHONE, PASSWORD))
        assertTrue(secondTransport.polled.await(3, TimeUnit.SECONDS))
        secondEngine.stop()
        assertTrue(secondEngine.awaitStopped())
        assertCancelled(second)
        assertTrue(SessionRepository.hasLiveSession())
        assertEquals("两轮监听合计只允许首次登录", 1,
            transports.sumOf { transport -> transport.paths.count { it == LOGIN_PATH } })
        assertTrue("新传输复用认证，不得重登或切店",
            secondTransport.paths.all { it == CLERK_PATH || it == POLL_PATH })
        assertTrue(firstTransport.cancelled)
        assertTrue(secondTransport.cancelled)
        val third = SessionRepository.forAccount(config, PHONE, PASSWORD)
        assertSameAuthentication(originalSession, third.session)
        val thirdTransport = transports.last()
        val thirdEngine = engine(third)
        assertTrue(thirdEngine.start(PHONE, PASSWORD))
        assertTrue(thirdTransport.polled.await(3, TimeUnit.SECONDS))
        thirdEngine.stop()
        assertTrue(thirdEngine.awaitStopped())
        assertCancelled(third)
        assertTrue(SessionRepository.hasLiveSession())
        assertEquals("三轮启停仍只有一次登录", 1, allPaths.count { it == LOGIN_PATH })
    }

    @Test
    fun stoppingKeepsAuthenticationButForbidsClientSwapUntilWorkerExits() {
        val first = login()
        val originalSession = checkNotNull(first.session)
        val transport = transports.last().apply { holdConfirm = true }
        val outcomes = CopyOnWriteArrayList<OrderOutcome>()
        val successes = CopyOnWriteArrayList<Unit>()
        val task = engine(first, outcomes, successes)
        setServiceRunning(true)
        try {
        assertTrue(task.start(PHONE, PASSWORD))
        assertTrue("合法订单必须真正进入一次确认，而不是在筛选阶段失败",
            transport.confirmEntered.await(10, TimeUnit.SECONDS))
        assertSame("运行中只可取回当前客户端", first,
            SessionRepository.forAccount(config, PHONE, PASSWORD))
        task.stop()
        assertTrue("工作线程仍在收尾时服务不得提前空闲", ListenService.isRunning)
        assertTrue("停止中仍然已认证", SessionRepository.hasLiveSession())
        val before = SessionRepository.revision
        expectIllegalState { SessionRepository.forAccount(config, PHONE, PASSWORD) }
        expectIllegalState { SessionRepository.forAccount(config, OTHER_PHONE, PASSWORD) }
        expectIllegalState { SessionRepository.invalidate() }
        assertEquals(before, SessionRepository.revision)
        assertEquals(1, transports.size)
        assertCancelled(first)

        transport.releaseConfirm.countDown()
        assertTrue(task.awaitStopped())
        assertFalse(ListenService.isRunning)
        assertEquals(listOf(OutcomeType.UNKNOWN), outcomes.map { it.type })
        assertTrue("迟到成功不得发成功回调", successes.isEmpty())
        assertEquals("在途确认不得重发", 1, transport.paths.count { it == CONFIRM_PATH })
        assertFalse("取消后不得最终回读", transport.paths.contains(READBACK_PATH))
        val next = SessionRepository.forAccount(config, PHONE, PASSWORD)
        val revisionAfterSwap = SessionRepository.revision
        assertTrue(revisionAfterSwap > before)
        assertSameAuthentication(originalSession, next.session)
        // 旧对象的迟到状态更新只能影响旧对象，不能覆盖新客户端或仓库 revision。
        first.session = Session("late-synthetic-token", "105")
        assertSame(next, SessionRepository.forAccount(config, PHONE, PASSWORD))
        assertEquals(revisionAfterSwap, SessionRepository.revision)
        assertSameAuthentication(originalSession, next.session)
        assertCancelled(first)
        } finally { setServiceRunning(false) }
    }

    @Test
    fun accountOrPasswordChangeDropsAuthentication() {
        login()
        val changedAccount = SessionRepository.forAccount(config, OTHER_PHONE, PASSWORD)
        assertNull(changedAccount.session)
        assertFalse(SessionRepository.hasLiveSession())
        changedAccount.session = Session("synthetic-other-token", "92")
        val changedPassword = SessionRepository.forAccount(config, OTHER_PHONE, "other-synthetic-password")
        assertNull(changedPassword.session)
        assertFalse(SessionRepository.hasLiveSession())
        assertCancelled(changedAccount)
    }

    @Test
    fun everyConnectionIdentityChangeDropsAuthentication() {
        val changes = listOf(
            config.copy(shopId = 105),
            config.copy(aesKeyHex = config.aesKeyHex.reversed()),
            config.copy(aesIvHex = config.aesIvHex.reversed()),
            config.copy(aesKeyHexAlt = config.aesKeyHexAlt.reversed()),
            config.copy(aesIvHexAlt = config.aesIvHexAlt.reversed())
        )
        for (changed in changes) {
            SessionRepository.invalidate()
            val previous = login()
            val revision = SessionRepository.revision
            val next = SessionRepository.forAccount(changed, PHONE, PASSWORD)
            assertNotSame(previous, next)
            assertNull(next.session)
            assertFalse(SessionRepository.hasLiveSession())
            assertEquals(revision + 1, SessionRepository.revision)
            assertCancelled(previous)
        }
    }

    @Test
    fun explicitInvalidateLogsOutAndCannotResurrectOldClient() {
        val previous = login()
        previous.cancel()
        assertTrue(SessionRepository.hasLiveSession())
        val revision = SessionRepository.revision
        SessionRepository.invalidate()
        assertFalse(SessionRepository.hasLiveSession())
        assertEquals(revision + 1, SessionRepository.revision)
        assertNull(previous.session)
        assertCancelled(previous)
        val next = SessionRepository.forAccount(config, PHONE, PASSWORD)
        assertNull(next.session)
        assertFalse(SessionRepository.hasLiveSession())
        assertNotSame(previous, next)
        assertTrue("重建客户端自身不得发出请求", transports.last().paths.isEmpty())
    }

    @Test
    fun clearedAuthenticationIsNotCopiedWhenCancelledClientIsReplaced() {
        val previous = login()
        // 认证失效路径已清会话后，停止传输不能把旧认证找回来。
        previous.session = null
        previous.cancel()
        val next = SessionRepository.forAccount(config, PHONE, PASSWORD)
        assertNull(next.session)
        assertFalse(SessionRepository.hasLiveSession())
        assertCancelled(previous)
    }

    private fun login(): ApiClient {
        return SessionRepository.forAccount(config, PHONE, PASSWORD).also {
            it.sessionForAccount(PHONE, PASSWORD, config.shopId)
            assertTrue(SessionRepository.hasLiveSession())
        }
    }

    private fun engine(client: ApiClient, outcomes: MutableList<OrderOutcome> = CopyOnWriteArrayList(),
                       successes: MutableList<Unit> = CopyOnWriteArrayList()): ListenEngine {
        return ListenEngine(client, config, { _, _ -> },
            onStateChange = { active -> setServiceRunning(active) },
            onOutcome = { outcomes.add(it) }, onSuccess = { successes.add(Unit) }).also { engines.add(it) }
    }

    private fun setServiceRunning(active: Boolean) { runningField.setBoolean(null, active) }

    private fun assertCancelled(client: ApiClient) {
        assertTrue(client.isCancelled)
        for (call in listOf<() -> Unit>({ client.checkActive() }, { client.resumeRequests() })) {
            try {
                call()
                fail("已取消的旧客户端不得复活")
            } catch (error: ApiError) {
                assertEquals(ApiError.Kind.CANCELLED, error.kind)
            }
        }
    }

    private fun assertSameAuthentication(expected: Session, actual: Session?) {
        assertNotNull(actual)
        assertTrue("认证令牌必须保持一致且不得写入日志", expected.token == actual?.token)
        assertTrue("认证店铺必须保持一致", expected.sid == actual?.sid)
    }

    private fun expectIllegalState(call: () -> Unit) {
        try { call(); fail("服务退出前不得换客户端或清会话") }
        catch (_: IllegalStateException) { }
    }

    @Test
    fun cancelledHangingPollCannotIssueAnotherRequest() {
        val client = login()
        val transport = transports.last().apply { holdPoll = true }
        val task = engine(client)
        assertTrue(task.start(PHONE, PASSWORD))
        assertTrue(transport.polled.await(3, TimeUnit.SECONDS))
        task.stop()
        assertTrue("停止必须调用真实替身 cancelAll", transport.cancelObserved.await(3, TimeUnit.SECONDS))
        transport.releasePoll.countDown()
        assertTrue(task.awaitStopped())
        assertEquals("迟到空响应后不得下一次轮询", 1, transport.paths.count { it == POLL_PATH })
        assertCancelled(client)
        assertTrue(SessionRepository.hasLiveSession())
    }

    @Test
    fun activityStartStopKeepsProfileAndDoesNotLoginAgain() {
        prepareServiceCase()
        val client = SessionRepository.forAccount(config, PHONE, PASSWORD)
        client.sessionForAccount(PHONE, PASSWORD, config.shopId)
        transports.last().holdPoll = true
        launchServiceActivity()
        clickStart()
        val transport = transports.last()
        assertTrue(transport.polled.await(5, TimeUnit.SECONDS))
        clickStop()
        assertTrue(transport.cancelObserved.await(5, TimeUnit.SECONDS))
        transport.releasePoll.countDown()
        awaitCondition("停止后服务必须真正销毁") { !ListenService.isRunning && !serviceInstancePresent() }
        assertEquals(1, transport.paths.count { it == POLL_PATH })
        assertCancelled(client)
        assertProfileRetained()
        clickStart()
        awaitCondition("第二轮真实服务必须轮询") {
            allPaths.count { it == POLL_PATH } >= 2 && ListenService.isRunning
        }
        clickStop()
        awaitCondition("第二轮服务必须退出") { !ListenService.isRunning && !serviceInstancePresent() }
        assertProfileRetained()
        assertEquals("实际点击启停不得重复登录", 1, allPaths.count { it == LOGIN_PATH })
        assertFalse("真实服务用例禁止确认订单", allPaths.contains(CONFIRM_PATH))
    }

    @Test
    fun rapidActivityStartStopDoesNotRetainEngineOrLoseAuthentication() {
        prepareServiceCase()
        login()
        launchServiceActivity()
        // 在同一个主线程步骤连续点击开始/停止，不等待轮询或“正在监听”。
        scenario!!.onActivity { activity ->
            busyField.setBoolean(activity, false)
            refreshStatus(activity)
            val start = activity.findViewById<Button>(R.id.startButton)
            assertTrue("真实开始按钮必须可用", start.isEnabled)
            assertTrue(start.performClick())
            busyField.setBoolean(activity, true)
            val stop = activity.findViewById<Button>(R.id.stopButton)
            stop.isEnabled = true
            assertTrue(stop.performClick())
            // stop(context) 在服务尚未接到 START 时可能无操作，补排 STOP Intent，
            // 与实际 START 串行进入同一个 Service，覆盖 baseline 前就取消的窗口。
            activity.startService(Intent(activity, ListenService::class.java).setAction(ListenService.ACTION_STOP))
        }
        awaitCondition("快速停止必须销毁服务") {
            transports.any { it.cancelled } && !ListenService.isRunning && !serviceInstancePresent()
        }
        assertProfileRetained()
        assertEquals(1, allPaths.count { it == LOGIN_PATH })
        clickStart()
        awaitCondition("快速停止后必须允许新一轮启动") {
            ListenService.isRunning && allPaths.contains(POLL_PATH)
        }
        clickStop()
        awaitCondition("重启后服务必须正常退出") { !ListenService.isRunning && !serviceInstancePresent() }
        assertProfileRetained()
        assertEquals(1, allPaths.count { it == LOGIN_PATH })
    }

    private fun prepareServiceCase() {
        serviceCase = true
        val store = CredentialStore(context)
        assertTrue("仅允许空账号的一次性验收环境", store.loadPhone().isEmpty())
        previousConfig = store.loadConfig()
        store.saveConfig(config)
        store.saveAccount(PHONE, PASSWORD)
        temporaryCredentials = true
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val callback = object : ActivityLifecycleCallback {
            override fun onActivityLifecycleChanged(activity: Activity, stage: Stage) {
                if (activity is MainActivity && stage == Stage.CREATED) {
                    // 生命周期用例在首次恢复前暂缓自动账号刷新；所有请求仍由严格替身处理。
                    busyField.setBoolean(activity, true)
                }
            }
        }
        lifecycleCallback = callback
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }

    private fun launchServiceActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            busyField.setBoolean(activity, true)
            activity.findViewById<View>(R.id.tab_workbench).performClick()
        }
    }

    private fun clickStart() {
        awaitCondition("开始前不得仍有旧服务") { !ListenService.isRunning && !serviceInstancePresent() }
        scenario!!.onActivity { activity ->
            busyField.setBoolean(activity, false)
            refreshStatus(activity)
            val start = activity.findViewById<Button>(R.id.startButton)
            assertTrue("真实开始按钮必须可用", start.isEnabled)
            assertTrue("必须走实际开始按钮回调", start.performClick())
            busyField.setBoolean(activity, true)
        }
    }

    private fun clickStop() {
        scenario!!.onActivity { activity ->
            refreshStatus(activity)
            val stop = activity.findViewById<Button>(R.id.stopButton)
            assertTrue("真实停止按钮必须可用", stop.isEnabled)
            assertTrue("必须走实际停止按钮回调", stop.performClick())
        }
    }

    private fun assertProfileRetained() {
        assertTrue(SessionRepository.hasLiveSession())
        scenario!!.onActivity { activity ->
            activity.findViewById<View>(R.id.tab_account).performClick()
            val refresh = MainActivity::class.java.getDeclaredMethod("refreshAccountHeader")
            refresh.isAccessible = true
            refresh.invoke(activity)
            assertEquals("停止后身份卡仍在", View.VISIBLE,
                activity.findViewById<View>(R.id.profileCard).visibility)
            // 自动账号刷新可能仍在收尾（networkExecutor 回主线程 setNetworkBusy），
            // 按钮状态是收敛值：强制空闲并刷新后短暂轮询，而不是对瞬态做一次性断言。
            var enabled = false
            for (attempt in 0 until 30) {
                busyField.setBoolean(activity, false)
                refreshStatus(activity)
                enabled = activity.findViewById<Button>(R.id.startButton).isEnabled
                if (enabled) break
                SystemClock.sleep(100)
            }
            assertTrue("停止不是注销，开始按钮必须能恢复", enabled)
            busyField.setBoolean(activity, true)
            activity.findViewById<View>(R.id.tab_workbench).performClick()
        }
    }

    private fun refreshStatus(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("refreshListenStatus").apply {
            isAccessible = true
        }.invoke(activity)
    }

    private fun serviceInstancePresent(): Boolean {
        val field = ListenService::class.java.getDeclaredField("instanceRef").apply { isAccessible = true }
        return (field.get(null) as? java.lang.ref.WeakReference<*>)?.get() != null
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val matched = device.wait(Condition<UiDevice, Boolean> { condition() }, 8_000)
        assertTrue(message, matched == true)
    }

    private fun verifyNetworkIsolation() {
        assertTrue("仅允许 debuggable 目标", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val uids = setOf(context.applicationInfo.uid, instrumentation.context.applicationInfo.uid)
        val marker = device.executeShellCommand("cat /data/local/tmp/dpgrabber-ui-firewall.txt")
        for (line in listOf("targetUid=${context.applicationInfo.uid}",
            "testUid=${instrumentation.context.applicationInfo.uid}", "ipv4=blocked", "ipv6=blocked")) {
            assertTrue("缺少网络隔离标记", marker.lineSequence().any { it.trim() == line })
        }
        for (table in listOf("iptables", "ip6tables")) {
            val rules = device.executeShellCommand("su 0 $table -S OUTPUT")
            for (uid in uids) assertTrue("两 UID、两族网络必须拒绝全出口", rules.lineSequence().any {
                it.trim() in setOf("-A OUTPUT -m owner --uid-owner $uid -j REJECT",
                    "-A OUTPUT -m owner --uid-owner $uid -j DROP",
                    "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp-port-unreachable",
                    "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp6-port-unreachable")
            })
        }
    }

    private class StrictTransport(private val allPaths: MutableList<String>) : HttpTransport {
        val paths = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()
        val polled = CountDownLatch(1)
        val confirmEntered = CountDownLatch(1)
        val releaseConfirm = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val cancelObserved = CountDownLatch(1)
        @Volatile var holdConfirm = false
        @Volatile var holdPoll = false
        @Volatile var cancelled = false

        override fun cancelAll() { cancelled = true; cancelObserved.countDown() }

        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                             contentType: String?, timeoutMs: Long): HttpResponse {
            val uri = URI(url)
            val path = uri.path
            if (uri.scheme != "https" || uri.host != ApiClient.API_HOST || cancelled) {
                unexpected.add("传输隔离或取消约束被违反")
                throw AssertionError("严格替身拒绝未知请求")
            }
            paths.add(path)
            allPaths.add(path)
            val response = when {
                path == LOGIN_PATH && method == "POST" ->
                    """{"code":0,"data":{"token":"synthetic-base-token","shopId":105}}"""
                path == "/s/duopei-account/list-shop-account" && method == "GET" ->
                    """{"code":0,"data":[{"accountId":"synthetic-account","shopId":92}]}"""
                path == "/s/duopei-account/switch-account" && method == "POST" ->
                    """{"code":0,"data":{"token":"synthetic-shop-token"}}"""
                path == CLERK_PATH && method == "GET" ->
                    """{"code":0,"clerk":{"id":7,"shopId":92,"status":1,"randomOrderSwitch":true}}"""
                path == "/s/c/clerk/getItemSwitchList" && method == "POST" ->
                    """{"code":0,"itemSwitchList":[{"id":465,"name":"语音"}],"clerkItemSwitchIds":[465]}"""
                path == "/s/c/statistics/statisticsClerkOrder" && method == "GET" ->
                    """{"code":0,"data":{"orderCount":0,"orderAmount":0,"continuedAmount":0,"continuedCount":0,"continuedUserCount":0}}"""
                path == "/s/c/order/list" && method == "GET" ->
                    """{"code":0,"data":{"list":[],"total":0}}"""
                path == POLL_PATH && method == "GET" -> {
                    polled.countDown()
                    if (holdPoll) awaitRelease(releasePoll)
                    if (holdConfirm) """{"code":0,"list":[{"id":"synthetic-order","totalAmount":2500}]}"""
                    else """{"code":0,"list":[]}"""
                }
                path == CONFIRM_PATH && method == "POST" && holdConfirm -> {
                    confirmEntered.countDown()
                    awaitRelease(releaseConfirm)
                    """{"code":0}"""
                }
                else -> {
                    unexpected.add("未知端点或动作")
                    throw AssertionError("严格替身不允许网络回退")
                }
            }
            return HttpResponse(200, response.toByteArray(Charsets.UTF_8))
        }

        private fun awaitRelease(gate: CountDownLatch) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                try { released = gate.await(100, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { /* 模拟取消后仍迟到的响应。 */ }
            }
            check(released) { "响应闸门未释放" }
        }
    }

    companion object {
        private const val PHONE = "10000000000"
        private const val OTHER_PHONE = "10000000001"
        private const val PASSWORD = "synthetic-password"
        private const val LOGIN_PATH = "/s/duopei-account/login"
        private const val CLERK_PATH = "/s/c/clerk/get"
        private const val POLL_PATH = "/s/c/order/randomList"
        private const val CONFIRM_PATH = "/s/c/order/confirm"
        private const val READBACK_PATH = "/s/c/order/get"
    }
}
