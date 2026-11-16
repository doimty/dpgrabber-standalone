package com.doimty.dpgrabber

import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅检查安装包属性与图标资源，不启动页面或访问业务数据。 */
@RunWith(AndroidJUnit4::class)
class DpppBrandInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "compact-light"
    private val output by lazy {
        requireNotNull(context.getExternalFilesDir("ui-review")).apply {
            check(mkdirs() || isDirectory)
        }
    }

    @Before
    fun verifyOfflineTarget() {
        assertTrue("仅允许已知应用包", context.packageName in setOf("com.doimty.dpgrabber", "com.doimty.dpgrabber.preview"))
        assertTrue("仅允许 debuggable 测试目标", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("未知屏幕配置", profile in setOf("compact-light", "compact-dark-large", "wide-light"))
        val targetUid = context.applicationInfo.uid
        val testUid = instrumentation.context.applicationInfo.uid
        val marker = device.executeShellCommand("cat /data/local/tmp/dpgrabber-ui-firewall.txt")
        for (line in listOf("targetUid=$targetUid", "testUid=$testUid", "profile=$profile", "ipv4=blocked", "ipv6=blocked")) {
            assertTrue("缺少离线隔离标记：$line", marker.lineSequence().any { it.trim() == line })
        }
        for (command in listOf("su 0 iptables -S OUTPUT", "su 0 ip6tables -S OUTPUT")) {
            val rules = device.executeShellCommand(command)
            for (uid in setOf(targetUid, testUid)) {
                assertTrue("缺少 $command 的 UID $uid 全出口拒绝", rules.lineSequence().any {
                    it.trim() in setOf(
                        "-A OUTPUT -m owner --uid-owner $uid -j REJECT",
                        "-A OUTPUT -m owner --uid-owner $uid -j DROP",
                        "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp-port-unreachable",
                        "-A OUTPUT -m owner --uid-owner $uid -j REJECT --reject-with icmp6-port-unreachable"
                    )
                })
            }
        }
    }

    @Test
    fun installedBrandAttributes() {
        val pm = context.packageManager
        val app = pm.getApplicationInfo(context.packageName, 0)
        val info = pm.getPackageInfo(context.packageName, 0)
        assertEquals("DPPP", pm.getApplicationLabel(app).toString())
        val description = app.loadDescription(pm)?.toString().orEmpty()
        assertTrue("安装包描述应包含监听功能", description.contains("监听"))
        assertTrue("安装包描述应包含历史查询", description.contains("历史查询"))
        assertEquals(4L, PackageInfoCompat.getLongVersionCode(info))
        val expectedVersion = if (context.packageName.endsWith(".preview")) "1.0.3-preview" else "1.0.3"
        assertEquals(expectedVersion, info.versionName)
    }

    @Test
    fun installedIconsRenderWithBlueSubjectAndWhiteBackground() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        renderAndVerify(icon, "icon", "PackageManager.getApplicationIcon")
        val round = requireNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
        renderAndVerify(round, "round", "R.mipmap.ic_launcher_round")
    }

    private fun renderAndVerify(drawable: Drawable, suffix: String, source: String) {
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            var opaque = 0
            var blue = 0
            var white = 0
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 240) continue
                opaque++
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blueChannel = Color.blue(pixel)
                if (blueChannel > 120 && blueChannel - red > 30 && blueChannel >= green) blue++
                if (red >= 240 && green >= 240 && blueChannel >= 240) white++
            }
            val blueRatio = if (opaque > 0) blue.toDouble() / opaque else 0.0
            val whiteRatio = if (opaque > 0) white.toDouble() / opaque else 0.0
            val name = "$profile-brand-installed-$suffix"
            File(output, "$name.png").outputStream().use {
                assertTrue("PNG 编码失败", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val metadata = JSONObject()
                .put("kind", "drawable_render")
                .put("source", source)
                .put("profile", profile)
                .put("packageName", context.packageName)
                .put("versionName", info.versionName)
                .put("versionCode", PackageInfoCompat.getLongVersionCode(info))
                .put("drawableClass", drawable.javaClass.name)
                .put("image", "$name.png")
                .put("width", size)
                .put("height", size)
                .put("colorStatistics", JSONObject()
                    .put("opaquePixels", opaque)
                    .put("bluePixels", blue)
                    .put("whitePixels", white)
                    .put("blueRatioOfOpaque", blueRatio)
                    .put("whiteRatioOfOpaque", whiteRatio)
                    .put("opaqueAlphaMin", 240)
                    .put("whiteChannelMin", 240)
                    .put("blueRule", "B > 120 && B - R > 30 && B >= G"))
            File(output, "$name-metadata.json").writeText(metadata.toString(2))
            assertTrue("图标不能是空图：$suffix", opaque > pixels.size / 2)
            assertTrue("图标应包含蓝色主体：$suffix，比例=$blueRatio", blueRatio > 0.03 && blueRatio < 0.60)
            assertTrue("图标应以白色为底：$suffix，比例=$whiteRatio", whiteRatio > 0.35)
        } finally {
            bitmap.recycle()
        }
    }
}
