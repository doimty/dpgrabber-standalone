package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import com.doimty.dpgrabber.data.OkHttpTransport
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 服务端推送，四渠道对齐 PC pc/notify.py：
 * - bark      POST <server>/push                     json device_key/title/body/group=DPGrabber
 * - serverchan POST https://sctapi.ftqq.com/<key>.send  form title/desp
 * - pushplus  POST https://www.pushplus.plus/send    json token/title/content/template=txt
 * - webhook   POST <url>                              json title/body
 *
 * 独立于 ApiClient：不携带平台令牌、不钉死 /s/ 路径。
 * 安卓侧所有 URL 强制 https://，即使 PC 允许 http 也不降级。
 * 单次尝试、不重试；任何异常收敛为 false，绝不打印 URL/key/secret/body/响应体。
 */
class PushNotifier(private val transport: HttpTransport = OkHttpTransport()) {
    fun send(title: String, body: String, provider: String, secret: String, barkServer: String): Boolean {
        return try {
            when (provider.trim().lowercase()) {
                "bark" -> sendBark(title, body, secret, barkServer)
                "serverchan" -> sendServerChan(title, body, secret)
                "pushplus" -> sendPushPlus(title, body, secret)
                "webhook" -> sendWebhook(title, body, secret)
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendBark(title: String, body: String, key: String, server: String): Boolean {
        if (key.isBlank()) return false
        val host = server.trim().ifEmpty { DEFAULT_BARK_SERVER }
        if (!host.startsWith("https://")) return false
        val payload = JSONObject()
            .put("device_key", key.trim())
            .put("title", title)
            .put("body", body)
            .put("group", "DPGrabber")
            .toString()
        val response = post(host.trimEnd('/') + "/push", JSON_TYPE, payload)
        return response.statusCode == 200 && hasCode(response, 200)
    }

    private fun sendServerChan(title: String, body: String, key: String): Boolean {
        if (key.isBlank()) return false
        val encodedKey = URLEncoder.encode(key.trim(), "UTF-8").replace("+", "%20")
        val payload = "title=" + URLEncoder.encode(title, "UTF-8").replace("+", "%20") +
            "&desp=" + URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        val response = post(
            "https://sctapi.ftqq.com/$encodedKey.send", FORM_TYPE,
            payload.toByteArray(Charsets.UTF_8)
        )
        return response.statusCode == 200 && hasCode(response, 0)
    }

    private fun sendPushPlus(title: String, body: String, token: String): Boolean {
        if (token.isBlank()) return false
        val payload = JSONObject()
            .put("token", token.trim())
            .put("title", title)
            .put("content", body)
            .put("template", "txt")
            .toString()
        val response = post(PUSHPLUS_URL, JSON_TYPE, payload)
        return response.statusCode == 200 && hasCode(response, 200)
    }

    private fun sendWebhook(title: String, body: String, url: String): Boolean {
        if (url.isBlank() || !url.trim().startsWith("https://")) return false
        val payload = JSONObject().put("title", title).put("body", body).toString()
        val response = post(url.trim(), JSON_TYPE, payload)
        return response.statusCode in 200..299
    }

    private fun post(url: String, contentType: String, payload: String): HttpResponse =
        post(url, contentType, payload.toByteArray(Charsets.UTF_8))

    private fun post(url: String, contentType: String, bytes: ByteArray): HttpResponse =
        transport.request(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to contentType),
            body = bytes,
            contentType = contentType,
            timeoutMs = 8_000L
        )

    /** 平台成功码判定：去空白后含 "code":N（避免空格/换行导致误判）。 */
    private fun hasCode(response: HttpResponse, code: Int): Boolean =
        response.bodyAsString.replace(Regex("\\s"), "").contains("\"code\":$code")

    private companion object {
        const val DEFAULT_BARK_SERVER = "https://api.day.app"
        const val PUSHPLUS_URL = "https://www.pushplus.plus/send"
        const val JSON_TYPE = "application/json"
        const val FORM_TYPE = "application/x-www-form-urlencoded"
    }
}
