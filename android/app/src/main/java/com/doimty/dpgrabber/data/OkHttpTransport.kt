package com.doimty.dpgrabber.data

import com.doimty.dpgrabber.core.HttpResponse
import com.doimty.dpgrabber.core.HttpTransport
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [HttpTransport].
 *
 * The client itself enforces the protocol rules (host pinning, /s/ paths,
 * fixed headers); this class only performs the request. TLS verification
 * 始终使用系统证书链和主机名验证。旧 verifyTls 参数仅保留配置兼容，
 * 不允许将账号令牌发给未验证的服务器；禁止自动重定向和写请求重发。
 */
class OkHttpTransport(verifyTls: Boolean = true) : HttpTransport {

    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // 兼容旧配置参数，但所有构建均强制平台证书与主机名验证。
        builder.followRedirects(false).followSslRedirects(false)
        builder.retryOnConnectionFailure(false)
        builder.build()
    }

    override fun cancelAll() = client.dispatcher.cancelAll()

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Long
    ): HttpResponse {
        val requestBody = if (body != null) {
            body.toRequestBody(contentType?.toMediaTypeOrNull())
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            // OkHttp requires a (possibly empty) body for POST.
            ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
        } else {
            null
        }
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        // Per-call timeout on the call itself, so the shared client (and
        // its connection pool) is reused across polls instead of being
        // rebuilt for every request.
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs + 500, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return HttpResponse(resp.code, bytes)
        }
    }
}
