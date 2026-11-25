package online.bingzi.bilibili.video.internal.service

import online.bingzi.bilibili.video.internal.Messages

import online.bingzi.bilibili.video.internal.config.DedupStrategy
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.config.RewardTemplate
import online.bingzi.bilibili.video.internal.repository.BoundAccountRepository
import online.bingzi.bilibili.video.internal.repository.RewardRecordRepository
import online.bingzi.bilibili.video.internal.database.ClaimStore

/**
 * 奖励发放服务层。
 *
 * 当前实现：
 * - 基于「玩家 + bvid」检测是否完成三连
 * - 若完成且未领取过，则记录一条奖励记录
 * - 根据 rewardKey 从配置中载入 原生奖励模板，供命令层执行
 */
object RewardService {

    data class RewardResult(
        val success: Boolean,
        val message: String,
        val template: RewardTemplate? = null,
        val bvid: String? = null,
        val targetKey: String? = null,
        val claimKey: String? = null,
        val claimMid: Long? = null
    )

    /**
     * 使用玩家绑定的 B 站账号，基于指定 bvid 发放奖励。
     *
     * @param player     Minecraft 玩家
     * @param bvid       视频 BVID
     * @param targetKey  奖励目标 key（默认使用 bvid）
     * @param rewardKey  奖励模板 key（默认 "default"）
     */
    fun rewardByPlayerAndBvid(
        playerUuid: String,
        playerName: String,
        bvid: String,
        targetKey: String = bvid,
        rewardKey: String = "default"
    ): RewardResult {
        if (!RewardConfigManager.isBvidConfigured(bvid)) {
            return RewardResult(
                success = false,
                message = Messages.text("reward.unregistered")
            )
        }

        // 获取玩家绑定的 B 站账号信息

        val boundAccount = BoundAccountRepository.findByPlayerUuid(playerUuid)
        val bilibiliMid = boundAccount?.bilibiliMid ?: return RewardResult(false, Messages.text("reward.unbound"))

        // 解析奖励模板
        val resolve = RewardConfigManager.resolveForBvid(bvid, defaultKey = rewardKey)
        val resolvedRewardKey = resolve.rewardKey

        // 根据配置的判重策略检查是否已领取
        val dedupStrategy = RewardConfigManager.getDedupStrategy()
        val alreadyClaimed = checkAlreadyClaimed(
            strategy = dedupStrategy,
            playerUuid = playerUuid,
            bilibiliMid = bilibiliMid,
            targetKey = targetKey,
            rewardKey = resolvedRewardKey
        )

        if (alreadyClaimed) {
            val strategyHint = when (dedupStrategy) {
                DedupStrategy.PLAYER_AND_BILIBILI -> Messages.text("reward.hint-pair")
                DedupStrategy.BILIBILI_ONLY -> Messages.text("reward.hint-bilibili")
                DedupStrategy.PLAYER_ONLY -> Messages.text("reward.hint-player")
            }
            return RewardResult(
                success = false,
                message = Messages.text("reward.already-claimed", "strategyHint" to (strategyHint))
            )
        }

        val template = resolve.template ?: return RewardResult(false, Messages.text("reward.template-missing"))
        val identity = when (dedupStrategy) {
            DedupStrategy.PLAYER_ONLY -> playerUuid
            DedupStrategy.BILIBILI_ONLY -> bilibiliMid?.toString() ?: return RewardResult(false, Messages.text("reward.binding-missing"))
            DedupStrategy.PLAYER_AND_BILIBILI -> "$playerUuid:${bilibiliMid ?: return RewardResult(false, Messages.text("reward.binding-missing")) }"
        }
        val claimKey = ClaimStore.key(dedupStrategy.name, identity, targetKey, resolvedRewardKey)
        if (ClaimStore.exists(claimKey)) return RewardResult(false, Messages.text("reward.busy"))
        // 本地已领取及处理中直接返回，仅新请求访问B站。
        // 检查三连状态
        val tripleResult = CredentialService.checkTripleByMid(bilibiliMid, bvid)
        if (!tripleResult.success || tripleResult.tripleStatus == null) {
            return RewardResult(
                success = false,
                message = tripleResult.message
            )
        }

        val tripleStatus = tripleResult.tripleStatus
        if (!tripleStatus.isTriple) {
            return RewardResult(
                success = false,
                message = Messages.text("reward.incomplete")
            )
        }

        if (!ClaimStore.reserve(claimKey, playerUuid)) return RewardResult(false, Messages.text("reward.busy"))
        return RewardResult(true, Messages.text("reward.success"), template, bvid, targetKey, claimKey, bilibiliMid)
    }

    fun complete(result: RewardResult, playerUuid: String, playerName: String, success: Boolean) {
        val key = result.claimKey ?: return
        if (success) {
            // 执行脚本期间可能重新扫码；奖励记录必须使用占位时的账号，不能在完成时换成新绑定。
            RewardRecordRepository.insert(playerUuid, playerName, result.claimMid, result.targetKey!!,
                result.template!!.key, 1, context = "bvid=${result.bvid}; claim=$key")
        }
        ClaimStore.finish(key, success)
    }

    /**
     * 根据判重策略检查是否已领取奖励。
     */
    private fun checkAlreadyClaimed(
        strategy: DedupStrategy,
        playerUuid: String,
        bilibiliMid: Long?,
        targetKey: String,
        rewardKey: String
    ): Boolean {
        return when(strategy) {
            DedupStrategy.PLAYER_ONLY -> RewardRecordRepository.hasSuccessful(playerUuid,null,targetKey,rewardKey)
            DedupStrategy.BILIBILI_ONLY -> bilibiliMid != null && RewardRecordRepository.hasSuccessful(null,bilibiliMid,targetKey,rewardKey)
            DedupStrategy.PLAYER_AND_BILIBILI -> RewardRecordRepository.hasSuccessful(playerUuid,bilibiliMid,targetKey,rewardKey)
        }
    }
}
