package com.doimty.dpgrabber.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * App configuration (mirrors PC config.py / PROTOCOL.md section 9.2).
 *
 * The phone/password are stored separately in encrypted storage
 * (CredentialStore); this config holds everything else, including the
 * AES key/IV pairs. No real account, token or password is ever shipped
 * in code: phone/password default to empty and the user fills them in.
 *
 * Key/IV defaults follow PROTOCOL.md sections 4 and 10.6: the primary
 * pair is DPGrabber's AES-256 set, the alternate pair is the PC
 * reference tool's AES-128 set. The API client tries the primary pair
 * first and falls back to the alternate pair when decryption fails.
 */
data class AppConfig(
    val phone: String = "",
    val password: String = "",
    val shopId: Int = 92,
    val minAmount: Double = 10.0,
    val maxAmount: Double = 0.0,
    val userDelaySec: Int = 0,
    val pollIntervalSec: Double = 0.5,
    val memoMode: Int = 0,
    val memoKeywords: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val aesKeyHex: String = ApiClient.DEFAULT_AES_KEY_HEX,
    val aesIvHex: String = ApiClient.DEFAULT_AES_IV_HEX,
    val aesKeyHexAlt: String = ApiClient.ALT_AES_KEY_HEX,
    val aesIvHexAlt: String = ApiClient.ALT_AES_IV_HEX,
    val requestTimeoutSec: Double = 15.0,
    val verifyTls: Boolean = true,
    val seenTtlSec: Double = 60.0
) {
    companion object {
        fun defaults(): AppConfig = AppConfig()

        fun fromJson(json: JSONObject): AppConfig {
            val base = defaults()
            return AppConfig(
                phone = json.optString("phone", base.phone),
                password = json.optString("password", base.password),
                shopId = json.optInt("shop_id", base.shopId),
                minAmount = json.optDouble("min_amount", base.minAmount),
                maxAmount = json.optDouble("max_amount", base.maxAmount),
                userDelaySec = json.optInt("user_delay", base.userDelaySec),
                pollIntervalSec = json.optDouble("poll_interval_sec", base.pollIntervalSec),
                memoMode = json.optInt("memo_mode", base.memoMode),
                memoKeywords = json.optJSONArray("memo_keywords")?.toStringList() ?: base.memoKeywords,
                blacklist = json.optJSONArray("blacklist")?.toStringList() ?: base.blacklist,
                aesKeyHex = json.optString("aes_key_hex", base.aesKeyHex),
                aesIvHex = json.optString("aes_iv_hex", base.aesIvHex),
                aesKeyHexAlt = json.optString("aes_key_hex_alt", base.aesKeyHexAlt),
                aesIvHexAlt = json.optString("aes_iv_hex_alt", base.aesIvHexAlt),
                requestTimeoutSec = json.optDouble("request_timeout_sec", base.requestTimeoutSec),
                verifyTls = json.optBoolean("verify_tls", base.verifyTls),
                seenTtlSec = json.optDouble("seen_ttl_sec", base.seenTtlSec)
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("phone", phone)
        json.put("password", password)
        json.put("shop_id", shopId)
        json.put("min_amount", minAmount)
        json.put("max_amount", maxAmount)
        json.put("user_delay", userDelaySec)
        json.put("poll_interval_sec", pollIntervalSec)
        json.put("memo_mode", memoMode)
        json.put("memo_keywords", JSONArray(memoKeywords))
        json.put("blacklist", JSONArray(blacklist))
        json.put("aes_key_hex", aesKeyHex)
        json.put("aes_iv_hex", aesIvHex)
        json.put("aes_key_hex_alt", aesKeyHexAlt)
        json.put("aes_iv_hex_alt", aesIvHexAlt)
        json.put("request_timeout_sec", requestTimeoutSec)
        json.put("verify_tls", verifyTls)
        json.put("seen_ttl_sec", seenTtlSec)
        return json
    }

    /**
     * Validate (fills nothing — defaults are applied by [fromJson]);
     * throws IllegalArgumentException on invalid values.
     */
    fun validate(): AppConfig {
        Crypto.validateKeyIv(aesKeyHex, aesIvHex)
        Crypto.validateKeyIv(aesKeyHexAlt, aesIvHexAlt)
        require(shopId > 0) { "shop_id must be a positive integer" }
        require(minAmount >= 0 && maxAmount >= 0) { "amounts must not be negative" }
        require(maxAmount == 0.0 || maxAmount >= minAmount) {
            "max_amount must be 0 (unlimited) or >= min_amount"
        }
        require(userDelaySec >= 0) { "user_delay must not be negative" }
        require(pollIntervalSec > 0) { "poll_interval_sec must be positive" }
        require(memoMode == 0 || memoMode == 1) {
            "memo_mode must be 0 (ignore memos) or 1 (drop memo orders)"
        }
        require(requestTimeoutSec > 0) { "request_timeout_sec must be positive" }
        require(seenTtlSec > 0) { "seen_ttl_sec must be positive" }
        return copy(
            memoKeywords = memoKeywords.map { it.trim() }.filter { it.isNotEmpty() },
            blacklist = blacklist.map { it.trim() }.filter { it.isNotEmpty() },
            phone = phone.trim()
        )
    }
}

private fun JSONArray.toStringList(): List<String> {
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        val value = optString(i, "")
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}

/** Mask a phone number for logs: keep first 3 and last 4 digits. */
fun maskPhone(phone: String): String {
    if (phone.length <= 7) return "***"
    return phone.substring(0, 3) + "****" + phone.takeLast(4)
}
