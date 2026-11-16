package com.doimty.dpgrabber

import android.content.pm.ApplicationInfo
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.fail

/** 仅隔离环境运行；设置页不启动监听，严格替身拒绝任何传输。 */
@RunWith(AndroidJUnit4::class)
class SettingsParityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = CredentialStore(context)
    private val factoryField = SessionRepository::class.java.getDeclaredField("transportFactory").apply {
        isAccessible = true
    }
    private var previousFactory: Any? = null
    private var previousConfig: AppConfig? = null
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val baseConfig = AppConfig(
        shopId = 50,
        confirmWaitByShop = mapOf("50" to 3.0, "92" to 5.0),
        globalWaitSec = 2.0
    )

    @Before
    fun prepare() {
        verifyNetworkIsolation()
        assertTrue("仅允许空账号的隔离验收环境", store.loadPhone().isEmpty() && store.loadPassword().isEmpty())
        assertFalse("不得接管监听服务", com.doimty.dpgrabber.service.ListenService.isRunning)
        SessionRepository.invalidate()
        previousFactory = factoryField.get(SessionRepository)
        factoryField.set(SessionRepository, { ForbiddenTransport() })
        previousConfig = store.loadConfig()
        store.saveConfig(baseConfig)
    }

    @After
    fun restore() {
        try {
            scenario?.close()
            assertFalse(com.doimty.dpgrabber.service.ListenService.isRunning)
            SessionRepository.invalidate()
            previousConfig?.let(store::saveConfig)
        } finally {
            previousFactory?.let { factoryField.set(SessionRepository, it) }
        }
    }

    @Test
    fun offsetBoundsAndTableOverridesSurviveRealSaveAndRecreate() {
        launch()
        setText(R.id.autoWaitOffsetInput, "0.5")
        assertPreview("等待表 3 秒 + 微调 0.5 秒 = 3.5 秒")
        assertFalse(input(R.id.globalWaitInput).isEnabled)
        assertEquals("2", input(R.id.globalWaitInput).text.toString())
        setText(R.id.busyCooldownInput, "75")
        save()

        launch()
        assertEquals("0.5", input(R.id.autoWaitOffsetInput).text.toString())
        assertEquals("75", input(R.id.busyCooldownInput).text.toString())
        assertPreview("等待表 3 秒 + 微调 0.5 秒 = 3.5 秒")
        setText(R.id.autoWaitOffsetInput, "-0.5")
        assertPreview("等待表 3 秒 + 微调 -0.5 秒 = 2.5 秒")
        save()

        launch()
        setText(R.id.shopIdInput, "92")
        assertPreview("等待表 5 秒；此店铺不应用微调")
        assertTrue("92 的文字不得暗示微调有效", !preview().contains("微调 -0.5 秒 ="))
        assertFalse(input(R.id.globalWaitInput).isEnabled)
        save()
        val saved = store.loadConfig()
        assertEquals(-0.5, saved.autoWaitOffset, 0.0)
        assertEquals(75, saved.busyCooldownSec)
        assertEquals(2.0, saved.globalWaitSec, 0.0)
        assertEquals(3.0, saved.confirmWaitByShop["50"] ?: error("50 等待丢失"), 0.0)
    }

    @Test
    fun globalFallbackActualShopAndInvalidDraftStayAccurate() {
        launch()
        setText(R.id.shopWaitInput, "50=3\n92=5")
        setText(R.id.shopIdInput, "999")
        assertPreview("优先店铺 999（实际以登录店铺为准）：使用全局等待 2 秒；此店铺不应用微调")
        assertTrue(input(R.id.globalWaitInput).isEnabled)
        setText(R.id.autoWaitOffsetInput, "0.1234")
        assertEquals("请完成等待设置", preview())
        assertTrue(input(R.id.globalWaitInput).isEnabled)
        save()
        assertEquals("无效草稿不得保存", 0.0, store.loadConfig().autoWaitOffset, 0.0)

        setText(R.id.autoWaitOffsetInput, "0")
        val before = SessionRepository.revision
        SessionRepository.forAccount(store.loadConfig(), "", "").session =
            com.doimty.dpgrabber.core.Session("synthetic-settings-token", "50")
        assertTrue(SessionRepository.revision > before)
        setText(R.id.shopIdInput, "999")
        assertPreview("当前登录店铺 50；保存后优先店铺 999：等待表 3 秒")
        // 会话保持断言只针对非连接字段；恢复原优先店铺，避免将真实换店误判为纯规则修改。
        setText(R.id.shopIdInput, "50")
        val revision = SessionRepository.revision
        setText(R.id.autoWaitOffsetInput, "0.25")
        setText(R.id.busyCooldownInput, "90")
        save()
        assertEquals("非连接字段不得注销会话", revision, SessionRepository.revision)
        assertTrue(SessionRepository.hasLiveSession())
        assertEquals(90, store.loadConfig().busyCooldownSec)
        assertEquals(0.25, store.loadConfig().autoWaitOffset, 0.0)
    }

    @Test
    fun newExplanationWrapsAndAllNewControlsMeetTouchTarget() {
        launch()
        val minimum = 48 * context.resources.displayMetrics.density
        for (id in listOf(R.id.autoWaitOffsetInput, R.id.busyCooldownInput, R.id.waitEffectiveText)) {
            val view = scenarioView<View>(id)
            assertTrue("新控件必须满足 48dp 触控高度", view.height >= minimum - 0.01)
        }
        setText(R.id.autoWaitOffsetInput, "0.5")
        assertTrue("说明须完整可读并可换行", previewView().lineCount >= 1)
        assertTrue("说明使用主题文字色", previewView().textColors.defaultColor != 0)
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    private fun setText(id: Int, value: String) {
        scenario!!.onActivity { activity ->
            activity.findViewById<EditText>(id).setText(value)
        }
        instrumentation.waitForIdleSync()
    }

    private fun input(id: Int): EditText = scenarioView(id)
    private fun previewView(): TextView = scenarioView(R.id.waitEffectiveText)
    private fun saveButton(): Button = scenarioView(R.id.saveAdvancedButton)
    private fun preview(): String = previewView().text.toString()

    private fun assertPreview(fragment: String) {
        assertTrue("说明缺少：$fragment；实际：${preview()}", preview().contains(fragment))
    }

    private fun save() {
        scenario!!.onActivity { it.findViewById<Button>(R.id.saveAdvancedButton).performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun <T : View> scenarioView(id: Int): T {
        var result: T? = null
        scenario!!.onActivity { activity -> result = activity.findViewById(id) }
        return result ?: error("设置页缺少视图 $id")
    }

    private fun verifyNetworkIsolation() {
        assertTrue("仅允许 debuggable 目标", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val uids = setOf(context.applicationInfo.uid, instrumentation.context.applicationInfo.uid)
        val device = UiDevice.getInstance(instrumentation)
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

    private class ForbiddenTransport : HttpTransport {
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                             contentType: String?, timeoutMs: Long): HttpResponse {
            fail("设置页回归禁止真实或替身网络请求")
            error("不可达")
        }
    }
}
