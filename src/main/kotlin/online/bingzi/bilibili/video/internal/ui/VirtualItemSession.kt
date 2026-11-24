package online.bingzi.bilibili.video.internal.ui

import online.bingzi.bilibili.video.internal.nms.NMSPacketHandler
import online.bingzi.bilibili.video.internal.util.QrCodeGenerator
import org.bukkit.entity.Player
import org.bukkit.map.MapPalette
import org.bukkit.map.MapView
import java.awt.Color
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 虚拟物品会话管理。
 *
 * 负责：
 * - 发送虚拟二维码地图物品到玩家主手槽位
 * - 保存玩家原主手物品和创建的 MapView
 * - 在扫码结束后刷新背包恢复原物品并清理 MapView
 */
object VirtualItemSession {

    // 客户端专用高位ID；不调用Bukkit.createMap，不写入世界地图存储。
    private val VIRTUAL_MAP_ID get() = online.bingzi.bilibili.video.internal.SafetySettings.virtualMapId
    private val sessions = ConcurrentHashMap<UUID, Long>()
    private val expiry = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "BilibiliVideo-MapExpiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun initialize() {
        expiry.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            sessions.entries.forEach { (uuid, deadline) ->
                if (now >= deadline) online.bingzi.bilibili.video.internal.TaskScheduler.entity(uuid,
                    retired = { sessions.remove(uuid, deadline) }) { player ->
                    if (sessions[uuid] == deadline) restoreItem(player)
                }
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    fun sendVirtualItem(player: Player, qrUrl: String) {
        val image = QrCodeGenerator.generateQrImage(qrUrl, 128)
        val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP)
        val meta = item.itemMeta as org.bukkit.inventory.meta.MapMeta
        meta.mapId = VIRTUAL_MAP_ID
        meta.setDisplayName("§b扫描二维码 · §f哔哩哔哩")
        item.itemMeta = meta
        sessions[player.uniqueId] = System.currentTimeMillis() + 180_000L
        try {
            NMSPacketHandler.instance.sendSlotItem(player, 36 + player.inventory.heldItemSlot, item)
            NMSPacketHandler.instance.sendMapData(player, VIRTUAL_MAP_ID, imageToMapColors(image))
        } catch (t: Throwable) {
            restoreItem(player)
            throw t
        }
    }

    fun restoreItem(player: Player) {
        if (sessions.remove(player.uniqueId) != null) NMSPacketHandler.instance.refreshInventory(player)
    }
    fun cleanup(playerUuid: UUID) { sessions.remove(playerUuid) }
    fun hasSession(playerUuid: UUID): Boolean = sessions.containsKey(playerUuid)
    fun shutdown() {
        expiry.shutdownNow()
        // 停服时网络会话关闭；不在已禁用插件上新建Entity任务。
        // 不支持热卸载；更新必须完整重启，真实背包从未改变。
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
