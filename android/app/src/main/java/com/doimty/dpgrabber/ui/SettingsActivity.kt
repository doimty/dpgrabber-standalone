package com.doimty.dpgrabber.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.databinding.ActivitySettingsBinding

/**
 * Advanced settings: preferred shop, timeouts, AES key/IV pairs, TLS.
 * The key/IV values are editable here, mirroring the PC config file;
 * the client tries the primary pair first and falls back to the
 * alternate pair when decryption fails.
 */
class SettingsActivity : AppCompatActivity() {

    private data class SettingsGroup(
        val key: String,
        val content: View,
        val header: View,
        val chevron: View,
        val defaultExpanded: Boolean
    )

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: CredentialStore
    private lateinit var loadedConfig: AppConfig
    private var rollingBack = false
    private val uiPrefs by lazy { getSharedPreferences("dpgrabber_settings", MODE_PRIVATE) }

    private fun settingsGroups() = listOf(
        SettingsGroup("connection", binding.contentConnection, binding.headerConnection,
            binding.chevronConnection, true),
        SettingsGroup("keys", binding.contentKeys, binding.headerKeys, binding.chevronKeys, false),
        SettingsGroup("wait", binding.contentWait, binding.headerWait, binding.chevronWait, true),
        SettingsGroup("run", binding.contentRun, binding.headerRun, binding.chevronRun, true),
        SettingsGroup("push", binding.contentPush, binding.headerPush, binding.chevronPush, true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            window.navigationBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.surface)
            androidx.core.view.WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars =
                resources.getBoolean(R.bool.light_system_bars)
        }
        // 保持整个设置表单在系统栏和键盘上方可滚动，不把 IME 高度叠加到内容底部。
        // padding 只改变可视窗口，ScrollView 的滚动范围与真实表单内容保持一致。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_advanced)
        store = CredentialStore(this)

