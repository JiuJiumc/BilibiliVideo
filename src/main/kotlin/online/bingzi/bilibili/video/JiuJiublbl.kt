package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.*
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.database.DatabaseFactory
import online.bingzi.bilibili.video.internal.command.BilibiliVideoCommand
import online.bingzi.bilibili.video.internal.credential.QrLoginService
import online.bingzi.bilibili.video.internal.listener.PlayerListener
import online.bingzi.bilibili.video.internal.ui.*
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.HandlerList
import java.io.File

class JiuJiublbl : JavaPlugin() {
    companion object { lateinit var instance: JiuJiublbl; private set }
    override fun onEnable() {
        instance=this
        try {
            check(server.pluginManager.getPlugin("BilibiliVideo") == null) { "请先删除旧BilibiliVideo JAR，不能与JiuJiublbl同时加载。" }
            // Old identity remains a read-only marker. Migration is manual while stopped.
            check(!File(dataFolder.parentFile,"BilibiliVideo").exists()) {
                "检测到旧plugins/BilibiliVideo目录：停服后将整个目录重命名为JiuJiublbl再启动，禁止空库重领奖。若新目录已生成先核对合并，不覆盖数据库。"
            }
            RewardConfigManager.initialize()
            ChatMessages.load(); SafetySettings.load(); TaskScheduler.initialize()
            online.bingzi.bilibili.video.internal.nms.NMSPacketHandler.instance
            DatabaseFactory.initFromConfig()
            server.pluginManager.registerEvents(PlayerListener,this)
            VirtualItemSession.initialize(); BilibiliGui.initialize()
            getCommand("jjblbl")!!.apply { setExecutor(BilibiliVideoCommand); tabCompleter=BilibiliVideoCommand }
            logger.info("JiuJiublbl启动完成：原生Folia，未加载TabooLib，/bv命令已注册。")
        } catch(t: Throwable) {
            logger.severe("启动失败：${t.message}")
            server.pluginManager.disablePlugin(this)
        }
    }
    override fun onDisable() {
        listOf<() -> Unit>(
            { online.bingzi.bilibili.video.internal.service.RewardExecutor.shutdown() }, { QrLoginService.shutdown() }, { BilibiliGui.shutdown() }, { VirtualItemSession.shutdown() },
            { online.bingzi.bilibili.video.internal.http.BilibiliHttpClient.shutdown() },
            { TaskScheduler.shutdown() }, { DatabaseFactory.shutdown() }
        ).forEach { runCatching(it).onFailure { t -> logger.warning("关闭资源异常：${t.javaClass.simpleName}") } }
        HandlerList.unregisterAll(this)
    }
}
