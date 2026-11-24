package online.bingzi.bilibili.video.internal.ui

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import online.bingzi.bilibili.video.internal.bukkitPlugin
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.config.VideoPresentation
import java.io.File

/** 在IO侧解析后一次性发布；已打开的会话沿用自己的不可变设置。 */
internal data class GuiSettings(
    val enabled: Boolean, val requirePack: Boolean, val maxSessions: Int, val cooldown: Long,
    val title: String, val material: Material, val model: String,
    val labels: Map<String, String>, val colors: Map<String, String>, val messages: Map<String, String>,
    val slots: Map<String, List<Int>>, val videos: Map<String, VideoText>, val tips: Map<String, List<String>>,
    val sounds: Map<String, String>, val soundEnabled: Boolean, val volume: Float, val pitch: Float
) {
    data class VideoText(val name: String?, val lore: List<String>)
    fun label(key: String) = labels.getValue(key)
    fun message(key: String) = messages.getValue(key)
    companion object {
        private val labelKeys = "unbound bound loading bind video subtitle inspect previous next like coin favorite done missing unchecked checking watch check claim claimed review empty back close".split(' ')
        fun load(): GuiSettings {
            val file = File(bukkitPlugin.dataFolder, "gui.yml")
            if (!file.exists()) bukkitPlugin.saveResource("gui.yml", false)
            val defaults = YamlConfiguration().apply { bukkitPlugin.getResource("gui.yml")!!.reader(Charsets.UTF_8).use { load(it) } }
            val y = YamlConfiguration().apply { load(file); setDefaults(defaults) }
            fun range(key: String, low: Int, high: Int): Int = y.getInt(key).also { require(it in low..high) { "$key 必须为$low～$high" } }
            fun strings(base: String, keys: List<String>) = keys.associateWith { key ->
                requireNotNull(y.getString("$base.$key")).also { require(it.length <= 256) { "$base.$key 过长" } }
            }
            val labels = strings("labels", labelKeys)
            val colors = strings("colors", listOf("title", "text", "muted", "success", "reward"))
            colors.values.forEach(GuiArt::validateColor)
            val slotKeys = listOf("close", "bind", "cards.first", "cards.second", "cards.third", "previous", "next", "watch", "check", "claim", "back")
            val slots = slotKeys.associateWith { k -> y.getIntegerList("slots.$k").toList().also { list ->
                require(list.isNotEmpty() && list.size <= 54 && list.all { it in 0..53 } && list.distinct().size == list.size) { "槽位无效: $k" }
            } }
            for (page in listOf(listOf("close", "bind", "cards.first", "cards.second", "cards.third", "previous", "next"), listOf("close", "watch", "check", "claim", "back"))) {
                val flat = page.flatMap { slots.getValue(it) }
                require(flat.size == flat.distinct().size) { "同页按钮槽位不能重叠" }
            }
            val model = y.getString("button-item-model")!!
            require(model.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { "物品模型ID无效" }
            val material = Material.matchMaterial(y.getString("button-material")!!) ?: error("按钮材质无效")
            require(material.isItem && !material.isAir)
            val sounds = strings("sounds", listOf("click", "select", "page", "back", "success", "failure"))
            sounds.values.forEach { require(it.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { "声音ID无效" } }
            val volume = y.getDouble("sounds.volume"); val pitch = y.getDouble("sounds.pitch")
            require(volume.isFinite() && volume in 0.0..1.0 && pitch.isFinite() && pitch in 0.5..2.0)
            val videos = RewardConfigManager.getVideoPresentations().mapValues { (bvid, primary) ->
                val merged = primary.withFallback(VideoPresentation.read(y.getConfigurationSection("videos.$bvid")))
                VideoText(merged.name, merged.lore.orEmpty())
            }
            val tips = listOf("bind", "watch", "check", "claim").associateWith { key -> y.getStringList("tips.$key").take(20).map { it.take(256) } }
            return GuiSettings(y.getBoolean("enabled"), y.getBoolean("require-resource-pack"), range("max-sessions", 1, 512),
                range("click-cooldown-ms", 100, 5000).toLong(), y.getString("title")!!.take(64), material, model,
                labels, colors, strings("messages", listOf("resource-pack", "dependency", "disabled", "busy", "failed", "unbound", "unknown-glyph")), slots, videos, tips,
                sounds, y.getBoolean("sounds.enabled"), volume.toFloat(), pitch.toFloat())
        }
    }
}
