package online.bingzi.bilibili.video.internal.repository

import online.bingzi.bilibili.video.internal.database.DatabaseFactory
import online.bingzi.bilibili.video.internal.database.SqliteWriteExecutor
import online.bingzi.bilibili.video.internal.entity.RewardRecord
import online.bingzi.bilibili.video.internal.entity.RewardRecords
import online.bingzi.bilibili.video.internal.entity.toRewardRecord
import org.ktorm.dsl.*

/**
 * 奖励记录仓储。
 */
internal object RewardRecordRepository {

    private val db get() = DatabaseFactory.database()

    fun findAllByPlayerAndTarget(playerUuid: String, targetKey: String): List<RewardRecord> {
        return db.from(RewardRecords)
            .select()
            .where { (RewardRecords.playerUuid eq playerUuid) and (RewardRecords.targetKey eq targetKey) }
            .map { it.toRewardRecord() }
    }

    fun findAllByBilibiliMidAndTarget(bilibiliMid: Long, targetKey: String): List<RewardRecord> {
        return db.from(RewardRecords)
            .select()
            .where { (RewardRecords.bilibiliMid eq bilibiliMid) and (RewardRecords.targetKey eq targetKey) }
            .map { it.toRewardRecord() }
    }

    fun findAllByPlayerAndBilibiliMidAndTarget(
        playerUuid: String,
        bilibiliMid: Long,
        targetKey: String
    ): List<RewardRecord> {
        return db.from(RewardRecords)
            .select()
            .where {
                (RewardRecords.playerUuid eq playerUuid) and
                        (RewardRecords.bilibiliMid eq bilibiliMid) and
                        (RewardRecords.targetKey eq targetKey)
            }
            .map { it.toRewardRecord() }
    }

    /** 只返回存在性，不把同目标的历史记录整批加载到堆内存。 */
    fun hasSuccessful(playerUuid: String?, bilibiliMid: Long?, targetKey: String, rewardKey: String): Boolean =
        DatabaseFactory.database().useConnection { connection ->
            hasSuccessful(connection,playerUuid,bilibiliMid,targetKey,rewardKey)
        }

    internal fun hasSuccessful(connection: java.sql.Connection, playerUuid: String?, bilibiliMid: Long?, targetKey: String, rewardKey: String): Boolean {
            val filters=mutableListOf("target_key=?", "reward_key=?", "status=1")
            if (playerUuid != null) filters.add("player_uuid=?")
            if (bilibiliMid != null) filters.add("bilibili_mid=?")
            require(playerUuid != null || bilibiliMid != null)
            val table=online.bingzi.bilibili.video.internal.DATABASE_TABLE_PREFIX + "reward_record"
            return connection.prepareStatement("SELECT 1 FROM $table WHERE ${filters.joinToString(" AND ")} LIMIT 1").use { statement ->
                var i=1
                statement.setString(i++,targetKey); statement.setString(i++,rewardKey)
                if(playerUuid != null) statement.setString(i++,playerUuid)
                if(bilibiliMid != null) statement.setLong(i,bilibiliMid)
                statement.executeQuery().use { it.next() }
            }
        }

    fun insert(
        playerUuid: String,
        playerName: String,
        bilibiliMid: Long?,
        targetKey: String,
        rewardKey: String,
        status: Int,
        issuedAt: Long = System.currentTimeMillis(),
        context: String? = null,
        failReason: String? = null
    ): Int = SqliteWriteExecutor.executeWrite {
        db.insert(RewardRecords) {
            set(it.playerUuid, playerUuid)
            set(it.playerName, playerName)
            set(it.bilibiliMid, bilibiliMid)
            set(it.targetKey, targetKey)
            set(it.rewardKey, rewardKey)
            set(it.status, status)
            set(it.issuedAt, issuedAt)
            set(it.context, context)
            set(it.failReason, failReason)
        }
    }
}
