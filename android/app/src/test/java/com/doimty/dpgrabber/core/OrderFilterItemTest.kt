package com.doimty.dpgrabber.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 接单类型三处修正的离线反例：零真实网络。 */
class OrderFilterItemTest {
    private fun order() = JSONObject().put("id", "o").put("totalAmount", 2500)

    private fun filter(
        enabled: List<Int> = emptyList(),
        nameMap: Map<Int, List<String>> = emptyMap(),
        alwaysTypes: List<String> = DEFAULT_ITEM_ALWAYS_TYPES,
        known: Boolean = false
    ) = OrderFilter(0.0, 0.0, 0, emptyList(), emptyList(), enabled, nameMap, alwaysTypes, known)

    private fun reject(block: () -> Unit) {
        try { block(); fail("应拒绝无效值") } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `item与itemCategory的names数组被识别`() {
        for (key in listOf("item", "itemCategory")) {
            val o = order().put(key, JSONObject().put("names", JSONArray().put("连麦")))
            assertEquals(setOf(465), orderItemIds(o))
            assertFalse(filter(enabled = listOf(2666)).check(o).first)
            assertTrue(filter(enabled = listOf(465)).check(o).first)
        }
    }

    @Test
    fun `嵌套dict的name与itemName子键均可识别`() {
        for (sub in listOf("name", "itemName")) {
            val o = order().put("item", JSONObject().put(sub, "视频"))
            assertEquals(setOf(2666), orderItemIds(o))
        }
    }

    @Test
    fun `文字语音条不误判连麦且基础类型放行`() {
        assertTrue(orderItemIds(order().put("itemName", "文字语音条")).isEmpty())
        assertTrue(matchAlwaysType(listOf("文字语音条"), DEFAULT_ITEM_ALWAYS_TYPES))
        val f = filter(enabled = listOf(2666), alwaysTypes = DEFAULT_ITEM_ALWAYS_TYPES, known = true)
        assertTrue(f.check(order().put("itemName", "文字语音条")).first)
        assertTrue(f.check(order().put("itemName", "文语体验单")).first)
    }

    @Test
    fun `开关列表已知且全关时命中类型词拒绝`() {
        val f = filter(known = true)
        val result = f.check(order().put("itemName", "游戏"))
        assertFalse(result.first)
        assertTrue(result.second.contains("全部关闭"))
        // 认不出的文本与无类型字段不受全关影响，fail open。
        assertTrue(f.check(order().put("itemName", "30分钟体验")).first)
        assertTrue(f.check(order()).first)
    }

    @Test
    fun `开关列表未知时全关判定不生效`() {
        assertTrue(filter(known = false).check(order().put("itemName", "游戏")).first)
    }

    @Test
    fun `nameMap自定义词优先默认映射`() {
        val map = mapOf(465 to listOf("自定义词"))
        val f = filter(enabled = listOf(465), nameMap = map)
        assertTrue(f.check(order().put("itemName", "自定义词")).first)
        // 465 的默认词已被自定义覆盖，连麦变成认不出，放行而不是误判。
        assertTrue(f.check(order().put("itemName", "连麦")).first)
        // 视频属于未启用的 2666，明确拒绝。
        assertFalse(f.check(order().put("itemName", "视频")).first)
    }

    @Test
    fun `认不出类型与无名称返回null并放行`() {
        assertNull(orderMatchesItems(order(), listOf(465), emptyMap(), DEFAULT_ITEM_ALWAYS_TYPES))
        assertNull(orderMatchesItems(order().put("itemName", "30分钟体验"), listOf(465), emptyMap(), DEFAULT_ITEM_ALWAYS_TYPES))
        assertTrue(filter(enabled = listOf(465)).check(order().put("itemName", "30分钟体验")).first)
    }

    @Test
    fun `ID明确时按ID判断且优先于名称`() {
        val o = order().put("itemId", 2666).put("itemName", "连麦")
        assertFalse(filter(enabled = listOf(465)).check(o).first)
        assertTrue(filter(enabled = listOf(2666)).check(o).first)
    }

    @Test
    fun `作用域不匹配时全关与启用列表都不生效`() {
        val key = accountKeyForPhone("10000000000")
        val cfg = AppConfig(itemSwitchListKnown = true, itemSwitchAccountKey = key, itemSwitchShopId = "92")
        val o = order().put("itemName", "游戏")
        assertFalse(orderFilterFromConfig(cfg, ItemSwitchScope(key, "92")).check(o).first)
        assertTrue(orderFilterFromConfig(cfg, ItemSwitchScope(accountKeyForPhone("10000000001"), "92")).check(o).first)
        assertTrue(orderFilterFromConfig(cfg).check(o).first)
    }

    @Test
    fun `旧JSON无新字段保持默认兼容`() {
        val cfg = AppConfig.fromJson(JSONObject()).validate()
        assertTrue(cfg.itemNameMap.isEmpty())
        assertEquals(listOf("文字语音条", "文语体验单"), cfg.itemAlwaysTypes)
        assertFalse(cfg.itemSwitchListKnown)
    }

    @Test
    fun `新字段往返并且validate拒绝非法映射`() {
        val cfg = AppConfig(
            itemNameMap = mapOf(465 to listOf(" 自定义词 ", "")),
            itemAlwaysTypes = listOf(" 文字语音条 ", " "),
            itemSwitchListKnown = true
        ).validate()
        assertEquals(mapOf(465 to listOf("自定义词")), cfg.itemNameMap)
        assertEquals(listOf("文字语音条"), cfg.itemAlwaysTypes)
        assertTrue(cfg.itemSwitchListKnown)
        assertEquals(cfg, AppConfig.fromJson(cfg.toJson()).validate())
        reject { AppConfig(itemNameMap = mapOf(0 to listOf("词"))).validate() }
        reject { AppConfig(itemNameMap = mapOf(465 to listOf(" "))).validate() }
        reject { AppConfig(itemAlwaysTypes = listOf(" ")).validate() }
        reject {
            AppConfig.fromJson(JSONObject().put("item_name_map",
                JSONObject().put("abc", JSONArray().put("词")))).validate()
        }
    }
}
