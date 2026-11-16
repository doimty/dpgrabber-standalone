package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.core.AppConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushConfigTest {
    @Test
    fun `defaults keep push off and success on`() {
        val cfg = AppConfig.defaults().validate()
        assertFalse(cfg.pushEnabled)
        assertTrue(cfg.pushOnSuccess)
        assertFalse(cfg.pushOnFailure)
        assertFalse(cfg.pushOnRisk)
    }

    @Test
    fun `four push switches round trip through json`() {
        val cfg = AppConfig(
            pushEnabled = true, pushOnSuccess = false,
            pushOnFailure = true, pushOnRisk = true
        )
        val restored = AppConfig.fromJson(cfg.toJson()).validate()
        assertEquals(cfg.copy(), restored.copy())
        assertTrue(restored.pushEnabled)
        assertFalse(restored.pushOnSuccess)
        assertTrue(restored.pushOnFailure)
        assertTrue(restored.pushOnRisk)
    }

    @Test
    fun `old json without push keys keeps defaults`() {
        val cfg = AppConfig.fromJson(JSONObject()).validate()
        assertFalse(cfg.pushEnabled)
        assertTrue(cfg.pushOnSuccess)
        assertFalse(cfg.pushOnFailure)
        assertFalse(cfg.pushOnRisk)
    }

    @Test
    fun `config json never carries webhook address or credentials`() {
        val cfg = AppConfig(phone = "10000000000", password = "sensitive").toJson().toString()
        assertFalse(cfg.contains("webhook"))
        assertFalse(cfg.contains("https://"))
        assertFalse(cfg.contains("sensitive"))
        assertFalse(cfg.contains("10000000000"))
    }
}
