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
 * follows [verifyTls]: when false, certificate validation is skipped —
 * the same trade-off the PC reference tool makes for its listen session
 * (PROTOCOL.md section 10.3). The default is true and the UI warns
 * when it is off.
 */
class OkHttpTransport(verifyTls: Boolean = true) : HttpTransport {

    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (!verifyTls) {
            val trustAll = TrustAllCerts.trustManager
            builder.sslSocketFactory(TrustAllCerts.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

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
