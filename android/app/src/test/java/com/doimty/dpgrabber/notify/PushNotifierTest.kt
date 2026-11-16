package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotifierTest {
    private class RecordingTransport(
        private val status: Int = 200,
        private val response: String = """{"code":200}""",
        private val failWith: Throwable? = null
    ) : HttpTransport {
        var calls = 0
            private set
        var method = ""
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body: ByteArray? = null
        var contentType: String? = null
        var timeoutMs = -1L

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            contentType: String?,
            timeoutMs: Long
        ): HttpResponse {
            calls++
            this.method = method
            this.url = url
            this.headers = headers
            this.body = body
            this.contentType = contentType
            this.timeoutMs = timeoutMs
            failWith?.let { throw it }
            return HttpResponse(status, response.toByteArray(Charsets.UTF_8))
        }

        fun payload(): String = String(body ?: ByteArray(0), Charsets.UTF_8)
    }

    private fun transport(status: Int = 200, response: String = """{"code":200}""", failWith: Throwable? = null) =
        RecordingTransport(status, response, failWith)

    private val title = "接单结果：成功"
    private val text = "结果：成功"

    @Test
    fun `bark posts device key title body and group to server push`() {
        val t = transport(response = """{"code":200}""")
        assertTrue(PushNotifier(t).send(title, text, "bark", "device-key", "https://api.day.app/"))
        assertEquals(1, t.calls)
        assertEquals("POST", t.method)
        assertEquals("https://api.day.app/push", t.url)
        assertEquals("application/json", t.headers["Content-Type"])
        assertEquals(8_000L, t.timeoutMs)
        val json = JSONObject(t.payload())
        assertEquals("device-key", json.getString("device_key"))
        assertEquals(title, json.getString("title"))
        assertEquals(text, json.getString("body"))
        assertEquals("DPGrabber", json.getString("group"))
        assertEquals(4, json.length())
    }

    @Test
    fun `bark blank server falls back to official https endpoint`() {
        val t = transport()
        assertTrue(PushNotifier(t).send(title, text, "bark", "key", "  "))
        assertEquals("https://api.day.app/push", t.url)
    }

    @Test
    fun `bark empty key or non https server rejected before request`() {
        val noKey = transport()
        assertFalse(PushNotifier(noKey).send(title, text, "bark", "  ", "https://api.day.app"))
        assertEquals(0, noKey.calls)
        val insecure = transport()
        assertFalse(PushNotifier(insecure).send(title, text, "bark", "key", "http://api.day.app"))
        assertEquals(0, insecure.calls)
    }

    @Test
    fun `bark accepts whitespace in success code but rejects other code and non 200`() {
        assertTrue(PushNotifier(transport(response = """{ "code" : 200 }""")).send(title, text, "bark", "key", ""))
        assertFalse(PushNotifier(transport(response = """{"code":400}""")).send(title, text, "bark", "key", ""))
        assertFalse(PushNotifier(transport(status = 201, response = """{"code":200}""")).send(title, text, "bark", "key", ""))
    }

    @Test
    fun `serverchan encodes key and sends form title desp`() {
        val t = transport(response = """{"code":0}""")
        assertTrue(PushNotifier(t).send(title, "结果 A&B", "serverchan", "SCT123+abc", ""))
        assertEquals("https://sctapi.ftqq.com/SCT123%2Babc.send", t.url)
        assertEquals("application/x-www-form-urlencoded", t.contentType)
        assertEquals("application/x-www-form-urlencoded", t.headers["Content-Type"])
        val payload = t.payload()
        assertTrue(payload.contains("title="))
        assertTrue(payload.contains("desp="))
        assertTrue(payload.contains("A%26B"))
        assertFalse(payload.contains(" A&B "))
        assertFalse(PushNotifier(transport(response = """{"code":1}""")).send(title, text, "serverchan", "key", ""))
        val noKey = transport()
        assertFalse(PushNotifier(noKey).send(title, text, "serverchan", "", ""))
        assertEquals(0, noKey.calls)
    }

    @Test
    fun `pushplus posts json token title content txt template`() {
        val t = transport(response = """{"code":200}""")
        assertTrue(PushNotifier(t).send(title, text, "pushplus", "token-xyz", ""))
        assertEquals("https://www.pushplus.plus/send", t.url)
        assertEquals("application/json", t.headers["Content-Type"])
        val json = JSONObject(t.payload())
        assertEquals("token-xyz", json.getString("token"))
        assertEquals(title, json.getString("title"))
        assertEquals(text, json.getString("content"))
        assertEquals("txt", json.getString("template"))
        assertEquals(4, json.length())
        assertFalse(PushNotifier(transport(response = """{"code":500}""")).send(title, text, "pushplus", "t", ""))
        val noToken = transport()
        assertFalse(PushNotifier(noToken).send(title, text, "pushplus", "", ""))
        assertEquals(0, noToken.calls)
    }

    @Test
    fun `webhook remains https only with json title body and 2xx success`() {
        val t = transport()
        assertTrue(PushNotifier(t).send(title, text, "webhook", "https://example.invalid/hook?key=secret", ""))
        assertEquals("https://example.invalid/hook?key=secret", t.url)
        assertEquals("application/json", t.headers["Content-Type"])
        val json = JSONObject(t.payload())
        assertEquals(title, json.getString("title"))
        assertEquals(text, json.getString("body"))
        assertEquals(2, json.length())
        assertFalse(t.payload().contains("https://"))
        val insecure = transport()
        assertFalse(PushNotifier(insecure).send(title, text, "webhook", "http://example.invalid/hook", ""))
        assertEquals(0, insecure.calls)
        assertFalse(PushNotifier(transport(status = 500)).send(title, text, "webhook", "https://example.invalid/h", ""))
    }

    @Test
    fun `unknown provider and transport exception return false without retry`() {
        val unknown = transport()
        assertFalse(PushNotifier(unknown).send(title, text, "unknown", "k", ""))
        assertEquals(0, unknown.calls)
        for (provider in listOf("bark", "serverchan", "pushplus", "webhook")) {
            val secret = if (provider == "webhook") "https://example.invalid/h" else "key"
            val thrown = transport(failWith = IllegalStateException("simulated offline"))
            assertFalse(PushNotifier(thrown).send(title, text, provider, secret, ""))
            assertEquals(1, thrown.calls)
        }
    }
}
