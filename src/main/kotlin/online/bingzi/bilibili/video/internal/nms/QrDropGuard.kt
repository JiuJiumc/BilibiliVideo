package online.bingzi.bilibili.video.internal.nms

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 只读入站动作，不改字节码；网络线程仅调用普通会话状态回调，不访问玩家背包。 */
internal class QrDropGuard(
    private val channel: Channel,
    private val isDrop: (Any) -> Boolean,
    private val dismiss: () -> Boolean
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val name = "jiujiublbl-qr-${UUID.randomUUID()}"
    private val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (!closed.get() && isDrop(msg) && dismiss()) { close(); return }
            ctx.fireChannelRead(msg)
        }
        override fun channelInactive(ctx: ChannelHandlerContext) {
            close()
            ctx.fireChannelInactive()
        }
    }
    fun install() {
        channel.eventLoop().execute {
            if (!closed.get() && channel.isActive) {
                val pipeline = channel.pipeline()
                if (pipeline.get("packet_handler") != null) pipeline.addBefore("packet_handler", name, handler)
                else { closed.set(true); dismiss() }
            }
        }
    }
    override fun close() {
        if (closed.getAndSet(true)) return
        val remove = Runnable { if (channel.pipeline().get(name) === handler) channel.pipeline().remove(name) }
        if (channel.eventLoop().inEventLoop()) remove.run()
        else if (!channel.eventLoop().isShuttingDown) channel.eventLoop().execute(remove)
    }
}
