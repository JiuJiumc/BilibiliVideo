package online.bingzi.bilibili.video.internal.config

import org.bukkit.configuration.ConfigurationSection

/** 缺省字段才回退旧gui.yml；空列表表示明确取消旧说明。 */
data class VideoPresentation(val name: String?, val lore: List<String>?) {
    fun withFallback(legacy: VideoPresentation?): VideoPresentation =
        VideoPresentation(name ?: legacy?.name, lore ?: legacy?.lore)

    companion object {
        fun read(section: ConfigurationSection?): VideoPresentation {
            if (section == null) return VideoPresentation(null, null)
            require(!section.contains("name") || section.isString("name")) { "视频name必须是文字" }
            require(!section.contains("lore") || section.isList("lore")) { "视频lore必须是列表" }
            return VideoPresentation(
                section.getString("name")?.take(128),
                if (section.contains("lore")) section.getStringList("lore").take(20).map { it.take(256) } else null
            )
        }
    }
}
