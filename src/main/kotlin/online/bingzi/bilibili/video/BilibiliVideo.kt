package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.credential.QrLoginService
import online.bingzi.bilibili.video.internal.database.DatabaseFactory
import taboolib.common.platform.Platform
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.module.metrics.Metrics
import taboolib.platform.util.bukkitPlugin

object BilibiliVideo : Plugin() {
    override fun onLoad() {
        info("正在加载 BilibiliVideo 插件...")
        info("BilibiliVideo 插件加载完成！")
    }

    override fun onEnable() {
        info("正在启动 BilibiliVideo 插件...")
        online.bingzi.bilibili.video.internal.ChatMessages.load()
        online.bingzi.bilibili.video.internal.SafetySettings.load()
        online.bingzi.bilibili.video.internal.TaskScheduler.initialize()
        // 启动时核对26.2二维码数据包签名，避免等玩家扫码才暴露不兼容。
        online.bingzi.bilibili.video.internal.nms.NMSPacketHandler.instance
        DatabaseFactory.initFromConfig()
        online.bingzi.bilibili.video.internal.config.RewardConfigManager.loadSnapshot()
        online.bingzi.bilibili.video.internal.ui.VirtualItemSession.initialize()
        online.bingzi.bilibili.video.internal.ui.BilibiliGui.initialize()
        info("BilibiliVideo 插件启动完成！")
    }

    override fun onActive() {
        info("正在激活 BilibiliVideo 插件...")
        // 初始化Metrics以收集插件的使用统计信息
        Metrics(20132, bukkitPlugin.description.version, Platform.BUKKIT)
        info("BilibiliVideo 插件激活完成！")
    }

    override fun onDisable() {
        info("正在禁用 BilibiliVideo 插件...")
        try {
            QrLoginService.shutdown()
        } catch (e: Throwable) {
            warning("[BilibiliVideo] QrLoginService 关闭异常: ${e.message}")
        }
        online.bingzi.bilibili.video.internal.ui.BilibiliGui.shutdown()
        online.bingzi.bilibili.video.internal.ui.VirtualItemSession.shutdown()
        online.bingzi.bilibili.video.internal.http.BilibiliHttpClient.shutdown()
        online.bingzi.bilibili.video.internal.TaskScheduler.shutdown()
        try {
            DatabaseFactory.shutdown()
        } catch (e: Throwable) {
            warning("[BilibiliVideo] DatabaseFactory 关闭异常: ${e.message}")
        }
        info("BilibiliVideo 插件禁用完成！")
    }
}
