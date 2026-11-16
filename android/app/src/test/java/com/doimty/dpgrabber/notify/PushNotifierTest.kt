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
            return HttpResponse(status, "{}".toByteArray(Charsets.UTF_8))
        }
    }

    private val hook = "https://example.invalid/hook?key=secret"

    @Test
    fun `post json to https hook with eight second timeout and json content type`() {
        val transport = RecordingTransport(200)
        assertTrue(PushNotifier(transport).send("接单结果：成功", "结果\n金额", hook))
        assertEquals(1, transport.calls)
        assertEquals("POST", transport.method)
        assertEquals(hook, transport.url)
        assertEquals("application/json", transport.headers["Content-Type"])
        assertEquals("application/json", transport.contentType)
        assertEquals(8_000L, transport.timeoutMs)
        val json = JSONObject(String(transport.body!!, Charsets.UTF_8))
        assertEquals("接单结果：成功", json.getString("title"))
        assertEquals("结果\n金额", json.getString("body"))
        assertEquals(2, json.length())
    }

    @Test
    fun `two xx statuses succeed others fail`() {
        for (status in listOf(200, 201, 204, 299)) {
            assertTrue(PushNotifier(RecordingTransport(status)).send("t", "b", hook))
        }
        for (status in listOf(199, 300, 302, 400, 500)) {
            assertFalse(PushNotifier(RecordingTransport(status)).send("t", "b", hook))
        }
    }

    @Test
    fun `http hook is rejected before any request`() {
        val transport = RecordingTransport(200)
        assertFalse(PushNotifier(transport).send("t", "b", "http://example.invalid/hook"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `transport exception returns false without throwing and no retry`() {
        val transport = RecordingTransport(500, IllegalStateException("simulated offline"))
        assertFalse(PushNotifier(transport).send("t", "b", hook))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `payload carries no webhook key url or platform token`() {
        val transport = RecordingTransport(200)
        PushNotifier(transport).send("title-only", "body-only", hook)
        val payload = String(transport.body!!, Charsets.UTF_8)
        assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("https://"))
        assertFalse(payload.contains("token"))
        assertTrue(payload.contains("title-only"))
        assertTrue(payload.contains("body-only"))
    }
}
