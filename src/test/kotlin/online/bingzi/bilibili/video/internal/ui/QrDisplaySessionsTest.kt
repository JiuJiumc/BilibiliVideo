package online.bingzi.bilibili.video.internal.ui

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import kotlin.test.*

class QrDisplaySessionsTest {
    @Test fun `inventory overwrite can be repaired while old queued tasks cannot revive replaced map`() {
        val table = QrDisplaySessions<String> { 2 }
        val id = UUID.randomUUID()
        val old = QrDisplaySessions.Entry(0, 180000L, "old map")
        val next = QrDisplaySessions.Entry(0, 180001L, "new map")
        assertTrue(table.put(id, old))
        var clientSlot = "real item"
        fun refresh(entry: QrDisplaySessions.Entry<String>) {
            if (table.current(id, entry)) clientSlot = entry.payload
        }
        refresh(old); assertEquals("old map", clientSlot)
        clientSlot = "real item" // 服务器背包同步覆盖显示。
        refresh(old); assertEquals("old map", clientSlot)
        assertTrue(table.put(id, next))
        refresh(next); refresh(old)
        assertEquals("new map", clientSlot)
        assertFalse(table.remove(id, old))
        assertSame(next, table.remove(id))
        clientSlot = "real item"
        refresh(next)
        assertEquals("real item", clientSlot)
    }

    @Test fun `concurrent timer ticks queue only one callback per session`() {
        val entry = QrDisplaySessions.Entry(8, 1L, "map")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val gate = CountDownLatch(1)
            val attempts = (1..100).map { executor.submit<Boolean> { gate.await(); entry.queued.compareAndSet(false, true) } }
            gate.countDown()
            assertEquals(1, attempts.count { it.get() })
            entry.queued.set(false)
            assertTrue(entry.queued.compareAndSet(false,true))
        } finally { executor.shutdownNow() }
    }

    @Test fun `display capacity and disconnect cleanup release all sessions`() {
        val table = QrDisplaySessions<String> { 1 }
        val a=UUID.randomUUID(); val b=UUID.randomUUID()
        assertTrue(table.put(a,QrDisplaySessions.Entry(0,1L,"map")))
        assertFalse(table.put(b,QrDisplaySessions.Entry(1,1L,"map")))
        assertTrue(table.put(a,QrDisplaySessions.Entry(2,2L,"new")))
        table.remove(a)
        assertTrue(table.put(b,QrDisplaySessions.Entry(1,1L,"map")))
        table.clear()
        var retained=0
        table.forEach { _, _ -> retained++ }
        assertEquals(0,retained)
    }
}
