package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.http.HttpBody
import online.bingzi.bilibili.video.internal.http.ManagedHttpClient
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class HttpLimitsTest {
    @Test fun `response limit accepts exact boundary rejects larger and unknown length`() {
        val n=HttpBody.MAX_BYTES.toInt()
        assertEquals(n,HttpBody.read(ByteArray(n) { 65 }.toResponseBody()).length)
        assertFailsWith<IOException> { HttpBody.read(ByteArray(n+1).toResponseBody()) }
        var sourceReads=0L
        val unknown=object : ResponseBody() {
            override fun contentType()="application/json; charset=utf-8".toMediaType()
            override fun contentLength()=-1L
            override fun source()=Buffer().write(ByteArray(n+1) { 65 }).also { sourceReads=it.size }
        }
        assertFailsWith<IOException> { HttpBody.read(unknown) }
        assertEquals((n+1).toLong(),sourceReads)
        assertEquals("你好",HttpBody.read("你好".toResponseBody("application/json; charset=utf-8".toMediaType())))
    }
    @Test fun `unused shutdown does not create client and late access rejected`() {
        val created=AtomicInteger()
        val managed=ManagedHttpClient { created.incrementAndGet();OkHttpClient() }
        managed.close();managed.close()
        assertEquals(0,created.get())
        assertFailsWith<IllegalStateException> { managed.get() }
    }
    @Test fun `concurrent access creates one client and close terminates its executor`() {
        val created=AtomicInteger()
        val managed=ManagedHttpClient { created.incrementAndGet();OkHttpClient() }
        val pool=Executors.newFixedThreadPool(8)
        try {
            val clients=(1..128).map { pool.submit<OkHttpClient> { managed.get() } }.map { it.get(5,TimeUnit.SECONDS) }
            assertEquals(1,created.get());assertTrue(clients.all { it===clients.first() })
            managed.close()
            assertTrue(clients.first().dispatcher.executorService.isShutdown)
            assertFailsWith<IllegalStateException> { managed.get() }
        } finally { managed.close();pool.shutdownNow() }
    }
    @Test fun `create close race never leaves a usable client after closure`() {
        val pool=Executors.newFixedThreadPool(4)
        try {
            repeat(100) {
                val managed=ManagedHttpClient { OkHttpClient() }
                val read=pool.submit<OkHttpClient?> { runCatching { managed.get() }.getOrNull() }
                val close=pool.submit { managed.close() }
                close.get(5,TimeUnit.SECONDS)
                val client=read.get(5,TimeUnit.SECONDS)
                if(client!=null) assertTrue(client.dispatcher.executorService.isShutdown)
                assertFailsWith<IllegalStateException> { managed.get() }
            }
        } finally { pool.shutdownNow() }
    }
}
