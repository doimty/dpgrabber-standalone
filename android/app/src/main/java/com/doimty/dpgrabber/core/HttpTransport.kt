package com.doimty.dpgrabber.core

/**
 * Injectable HTTP transport. The core ApiClient only talks to this
 * interface, so unit tests can stub it and never send a real request.
 */
interface HttpTransport {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Long
    ): HttpResponse
}

data class HttpResponse(val statusCode: Int, val body: ByteArray) {
    val bodyAsString: String
        get() = body.toString(Charsets.UTF_8)
}
