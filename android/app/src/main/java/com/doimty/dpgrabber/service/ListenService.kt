package com.doimty.dpgrabber.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.HistoryStore
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.engine.ListenEngine
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.ui.MainActivity

/** dataSync 前台任务只在用户明确启动后运行，不承诺系统永不终止。 */
class ListenService : Service() {
    companion object {
        const val ACTION_START = "com.doimty.dpgrabber.action.START"
        const val ACTION_STOP = "com.doimty.dpgrabber.action.STOP"
        const val CHANNEL_ID = "dpgrabber_listen"
        const val SUCCESS_CHANNEL_ID = "dpgrabber_success"
        const val NOTIFICATION_ID = 1001
        @Volatile var isRunning = false
            private set
        @Volatile var state = "已停止"
            private set
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, ListenService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) {
            if (isRunning) context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
        }
    }

    private var engine: ListenEngine? = null
    private var generation = 0L
    private var latestStartId = 0
    private val main = Handler(Looper.getMainLooper())
    private var lastNotificationMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); createChannels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                state = "正在停止；已提交请求可能仍需核对"
                engine?.stop()
                updateNotification(state)
                if (engine == null) cleanup(startId)
            }
            ACTION_START -> {
                // 不在旧工作线程退出前复用或恢复它的 transport。
                if (engine != null) return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification("正在启动"))
                try { startEngine(startId) } catch (_: Exception) {
                    LogBus.post(LogLevel.ERROR, "服务启动失败")
                    cleanup(startId)
                }
            }
            else -> cleanup(startId)
        }
        return START_NOT_STICKY
    }

    private fun startEngine(startId: Int) {
        HistoryStore.requireHealthy()
        val store = CredentialStore(this)
        val config = store.loadConfig()
        val phone = store.loadPhone()
        val password = store.loadPassword()
        if (phone.isEmpty() || password.isEmpty()) {
            LogBus.post(LogLevel.ERROR, "请先填写手机号和密码并保存")
            cleanup(startId)
            return
        }
        val client = SessionRepository.forAccount(config, phone, password)
        val history = HistoryStore(this)
        try { history.readableDatabase }
        catch (error: Exception) { HistoryStore.markUnavailable(); history.close(); throw error }
        val currentGeneration = ++generation
        val accountKey = java.security.MessageDigest.getInstance("SHA-256").digest(phone.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val task = ListenEngine(client, config,
            log = { level, text ->
                LogBus.post(level, text)
                main.post { if (generation == currentGeneration && engine != null) updateNotification("监听任务运行中") }
            },
            configSnapshot = { store.loadConfig() },
            onAttemptStarted = { value -> history.record(value) },
            onOutcome = { value ->
                try { history.record(value) } catch (error: Exception) {
                    LogBus.post(LogLevel.ERROR, "历史写入失败，任务将停止，请人工核对")
                    throw error
                }
            },
            isPersistentlyBlocked = { sid, orderId -> orderId in history.blockedOrderIds(accountKey, sid) },
            onSuccess = { main.post {
                if (generation == currentGeneration) {
                    getSystemService(NotificationManager::class.java).notify(1002,
                        NotificationCompat.Builder(this, SUCCESS_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification).setContentTitle("接单成功")
                            .setContentText("请打开应用核对订单").setAutoCancel(true).build())
                }
            } },
            onStateChange = { active -> main.post {
                if (generation == currentGeneration) {
                    isRunning = active
                    state = if (active) "正在监听" else "已停止"
                    if (!active) {
                        engine = null
                        history.close()
                        cleanup(startId)
                    }
                }
            } })
        engine = task
        isRunning = true
        state = "正在启动"
        task.start(phone, password)
    }

    private fun cleanup(startId: Int) {
        isRunning = false
        state = "已停止"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(maxOf(startId, latestStartId))
    }

    override fun onDestroy() {
        generation++
        engine?.stop()
        engine = null
        isRunning = false
        state = "已停止"
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(SUCCESS_CHANNEL_ID,
            "接单结果", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open)
            .addAction(R.drawable.ic_notification, "停止", stop).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs < 2000) return
        lastNotificationMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
