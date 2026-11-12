package com.doimty.dpgrabber.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.OkHttpTransport
import com.doimty.dpgrabber.engine.ListenEngine
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.ui.MainActivity

/**
 * Foreground service hosting the listening engine (the keep-alive).
 *
 * The engine runs on its own worker thread and only stops on user stop,
 * a successful grab, or a fatal login failure. The service is foreground
 * with a persistent notification so Android does not kill it while the
 * screen is off; for longer background runs the user should also exempt
 * the app from battery optimization (see README).
 */
class ListenService : Service() {

    companion object {
        const val ACTION_START = "com.doimty.dpgrabber.action.START"
        const val ACTION_STOP = "com.doimty.dpgrabber.action.STOP"
        const val CHANNEL_ID = "dpgrabber_listen"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ListenService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListenService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var engine: ListenEngine? = null
    private var lastNotificationMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine("已停止监听")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (engine?.running == true) return START_STICKY
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
                startEngine()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngine() {
        val store = CredentialStore(this)
        val config = store.loadConfig()
        val phone = store.loadPhone()
        val password = store.loadPassword()
        if (phone.isEmpty() || password.isEmpty()) {
            LogBus.post(LogLevel.ERROR, "请先填写手机号和密码并保存")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val client = ApiClient(
            aesKeyHex = config.aesKeyHex,
            aesIvHex = config.aesIvHex,
            aesKeyHexAlt = config.aesKeyHexAlt,
            aesIvHexAlt = config.aesIvHexAlt,
            verifyTls = config.verifyTls,
            transport = OkHttpTransport(config.verifyTls)
        )
        val engine = ListenEngine(
            client = client,
            config = config,
            log = { level, text ->
                LogBus.post(level, text)
                updateNotification(text)
            },
            onStateChange = { running ->
                isRunning = running
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        )
        this.engine = engine
        isRunning = true
        engine.start(phone, password)
        LogBus.post(LogLevel.INFO, "监听服务已启动")
    }

    private fun stopEngine(message: String) {
        engine?.stop()
        engine = null
        isRunning = false
        LogBus.post(LogLevel.INFO, message)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // The engine can log several times a second while polling; updating
        // the notification that often wastes battery, so throttle to one
        // update per 2 s (the latest text is kept by the next call).
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs < 2000) return
        lastNotificationMs = now
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
