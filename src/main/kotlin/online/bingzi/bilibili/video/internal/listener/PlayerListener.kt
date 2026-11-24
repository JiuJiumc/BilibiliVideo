package online.bingzi.bilibili.video.internal.listener

import online.bingzi.bilibili.video.internal.credential.QrLoginService
import online.bingzi.bilibili.video.internal.ui.VirtualItemSession
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import online.bingzi.bilibili.video.internal.TaskScheduler

/**
 * 玩家事件监听器。
 *
 * 处理玩家下线时的资源清理。
 */
object PlayerListener : Listener {

    // 虚拟地图没有真实物品可移动；操作退出显示，禁止误操作原主手物品。
    private fun dismiss(player: Player): Boolean {
        if (!VirtualItemSession.hasSession(player.uniqueId)) return false
        VirtualItemSession.restoreItem(player)
        val uuid = player.uniqueId
        // 事件结束后再同步，避免取消丢弃/交换的修正包留下幽灵物品。
        TaskScheduler.entity(uuid) { if (!VirtualItemSession.hasSession(uuid)) it.updateInventory() }
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHeld(event: PlayerItemHeldEvent) { dismiss(event.player) }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwap(event: PlayerSwapHandItemsEvent) { if (dismiss(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) { if (dismiss(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (VirtualItemSession.hasSession(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryOpen(event: InventoryOpenEvent) { (event.player as? Player)?.let(::dismiss) }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let { if (dismiss(it)) event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        (event.whoClicked as? Player)?.let { if (dismiss(it)) event.isCancelled = true }
    }

    /**
     * 玩家下线时清理二维码登录会话和虚拟物品会话。
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId

        // 清理虚拟物品会话
        VirtualItemSession.cleanup(uuid)

        // 取消二维码登录会话
        QrLoginService.cancelLogin(uuid)
    }
}
