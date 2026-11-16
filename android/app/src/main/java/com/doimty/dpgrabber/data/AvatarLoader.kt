package com.doimty.dpgrabber.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.doimty.dpgrabber.core.ApiClient
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 头像独立传输，不共享业务请求的令牌、Cookie 或重定向策略。 */
class AvatarLoader(private val cacheRoot: File) {
    fun interface Transport {
        fun download(url: String): ByteArray?
    }

    fun load(account: String, clerkId: Int, url: String): Bitmap? {
        val safeUrl = normalizeUrl(url) ?: return null
        val file = File(accountDirectory(account), "avatar_${clerkId}_${hash(safeUrl)}.png")
        read(file)?.let { return it }
        val bytes = transport.download(safeUrl)?.takeIf { it.size <= MAX_BYTES } ?: return null
        val bitmap = decode(bytes) ?: return null
        try {
            file.parentFile?.mkdirs()
            // 只有解码成功的图片才会进入缓存；URL 只以哈希版本存在。
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
        return bitmap
    }

    fun restore(account: String, clerkId: Int): Bitmap? = try {
        accountDirectory(account).listFiles()
            ?.filter { it.name.startsWith("avatar_${clerkId}_") && it.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?.firstNotNullOfOrNull { read(it) }
    } catch (_: Exception) { null }

    private fun accountDirectory(account: String) = File(File(cacheRoot, "avatars"), hash(account))

    private fun read(file: File): Bitmap? = try {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) null
        else decode(file.readBytes())
    } catch (_: Exception) { null }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_DIMENSION = 16_384
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()

        // 仅 androidTest 通过反射替换；生产没有开关或测试地址。
        @Volatile private var transport: Transport = Transport { start -> downloadHttps(start) }

        fun normalizeUrl(raw: String): String? = try {
            if (raw.isBlank()) null else {
                val base = URI(ApiClient.BASE_URL + "/")
                val uri = when {
                    raw.trim().startsWith("//") -> URI("https:" + raw.trim())
                    else -> base.resolve(raw.trim())
                }
                val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
                if (uri.userInfo != null || uri.host.isNullOrBlank() || scheme !in setOf("http", "https") ||
                    (uri.port !in setOf(-1, 443) && !(scheme == "http" && uri.port == 80))) null
                else {
                    val authority = uri.host + if (uri.port == 443) ":443" else ""
                    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                    URI("https://$authority${uri.rawPath.orEmpty().ifEmpty { "/" }}$query").toASCIIString()
                }
            }
        } catch (_: Exception) { null }

        private fun downloadHttps(start: String): ByteArray? {
            return try {
            var url = start
            var result: ByteArray? = null
            for (hop in 0..3) {
                var next: String? = null
                // 全新请求仅包含公共图片 Accept 头；不复用任何业务客户端。
                client.newCall(Request.Builder().url(url).header("Accept", "image/png,image/jpeg,image/webp,image/gif").build())
                    .execute().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            if (hop == 3) return null
                            val location = response.header("Location") ?: return null
                            val target = URI(url).resolve(location)
                            if (target.scheme != "https") return null
                            next = normalizeUrl(target.toString()) ?: return null
                        } else {
                            if (!response.isSuccessful) return null
                            val body = response.body ?: return null
                            if (body.contentLength() > MAX_BYTES) return null
                            val out = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    if (out.size() + size > MAX_BYTES) return null
                                    out.write(buffer, 0, size)
                                }
                            }
                            result = out.toByteArray()
                        }
                    }
                if (next == null) break
                url = next!!
            }
            result
            } catch (_: Exception) { null }
        }

        private fun decode(bytes: ByteArray): Bitmap? = try {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outMimeType !in setOf("image/png", "image/jpeg", "image/webp", "image/gif") ||
                    options.outWidth !in 1..MAX_DIMENSION || options.outHeight !in 1..MAX_DIMENSION) null
                else {
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 384) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
