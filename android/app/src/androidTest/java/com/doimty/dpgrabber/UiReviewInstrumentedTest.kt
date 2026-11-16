package com.doimty.dpgrabber

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import com.doimty.dpgrabber.data.AvatarLoader
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var savedAvatarTransport: Any
    private val avatarCalls = AtomicInteger()
    @Volatile private var avatarReply: () -> ByteArray? = { syntheticAvatar(Color.BLUE) }

    @Before
    fun prepare() {
        assertEquals(PACKAGE, context.packageName)
        assertTrue("仅允许 debuggable 测试目标", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("未知屏幕配置", profile in setOf("compact-light", "compact-dark-large", "wide-light"))
        verifyNetworkIsolation()
        val avatarField = AvatarLoader::class.java.getDeclaredField("transport").apply { isAccessible = true }
        savedAvatarTransport = avatarField.get(null)
        // 所有头像请求同样走替身；不存在真实网络回退。
        avatarField.set(null, AvatarLoader.Transport { url ->
            check(URI(url).scheme == "https" && URI(url).host == "avatar.invalid")
            avatarCalls.incrementAndGet()
            avatarReply()
        })
        File(context.cacheDir, "avatars").deleteRecursively()
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
        if (caseName.startsWith("11-history") || caseName.startsWith("12-history")) {
            HistoryStore(context).use { history ->
                history.writableDatabase.delete("outcomes", "entry_key IN (?, ?, ?)",
                    arrayOf("history-legacy-raw-fixture", "history-other-account-fixture", "history-missing-fields-fixture"))
            }
        }
        if (::savedAvatarTransport.isInitialized) AvatarLoader::class.java.getDeclaredField("transport")
            .apply { isAccessible = true }.set(null, savedAvatarTransport)
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
        // 未登录时“开始监听”为禁用态（MainActivity: startButton.isEnabled 依赖 hasValidSession()），
        // 因此不能走 click() 的 enabled 等待；改为断言禁用 + 仍尝试点击，验证不会启动服务、不会发请求。
        scrollTo("startButton")
        val start = device.wait(Until.findObject(By.res(PACKAGE, "startButton")), WAIT_MS)
        assertNotNull("工作台应包含“开始监听”按钮", start)
        assertFalse("未登录时“开始监听”应保持禁用", start!!.isEnabled)
        assertTrue("未登录时应说明禁用原因", textOf("statusHint").contains("请先登录"))
        start.click()
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
        // 登录成功后整块登录表单 GONE，loginStatus 不再在无障碍树中；已登录态由身份卡承载。
        assertTrue("登录成功后应出现身份卡", device.wait(Until.hasObject(By.res(PACKAGE, "profileCard")), WAIT_MS))
        assertFalse("登录成功后登录表单应隐藏", device.hasObject(By.res(PACKAGE, "loginFormContainer")))
        val profileName = textOf("profileName")
        assertTrue("身份卡姓名不应为空", profileName.isNotBlank())
        assertNotEquals("身份卡不应停留在占位符", "已登录店员", profileName)
        // fixture 的 clerk/get 固定返回 name=离线合成店员、id=90001，故姓名行应为「离线合成店员（ID 90001）」。
        assertTrue("身份卡姓名应包含店员 ID（实际：\"$profileName\"）", profileName.contains("ID"))
        assertNull("登录成功后不应再残留手机号输入框", device.findObject(By.res(PACKAGE, "phoneInput")))
        capture("login-verified")
        capture("profile-card")
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
            scrollToText(label)
            capture("history-$artifact")
        }
        scrollToText("未知")
        val unknown = device.wait(Until.findObject(By.text("未知")), WAIT_MS)
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
        // 先在主线程建立真实输入焦点并展开 IME；不直接 setText，输入仍走键盘事件。
        // 避免 IME 重排后 UiObject2 的旧坐标将输入打到另一个位置。
        scrollTo("phoneInput")
        main!!.onActivity { activity ->
            val input = activity.findViewById<android.widget.EditText>(R.id.phoneInput)
            assertTrue("手机号输入框应允许获取焦点", input.requestFocus())
            input.setSelection(input.text.length)
            val ime = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            ime.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        instrumentation.waitForIdleSync()
        // 必须等 IME 窗口真正出现后再输入；仅等主线程 idle 不代表 IME 已完成焦点重排。
        val keyboardShown = device.wait(Until.hasObject(By.pkg("com.android.inputmethod.latin")), WAIT_MS) ||
            device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 3_000)
        assertTrue("应实际展开系统输入法", keyboardShown)
        main!!.onActivity { activity ->
            val input = activity.findViewById<android.widget.EditText>(R.id.phoneInput)
            input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
            assertTrue("键盘展开后手机号输入框应能保持焦点", input.requestFocus())
        }
        instrumentation.waitForIdleSync()
        capture("phone-keyboard-ready")
        try {
            PHONE.forEachIndexed { index, digit ->
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_0 + (digit - '0'))
                instrumentation.waitForIdleSync()
                assertTrue("第 ${index + 1} 个键盘事件应进入手机号控件", device.wait(
                    Until.hasObject(By.res(PACKAGE, "phoneInput").text(PHONE.take(index + 1))), WAIT_MS))
            }
            main!!.onActivity { activity ->
                assertEquals("键盘输入应进入实际手机号控件", PHONE,
                    activity.findViewById<android.widget.EditText>(R.id.phoneInput).text.toString())
            }
        } finally {
            capture("phone-keyboard-input-evidence")
        }
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
        // 默认等待表覆盖 92，全局输入框起始为禁用；先改成不覆盖当前店铺的表使其可编辑。
        scrollTo("shopWaitInput")
        device.findObject(By.res(PACKAGE, "shopWaitInput")).apply { click(); text = "50=3" }
        device.pressBack()
        scrollTo("globalWaitInput")
        device.findObject(By.res(PACKAGE, "globalWaitInput")).apply { click(); text = "2" }
        device.pressBack()
        scrollTo("shopWaitInput")
        device.findObject(By.res(PACKAGE, "shopWaitInput")).apply { click(); text = "92=1.5" }
        device.pressBack()
        awaitText("waitEffectiveText", "等待表 1.5 秒；此店铺不应用微调")
        scrollTo("autoWaitOffsetInput")
        device.findObject(By.res(PACKAGE, "autoWaitOffsetInput")).apply { click(); text = "0.25" }
        device.pressBack()
        awaitText("waitEffectiveText", "等待表 1.5 秒；此店铺不应用微调")
        scrollTo("globalWaitInput")
        assertFalse("店铺有等待表时，应保留但不参与计算全局等待", device.findObject(By.res(PACKAGE, "globalWaitInput")).isEnabled)
        capture("settings-wait-offset-effective")
        scrollTo("busyCooldownInput")
        device.findObject(By.res(PACKAGE, "busyCooldownInput")).apply { click(); text = "45" }
        device.pressBack()
        val before = store.loadConfig().autoStopAfterGrab
        // 分组卡片后开关位于“运行与自动化”组，推送卡片在其下方；滚到最底会越过开关，必须按 id 精确定位。
        scrollTo("autoStopSwitch")
        val autoStop = device.wait(Until.findObject(By.res(PACKAGE, "autoStopSwitch")), WAIT_MS)
        assertNotNull("应能到达自动停止开关", autoStop)
        autoStop!!.click()
        assertTrue("开关视觉状态应随点击更新", device.wait(
            Until.hasObject(By.res(PACKAGE, "autoStopSwitch").checked(!before)), WAIT_MS))
        instrumentation.waitForIdleSync()
        assertEquals("开关点击应即时保存到设置", !before, store.loadConfig().autoStopAfterGrab)
        scrollBottom()
        capture("settings-bottom-edited")
        click("saveAdvancedButton")
        assertTrue("保存后应回到主界面", device.wait(Until.hasObject(By.res(PACKAGE, "bottomNav")), WAIT_MS))
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
        assertEquals(9.0, store.loadConfig().requestTimeoutSec, 0.0)
        assertEquals(2.0, store.loadConfig().globalWaitSec, 0.0)
        assertEquals(mapOf("92" to 1.5), store.loadConfig().confirmWaitByShop)
        assertEquals(0.25, store.loadConfig().autoWaitOffset, 0.0)
        assertEquals(45, store.loadConfig().busyCooldownSec)
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
        scrollTo("busyCooldownInput")
        assertTrue("满额冷却须真实重新加载", device.wait(Until.hasObject(By.res(PACKAGE, "busyCooldownInput").text("45")), WAIT_MS))
        scrollTo("autoWaitOffsetInput")
        assertTrue("等待微调须真实重新加载", device.wait(Until.hasObject(By.res(PACKAGE, "autoWaitOffsetInput").text("0.25")), WAIT_MS))
        awaitText("waitEffectiveText", "等待表 1.5 秒；此店铺不应用微调")
        capture("settings-persisted-top")
        device.pressBack()
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
        assertTrue("返回主页后底部导航应就绪", device.wait(Until.hasObject(By.res(PACKAGE, "bottomNav")), WAIT_MS))
        assertNotNull("“我的”页高级设置入口不可达；直达设置截图仅证明页面本身，不抵消导航失败", entry)
    }

    @Test(timeout = 240_000)
    fun shopControlsCanToggleRepeatedlyWithoutRelogin() {
        caseName = "07-shop-controls"
        store.saveAccount(PHONE, PASSWORD)
        val fixture = injectFixture(Mode.SUCCESS)
        launchMain()
        selectTab("我的")
        click("loginButton")
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "profileCard")), WAIT_MS))
        selectTab("工作台")
        awaitText("accountOverview", "已上线")
        repeat(2) {
            click("offlineButton")
            awaitText("accountOverview", "已下线")
            click("randomOffButton")
            awaitText("accountOverview", "随机单关闭")
            click("onlineButton")
            awaitText("accountOverview", "已上线")
            click("randomOnButton")
            awaitText("accountOverview", "随机单开启")
        }
        // 一次状态读取失败不得把两个方向全部禁用，也不得靠重新登录恢复。
        fixture.failNextClerkRead = true
        click("offlineButton")
        awaitText("accountOverview", "店铺状态读取失败")
        for (id in listOf("onlineButton", "offlineButton", "randomOnButton", "randomOffButton")) {
            val button = device.wait(Until.findObject(By.res(PACKAGE, id)), WAIT_MS)
            assertNotNull("未知状态仍应保留操作入口 $id", button)
            assertTrue("未知状态不得锁死操作 $id", button!!.wait(Until.enabled(true), WAIT_MS))
        }
        click("onlineButton")
        awaitText("accountOverview", "已上线")
        capture("repeated-toggles-without-relogin")
        assertEquals("多次切换和读取失败后只能有最初那次登录", 1,
            fixture.requests.count { it.getString("path") == "/s/duopei-account/login" })
        assertEquals("上线与下线应各执行三次", 6,
            fixture.requests.count { it.getString("path") == "/s/c/clerk/updateStatus" })
        assertEquals("随机单开关应各执行两次", 4,
            fixture.requests.count { it.getString("path") == "/s/c/clerk/updateRandomOrderSwitch" })
        assertTrue(SessionRepository.hasLiveSession())
        assertFalse(ListenService.isRunning)
    }

    @Test(timeout = 180_000)
    fun itemTypeSelectionPersistsAccountAndShopScope() {
        caseName = "10-item-types"
        store.saveAccount(PHONE, PASSWORD)
        val fixture = injectFixture(Mode.SUCCESS)
        launchMain()
        selectTab("我的")
        click("loginButton")
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "profileCard")), WAIT_MS))
        selectTab("工作台")
        awaitText("accountOverview", "已上线")
        awaitText("itemTypesHint", "已启用 3 类")
        click("itemTypesButton")
        assertTrue(device.wait(Until.hasObject(By.text("接单类型")), WAIT_MS))
        val video = device.wait(Until.findObject(By.text("视频")), WAIT_MS)
        assertNotNull("应通过真实类型对话框操作“视频”", video)
        video!!.click()
        // 对话框保持打开；轮询本地持久化而不是依赖后台资料刷新。
        val persisted = run {
            val deadline = SystemClock.elapsedRealtime() + WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (store.loadConfig().itemSwitchIds == listOf(465, 2667)) return@run true
                SystemClock.sleep(100)
            }
            false
        }
        assertTrue("平台确认后应立即保存类型选择", persisted)
        val saved = store.loadConfig()
        assertEquals(listOf(465, 2667), saved.itemSwitchIds)
        assertEquals(HistoryStore.accountKey(PHONE), saved.itemSwitchAccountKey)
        assertEquals("92", saved.itemSwitchShopId)
        assertTrue(fixture.updatedItems.any {
            it.getInt("switchId") == 2666 && it.getJSONArray("switchIds").toString() == "[465,2667]"
        })
        val done = device.wait(Until.findObject(By.res("android", "button1")), WAIT_MS)
        assertNotNull("完成保存后应能关闭类型对话框", done)
        done!!.click()
        instrumentation.waitForIdleSync()
        awaitText("itemTypesHint", "已启用 2 类")
        capture("item-types-selection-saved")
        // 另一个账号不能继承此账号、此店铺的类型筛选。
        val other = store.loadConfig().copy(itemSwitchAccountKey = HistoryStore.accountKey("00000000001"))
        assertFalse(com.doimty.dpgrabber.core.orderFilterFromConfig(other,
            com.doimty.dpgrabber.core.ItemSwitchScope(HistoryStore.accountKey(PHONE), "92")).enabledItems.isNotEmpty())
        val filter = com.doimty.dpgrabber.core.orderFilterFromConfig(saved,
            com.doimty.dpgrabber.core.ItemSwitchScope(HistoryStore.accountKey(PHONE), "92"))
        assertTrue(filter.check(JSONObject().put("id", "synthetic-video")
            .put("itemId", 2666).put("totalAmount", 1000)).first.not())
        assertTrue(filter.check(JSONObject().put("id", "synthetic-voice")
            .put("itemId", 465).put("totalAmount", 1000)).first)
    }

    @Test(timeout = 180_000)
    fun avatarImageCacheRecreationAndAbout() {
        caseName = "08-avatar-cache"
        store.saveAccount(PHONE, PASSWORD)
        injectFixture(Mode.SUCCESS)
        launchMain()
        selectTab("我的")
        click("loginButton")
        assertTrue("头像成功后状态行应消失，不留下空白点击区域",
            device.wait(Until.gone(By.res(PACKAGE, "avatarStatusText")), WAIT_MS))
        assertActualAvatar()
        val downloads = avatarCalls.get()
        assertTrue("应下载真实合成 PNG 字节", downloads > 0)
        main!!.recreate()
        selectTab("我的")
        assertTrue("头像成功后状态行应消失，不留下空白点击区域",
            device.wait(Until.gone(By.res(PACKAGE, "avatarStatusText")), WAIT_MS))
        assertActualAvatar()
        assertEquals("重建必须复用缓存，不应重复下载", downloads, avatarCalls.get())
        capture("avatar-after-recreate")
        scrollTo("aboutVersionText")
        val packageInfo = context.packageManager.getPackageInfo(PACKAGE, 0)
        val expectedVersion = "版本 ${packageInfo.versionName.orEmpty()}（${if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()}）"
        awaitTextExact("aboutVersionText", expectedVersion)
        awaitTextExact("aboutNameText", "DPPP")
        awaitTextExact("aboutBuildText", "Android 客户端")
        awaitText("aboutUsageText", "处理记录")
        assertTrue("正式关于文案应包含处理记录", textOf("aboutUsageText").contains("处理记录"))
        val appInfo = context.packageManager.getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA)
        assertEquals("DPPP", appInfo.loadLabel(context.packageManager).toString())
        assertTrue("应用描述应包含品牌名", appInfo.loadDescription(context.packageManager)?.toString()?.contains("DPPP") == true)
        assertTrue("应用图标资源必须存在", appInfo.icon != 0)
        assertNotNull("应用图标必须可加载", appInfo.loadIcon(context.packageManager))
        val roundIcon = context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            var resource = 0
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "application") {
                    resource = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "roundIcon", 0)
                    break
                }
                parser.next()
            }
            resource
        }
        assertTrue("清单必须声明圆形图标", roundIcon != 0)
        assertNotNull("圆形图标必须可加载", context.packageManager.getDrawable(PACKAGE, roundIcon, appInfo))
        capture("about-runtime-version")
        // 坏缓存不得被永久复用；必须回退下载，且真实 PNG 不依赖 Content-Type。
        val cache = File(context.cacheDir, "avatars").walkTopDown().first { it.extension == "png" }
        cache.writeText("<html>不是图片</html>")
        val loader = AvatarLoader(context.cacheDir)
        assertNotNull(loader.load(HistoryStore.accountKey(PHONE), 90001,
            "https://avatar.invalid/avatar_90001.png?v=1"))
        assertTrue(avatarCalls.get() > downloads)
        assertEquals("https://avatar.invalid/a.png?sig=a%2Fb", AvatarLoader.normalizeUrl("http://avatar.invalid/a.png?sig=a%2Fb"))
        assertEquals("https://avatar.invalid/a.png", AvatarLoader.normalizeUrl("//avatar.invalid/a.png"))
        assertEquals(ApiClient.BASE_URL + "/avatars/a.png", AvatarLoader.normalizeUrl("/avatars/a.png"))
        assertNull(AvatarLoader.normalizeUrl("file:///private/avatar.png"))
        assertNull(AvatarLoader.normalizeUrl("https://user:password@avatar.invalid/a.png"))
        avatarReply = { ByteArray(2 * 1024 * 1024 + 1) }
        assertNull("超过2MB的内容必须拒绝", loader.load("size-boundary", 90001, "https://avatar.invalid/too-large.png"))
        avatarReply = { "<html>伪装成图片的错误页</html>".toByteArray() }
        assertNull("无论响应标签如何，HTML不能成为头像", loader.load("html-boundary", 90001, "https://avatar.invalid/error.png"))
        avatarReply = { syntheticAvatar(Color.BLUE) }
        // 真正失去会话时，重新出现的表单不能残留“登录成功”。
        SessionRepository.invalidate()
        main!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("refreshAccountHeader")
                .apply { isAccessible = true }.invoke(activity)
        }
        selectTab("我的")
        awaitText("loginStatus", "会话已失效")
        assertFalse(textOf("loginStatus").contains("登录成功"))
    }

    @Test(timeout = 180_000)
    fun avatarFailureRetryAndOldAccountResultCannotReturn() {
        caseName = "09-avatar-retry-isolation"
        avatarReply = { "<html>不是图片</html>".toByteArray() }
        store.saveAccount(PHONE, PASSWORD)
        injectFixture(Mode.SUCCESS)
        launchMain()
        selectTab("我的")
        click("loginButton")
        awaitText("avatarStatusText", "暂不可用")
        main!!.onActivity { activity ->
            assertFalse(activity.findViewById<TextView>(R.id.accountAvatarText).background is RoundedBitmapDrawable)
        }
        avatarReply = { syntheticAvatar(Color.BLUE) }
        click("avatarStatusText")
        assertTrue("头像成功后状态行应消失，不留下空白点击区域",
            device.wait(Until.gone(By.res(PACKAGE, "avatarStatusText")), WAIT_MS))
        assertActualAvatar()
        // 新版本的头像请求暂停，注销换号后再放行旧请求，证明不会串图。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        avatarReply = {
            entered.countDown()
            // 故意忽略中断，模拟网络下载在注销后才完成。
            var ready = false
            while (!ready) try { release.await(45, TimeUnit.SECONDS); ready = true } catch (_: InterruptedException) { }
            returned.countDown()
            syntheticAvatar(Color.RED)
        }
        main!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("updateClerkProfile", JSONObject::class.java)
                .apply { isAccessible = true }.invoke(activity, JSONObject().put("id", 90001)
                    .put("name", "旧合成店员").put("avatarUrl", "https://avatar.invalid/old-delayed.png"))
        }
        assertTrue(entered.await(8, TimeUnit.SECONDS))
        try {
            click("logoutButton")
            val confirm = device.wait(Until.findObject(By.res("android", "button1")), WAIT_MS)
            assertNotNull(confirm)
            confirm!!.click()
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "phoneInput")), WAIT_MS))
            assertFalse(textOf("loginStatus").contains("登录成功"))
            val otherPhone = "00000000001"
            store.saveAccount(otherPhone, PASSWORD)
            avatarReply = { syntheticAvatar(Color.GREEN) }
            val fixture = injectFixture(Mode.SUCCESS, phone = otherPhone)
            fixture.clerkId = 90002
            main!!.onActivity {
                it.findViewById<android.widget.EditText>(R.id.phoneInput).setText(otherPhone)
                it.findViewById<android.widget.EditText>(R.id.passwordInput).setText(PASSWORD)
            }
            click("loginButton")
            awaitText("profileName", "90002")
            release.countDown()
            assertTrue(returned.await(8, TimeUnit.SECONDS))
            assertTrue("头像成功后状态行应消失，不留下空白点击区域",
            device.wait(Until.gone(By.res(PACKAGE, "avatarStatusText")), WAIT_MS))
            main!!.onActivity { activity ->
                val drawable = activity.findViewById<TextView>(R.id.accountAvatarText).background as RoundedBitmapDrawable
                assertEquals("旧账号红色头像不得闪回", Color.GREEN, drawable.bitmap!!.getPixel(0, 0))
            }
            assertFalse(ListenService.isRunning)
            capture("avatar-after-account-change")
        } finally { release.countDown() }
    }

    @Test(timeout = 240_000)
    fun legacyHistoryDetailsClipboardRecreationAndAccountIsolation() {
        caseName = "11-history-legacy-details"
        val account = HistoryStore.accountKey("")
        val id = "LEGACY-RAW-900719925474099312345"
        val no = "000900719925474099312345678901"
        val wechat = "synthetic-wechat-900719925474099312345"
        val memo = "第一行合成备注\n第二行\n第三行\n第四行完整保留\n" + "长备注".repeat(100)
        val payload = JSONObject().put("no", no).put("totalAmount", 0)
            .put("userWechatId", wechat).put("userMemo", memo)
        val stamp = 1704067200000L
        HistoryStore(context).use { history ->
            assertEquals("必须继续使用 v1 数据库", 1, history.writableDatabase.version)
            history.writableDatabase.execSQL("INSERT OR REPLACE INTO outcomes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("history-legacy-raw-fixture", account, "92", id, stamp, "UNKNOWN", "合成待核验原因", payload.toString()))
            history.writableDatabase.execSQL("INSERT OR REPLACE INTO outcomes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("history-other-account-fixture", HistoryStore.accountKey("00000000992"), "92", "OTHER-PRIVATE-ID",
                    stamp, "SUCCESS", "", """{"userWechatId":"other-account-wechat"}"""))
        }
        HistoryStore(context).use { reopened ->
            val saved = reopened.entries(account).single { it.orderId == id }
            assertFalse("旧 payload 不能凭空补下单时间", saved.order.has("createTime"))
            assertEquals(memo, saved.order.getString("userMemo"))
            assertEquals(wechat, saved.order.getString("userWechatId"))
            assertFalse(reopened.entries(account).any { it.orderId == "OTHER-PRIVATE-ID" })
        }
        launchMain()
        selectTab("处理记录")
        awaitText("historySummary", "当前账号共 1 条记录")
        fun assertCard() {
            main!!.onActivity { activity ->
                val list = activity.findViewById<ViewGroup>(R.id.historyList)
                val texts = historyTexts(list)
                assertTrue(texts.any { it.text.toString() == "订单号：$no" })
                assertTrue(texts.any { it.text.toString() == "微信号：$wechat" })
                assertTrue(texts.any { it.text.toString() == "¥0.00" })
                assertTrue(texts.any { it.text.toString().contains("2024-01-01 08:00:00") })
                assertFalse(texts.any { it.text.toString().contains("other-account-wechat") })
                val summary = texts.single { it.contentDescription == "备注摘要，完整内容请查看详情" }
                assertEquals(3, summary.maxLines)
                assertTrue("摘要保留完整原文本，仅视觉省略", summary.text.toString().contains(memo))
            }
        }
        assertCard()
        capture("history-legacy-visible-fields")
        val requestsBefore = fixtures.sumOf { it.requests.size }
        scrollToText("复制微信号")
        device.findObject(By.text("复制微信号")).click()
        instrumentation.waitForIdleSync()
        fun clipboard(): String {
            var value = ""
            instrumentation.runOnMainSync {
                value = context.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            return value
        }
        assertEquals(wechat, clipboard())
        scrollToText("查看详情")
        device.findObject(By.text("查看详情")).click()
        val full = device.wait(Until.findObject(By.desc("完整订单信息")), WAIT_MS)
        assertNotNull("真实详情应可见", full)
        val expected = com.doimty.dpgrabber.engine.HistoryFormatter.details(payload, id, "92", stamp, OutcomeType.UNKNOWN, "合成待核验原因")
        assertEquals("详情必须包含完整长备注和独立订单ID", expected, full!!.text)
        capture("history-details-full-top")
        val detailScroller = UiScrollable(UiSelector().description("订单详情滚动区域"))
        assertTrue(detailScroller.scrollIntoView(UiSelector().text("复制完整订单信息")))
        device.findObject(By.text("复制完整订单信息")).click()
        instrumentation.waitForIdleSync()
        assertEquals(expected, clipboard())
        assertTrue(detailScroller.scrollIntoView(UiSelector().text("复制订单号")))
        device.findObject(By.text("复制订单号")).click()
        instrumentation.waitForIdleSync()
        assertEquals(no, clipboard())
        capture("history-details-full-copy")
        device.findObject(By.text("关闭")).click()
        scrollTop()
        scrollToText("订单号：$no")
        device.findObject(By.text("订单号：$no")).longClick()
        assertTrue(device.wait(Until.hasObject(By.text("只读核验未知结果（不重新提交）")), WAIT_MS))
        assertTrue(device.hasObject(By.text("拉黑客户")))
        assertTrue(device.hasObject(By.text("添加备注关键词")))
        device.pressBack()
        assertEquals("详情、复制和打开菜单不允许调用业务接口", requestsBefore, fixtures.sumOf { it.requests.size })
        main!!.recreate()
        selectTab("处理记录")
        awaitText("historySummary", "当前账号共 1 条记录")
        assertCard()
        main!!.close()
        launchMain()
        selectTab("处理记录")
        awaitText("historySummary", "当前账号共 1 条记录")
        assertCard()
        main!!.close()
        store.saveAccount("00000000993", PASSWORD)
        injectFixture(Mode.SUCCESS, phone = "00000000993")
        launchMain()
        selectTab("处理记录")
        awaitText("historySummary", "当前账号共 0 条")
        main!!.onActivity { activity ->
            assertEquals("换号后旧记录不可见", 0, activity.findViewById<ViewGroup>(R.id.historyList).childCount)
        }
    }

    @Test(timeout = 180_000)
    fun missingHistoryFieldsDisableWechatAndCopyFallbackId() {
        caseName = "12-history-missing-fields"
        val id = "SYNTHETIC-FALLBACK-900719925474099312345"
        HistoryStore(context).use { history ->
            history.writableDatabase.execSQL("INSERT OR REPLACE INTO outcomes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("history-missing-fields-fixture", HistoryStore.accountKey(""), "92", id,
                    1704067200000L, "FAILED", "合成失败原因", """{"no":null,"totalAmount":null,"userWechatId":null,"userMemo":""}"""))
        }
        launchMain()
        selectTab("处理记录")
        awaitText("historySummary", "当前账号共 1 条记录")
        main!!.onActivity { activity ->
            val texts = historyTexts(activity.findViewById(R.id.historyList))
            assertTrue(texts.any { it.text.toString() == "订单号：$id" })
            assertTrue(texts.any { it.text.toString() == "金额未提供" })
            assertTrue(texts.any { it.text.toString() == "微信号：未提供微信号" })
            assertTrue(texts.any { it.text.toString() == "备注：无备注" })
            assertFalse(texts.single { it.text.toString() == "复制微信号" }.isEnabled)
        }
        scrollToText("查看详情")
        device.findObject(By.text("查看详情")).click()
        val details = device.wait(Until.findObject(By.desc("完整订单信息")), WAIT_MS)
        assertNotNull(details)
        assertTrue(details!!.text.contains("订单号：$id\n订单ID：$id"))
        val scroll = UiScrollable(UiSelector().description("订单详情滚动区域"))
        assertTrue(scroll.scrollIntoView(UiSelector().text("复制微信号")))
        assertFalse(device.findObject(By.text("复制微信号")).isEnabled)
        assertTrue(scroll.scrollIntoView(UiSelector().text("复制订单号")))
        device.findObject(By.text("复制订单号")).click()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(id, context.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        device.findObject(By.text("关闭")).click()
        assertTrue("缺字段详情与复制不能发业务请求", fixtures.all { it.requests.isEmpty() })
    }

    private fun historyTexts(view: View): List<TextView> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { historyTexts(view.getChildAt(it)) }
        is TextView -> listOf(view)
        else -> emptyList()
    }

    private fun assertActualAvatar() {
        main!!.onActivity { activity ->
            val view = activity.findViewById<TextView>(R.id.accountAvatarText)
            assertTrue("必须是解码图片而非占位背景", view.background is RoundedBitmapDrawable)
            assertTrue("真实头像不能叠手机号尾号", view.text.isEmpty())
            assertEquals("店员头像已加载", view.contentDescription.toString())
        }
    }

    private fun syntheticAvatar(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        canvas.drawCircle(48f, 40f, 20f, Paint().apply { this.color = Color.WHITE })
        canvas.drawRect(22f, 67f, 74f, 96f, Paint().apply { this.color = Color.WHITE })
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
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
                    }, JSONObject().put("id", "UI-ONLY-${type.name}")
                        .put("no", "SYNTHETIC-NO-${type.name}-00000000000000000001")
                        .put("createTime", System.currentTimeMillis())
                        .put("totalAmount", 25)
                        .put("userWechatId", "fixture-not-a-real-user")
                        .put("userMemo", "离线合成备注\n第二行\n第三行\n第四行")))
            }
            // 原始 v1 行不经新 record() 写入；没有 createTime，不为旧数据伪造字段。
            history.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO outcomes VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("fixture-legacy-v1", HistoryStore.accountKey(PHONE), "92", "LEGACY-ID-ONLY",
                    System.currentTimeMillis() - 172800000L, "SUCCESS", "旧 v1 合成记录",
                    """{"id":"LEGACY-ID-ONLY","totalAmount":null,"userWechatId":"legacy-wechat","userMemo":"旧记录备注"}""")
            )
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
        val settings = UiSelector().resourceId("$PACKAGE:id/settingsScroll")
        if (device.findObject(settings).exists()) return UiScrollable(settings)
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
    private fun textOf(id: String): String = device.findObject(By.res(PACKAGE, id))?.text ?: run {
        var text = ""
        main?.onActivity { text = it.findViewById<TextView?>(R.id::class.java.getField(id).getInt(null))?.text?.toString().orEmpty() }
        text
    }

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
        // 已加载头像的状态行会 GONE，仅保留无障碍描述；仍须核验真实 Bitmap。
        if (id == "avatarStatusText" && fragment == "已加载") {
            assertTrue("必须等待真实头像而非可见成功文案", device.wait(Until.hasObject(
                By.res(PACKAGE, "accountAvatarText").descContains("店员头像已加载")), ms))
            assertActualAvatar()
            return
        }
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
        val updatedItems = CopyOnWriteArrayList<JSONObject>()
        val unknown = CopyOnWriteArrayList<String>()
        @Volatile var failNextClerkRead = false
        private var onlineStatus = 1
        private var randomEnabled = true
        private val selectedItems = mutableSetOf(465, 2666, 2667)
        @Volatile var clerkId = 90001

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
                "/s/c/clerk/get" -> {
                    if (failNextClerkRead) {
                        failNextClerkRead = false
                        return json("""{"code":7,"msg":"合成状态读取失败"}""")
                    }
                    json(JSONObject().put("code", 0).put("clerk", JSONObject()
                        .put("id", clerkId).put("shopId", 92).put("name", "离线合成店员")
                        .put("avatarUrl", "https://avatar.invalid/avatar_$clerkId.png?v=1")
                        .put("status", onlineStatus).put("randomOrderSwitch", randomEnabled)).toString())
                }
                "/s/c/clerk/updateStatus" -> {
                    val form = body?.toString(Charsets.UTF_8).orEmpty().split("&")
                        .associate { part -> part.substringBefore("=") to part.substringAfter("=", "") }
                    onlineStatus = requireNotNull(form["status"]?.toIntOrNull())
                    check(onlineStatus in 1..2)
                    json("""{"code":0}""")
                }
                "/s/c/clerk/updateRandomOrderSwitch" -> {
                    val value = body?.toString(Charsets.UTF_8).orEmpty().substringAfter("randomOrderSwitch=")
                    check(value == "true" || value == "false")
                    randomEnabled = value == "true"
                    json("""{"code":0}""")
                }
                "/s/c/statistics/statisticsClerkOrder" -> json("""{"code":0,"data":{"orderCount":12,"orderAmount":240,"continuedAmount":60,"continuedCount":3,"continuedUserCount":2}}""")
                "/s/c/clerk/getItemSwitchList" -> json(JSONObject().put("code", 0)
                    .put("itemSwitchList", JSONArray(listOf(
                        JSONObject().put("id", 465).put("name", "语音"),
                        JSONObject().put("id", 2666).put("name", "视频"),
                        JSONObject().put("id", 2667).put("name", "游戏"))))
                    .put("clerkItemSwitchIds", JSONArray(selectedItems.toList())).toString())
                "/s/c/clerk/updateItemSwitch" -> {
                    val form = body?.toString(Charsets.UTF_8).orEmpty().split("&")
                        .associate { part -> part.substringBefore("=") to part.substringAfter("=", "") }
                    val selected = java.net.URLDecoder.decode(form["switchIds"], "UTF-8")
                    val ids = if (selected.isNullOrBlank() || selected == "[]") emptyList() else
                        JSONArray(selected).let { array -> (0 until array.length()).map { array.getInt(it) } }
                    val switchId = requireNotNull(form["switchId"]?.toIntOrNull())
                    check(switchId in setOf(465, 2666, 2667))
                    check(ids.toSet().size == ids.size && ids.all { it in setOf(465, 2666, 2667) })
                    synchronized(selectedItems) {
                        selectedItems.clear()
                        selectedItems.addAll(ids)
                    }
                    updatedItems.add(JSONObject().put("switchId", switchId).put("switchIds", JSONArray(ids)))
                    json(JSONObject().put("code", 0).put("clerkItemSwitchIds", JSONArray(ids)).toString())
                }
                else -> throw AssertionError("白名单没有响应：$key")
            }
        }

        private fun json(value: String) = HttpResponse(200, value.toByteArray(Charsets.UTF_8))

        companion object {
            private val ALLOWED = setOf("POST /s/duopei-account/login", "GET /s/duopei-account/list-shop-account",
                "POST /s/duopei-account/switch-account", "GET /s/c/clerk/get", "GET /s/c/statistics/statisticsClerkOrder",
                "POST /s/c/clerk/updateStatus", "POST /s/c/clerk/updateRandomOrderSwitch",
                "POST /s/c/clerk/getItemSwitchList", "POST /s/c/clerk/updateItemSwitch")
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
