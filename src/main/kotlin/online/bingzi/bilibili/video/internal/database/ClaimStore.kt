package online.bingzi.bilibili.video.internal.database

import online.bingzi.bilibili.video.internal.DATABASE_TABLE_PREFIX
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException

/** 数据库唯一键负责多线程/多进程占位；失败保留占位，绝不自动重发不确定奖励。 */
internal object ClaimStore {
    private val table get() = DATABASE_TABLE_PREFIX + "reward_claim"
    fun key(strategy: String, identity: String, target: String, reward: String): String {
        val canonical = listOf(strategy, identity, target, reward).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun create(connection: Connection, tableName: String) {
        connection.createStatement().use { it.execute("""
            CREATE TABLE IF NOT EXISTS $tableName (
                claim_key VARCHAR(64) PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                state INTEGER NOT NULL,
                updated_at BIGINT NOT NULL
            )
        """.trimIndent()) }
    }
    fun reserve(connection: Connection, tableName: String, key: String, uuid: String): Boolean {
        return try {
            connection.prepareStatement("INSERT INTO $tableName (claim_key,player_uuid,state,updated_at) VALUES (?,?,0,?)").use {
                it.setString(1, key); it.setString(2, uuid); it.setLong(3, System.currentTimeMillis())
                it.executeUpdate() == 1
            }
        } catch (e: SQLException) {
            // 不把连接错误/语法错误当作已领取；仅主键冲突返回false。
            if (e.sqlState == "23505" || e.errorCode == 1062 ||
                (e.errorCode == 19 && e.message.orEmpty().contains("UNIQUE constraint failed"))) false
            else throw e
        }
    }
    fun reserve(key: String, uuid: String): Boolean = DatabaseFactory.database().useConnection {
        reserve(it, table, key, uuid)
    }
    fun finish(key: String, success: Boolean) {
        DatabaseFactory.database().useConnection { connection ->
            connection.prepareStatement("UPDATE $table SET state=?, updated_at=? WHERE claim_key=? AND state=0").use {
                it.setInt(1, if (success) 1 else 2); it.setLong(2, System.currentTimeMillis()); it.setString(3, key)
                check(it.executeUpdate() == 1) { "领奖占位状态异常，请管理员核账" }
            }
        }
    }
}
