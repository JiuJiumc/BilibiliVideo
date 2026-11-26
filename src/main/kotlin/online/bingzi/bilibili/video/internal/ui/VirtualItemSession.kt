package online.bingzi.bilibili.video.internal.ui

import online.bingzi.bilibili.video.internal.Messages
import online.bingzi.bilibili.video.internal.ChatMessages

import online.bingzi.bilibili.video.internal.nms.NMSPacketHandler
import online.bingzi.bilibili.video.internal.util.QrCodeGenerator
import org.bukkit.entity.Player
import org.bukkit.map.MapPalette
import java.awt.Color
import java.util.*

/**
 * 虚拟物品会话管理。
 *
 * 负责：
 * - 发送虚拟二维码地图物品到玩家主手槽位
 * - 真实背包不变，限时维护客户端覆盖层，抵抗关菜单后的背包同步
 * - 扫码结束、交互退出或下线时清理覆盖层；不创建世界MapView
 */
object VirtualItemSession {

    // 客户端专用高位ID；不调用Bukkit.createMap，不写入世界地图存储。
    private val VIRTUAL_MAP_ID get() = online.bingzi.bilibili.video.internal.SafetySettings.virtualMapId
    private class Display(val item: org.bukkit.inventory.ItemStack) {
        @Volatile var guard: AutoCloseable? = null
    }
    private val sessions = QrDisplaySessions<Display> {
        online.bingzi.bilibili.video.internal.SafetySettings.maxSessions
    }
    private val expiry = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "JiuJiublbl-MapExpiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun initialize() {
        expiry.scheduleWithFixedDelay({
            sessions.forEach { uuid, entry ->
                if (entry.queued.compareAndSet(false, true)) {
                    online.bingzi.bilibili.video.internal.TaskScheduler.entity(uuid,
                        retired = { if (sessions.remove(uuid, entry)) entry.payload.guard?.close(); entry.queued.set(false) }) { player ->
                        try {
                            if (sessions.current(uuid, entry)) {
                                if (System.currentTimeMillis() >= entry.deadline || player.isDead ||
                                    player.inventory.heldItemSlot != entry.slot ||
                                    player.openInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                                    restoreItem(player)
                                } else {
                                    updateText(entry.payload.item)
                                    NMSPacketHandler.instance.sendSlotItem(player, 36 + entry.slot, entry.payload.item)
                                }
                            }
                        } catch (t: Throwable) {
                            restoreItem(player)
                            online.bingzi.bilibili.video.internal.warning("二维码显示刷新失败: ${t.javaClass.simpleName}")
                        } finally { entry.queued.set(false) }
                    }
                }
            }
        }, 250, online.bingzi.bilibili.video.internal.SafetySettings.qrRefreshMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Suppress("DEPRECATION")
    fun sendVirtualItem(player: Player, qrUrl: String) {
        val image = QrCodeGenerator.generateQrImage(qrUrl, 128)
        val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP)
        val meta = item.itemMeta as org.bukkit.inventory.meta.MapMeta
        meta.mapId = VIRTUAL_MAP_ID
        item.itemMeta = meta
        updateText(item)
        // 关菜单后可能还有已排队的真实背包同步；后续限时刷新会重新补上覆盖层。
        player.closeInventory()
        val entry = QrDisplaySessions.Entry(player.inventory.heldItemSlot,
            System.currentTimeMillis() + online.bingzi.bilibili.video.internal.SafetySettings.sessionMillis, Display(item))
        // 同玩家换二维码时关闭旧监听；真实背包始终不变。
        sessions.remove(player.uniqueId)?.payload?.guard?.close()
        check(sessions.put(player.uniqueId, entry)) { Messages.text("qr-display.capacity") }
        try {
            val uuid = player.uniqueId
            entry.payload.guard = NMSPacketHandler.instance.watchDrop(player) {
                // Q在真实主手为空时不会产生PlayerDropItemEvent，因此在入站动作处结束显示。
                if (!sessions.remove(uuid, entry)) false else {
                    entry.payload.guard?.close()
                    online.bingzi.bilibili.video.internal.TaskScheduler.entity(uuid) { current ->
                        if (!sessions.contains(uuid)) NMSPacketHandler.instance.refreshInventory(current)
                    }
                    true // 吞掉本次Q，不能让它丢弃真实主手物品。
                }
            }
            NMSPacketHandler.instance.sendSlotItem(player, 36 + player.inventory.heldItemSlot, item)
            NMSPacketHandler.instance.sendMapData(player, VIRTUAL_MAP_ID, imageToMapColors(image))
        } catch (t: Throwable) {
            restoreItem(player)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun updateText(item: org.bukkit.inventory.ItemStack) {
        val name = ChatMessages.codes(Messages.text("qr-display.qr-item-name"))
        val lore = ChatMessages.codes(Messages.text("qr-display.qr-item-lore"))
        val meta = item.itemMeta
        // 配置没变时不重复写入元数据；reload后下次补发自动更新。
        val lines = if (lore.isEmpty()) emptyList() else lore.lines()
        if (meta.displayName != name || meta.lore.orEmpty() != lines) {
            meta.setDisplayName(name)
            meta.lore = lines
            item.itemMeta = meta
        }
    }

    fun restoreItem(player: Player) {
        val removed = sessions.remove(player.uniqueId) ?: return
        removed.payload.guard?.close()
        NMSPacketHandler.instance.refreshInventory(player)
    }
    fun cleanup(playerUuid: UUID) { sessions.remove(playerUuid)?.payload?.guard?.close() }
    fun hasSession(playerUuid: UUID): Boolean = sessions.contains(playerUuid)
    fun shutdown() {
        expiry.shutdownNow()
        // 停服时网络会话关闭；不在已禁用插件上新建Entity任务。
        // 不支持热卸载；更新必须完整重启，真实背包从未改变。
        sessions.forEach { _, entry -> entry.payload.guard?.close() }
        sessions.clear()
    }

    /**
     * 将 BufferedImage 转换为 MapPalette 颜色字节数组。
     *
     * 地图数据格式：128x128 字节数组，每个字节是 MapPalette 颜色索引。
     * 地图数据按行排列：colors[y * 128 + x]
     * （从左上角开始，一列一列地写入）
     *
     * 注意：不使用 MapPalette.matchColor，因为在某些版本上可能返回不正确的索引。
     * 二维码只需要黑白两色，使用简单的灰度判断更可靠。
     */
    @Suppress("DEPRECATION")
    private fun imageToMapColors(image: java.awt.image.BufferedImage): ByteArray {
        val colors = ByteArray(128 * 128)
        val width = minOf(128, image.width)
        val height = minOf(128, image.height)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val rgb = Color(image.getRGB(x, y))
                val gray = (rgb.red + rgb.green + rgb.blue) / 3
                // 使用 MapPalette 预定义常量，比 matchColor 更可靠
                // 注意：Minecraft 地图数据是行优先排列 (row-major)
                colors[y * 128 + x] = if (gray < 128) MapPalette.DARK_GRAY else MapPalette.WHITE
            }
        }
        return colors
    }
}
