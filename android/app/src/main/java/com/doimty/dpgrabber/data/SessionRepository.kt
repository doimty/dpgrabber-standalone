package com.doimty.dpgrabber.data

import com.doimty.dpgrabber.core.ApiClient
import com.doimty.dpgrabber.core.AppConfig
import com.doimty.dpgrabber.core.HttpTransport

/** Activity 和 Service 仅共享进程内会话，不通过 Intent 传递凭据或令牌。 */
object SessionRepository {
    private var client: ApiClient? = null
    private var account: Pair<String, String>? = null
    private var connection: List<String>? = null
    private var transportFactory: () -> HttpTransport = { OkHttpTransport() }
    @Volatile var revision: Long = 0
        private set

    @Synchronized
    fun forAccount(config: AppConfig, phone: String, password: String): ApiClient {
        val key = listOf(config.shopId.toString(), config.aesKeyHex, config.aesIvHex,
            config.aesKeyHexAlt, config.aesIvHexAlt)
        val sameIdentity = account == (phone to password) && connection == key
        val previous = client
        if (!sameIdentity || previous == null || previous.isCancelled) {
            check(!com.doimty.dpgrabber.service.ListenService.isRunning) { "任务退出前不可替换会话或连接设置" }
            // 停止只取消本轮传输，不是注销；仅相同账号、相同连接配置允许继承认证。
            val savedSession = if (sameIdentity) previous?.session else null
            val replacement = ApiClient(config.aesKeyHex, config.aesIvHex, config.aesKeyHexAlt,
                config.aesIvHexAlt, true, transportFactory()).apply { session = savedSession }
            revision++
            previous?.cancel()
            previous?.session = null
            client = replacement
            account = phone to password
            connection = key
        }
        return checkNotNull(client)
    }

    /**
     * 只读判定：当前账号是否持有认证会话，而不是本轮传输是否仍可用。
     *
     * 不构造客户端、不递增 [revision]、不取消任何会话。停止监听后旧客户端
     * 永久取消，但认证仍保留；工作线程退出后 forAccount 会把认证交给全新客户端。
     * 账号/连接配置变更或 invalidate 才清除认证，与手机号是否落盘无关。
     */
    @Synchronized
    fun hasLiveSession(): Boolean {
        val current = client ?: return false
        return current.session != null
    }

    /** 仅给界面提供当前认证店铺；不构造客户端、不改变会话或发起网络。 */
    @Synchronized
    fun currentShopId(): Int? = client?.session?.sid?.trim()?.toIntOrNull()?.takeIf { it > 0 }

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
