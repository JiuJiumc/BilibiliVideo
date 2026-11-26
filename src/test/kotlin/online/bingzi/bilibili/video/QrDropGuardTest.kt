package online.bingzi.bilibili.video

import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import online.bingzi.bilibili.video.internal.nms.QrDropGuard
import org.junit.jupiter.api.Test
import kotlin.test.*

class QrDropGuardTest {
    private fun channel() = EmbeddedChannel().apply { pipeline().addLast("packet_handler", ChannelInboundHandlerAdapter()) }
    @Test fun `Q and control Q close display and never forward real drop even with empty hand`() {
        for (action in listOf("DROP_ITEM", "DROP_ALL_ITEMS")) {
            val ch=channel()
            var active=true; var dismissed=0
            val guard=QrDropGuard(ch,{ it==action }) {
                if (!active) false else { active=false; dismissed++; true }
            }
            try {
                guard.install(); ch.runPendingTasks()
                assertTrue(ch.writeInbound("MOVE")); assertEquals("MOVE",ch.readInbound<String>())
                assertFalse(ch.writeInbound(action)); assertNull(ch.readInbound<Any>())
                assertEquals(1,dismissed); assertFalse(active)
                ch.runPendingTasks()
                assertEquals(listOf("packet_handler",ch.pipeline().names().last()),ch.pipeline().names())
                assertTrue(ch.writeInbound(action)); assertEquals(action,ch.readInbound<String>())
            } finally { guard.close(); ch.finishAndReleaseAll() }
        }
    }
    @Test fun `closing before scheduled installation does not leave a handler behind`() {
        val ch=channel();var called=false
        val guard=QrDropGuard(ch,{true}) { called=true;true }
        try {
            guard.install();guard.close();ch.runPendingTasks()
            assertTrue(ch.writeInbound("DROP_ITEM"));assertEquals("DROP_ITEM",ch.readInbound<String>())
            assertFalse(called)
        } finally { ch.finishAndReleaseAll() }
    }
    @Test fun `stale session does not consume a real drop and close is idempotent`() {
        val ch=channel();val guard=QrDropGuard(ch,{true}) { false }
        try {
            guard.install();ch.runPendingTasks()
            assertTrue(ch.writeInbound("DROP_ITEM"));assertEquals("DROP_ITEM",ch.readInbound<String>())
            guard.close();guard.close();ch.runPendingTasks()
            assertEquals(2,ch.pipeline().names().size)
        } finally { ch.finishAndReleaseAll() }
    }
}
