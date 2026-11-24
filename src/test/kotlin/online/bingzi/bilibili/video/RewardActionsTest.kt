package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.service.RewardActions
import org.junit.jupiter.api.Test
import kotlin.test.*

class RewardActionsTest {
    @Test fun `confirmed gifts parse with exact counts`() {
        val actions=RewardActions.parse(listOf("item minecraft:diamond 1","item minecraft:cooked_beef 16","item minecraft:experience_bottle 16","message thanks"))
        assertEquals(listOf(1,16,16),actions.filterIsInstance<RewardActions.Item>().map { it.amount })
        assertEquals(4,actions.size)
    }
    @Test fun `old give and tell migrate without script engine`() {
        val lines=listOf("""command papi "minecraft:give %player_name% minecraft:diamond 1" by console""", """tell "thanks"""")
        val a=RewardActions.parse(lines)
        assertEquals(RewardActions.Item("minecraft:diamond",1),a[0])
        assertEquals(RewardActions.Message("thanks"),a[1])
    }
    @Test fun `complex scripts and invalid amounts rejected before execution`() {
        for(line in listOf("item diamond 0","item diamond -1","item diamond 2305","console /give p diamond 1","message a\nb")) {
            assertFailsWith<IllegalArgumentException> { RewardActions.parse(listOf(line)) }
        }
        assertFails { RewardActions.parse(listOf("if true then { tell hi }")) }
    }
    @Test fun `command sender semantics preserved`() {
        assertIs<RewardActions.PlayerCommand>(RewardActions.parse(listOf("""command "help""""))[0])
        assertIs<RewardActions.Console>(RewardActions.parse(listOf("""command "say hi" by console"""))[0])
    }
}
