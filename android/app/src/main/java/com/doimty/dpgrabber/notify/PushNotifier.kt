package com.doimty.dpgrabber.notify

import com.doimty.dpgrabber.core.HttpTransport
import com.doimty.dpgrabber.data.OkHttpTransport
import org.json.JSONObject

/**
 * 服务端 webhook 推送。独立于 ApiClient：不携带平台令牌、不钉死 /s/ 路径。
 * 单次尝试、不重试；任何异常都收敛为 false，绝不抛出。
 * 不打印 URL / key / body，返回值只表示成功与否（ failures 由调用方按类别记录）。
 */
class PushNotifier(private val transport: HttpTransport = OkHttpTransport()) {
    fun send(title: String, body: String, webhook: String): Boolean {
        // 配置类别：非 HTTPS 直接拒绝，绝不发起请求，也绝不降级为明文 HTTP。
        if (!webhook.startsWith("https://")) return false
        return try {
            val payload = JSONObject().put("title", title).put("body", body).toString()
            val response = transport.request(
                method = "POST",
                url = webhook,
                headers = mapOf("Content-Type" to "application/json"),
                body = payload.toByteArray(Charsets.UTF_8),
                contentType = "application/json",
                timeoutMs = 8_000L
            )
            response.statusCode in 200..299
        } catch (_: Throwable) {
            // 网络类别 / 序列化类别统一失败，不重试、不外泄细节。
            false
        }
    }
}
