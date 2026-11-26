package online.bingzi.bilibili.video.internal.nms

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


/**
 * NMS 发包抽象层。
 *
 * 当前维护实现针对26.2 Mojang映射，用于发送虚拟物品。
 */
abstract class NMSPacketHandler {

    /**
     * 发送虚拟物品到指定槽位。
     *
     * @param player 目标玩家
     * @param slot 槽位编号（玩家背包窗口 ID=0 的槽位，主手 = 36 + heldItemSlot）
     * @param item 要显示的物品
     */
    abstract fun sendSlotItem(player: Player, slot: Int, item: ItemStack)

    /**
     * 刷新玩家整个背包，使虚拟物品消失，恢复真实物品。
     */
    abstract fun refreshInventory(player: Player)

    /** 监听Q/Ctrl+Q动作，返回本会话清理句柄；onDrop在网络线程运行。 */
    abstract fun watchDrop(player: Player, onDrop: () -> Boolean): AutoCloseable

    /**
     * 发送地图数据到玩家客户端。
     *
     * @param player 目标玩家
     * @param mapId 客户端虚拟地图编号
     * @param colors 128x128 地图颜色数据（MapPalette 颜色字节数组）
     */
    abstract fun sendMapData(player: Player, mapId: Int, colors: ByteArray)

    companion object {
        val instance by lazy { NMSPacketHandlerImpl() }
    }
}
