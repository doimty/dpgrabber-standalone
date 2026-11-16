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
    /** Hall-order confirm window (seconds) from the task start; used when
     * the order has a createTime, otherwise the fallback window applies
     * (DPGConfirmationTask, DPGEngine.m). */
    val randomConfirmWindowSec: Double = 20.0,
    val randomConfirmFallbackSec: Double = 20.0,
    /** Delay between confirm retries inside the window (seconds). */
    val confirmRetryDelaySec: Double = 0.5,
    val pollIntervalSec: Double = 0.6,
    val pollJitterSec: Double = 0.2,
    val confirmWaitByShop: Map<String, Double> = mapOf("92" to 5.0, "50" to 3.0, "105" to 3.0, "1391" to 3.0),
    val globalWaitSec: Double = 0.0,
    val busyCooldownSec: Int = 60,
    val autoWaitOffset: Double = 0.0,
    val itemSwitchIds: List<Int> = emptyList(),
    val itemSwitchAccountKey: String = "",
    val itemSwitchShopId: String = "",
    val runDurationSec: Double = 0.0,
    val restDurationSec: Double = 0.0,
    val autoStopAfterGrab: Boolean = true,
    val onlineAfterLogin: Boolean = false,
    val memoMode: Int = 0,
    val memoKeywords: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val aesKeyHex: String = ApiClient.DEFAULT_AES_KEY_HEX,
    val aesIvHex: String = ApiClient.DEFAULT_AES_IV_HEX,
    val aesKeyHexAlt: String = ApiClient.ALT_AES_KEY_HEX,
    val aesIvHexAlt: String = ApiClient.ALT_AES_IV_HEX,
    val requestTimeoutSec: Double = 15.0,
    val verifyTls: Boolean = true,
    val seenTtlSec: Double = 60.0,
    /** 密钥与 URL 不写在这里（config_json 是明文），只存加密 prefs。 */
    val pushEnabled: Boolean = false,
    val pushOnSuccess: Boolean = true,
    val pushOnFailure: Boolean = false,
    val pushOnRisk: Boolean = false,
    val pushProvider: String = "bark",
    /** 接单类型名称映射：自定义类型 ID -> 关键词，优先于默认映射。 */
    val itemNameMap: Map<Int, List<String>> = emptyMap(),
    /** 命中即放行的基础类型（PC base_item_types）。 */
    val itemAlwaysTypes: List<String> = listOf("文字语音条", "文语体验单"),
    /** 开关列表是否可信已知；为 true 且启用列表为空时，全关类型词命中即拒绝。 */
    val itemSwitchListKnown: Boolean = false
) {
    companion object {
        /** 推送渠道白名单；非法值由 [validate] 回退为 bark，不抛错。 */
        val PUSH_PROVIDERS = setOf("bark", "serverchan", "pushplus", "webhook")

        fun normalizePushProvider(value: String): String =
            value.trim().lowercase().takeIf { it in PUSH_PROVIDERS } ?: "bark"

        fun defaults(): AppConfig = AppConfig()

        fun fromJson(json: JSONObject): AppConfig {
            val base = defaults()
            return AppConfig(
                phone = json.optString("phone", base.phone),
                password = json.optString("password", base.password),
                shopId = json.optInt("shop_id", base.shopId),
                minAmount = json.optDouble("min_amount", base.minAmount),
                maxAmount = json.optDouble("max_amount", base.maxAmount),
                randomConfirmWindowSec = json.optDouble(
                    "random_confirm_window_sec", base.randomConfirmWindowSec
                ),
                randomConfirmFallbackSec = json.optDouble(
                    "random_confirm_fallback_sec", base.randomConfirmFallbackSec
                ),
                confirmRetryDelaySec = json.optDouble(
                    "confirm_retry_delay_sec", base.confirmRetryDelaySec
                ),
                pollIntervalSec = json.optDouble("poll_interval_sec", base.pollIntervalSec),
                pollJitterSec = json.optDouble("poll_jitter_sec", base.pollJitterSec),
                confirmWaitByShop = json.optJSONObject("confirm_wait_by_shop")?.let { waits ->
                    waits.keys().asSequence().associateWith { waits.getDouble(it) }
                } ?: base.confirmWaitByShop,
                globalWaitSec = json.optDouble("global_wait_sec", base.globalWaitSec),
                busyCooldownSec = if (json.has("busy_cooldown_sec")) requireNotNull(positiveItemId(json.opt("busy_cooldown_sec"))) { "忙碌冷却必须为整数" } else base.busyCooldownSec,
                autoWaitOffset = if (json.has("auto_wait_offset")) json.getDouble("auto_wait_offset") else base.autoWaitOffset,
                itemSwitchIds = if (json.has("item_switch_ids")) {
                    val ids = json.getJSONArray("item_switch_ids")
                    (0 until ids.length()).map { requireNotNull(positiveItemId(ids.opt(it))) { "接单类型必须为正整数" } }.distinct()
                } else base.itemSwitchIds,
                itemSwitchAccountKey = json.optString("item_switch_account_key", ""),
                itemSwitchShopId = json.optString("item_switch_shop_id", ""),
                runDurationSec = json.optDouble("run_duration_sec", 0.0),
                restDurationSec = json.optDouble("rest_duration_sec", 0.0),
                autoStopAfterGrab = json.optBoolean("auto_stop_after_grab", true),
                onlineAfterLogin = json.optBoolean("online_after_login", false),
                memoMode = json.optInt("memo_mode", base.memoMode),
                memoKeywords = json.optJSONArray("memo_keywords")?.toStringList() ?: base.memoKeywords,
                blacklist = json.optJSONArray("blacklist")?.toStringList() ?: base.blacklist,
                aesKeyHex = json.optString("aes_key_hex", base.aesKeyHex),
                aesIvHex = json.optString("aes_iv_hex", base.aesIvHex),
                aesKeyHexAlt = json.optString("aes_key_hex_alt", base.aesKeyHexAlt),
                aesIvHexAlt = json.optString("aes_iv_hex_alt", base.aesIvHexAlt),
                requestTimeoutSec = json.optDouble("request_timeout_sec", base.requestTimeoutSec),
                verifyTls = json.optBoolean("verify_tls", base.verifyTls),
                seenTtlSec = json.optDouble("seen_ttl_sec", base.seenTtlSec),
                pushEnabled = json.optBoolean("push_enabled", base.pushEnabled),
                pushOnSuccess = json.optBoolean("push_on_success", base.pushOnSuccess),
                pushOnFailure = json.optBoolean("push_on_failure", base.pushOnFailure),
                pushOnRisk = json.optBoolean("push_on_risk", base.pushOnRisk),
                pushProvider = json.optString("push_provider", base.pushProvider),
                itemNameMap = json.optJSONObject("item_name_map")?.let { obj ->
                    obj.keys().asSequence().associate { key ->
                        requireNotNull(positiveItemId(key)) { "接单类型名称映射的 key 必须为正整数" } to
                            (obj.optJSONArray(key)?.toStringList() ?: emptyList())
                    }
                } ?: base.itemNameMap,
                itemAlwaysTypes = json.optJSONArray("item_always_types")?.toStringList() ?: base.itemAlwaysTypes,
                itemSwitchListKnown = json.optBoolean("item_switch_list_known", base.itemSwitchListKnown)
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("schema_version", 2)
        json.put("shop_id", shopId)
        json.put("min_amount", minAmount)
        json.put("max_amount", maxAmount)
        json.put("random_confirm_window_sec", randomConfirmWindowSec)
        json.put("random_confirm_fallback_sec", randomConfirmFallbackSec)
        json.put("confirm_retry_delay_sec", confirmRetryDelaySec)
        json.put("poll_interval_sec", pollIntervalSec)
        json.put("poll_jitter_sec", pollJitterSec)
        json.put("confirm_wait_by_shop", JSONObject(confirmWaitByShop))
        json.put("global_wait_sec", globalWaitSec)
        json.put("busy_cooldown_sec", busyCooldownSec)
        json.put("auto_wait_offset", autoWaitOffset)
        json.put("item_switch_ids", JSONArray(itemSwitchIds.distinct()))
        json.put("item_switch_account_key", itemSwitchAccountKey)
        json.put("item_switch_shop_id", itemSwitchShopId)
        json.put("run_duration_sec", runDurationSec)
        json.put("rest_duration_sec", restDurationSec)
        json.put("auto_stop_after_grab", autoStopAfterGrab)
        json.put("online_after_login", onlineAfterLogin)
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
        json.put("push_enabled", pushEnabled)
        json.put("push_on_success", pushOnSuccess)
        json.put("push_on_failure", pushOnFailure)
        json.put("push_on_risk", pushOnRisk)
        json.put("push_provider", pushProvider)
        val nameMapJson = JSONObject()
        itemNameMap.forEach { (id, words) -> nameMapJson.put(id.toString(), JSONArray(words)) }
        json.put("item_name_map", nameMapJson)
        json.put("item_always_types", JSONArray(itemAlwaysTypes))
        json.put("item_switch_list_known", itemSwitchListKnown)
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
        require(minAmount.isFinite() && maxAmount.isFinite() && minAmount >= 0 && maxAmount >= 0) { "amounts must not be negative" }
        require(maxAmount == 0.0 || maxAmount >= minAmount) {
            "max_amount must be 0 (unlimited) or >= min_amount"
        }
        require(randomConfirmWindowSec in 0.1..86400.0) {
            "random_confirm_window_sec must be between 0.1 and 86400"
        }
        require(randomConfirmFallbackSec in 0.1..86400.0) {
            "random_confirm_fallback_sec must be between 0.1 and 86400"
        }
        require(confirmRetryDelaySec in 0.0..60.0) {
            "confirm_retry_delay_sec must be between 0 and 60"
        }
        require(pollIntervalSec.isFinite() && pollIntervalSec > 0) { "poll_interval_sec must be positive" }
        require(pollJitterSec.isFinite() && pollJitterSec >= 0) { "轮询抖动必须为非负数" }
        require(busyCooldownSec in 1..600) { "忙碌冷却必须为1–600秒" }
        require(autoWaitOffset.isFinite() && autoWaitOffset in -0.5..0.5 &&
            java.math.BigDecimal.valueOf(autoWaitOffset).stripTrailingZeros().scale() <= 3) { "等待偏移须为-0.5至0.5秒，最多3位小数" }
        require(itemSwitchIds.all { it > 0 }) { "接单类型必须为正整数" }
        require(itemSwitchAccountKey.isEmpty() || itemSwitchAccountKey.matches(Regex("[0-9a-f]{64}"))) { "接单类型账号作用域必须为SHA256摘要" }
        require(itemSwitchShopId.isEmpty() || positiveItemId(itemSwitchShopId) != null) { "接单类型店铺作用域无效" }
        require(itemNameMap.keys.all { it > 0 }) { "类型名称映射的 key 必须为正整数" }
        // 与 PC pc/config.py 一致：先 trim 再丢空串。整项只有空白视为结构性非法；
        // 原本就是空列表则整 key 丢弃，避免保留空列表导致 toJson/fromJson 往返不准。
        val normalizedNameMap = linkedMapOf<Int, List<String>>()
        for ((id, words) in itemNameMap) {
            val cleaned = words.map { it.trim() }.filter { it.isNotEmpty() }
            require(cleaned.isNotEmpty() || words.isEmpty()) { "类型名称映射的关键词不得为空" }
            if (cleaned.isNotEmpty()) normalizedNameMap[id] = cleaned
        }
        val normalizedAlwaysTypes = itemAlwaysTypes.map { it.trim() }.filter { it.isNotEmpty() }
        require(itemAlwaysTypes.isEmpty() || normalizedAlwaysTypes.isNotEmpty()) { "基础类型名不得为空" }
        require(globalWaitSec.isFinite() && globalWaitSec in 0.0..120.0) { "全局等待必须为0–120秒" }
        require(confirmWaitByShop.all { it.key.toIntOrNull()?.let { id -> id > 0 } == true && it.value.isFinite() && it.value in 0.0..120.0 }) { "店铺等待必须为0–120秒" }
        require(runDurationSec.isFinite() && runDurationSec >= 0 && restDurationSec.isFinite() && restDurationSec >= 0) { "运行/休息时长必须为非负数" }
        require(memoMode == 0 || memoMode == 1) {
            "memo_mode must be 0 (ignore memos) or 1 (drop memo orders)"
        }
        require(requestTimeoutSec.isFinite() && requestTimeoutSec > 0 && requestTimeoutSec <= 300) { "request_timeout_sec must be positive" }
        require(seenTtlSec.isFinite() && seenTtlSec > 0 && seenTtlSec <= 86400) { "seen_ttl_sec must be positive" }
        return copy(
            memoKeywords = memoKeywords.map { it.trim() }.filter { it.isNotEmpty() },
            blacklist = blacklist.map { it.trim() }.filter { it.isNotEmpty() },
            phone = phone.trim(),
            itemSwitchIds = itemSwitchIds.distinct(),
            pushProvider = normalizePushProvider(pushProvider),
            itemNameMap = normalizedNameMap,
            itemAlwaysTypes = normalizedAlwaysTypes
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
