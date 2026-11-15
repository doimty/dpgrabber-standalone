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
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.data.HistoryStore
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import com.doimty.dpgrabber.databinding.ActivityMainBinding
import com.doimty.dpgrabber.engine.LogLevel
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    @Volatile private var accountGeneration = 0L
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var networkBusy = false
    private var lastRunning = false
    /** 最近一次 saveAccount 是否真正写入本机加密存储。 */
    @Volatile private var credentialPersisted = true
    private var historyGeneration = 0L
    private val historyListener: () -> Unit = { runOnUiThread { refreshHistory() } }

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
        refreshAccountHeader()
        updateLoginStatusVisibility()

        binding.loginButton.setOnClickListener { verifyLogin() }
        binding.onlineButton.setOnClickListener { networkAction { it.updateStatus(1, false) } }
        binding.offlineButton.setOnClickListener { networkAction { it.updateStatus(2, false) } }
        binding.randomOnButton.setOnClickListener { networkAction { it.updateRandomOrderSwitch(true) } }
        binding.randomOffButton.setOnClickListener { networkAction { it.updateRandomOrderSwitch(false) } }
        binding.itemTypesButton.setOnClickListener { editItemTypes() }
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_workbench -> showPage(0)
                R.id.tab_rules -> showPage(1)
                R.id.tab_history -> showPage(2)
                R.id.tab_account -> showPage(3)
            }
            true
        }
        binding.bottomNav.selectedItemId = R.id.tab_workbench

        val openHistory = View.OnClickListener {
            binding.bottomNav.selectedItemId = R.id.tab_history
        }
        binding.openHistoryLink.setOnClickListener(openHistory)
        binding.openHistoryLink2.setOnClickListener(openHistory)
        binding.advancedSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** Switches between the four bottom-navigation pages. */
    private fun showPage(index: Int) {
        val pages = arrayOf(
            binding.pageWorkbench,
            binding.pageRules,
            binding.pageHistory,
            binding.pageAccount
        )
        pages.forEachIndexed { position, page ->
            page.visibility = if (position == index) View.VISIBLE else View.GONE
        }
        binding.toolbar.title = getString(PAGE_TITLES[index])
        pages[index].scrollTo(0, 0)
    }

    override fun onResume() {
        super.onResume()
        LogBus.addListener(logListener)
        HistoryStore.addListener(historyListener)
        binding.logView.text = ""
        for (line in LogBus.snapshot()) appendLog(line)
        if (LogBus.snapshot().isEmpty()) {
            binding.logView.text = getString(R.string.log_empty)
        }
        // Note: fields are NOT reloaded here — reloading would discard
        // unsaved edits when returning from the advanced settings screen.
        refreshListenStatus()
        refreshAccountHeader()
        refreshHistory()
        refreshAccountInfo()
        binding.root.post(statusRefresh)
    }

    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshListenStatus()
            binding.root.postDelayed(this, 1000)
        }
    }

    override fun onPause() {
        super.onPause()
        LogBus.removeListener(logListener)
        HistoryStore.removeListener(historyListener)
        binding.root.removeCallbacks(statusRefresh)
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
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
            minAmount = binding.minAmountInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("最低金额必须是数字"),
            maxAmount = binding.maxAmountInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("最高金额必须是数字"),
            pollIntervalSec = binding.pollIntervalInput.text.toString().toDoubleOrNull()
                ?: throw IllegalArgumentException("轮询间隔必须是数字"),
            memoMode = binding.memoModeSpinner.selectedItemPosition,
            memoKeywords = lines(binding.memoKeywordsInput.text ?: ""),
            blacklist = lines(binding.blacklistInput.text ?: "")
        ).validate()
    }

    private fun saveSettings(showToast: Boolean = true): Boolean {
        return try {
            val config = readSettingsIntoConfig()
            val phone = binding.phoneInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            if (phone != store.loadPhone() || password != store.loadPassword()) {
                check(!ListenService.isRunning) { "修改账号前请停止监听并等待退出" }
                accountGeneration++
                SessionRepository.invalidate()
            }
            val persisted = store.saveAccount(phone, password)
            credentialPersisted = persisted
            store.saveConfig(config)
            if (!persisted) Toast.makeText(this, "设置已保存；账号凭据仅在当前进程内存中，未持久保存", Toast.LENGTH_LONG).show()
            else if (showToast) Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            refreshHistory()
            refreshAccountHeader()
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
        if (!HistoryStore.persistenceHealthy) { Toast.makeText(this, "历史存储异常，平台操作已锁定", Toast.LENGTH_LONG).show(); return }
        val phone = binding.phoneInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.need_login_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (ListenService.isRunning) {
            Toast.makeText(this, "请先停止监听再验证登录", Toast.LENGTH_SHORT).show()
            return
        }
        if (networkBusy) return
        binding.loginStatus.text = getString(R.string.login_checking)
        updateLoginStatusVisibility()
        setNetworkBusy(true)
        val generation = accountGeneration
        var requestRevision = SessionRepository.revision
        networkExecutor.execute {
            try {
                val config = store.loadConfig()
                val client = SessionRepository.forAccount(config, phone, password)
                val loginRevision = SessionRepository.revision
                requestRevision = loginRevision
                client.resumeRequests()
                val session = client.session ?: client.sessionForAccount(phone, password, config.shopId)
                val clerk = try {
                    ApiClient.extractClerk(client.clerkGet(session))
                } catch (e: ApiError) {
                    null
                }
                client.checkActive()
                if (generation != accountGeneration || loginRevision != SessionRepository.revision) return@execute
                val persisted = store.saveAccount(phone, password)
                credentialPersisted = persisted
                val name = clerk?.optString("name", "") ?: ""
                val id = clerk?.optInt("id", 0) ?: 0
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || loginRevision != SessionRepository.revision || client.isCancelled) return@runOnUiThread
                    // 成功结果不再向用户暴露 sid 等内部标识；"已验证"由头像头状态承载。
                    binding.loginStatus.text = getString(R.string.login_success)
                    updateLoginStatusVisibility()
                    refreshHistory()
                    // 登录成功后工作台也要跟着变：头像头显示“已登录/监听中”，
                    // 状态卡与“开始监听”按钮按会话启用；账户/店铺与统计由
                    // 下方 finally 中的 refreshAccountInfo() 刷新。
                    refreshAccountHeader()
                    refreshListenStatus()
                    if (!persisted) Toast.makeText(this, "登录成功；凭据仅当前进程可用，未持久保存", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiError) {
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || requestRevision != SessionRepository.revision) return@runOnUiThread
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：登录或会话校验失败"
                    updateLoginStatusVisibility()
                }
            } catch (e: Exception) {
                // Unexpected failure (e.g. malformed JSON): never leave
                // the button disabled and the status stuck on "checking".
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || requestRevision != SessionRepository.revision) return@runOnUiThread
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：请求未完成"
                    updateLoginStatusVisibility()
                }
            } finally {
                runOnUiThread { setNetworkBusy(false); refreshAccountInfo() }
            }
        }
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
        if (!HistoryStore.persistenceHealthy) { Toast.makeText(this, "历史存储异常，平台操作已锁定", Toast.LENGTH_LONG).show(); return }
        ListenService.start(this)
        refreshListenStatus()
    }

    private fun refreshListenStatus() {
        binding.listenStatus.text = ListenService.state
        val running = ListenService.isRunning
        if (lastRunning != running) {
            // Listening state drives the account header subtitle, so refresh it
            // only on transitions instead of on every 1s tick (loadPhone() may
            // fall back to EncryptedSharedPreferences decryption).
            refreshAccountHeader()
            if (lastRunning && !running) {
                refreshHistory()
                refreshAccountInfo()
            }
        }
        lastRunning = running
        val loggedIn = hasValidSession()
        binding.startButton.isEnabled = !running && !networkBusy && loggedIn
        binding.stopButton.isEnabled = running
        // 未登录时按钮禁用，同时说明原因，避免点了没反应。
        binding.statusHint.text = if (!running && !loggedIn) {
            getString(R.string.need_login_first)
        } else {
            getString(R.string.status_hint_default)
        }
    }

    /**
     * 登录态判定（会话优先）：只看 SessionRepository 里是否真的有可用会话，
     * 不看本机是否存下了手机号。凭据未持久化、手机号为空但会话仍有效时
     * 都算已登录；反之凭据存在但会话已失效则算未登录。
     *
     * 只读判定，不会构造客户端或触发会话重建，可安全用于每秒刷新。
     */
    private fun hasValidSession(): Boolean = SessionRepository.hasLiveSession()

    /**
     * Account header on the "我的" page. Only masked phone numbers are ever
     * written to the UI; the full number must never be displayed or logged.
     */
    private fun refreshAccountHeader() {
        val phone = store.loadPhone()
        val loggedIn = hasValidSession()
        val avatar: String
        val phoneText: String
        if (phone.isEmpty()) {
            // 未登录时头像头承载账号状态，副标题不再重复“未登录”。
            avatar = if (loggedIn) "已" else "未"
            phoneText = if (loggedIn) "会话已登录" else "未登录"
        } else {
            avatar = phone.takeLast(2)
            phoneText = maskPhone(phone)
        }
        // 取值顺序固定：正在监听 → 有会话 → 未登录。
        val status = when {
            ListenService.isRunning -> "监听中"
            loggedIn && credentialPersisted -> "已登录"
            loggedIn -> "已登录（本次会话；凭据未保存到本机）"
            else -> "手机号 + 密码登录"
        }
        setAccountHeader(avatar, phoneText, status)
        updateLoginStatusVisibility()
    }

    private fun maskPhone(phone: String): String = if (phone.length >= 7) {
        phone.take(3) + "****" + phone.takeLast(4)
    } else {
        "****" + phone.takeLast(2)
    }

    /**
     * “登录状态”行的显隐：未登录文案由头像头表达，这里不再重复；
     * 只有“正在登录…/登录成功/登录失败…”这类过程与结果反馈才显示。
     */
    private fun updateLoginStatusVisibility() {
        val text = binding.loginStatus.text?.toString().orEmpty()
        val redundant = text.isBlank() || text == getString(R.string.login_not_logged_in)
        binding.loginStatus.visibility = if (redundant) View.GONE else View.VISIBLE
    }

    /** Assigns only on change so repeated refreshes never flicker. */
    private fun setAccountHeader(avatar: String, phone: String, status: String) {
        if (binding.accountAvatarText.text.toString() != avatar) binding.accountAvatarText.text = avatar
        if (binding.accountPhoneText.text.toString() != phone) binding.accountPhoneText.text = phone
        if (binding.accountStatusText.text.toString() != status) binding.accountStatusText.text = status
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
        val logText = binding.logView.text.toString()
        if (logText.length > 100_000) binding.logView.text = logText.takeLast(80_000)
        // Keep the newest line visible.
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setNetworkBusy(busy: Boolean) {
        networkBusy = busy
        listOf(binding.loginButton, binding.onlineButton, binding.offlineButton,
            binding.randomOnButton, binding.randomOffButton, binding.itemTypesButton,
            binding.saveSettingsButton).forEach { it.isEnabled = !busy }
        binding.phoneInput.isEnabled = !busy
        binding.passwordInput.isEnabled = !busy
        binding.startButton.isEnabled = !busy && !ListenService.isRunning && hasValidSession()
    }

    private fun networkAction(action: (ApiClient) -> Unit) {
        if (isDestroyed || networkBusy || ListenService.isRunning) return
        if (!HistoryStore.persistenceHealthy) { Toast.makeText(this, "历史存储异常，平台操作已锁定", Toast.LENGTH_LONG).show(); return }
        val client = try { SessionRepository.forAccount(store.loadConfig(), store.loadPhone(), store.loadPassword()) }
        catch (_: Exception) { return }
        if (client.session == null) {
            Toast.makeText(this, "请先登录并停止监听后操作", Toast.LENGTH_SHORT).show()
            return
        }
        val revision = SessionRepository.revision
        setNetworkBusy(true)
        networkExecutor.execute {
            try {
                client.resumeRequests()
                action(client)
            } catch (_: Exception) {
                runOnUiThread { if (revision == SessionRepository.revision) Toast.makeText(this, "操作失败，已恢复原状态", Toast.LENGTH_LONG).show() }
            } finally {
                runOnUiThread { setNetworkBusy(false); refreshAccountInfo() }
            }
        }
    }

    private fun refreshAccountInfo() {
        if (isDestroyed || networkBusy || ListenService.isRunning) return
        if (!HistoryStore.persistenceHealthy) { Toast.makeText(this, "历史存储异常，平台操作已锁定", Toast.LENGTH_LONG).show(); return }
        val client = try { SessionRepository.forAccount(store.loadConfig(), store.loadPhone(), store.loadPassword()) }
        catch (_: Exception) { return }
        val session = client.session
        if (session == null) {
            binding.accountOverview.text = "账户/店铺状态：未登录"
            binding.statisticsOverview.text = "本周统计未读取；续费率 —；续单率 —；续客率口径待确认"
            return
        }
        val revision = SessionRepository.revision
        val range = com.doimty.dpgrabber.core.platformWeekRange(System.currentTimeMillis())
        setNetworkBusy(true)
        binding.statisticsOverview.text = "正在读取本周统计；续费率 —；续单率 —"
        networkExecutor.execute {
            try {
                client.resumeRequests()
                val clerk = ApiClient.extractClerk(client.clerkGet(session))
                    ?: throw IllegalArgumentException("缺少店员状态")
                val status = clerk.opt("status")?.toString() ?: "未知"
                val random = clerk.opt("randomOrderSwitch")?.toString() ?: "未知"
                runOnUiThread { if (revision == SessionRepository.revision) binding.accountOverview.text = "店铺 ${session.sid} · 在线状态 $status · 随机单 $random" }
            } catch (_: Exception) {
                runOnUiThread { if (revision == SessionRepository.revision) binding.accountOverview.text = "账户状态读取失败，旧状态已清除" }
            }
            val text = try {
                com.doimty.dpgrabber.core.statisticsText(client.statisticsClerkOrder(range.monday, range.today))
            } catch (_: Exception) {
                val count = try {
                    client.orderList("${range.monday} 00:00:00", "${range.today} 23:59:59", pageSize = 1)
                        .opt("total")?.toString()?.toLongOrNull()?.takeIf { it >= 0 }?.toString() ?: "—"
                } catch (_: Exception) { "—" }
                "本周订单 $count 单（订单列表兜底）\n续费率 —；续单率 —；续客率：口径待确认"
            }
            runOnUiThread {
                if (revision == SessionRepository.revision) binding.statisticsOverview.text = text
                setNetworkBusy(false)
            }
        }
    }

    private fun editItemTypes() = networkAction { client ->
        val revision = SessionRepository.revision
        val response = client.getItemSwitchList()
        val data = response.optJSONObject("data") ?: response
        val items = data.optJSONArray("itemSwitchList") ?: throw IllegalArgumentException()
        val selected = data.optJSONArray("clerkItemSwitchIds") ?: org.json.JSONArray()
        val ids = (0 until items.length()).map { items.getJSONObject(it).getInt("id") }
        val names = (0 until items.length()).map { items.getJSONObject(it).optString("name", ids[it].toString()) }.toTypedArray()
        val checked = BooleanArray(ids.size) { index -> (0 until selected.length()).any { selected.optString(it) == ids[index].toString() } }
        runOnUiThread {
            if (isDestroyed || revision != SessionRepository.revision || client.isCancelled) return@runOnUiThread
            AlertDialog.Builder(this).setTitle("接单类型")
                .setMultiChoiceItems(names, checked) { dialog, index, enabled ->
                    val list = (dialog as AlertDialog).listView
                    val before = !enabled
                    if (networkBusy || ListenService.isRunning || revision != SessionRepository.revision) {
                        checked[index] = before
                        list.setItemChecked(index, before)
                        return@setMultiChoiceItems
                    }
                    val chosen = ids.filterIndexed { i, _ -> if (i == index) enabled else checked[i] }
                    list.isEnabled = false
                    networkAction { api ->
                        try { api.updateItemSwitch(ids[index], chosen); checked[index] = enabled }
                        catch (e: Exception) {
                            checked[index] = before
                            runOnUiThread { list.setItemChecked(index, before) }
                            throw e
                        } finally { runOnUiThread { list.isEnabled = true } }
                    }
                }.setPositiveButton("完成", null).show()
        }
    }

    private fun refreshHistory() {
        if (isDestroyed) return
        val request = ++historyGeneration
        val account = HistoryStore.accountKey(store.loadPhone())
        Thread {
            val entries: List<HistoryStore.Entry>
            val counts: Map<com.doimty.dpgrabber.engine.OutcomeType, Int>
            try {
                HistoryStore(this).use { history ->
                    entries = history.entries(account, 100)
                    counts = history.todayCounts(account)
                }
            } catch (_: Exception) {
                HistoryStore.markUnavailable()
                runOnUiThread { if (!isDestroyed) binding.todayOverview.text = "历史读取失败；平台操作已锁定，原文件保留待修复" }
                return@Thread
            }
            runOnUiThread {
                if (isDestroyed || request != historyGeneration || account != HistoryStore.accountKey(store.loadPhone())) return@runOnUiThread
                binding.todaySuccessCount.text = (counts[com.doimty.dpgrabber.engine.OutcomeType.SUCCESS] ?: 0).toString()
                binding.todayPendingCount.text = (counts[com.doimty.dpgrabber.engine.OutcomeType.UNKNOWN] ?: 0).toString()
                binding.todayRiskCount.text = (counts[com.doimty.dpgrabber.engine.OutcomeType.RISK] ?: 0).toString()
                binding.todayOverview.text = "仅当前账号 · 按北京时间统计"
                binding.historyList.removeAllViews()
                binding.historyEmptyHint.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                entries.forEach { entry ->
                    val text = TextView(this).apply {
                        setPadding(8, 12, 8, 12)
                        val label = when (entry.outcome) {
                            com.doimty.dpgrabber.engine.OutcomeType.SUCCESS -> "成功"
                            com.doimty.dpgrabber.engine.OutcomeType.FAILED -> "失败"
                            com.doimty.dpgrabber.engine.OutcomeType.SKIPPED -> "跳过"
                            com.doimty.dpgrabber.engine.OutcomeType.UNKNOWN -> "未知"
                            com.doimty.dpgrabber.engine.OutcomeType.RISK -> "风控"
                        }
                        this.text = "$label · 店铺 ${entry.shop} · ${entry.orderId.takeLast(8)} · ${entry.reason}"
                        setOnLongClickListener {
                            val wechat = entry.order.optString("userWechatId", "")
                            val memo = entry.order.optString("userMemo", "")
                            AlertDialog.Builder(this@MainActivity).setItems(if (entry.outcome == com.doimty.dpgrabber.engine.OutcomeType.UNKNOWN) arrayOf("拉黑客户", "添加备注关键词", "复制订单号", "只读核验未知结果（不重新提交）") else arrayOf("拉黑客户", "添加备注关键词", "复制订单号")) { _, which ->
                                val config = store.loadConfig()
                                when (which) {
                                    0 -> if (wechat.isNotBlank()) { store.saveConfig(config.copy(blacklist = (config.blacklist + wechat).distinct())); binding.blacklistInput.setText((config.blacklist + wechat).distinct().joinToString("\n")) }
                                    1 -> if (memo.isNotBlank()) { store.saveConfig(config.copy(memoKeywords = (config.memoKeywords + memo).distinct())); binding.memoKeywordsInput.setText((config.memoKeywords + memo).distinct().joinToString("\n")) }
                                    2 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("订单号", entry.orderId))
                                    3 -> verifyUnknown(entry)
                                }
                            }.show()
                            true
                        }
                    }
                    binding.historyList.addView(text)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun verifyUnknown(entry: HistoryStore.Entry) = networkAction { client ->
        require(entry.account == HistoryStore.accountKey(store.loadPhone()) && client.session?.sid == entry.shop) { "请登录记录对应的账号与店铺" }
        val clerk = ApiClient.extractClerk(client.clerkGet()) ?: throw IllegalStateException("无法核验店员身份")
        val ownId = ApiClient.parseCode(org.json.JSONObject().put("code", clerk.opt("id"))) ?: 0
        require(ownId > 0)
        val verdict = com.doimty.dpgrabber.core.reconcileOrder(client.orderGet(entry.orderId), ownId)
        client.checkActive()
        val type = when (verdict.verdict) {
            com.doimty.dpgrabber.core.ReconcileVerdict.OK -> com.doimty.dpgrabber.engine.OutcomeType.SUCCESS
            com.doimty.dpgrabber.core.ReconcileVerdict.FAIL -> if (verdict.reason == "被其他人抢走") com.doimty.dpgrabber.engine.OutcomeType.SKIPPED else com.doimty.dpgrabber.engine.OutcomeType.FAILED
            else -> com.doimty.dpgrabber.engine.OutcomeType.UNKNOWN
        }
        val resultStore = HistoryStore(this)
        try { resultStore.reconcile(entry, type, if (type == com.doimty.dpgrabber.engine.OutcomeType.UNKNOWN) "仍未核实，未重新提交" else "只读核验：${verdict.reason}") }
        finally { resultStore.close() }
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
        private val PAGE_TITLES = intArrayOf(
            R.string.tab_workbench,
            R.string.tab_rules,
            R.string.tab_history,
            R.string.tab_account
        )
    }
}
