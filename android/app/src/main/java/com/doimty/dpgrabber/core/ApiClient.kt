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
                is Long -> if (code in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) code.toInt() else null
                is Double -> if (code.isFinite() && code % 1.0 == 0.0 && code in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) code.toInt() else null
                is String -> {
                    val trimmed = code.trim()
                    trimmed.toIntOrNull()
                        ?: trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
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
                val id = parseCode(JSONObject().put("code", cand.get("id"))) ?: continue
                if (id > 0) return cand
            }
            return null
        }
    }

    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    fun cancel() { cancelled = true; transport.cancelAll() }
    /** 已取消的客户端不可复活；新任务从仓库取得新实例。 */
    fun resumeRequests() { checkActive() }
    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw ApiError(ApiError.Kind.CANCELLED, "请求已停止；已发出的写请求结果可能未知")
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
        throw ApiError(ApiError.Kind.API, "响应解密失败")
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
            throw ApiError(ApiError.Kind.API, "响应不是有效JSON")
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
        val code = parseCode(resolved) ?: run {
            // 连 code 都没有时，若平台仍给了 msg，也要留在错误里，否则无法定位。
            val reason = sanitizeReason(resolved.optString("msg", resolved.optString("message", "")))
            throw ApiError(ApiError.Kind.API,
                if (reason.isEmpty()) "response has no valid business code"
                else "response has no valid business code（$reason）")
        }
        if (code == 401 || code == 403) {
            val reason = sanitizeReason(resolved.optString("msg", resolved.optString("message", "")))
            val permission = listOf("没有操作权限", "无操作权限", "不是店员", "非店员").any { reason.contains(it) }
            // 平台原因不能丢：401/403 既可能是会话失效，也可能是身份/店铺权限问题。
            val detail = if (reason.isEmpty()) "code=$code" else "code=$code：$reason"
            throw ApiError(if (permission) ApiError.Kind.PERMISSION else ApiError.Kind.AUTH,
                "会话或店铺访问被拒绝（$detail）")
        }
        val msg = resolved.optString("msg", resolved.optString("message", ""))
        if (code == 1 && msg.contains("IP")) {
            throw ApiError(ApiError.Kind.IP, "平台IP风控暂停")
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
        checkActive()
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
            checkActive()
            // 传输层失败（拿不到任何业务响应）必须与“平台业务拒绝”区分开：
            // 前者是 TLS 被掐断/超时/拒连，后者才是解析到 JSON 且 code != 0。
            throw ApiError(ApiError.Kind.NETWORK, transportFailureText(e))
        }
        checkActive()
        return try {
            if (resp.statusCode == 401 || resp.statusCode == 403) {
                try { decodeResponse(resp.body) } catch (e: ApiError) {
                    if (e.kind == ApiError.Kind.PERMISSION) throw e
                }
                throw ApiError(ApiError.Kind.AUTH, "会话失效 (HTTP ${resp.statusCode})")
            }
            if (resp.statusCode >= 400) {
                throw ApiError(ApiError.Kind.NETWORK, "HTTP ${resp.statusCode}")
            }
            decodeResponse(resp.body)
        } catch (error: ApiError) {
            // 本地停止只取消传输，认证仍保留；真正的认证拒绝才清会话。
            // 只清发出本次请求的同一对象，迟到旧请求不能清掉后来刷新出的会话。
            if (error.kind == ApiError.Kind.AUTH && useSession != null && this.session === useSession) {
                this.session = null
            }
            throw error
        }
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
        val cleanPassword = password.trim()
        val secret = if (cleanPassword.matches(Regex("(?i)[0-9a-f]{32}"))) cleanPassword.lowercase() else
            java.security.MessageDigest.getInstance("MD5").digest(cleanPassword.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (cleanPhone.isEmpty() || cleanPassword.isEmpty()) {
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
                rejectionText(resp, "登录被拒绝")
            )
        }
        val data = resp.optJSONObject("data")
        val token = data?.optString("token", "") ?: ""
        if (token.isEmpty()) {
            throw ApiError(ApiError.Kind.AUTH, "login response has no token")
        }
        val sid = data?.optString("shopId", "")?.trim()?.takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }
            ?: throw ApiError(ApiError.Kind.AUTH, "登录响应缺少有效店铺身份")
        return Session(token = token, sid = sid)
    }

    /**
     * Full manual login: login -> list shops -> switch per shop ->
     * getBalance validation. Returns a usable session or throws ApiError.
     * Auth / IP / cancelled failures abort immediately; other per-shop
     * failures move on to the next shop (DPGAPIClient.m:298-361).
     */
    @Throws(ApiError::class)
    @Synchronized
    fun sessionForAccount(phone: String, password: String, preferredShopId: Int = 92): Session {
        val base = loginAccount(phone, password)
        val resp = request(
            "GET", "/s/duopei-account/list-shop-account", session = base,
            extraHeaders = mapOf("sid" to base.sid), timeoutSec = 15.0
        )
        if (resp.optInt("code", -1) != 0) {
            throw ApiError(ApiError.Kind.API, rejectionText(resp, "店铺列表被拒绝"))
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
                    extraHeaders = mapOf("sid" to base.sid), timeoutSec = 15.0
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
            if (switched.optInt("code", -1) != 0) {
                // 换店被平台拒绝时留下原因，最终“无可用店铺会话”的报错才有诊断价值。
                lastError = ApiError(ApiError.Kind.API, rejectionText(switched, "切换店铺被拒绝"))
                continue
            }
            val switchedData = switched.optJSONObject("data")
            val token = switchedData?.optString("token", "") ?: ""
            if (token.isEmpty()) continue
            val candidate = Session(token = token, sid = sid)
            try {
                val probe = clerkGet(candidate)
                val clerk = extractClerk(probe)
                val actualShop = clerk?.opt("shopId")?.toString()
                if (parseCode(probe) == 0 && clerk != null && (actualShop.isNullOrBlank() || actualShop == sid)) {
                    session = candidate
                    return candidate
                }
            } catch (e: ApiError) {
                // 只有候选店铺的店员探测允许 AUTH 拒绝；基础登录/列表/切换仍立即中止。
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

    /**
     * 平台拒绝的统一文案：带上业务 code 与脱敏后的 msg，便于在日志里区分
     * “另一台设备在监听”“操作过于频繁”“会话失效”等原因。两者都拿不到时
     * 才退回 [prefix] 原文案。
     */
    private fun rejectionText(response: JSONObject, prefix: String = "平台拒绝操作"): String {
        val code = parseCode(response)
        val reason = sanitizeReason(response.optString("msg", response.optString("message", "")))
        return when {
            code != null && reason.isNotEmpty() -> "$prefix（code=$code：$reason）"
            code != null -> "$prefix（code=$code）"
            reason.isNotEmpty() -> "$prefix（$reason）"
            else -> prefix
        }
    }

    /**
     * 传输层失败的分类文案：TLS 被对端掐断、超时、拒连、对端关闭。
     * 这些情况完全没有业务 JSON，因此绝不会进入“平台拒绝操作（code=…）”。
     */
    private fun transportFailureText(error: Throwable): String = when (error) {
        is javax.net.ssl.SSLException -> "网络连接被中断（SSL 握手失败，可能被限流）"
        is java.net.SocketTimeoutException -> "请求超时（网络不稳定或对端限流）"
        is java.net.ConnectException -> "连接被拒绝（网络不可用或对端拒绝）"
        is java.io.EOFException -> "连接被对端关闭（可能被限流）"
        is java.io.IOException -> "网络请求失败（连接被中断）"
        else -> "网络请求失败"
    }

    /**
     * 平台原因脱敏：32 位以上十六进制串（token/密钥形态）替换为 `<token>`，
     * 连续 11 位及以上的数字串（手机号、订单号）替换为 `<num>`，折叠换行/制表
     * 并按字符截断到 60。token、authorization、密码从不参与这段文本的提取。
     */
    private fun sanitizeReason(raw: String): String = raw
        .replace(Regex("\\b[0-9a-fA-F]{32,}\\b"), "<token>")
        .replace(Regex("\\d{11,}"), "<num>")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(60)

    private fun successful(response: JSONObject): JSONObject {
        if (parseCode(response) != 0) throw ApiError(ApiError.Kind.API, rejectionText(response))
        return response
    }

    fun updateStatus(status: Int, updateAutoOnline: Boolean = false): JSONObject {
        require(status == 1 || status == 2)
        return successful(request("POST", "/s/c/clerk/updateStatus", body = mapOf(
            "status" to status, "updateAutoOnline" to updateAutoOnline.toString()), bodyType = "form"))
    }

    fun updateRandomOrderSwitch(enabled: Boolean): JSONObject = successful(request(
        "POST", "/s/c/clerk/updateRandomOrderSwitch", body = mapOf("randomOrderSwitch" to enabled.toString()), bodyType = "form"))

    fun getItemSwitchList(): JSONObject = successful(request("POST", "/s/c/clerk/getItemSwitchList",
        body = emptyMap<String, String>(), bodyType = "form"))

    fun updateItemSwitch(switchId: Int, switchIds: List<Int>): JSONObject = successful(request(
        "POST", "/s/c/clerk/updateItemSwitch", body = mapOf("switchId" to switchId,
            "switchIds" to JSONArray(switchIds).toString()), bodyType = "form"))

    fun statisticsClerkOrder(startTime: String, endTime: String): JSONObject {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(startTime) && Regex("\\d{4}-\\d{2}-\\d{2}").matches(endTime))
        return successful(request("GET", "/s/c/statistics/statisticsClerkOrder?startTime=${percentEncode(startTime)}&endTime=${percentEncode(endTime)}"))
    }

    fun orderList(startTime: String, endTime: String, pageNo: Int = 1, pageSize: Int = 20): JSONObject {
        val pattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
        require(pattern.matches(startTime) && pattern.matches(endTime) && pageNo > 0 && pageSize > 0)
        return successful(request("GET", "/s/c/order/list?pageNo=$pageNo&pageSize=$pageSize&startTime=${percentEncode(startTime)}&endTime=${percentEncode(endTime)}"))
    }

    // -- business calls -------------------------------------------------

    /** GET /s/c/clerk/get. Returns the raw decoded dict. */
    @Throws(ApiError::class)
    fun clerkGet(session: Session? = null): JSONObject =
        successful(request("GET", "/s/c/clerk/get", session = session, timeoutSec = 15.0))

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
        successful(resp)
        val orders = resp.optJSONArray("list")
        if (orders != null) return orders
        val data = resp.optJSONObject("data")
        val nested = data?.optJSONArray("list")
        return nested ?: throw ApiError(ApiError.Kind.API, "订单列表字段缺失或格式错误")
    }

    /**
     * GET /s/c/order/get?id=... (DPGrabber reconcile form, used to verify
     * a confirm result whose response never arrived). Returns the decoded
     * dict; the order object is in `order`.
     */
    @Throws(ApiError::class)
    fun orderGet(orderId: String, timeoutSec: Double = 15.0): JSONObject {
        val path = "/s/c/order/get?id=${percentEncode(orderId)}"
        return successful(request("GET", path, timeoutSec = timeoutSec))
    }

    /** POST /s/c/order/confirm?id=... . Returns the decoded dict. */
    @Throws(ApiError::class)
    fun orderConfirm(orderId: String, timeoutSec: Double = 10.0): JSONObject {
        val path = "/s/c/order/confirm?id=${percentEncode(orderId)}"
        return request("POST", path, timeoutSec = timeoutSec)
    }
}
