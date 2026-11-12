package com.doimty.dpgrabber.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * DuoPei API client (PROTOCOL.md sections 1-5).
 *
 * Pure Kotlin: talks only to an injected [HttpTransport], parses with
 * org.json. Mirrors the PC implementation (dpapi.py) one-to-one:
 *
 * * Base URL https://api.duopei.feiniaowangluo.com; only /s/ paths are
 *   allowed and the host is pinned.
 * * Fixed headers: DPGrabber UA, api-version 202501071, platform app.
 * * Bodies: JSON or form (keys sorted, RFC 3986 percent-encoding).
 * * Response envelope (section 4.2): outer JSON `response` field; when
 *   `isEncrypted` is set, or when the response string does not parse as
 *   plain JSON, the payload is Base64-decoded and AES-CBC/PKCS#7-decrypted.
 *   The primary AES pair is tried first, then the alternate pair
 *   ("decrypt with whichever works").
 * * Login flow (section 3.2): phone + password -> token (sid "50") ->
 *   list-shop-account -> switch-account per shop -> getBalance validation.
 *   Token is kept in memory only, never written to disk.
 */
class ApiClient(
    var aesKeyHex: String,
    var aesIvHex: String,
    var aesKeyHexAlt: String = "",
    var aesIvHexAlt: String = "",
    var verifyTls: Boolean = true,
    private val transport: HttpTransport
) {
    /** The live session, or null when logged out. Memory only. */
    @Volatile
    var session: Session? = null

    companion object {
        const val BASE_URL = "https://api.duopei.feiniaowangluo.com"
        const val API_HOST = "api.duopei.feiniaowangluo.com"
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 15_1_1 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
                "Html5Plus/1.0 (Immersed/20) uni-app"
        const val API_VERSION = "202501071"

        /** DPGrabber AES-256 set (DPGAPIClient.m:12-13). Primary. */
        const val DEFAULT_AES_KEY_HEX =
            "faf6bcb4eeaa8aa62154ed3b8c14cf5bce08cdf83086b9188abdca6f2c8bcbb1"
        const val DEFAULT_AES_IV_HEX = "d45df726d59d21fac023cdad3dfcea86"

        /** PC reference tool AES-128 set (DP.py:305-306). Alternate. */
        const val ALT_AES_KEY_HEX = "81b120ef00216c33b266763abb02e6d1"
        const val ALT_AES_IV_HEX = "e6a4cc0507dfe344b042289eeb945dce"

        private val FORM_SAFE: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

        /**
         * Read the business `code` as an int (number or numeric string);
         * null when missing or malformed (PROTOCOL.md section 1).
         */
        fun parseCode(obj: JSONObject): Int? {
            if (!obj.has("code") || obj.isNull("code")) return null
            val code = obj.get("code")
            return when (code) {
                is Int -> code
                is Long -> code.toInt()
                is Double -> if (code % 1.0 == 0.0) code.toInt() else null
                is String -> {
                    val trimmed = code.trim()
                    trimmed.toIntOrNull()
                        ?: trimmed.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
                }
                else -> null
            }
        }

        /**
         * Find the clerk object in a clerk/get response: try `clerk`,
         * `data` and the top level, in that order; take the first dict
         * with a positive integer `id` (PROTOCOL.md section 2.1.6).
         */
        fun extractClerk(decoded: JSONObject): JSONObject? {
            val candidates = mutableListOf<JSONObject>()
            val clerk = decoded.optJSONObject("clerk")
            if (clerk != null) candidates.add(clerk)
            val data = decoded.optJSONObject("data")
            if (data != null) candidates.add(data)
            candidates.add(decoded)
            for (cand in candidates) {
                if (!cand.has("id") || cand.isNull("id")) continue
                val id = when (val raw = cand.get("id")) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: continue
                    else -> continue
                }
                if (id > 0) return cand
            }
            return null
        }
    }

    // -- decryption -----------------------------------------------------

    private fun decryptResponseText(payload: String): JSONObject {
        val attempts = mutableListOf(aesKeyHex to aesIvHex)
        if (aesKeyHexAlt.isNotEmpty() && aesIvHexAlt.isNotEmpty()) {
            attempts.add(aesKeyHexAlt to aesIvHexAlt)
        }
        var lastError: Exception? = null
        for ((keyHex, ivHex) in attempts) {
            try {
                val plain = Crypto.aesDecryptB64(payload, keyHex, ivHex)
                return JSONObject(String(plain, Charsets.UTF_8))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ApiError(ApiError.Kind.API, "response decryption failed: $lastError")
    }

    /**
     * Decode one HTTP response body per PROTOCOL.md section 4.2, with one
     * documented merge: when the `response` string does not parse as plain
     * JSON, AES decryption is attempted as well (the PC tool decrypts
     * unconditionally; DPGrabber only decrypts when `isEncrypted` is set).
     * Enforces the `code` rules (missing code -> error, 401/403 -> auth,
     * code 1 + "IP" in msg -> IP control).
     */
    fun decodeResponse(raw: ByteArray): JSONObject {
        val outer = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ApiError(ApiError.Kind.API, "response is not valid JSON: ${e.message}")
        }
        val envelope = if (outer.has("response") && !outer.isNull("response")) {
            outer.get("response")
        } else {
            null
        }
        val encrypted = outer.optBoolean("isEncrypted", false)
        var inner: JSONObject? = null
        when (envelope) {
            is JSONObject -> inner = envelope
            is String -> {
                if (envelope.isNotEmpty()) {
                    if (encrypted) {
                        inner = decryptResponseText(envelope)
                    } else {
                        val parsed = try {
                            JSONObject(envelope)
                        } catch (e: Exception) {
                            null
                        }
                        inner = parsed ?: decryptResponseText(envelope)
                    }
                }
            }
        }
        val resolved = inner ?: outer
        val code = parseCode(resolved)
            ?: throw ApiError(ApiError.Kind.API, "response has no valid business code")
        if (code == 401 || code == 403) {
            throw ApiError(ApiError.Kind.AUTH, "login state invalid (code $code)")
        }
        val msg = resolved.optString("msg", resolved.optString("message", ""))
        if (code == 1 && msg.contains("IP")) {
            throw ApiError(ApiError.Kind.IP, "IP control: $msg")
        }
        return resolved
    }

    // -- transport ------------------------------------------------------

    /**
     * One API call. Throws [ApiError] on any failure.
     * Must not be called from the main thread (callers are background
     * threads / the foreground service).
     */
    @Throws(ApiError::class)
    fun request(
        method: String,
        path: String,
        session: Session? = null,
        body: Any? = null,
        bodyType: String = "none",
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Double = 15.0
    ): JSONObject {
        require(path.startsWith("/s/") && timeoutSec > 0) {
            "invalid request path or timeout"
        }
        val url = BASE_URL + path
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = USER_AGENT
        headers["api-version"] = API_VERSION
        headers["platform"] = "app"
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = "zh-CN,zh-Hans;q=0.9"
        val useSession = session ?: this.session
        if (useSession != null && useSession.token.isNotEmpty()) {
            headers["authorization-token"] = useSession.token
            headers["sid"] = useSession.sid
        }
        headers.putAll(extraHeaders)

        var data: ByteArray? = null
        var contentType: String? = null
        if (body != null && bodyType == "json") {
            data = JSONObject(body as Map<*, *>).toString().toByteArray(Charsets.UTF_8)
            contentType = "application/json"
        } else if (body != null && bodyType == "form") {
            val map = body as Map<*, *>
            val pairs = map.keys.map { it.toString() }.sorted().map { key ->
                "${percentEncode(key)}=${percentEncode(map[key].toString())}"
            }
            data = pairs.joinToString("&").toByteArray(Charsets.UTF_8)
            contentType = "application/x-www-form-urlencoded"
        }

        val resp = try {
            transport.request(
                method, url, headers, data, contentType,
                (timeoutSec * 1000).toLong().coerceAtLeast(100)
            )
        } catch (e: Exception) {
            throw ApiError(ApiError.Kind.NETWORK, "request failed: ${e.message}")
        }
        if (resp.statusCode == 401 || resp.statusCode == 403) {
            throw ApiError(ApiError.Kind.AUTH, "login state invalid (HTTP ${resp.statusCode})")
        }
        if (resp.statusCode >= 400) {
            throw ApiError(ApiError.Kind.NETWORK, "HTTP ${resp.statusCode}")
        }
        return decodeResponse(resp.body)
    }

    /** RFC 3986 percent-encoding (unreserved set A-Z a-z 0-9 -._~). */
    fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (FORM_SAFE.contains(c)) {
                out.append(c)
            } else {
                out.append('%')
                out.append(String.format("%02X", b.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    // -- login flow (PROTOCOL.md section 3.2) ---------------------------

    /** POST /s/duopei-account/login. Returns (token, sid "50"). */
    @Throws(ApiError::class)
    fun loginAccount(phone: String, password: String): Session {
        val cleanPhone = phone.trim()
        val secret = password.trim()
        if (cleanPhone.isEmpty() || secret.isEmpty()) {
            throw ApiError(ApiError.Kind.API, "phone and password are required")
        }
        val body = mapOf(
            "method" to "password",
            "phoneNumber" to mapOf(
                "regionCode" to "CN",
                "mobileDial" to "86",
                "mobile" to cleanPhone
            ),
            "password" to secret
        )
        val resp = request(
            "POST", "/s/duopei-account/login", session = null,
            body = body, bodyType = "json",
            extraHeaders = mapOf("sid" to "-1", "authorization-token" to ""),
            timeoutSec = 15.0
        )
        if (resp.optInt("code", -1) != 0) {
            throw ApiError(
                ApiError.Kind.AUTH,
                "login rejected: ${resp.optString("msg", resp.optString("message", ""))}"
            )
        }
        val data = resp.optJSONObject("data")
        val token = data?.optString("token", "") ?: ""
        if (token.isEmpty()) {
            throw ApiError(ApiError.Kind.AUTH, "login response has no token")
        }
        return Session(token = token, sid = "50")
    }

    /**
     * Full manual login: login -> list shops -> switch per shop ->
     * getBalance validation. Returns a usable session or throws ApiError.
     * Auth / IP / cancelled failures abort immediately; other per-shop
     * failures move on to the next shop (DPGAPIClient.m:298-361).
     */
    @Throws(ApiError::class)
    fun sessionForAccount(phone: String, password: String, preferredShopId: Int = 92): Session {
        val base = loginAccount(phone, password)
        val resp = request(
            "GET", "/s/duopei-account/list-shop-account", session = base,
            extraHeaders = mapOf("sid" to "50"), timeoutSec = 15.0
        )
        if (resp.optInt("code", -1) != 0) {
            throw ApiError(ApiError.Kind.API, "shop list rejected")
        }
        val shops = mutableListOf<JSONObject>()
        val data = resp.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val shop = data.optJSONObject(i) ?: continue
                shops.add(shop)
            }
        }
        val ordered = shops.sortedWith(
            compareByDescending<JSONObject> { it.optInt("shopId", -1) == preferredShopId }
        )
        var lastError: ApiError? = null
        for (shop in ordered) {
            val accountId = shop.optString("accountId", "")
            val sid = shop.optString("shopId", "")
            if (accountId.isEmpty() || sid.isEmpty()) continue
            val switched = try {
                request(
                    "POST", "/s/duopei-account/switch-account", session = base,
                    body = mapOf("accountId" to accountId), bodyType = "form",
                    extraHeaders = mapOf("sid" to "50"), timeoutSec = 15.0
                )
            } catch (e: ApiError) {
                if (e.kind == ApiError.Kind.AUTH || e.kind == ApiError.Kind.IP ||
                    e.kind == ApiError.Kind.CANCELLED
                ) {
                    throw e
                }
                lastError = e
                continue
            }
            if (switched.optInt("code", -1) != 0) continue
            val switchedData = switched.optJSONObject("data")
            val token = switchedData?.optString("token", "") ?: ""
            if (token.isEmpty()) continue
            val candidate = Session(token = token, sid = sid)
            try {
                val balance = request(
                    "GET", "/s/balance/getBalance", session = candidate, timeoutSec = 15.0
                )
                if (balance.optInt("code", -1) == 0) {
                    session = candidate
                    return candidate
                }
            } catch (e: ApiError) {
                if (e.kind == ApiError.Kind.IP || e.kind == ApiError.Kind.CANCELLED) {
                    throw e
                }
                lastError = e
                continue
            }
        }
        throw ApiError(
            ApiError.Kind.AUTH,
            "no usable shop session: ${lastError?.messageText ?: "all shops rejected"}"
        )
    }

    // -- business calls -------------------------------------------------

    /** GET /s/c/clerk/get. Returns the raw decoded dict. */
    @Throws(ApiError::class)
    fun clerkGet(session: Session? = null): JSONObject =
        request("GET", "/s/c/clerk/get", session = session, timeoutSec = 15.0)

    /**
     * GET /s/c/order/randomList. Returns the order list (possibly empty).
     * A missing list is treated as empty, not fatal, so polling continues.
     */
    @Throws(ApiError::class)
    fun randomList(pageSize: Int = 20, timeoutSec: Double = 15.0): JSONArray {
        val resp = request(
            "GET", "/s/c/order/randomList?pageNo=1&pageSize=$pageSize",
            timeoutSec = timeoutSec
        )
        val orders = resp.optJSONArray("list")
        if (orders != null) return orders
        val data = resp.optJSONObject("data")
        val nested = data?.optJSONArray("list")
        return nested ?: JSONArray()
    }

    /**
     * POST /s/c/order/get?id=... (PC tool form, used for the precise grab
     * time). Returns the decoded dict; the order object is in `order`.
     */
    @Throws(ApiError::class)
    fun orderGet(orderId: String, timeoutSec: Double = 10.0): JSONObject {
        val path = "/s/c/order/get?id=${percentEncode(orderId)}"
        return request("POST", path, timeoutSec = timeoutSec)
    }

    /** POST /s/c/order/confirm?id=... . Returns the decoded dict. */
    @Throws(ApiError::class)
    fun orderConfirm(orderId: String, timeoutSec: Double = 10.0): JSONObject {
        val path = "/s/c/order/confirm?id=${percentEncode(orderId)}"
        return request("POST", path, timeoutSec = timeoutSec)
    }
}