        val config = store.loadConfig()
        loadedConfig = config
        binding.shopIdInput.setText(config.shopId.toString())
        binding.requestTimeoutInput.setText(trimNumber(config.requestTimeoutSec))
        binding.seenTtlInput.setText(trimNumber(config.seenTtlSec))
        binding.aesKeyInput.setText(config.aesKeyHex)
        binding.aesIvInput.setText(config.aesIvHex)
        binding.aesKeyAltInput.setText(config.aesKeyHexAlt)
        binding.aesIvAltInput.setText(config.aesIvHexAlt)
        binding.shopWaitInput.setText(config.confirmWaitByShop.entries.joinToString("\n") { "${it.key}=${it.value}" })
        binding.globalWaitInput.setText(trimNumber(config.globalWaitSec))
        binding.autoWaitOffsetInput.setText(trimNumber(config.autoWaitOffset))
        binding.busyCooldownInput.setText(config.busyCooldownSec.toString())
        listOf(binding.shopIdInput, binding.shopWaitInput, binding.globalWaitInput,
            binding.autoWaitOffsetInput).forEach { input ->
            input.doAfterTextChanged { updateEffectiveWait() }
        }
        updateEffectiveWait()
        bindGroups()
        binding.runDurationInput.setText(trimNumber(config.runDurationSec))
        binding.restDurationInput.setText(trimNumber(config.restDurationSec))
        binding.autoStopSwitch.isChecked = config.autoStopAfterGrab
        binding.onlineAfterLoginSwitch.isChecked = config.onlineAfterLogin
        binding.pushEnabledSwitch.isChecked = config.pushEnabled
        binding.pushOnSuccessSwitch.isChecked = config.pushOnSuccess
        binding.pushOnFailureSwitch.isChecked = config.pushOnFailure
        binding.pushOnRiskSwitch.isChecked = config.pushOnRisk
        binding.pushWebhookInput.setText(store.loadPushWebhook())
        binding.autoStopSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rollingBack) try { store.saveConfig(store.loadConfig().copy(autoStopAfterGrab = checked)) }
            catch (_: Exception) {
                rollingBack = true
                binding.autoStopSwitch.isChecked = !checked
                rollingBack = false
                Toast.makeText(this, "保存失败，已恢复原状态", Toast.LENGTH_LONG).show()
            }
        }
        binding.onlineAfterLoginSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rollingBack) try { store.saveConfig(store.loadConfig().copy(onlineAfterLogin = checked)) }
            catch (_: Exception) {
                rollingBack = true
                binding.onlineAfterLoginSwitch.isChecked = !checked
                rollingBack = false
                Toast.makeText(this, "保存失败，已恢复原状态", Toast.LENGTH_LONG).show()
            }
        }

        binding.pushTestButton.setOnClickListener {
            Toast.makeText(this, R.string.push_test_hint, Toast.LENGTH_SHORT).show()
        }
        binding.saveAdvancedButton.setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        try {
            val current = store.loadConfig()
            val updated = readWaitDraft(current).copy(
                busyCooldownSec = binding.busyCooldownInput.text.toString().trim().toIntOrNull()
                    ?: throw IllegalArgumentException("冷却须为整数"),
                requestTimeoutSec = binding.requestTimeoutInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("请求超时必须是数字"),
                seenTtlSec = binding.seenTtlInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("去重记忆必须是数字"),
                aesKeyHex = binding.aesKeyInput.text.toString().trim().lowercase(),
                aesIvHex = binding.aesIvInput.text.toString().trim().lowercase(),
                aesKeyHexAlt = binding.aesKeyAltInput.text.toString().trim().lowercase(),
                aesIvHexAlt = binding.aesIvAltInput.text.toString().trim().lowercase(),
                verifyTls = true,
                runDurationSec = binding.runDurationInput.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("运行秒数无效"),
                restDurationSec = binding.restDurationInput.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("休息秒数无效"),
                autoStopAfterGrab = binding.autoStopSwitch.isChecked,
                onlineAfterLogin = binding.onlineAfterLoginSwitch.isChecked,
                pushEnabled = binding.pushEnabledSwitch.isChecked,
                pushOnSuccess = binding.pushOnSuccessSwitch.isChecked,
                pushOnFailure = binding.pushOnFailureSwitch.isChecked,
                pushOnRisk = binding.pushOnRiskSwitch.isChecked
            ).validate()
            if (updated.shopId != current.shopId || updated.aesKeyHex != current.aesKeyHex || updated.aesIvHex != current.aesIvHex || updated.aesKeyHexAlt != current.aesKeyHexAlt || updated.aesIvHexAlt != current.aesIvHexAlt) {
                check(!com.doimty.dpgrabber.service.ListenService.isRunning) { "请先停止监听再修改连接设置" }
                com.doimty.dpgrabber.data.SessionRepository.invalidate()
            }
            store.saveConfig(updated)
            // webhook 单独加密保存；失败只提示，不影响已落盘的配置。
            if (!store.savePushWebhook(binding.pushWebhookInput.text.toString().trim())) {
                Toast.makeText(this, R.string.push_webhook_save_failed, Toast.LENGTH_LONG).show()
            }
            Toast.makeText(this, R.string.advanced_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                when {
                    e is IllegalArgumentException -> "设置无效，请检查范围、小数位及密钥格式"
                    com.doimty.dpgrabber.service.ListenService.isRunning -> "请先停止监听再修改连接设置"
                    else -> "保存失败，请重试"
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readWaitDraft(base: AppConfig): AppConfig {
        val offsetText = binding.autoWaitOffsetInput.text.toString().trim()
        require(offsetText.toBigDecimalOrNull()?.scale()?.let { it <= 3 } == true) {
            "微调最多3位小数"
        }
        return base.copy(
            shopId = binding.shopIdInput.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("店铺 ID 必须是整数"),
            confirmWaitByShop = binding.shopWaitInput.text.toString().lines()
                .filter { it.isNotBlank() }.associate { line ->
                    val parts = line.split("=")
                    require(parts.size == 2) { "店铺等待须为 sid=秒" }
                    parts[0].trim() to (parts[1].trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException("等待必须为数字"))
                },
            globalWaitSec = binding.globalWaitInput.text.toString().trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("全局等待无效"),
            autoWaitOffset = offsetText.toDoubleOrNull()
                ?: throw IllegalArgumentException("等待微调无效")
        )
    }

    private fun updateEffectiveWait() {
        binding.globalWaitInput.isEnabled = true
        binding.globalWaitHintText.text = "仅当当前店铺没有等待表配置时使用；保留此值供其他店铺回退。"
        try {
            val draft = readWaitDraft(loadedConfig).validate()
            // 登录店铺是实际认证对象；只有编辑优先店铺时才展示即将保存的草稿目标。
            val actualSid = com.doimty.dpgrabber.data.SessionRepository.currentShopId()
            val sid = actualSid ?: draft.shopId
            val shopWait = draft.confirmWaitByShop[sid.toString()]
            val baseWait = shopWait ?: draft.globalWaitSec
            val source = if (shopWait != null) "等待表" else "使用全局等待"
            binding.globalWaitInput.isEnabled = shopWait == null
            if (shopWait != null) {
                binding.globalWaitHintText.text = "店铺 $sid 已使用等待表，全局等待不参与本店铺计算；保留此值供其他店铺回退。"
            }
            val detail = if (sid in setOf(50, 105, 1391)) {
                val total = java.math.BigDecimal.valueOf(baseWait)
                    .add(java.math.BigDecimal.valueOf(draft.autoWaitOffset))
                    .max(java.math.BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                "$source ${trimNumber(baseWait)} 秒 + 微调 ${trimNumber(draft.autoWaitOffset)} 秒 = $total 秒"
            } else {
                "$source ${trimNumber(baseWait)} 秒；此店铺不应用微调"
            }
            val scopeLabel = if (actualSid != null) {
                if (draft.shopId != loadedConfig.shopId) {
                    "当前登录店铺 $sid；保存后优先店铺 ${draft.shopId}"
                } else {
                    "当前登录店铺 $sid"
                }
            } else {
                "优先店铺 $sid（实际以登录店铺为准）"
            }
            binding.waitEffectiveText.text = "$scopeLabel：$detail；实际确认时会扣除订单已等待时间。"
        } catch (_: IllegalArgumentException) {
            binding.waitEffectiveText.text = "请完成等待设置"
        }
    }

    private fun bindGroups() {
        for (group in settingsGroups()) {
            val expanded = uiPrefs.getBoolean("settings_group_expanded_${group.key}", group.defaultExpanded)
            applyGroupState(group, expanded)
            group.header.setOnClickListener {
                val next = group.content.visibility != View.VISIBLE
                uiPrefs.edit().putBoolean("settings_group_expanded_${group.key}", next).apply()
                applyGroupState(group, next)
            }
        }
    }

    private fun applyGroupState(group: SettingsGroup, expanded: Boolean) {
        group.content.visibility = if (expanded) View.VISIBLE else View.GONE
        group.chevron.rotation = if (expanded) 0f else -90f
    }

    private fun trimNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString()
    }
}
