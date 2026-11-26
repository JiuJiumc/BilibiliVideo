package online.bingzi.bilibili.video.internal

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.platform.util.bukkitPlugin
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 有界IO执行器；所有玩家回调回到其Entity调度器，禁止跨区等待。 */
object TaskScheduler {
    private var folia = false
    private val running = AtomicBoolean(false)
    private val sequence = AtomicInteger()
    private val io by lazy { ThreadPoolExecutor(SafetySettings.ioThreads, SafetySettings.ioThreads, 0, TimeUnit.SECONDS, ArrayBlockingQueue(SafetySettings.ioQueue),
        { r -> Thread(r, "BilibiliVideo-IO-${sequence.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()) }

    fun initialize() {
        folia = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) { false }
        running.set(true)
    }

    fun async(task: () -> Unit): Boolean {
        if (!running.get()) return false
        return try {
            io.execute { if (running.get()) task() }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }

    fun entity(uuid: UUID, retired: () -> Unit = {}, task: (Player) -> Unit) {
        if (!running.get()) { retired(); return }
        val player = Bukkit.getPlayer(uuid) ?: run { retired(); return }
        val callback = { if (running.get() && player.isOnline) task(player) else retired() }
        if (folia) {
            if (player.scheduler.runDelayed(bukkitPlugin, { callback() }, Runnable { retired() }, 1L) == null) retired()
        } else {
            Bukkit.getScheduler().runTaskLater(bukkitPlugin, Runnable { callback() }, 1L)
        }
    }

    fun shutdown() {
        running.set(false)
        io.shutdownNow()
        try { io.awaitTermination(3, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(bukkitPlugin)
            Bukkit.getAsyncScheduler().cancelTasks(bukkitPlugin)
        } else Bukkit.getScheduler().cancelTasks(bukkitPlugin)
    }
}
