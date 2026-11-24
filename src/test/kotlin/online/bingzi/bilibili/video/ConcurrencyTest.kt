package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.OperationGate
import online.bingzi.bilibili.video.internal.database.ClaimStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ConcurrencyTest {
    @TempDir lateinit var directory: Path

    @Test fun `64 concurrent claim requests reserve once and survive reopen`() {
        val url = "jdbc:sqlite:${directory.resolve("claims.db")}"
        DriverManager.getConnection(url).use { ClaimStore.create(it, "claims") }
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val futures = (1..64).map {
                pool.submit<Boolean> {
                    start.await()
                    DriverManager.getConnection(url).use { connection ->
                        connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
                        ClaimStore.reserve(connection, "claims", "same-video-same-player", "uuid")
                    }
                }
            }
            start.countDown()
            assertEquals(1, futures.count { it.get(20, TimeUnit.SECONDS) })
            DriverManager.getConnection(url).use {
                assertFalse(ClaimStore.reserve(it, "claims", "same-video-same-player", "uuid"))
                assertTrue(ClaimStore.reserve(it, "claims", "another-video", "uuid"))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `database failure is never treated as duplicate`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {
            assertFailsWith<java.sql.SQLException> { ClaimStore.reserve(it, "missing", "key", "uuid") }
        }
    }

    @Test fun `dedup keys distinguish fields and strategy`() {
        assertNotEquals(ClaimStore.key("P", "a:b", "c", "d"), ClaimStore.key("P", "a", "b:c", "d"))
        assertNotEquals(ClaimStore.key("P", "a", "b", "d"), ClaimStore.key("B", "a", "b", "d"))
        assertEquals(64, ClaimStore.key("P", "a", "b", "d").length)
    }

    @Test fun `gate bounds memory and releases without duplicate permits`() {
        val gate = OperationGate(2)
        val one = UUID.randomUUID(); val two = UUID.randomUUID(); val three = UUID.randomUUID()
        assertTrue(gate.enter(one)); assertFalse(gate.enter(one))
        assertTrue(gate.enter(two)); assertFalse(gate.enter(three)); assertEquals(2, gate.size())
        gate.leave(one); gate.leave(one)
        assertTrue(gate.enter(three)); assertFalse(gate.enter(one))
        gate.leave(two); gate.leave(three); assertEquals(0, gate.size())
    }
    @Test fun `sqlite worker supports nested writes and shuts down`() {
        val worker = online.bingzi.bilibili.video.internal.database.SqliteWriteExecutor
        worker.initialize(online.bingzi.bilibili.video.internal.config.DatabaseType.SQLITE)
        try {
            assertEquals(42, worker.executeWrite { worker.executeWrite { 42 } })
        } finally { worker.shutdown() }
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("bv-sqlite-write-") })
    }
}
