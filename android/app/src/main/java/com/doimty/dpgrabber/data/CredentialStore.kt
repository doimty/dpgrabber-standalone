package com.doimty.dpgrabber.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.doimty.dpgrabber.core.AppConfig
import org.json.JSONObject

/** 凭据只写加密存储；密钥不可用时仅在当前进程保留。 */
class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val settingsPrefs = appContext.getSharedPreferences("dpgrabber_settings", Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences("dpgrabber_credentials_plain", Context.MODE_PRIVATE)
    private val prefs: SharedPreferences? = try {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(appContext, "dpgrabber_credentials", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (_: Exception) { null }
    val encrypted: Boolean get() = prefs != null

    init { try { migrateLegacy() } catch (_: Exception) { /* 保留未验证的旧字段。 */ } }

    private fun readPair(source: SharedPreferences?): Pair<String, String>? = try {
        completePair(source?.getString("phone", null), source?.getString("password", null))
    } catch (_: Exception) { null }

    private fun readConfigJson(): JSONObject? = try {
        settingsPrefs.getString("config_json", null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    @Synchronized
    private fun migrateLegacy() {
        val target = prefs ?: return
        val raw = readConfigJson()
        val pair = readPair(target) ?: readPair(legacyPrefs)
            ?: completePair(raw?.optString("phone", ""), raw?.optString("password", "")) ?: return
        // 一个来源必须同时具有完整账号与密码，绝不混合不同来源的半对凭据。
        if (!target.edit().putString("phone", pair.first).putString("password", pair.second).commit()) return
        if (readPair(target) != pair) return
        removeLegacyFields(raw)
    }

    private fun removeLegacyFields(raw: JSONObject?) {
        legacyPrefs.edit().remove("phone").remove("password").commit()
        if (raw != null && (raw.has("phone") || raw.has("password"))) {
            raw.remove("phone")
            raw.remove("password")
            settingsPrefs.edit().putString("config_json", raw.toString()).commit()
        }
    }

    fun saveAccount(phone: String, password: String): Boolean {
        memoryPair = phone to password
        val target = prefs ?: return false
        return try {
            val stored = target.edit().putString("phone", phone).putString("password", password).commit() &&
                target.getString("phone", null) == phone && target.getString("password", null) == password
            if (stored) removeLegacyFields(readConfigJson())
            stored
        } catch (_: Exception) { false }
    }

    fun loadPhone(): String = (memoryPair ?: readPair(prefs))?.first ?: ""
    fun loadPassword(): String = (memoryPair ?: readPair(prefs))?.second ?: ""

    /**
     * 店员姓名与平台 ID：与账号同样只写加密存储，密钥不可用时退回内存。
     * 返回值表示是否真正落盘；未落盘时当前进程内仍可读取，界面不会因此空白。
     */
    fun saveClerk(name: String, id: Int): Boolean {
        memoryClerk = name to id
        val target = prefs ?: return false
        return try {
            target.edit().putString("clerk_name", name).putInt("clerk_id", id).commit() &&
                target.getString("clerk_name", null) == name && target.getInt("clerk_id", 0) == id
        } catch (_: Exception) { false }
    }

    /** 无值时返回空串与 0；调用方据此显示兜底文案。 */
    fun loadClerk(): Pair<String, Int> = memoryClerk ?: try {
        val target = prefs
        if (target == null) "" to 0
        else (target.getString("clerk_name", null) ?: "") to target.getInt("clerk_id", 0)
    } catch (_: Exception) { "" to 0 }

    fun clearAccount() {
        SessionRepository.invalidate()
        memoryPair = "" to ""
        try { prefs?.edit()?.remove("phone")?.remove("password")?.commit() } catch (_: Exception) { }
        try { legacyPrefs.edit().remove("phone").remove("password").commit() } catch (_: Exception) { }
        try {
            readConfigJson()?.let { raw ->
                raw.remove("phone"); raw.remove("password")
                settingsPrefs.edit().putString("config_json", raw.toString()).commit()
            }
        } catch (_: Exception) { }
    }

    fun saveConfig(config: AppConfig) {
        val previous = readConfigJson()
        check(previous == null || (previous.optString("password", "").isBlank() && previous.optString("phone", "").isBlank())) {
            "旧凭据尚未安全迁移，请恢复加密存储后保存"
        }
        check(settingsPrefs.edit().putString("config_json", config.toJson().toString()).commit()) { "设置保存失败" }
    }

    fun loadConfig(): AppConfig = try {
        AppConfig.fromJson(readConfigJson() ?: JSONObject()).copy(phone = "", password = "").validate()
    } catch (_: Exception) { AppConfig.defaults() }

    fun saveConfigJsonForTest(rawJson: String) {
        settingsPrefs.edit().putString("config_json", rawJson).commit()
    }

    companion object {
        @Volatile private var memoryPair: Pair<String, String>? = null
        /** 姓名与 ID 的内存回退；null 表示本进程尚未写入过。 */
        @Volatile private var memoryClerk: Pair<String, Int>? = null
        internal fun completePair(phone: String?, password: String?): Pair<String, String>? =
            if (!phone.isNullOrBlank() && !password.isNullOrBlank()) phone to password else null
    }
}
