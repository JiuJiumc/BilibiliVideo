package online.bingzi.bilibili.video.internal.http

import okhttp3.OkHttpClient

/** 创建与关闭互斥；关闭未用过的服务不会新建客户端，晚到任务也不能重新创建。 */
internal class ManagedHttpClient(private val factory: () -> OkHttpClient) : AutoCloseable {
    private var closed = false
    private var client: OkHttpClient? = null
    @Synchronized fun get(): OkHttpClient {
        check(!closed) { "HTTP服务已关闭" }
        return client ?: factory().also { client = it }
    }
    override fun close() {
        val current = synchronized(this) {
            if (closed) return
            closed = true
            client.also { client = null }
        } ?: return
        current.dispatcher.cancelAll()
        current.connectionPool.evictAll()
        current.dispatcher.executorService.shutdownNow()
        current.cache?.close()
    }
}
