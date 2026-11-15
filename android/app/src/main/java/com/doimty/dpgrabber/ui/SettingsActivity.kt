package com.doimty.dpgrabber.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.databinding.ActivitySettingsBinding

/**
 * Advanced settings: preferred shop, timeouts, AES key/IV pairs, TLS.
 * The key/IV values are editable here, mirroring the PC config file;
 * the client tries the primary pair first and falls back to the
 * alternate pair when decryption fails.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: CredentialStore
    private var rollingBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_advanced)
        store = CredentialStore(this)

        val config = store.loadConfig()
        binding.shopIdInput.setText(config.shopId.toString())
        binding.requestTimeoutInput.setText(trimNumber(config.requestTimeoutSec))
        binding.seenTtlInput.setText(trimNumber(config.seenTtlSec))
        binding.aesKeyInput.setText(config.aesKeyHex)
        binding.aesIvInput.setText(config.aesIvHex)
        binding.aesKeyAltInput.setText(config.aesKeyHexAlt)
        binding.aesIvAltInput.setText(config.aesIvHexAlt)
        binding.verifyTlsSwitch.isChecked = true
        binding.verifyTlsSwitch.isEnabled = false
        binding.shopWaitInput.setText(config.confirmWaitByShop.entries.joinToString("\n") { "${it.key}=${it.value}" })
        binding.globalWaitInput.setText(trimNumber(config.globalWaitSec))
        binding.runDurationInput.setText(trimNumber(config.runDurationSec))
        binding.restDurationInput.setText(trimNumber(config.restDurationSec))
        binding.autoStopSwitch.isChecked = config.autoStopAfterGrab
        binding.onlineAfterLoginSwitch.isChecked = config.onlineAfterLogin
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

        binding.saveAdvancedButton.setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        try {
            val current = store.loadConfig()
            val updated = current.copy(
                shopId = binding.shopIdInput.text.toString().toIntOrNull()
                    ?: throw IllegalArgumentException("店铺 ID 必须是整数"),
                requestTimeoutSec = binding.requestTimeoutInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("请求超时必须是数字"),
                seenTtlSec = binding.seenTtlInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("去重记忆必须是数字"),
                aesKeyHex = binding.aesKeyInput.text.toString().trim().lowercase(),
                aesIvHex = binding.aesIvInput.text.toString().trim().lowercase(),
                aesKeyHexAlt = binding.aesKeyAltInput.text.toString().trim().lowercase(),
                aesIvHexAlt = binding.aesIvAltInput.text.toString().trim().lowercase(),
                verifyTls = true,
                confirmWaitByShop = binding.shopWaitInput.text.toString().lines().filter { it.isNotBlank() }.associate { line ->
                    val parts = line.split("=")
                    require(parts.size == 2) { "店铺等待须为 sid=秒" }
                    parts[0].trim() to (parts[1].trim().toDoubleOrNull() ?: throw IllegalArgumentException("等待必须为数字"))
                },
                globalWaitSec = binding.globalWaitInput.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("全局等待无效"),
                runDurationSec = binding.runDurationInput.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("运行秒数无效"),
                restDurationSec = binding.restDurationInput.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("休息秒数无效"),
                autoStopAfterGrab = binding.autoStopSwitch.isChecked,
                onlineAfterLogin = binding.onlineAfterLoginSwitch.isChecked
            ).validate()
            if (updated.shopId != current.shopId || updated.aesKeyHex != current.aesKeyHex || updated.aesIvHex != current.aesIvHex || updated.aesKeyHexAlt != current.aesKeyHexAlt || updated.aesIvHexAlt != current.aesIvHexAlt) {
                check(!com.doimty.dpgrabber.service.ListenService.isRunning) { "请先停止监听再修改连接设置" }
                com.doimty.dpgrabber.data.SessionRepository.invalidate()
            }
            store.saveConfig(updated)
            Toast.makeText(this, R.string.advanced_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.advanced_invalid, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun trimNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString()
    }
}
