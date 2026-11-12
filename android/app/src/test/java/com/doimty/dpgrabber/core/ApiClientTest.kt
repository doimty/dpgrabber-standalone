package com.doimty.dpgrabber.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake transport: scripted responses keyed by method + path prefix.
 * Records every call for header/body assertions. Never sends a request.
 */
class FakeTransport : HttpTransport {
    data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val contentType: String?
    )

    data class Scripted(val status: Int, val body: ByteArray)

    val calls = mutableListOf<Call>()
    private val scripts = mutableListOf<Pair<(Call) -> Boolean, Scripted>>()
    var defaultResponse = Scripted(200, """{"code":0}""".toByteArray(Charsets.UTF_8))

    fun script(pathContains: String, status: Int, body: String) {
        scripts.add(
            { call: Call -> call.url.contains(pathContains) } to
                Scripted(status, body.toByteArray(Charsets.UTF_8))
        )
    }

    /** Script keyed on the request body as well (e.g. switch-account per shop). */
    fun scriptBody(pathContains: String, bodyContains: String, status: Int, body: String) {
        scripts.add(
            { call: Call ->
                call.url.contains(pathContains) &&
                    (call.body?.toString(Charsets.UTF_8)?.contains(bodyContains) == true)
            } to Scripted(status, body.toByteArray(Charsets.UTF_8))
        )
    }

    /** Stateful script: the handler decides the response per call. */
    fun scriptDynamic(pathContains: String, handler: (Call) -> Scripted) {
        dynamicHandlers.add { call: Call ->
            if (call.url.contains(pathContains)) handler(call) else null
        }
    }

    private val dynamicHandlers = mutableListOf<(Call) -> Scripted?>()

    fun scriptJson(pathContains: String, body: JSONObject, status: Int = 200) {
        script(pathContains, status, body.toString())
    }

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Long
    ): HttpResponse {
        calls.add(Call(method, url, headers, body, contentType))
        val call = calls.last()
        val dynamic = dynamicHandlers.firstNotNullOfOrNull { it(call) }
        val match = dynamic ?: scripts.firstOrNull { (pred, _) -> pred(call) }?.second
            ?: defaultResponse
        return HttpResponse(match.status, match.body)
    }

    fun lastCall(): Call = calls.last()
}

/** Helpers shared by the core tests. */
object TestKit {
    const val KEY = ApiClient.DEFAULT_AES_KEY_HEX
    const val IV = ApiClient.DEFAULT_AES_IV_HEX
    const val ALT_KEY = ApiClient.ALT_AES_KEY_HEX
    const val ALT_IV = ApiClient.ALT_AES_IV_HEX

    fun client(transport: HttpTransport): ApiClient =
        ApiClient(KEY, IV, ALT_KEY, ALT_IV, true, transport)

    /** A plain (unencrypted) envelope: {"code":0, ...} at the top level. */
    fun plain(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((k, v) in pairs) json.put(k, v)
        return json
    }

    /** An encrypted envelope: {"response": "<base64>", "isEncrypted": true}. */
    fun encrypted(inner: JSONObject, key: String = KEY, iv: String = IV): JSONObject {
        val payload = Crypto.aesEncryptB64(
            inner.toString().toByteArray(Charsets.UTF_8), key, iv
        )
        return JSONObject().apply {
            put("response", payload)
            put("isEncrypted", true)
        }
    }

    fun jsonObject(text: String): JSONObject = JSONObject(text)
}

class CryptoTest {
    @Test
    fun `round trip AES-256`() {
        val plain = "hello dpgrabber".toByteArray(Charsets.UTF_8)
        val cipher = Crypto.aesEncryptB64(plain, TestKit.KEY, TestKit.IV)
        val back = Crypto.aesDecryptB64(cipher, TestKit.KEY, TestKit.IV)
        assertEquals("hello dpgrabber", String(back, Charsets.UTF_8))
    }

    @Test
    fun `round trip AES-128 alt pair`() {
        val plain = """{"code":0,"list":[]}""".toByteArray(Charsets.UTF_8)
        val cipher = Crypto.aesEncryptB64(plain, TestKit.ALT_KEY, TestKit.ALT_IV)
        val back = Crypto.aesDecryptB64(cipher, TestKit.ALT_KEY, TestKit.ALT_IV)
        assertEquals("""{"code":0,"list":[]}""", String(back, Charsets.UTF_8))
    }

