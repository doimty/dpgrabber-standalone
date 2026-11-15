package com.doimty.dpgrabber

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.HistoryStore
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.engine.OrderOutcome
import com.doimty.dpgrabber.engine.OutcomeType
import com.doimty.dpgrabber.service.ListenService
import com.doimty.dpgrabber.service.LogBus
import com.doimty.dpgrabber.ui.MainActivity
import com.doimty.dpgrabber.ui.SettingsActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅进入 androidTest APK；正式应用不包含合成账号、传输替身或验收入口。 */
@RunWith(AndroidJUnit4::class)
class UiReviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "compact-light"
    private val output by lazy { File(context.getExternalFilesDir(null), "ui-review").apply { check(mkdirs() || isDirectory) } }
    private val fixtures = mutableListOf<StrictFixture>()
    private val findings = JSONArray()
    private var main: ActivityScenario<MainActivity>? = null
    private var caseName = "setup"
    private lateinit var store: CredentialStore

    @Before
    fun prepare() {
        assertEquals(PACKAGE, context.packageName)
        assertTrue("仅允许 debuggable 测试目标", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("未知屏幕配置", profile in setOf("compact-light", "compact-dark-large", "wide-light"))
        verifyNetworkIsolation()
        assertFalse("测试不得接管运行中的监听", ListenService.isRunning)
        store = CredentialStore(context)
        store.clearAccount()
        store.saveConfig(testConfig())
        injectFixture(Mode.SUCCESS, phone = "", password = "")
        LogBus.clear()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (profile == "compact-dark-large") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    @After
    fun finish() {
        fixtures.forEach { it.release.countDown() }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SettingsActivity>().forEach { it.finish() }
        }
        main?.close()
        if (::store.isInitialized) store.clearAccount()
        val requests = JSONArray()
        fixtures.forEach { fixture -> fixture.requests.forEach { requests.put(it) } }
        File(output, "$profile-$caseName-fixture-requests.json").writeText(requests.toString(2))
        File(output, "$profile-$caseName-findings.json").writeText(findings.toString(2))
        assertTrue("发现未获准的 fixture 请求；没有真实网络回退", fixtures.all { it.unknown.isEmpty() })
        assertFalse("离线验收不得启动监听服务", ListenService.isRunning)
    }

    @Test(timeout = 120_000)
    fun emptyHomeAndStartWithoutAccount() {
        caseName = "01-empty"
        launchMain()
        awaitText("accountOverview", "未登录")
        scrollTop()
        capture("home-top")
        assertEquals("", store.loadPhone())
        assertEquals("", store.loadPassword())
        click("startButton")
        assertFalse(ListenService.isRunning)
        capture("start-without-account")
        assertTrue("无账号操作不得调用任何端点", fixtures.all { it.requests.isEmpty() })
        scrollBottom()
        capture("home-bottom-empty-log")
        selectTab("我的")
        capture("account-empty")
        val phone = device.findObject(By.res(PACKAGE, "phoneInput"))
        assertNotNull("“我的”页应包含手机号输入框", phone)
        // TextInputEditText 空态会把 hint 当作无障碍 text 暴露，故按“空或等于 hint”判定，存储层断言仍是权威依据。
        assertTrue("未登录时手机号应保持为空", isEffectivelyEmpty("phoneInput", "11 位手机号"))
    }

    @Test(timeout = 240_000)
    fun realLoginLoadingStatisticsHistoryAndLog() {
        caseName = "02-populated"
        seedHistory()
        store.saveAccount(PHONE, PASSWORD)
        val fixture = injectFixture(Mode.SUCCESS, gated = true)
        LogBus.post(LogLevel.INFO, "离线合成场景：读取账户与本周统计")
        LogBus.post(LogLevel.WARNING, "合成未知结果保留待核验；不会重新提交")
        LogBus.post(LogLevel.ERROR, "合成风控记录：仅展示，不发送业务请求")
        launchMain()
        selectTab("我的")
        click("loginButton")
        assertTrue("登录请求必须进入受控替身", fixture.entered.await(8, TimeUnit.SECONDS))
        awaitText("loginStatus", "正在登录")
        assertFalse(device.findObject(By.res(PACKAGE, "loginButton")).isEnabled)
        capture("login-loading")
        fixture.release.countDown()
        awaitText("loginStatus", "登录成功")
        capture("login-verified")
        selectTab("工作台")
        awaitText("statisticsOverview", "本周订单 12 单")
        assertTrue(device.findObject(By.res(PACKAGE, "statisticsOverview")).text.contains("25.00%"))
        capture("weekly-statistics")
        awaitTextExact("todaySuccessCount", "1")
        assertTrue("今日概览应说明统计口径", textOf("todayOverview").contains("仅当前账号"))
        capture("history-summary")
        selectTab("接单规则")
        scrollTo("memoKeywordsInput")
        capture("rules-page")
        selectTab("处理记录")
        for ((label, artifact) in listOf("成功" to "success", "失败" to "failed", "风控" to "risk", "跳过" to "skipped")) {
            scrollToText("$label · 店铺")
            capture("history-$artifact")
        }
        scrollToText("未知 · 店铺")
        val unknown = device.wait(Until.findObject(By.textStartsWith("未知 · 店铺")), WAIT_MS)
        assertNotNull("合成未知记录应经 HistoryStore 渲染", unknown)
        unknown!!.longClick()
        assertTrue(device.wait(Until.hasObject(By.text("只读核验未知结果（不重新提交）")), WAIT_MS))
        capture("history-long-press-menu")
        device.pressBack()
        selectTab("我的")
        scrollBottom()
        capture("account-with-logs")
        click("clearLogButton")
        awaitText("logView", "暂无")
        capture("logs-cleared")
        assertTrue(LogBus.snapshot().isEmpty())
        assertTrue(fixture.requests.any { it.getString("path") == "/s/duopei-account/login" })
        assertTrue(fixture.requests.any { it.getString("path") == "/s/c/statistics/statisticsClerkOrder" })
    }

    @Test(timeout = 120_000)
    fun businessRejectionFromRealLoginClick() {
        loginFailure(Mode.BUSINESS_REJECT, "03-business-rejected")
    }

    @Test(timeout = 120_000)
    fun timeoutFromRealLoginClick() {
        loginFailure(Mode.TIMEOUT, "04-network-timeout")
    }

    @Test(timeout = 120_000)
    fun phoneKeyboardContainsOnlySyntheticInput() {
        caseName = "05-keyboard"
        launchMain()
        selectTab("我的")
        scrollTop()
        val phone = device.wait(Until.findObject(By.res(PACKAGE, "phoneInput")), WAIT_MS)
        assertNotNull(phone)
        phone!!.click()
        phone.text = PHONE
        // 赋值后立刻读取会拿到旧的无障碍节点（值尚未提交），改为等待式校验。
        instrumentation.waitForIdleSync()
        assertTrue(
            "手机号未写入输入框",
            device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput").text(PHONE)), WAIT_MS)
        )
        assertTrue("应实际展开系统输入法", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 3_000) ||
            device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
        capture("phone-keyboard-expanded")
        // 该状态下 findObject(...).text 可能为 null，统一走 textOf 安全读取。
        assertEquals(PHONE, textOf("phoneInput"))
        assertEquals("", store.loadPhone())
        device.pressBack()
    }

    @Test(timeout = 180_000)
    fun settingsNavigationSaveReturnAndRecreation() {
        caseName = "06-settings"
        launchMain()
        selectTab("我的")
        // 入口位于“我的”页登录卡底部，窄屏需先滚动；缺失时不中断，继续取证。
        tryScrollTo("advancedSettingsButton")
        val entry = device.wait(Until.findObject(By.text("高级设置")), WAIT_MS)
        if (entry == null) {
            findings.put(JSONObject().put("code", "advanced-settings-entry-unreachable")
                .put("detail", "“我的”页未找到可见的“高级设置”按钮；随后以内部显式 Intent 验收设置页，不代表导航已通过"))
            capture("missing-advanced-entry")
            // 页面本身独立验收，不伪造入口或调用 onOptionsItemSelected。
            openSettingsDirectly()
        } else {
            entry.click()
            instrumentation.waitForIdleSync()
        }
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "shopIdInput")), WAIT_MS))
        capture("settings-top")
        scrollTo("requestTimeoutInput")
        val timeout = device.findObject(By.res(PACKAGE, "requestTimeoutInput"))
        timeout.click()
        timeout.text = "9"
        device.pressBack()
        scrollTo("globalWaitInput")
        device.findObject(By.res(PACKAGE, "globalWaitInput")).apply { click(); text = "2" }
        device.pressBack()
        scrollTo("autoStopSwitch")
        val before = store.loadConfig().autoStopAfterGrab
        device.findObject(By.res(PACKAGE, "autoStopSwitch")).click()
        assertEquals(!before, store.loadConfig().autoStopAfterGrab)
        scrollBottom()
        capture("settings-bottom-edited")
        click("saveAdvancedButton")
        assertTrue("保存后应回到主界面", device.wait(Until.hasObject(By.res(PACKAGE, "bottomNav")), WAIT_MS))
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
        assertEquals(9.0, store.loadConfig().requestTimeoutSec, 0.0)
        assertEquals(2.0, store.loadConfig().globalWaitSec, 0.0)
        capture("settings-saved-return-home")
        main!!.recreate()
        // 重建后底部导航默认回到工作台，必须回到“我的”才能继续验收账号区。
        selectTab("我的")
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
        capture("home-after-actual-recreate")
        // 第二次打开验证配置被实际加载，随后用系统返回键返回。
        openSettingsDirectly()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "shopIdInput")), WAIT_MS))
        scrollTo("requestTimeoutInput")
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "requestTimeoutInput").text("9")), WAIT_MS))
        capture("settings-persisted-top")
        device.pressBack()
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
        assertTrue("返回主页后底部导航应就绪", device.wait(Until.hasObject(By.res(PACKAGE, "bottomNav")), WAIT_MS))
        assertNotNull("“我的”页高级设置入口不可达；直达设置截图仅证明页面本身，不抵消导航失败", entry)
    }

    private fun openSettingsDirectly() {
        main!!.onActivity { it.startActivity(Intent(it, SettingsActivity::class.java)) }
    }

    private fun loginFailure(mode: Mode, name: String) {
        caseName = name
        store.saveAccount(PHONE, PASSWORD)
        val fixture = injectFixture(mode)
        launchMain()
        selectTab("我的")
        click("loginButton")
        // 超时路径要等网络超时回调，大字号档位（1.3x）下 10s 窗口不足，用专用超时。
        awaitText("loginStatus", "登录失败", FAILURE_WAIT_MS)
        assertTrue(device.wait(Until.findObject(By.res(PACKAGE, "loginButton")), WAIT_MS).isEnabled)
        assertNull(currentClient().session)
        capture("login-error")
        assertEquals(1, fixture.requests.size)
        assertEquals("/s/duopei-account/login", fixture.requests.single().getString("path"))
    }

    private fun testConfig() = AppConfig.defaults().copy(
        shopId = 92, aesKeyHex = "11".repeat(32), aesIvHex = "22".repeat(16),
        aesKeyHexAlt = "33".repeat(16), aesIvHexAlt = "44".repeat(16), onlineAfterLogin = false
    ).validate()

    private fun injectFixture(mode: Mode, gated: Boolean = false, phone: String = PHONE, password: String = PASSWORD): StrictFixture {
        val config = store.loadConfig()
        val fixture = StrictFixture(mode, gated)
        fixtures.add(fixture)
        val client = ApiClient(config.aesKeyHex, config.aesIvHex, config.aesKeyHexAlt, config.aesIvHexAlt, true, fixture)
        synchronized(SessionRepository) {
            SessionRepository.invalidate()
            setRepositoryField("client", client)
            setRepositoryField("account", phone to password)
            setRepositoryField("connection", listOf(config.shopId.toString(), config.aesKeyHex, config.aesIvHex, config.aesKeyHexAlt, config.aesIvHexAlt))
        }
        assertSame(client, SessionRepository.forAccount(config, phone, password))
        return fixture
    }

    private fun setRepositoryField(name: String, value: Any) {
        SessionRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(SessionRepository, value)
    }

    private fun currentClient() = SessionRepository.forAccount(store.loadConfig(), PHONE, PASSWORD)

    private fun seedHistory() {
        HistoryStore(context).use { history ->
            OutcomeType.entries.forEachIndexed { index, type ->
                history.record(OrderOutcome(HistoryStore.accountKey(PHONE), "92", "UI-ONLY-${type.name}", "ui-review", "fixture-$index",
                    System.currentTimeMillis() - index * 1000L, type,
                    when (type) {
                        OutcomeType.SUCCESS -> "合成成功：只用于离线界面验收"
                        OutcomeType.FAILED -> "合成失败：平台业务拒绝"
                        OutcomeType.RISK -> "合成风控：暂停操作，不重试"
                        OutcomeType.UNKNOWN -> "合成未知：保留待只读核验"
                        OutcomeType.SKIPPED -> "合成跳过：不符合筛选条件"
                    }, JSONObject().put("id", "UI-ONLY-${type.name}").put("totalAmount", 25)
                        .put("userWechatId", "fixture-not-a-real-user").put("userMemo", "离线合成备注")))
            }
        }
    }

    private fun launchMain() {
        main = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("底部导航缺失：四页界面未加载", device.wait(Until.hasObject(By.res(PACKAGE, "bottomNav")), WAIT_MS))
        assertTrue("工作台页应默认可见", device.wait(Until.hasObject(By.res(PACKAGE, "pageWorkbench")), WAIT_MS))
        main!!.onActivity { activity ->
            val config = activity.resources.configuration
            val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertEquals("必须应用真实夜间资源配置", profile == "compact-dark-large", night)
            if (profile == "compact-dark-large") assertTrue("大字号必须至少 1.3", config.fontScale >= 1.3f)
            if (profile.startsWith("compact")) assertTrue("窄屏必须不超过 360dp", config.screenWidthDp <= 360)
        }
    }

    /** 底部导航切页：同一时刻只有当前页 VISIBLE，切换后等待布局稳定再操作。 */
    private fun selectTab(label: String) {
        val tab = device.wait(Until.findObject(By.text(label)), WAIT_MS)
        assertNotNull("底部导航缺少“$label”标签，无法到达目标页", tab)
        tab!!.click()
        instrumentation.waitForIdleSync()
        device.waitForIdle(TAB_SETTLE_MS)
    }

    /**
     * 当前可见页的滚动容器。页面容器是 NestedScrollView，内容不满一屏时 scrollable=false，
     * 用 UiSelector().scrollable(true) 会匹配不到节点并让 UiScrollable 抛 UiObjectNotFoundException。
     * 因此先按“当前可见的页容器 id”定位（隐藏页不参与，避免误取 GONE 容器），再回退到类名/scrollable。
     */
    private fun pageScroller(): UiScrollable {
        for (page in PAGE_CONTAINERS) {
            val selector = UiSelector().resourceId("$PACKAGE:id/$page")
            if (isOnScreen(selector)) return UiScrollable(selector)
        }
        if (device.hasObject(By.clazz(NESTED_SCROLL_VIEW))) return UiScrollable(UiSelector().className(NESTED_SCROLL_VIEW).instance(0))
        return UiScrollable(UiSelector().scrollable(true).instance(0))
    }

    /** 目标是否已在屏幕上可见（存在但不可见时仍需滚动）。 */
    private fun isOnScreen(selector: UiSelector): Boolean = runCatching {
        val target = device.findObject(selector)
        if (!target.exists()) return@runCatching false
        val bounds = target.visibleBounds
        bounds.width() > 0 && bounds.height() > 0
    }.getOrDefault(false)

    private fun scrollTop() {
        runCatching { pageScroller().scrollToBeginning(24) }
        device.waitForIdle(1_000)
    }

    private fun scrollBottom() {
        runCatching { pageScroller().scrollToEnd(32) }
        device.waitForIdle(1_000)
    }

    private fun scrollTo(id: String) {
        val selector = UiSelector().resourceId("$PACKAGE:id/$id")
        // 目标已可见时直接返回：避免“内容已可见但容器不可滚”时 scrollIntoView 误抛。
        if (isOnScreen(selector)) return
        assertTrue("未能滚动至 $id", runCatching { pageScroller().scrollIntoView(selector) }.getOrDefault(false))
        device.waitForIdle(1_000)
    }

    private fun scrollToText(prefix: String) {
        val selector = UiSelector().textStartsWith(prefix)
        if (isOnScreen(selector)) return
        assertTrue("未能滚动至历史行", runCatching { pageScroller().scrollIntoView(selector) }.getOrDefault(false))
    }

    /** 静默滚动：控件缺失时返回 false，供入口缺失的取证分支继续执行。 */
    private fun tryScrollTo(id: String): Boolean {
        val selector = UiSelector().resourceId("$PACKAGE:id/$id")
        if (isOnScreen(selector)) return true
        return runCatching { pageScroller().scrollIntoView(selector) }.getOrDefault(false)
    }

    /** 安全读取控件文本：节点缺失或 text 为 null 时返回空串，避免 NPE。 */
    private fun textOf(id: String): String = device.findObject(By.res(PACKAGE, id))?.text ?: ""

    /** TextInputEditText 空态会把 hint 当作无障碍 text 暴露，故“空或等于 hint”都算未填写。 */
    private fun isEffectivelyEmpty(id: String, hint: String): Boolean {
        val t = textOf(id)
        return t.isEmpty() || t == hint
    }

    private fun click(id: String) {
        scrollTo(id)
        val view = device.wait(Until.findObject(By.res(PACKAGE, id)), WAIT_MS)
        assertNotNull(view)
        assertTrue("控件未就绪 $id", view!!.wait(Until.enabled(true), WAIT_MS))
        view.click()
        instrumentation.waitForIdleSync()
    }

    /**
     * 文本等待：不依赖控件“当前可滚动可见”（登录状态行在登录前是 GONE，滚动会失败），
     * 先尽力滚动一次，再按文本片段轮询；只有需要交互的场景才用 scrollTo/click。
     */
    private fun awaitText(id: String, fragment: String, ms: Long = WAIT_MS) {
        // 目标可能因 GONE/不可见而无法 scrollIntoView，失败不中断等待。
        runCatching { scrollTo(id) }
        if (device.wait(Until.hasObject(By.res(PACKAGE, id).textContains(fragment)), ms)) return
        fail("$id 未出现期望文本：$fragment（实际：\"${textOf(id)}\"）")
    }

    /** 精确文本等待：计数类控件 text 为纯整数，不能用片段匹配；失败时打印实际值。 */
    private fun awaitTextExact(id: String, expected: String) {
        scrollTo(id)
        if (device.wait(Until.hasObject(By.res(PACKAGE, id).text(expected)), WAIT_MS)) return
        fail("$id 未出现期望文本：$expected（实际：\"${textOf(id)}\"）")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.waitForIdle(1_000)
        val stem = "$profile-$caseName-$name"
        assertTrue("必须生成真实屏幕像素", device.takeScreenshot(File(output, "$stem.png")))
        device.dumpWindowHierarchy(File(output, "$stem.xml"))
        val metadata = JSONObject().put("case", caseName).put("screen", name).put("profile", profile)
            .put("timestampElapsedMs", SystemClock.elapsedRealtime()).put("widthPx", device.displayWidth).put("heightPx", device.displayHeight)
            .put("fixture", "仅合成账号与严格白名单 HttpTransport；IPv4/IPv6 两 UID 全出口拒绝；无真实网络回退")
            .put("settingsDirectLaunch", caseName == "06-settings" && findings.length() > 0)
        val metrics = JSONArray()
        var activityFound = false
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it is MainActivity || it is SettingsActivity }
            if (activity != null) {
                activityFound = true
                val resources = activity.resources
                metadata.put("density", resources.displayMetrics.density.toDouble()).put("densityDpi", resources.displayMetrics.densityDpi)
                    .put("fontScale", resources.configuration.fontScale.toDouble())
                    .put("screenWidthDp", resources.configuration.screenWidthDp)
                    .put("night", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                    .put("activity", activity.javaClass.simpleName)
                collectMetrics(activity.window.decorView, resources.displayMetrics.density, metrics)
            }
        }
        metadata.put("activityResumed", activityFound)
        File(output, "$stem-metadata.json").writeText(metadata.toString(2))
        File(output, "$stem-accessibility.json").writeText(metrics.toString(2))
    }

    private fun collectMetrics(view: View, density: Float, result: JSONArray) {
        val bounds = Rect()
        val visible = view.getGlobalVisibleRect(bounds)
        val id = if (view.id == View.NO_ID) "" else try { view.resources.getResourceName(view.id) } catch (_: Exception) { "" }
        val item = JSONObject().put("id", id).put("class", view.javaClass.simpleName)
            .put("visible", visible && view.visibility == View.VISIBLE).put("clickable", view.isClickable)
            .put("longClickable", view.isLongClickable).put("enabled", view.isEnabled).put("focusable", view.isFocusable)
            .put("boundsPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("widthDp", (view.width / density).toDouble()).put("heightDp", (view.height / density).toDouble())
            .put("minTouchTargetDp", (minOf(view.width, view.height) / density).toDouble())
        if (view is TextView) item.put("textSizeSp", (view.textSize / view.resources.displayMetrics.scaledDensity).toDouble())
            .put("lineCount", view.lineCount).put("ellipsized", (0 until (view.layout?.lineCount ?: 0)).any { view.layout.getEllipsisCount(it) > 0 })
        result.put(item)
        if (view is ViewGroup) for (i in 0 until view.childCount) collectMetrics(view.getChildAt(i), density, result)
    }

    private fun verifyNetworkIsolation() {
        val targetUid = context.applicationInfo.uid
        val testUid = instrumentation.context.applicationInfo.uid
        val marker = device.executeShellCommand("cat /data/local/tmp/dpgrabber-ui-firewall.txt")
        for (line in listOf("targetUid=$targetUid", "testUid=$testUid", "ipv4=blocked", "ipv6=blocked")) {
            assertTrue("缺少离线隔离标记；中止所有请求：$line", marker.lineSequence().any { it.trim() == line })
        }
        for (command in listOf("su 0 iptables -S OUTPUT", "su 0 ip6tables -S OUTPUT")) {
            val rules = device.executeShellCommand(command)
            for (uid in setOf(targetUid, testUid)) {
                assertTrue("缺少 $command 的 UID $uid 全出口拒绝；中止所有请求", rules.lineSequence().any {
                    it.trim() in setOf("-A OUTPUT -m owner --uid-owner $uid -j REJECT", "-A OUTPUT -m owner --uid-owner $uid -j DROP",
                        "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp-port-unreachable",
                        "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp6-port-unreachable")
                })
            }
        }
    }

    private enum class Mode { SUCCESS, BUSINESS_REJECT, TIMEOUT }

    private class StrictFixture(private val mode: Mode, gated: Boolean) : HttpTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (gated) 1 else 0)
        val requests = CopyOnWriteArrayList<JSONObject>()
        val unknown = CopyOnWriteArrayList<String>()

        override fun cancelAll() { release.countDown() }

        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, timeoutMs: Long): HttpResponse {
            val uri = URI(url)
            val path = uri.path
            val key = "$method $path"
            requests.add(JSONObject().put("method", method).put("path", path))
            if (uri.scheme != "https" || uri.host != ApiClient.API_HOST || key !in ALLOWED) {
                unknown.add(key)
                throw AssertionError("未允许的离线请求：$key")
            }
            if (path == "/s/duopei-account/login") {
                entered.countDown()
                check(release.await(45, TimeUnit.SECONDS)) { "受控登录 gate 超时" }
                if (mode == Mode.TIMEOUT) throw SocketTimeoutException("合成网络超时")
                if (mode == Mode.BUSINESS_REJECT) return json("""{"code":401,"msg":"合成业务拒绝"}""")
            }
            return when (path) {
                "/s/duopei-account/login" -> json("""{"code":0,"data":{"token":"ui-fixture-only-not-valid","shopId":50}}""")
                "/s/duopei-account/list-shop-account" -> json("""{"code":0,"data":[{"shopId":92,"accountId":"ui-fixture-shop"}]}""")
                "/s/duopei-account/switch-account" -> json("""{"code":0,"data":{"token":"ui-fixture-switched-not-valid"}}""")
                "/s/c/clerk/get" -> json("""{"code":0,"clerk":{"id":90001,"shopId":92,"name":"离线合成店员","status":1,"randomOrderSwitch":true}}""")
                "/s/c/statistics/statisticsClerkOrder" -> json("""{"code":0,"data":{"orderCount":12,"orderAmount":240,"continuedAmount":60,"continuedCount":3,"continuedUserCount":2}}""")
                else -> throw AssertionError("白名单没有响应：$key")
            }
        }

        private fun json(value: String) = HttpResponse(200, value.toByteArray(Charsets.UTF_8))

        companion object {
            private val ALLOWED = setOf("POST /s/duopei-account/login", "GET /s/duopei-account/list-shop-account",
                "POST /s/duopei-account/switch-account", "GET /s/c/clerk/get", "GET /s/c/statistics/statisticsClerkOrder")
        }
    }

    companion object {
        private const val PACKAGE = "com.doimty.dpgrabber"
        private const val PHONE = "00000000000"
        private const val PASSWORD = "ui-fixture-not-a-real-password"
        private const val WAIT_MS = 10_000L
        /** 登录失败要等网络超时回调，深色大字号档位需要更长窗口。 */
        private const val FAILURE_WAIT_MS = 30_000L
        private const val TAB_SETTLE_MS = 800L
        private const val NESTED_SCROLL_VIEW = "androidx.core.widget.NestedScrollView"
        private val PAGE_CONTAINERS = listOf("pageWorkbench", "pageRules", "pageHistory", "pageAccount")
    }
}
