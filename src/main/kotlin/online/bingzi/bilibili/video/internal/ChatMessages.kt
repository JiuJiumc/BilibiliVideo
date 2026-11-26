package online.bingzi.bilibili.video.internal

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.ProxyCommandSender
import taboolib.platform.util.bukkitPlugin
import java.io.File

/** 原有消息内容保持不变；统一处理旧[BV]前缀和正文颜色，不触及奖励脚本。 */
internal object ChatMessages {
    private data class Style(val prefix: String, val body: String)
    @Volatile private var style = Style("§f[§5Bilibili§f] ", "§f")
    private val legacyColors = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
    private val ampColors = Regex("&([0-9a-fk-orx])", RegexOption.IGNORE_CASE)
    private fun codes(value: String) = ampColors.replace(value) { "§${it.groupValues[1]}" }
    fun load() {
        val file = File(bukkitPlugin.dataFolder, "chat-style.yml")
        if (!file.exists()) bukkitPlugin.saveResource("chat-style.yml", false)
        val y = YamlConfiguration().apply { load(file) }
        val prefix = codes(y.getString("prefix", "&f[&5Bilibili&f] ")!!)
        val body = codes(y.getString("body-color", "&f")!!)
        require(prefix.length <= 128 && !prefix.contains('\n') && !prefix.contains('\r')) { "聊天前缀过长或包含换行" }
        require(body.matches(Regex("§[0-9a-f]", RegexOption.IGNORE_CASE))) { "body-color须为单个原版颜色代码" }
        style = Style(prefix, body)
    }
    fun format(value: String): String {
        val current = style
        val plain = legacyColors.replace(value, "")
        val oldPrefix = when {
            plain.startsWith("[BV]") -> "[BV]"
            plain.startsWith("[Bilibili]") -> "[Bilibili]"
            else -> null
        }
        return if (oldPrefix != null) current.prefix + current.body + plain.removePrefix(oldPrefix).trimStart()
            else current.body + plain
    }
}

internal fun CommandSender.bilibiliMessage(value: String) { sendMessage(ChatMessages.format(value)) }
internal fun ProxyCommandSender.bilibiliMessage(value: String) { sendMessage(ChatMessages.format(value)) }
