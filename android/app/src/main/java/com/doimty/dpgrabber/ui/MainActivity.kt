package com.doimty.dpgrabber.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.ApiError
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.WeekMetrics
import com.doimty.dpgrabber.core.weekMetrics
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
import com.google.android.material.button.MaterialButton
import com.doimty.dpgrabber.service.ListenService
import com.doimty.dpgrabber.service.LogBus
import java.io.File
import java.util.Locale

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
    /** 注销流程进行中（等待监听退出），用于避免重复触发。 */
    private var logoutPending = false
    /** 店铺是否已上线；null 表示未知，未知时四个状态按钮都不锁。 */
    private var isOnline: Boolean? = null
    /** 随机单是否开启；null 表示未知。 */
    private var randomEnabled: Boolean? = null
    /** 已解码的真实头像；null 表示回退首字圆形占位。 */
    @Volatile private var avatarBitmap: Bitmap? = null
    /** 头像请求序号：只有最后一次请求的结果才允许写进界面。 */
    @Volatile private var avatarRequestId = 0L
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
        binding.logoutButton.setOnClickListener { confirmLogout() }
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
        restoreAvatarFromCache()
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
                // 姓名与 ID 只进加密存储（失败退回内存），界面文案由它们派生。
                store.saveClerk(name, id)
                // 头像地址可能带签名参数：只交给下载流程，不落库、不进日志。
                val avatarUrl = clerk?.optString("avatarUrl", "") ?: ""
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || loginRevision != SessionRepository.revision || client.isCancelled) return@runOnUiThread
                    // 成功结果不再向用户暴露 sid 等内部标识；"已验证"由身份卡承载。
                    binding.loginStatus.text = getString(R.string.login_success)
                    updateLoginStatusVisibility()
                    refreshHistory()
                    // 登录成功后工作台也要跟着变：身份卡显示“已登录/监听中”，
                    // 状态卡与“开始监听”按钮按会话启用；账户/店铺与统计由
                    // 下方 finally 中的 refreshAccountInfo() 刷新。
                    refreshAccountHeader()
                    refreshListenStatus()
                    // 头像只在登录成功这一次触发；下载放到后台，失败保持首字占位。
                    loadAvatar(avatarUrl, id)
                    if (!persisted) Toast.makeText(this, "登录成功；凭据仅当前进程可用，未持久保存", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiError) {
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || requestRevision != SessionRepository.revision) return@runOnUiThread
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：" + e.messageText.take(40)
                    updateLoginStatusVisibility()
                }
            } catch (e: Exception) {
                // Unexpected failure (e.g. malformed JSON): never leave
                // the button disabled and the status stuck on "checking".
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    if (generation != accountGeneration || requestRevision != SessionRepository.revision) return@runOnUiThread
                    binding.loginStatus.text =
                        getString(R.string.login_failed) + "：" + (e.message?.take(40) ?: "请求未完成")
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
        applyShopControls()
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
        // 会话一旦不在，上一账号的头像必须立刻消失，不能留在界面上。
        if (!loggedIn) avatarBitmap = null
        applyAvatarDrawable()
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
        // 头部状态与登录态同源：任何一次头部刷新都重新判定表单/身份卡。
        applyAuthState()
    }

    /**
     * 登录态切换：有会话时隐藏登录表单、展示身份卡；否则反之。
     * 未登录时身份卡必须整体 gone，避免上一次账号的指标残留。
     */
    private fun applyAuthState() {
        val loggedIn = hasValidSession()
        binding.profileCard.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.loginFormContainer.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (loggedIn) refreshProfileName() else renderProfileMetrics(null)
    }

    /** 身份卡姓名行：「姓名（ID 307569）」→「姓名」→「已登录店员」。 */
    private fun refreshProfileName() {
        val (name, id) = store.loadClerk()
        val text = when {
            name.isNotBlank() && id > 0 -> "$name（ID $id）"
            name.isNotBlank() -> name
            else -> "已登录店员"
        }
        if (binding.profileName.text.toString() != text) binding.profileName.text = text
    }

    /** 身份卡三项周指标；null 表示本周统计未读到，全部显示占位符。 */
    private fun renderProfileMetrics(metrics: WeekMetrics?) {
        fun percent(value: Double?): String =
            value?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) } ?: "—"
        val orders = metrics?.orders?.toString() ?: "—"
        val feeRate = percent(metrics?.feeRate)
        val orderRate = percent(metrics?.orderRate)
        if (binding.profileOrders.text.toString() != orders) binding.profileOrders.text = orders
        if (binding.profileFeeRate.text.toString() != feeRate) binding.profileFeeRate.text = feeRate
        if (binding.profileOrderRate.text.toString() != orderRate) binding.profileOrderRate.text = orderRate
    }

    /**
     * 头像：优先本地缓存 avatar_{clerkId}.png（>1KB 视为有效），没有缓存才按 avatarUrl 下载。
     * 任何失败（网络/解码/磁盘）都静默回退首字占位，不影响登录结果；URL 可能带签名参数，
     * 因此不写日志，缓存文件名只用 clerkId。传空 url 表示只读缓存（回到前台恢复用）。
     */
    private fun loadAvatar(url: String, clerkId: Int) {
        if (clerkId <= 0 || avatarBitmap != null) return
        val requestId = ++avatarRequestId
        networkExecutor.execute {
            val cached = avatarCacheFile(clerkId)?.takeIf { it.isFile && it.length() > MIN_AVATAR_BYTES }
            val bytes = when {
                cached != null -> try { cached.readBytes() } catch (_: Exception) { null }
                isHttpUrl(url) -> downloadAvatar(url)?.also { writeAvatarCache(clerkId, it) }
                else -> null
            }
            val bitmap = bytes?.let { decodeAvatar(it) } ?: return@execute
            runOnUiThread {
                if (isDestroyed || requestId != avatarRequestId || !hasValidSession()) return@runOnUiThread
                avatarBitmap = bitmap
                applyAvatarDrawable()
                binding.accountAvatarText.text = ""
            }
        }
    }

    /** 回到前台只恢复本地缓存，不重复发起下载。 */
    private fun restoreAvatarFromCache() {
        if (avatarBitmap != null || !hasValidSession()) return
        val (_, clerkId) = store.loadClerk()
        val file = avatarCacheFile(clerkId) ?: return
        if (!file.isFile || file.length() <= MIN_AVATAR_BYTES) return
        loadAvatar("", clerkId)
    }

    /** 48dp 圆形头像；没有图就回到 bg_avatar 的首字圆形底。 */
    private fun applyAvatarDrawable() {
        val bitmap = avatarBitmap
        binding.accountAvatarText.background = if (bitmap == null) {
            ContextCompat.getDrawable(this, R.drawable.bg_avatar)
        } else {
            RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
        }
    }

    private fun avatarCacheFile(clerkId: Int): File? =
        if (clerkId <= 0) null else File(File(cacheDir, AVATAR_DIR), "avatar_$clerkId.png")

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun writeAvatarCache(clerkId: Int, bytes: ByteArray) {
        val file = avatarCacheFile(clerkId) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (_: Exception) {
            // 缓存失败不影响本次显示。
        }
    }

    private fun downloadAvatar(url: String): ByteArray? {
        val connection = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = AVATAR_TIMEOUT_MS
            connection.readTimeout = AVATAR_TIMEOUT_MS
            if (!connection.contentType.orEmpty().startsWith("image/", ignoreCase = true)) return null
            if (connection.contentLengthLong > MAX_AVATAR_BYTES) return null
            val buffer = java.io.ByteArrayOutputStream()
            val input = connection.inputStream
            try {
                val chunk = ByteArray(16 * 1024)
                var read = input.read(chunk)
                while (read >= 0) {
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_AVATAR_BYTES) return null
                    read = input.read(chunk)
                }
            } finally {
                try { input.close() } catch (_: Exception) { }
            }
            val bytes = buffer.toByteArray()
            if (looksLikeImage(bytes)) bytes else null
        } catch (_: Exception) {
            null
        } finally {
            try { connection.disconnect() } catch (_: Exception) { }
        }
    }

    // 服务端不一定给 image/… 的 Content-Type，用文件头兜底判断 PNG/JPEG/GIF/WEBP。
    // 注意：这里必须用行注释。Kotlin 块注释支持嵌套，注释里写 image/* 会再开一层注释，
    // 导致整个文件后半段被吞掉、报 "Unclosed comment"。
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true
            else -> false
        }
    }

    /** 按 48dp 目标尺寸降采样解码，避免原图占用过多内存。 */
    private fun decodeAvatar(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= AVATAR_TARGET_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) {
        null
    }

    /** 注销二次确认：不可撤销，先把后果说清楚。 */
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("注销当前账号")
            .setMessage("将停止监听并清除本机保存的账号；历史记录与统计不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("注销") { _, _ -> performLogout() }
            .show()
    }

    /**
     * 注销：先让监听真正退出，再清账号与姓名。
     * 会话与凭据的清除都禁止在任务运行中执行，因此这里必须等 isRunning 落下。
     */
    private fun performLogout() {
        if (logoutPending) return
        logoutPending = true
        if (!ListenService.isRunning) { finishLogout(); return }
        ListenService.stop(this)
        awaitListenerStopped(0)
    }

    private fun awaitListenerStopped(attempt: Int) {
        if (!ListenService.isRunning) { finishLogout(); return }
        if (attempt >= LOGOUT_STOP_ATTEMPTS) {
            logoutPending = false
            Toast.makeText(this, "监听仍在退出，请稍后再注销", Toast.LENGTH_LONG).show()
            refreshListenStatus()
            return
        }
        binding.root.postDelayed({ awaitListenerStopped(attempt + 1) }, LOGOUT_STOP_POLL_MS)
    }

    /** 只清账号与姓名；历史记录（HistoryStore）与设置一律不动。 */
    private fun finishLogout() {
        logoutPending = false
        // 先让在途登录回调失效，避免注销后又被旧结果写回登录态。
        accountGeneration++
        try {
            store.clearAccount()
            store.saveClerk("", 0)
        } catch (_: Exception) {
            Toast.makeText(this, "注销未完成，请稍后重试", Toast.LENGTH_LONG).show()
            return
        }
        binding.phoneInput.setText("")
        binding.passwordInput.setText("")
        binding.loginStatus.text = ""
        updateLoginStatusVisibility()
        refreshAccountHeader()
        refreshListenStatus()
        refreshAccountInfo()
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
        // 已有真实头像时不再画首字，避免文字压在图片上。
        val avatarText = if (avatarBitmap != null) "" else avatar
        if (binding.accountAvatarText.text.toString() != avatarText) binding.accountAvatarText.text = avatarText
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
        listOf(binding.loginButton, binding.itemTypesButton,
            binding.saveSettingsButton).forEach { it.isEnabled = !busy }
        binding.phoneInput.isEnabled = !busy
        binding.passwordInput.isEnabled = !busy
        binding.startButton.isEnabled = !busy && !ListenService.isRunning && hasValidSession()
        // 上线/下线/随机单四个按钮的可用性由当前状态决定，不能一律放开。
        applyShopControls()
    }

    /** 当前状态用文字标记；未知状态允许用户重试，监听中明确锁定状态写操作。 */
    private fun applyShopControls() {
        val canChange = !networkBusy && !ListenService.isRunning && hasValidSession() && HistoryStore.persistenceHealthy
        binding.onlineButton.isEnabled = canChange && isOnline != true
        binding.offlineButton.isEnabled = canChange && isOnline != false
        binding.randomOnButton.isEnabled = canChange && randomEnabled != true
        binding.randomOffButton.isEnabled = canChange && randomEnabled != false
        setButtonCurrent(binding.onlineButton, getString(R.string.btn_online), isOnline == true)
        setButtonCurrent(binding.offlineButton, getString(R.string.btn_offline), isOnline == false)
        setButtonCurrent(binding.randomOnButton, getString(R.string.btn_random_on), randomEnabled == true)
        setButtonCurrent(binding.randomOffButton, getString(R.string.btn_random_off), randomEnabled == false)
    }

    /**
     * 当前生效的那个按钮加「✓ 」前缀并禁用：双列按钮宽度有限，
     * 用前缀而不是长后缀，深色模式下也不靠灰度的细微差别去猜。
     */
    private fun setButtonCurrent(button: MaterialButton, label: String, current: Boolean) {
        val text = if (current) "✓ $label" else label
        if (button.text.toString() != text) button.text = text
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
            } catch (e: Exception) {
                // 之前这里吞掉了异常，用户只看到"操作失败"却不知道原因，
                // 表现就是"按钮点了没反应"。原因必须进日志。
                // 40 字是为了让「平台拒绝操作（code=1004：…）」里的 code 不被截掉。
                val reason = ((e as? com.doimty.dpgrabber.core.ApiError)?.messageText
                    ?: e.javaClass.simpleName).take(40)
                com.doimty.dpgrabber.service.LogBus.post(
                    com.doimty.dpgrabber.engine.LogLevel.WARNING, "操作失败：$reason")
                // Toast 不受 revision 限制：用户点过就应该看到结果，哪怕会话已换。
                runOnUiThread { Toast.makeText(this, "操作失败：$reason", Toast.LENGTH_LONG).show() }
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
            resetShopState("账户/店铺状态：未登录")
            binding.statisticsOverview.text = "本周统计未读取；续费率 —；续单率 —；续客率口径待确认"
            renderProfileMetrics(null)
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
                val status = clerk.opt("status")
                val random = clerk.opt("randomOrderSwitch")
                runOnUiThread {
                    if (revision == SessionRepository.revision) renderShopState(session.sid, status, random)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (revision == SessionRepository.revision) resetShopState("店铺状态读取失败，请稍后重试")
                }
            }
            // 统计只解析一次：同一份响应同时喂给工作台文案与身份卡三项指标。
            var metrics: WeekMetrics? = null
            val text = try {
                val response = client.statisticsClerkOrder(range.monday, range.today)
                metrics = weekMetrics(response)
                com.doimty.dpgrabber.core.statisticsText(response)
            } catch (_: Exception) {
                val count = try {
                    client.orderList("${range.monday} 00:00:00", "${range.today} 23:59:59", pageSize = 1)
                        .opt("total")?.toString()?.toLongOrNull()?.takeIf { it >= 0 }?.toString() ?: "—"
                } catch (_: Exception) { "—" }
                "本周订单 $count 单（订单列表兜底）\n续费率 —；续单率 —；续客率：口径待确认"
            }
            runOnUiThread {
                if (revision == SessionRepository.revision) {
                    binding.statisticsOverview.text = text
                    renderProfileMetrics(metrics)
                }
                setNetworkBusy(false)
            }
        }
    }

    /**
     * 店铺状态可读化：接口给的是 status=1/2 与 randomOrderSwitch=true/false，
     * 界面只允许出现「已上线/已下线」「随机单开启/关闭」，不暴露原始取值。
     * 无法识别的一律按未知处理，此时不锁任何状态按钮。
     */
    private fun renderShopState(sid: String, status: Any?, randomSwitch: Any?) {
        isOnline = when (numericValue(status)) {
            STATUS_ONLINE -> true
            STATUS_OFFLINE -> false
            else -> null
        }
        randomEnabled = booleanValue(randomSwitch)
        val onlineText = when (isOnline) {
            true -> "已上线"
            false -> "已下线"
            null -> "在线状态未知"
        }
        val randomText = when (randomEnabled) {
            true -> "随机单开启"
            false -> "随机单关闭"
            null -> "随机单状态未知"
        }
        val text = "店铺 $sid · $onlineText · $randomText"
        if (binding.accountOverview.text.toString() != text) binding.accountOverview.text = text
        applyShopControls()
    }

    private fun resetShopState(overview: String) {
        isOnline = null
        randomEnabled = null
        if (binding.accountOverview.text.toString() != overview) binding.accountOverview.text = overview
        applyShopControls()
    }

    /** 接口可能给数字或数字字符串；小数、非有限值和越界值不能截断成有效状态。 */
    private fun numericValue(value: Any?): Int? =
        value?.toString()?.toDoubleOrNull()?.takeIf {
            it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == kotlin.math.floor(it)
        }?.toInt()

    /** 只认 true/false 与 1/0（数字或字符串），其它取值不猜。 */
    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toDouble()) { 1.0 -> true; 0.0 -> false; else -> null }
        is String -> when (value.trim().lowercase(Locale.ROOT)) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
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
        // 核验结果写的是历史库，服务收不到 onOutcome；通知里的“待核验”需要主动重载。
        ListenService.notifyHistoryChanged()
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
        /** 注销等待监听退出的轮询次数与间隔（约 3 秒）。 */
        private const val LOGOUT_STOP_ATTEMPTS = 30
        private const val LOGOUT_STOP_POLL_MS = 100L
        /** clerk.status 取值：1=上线、2=下线（与 ApiClient.updateStatus 一致）。 */
        private const val STATUS_ONLINE = 1
        private const val STATUS_OFFLINE = 2
        /** 头像缓存与下载限制。 */
        private const val AVATAR_DIR = "avatars"
        private const val MIN_AVATAR_BYTES = 1024L
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
        private const val AVATAR_TIMEOUT_MS = 10_000
        /** 48dp 头像在最高密度下约 144px，用作物理解码上限。 */
        private const val AVATAR_TARGET_PX = 144
        private val PAGE_TITLES = intArrayOf(
            R.string.tab_workbench,
            R.string.tab_rules,
            R.string.tab_history,
            R.string.tab_account
        )
    }
}
