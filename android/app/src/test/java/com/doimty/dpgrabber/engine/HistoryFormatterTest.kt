package com.doimty.dpgrabber.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormatterTest {
    @Test
    fun formatsLegacyPayloadWithoutLosingLongOrderNumber() {
        val order = JSONObject()
            .put("no", "202401010000000000001234567890")
            .put("totalAmount", 0)
            .put("userWechatId", "wechat-long")
            .put("userMemo", "第一行\n第二行\n第三行\n第四行")
        assertEquals("202401010000000000001234567890", HistoryFormatter.orderNumber(order, "fallback"))
        assertEquals("¥0.00", HistoryFormatter.amount(order))
        assertEquals("wechat-long", HistoryFormatter.wechat(order))
        assertEquals("第一行\n第二行\n第三行\n第四行", HistoryFormatter.memo(order))
    }

    @Test
    fun handlesMissingNullAndInvalidFields() {
        val order = JSONObject().put("no", JSONObject.NULL).put("totalAmount", JSONObject.NULL)
            .put("userWechatId", JSONObject.NULL).put("userMemo", JSONObject.NULL)
        assertEquals("fallback-order", HistoryFormatter.orderNumber(order, "fallback-order"))
        assertEquals("金额未提供", HistoryFormatter.amount(order))
        assertEquals("未提供微信号", HistoryFormatter.wechat(order))
        assertEquals("无备注", HistoryFormatter.memo(order))
        assertEquals("时间未提供", HistoryFormatter.timestamp(0L))
    }

    @Test
    fun formatsCentsAsTwoDecimalYuan() {
        assertEquals("¥12.34", HistoryFormatter.amount(JSONObject().put("totalAmount", "1234")))
        for (invalid in listOf("NaN", "Infinity", "-Infinity", "null", "")) {
            assertEquals("金额未提供", HistoryFormatter.amount(JSONObject().put("totalAmount", invalid)))
        }
    }

    @Test
    fun emptyAndSentinelTextNeverLeaksIntoFields() {
        for (empty in listOf("", " ", "null", "NaN", "Infinity")) {
            val order = JSONObject().put("no", empty).put("userWechatId", empty).put("userMemo", empty)
            assertEquals("900719925474099312345", HistoryFormatter.orderNumber(order, "900719925474099312345"))
            assertEquals("未提供微信号", HistoryFormatter.wechat(order))
            assertEquals("无备注", HistoryFormatter.memo(order))
        }
        assertEquals("9007199254740993", HistoryFormatter.orderNumber(JSONObject().put("no", 9007199254740993L), ""))
    }

    @Test
    fun rejectsWrongFieldTypesAndNegativeAmounts() {
        for (value in listOf(true, JSONObject(), org.json.JSONArray(), 1234.5)) {
            val order = JSONObject().put("no", value).put("userWechatId", value).put("userMemo", value)
            assertEquals("fallback", HistoryFormatter.orderNumber(order, "fallback"))
            assertEquals("未提供微信号", HistoryFormatter.wechat(order))
            assertEquals("无备注", HistoryFormatter.memo(order))
        }
        assertEquals("无备注", HistoryFormatter.memo(JSONObject().put("userMemo", 1234)))
        for (value in listOf("-100", "1e1000000000", "1e-1000000000", "-Infinity")) {
            assertEquals("金额未提供", HistoryFormatter.amount(JSONObject().put("totalAmount", value)))
        }
    }

    @Test
    fun timestampDoesNotFollowDeviceTimezone() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("2024-01-01 08:00:00", HistoryFormatter.timestamp(1704067200000L))
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun optionalCreationTimeAcceptsSecondsAndMillisecondsWithoutInventingMissingTime() {
        fun details(order: JSONObject) = HistoryFormatter.details(order, "id", "92", 1704153600000L, OutcomeType.SUCCESS, "")
        for (created in listOf(1704067200L, 1704067200000L)) {
            org.junit.Assert.assertTrue(details(JSONObject().put("createTime", created))
                .contains("下单时间（北京时间）：2024-01-01 08:00:00"))
        }
        org.junit.Assert.assertFalse(details(JSONObject()).contains("下单时间"))
        org.junit.Assert.assertFalse(details(JSONObject().put("createTime", JSONObject.NULL)).contains("下单时间"))
    }

    @Test
    fun detailsIncludeFullMemoSeparateIdAndBeijingTime() {
        val memo = "第一行\n第二行\n第三行\n第四行\n" + "多行超长备注".repeat(300)
        val order = JSONObject().put("no", "000000000000001").put("userMemo", memo)
        val details = HistoryFormatter.details(order, "internal-id", "92", 1704067200000L, OutcomeType.UNKNOWN, "待核验")
        org.junit.Assert.assertTrue(details.contains("订单号：000000000000001\n订单ID：internal-id"))
        org.junit.Assert.assertTrue(details.contains("备注：$memo"))
        org.junit.Assert.assertTrue(details.contains("2024-01-01 08:00:00"))
        org.junit.Assert.assertTrue(details.contains("金额未提供"))
    }
}
