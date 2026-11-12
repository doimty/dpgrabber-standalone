package com.doimty.dpgrabber.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.OkHttpTransport
import com.doimty.dpgrabber.databinding.ActivityMainBinding
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.service.ListenService
import com.doimty.dpgrabber.service.LogBus

/**
 * Main screen: login, listen settings, start/stop, run log.
 * All networking happens on background threads / in the foreground
 * service; this activity only reads/writes settings and observes logs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CredentialStore

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Permission only affects whether the foreground notification
            // shows; the service starts regardless.
            startListening()
        }

    private val logListener: (LogBus.Line) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CredentialStore(this)

        if (!store.encrypted) {
            binding.credentialsWarning.visibility = View.VISIBLE
        }
        loadFieldsFromStore()
        refreshListenStatus()

        binding.loginButton.setOnClickListener { verifyLogin() }
        binding.saveSettingsButton.setOnClickListener { saveSettings() }
        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener {
            ListenService.stop(this)
            refreshListenStatus()
        }
        binding.clearLogButton.setOnClickListener {
            LogBus.clear()
            binding.logView.text = getString(R.string.log_empty)
        }
    }

    override fun onResume() {
        super.onResume()
        LogBus.addListener(logListener)
        binding.logView.text = ""
        for (line in LogBus.snapshot()) appendLog(line)
        if (LogBus.snapshot().isEmpty()) {
            binding.logView.text = getString(R.string.log_empty)
        }
        // Note: fields are NOT reloaded here — reloading would discard
        // unsaved edits when returning from the advanced settings screen.
        refreshListenStatus()
    }

    override fun onPause() {
        super.onPause()
        LogBus.removeListener(logListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ADVANCED, Menu.NONE, R.string.menu_advanced)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_ADVANCED) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadFieldsFromStore() {
        val config = store.loadConfig()
        binding.phoneInput.setText(store.loadPhone())
        binding.passwordInput.setText(store.loadPassword())
        binding.minAmountInput.setText(trimNumber(config.minAmount))
        binding.maxAmountInput.setText(trimNumber(config.maxAmount))
        binding.userDelayInput.setText(config.userDelaySec.toString())
        binding.pollIntervalInput.setText(trimNumber(config.pollIntervalSec))
        binding.memoModeSpinner.setSelection(config.memoMode.coerceIn(0, 1))
        binding.memoKeywordsInput.setText(config.memoKeywords.joinToString("\n"))
        binding.blacklistInput.setText(config.blacklist.joinToString("\n"))
    }

    private fun readSettingsIntoConfig(): AppConfig {
        val current = store.loadConfig()
        val lines = { text: CharSequence ->
            text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        return current.copy(
            phone = binding.phoneInput.text.toString().trim(),
            password = binding.passwordInput.text.toString(),
            minAmount = binding.minAmountInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("最低金额必须是数字"),
            maxAmount = binding.maxAmountInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("最高金额必须是数字"),
            userDelaySec = binding.userDelayInput.text.toString().toIntOrNull()
                ?: throw IllegalArgumentException("延时必须是整数"),
            pollIntervalSec = binding.pollIntervalInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("轮询间隔必须是数字"),
            memoMode = binding.memoModeSpinner.selectedItemPosition,
            memoKeywords = lines(binding.memoKeywordsInput.text),
            blacklist = lines(binding.blacklistInput.text)
        ).validate()
    }

    private fun saveSettings(showToast: Boolean = true): Boolean {
        return try {
            val config = readSettingsIntoConfig()
            store.saveConfig(config)
            store.saveAccount(config.phone, config.password)
            if (showToast) Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.settings_invalid) + "：" + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /** "登录验证": runs the full login flow once on a background thread. */
    private fun verifyLogin() {
        val phone = binding.phoneInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.need_login_first, Toast.LENGTH_SHORT).show()
            return
        }
        binding.loginStatus.text = getString(R.string.login_checking)
        binding.loginButton.isEnabled = false
        Thread {
            try {
                val config = store.loadConfig()
                val client = ApiClient(
                    aesKeyHex = config.aesKeyHex,
                    aesIvHex = config.aesIvHex,
                    aesKeyHexAlt = config.aesKeyHexAlt,
                    aesIvHexAlt = config.aesIvHexAlt,
                    verifyTls = config.verifyTls,
                    transport = OkHttpTransport(config.verifyTls)
                )
                val session = client.sessionForAccount(phone, password, config.shopId)
                val clerk = try {
                    ApiClient.extractClerk(client.clerkGet(session))
                } catch (e: ApiError) {
                    null
                }
                store.saveAccount(phone, password)
                val name = clerk?.optString("name", "") ?: ""
                val id = clerk?.optInt("id", 0) ?: 0
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    binding.loginStatus.text = getString(R.string.login_success) +
                        "（sid=${session.sid}" +
                        (if (name.isNotEmpty()) "，$name ID：$id" else "") + "）"
                    Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiError) {
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：" + e.messageText
                }
            } catch (e: Exception) {
                // Unexpected failure (e.g. malformed JSON): never leave
                // the button disabled and the status stuck on "checking".
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：" + (e.message ?: e.toString())
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun onStartClicked() {
        if (!saveSettings(showToast = false)) return
        if (store.loadPhone().isEmpty() || store.loadPassword().isEmpty()) {
            Toast.makeText(this, R.string.need_login_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startListening()
    }

    private fun startListening() {
        ListenService.start(this)
        refreshListenStatus()
    }

    private fun refreshListenStatus() {
        binding.listenStatus.text = if (ListenService.isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_idle)
        }
        binding.startButton.isEnabled = !ListenService.isRunning
        binding.stopButton.isEnabled = ListenService.isRunning
    }

    private fun appendLog(line: LogBus.Line) {
        if (binding.logView.text.toString() == getString(R.string.log_empty)) {
            binding.logView.text = ""
        }
        val prefix = when (line.level) {
            LogLevel.INFO -> "INFO"
            LogLevel.WARNING -> "WARN"
            LogLevel.ERROR -> "ERROR"
            LogLevel.SUCCESS -> "OK"
            LogLevel.DEBUG -> "DEBUG"
        }
        binding.logView.append("[${line.time}] [$prefix] ${line.text}\n")
        // Keep the newest line visible.
        val scroll = binding.logView.parent
        if (scroll is android.widget.ScrollView) {
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun trimNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    companion object {
        private const val MENU_ADVANCED = 1
    }
}
