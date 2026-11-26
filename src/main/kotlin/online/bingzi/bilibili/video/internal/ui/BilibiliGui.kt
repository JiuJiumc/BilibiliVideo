package online.bingzi.bilibili.video.internal.ui

import online.bingzi.bilibili.video.internal.bilibiliMessage
import city.jiujiu.lib.gui.presentation.GuiInventories
import city.jiujiu.lib.gui.presentation.SelfSoundedInventory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.NamedTextColor
import online.bingzi.bilibili.video.internal.TaskScheduler
import online.bingzi.bilibili.video.internal.bilibili.dto.TripleStatusResult
import online.bingzi.bilibili.video.internal.command.BilibiliVideoCommand
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.repository.BoundAccountRepository
import online.bingzi.bilibili.video.internal.service.CredentialService
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import taboolib.platform.util.bukkitPlugin
import java.util.UUID

/** 只有玩家Entity上下文读写会话和背包；IO只传UUID、令牌、BV号和不可变结果。 */
internal object BilibiliGui : Listener {
    @Volatile private var settings: GuiSettings? = null
    @Volatile private var available = false
    private val sessions = GuiSessions<Session>()
    private data class Video(val bvid: String, val name: String, val lore: List<String>)
    private class Session(val id: UUID, val config: GuiSettings, val videos: List<Video>) {
        val token = UUID.randomUUID()
        var page = 0
        var selected: Video? = null
        var binding: String? = null
        var loading = true
        var busy = false
        var status: TripleStatusResult? = null
        var nextClick = 0L
        var view: View? = null
    }
    private class View(val owner: UUID) : SelfSoundedInventory {
        lateinit var contents: Inventory
        val actions = mutableMapOf<Int, String>() // 构造后不再修改，仅由所属玩家访问。
        override fun getInventory() = contents
    }

