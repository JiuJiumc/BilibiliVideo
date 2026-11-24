package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.config.VideoPresentation
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.*

class VideoPresentationTest {
    private fun read(yaml: String) = VideoPresentation.read(YamlConfiguration().apply { loadFromString(yaml) })

    @Test fun `main config wins over old gui and missing fields retain old values`() {
        val old = read("name: 旧名称\nlore: [旧说明]")
        assertEquals(VideoPresentation("新名称", listOf("新说明")), read("name: 新名称\nlore: [新说明]").withFallback(old))
        assertEquals(VideoPresentation("新名称", listOf("旧说明")), read("name: 新名称").withFallback(old))
        assertEquals(old, VideoPresentation.read(null).withFallback(old))
        assertEquals(VideoPresentation(null, null), VideoPresentation.read(null).withFallback(null))
    }

    @Test fun `explicit empty lore does not resurrect legacy instructions`() {
        assertEquals(emptyList(), read("lore: []").withFallback(read("lore: [过期奖励说明]")).lore)
        assertFailsWith<IllegalArgumentException> { read("lore: 不是列表") }
        assertFailsWith<IllegalArgumentException> { read("name: [不是文字]") }
    }

    @Test fun `new video and presentation reload need only main config`() {
        val main = YamlConfiguration().apply { loadFromString("""
            reward:
              videos:
                BV1oHyjzMEXM:
                  rewardKey: default
                  name: 宣传视频一
                  lore: [第一条说明]
                BV1abcdefghj:
                  rewardKey: default
                  name: 宣传视频二
                  lore: [第二条说明]
        """.trimIndent()) }
        val section=main.getConfigurationSection("reward.videos")!!
        val before=section.getKeys(false).associateWith { VideoPresentation.read(section.getConfigurationSection(it)) }
        assertEquals(2,before.size)
        assertEquals("宣传视频二",before.getValue("BV1abcdefghj").name)
        main.set("reward.videos.BV1abcdefghj.name","更新名称")
        assertEquals("更新名称",VideoPresentation.read(section.getConfigurationSection("BV1abcdefghj")).name)
        assertEquals("宣传视频二",before.getValue("BV1abcdefghj").name)
        main.set("reward.videos.BV1abcdefghj",null)
        assertEquals(setOf("BV1oHyjzMEXM"),section.getKeys(false))
    }
}
