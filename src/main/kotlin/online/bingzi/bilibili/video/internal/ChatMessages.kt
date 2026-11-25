package online.bingzi.bilibili.video.internal

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import online.bingzi.bilibili.video.internal.bukkitPlugin
import java.io.File

/** 提示正文由messages.yml提供；保留正文自定义颜色，兼容旧[BV]前缀。 */
internal object ChatMessages {
    private data class Style(val prefix: String, val body: String)
    @Volatile private var style = Style("§f[§5Bilibili§f] ", "§f")
    private val ampColors = Regex("&([0-9a-fk-orx])", RegexOption.IGNORE_CASE)
    fun codes(value: String) = ampColors.replace(value) { "§${it.groupValues[1]}" }
    fun load() {
        val file = File(bukkitPlugin.dataFolder, "chat-style.yml")
        if (!file.exists()) bukkitPlugin.saveResource("chat-style.yml", false)
        val y = YamlConfiguration().apply { load(file) }
        val prefix = codes(y.getString("prefix", "&f[&5Bilibili&f] ")!!)
        val body = codes(y.getString("body-color", "&f")!!)
        require(prefix.length <= 128 && !prefix.contains('\n') && !prefix.contains('\r')) { "聊天前缀过长或包含换行" }
        require(body.matches(Regex("§[0-9a-f]", RegexOption.IGNORE_CASE))) { "body-color须为单个原版颜色代码" }
        Messages.load()
        style = Style(prefix, body)
    }
    fun format(value: String): String {
        val current = style
        val colored = codes(value)
        val plain = colored.replace(Regex("^(?:§[0-9a-fk-orx])*", RegexOption.IGNORE_CASE), "")
        val oldPrefix = when {
            plain.startsWith("[BV]") -> "[BV]"
            plain.startsWith("[Bilibili]") -> "[Bilibili]"
            else -> null
        }
        return if (oldPrefix != null) current.prefix + current.body + plain.removePrefix(oldPrefix).trimStart()
            else current.body + colored.replace("{prefix}", current.prefix + current.body)
    }
}

internal fun CommandSender.bilibiliMessage(value: String) {
    if (value.isNotEmpty()) ChatMessages.format(value).split('\n').forEach { if (it.isNotEmpty()) sendMessage(it) }
}
