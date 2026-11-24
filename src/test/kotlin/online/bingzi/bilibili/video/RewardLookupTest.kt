package online.bingzi.bilibili.video

import online.bingzi.bilibili.video.internal.repository.RewardRecordRepository
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.*

class RewardLookupTest {
    @Test fun `existence lookup keeps strategy and success semantics with large history`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE bv_reward_record (player_uuid TEXT,bilibili_mid INTEGER,target_key TEXT,reward_key TEXT,status INTEGER)") }
            c.autoCommit=false
            c.prepareStatement("INSERT INTO bv_reward_record VALUES (?,?,?,?,?)").use { s ->
                repeat(10000) { i ->
                    s.setString(1,"player-$i");s.setLong(2,i.toLong());s.setString(3,"video");s.setString(4,"default");s.setInt(5,1);s.addBatch()
                }
                s.executeBatch()
            }
            c.commit()
            fun exists(p: String?,m: Long?,r: String="default")=RewardRecordRepository.hasSuccessful(c,p,m,"video",r)
            assertTrue(exists("player-9999",null))
            assertTrue(exists(null,9999))
            assertTrue(exists("player-9999",9999))
            assertFalse(exists("player-9999",1))
            assertFalse(exists("absent",null));assertFalse(exists(null,10001))
            assertFalse(exists("player-9999",null,"other-template"))
            c.createStatement().use { it.executeUpdate("UPDATE bv_reward_record SET status=2 WHERE player_uuid='player-9999'") }
            assertFalse(exists("player-9999",null))
            assertFailsWith<IllegalArgumentException> { exists(null,null) }
        }
    }
}
