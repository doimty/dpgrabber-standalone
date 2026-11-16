package com.doimty.dpgrabber.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The section 7.1 filter chain (PC tool `handle_order` + `match_keyword`).
 * `check(order)` returns (passed, reason).
 */
class OrderFilter(
    val minAmount: Double,
    val maxAmount: Double,
    val memoMode: Int,
    memoKeywords: List<String>,
    blacklist: List<String>,
    val enabledItems: List<Int> = emptyList()
) {
    val memoKeywords: List<String> =
        memoKeywords.map { it.trim() }.filter { it.isNotEmpty() }
    val blacklist: Set<String> =
        blacklist.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun check(order: JSONObject?): Pair<Boolean, String> {
        if (order == null) return false to "订单数据异常"
        if (!order.has("totalAmount") || order.isNull("totalAmount")) {
            return false to "订单缺少金额字段"
        }
        if (orderIdOf(order.toFieldMap()).isEmpty()) return false to "订单缺少 id 字段"
        val amount = amountYuanFromCents(order.opt("totalAmount"))
        if (!amount.isFinite() || amount < 0) return false to "订单金额无效"
        val wechat = order.optString("userWechatId", "")
        if (wechat.isNotEmpty() && wechat in blacklist) {
            return false to "客户在黑名单中: $wechat"
        }
        if (amount < minAmount) return false to "金额 %.2f 元低于最低金额".format(amount)
        if (maxAmount > 0 && amount > maxAmount) {
            return false to "金额 %.2f 元高于最高金额".format(amount)
        }
        val memo = order.optString("userMemo", "")
        if (memoMode == 1 && memo.isNotEmpty()) return false to "订单有备注，不符合要求"
        if (memo.isNotEmpty() && memoKeywords.isNotEmpty()) {
            val lowered = memo.lowercase()
            for (keyword in memoKeywords) {
                if (keyword.lowercase() in lowered) {
                    return false to "订单备注命中关键词[$keyword]"
                }
            }
        }
        val itemIds = orderItemIds(order)
        if (enabledItems.isNotEmpty() && itemIds.isNotEmpty() && itemIds.none { it in enabledItems }) {
            return false to "订单类型未启用（名称仅按已知语音/视频/游戏映射）"
        }
        return true to ""
    }
}

/**
 * 自有字段转换：JVM 的 org.json 自带 toMap()，Android 系统 JSONObject 没有该方法。
 * 名称不能相同，否则 JVM 优先绑定到成员函数，运行到设备时才出现 NoSuchMethodError。
 */
fun JSONObject.toFieldMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = get(key)
    }
    return map
}

/** 与 HistoryStore.accountKey 一致，但核心不依赖 Android 存储。 */
fun accountKeyForPhone(phone: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(phone.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

data class ItemSwitchScope(val accountKey: String, val shopId: String)

/** 缺失或不匹配的缓存作用域视为未知，不把其他账号/店铺的类型用于过滤。 */
fun orderFilterFromConfig(cfg: AppConfig, scope: ItemSwitchScope? = null): OrderFilter {
    val enabled = if (scope != null && scope.accountKey.isNotEmpty() && scope.shopId.isNotEmpty() &&
        cfg.itemSwitchAccountKey == scope.accountKey && cfg.itemSwitchShopId == scope.shopId) cfg.itemSwitchIds else emptyList()
    return OrderFilter(cfg.minAmount, cfg.maxAmount, cfg.memoMode, cfg.memoKeywords, cfg.blacklist, enabled)
}

/** 类型 ID 不接受布尔、浮点数、小数文本或溢出。无效候选不遮挡后续字段。 */
fun positiveItemId(value: Any?): Int? {
    val text = when (value) {
        is Byte, is Short, is Int, is Long -> value.toString()
        is String -> value.trim()
        else -> return null
    }
    return if (text.matches(Regex("[0-9]+"))) text.toIntOrNull()?.takeIf { it > 0 } else null
}

fun orderItemIds(order: JSONObject): Set<Int> {
    val ids = linkedSetOf<Int>()
    fun addId(value: Any?) {
        if (value is JSONArray) {
            for (i in 0 until value.length()) addId(value.opt(i))
        } else positiveItemId(value)?.let { ids.add(it) }
    }
    for (key in listOf("itemId", "itemSwitchId", "itemCategoryId", "typeId", "itemType", "serviceItemId", "categoryId")) {
        addId(order.opt(key))
    }
    if (ids.isNotEmpty()) return ids
    fun addName(value: Any?) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) addName(value.opt(i))
            is JSONObject -> { addName(value.opt("name")); addName(value.opt("itemName")) }
            is String -> {
                val name = value.trim().lowercase()
                // 已知名称映射与 PC 对齐；“文字语音条”等包含语音词的店铺归属仍需独立核验。
                if (listOf("连麦", "语音通话", "语音").any { it in name }) ids.add(465)
                if ("视频" in name) ids.add(2666)
                if ("游戏" in name) ids.add(2667)
            }
        }
    }
    for (key in listOf("itemNames", "item_names", "itemName", "item_name", "items", "serviceNames", "serviceName", "itemCategoryName")) {
        addName(order.opt(key))
    }
    return ids
}