    @Test
    fun `wrong key fails decryption`() {
        val plain = "secret".toByteArray(Charsets.UTF_8)
        val cipher = Crypto.aesEncryptB64(plain, TestKit.KEY, TestKit.IV)
        try {
            Crypto.aesDecryptB64(cipher, TestKit.ALT_KEY, TestKit.ALT_IV)
            assertTrue("expected decryption failure", false)
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }

    @Test
    fun `bad hex rejected`() {
        try {
            Crypto.hexDecode("zz")
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
        try {
            Crypto.hexDecode("abc")
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `bad base64 rejected`() {
        try {
            Crypto.aesDecryptB64("!!!not-base64!!!", TestKit.KEY, TestKit.IV)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `ciphertext length must be multiple of 16`() {
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        try {
            Crypto.aesDecryptB64(short, TestKit.KEY, TestKit.IV)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `key and iv length validation`() {
        Crypto.validateKeyIv(TestKit.KEY, TestKit.IV)
        Crypto.validateKeyIv(TestKit.ALT_KEY, TestKit.ALT_IV)
        try {
            Crypto.validateKeyIv("00".repeat(8), TestKit.IV)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
        try {
            Crypto.validateKeyIv(TestKit.KEY, "00".repeat(8))
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `normalizeMs`() {
        assertEquals(0L, normalizeMs(0))
        assertEquals(0L, normalizeMs(-5))
        assertEquals(1_700_000_000_000L, normalizeMs(1_700_000_000))
        assertEquals(1_700_000_000_000L, normalizeMs(1_700_000_000_000))
        assertEquals(0L, normalizeMs("nonsense"))
        assertEquals(0L, normalizeMs(null))
    }

    @Test
    fun `amountYuanFromCents`() {
        assertEquals(10.0, amountYuanFromCents(1000), 0.001)
        assertEquals(0.0, amountYuanFromCents(null), 0.001)
        assertEquals(5.5, amountYuanFromCents("550"), 0.001)
    }
}

class DecodeResponseTest {
    @Test
    fun `plain top-level code passes through`() {
        val client = TestKit.client(FakeTransport())
        val decoded = client.decodeResponse(
            """{"code":0,"data":{"token":"abc"}}""".toByteArray(Charsets.UTF_8)
        )
        assertEquals(0, decoded.getInt("code"))
        assertEquals("abc", decoded.getJSONObject("data").getString("token"))
    }

    @Test
    fun `dict response used directly`() {
        val client = TestKit.client(FakeTransport())
        val decoded = client.decodeResponse(
            """{"response":{"code":0,"list":[]},"isEncrypted":false}""".toByteArray(Charsets.UTF_8)
        )
        assertEquals(0, decoded.getInt("code"))
    }

    @Test
    fun `encrypted response decrypted with primary key`() {
        val client = TestKit.client(FakeTransport())
        val inner = TestKit.plain("code" to 0, "list" to org.json.JSONArray())
        val envelope = TestKit.encrypted(inner)
        val decoded = client.decodeResponse(envelope.toString().toByteArray(Charsets.UTF_8))
        assertEquals(0, decoded.getInt("code"))
    }

    @Test
    fun `encrypted response falls back to alternate key`() {
        val client = TestKit.client(FakeTransport())
        val inner = TestKit.plain("code" to 0)
        // Encrypt with the ALT pair; primary must fail, alternate must work.
        val envelope = TestKit.encrypted(inner, TestKit.ALT_KEY, TestKit.ALT_IV)
        val decoded = client.decodeResponse(envelope.toString().toByteArray(Charsets.UTF_8))
        assertEquals(0, decoded.getInt("code"))
    }

    @Test
    fun `plain string response that is not JSON falls back to decryption`() {
        val client = TestKit.client(FakeTransport())
        val inner = TestKit.plain("code" to 0, "msg" to "ok")
        val payload = Crypto.aesEncryptB64(
            inner.toString().toByteArray(Charsets.UTF_8), TestKit.KEY, TestKit.IV
        )
        val envelope = JSONObject().apply { put("response", payload) }
        val decoded = client.decodeResponse(envelope.toString().toByteArray(Charsets.UTF_8))
        assertEquals("ok", decoded.getString("msg"))
    }

    @Test
    fun `missing code is an api error`() {
        val client = TestKit.client(FakeTransport())
        try {
            client.decodeResponse("""{"foo":1}""".toByteArray(Charsets.UTF_8))
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.API, e.kind)
        }
    }

    @Test
    fun `code 401 is auth`() {
        val client = TestKit.client(FakeTransport())
        try {
            client.decodeResponse("""{"code":401}""".toByteArray(Charsets.UTF_8))
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun `code 1 with IP in msg is ip control`() {
        val client = TestKit.client(FakeTransport())
        try {
            client.decodeResponse(
                """{"code":1,"msg":"IP limited"}""".toByteArray(Charsets.UTF_8)
            )
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.IP, e.kind)
        }
    }

    @Test
    fun `code 1 without IP is a plain decoded dict`() {
        val client = TestKit.client(FakeTransport())
        val decoded = client.decodeResponse(
            """{"code":1,"msg":"something else"}""".toByteArray(Charsets.UTF_8)
        )
        assertEquals(1, decoded.getInt("code"))
    }

    @Test
    fun `string code is parsed`() {
        val client = TestKit.client(FakeTransport())
        val decoded = client.decodeResponse(
            """{"code":"0"}""".toByteArray(Charsets.UTF_8)
        )
        assertEquals(0, decoded.getInt("code"))
    }
}

class RequestTest {
    @Test
    fun `non s-path rejected`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        try {
            client.request("GET", "/other/path")
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `fixed headers and session headers are sent`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        client.session = Session(token = "tok123", sid = "92")
        client.request("GET", "/s/balance/getBalance")
        val call = transport.lastCall()
        assertEquals(ApiClient.USER_AGENT, call.headers["User-Agent"])
        assertEquals("202501071", call.headers["api-version"])
        assertEquals("app", call.headers["platform"])
        assertEquals("tok123", call.headers["authorization-token"])
        assertEquals("92", call.headers["sid"])
        assertTrue(call.url.startsWith(ApiClient.BASE_URL))
    }

    @Test
    fun `form body is sorted and percent-encoded`() {
        val transport = FakeTransport()
        val client = TestKit.client(transport)
        client.request(
            "POST", "/s/duopei-account/switch-account",
            body = mapOf("b key" to "v&v", "accountId" to "42"),
            bodyType = "form"
        )
        val body = String(transport.lastCall().body ?: ByteArray(0), Charsets.UTF_8)
        assertEquals("accountId=42&b%20key=v%26v", body)
        assertEquals(
            "application/x-www-form-urlencoded",
            transport.lastCall().contentType
        )
    }

    @Test
    fun `http 401 becomes auth error`() {
        val transport = FakeTransport()
        transport.defaultResponse = FakeTransport.Scripted(401, """{"code":401}""".toByteArray(Charsets.UTF_8))
        val client = TestKit.client(transport)
        try {
            client.request("GET", "/s/balance/getBalance")
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun `http 500 becomes network error`() {
        val transport = FakeTransport()
        transport.defaultResponse = FakeTransport.Scripted(500, ByteArray(0))
        val client = TestKit.client(transport)
        try {
            client.request("GET", "/s/balance/getBalance")
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.NETWORK, e.kind)
        }
    }
}

class LoginFlowTest {
    private fun loginTransport(): FakeTransport {
        val transport = FakeTransport()
        transport.scriptJson(
            "/s/duopei-account/login",
            TestKit.plain("code" to 0, "data" to JSONObject().apply { put("token", "login-token") })
        )
        val shops = org.json.JSONArray().apply {
            put(JSONObject().apply { put("shopId", 55); put("accountId", "a55") })
            put(JSONObject().apply { put("shopId", 92); put("accountId", "a92") })
        }
        transport.scriptJson(
            "/s/duopei-account/list-shop-account",
            TestKit.plain("code" to 0, "data" to shops)
        )
        transport.scriptJson(
            "/s/duopei-account/switch-account",
            TestKit.plain("code" to 0, "data" to JSONObject().apply { put("token", "shop-token") })
        )
        transport.scriptJson("/s/balance/getBalance", TestKit.plain("code" to 0))
        return transport
    }

    @Test
    fun `login_account returns token with sid 50 and login headers`() {
        val transport = loginTransport()
        val client = TestKit.client(transport)
        val session = client.loginAccount("13800138000", "secret")
        assertEquals("login-token", session.token)
        assertEquals("50", session.sid)
        val loginCall = transport.calls.first { it.url.contains("/s/duopei-account/login") }
        assertEquals("-1", loginCall.headers["sid"])
        assertEquals("", loginCall.headers["authorization-token"])
        val body = JSONObject(String(loginCall.body ?: ByteArray(0), Charsets.UTF_8))
        assertEquals("password", body.getString("method"))
        assertEquals("13800138000", body.getJSONObject("phoneNumber").getString("mobile"))
        assertEquals("86", body.getJSONObject("phoneNumber").getString("mobileDial"))
    }

    @Test
    fun `session_for_account prefers shop 92`() {
        val transport = loginTransport()
        val client = TestKit.client(transport)
        val session = client.sessionForAccount("13800138000", "secret", 92)
        assertEquals("shop-token", session.token)
        assertEquals("92", session.sid)
        val switchCall = transport.calls.first { it.url.contains("switch-account") }
        val body = String(switchCall.body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue(body.contains("accountId=a92"))
    }

    @Test
    fun `session_for_account falls through to next shop on switch failure`() {
        val transport = FakeTransport()
        transport.scriptJson(
            "/s/duopei-account/login",
            TestKit.plain("code" to 0, "data" to JSONObject().apply { put("token", "t") })
        )
        val shops = org.json.JSONArray().apply {
            put(JSONObject().apply { put("shopId", 92); put("accountId", "bad") })
            put(JSONObject().apply { put("shopId", 77); put("accountId", "good") })
        }
        transport.scriptJson(
            "/s/duopei-account/list-shop-account",
            TestKit.plain("code" to 0, "data" to shops)
        )
        transport.scriptBody(
            "/s/duopei-account/switch-account", "accountId=bad",
            200, """{"code":1,"msg":"no"}"""
        )
        transport.scriptBody(
            "/s/duopei-account/switch-account", "accountId=good",
            200,
            """{"code":0,"data":{"token":"good-token"}}"""
        )
        transport.scriptJson("/s/balance/getBalance", TestKit.plain("code" to 0))
        val client = TestKit.client(transport)
        val session = client.sessionForAccount("13800138000", "secret", 92)
        assertEquals("good-token", session.token)
        assertEquals("77", session.sid)
        val switchBodies = transport.calls
            .filter { it.url.contains("switch-account") }
            .map { String(it.body ?: ByteArray(0), Charsets.UTF_8) }
        assertEquals(listOf("accountId=bad", "accountId=good"), switchBodies)
    }

    @Test
    fun `login rejected without token`() {
        val transport = FakeTransport()
        transport.scriptJson(
            "/s/duopei-account/login",
            TestKit.plain("code" to 0, "data" to JSONObject())
        )
        val client = TestKit.client(transport)
        try {
            client.loginAccount("13800138000", "secret")
            assertTrue("expected failure", false)
        } catch (e: ApiError) {
            assertEquals(ApiError.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun `extractClerk finds clerk with positive id`() {
        val decoded = JSONObject(
            """{"clerk":{"id":7,"shopId":92,"name":"Test"}}"""
        )
        val clerk = ApiClient.extractClerk(decoded)
        assertNotNull(clerk)
        assertEquals(7, clerk!!.getInt("id"))
        assertNull(ApiClient.extractClerk(JSONObject("""{"clerk":{"id":0}}""")))
        assertNull(ApiClient.extractClerk(JSONObject("""{"other":1}""")))
    }
}

class RandomListTest {
    @Test
    fun `list from response list`() {
        val transport = FakeTransport()
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain(
                "code" to 0,
                "list" to org.json.JSONArray().apply {
                    put(JSONObject().apply { put("id", "o1") })
                }
            )
        )
        val client = TestKit.client(transport)
        val list = client.randomList()
        assertEquals(1, list.length())
        assertEquals("o1", list.getJSONObject(0).getString("id"))
    }

    @Test
    fun `list from data list fallback`() {
        val transport = FakeTransport()
        transport.scriptJson(
            "/s/c/order/randomList",
            TestKit.plain(
                "code" to 0,
                "data" to JSONObject().apply {
                    put("list", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("id", "o2") })
                    })
                }
            )
        )
        val client = TestKit.client(transport)
        assertEquals("o2", client.randomList().getJSONObject(0).getString("id"))
    }

    @Test
    fun `missing list is empty not fatal`() {
        val transport = FakeTransport()
        transport.scriptJson("/s/c/order/randomList", TestKit.plain("code" to 0))
        val client = TestKit.client(transport)
        assertEquals(0, client.randomList().length())
    }
}
