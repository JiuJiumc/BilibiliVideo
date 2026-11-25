package online.bingzi.bilibili.video.internal.service

import online.bingzi.bilibili.video.internal.*
import online.bingzi.bilibili.video.internal.config.RewardTemplate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object RewardExecutor {
    private val accepting = AtomicBoolean(true)
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private fun material(id: String): Material = requireNotNull(Material.matchMaterial(id)) { Messages.text("reward-executor.invalid-item", "id" to (id)) }.also { require(it.isItem && !it.isAir) }
    internal fun validate(lines: List<String>) {
        RewardActions.parse(lines).filterIsInstance<RewardActions.Item>().forEach { material(it.material) }
    }
    fun checkCapacity(player: Player, template: RewardTemplate) {
        val items = RewardActions.parse(template.lines).filterIsInstance<RewardActions.Item>()
        val storage = player.inventory.storageContents.map { it?.clone() }.toMutableList()
        for (a in items) {
            val m = material(a.material)
            val sample = ItemStack(m)
            var remaining = a.amount
            for (old in storage) if (old != null && old.isSimilar(sample)) {
                val moved = minOf(remaining, (minOf(old.maxStackSize, player.inventory.maxStackSize) - old.amount).coerceAtLeast(0))
                old.amount += moved
                remaining -= moved
            }
            for (i in storage.indices) if (remaining > 0 && (storage[i] == null || storage[i]!!.type.isAir)) {
                val moved = minOf(remaining, minOf(m.maxStackSize, player.inventory.maxStackSize))
                storage[i] = ItemStack(m, moved)
                remaining -= moved
            }
            require(remaining == 0) { Messages.text("reward-executor.inventory-full") }
        }
    }
    fun execute(player: Player, template: RewardTemplate, bvid: String, targetKey: String): CompletableFuture<Unit> {
        check(accepting.get()) { Messages.text("reward-executor.closing") }
        checkCapacity(player, template)
        val actions = RewardActions.parse(template.lines)
        val uuid = player.uniqueId
        val name = player.name
        val result = CompletableFuture<Unit>()
        synchronized(pending) {
            check(accepting.get()) { Messages.text("reward-executor.closing") }
            check(pending.size < 512) { Messages.text("reward-executor.queue-full") }
            pending.add(result)
        }
        result.whenComplete { _, _ -> pending.remove(result) }
        // 外部调用也必须限时，不能只依靠命令调用方处理。
        result.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        fun resolve(value: String) = value.replace("%player_name%", name).replace("{player}", name)
            .replace("{bvid}", bvid).replace("{target}", targetKey).replace("{rewardKey}", template.key)
        fun step(index: Int) {
            if (result.isDone || !accepting.get()) return
            if (index >= actions.size) { result.complete(Unit); return }
            val action = actions[index]
            if (action is RewardActions.Console) {
                TaskScheduler.global {
                    if (!result.isDone && accepting.get()) try {
                        check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolve(action.command))) { Messages.text("reward-executor.console-rejected") }
                        step(index + 1)
                    } catch (t: Throwable) { result.completeExceptionally(t) }
                }
            } else TaskScheduler.entity(uuid, retired = { result.completeExceptionally(IllegalStateException(Messages.text("reward-executor.offline"))) }) { current ->
                if (!result.isDone && accepting.get()) try {
                    var next=index+1
                    when (action) {
                        is RewardActions.Item -> {
                            // 每条动作按配置顺序执行；一次加物品前检查剩余整份物品的容量。
                            val rest = template.copy(lines = template.lines.drop(index))
                            checkCapacity(current, rest)
                            // 连续物品动作在同一个Entity回调内批量发完，中间不跨tick。
                            var i=index
                            while(i<actions.size && actions[i] is RewardActions.Item) {
                                val item=actions[i] as RewardActions.Item
                                val m=material(item.material)
                                var remaining=item.amount
                                while(remaining>0) {
                                    val n=minOf(remaining,m.maxStackSize)
                                    check(current.inventory.addItem(ItemStack(m,n)).isEmpty()) { Messages.text("reward-executor.inventory-changed") }
                                    remaining-=n
                                }
                                i++
                            }
                            next=i
                        }
                        is RewardActions.Message -> {
                            val text=org.bukkit.ChatColor.translateAlternateColorCodes('&', resolve(action.text))
                            val plain=org.bukkit.ChatColor.stripColor(text).orEmpty()
                            current.bilibiliMessage(if(plain.startsWith("[BV]") || plain.startsWith("[Bilibili]")) text else Messages.text("reward-executor.message-wrapper", "text" to (text)))
                        }
                        is RewardActions.PlayerCommand -> check(current.performCommand(resolve(action.command))) { Messages.text("reward-executor.player-command-rejected") }
                        else -> error(Messages.text("reward-executor.context-error"))
                    }
                    step(next)
                } catch (t: Throwable) { result.completeExceptionally(t) }
            }
        }
        step(0)
        return result
    }
    fun shutdown() {
        val remaining = synchronized(pending) { accepting.set(false); pending.toList() }
        remaining.forEach { it.completeExceptionally(IllegalStateException(Messages.text("reward-executor.closed"))) }
        pending.clear()
    }
}
