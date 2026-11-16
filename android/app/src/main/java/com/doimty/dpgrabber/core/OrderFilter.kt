package com.doimty.dpgrabber.core

import org.json.JSONArray
import org.json.JSONObject

/** 与 PC ITEM_NAME_FIELDS 对齐的名称候选字段（含嵌套 item / itemCategory）。 */
private val ITEM_NAME_FIELDS = listOf(
    "itemNames", "item_names", "itemName", "item_name", "items",
    "serviceNames", "serviceName", "itemCategoryName", "item", "itemCategory"
)

/** 与 PC ITEM_ID_FIELDS 对齐的类型 ID 候选字段。 */
private val ITEM_ID_FIELDS = listOf(
    "itemId", "itemSwitchId", "itemCategoryId", "typeId", "itemType", "serviceItemId", "categoryId"
)

/**
 * 默认名称映射，已按 PC 收紧：465 只认「连麦」。
 * 去掉「语音通话」「语音」是为了避免「文字语音条」被误判为连麦；
 * 这类基础类型交由 alwaysTypes 放行，不参与 ID 映射。
 */
val DEFAULT_ITEM_NAME_MAP: Map<Int, List<String>> = mapOf(
    465 to listOf("连麦"),
    2666 to listOf("视频"),
    2667 to listOf("游戏")
)

/** PC base_item_types：命中即放行，不参与开关判定。 */
val DEFAULT_ITEM_ALWAYS_TYPES: List<String> = listOf("文字语音条", "文语体验单")

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
    val enabledItems: List<Int> = emptyList(),
    val nameMap: Map<Int, List<String>> = emptyMap(),
    val alwaysTypes: List<String> = DEFAULT_ITEM_ALWAYS_TYPES,
    val switchListKnown: Boolean = false
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
        val ids = orderTypeIds(order, nameMap)
        val names = orderNames(order)
        // 开关列表可信已知且全部关闭：命中任一已知类型词即拒绝，基础类型与认不出的仍放行。
        if (switchListKnown && enabledItems.isEmpty() && names.isNotEmpty() &&
            !matchAlwaysType(names, alwaysTypes) &&
            names.any { name -> allKnownTypeWords().any { it in name } }
        ) {
            return false to "接单类型已全部关闭（${names.joinToString("、")}）"
        }
        if (orderMatchesItems(order, enabledItems, nameMap, alwaysTypes) == false) {
            val detail = if (ids.isNotEmpty()) ids.joinToString("、") else names.joinToString("、")
            return false to "该接单类型已关闭（$detail）"
        }
        return true to ""
    }

    private fun allKnownTypeWords(): Set<String> =
        (DEFAULT_ITEM_NAME_MAP.values.flatten() + nameMap.values.flatten())
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
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

/** 缺失或不匹配的缓存作用域视为未知：既不限制类型，也不启用“全关”判定。 */
fun orderFilterFromConfig(cfg: AppConfig, scope: ItemSwitchScope? = null): OrderFilter {
    val scoped = scope != null && scope.accountKey.isNotEmpty() && scope.shopId.isNotEmpty() &&
        cfg.itemSwitchAccountKey == scope.accountKey && cfg.itemSwitchShopId == scope.shopId
    return OrderFilter(
        cfg.minAmount, cfg.maxAmount, cfg.memoMode, cfg.memoKeywords, cfg.blacklist,
        if (scoped) cfg.itemSwitchIds else emptyList(),
        cfg.itemNameMap, cfg.itemAlwaysTypes,
        scoped && cfg.itemSwitchListKnown
    )
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

private fun addItemIds(value: Any?, ids: MutableSet<Int>) {
    if (value is JSONArray) {
        for (i in 0 until value.length()) addItemIds(value.opt(i), ids)
    } else positiveItemId(value)?.let { ids.add(it) }
}

/** dict 读 names / name / itemName 子键，names 可为数组；数组递归展开。 */
private fun collectNameStrings(value: Any?, out: MutableList<String>) {
    when (value) {
        is JSONArray -> for (i in 0 until value.length()) collectNameStrings(value.opt(i), out)
        is JSONObject -> for (key in listOf("names", "name", "itemName")) {
            if (value.has(key)) collectNameStrings(value.opt(key), out)
        }
        is String -> out.add(value)
    }
}

/** 归一化后的名称候选（小写、去空）；认不出时为空列表。 */
fun orderNames(order: JSONObject): List<String> {
    val raw = mutableListOf<String>()
    for (key in ITEM_NAME_FIELDS) collectNameStrings(order.opt(key), raw)
    return raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}

/** nameMap 自定义词优先于同 ID 的默认词。 */
private fun effectiveNameMap(nameMap: Map<Int, List<String>>): Map<Int, List<String>> =
    DEFAULT_ITEM_NAME_MAP + nameMap

private fun orderTypeIds(order: JSONObject, nameMap: Map<Int, List<String>>): Set<Int> {
    val ids = linkedSetOf<Int>()
    for (key in ITEM_ID_FIELDS) addItemIds(order.opt(key), ids)
    if (ids.isNotEmpty()) return ids
    val names = orderNames(order)
    for (name in names) {
        for ((id, words) in effectiveNameMap(nameMap)) {
            if (words.any { it in name }) ids.add(id)
        }
    }
    return ids
}

/** 向后兼容的纯函数：只用默认映射推断 ID。 */
fun orderItemIds(order: JSONObject): Set<Int> = orderTypeIds(order, emptyMap())

/** 基础类型命中即放行（与 PC base_item_types 一致）。 */
fun matchAlwaysType(names: List<String>, alwaysTypes: List<String>): Boolean {
    val words = alwaysTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    return names.any { name -> words.any { it in name } }
}

/**
 * 类型联动判定：true 放行 / false 拒绝 / null 认不出（fail open，不猜）。
 * 语义严格对齐 PC：无 enabled 直接放行；ID 明确则只看 ID；无名称返回 null；
 * 命中基础类型放行；候选词中启用词优先于禁用词。
 */
fun orderMatchesItems(
    order: JSONObject,
    enabled: List<Int>,
    nameMap: Map<Int, List<String>>,
    alwaysTypes: List<String>
): Boolean? {
    if (enabled.isEmpty()) return true
    val ids = orderTypeIds(order, nameMap)
    if (ids.isNotEmpty()) return ids.any { it in enabled }
    val names = orderNames(order)
    if (names.isEmpty()) return null
    if (matchAlwaysType(names, alwaysTypes)) return true
    val enabledSet = enabled.toSet()
    val enabledWords = linkedSetOf<String>()
    val disabledWords = linkedSetOf<String>()
    for ((id, words) in effectiveNameMap(nameMap)) {
        val normalized = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (id in enabledSet) enabledWords.addAll(normalized) else disabledWords.addAll(normalized)
    }
    if (names.any { name -> enabledWords.any { it in name } }) return true
    if (names.any { name -> disabledWords.any { it in name } }) return false
    return null
}
