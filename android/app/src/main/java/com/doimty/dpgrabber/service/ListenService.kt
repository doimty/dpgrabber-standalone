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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.doimty.dpgrabber.R
import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.data.CredentialStore
import com.doimty.dpgrabber.data.HistoryStore
import com.doimty.dpgrabber.data.SessionRepository
import com.doimty.dpgrabber.engine.ListenEngine
import com.doimty.dpgrabber.engine.LogLevel
import com.doimty.dpgrabber.engine.OutcomeType
import com.doimty.dpgrabber.notify.OrderNotifier
import com.doimty.dpgrabber.ui.MainActivity

/** dataSync 前台任务只在用户明确启动后运行，不承诺系统永不终止。 */
class ListenService : Service() {
    companion object {
        const val ACTION_START = "com.doimty.dpgrabber.action.START"
        const val ACTION_STOP = "com.doimty.dpgrabber.action.STOP"
        const val CHANNEL_ID = "dpgrabber_listen"
        const val NOTIFICATION_ID = 1001
        /** 计数变化的刷新节流下限：连单时不刷屏。 */
        private const val COUNT_REFRESH_MIN_MS = 1_000L
        /** 运行时长在 subText 里只按分钟级变化，30 秒刷一次即可。 */
        private const val RUNTIME_TICK_MS = 30_000L
        @Volatile var isRunning = false
            private set
        @Volatile var state = "已停止"
            private set

        /** 运行中实例的弱引用：只用于 Activity 侧改动历史后刷新通知，绝不据此启动服务。 */
        @Volatile private var instanceRef: java.lang.ref.WeakReference<ListenService>? = null

        /**
         * 历史被外部改动（例如人工核验把“待核验”改成“成功”）后调用。
         * 服务未运行时不做事，不启动服务、不发广播。
         */
        fun notifyHistoryChanged() {
            val service = instanceRef?.get() ?: return
            try {
                service.main.post { service.refreshAfterExternalChange() }
            } catch (_: Throwable) {
            }
        }

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

    /** 今日统计：库基线（后台线程写一次）+ 本次会话增量（引擎线程写），显示时相加。 */
    private data class TodayCounts(val success: Int, val pending: Int, val risk: Int, val total: Int)

    private val countLock = Any()
    /** 每条结果落地即自增；外部刷新用它判断"读库期间是否又有新结果"。 */
    @Volatile private var sessionMark = 0L
    private var countsBase = TodayCounts(0, 0, 0, 0)
    private var countsSession = TodayCounts(0, 0, 0, 0)
    @Volatile private var currentShopId = ""
    @Volatile private var listeningSinceUptimeMs = 0L
    /** 以下两个只在主线程读写。 */
    private var lastCountNotifyUptimeMs = 0L
    private var countNotifyScheduled = false

    private val countRefreshRunnable = Runnable {
        countNotifyScheduled = false
        lastCountNotifyUptimeMs = SystemClock.elapsedRealtime()
        notifyNow()
    }

    /** 运行时长定时刷新；cleanup/onDestroy 必须移除，避免后台空转。 */
    private val runtimeTicker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            notifyNow()
            main.postDelayed(this, RUNTIME_TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instanceRef = java.lang.ref.WeakReference(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                // UI 侧要保留"结果可能未定"的告警，所以 state 保持长句；
                // 通知栏标题另用短词映射，见 buildNotification。
                state = "正在停止；已提交请求可能仍需核对"
                engine?.stop()
                notifyNow()
                if (engine == null) cleanup(startId)
            }
            ACTION_START -> {
                // 不在旧工作线程退出前复用或恢复它的 transport。
                if (engine != null) return START_NOT_STICKY
                beginSession()
                // 前台通知/权限异常（如系统限制 dataSync 类型）同样是主线程崩溃源，
                // 这里收敛成一次可读提示后停止，而不是让进程退出。
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                } catch (error: Throwable) {
                    LogBus.post(LogLevel.ERROR, "前台通知不可用，任务未启动：" + failureText(error))
                    cleanup(startId)
                    return START_NOT_STICKY
                }
                try {
                    startEngine(startId)
                } catch (error: Throwable) {
                    LogBus.post(LogLevel.ERROR, "服务启动失败：" + failureText(error))
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
        currentShopId = client.session?.sid.orEmpty()
        // 结果通知双保险：本机 Notification + 加密保存的 HTTPS 推送密钥。
        // 配置、密钥与 Bark 服务器都热读，任何异常都被 OrderNotifier 隔离。
        val orderNotifier = OrderNotifier(this, { store.loadConfig() },
            { store.loadPushSecret() }, { store.loadBarkServer() })
        val task = ListenEngine(client, config,
            log = { level, text ->
                // 通知改为按“计数变化 + 状态变化 + 30 秒时长”刷新，
                // 不再逐条日志触发，避免连单时刷屏与主线程空转。
                LogBus.post(level, text)
                syncShopId(client)
            },
            configSnapshot = { store.loadConfig() },
            onAttemptStarted = { value -> history.record(value) },
            onOutcome = { value ->
                val recorded = try {
                    history.record(value)
                    true
                } catch (error: Throwable) {
                    // 历史写入失败必须停止任务，但绝不能在后台线程向上抛：
                    // 那个异常没有顶层捕获，会直接把进程杀掉（实机“闪退”）。
                    // 语义保持不变：锁定平台操作 + 立即停止 + 不再接下一单。
                    HistoryStore.markUnavailable()
                    LogBus.post(LogLevel.ERROR, "历史写入失败，任务将停止，请人工核对")
                    engine?.abort()
                    false
                }
                // 计数与通知只是展示，任何异常都不得反过来影响监听。
                if (recorded) {
                    // 历史落库后才通知；OrderNotifier 内部逐路径隔离。
                    try { orderNotifier.notify(value) } catch (_: Throwable) { }
                    try {
                        // 失败/跳过不进计数通知，展示值没变就不刷新，省电也避免无意义重建。
                        if (bumpToday(value.type)) refreshCountsThrottled()
                    } catch (_: Throwable) { }
                }
            },
            isPersistentlyBlocked = { sid, orderId -> orderId in history.blockedOrderIds(accountKey, sid) },
            onStateChange = { active -> postGuarded("监听状态收尾") {
                if (generation == currentGeneration) {
                    isRunning = active
                    state = if (active) "正在监听" else "已停止"
                    if (active) {
                        startRuntimeTicker()
                        notifyNow()
                    }
                    if (!active) finishEngine(history, currentGeneration, startId)
                }
            } })
        engine = task
        isRunning = true
        state = "正在启动"
        // 今日统计基线要查库，只在后台线程做；基线就绪后再让引擎开跑，
        // “基线 + 本次增量”才不会和本次写入重复计数。
        Thread({
            try {
                loadTodayBaseline(history, accountKey, currentGeneration)
                // 与 ACTION_STOP 在主线程串行：停止发生在启动之前时，绝不再启动旧任务。
                main.post {
                    if (generation != currentGeneration || engine !== task || client.isCancelled) {
                        finishEngine(history, currentGeneration, startId)
                    } else {
                        try {
                            task.start(phone, password)
                        } catch (error: Throwable) {
                            LogBus.post(LogLevel.ERROR, "监听启动失败：" + failureText(error))
                            finishEngine(history, currentGeneration, startId)
                        }
                    }
                }
            } catch (error: Throwable) {
                LogBus.post(LogLevel.ERROR, "监听启动失败：" + failureText(error))
                main.post { finishEngine(history, currentGeneration, startId) }
            }
        }, "dpgrabber-today-baseline").apply { isDaemon = true; start() }
    }

    private fun finishEngine(history: HistoryStore, forGeneration: Long, startId: Int) {
        try { history.close() }
        catch (_: Throwable) { LogBus.post(LogLevel.WARNING, "监听历史存储关闭失败") }
        finally {
            if (generation == forGeneration) {
                engine = null
                cleanup(startId)
            }
        }
    }

    /** 新一次监听会话：清零计数增量、重置运行时长，通知立即可读。 */
    private fun beginSession() {
        synchronized(countLock) {
            countsBase = TodayCounts(0, 0, 0, 0)
            countsSession = TodayCounts(0, 0, 0, 0)
        }
        currentShopId = ""
        listeningSinceUptimeMs = SystemClock.elapsedRealtime()
        lastCountNotifyUptimeMs = 0L
        state = "正在启动"
    }

    /**
     * 今日统计基线（只在后台线程调用）：成功/待核验/风控取今日当前账号的
     * 权威统计；合计只算这三项之和（见 [threeWayTotal]），失败与跳过不进合计。
     */
    private fun loadTodayBaseline(history: HistoryStore, accountKey: String, forGeneration: Long) {
        val counts = try {
            history.todayCounts(accountKey)
        } catch (error: Throwable) {
            LogBus.post(LogLevel.WARNING, "今日统计读取失败，通知计数从本次会话开始")
            return
        }
        val base = TodayCounts(
            success = counts[OutcomeType.SUCCESS] ?: 0,
            pending = counts[OutcomeType.UNKNOWN] ?: 0,
            risk = counts[OutcomeType.RISK] ?: 0,
            total = threeWayTotal(counts)
        )
        synchronized(countLock) {
            if (generation != forGeneration) return
            countsBase = base
        }
        refreshCountsThrottled()
    }

    /**
     * 通知里的“今日 N 单”只统计成功 / 待核验 / 风控三项之和，与折叠行显示的数字自洽。
     * 失败与跳过是没抢到的单，计入会夸大成果，因此不进合计（但仍会写进历史）。
     */
    private fun threeWayTotal(counts: Map<OutcomeType, Int>): Int =
        (counts[OutcomeType.SUCCESS] ?: 0) + (counts[OutcomeType.UNKNOWN] ?: 0) + (counts[OutcomeType.RISK] ?: 0)

    /** 只有落地成功的结果才计入，避免通知显示未持久化的数字。返回是否改变了展示值。 */
    private fun bumpToday(type: OutcomeType): Boolean {
        synchronized(countLock) {
            sessionMark++
            val current = countsSession
            countsSession = when (type) {
                OutcomeType.SUCCESS -> TodayCounts(current.success + 1, current.pending, current.risk, current.total + 1)
                OutcomeType.UNKNOWN -> TodayCounts(current.success, current.pending + 1, current.risk, current.total + 1)
                OutcomeType.RISK -> TodayCounts(current.success, current.pending, current.risk + 1, current.total + 1)
                // 失败/跳过只写历史，不进通知的三项与合计。
                else -> return false
            }
            return true
        }
    }

    private fun todaySnapshot(): TodayCounts {
        synchronized(countLock) {
            val base = countsBase
            val session = countsSession
            return TodayCounts(base.success + session.success, base.pending + session.pending,
                base.risk + session.risk, base.total + session.total)
        }
    }

    /** 计数变化立即刷新，但两次之间至少间隔 [COUNT_REFRESH_MIN_MS]。 */
    private fun refreshCountsThrottled() {
        postGuarded("今日统计通知") {
            val now = SystemClock.elapsedRealtime()
            val wait = COUNT_REFRESH_MIN_MS - (now - lastCountNotifyUptimeMs)
            when {
                wait <= 0L -> {
                    lastCountNotifyUptimeMs = now
                    notifyNow()
                }
                !countNotifyScheduled -> {
                    countNotifyScheduled = true
                    main.postDelayed(countRefreshRunnable, wait)
                }
            }
        }
    }

    private fun startRuntimeTicker() {
        main.removeCallbacks(runtimeTicker)
        main.postDelayed(runtimeTicker, RUNTIME_TICK_MS)
    }

    /** 只读同步店铺 sid，供通知展开态展示；不触发任何请求。 */
    private fun syncShopId(client: ApiClient) {
        try {
            val sid = client.session?.sid ?: return
            if (sid != currentShopId) currentShopId = sid
        } catch (_: Throwable) { }
    }

    private fun cleanup(startId: Int) {
        main.removeCallbacks(runtimeTicker)
        main.removeCallbacks(countRefreshRunnable)
        countNotifyScheduled = false
        isRunning = false
        state = "已停止"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(maxOf(startId, latestStartId))
    }

    override fun onDestroy() {
        main.removeCallbacks(runtimeTicker)
        main.removeCallbacks(countRefreshRunnable)
        countNotifyScheduled = false
        generation++
        instanceRef = null
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
    }

    /**
     * 常驻通知：折叠态一行计数、标题为当前状态；展开态追加店铺与运行时长。
     * 只出现店铺 sid、计数与时长，绝不含手机号、订单号或令牌。
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val counts = todaySnapshot()
        val runtime = runtimeText()
        val countsText = getString(R.string.notification_today_counts,
            counts.success, counts.pending, counts.risk)
        val detail = getString(R.string.notification_today_detail,
            currentShopId.ifBlank { "—" }, counts.total, runtime)
        // 停止是瞬时状态：标题保持短词，把"已提交请求可能仍需核对"这句告警挪到正文，
        // 展开态同样给告警，避免用户在通知栏只看到一句长标题被截断。
        val stopping = state.startsWith("正在停止")
        val bodyText = if (stopping) getString(R.string.notification_stopping_hint) else countsText
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (stopping) "正在停止" else state)
            .setContentText(bodyText)
            .setSubText(getString(R.string.notification_today_runtime, counts.total, runtime))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText + "\n" + detail))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "停止", stop)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    /**
     * 历史被外部改动后重载基线：整表重算，并把本次会话增量清零，
     * 否则已经算进基线的单会被再加一次。必须先读库再重置，避免窗口内重复。
     */
    private fun refreshAfterExternalChange(attempt: Int = 0) {
        if (!isRunning) return
        Thread({
            var retry = false
            try {
                val localStore = HistoryStore(this)
                try {
                    val phone = CredentialStore(this).loadPhone()
                    val key = HistoryStore.accountKey(phone)
                    // 两段式：读库期间若有新结果落地，快照会变，需要重查一次再应用，
                    // 否则"读库后到清零前"落地的单会被减掉。
                    var mark = sessionMark
                    var counts = localStore.todayCounts(key)
                    if (sessionMark != mark) {
                        mark = sessionMark
                        counts = localStore.todayCounts(key)
                    }
                    synchronized(countLock) {
                        // 判据必须在锁内再取一次：比较在锁外做，两个动作之间仍可能插入新结果。
                        if (sessionMark != mark) {
                            retry = true
                        } else {
                            countsBase = TodayCounts(
                                counts[OutcomeType.SUCCESS] ?: 0,
                                counts[OutcomeType.UNKNOWN] ?: 0,
                                counts[OutcomeType.RISK] ?: 0,
                                threeWayTotal(counts)
                            )
                            countsSession = TodayCounts(0, 0, 0, 0)
                        }
                    }
                } finally {
                    localStore.close()
                }
            } catch (_: Throwable) {
                // 重载失败就保持旧值，下一次计数变化或 30 秒 tick 会再试。
            } finally {
                main.post { notifyNow() }
            }
            if (retry && attempt < 2) {
                main.postDelayed({ refreshAfterExternalChange(attempt + 1) }, 250)
            }
        }, "dpgrabber-today-refresh").apply { isDaemon = true }.start()
    }

    /** 刷新常驻通知：任何异常只降级为 WARN，绝不影响监听与进程。 */
    private fun notifyNow() {        // 停止后迟到的刷新不得把通知重新挂回来。
        if (!isRunning) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (error: Throwable) {
            LogBus.post(LogLevel.WARNING, "运行通知更新失败，监听继续：" + failureText(error))
        }
    }

    /** 不足 1 小时用 mm:ss，超过用 H:mm。 */
    private fun runtimeText(): String {
        val since = listeningSinceUptimeMs
        if (since <= 0L) return "00:00"
        val totalSec = (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L) / 1000L
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "%d:%02d".format(hours, minutes) else "%02d:%02d".format(minutes, seconds)
    }

    /**
     * 主线程回调兜底：任何 Throwable 只记脱敏日志，绝不让进程崩溃。
     * 后台线程的异常有引擎兜底，主线程的 Handler 回调没有，必须在这里收回。
     */
    private fun postGuarded(what: String, block: () -> Unit) {
        main.post {
            try {
                block()
            } catch (_: Throwable) {
                try { LogBus.post(LogLevel.WARNING, "后台回调异常，已忽略（$what）") } catch (_: Throwable) { }
            }
        }
    }

    /** 故障分类文案：只暴露类别，不含订单号、手机号或令牌。 */
    private fun failureText(error: Throwable): String = when (error) {
        is SecurityException -> "缺少系统授权"
        is IllegalArgumentException -> "通知参数不被系统接受"
        is IllegalStateException -> "服务状态不允许"
        is java.io.IOException -> "本地读写失败"
        is OutOfMemoryError -> "内存不足"
        is Error -> "运行时故障"
        else -> error.javaClass.simpleName.ifBlank { "未知异常" }
    }
}
