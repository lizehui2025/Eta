package io.github.mangi.eta.agent.model

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L
    const val CHAT_CALL_TIMEOUT_MS = 15 * 60 * 1000L
    const val COMPACTION_CALL_TIMEOUT_MS = 90_000L
    const val REWRITE_CALL_TIMEOUT_MS = 5 * 60 * 1000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val chatModelClient: OkHttpClient by lazy {
        modelClient.newBuilder().callTimeout(CHAT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private val compactionModelClient: OkHttpClient by lazy {
        modelClient.newBuilder().callTimeout(COMPACTION_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    private val rewriteModelClient: OkHttpClient by lazy {
        modelClient.newBuilder().callTimeout(REWRITE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /**
     * 按用途选择带总超时的模型客户端：
     * - 普通对话允许长任务，但总时长有上限；
     * - 压缩必须落在 90 秒墙钟内，避免用户无限等待；
     * - 回复改写是用户等待中的轻量操作，限制为 5 分钟。
     */
    fun modelClientFor(purpose: ProviderRequestPurpose): OkHttpClient = when (purpose) {
        ProviderRequestPurpose.CHAT -> chatModelClient
        ProviderRequestPurpose.COMPACTION -> compactionModelClient
        ProviderRequestPurpose.REPLY_REWRITE -> rewriteModelClient
    }

    val client: OkHttpClient by lazy {
        // 并发子代理不设并行度上限：网络层不再为扇出设卡（此前 32/16 会让大规模扇出排队）。
        // 请求并发仍受连接池复用与系统资源约束；如需收敛请在 spawn_agents 调用中显式声明参数。
        val dispatcher = Dispatcher().apply {
            maxRequests = Int.MAX_VALUE
            maxRequestsPerHost = Int.MAX_VALUE
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
