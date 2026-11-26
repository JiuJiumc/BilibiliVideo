package online.bingzi.bilibili.video.internal.ui

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuiSessionsTest {
    @Test fun `old close cannot remove replacement and quit releases capacity`() {
        val store = GuiSessions<Any>()
        val id = UUID.randomUUID(); val old = Any(); val next = Any()
        assertTrue(store.admit(id, old, 1))
        assertFalse(store.admit(UUID.randomUUID(), Any(), 1))
        assertTrue(store.admit(id, next, 1))
        assertFalse(store.remove(id, old))
        assertSame(next, store[id])
        store.remove(id)
        assertTrue(store.admit(UUID.randomUUID(), Any(), 1))
        store.clear()
        assertEquals(0, store.size())
    }
    @Test fun `simultaneous region admissions never exceed capacity`() {
        val store = GuiSessions<Any>(); val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1); val done = CountDownLatch(200)
        try {
            repeat(200) { pool.execute { try { start.await(); store.admit(UUID.randomUUID(), Any(), 32) } finally { done.countDown() } } }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(32, store.size())
        } finally { pool.shutdownNow(); store.clear() }
    }
}
