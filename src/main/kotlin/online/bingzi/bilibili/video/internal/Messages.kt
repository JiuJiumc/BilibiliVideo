package online.bingzi.bilibili.video.internal

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** IO侧整份校验后发布；显示侧只读取不可变快照，不做文件IO。 */
internal object Messages {
    private fun defaults() = YamlConfiguration().apply {
        Messages::class.java.getResourceAsStream("/messages.yml")!!.reader(Charsets.UTF_8).use { load(it) }
    }
    private val fallback = parse(defaults())
    @Volatile private var values: Map<String, String> = fallback

    internal fun parse(y: YamlConfiguration): Map<String, String> = y.getKeys(true)
        .filter { !y.isConfigurationSection(it) }.associateWith { key ->
            val value = y.get(key)
            val text = when (value) {
                is String -> value
                is List<*> -> { require(value.all { it is String }) { "$key 必须是文字或文字列表" }; value.joinToString("\n") }
                else -> error("$key 必须是文字或文字列表")
            }
            require(text.length <= 8192 && '\u0000' !in text) { "$key 消息过长或含非法字符" }
            text
        }.toMap()

    fun load() {
        val file = File(bukkitPlugin.dataFolder, "messages.yml")
        if (!file.exists()) bukkitPlugin.saveResource("messages.yml", false)
        val parsed = parse(YamlConfiguration().apply { load(file) })
        values = fallback + parsed
    }
    fun text(key: String, vararg parameters: Pair<String, Any?>): String {
        if (parameters.any { it.first == "message" && it.second == "" }) return ""
        return render(values[key] ?: error("未知消息键：$key"), parameters.toMap())
    }

    private val token = Regex("\\{([A-Za-z][A-Za-z0-9_-]*)}")
    internal fun render(template: String, parameters: Map<String, Any?>): String =
        token.replace(template) { match ->
            if (parameters.containsKey(match.groupValues[1])) parameters[match.groupValues[1]]?.toString().orEmpty()
            else match.value
        }
}
