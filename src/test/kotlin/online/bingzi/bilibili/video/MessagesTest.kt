package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.Messages
import online.bingzi.bilibili.video.internal.ChatMessages
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.*

class MessagesTest {
    @Test fun `all packaged defaults load and custom text keeps colors and placeholders`() {
        assertTrue(Messages.text("command.qr-generated").contains("二维码"))
        assertEquals("&d你好 玩家 {other}",Messages.render("&d你好 {player} {message}",mapOf("player" to "玩家", "message" to "{other}", "other" to "不可递归替换")))
        assertEquals("§f[§5Bilibili§f] §f§d自定义提示",ChatMessages.format("[BV] &d自定义提示"))
    }

    @Test fun `edited config snapshots accept multiline silence and reject invalid types`() {
        fun parse(yaml: String) = Messages.parse(YamlConfiguration().apply { loadFromString(yaml) })
        val old=parse("command:\n  qr-generated: '&d旧提示'")
        val next=parse("command:\n  qr-generated: ['&a新第一行', '&f新第二行']\n  busy: []")
        assertEquals("&a新第一行\n&f新第二行",next.getValue("command.qr-generated"))
        assertEquals("",next.getValue("command.busy"))
        assertEquals("&d旧提示",old.getValue("command.qr-generated"))
        assertFailsWith<IllegalStateException> { parse("command:\n  qr-generated: 123") }
        assertFailsWith<IllegalArgumentException> { parse("command:\n  qr-generated: [文字, 123]") }
        assertEquals("",Messages.render("",mapOf("player" to "玩家")))
    }
}
