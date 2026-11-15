package com.doimty.dpgrabber.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.doimty.dpgrabber.engine.OrderOutcome
import com.doimty.dpgrabber.engine.OutcomeType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 独立持久历史，不从日志推导结果。 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "order_history.db", null, 1) {
    companion object {
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        fun addListener(listener: () -> Unit) { listeners.add(listener) }
        fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
        @Volatile var persistenceHealthy: Boolean = true
            private set
        fun markUnavailable() { persistenceHealthy = false }
        fun requireHealthy() = check(persistenceHealthy) { "历史存储未确认安全，平台操作已锁定；请恢复存储后重启" }
        fun accountKey(phone: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(phone.trim().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outcomes (entry_key TEXT PRIMARY KEY, account_key TEXT NOT NULL, shop_id TEXT NOT NULL, order_id TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, outcome TEXT NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(value: OrderOutcome) {
        requireHealthy()
        val values = ContentValues().apply {
            put("entry_key", value.key); put("account_key", value.accountKey); put("shop_id", value.shopId)
            put("order_id", value.orderId); put("timestamp_ms", value.timestampMs)
            put("outcome", value.type.name); put("reason", value.reason)
            val allowed = JSONObject()
            for (key in listOf("id", "no", "totalAmount", "userWechatId", "userMemo")) {
                if (value.order.has(key)) allowed.put(key, value.order.opt(key))
            }
            put("payload", allowed.toString())
        }
        try {
            check(writableDatabase.insertWithOnConflict("outcomes", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "历史保存失败" }
        } catch (error: Exception) { markUnavailable(); throw error }
        listeners.forEach { listener -> try { listener() } catch (_: Exception) { } }
    }

    data class Entry(val account: String, val shop: String, val orderId: String, val timeMs: Long,
        val outcome: OutcomeType, val reason: String, val order: JSONObject)

    fun entries(account: String, limit: Int = 200): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query("outcomes", null, "account_key=?", arrayOf(account), null, null, "timestamp_ms DESC", limit.toString()).use { cursor ->
            while (cursor.moveToNext()) result.add(Entry(cursor.getString(cursor.getColumnIndexOrThrow("account_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("shop_id")), cursor.getString(cursor.getColumnIndexOrThrow("order_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")), OutcomeType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("outcome"))),
                cursor.getString(cursor.getColumnIndexOrThrow("reason")), JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("payload")))))
        }
        return result
    }

    fun blockedOrderIds(account: String, shop: String): Set<String> {
        val ids = mutableSetOf<String>()
        readableDatabase.query("outcomes", arrayOf("order_id"), "account_key=? AND shop_id=?", arrayOf(account, shop), null, null, null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        return ids
    }

    fun todayCounts(account: String, nowMs: Long = System.currentTimeMillis()): Map<OutcomeType, Int> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        val start = checkNotNull(format.parse(format.format(Date(nowMs)))).time
        val latest = linkedMapOf<String, OutcomeType>()
        readableDatabase.query("outcomes", arrayOf("shop_id", "order_id", "outcome"),
            "account_key=? AND timestamp_ms>=? AND timestamp_ms<?", arrayOf(account, start.toString(), (start + 86400000L).toString()),
            null, null, "timestamp_ms ASC, rowid ASC").use { c ->
            while (c.moveToNext()) latest[c.getString(0) + ":" + c.getString(1)] = OutcomeType.valueOf(c.getString(2))
        }
        return latest.values.groupingBy { it }.eachCount()
    }

    fun todaySuccessCount(account: String, nowMs: Long = System.currentTimeMillis()): Int = todayCounts(account, nowMs)[OutcomeType.SUCCESS] ?: 0

    fun reconcile(entry: Entry, type: OutcomeType, reason: String) {
        requireHealthy()
        val values = ContentValues().apply { put("outcome", type.name); put("reason", reason) }
        try {
            check(writableDatabase.update("outcomes", values,
                "account_key=? AND shop_id=? AND order_id=? AND outcome=?",
                arrayOf(entry.account, entry.shop, entry.orderId, OutcomeType.UNKNOWN.name)) > 0) { "未知记录已变化，请刷新" }
        } catch (error: Exception) { markUnavailable(); throw error }
        listeners.forEach { listener -> try { listener() } catch (_: Exception) { } }
    }
}