    fun initialize() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("JiuJiuLib")) {
                warning("[BV] 未检测到JiuJiuLib，内置GUI不可用，原命令仍可使用。")
                return
            }
            GuiArt.initialize()
            settings = GuiSettings.load()
            Bukkit.getPluginManager().registerEvents(this, bukkitPlugin)
            available = true
        } catch (t: Throwable) {
            warning("[BV] 内置GUI初始化失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
    fun reload(): Boolean = if (!available) false else try {
        settings = GuiSettings.load()
        true
    } catch (t: Throwable) { warning("[BV] GUI重载失败，保留原设置: ${t.message}"); false }

    fun open(player: Player) {
        val config = settings
        if (!available || config == null) { player.bilibiliMessage("§e[BV] 界面需要兼容的JiuJiuLib及配套CE资源，请联系管理员。"); return }
        if (!config.enabled) { player.bilibiliMessage(config.message("disabled")); return }
        if (!player.hasPermission("bilibili.video.use")) return
        if (config.requirePack && !player.hasResourcePack()) { player.bilibiliMessage(config.message("resource-pack")); return }
        val videos = RewardConfigManager.getConfiguredBvids().filter { it.matches(Regex("BV1[A-Za-z0-9]{9}")) }.map { bvid ->
            val custom = config.videos[bvid]
            Video(bvid, custom?.name ?: bvid, custom?.lore?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(RewardConfigManager.resolveForBvid(bvid).template?.description))
        }
        val session = Session(player.uniqueId, config, videos)
        if (!sessions.admit(session.id, session, config.maxSessions)) { player.bilibiliMessage(config.message("busy")); return }
        render(player, session)
        if (GuiArt.hasMissing(config.title, "header") || config.labels.values.any { GuiArt.hasMissing(it, "label") } || videos.any { GuiArt.hasMissing(it.name, "label") }) {
            player.bilibiliMessage(config.message("unknown-glyph"))
        }
        val token = session.token
        if (!BilibiliVideoCommand.request(player, onFailure = { current ->
                matching(current.uniqueId, token)?.let { s -> s.loading = false; current.bilibiliMessage(s.config.message("failed")); render(current, s) }
            }) { uuid, _ ->
                val binding = BoundAccountRepository.findByPlayerUuid(uuid.toString())?.let { "${it.bilibiliName} (UID ${it.bilibiliMid})" }
                TaskScheduler.entity(uuid) { current -> matching(uuid, token)?.let { s ->
                    s.binding = binding; s.loading = false; render(current, s)
                } }
            }) { session.loading = false; render(player, session) }
    }
    private fun matching(uuid: UUID, token: UUID): Session? = sessions[uuid]?.takeIf { it.token == token }
    private fun cue(player: Player, config: GuiSettings, event: String) {
        if (config.soundEnabled) player.playSound(player, config.sounds.getValue(event), SoundCategory.MASTER, config.volume, config.pitch)
    }
    private fun render(player: Player, s: Session) {
        if (!available || sessions[s.id] !== s) return
        val c = s.config
        val selected = s.selected
        var title = GuiArt.base(selected != null)
        fun word(value: String, x: Int, y: Int, width: Int, style: String = "label", color: String = "text", center: Boolean = true) {
            title = title.append(GuiArt.line(value, x, y, width, style, c.colors.getValue(color), center))
        }
        word(c.title, 30, -4, 130, "header", "title", false)
        val v = View(s.id)
        // 先完成标题再创建背包；没有重复bitmap provider或每次开菜单重新加载贴图。
        val buttons = mutableListOf<Triple<String, String, List<String>>>()
        fun button(action: String, name: String, lore: List<String> = emptyList()) { buttons.add(Triple(action, name, lore)) }
        button("close", c.label("close"))
        if (selected == null) {
            word(if (s.loading) c.label("loading") else if (s.binding == null) c.label("unbound") else c.label("bound"), 20, 23, 90, "small", "title", false)
            word(c.label("bind"), 137, 23, 45, "label", "text")
            button("bind", c.label("bind"), listOf(s.binding ?: c.label("unbound")) + c.tips.getValue("bind"))
            val count = maxOf(1, (s.videos.size + 2) / 3)
            s.page = s.page.coerceIn(0, count - 1)
            for (i in 0..2) {
                val video = s.videos.getOrNull(s.page * 3 + i)
                val x = 34 + 46 * i
                word(video?.name ?: c.label("empty"), x, 46, 35)
                if (video != null) {
                    word(c.label("subtitle"), x, 77, 33, "small", "reward")
                    word(c.label("inspect"), x, 87, 32, "small", "success")
                    button("card$i", video.name, listOf(video.bvid) + video.lore + c.label("inspect"))
                }
            }
            word(c.label("previous"), 34, 105, 32)
            word("${s.page + 1} / $count", 80, 105, 36, "label", "title")
            word(c.label("next"), 126, 105, 32)
            button("previous", c.label("previous")); button("next", c.label("next"))
        } else {
            word(selected.name, 23, 23, 104, "label", "title", false)
            word(if (s.binding == null) c.label("unbound") else c.label("bound"), 146, 23, 35, "small", "title")
            val flags = s.status?.let { listOf(it.liked, it.coinCount > 0, it.favoured) }
            for ((i, key) in listOf("like", "coin", "favorite").withIndex()) {
                val x = listOf(29, 80, 131)[i]
                word(c.label(key), x, 67, 38)
                val text = if (s.busy) c.label("checking") else if (flags == null) c.label("unchecked") else c.label(if (flags[i]) "done" else "missing")
                word(text, x, 78, 38, "small", if (flags?.get(i) == true) "success" else "muted")
            }
            for ((i, key) in listOf("watch", "check", "claim").withIndex()) {
                word(c.label(key), 26 + 54 * i, 91, 46)
                val hints = (if (key == "claim") selected.lore else emptyList()) + c.tips.getValue(key)
                button(key, if (s.busy && key == "check") c.label("checking") else c.label(key), listOf(selected.bvid) + hints)
            }
            button("back", c.label("back"))
        }
        v.contents = GuiInventories.create(v, 54, title)
        for ((action, name, lore) in buttons) {
            val slotKey = if (action.startsWith("card")) "cards.${listOf("first", "second", "third")[action.last().digitToInt()]}" else action
            val item = ItemStack(c.material)
            item.editMeta { meta ->
                meta.itemModel = NamespacedKey.fromString(c.model)
                meta.displayName(Component.text(org.bukkit.ChatColor.stripColor(name) ?: name).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore.map { Component.text(org.bukkit.ChatColor.stripColor(it) ?: it).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
            }
            for (slot in c.slots.getValue(slotKey)) { v.contents.setItem(slot, item); v.actions[slot] = action }
        }
        s.view = v // 替换发生在open前，旧InventoryClose不清理新页面。
        if (player.openInventory(v.contents) == null) sessions.remove(s.id, s)
    }

    @EventHandler fun click(e: InventoryClickEvent) {
        val v = e.view.topInventory.holder as? View ?: return
        e.isCancelled = true // 连下方背包也禁移物品，覆盖Shift/数字键/双击/交换副手/创造模式。
        val player = e.whoClicked as? Player ?: return
        if (player.uniqueId != v.owner || e.click != ClickType.LEFT) return
        val s = sessions[v.owner]?.takeIf { it.view === v } ?: return
        if (!player.hasPermission("bilibili.video.use")) return
        val action = v.actions[e.rawSlot] ?: return
        val now = System.nanoTime()
        if (now < s.nextClick) return
        s.nextClick = now + s.config.cooldown * 1_000_000
        val token = s.token
        TaskScheduler.entity(v.owner) { current -> matching(v.owner, token)?.takeIf { it.view === v }?.let { perform(current, it, action) } }
    }
    @EventHandler fun drag(e: InventoryDragEvent) { if (e.view.topInventory.holder is View) e.isCancelled = true }
    @EventHandler fun close(e: InventoryCloseEvent) {
        val v = e.inventory.holder as? View ?: return
        val s = sessions[v.owner] ?: return
        if (s.view === v) sessions.remove(v.owner, s)
    }
    @EventHandler fun quit(e: PlayerQuitEvent) { sessions.remove(e.player.uniqueId) }
    private fun perform(player: Player, s: Session, action: String) {
        val c = s.config
        when {
            action == "close" -> { cue(player, c, "back"); player.closeInventory() }
            action == "bind" -> { cue(player, c, "click"); player.closeInventory(); player.performCommand("bv qrcode") }
            action == "back" -> { cue(player, c, "back"); s.selected = null; s.status = null; render(player, s) }
            action == "previous" || action == "next" -> {
                val next = (s.page + if (action == "next") 1 else -1).coerceIn(0, maxOf(0, (s.videos.size - 1) / 3))
                if (next != s.page) { s.page = next; cue(player, c, "page"); render(player, s) }
            }
            action.startsWith("card") -> {
                s.selected = s.videos.getOrNull(s.page * 3 + action.last().digitToInt()) ?: return
                s.status = null; cue(player, c, "select"); render(player, s)
            }
            action == "watch" -> { val bvid = s.selected?.bvid ?: return; cue(player, c, "click"); player.closeInventory(); player.performCommand("bv videoqr $bvid") }
            action == "claim" -> {
                if (s.loading || s.busy) { player.bilibiliMessage(c.message("busy")); return }
                val bvid = s.selected?.bvid ?: return
                player.closeInventory()
                BilibiliVideoCommand.claim(player, bvid) { current, success -> cue(current, c, if (success) "success" else "failure") }
            }
            action == "check" -> check(player, s)
        }
    }
    private fun check(player: Player, s: Session) {
        if (s.loading || s.busy) { player.bilibiliMessage(s.config.message("busy")); return }
        val bvid = s.selected?.bvid ?: return
        if (!RewardConfigManager.isBvidConfigured(bvid)) { player.bilibiliMessage(s.config.message("failed")); return }
        val token = s.token
        s.busy = true
        val accepted = BilibiliVideoCommand.request(player, onFailure = { current -> matching(current.uniqueId, token)?.let { active ->
            active.busy = false; cue(current, active.config, "failure"); render(current, active)
        } }) { uuid, _ ->
            val result = CredentialService.checkTripleByPlayer(uuid.toString(), bvid)
            TaskScheduler.entity(uuid) { current -> matching(uuid, token)?.let { active ->
                active.busy = false
                if (active.selected?.bvid == bvid) active.status = if (result.success) result.tripleStatus else null
                if (!result.success) { current.bilibiliMessage("§c[BV] ${result.message}"); cue(current, active.config, "failure") }
                render(current, active)
            } }
        }
        if (!accepted) s.busy = false else cue(player, s.config, "click")
        render(player, s)
    }
    fun shutdown() { available = false; sessions.clear(); HandlerList.unregisterAll(this) }
}
