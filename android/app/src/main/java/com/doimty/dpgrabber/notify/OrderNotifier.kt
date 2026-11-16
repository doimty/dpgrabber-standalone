package com.doimty.dpgrabber.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.data.OkHttpTransport
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.engine.OrderOutcome
import com.doimty.dpgrabber.engine.OutcomeType
import com.doimty.dpgrabber.service.LogBus
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 结果双保险：本机 Notification + 服务端 HTTPS webhook。
 *
 * 开关语义（双保险：本机为默认兜底，远程为可选加项）：
 * - pushOnSuccess / pushOnFailure / pushOnRisk 决定哪类结果要本机通知；
 *   UNKNOWN 与 SKIPPED 从不通知（避免中间态与没抢到的单刷屏）。默认仍保留成功通知，
 *   与改造前的 1002 通知行为一致。
 * - pushEnabled 只控制远程 webhook，且还要求 webhook 非空；不影响本机通知。
 * 两条路径各自 try/catch 隔离：本机失败不影响远程，远程失败不影响监听/抢单。
 */
class OrderNotifier(
    context: Context,
    private val configProvider: () -> AppConfig,
    private val webhookProvider: () -> String,
    private val sender: PushNotifier = PushNotifier(OkHttpTransport())
) {
    private val appContext = context.applicationContext

    /** 单线程、队列容量 1、丢弃最旧：推送慢/失败不堆积、不阻塞引擎线程。 */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "dpgrabber-push").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    fun notify(o: OrderOutcome) {
        val config = try { configProvider() } catch (_: Throwable) { return }
        val typeEnabled = when (o.type) {
            OutcomeType.SUCCESS -> config.pushOnSuccess
            OutcomeType.FAILED -> config.pushOnFailure
            OutcomeType.RISK -> config.pushOnRisk
            OutcomeType.UNKNOWN, OutcomeType.SKIPPED -> false
        }
        if (!typeEnabled) return

        // 本机通知是默认兜底，不受远程开关约束（否则关闭 webhook 会连原有成功通知一起丢失）。
        notifyLocal(o)
        // 远程推送需显式开启；pushRemote 内部再校验 webhook 非空。
        if (config.pushEnabled) pushRemote(o)
    }

    private fun notifyLocal(o: OrderOutcome) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = appContext.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID, "接单结果", NotificationManager.IMPORTANCE_DEFAULT
                )
                manager?.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(NotificationText.title(o))
                .setContentText(NotificationText.body(o))
                .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationText.body(o)))
                .setAutoCancel(true)
                .build()
            val manager = appContext.getSystemService(NotificationManager::class.java)
                ?: return
            manager.notify(notificationId(o), notification)
        } catch (_: Throwable) {
            // 本机通知异常只降级：只记类别，不带订单号或任何负载。
            try { LogBus.post(LogLevel.WARNING, "本机结果通知未发出，监听继续") } catch (_: Throwable) { }
        }
    }

    private fun pushRemote(o: OrderOutcome) {
        try {
            val webhook = webhookProvider()
            if (webhook.isBlank()) return
            val title = NotificationText.title(o)
            val body = NotificationText.body(o)
            try {
                executor.execute {
                    try {
                        if (!sender.send(title, body, webhook)) {
                            LogBus.post(LogLevel.WARNING, "远程结果推送未成功，监听继续")
                        }
                    } catch (_: Throwable) {
                        try { LogBus.post(LogLevel.WARNING, "远程结果推送失败，监听继续") } catch (_: Throwable) { }
                    }
                }
            } catch (_: Throwable) {
                try { LogBus.post(LogLevel.WARNING, "远程结果推送未排队，监听继续") } catch (_: Throwable) { }
            }
        } catch (_: Throwable) {
            try { LogBus.post(LogLevel.WARNING, "远程结果推送未发出，监听继续") } catch (_: Throwable) { }
        }
    }

    /** 同一结果重复到达时更新而非互相覆盖；不同订单各自独立。 */
    private fun notificationId(o: OrderOutcome): Int =
        BASE_ID + (o.orderId.hashCode() and 0x7fffffff) % 100_000

    private companion object {
        const val CHANNEL_ID = "dpgrabber_outcome"
        const val BASE_ID = 1003
    }
}
