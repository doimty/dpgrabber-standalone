package com.doimty.dpgrabber.data

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.AppConfig

/** Activity 和 Service 仅共享进程内会话，不通过 Intent 传递凭据或令牌。 */
object SessionRepository {
    private var client: ApiClient? = null
    private var account: Pair<String, String>? = null
    private var connection: List<String>? = null
    @Volatile var revision: Long = 0
        private set

    @Synchronized
    fun forAccount(config: AppConfig, phone: String, password: String): ApiClient {
        val key = listOf(config.shopId.toString(), config.aesKeyHex, config.aesIvHex,
            config.aesKeyHexAlt, config.aesIvHexAlt)
        if (account != (phone to password) || connection != key || client?.isCancelled == true) {
            check(!com.doimty.dpgrabber.service.ListenService.isRunning) { "任务退出前不可替换会话或连接设置" }
            revision++
            client?.cancel()
            client?.session = null
            client = ApiClient(config.aesKeyHex, config.aesIvHex, config.aesKeyHexAlt,
                config.aesIvHexAlt, true, OkHttpTransport())
            account = phone to password
            connection = key
        }
        return checkNotNull(client)
    }

    @Synchronized
    fun invalidate() {
        check(!com.doimty.dpgrabber.service.ListenService.isRunning) { "任务退出前不可清除会话" }
        revision++
        client?.cancel()
        client?.session = null
        client = null
        account = null
        connection = null
    }
}
