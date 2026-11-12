package com.doimty.dpgrabber.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.doimty.dpgrabber.core.AppConfig
import org.json.JSONObject

/**
 * Storage for the login account and the app config.
 *
 * * Phone + password go to EncryptedSharedPreferences (AndroidX Security,
 *   AES-256-GCM, key in the Android Keystore). If encrypted storage cannot
 *   be created on this device (e.g. a broken Keystore), it falls back to
 *   plain SharedPreferences MODE_PRIVATE and records that in [encrypted];
 *   the UI shows a warning in that case.
 * * The session token is never persisted anywhere (memory only).
 * * Other settings live in plain SharedPreferences as JSON.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences
    val encrypted: Boolean

    init {
        var enc: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            enc = EncryptedSharedPreferences.create(
                appContext,
                "dpgrabber_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("CredentialStore", "EncryptedSharedPreferences unavailable, using plain prefs", e)
        }
        encrypted = enc != null
        prefs = enc ?: appContext.getSharedPreferences(
            "dpgrabber_credentials_plain", Context.MODE_PRIVATE
        )
    }

    private val settingsPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("dpgrabber_settings", Context.MODE_PRIVATE)
    }

    // -- account (encrypted when possible) ------------------------------

    fun saveAccount(phone: String, password: String) {
        prefs.edit()
            .putString("phone", phone)
            .putString("password", password)
            .apply()
    }

    fun loadPhone(): String = prefs.getString("phone", "") ?: ""

    fun loadPassword(): String = prefs.getString("password", "") ?: ""

    fun clearAccount() {
        prefs.edit().remove("phone").remove("password").apply()
    }

    // -- settings (plain prefs, JSON blob) -------------------------------

    fun saveConfig(config: AppConfig) {
        settingsPrefs.edit()
            .putString("config_json", config.toJson().toString())
            .apply()
    }

    fun loadConfig(): AppConfig {
        val raw = settingsPrefs.getString("config_json", null) ?: return AppConfig.defaults()
        return try {
            AppConfig.fromJson(JSONObject(raw)).validate()
        } catch (e: Exception) {
            Log.w("CredentialStore", "stored config invalid, using defaults", e)
            AppConfig.defaults()
        }
    }

    /** Test-only helper: write a config JSON string directly. */
    @SuppressLint("ApplySharedPref")
    fun saveConfigJsonForTest(rawJson: String) {
        settingsPrefs.edit().putString("config_json", rawJson).commit()
    }
}
