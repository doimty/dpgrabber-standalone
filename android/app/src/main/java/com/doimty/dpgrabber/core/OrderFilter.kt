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
    blacklist: List<String>
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
        if (orderIdOf(order.toMap()).isEmpty()) return false to "订单缺少 id 字段"
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
        return true to ""
    }
}

/** JSONObject -> Map<String, Any?> bridge for [orderIdOf]. */
fun JSONObject.toMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = get(key)
    }
    return map
}

/** Convenience: build a filter from an [AppConfig]. */
fun orderFilterFromConfig(cfg: AppConfig): OrderFilter =
    OrderFilter(cfg.minAmount, cfg.maxAmount, cfg.memoMode, cfg.memoKeywords, cfg.blacklist)
