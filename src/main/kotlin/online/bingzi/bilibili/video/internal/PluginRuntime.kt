package online.bingzi.bilibili.video.internal

import online.bingzi.bilibili.video.JiuJiublbl
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Level

internal val bukkitPlugin: JiuJiublbl get() = JiuJiublbl.instance
internal fun getDataFolder(): File = bukkitPlugin.dataFolder
internal fun info(message: String) = bukkitPlugin.logger.info(message)
internal fun warning(message: String, error: Throwable? = null) {
    if (error == null) bukkitPlugin.logger.warning(message)
    else bukkitPlugin.logger.log(Level.WARNING, message, error)
}
internal fun loadYaml(name: String): YamlConfiguration {
    val file = File(bukkitPlugin.dataFolder, name)
    if (!file.exists()) bukkitPlugin.saveResource(name, false)
    return YamlConfiguration().apply { load(file) }
}
