package online.bingzi.bilibili.video.internal.nms

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Optional

/** 26.2 Mojang映射发包；只反射公开运行时接口，不使用版本门禁或字节码转换。 */
class NMSPacketHandlerImpl : NMSPacketHandler() {
    private val craftPackage = Bukkit.getServer().javaClass.packageName
    private val itemClass = Class.forName("net.minecraft.world.item.ItemStack")
    private val packetClass = Class.forName("net.minecraft.network.protocol.Packet")
    private val craftItem = Class.forName("$craftPackage.inventory.CraftItemStack")
        .getMethod("asNMSCopy", ItemStack::class.java)
    private val craftPlayer = Class.forName("$craftPackage.entity.CraftPlayer").getMethod("getHandle")
    private val connectionField = craftPlayer.returnType.getField("connection")
    private val send = connectionField.type.getMethod("send", packetClass)
    private val slotConstructor = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket")
        .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, itemClass)
    private val mapId = Class.forName("net.minecraft.world.level.saveddata.maps.MapId")
    private val mapIdConstructor = mapId.getConstructor(Int::class.javaPrimitiveType)
    private val patchClass = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData\$MapPatch")
    private val patchConstructor = patchClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
    private val mapConstructor = Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket")
        .getConstructor(mapId, Byte::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Optional::class.java, Optional::class.java)

    private fun sendPacket(player: Player, packet: Any) {
        send.invoke(connectionField.get(craftPlayer.invoke(player)), packet)
    }
    override fun sendSlotItem(player: Player, slot: Int, item: ItemStack) {
        sendPacket(player, slotConstructor.newInstance(0, 0, slot, craftItem.invoke(null, item)))
    }
    override fun refreshInventory(player: Player) { player.updateInventory() }
    override fun sendMapData(player: Player, mapId: Int, colors: ByteArray) {
        require(colors.size == 128 * 128)
        sendPacket(player, mapConstructor.newInstance(mapIdConstructor.newInstance(mapId), 0.toByte(), false,
            Optional.empty<Any>(), Optional.of(patchConstructor.newInstance(0, 0, 128, 128, colors))))
    }
}
