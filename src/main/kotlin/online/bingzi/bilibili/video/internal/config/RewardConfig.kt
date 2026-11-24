package online.bingzi.bilibili.video.internal.config

import online.bingzi.bilibili.video.internal.warning
import org.bukkit.configuration.file.YamlConfiguration
import online.bingzi.bilibili.video.internal.loadYaml

/**
 * 奖励判重策略枚举。
 */
enum class DedupStrategy {
    /**
     * 玩家和 B 站账户同时记录（最严格）。
     * 同一玩家 + 同一 B 站号只能领一次。
     */
    PLAYER_AND_BILIBILI,

    /**
     * 仅记录 B 站账户。
     * 同一 B 站号只能领一次，玩家换号可重新领。
     */
    BILIBILI_ONLY,

    /**
     * 仅记录玩家。
     * 同一玩家只能领一次，换号无法重新领。
     */
    PLAYER_ONLY
}

/**
 * 奖励模板配置。
 *
 * 从 config.yml 中读取 reward.templates 节点，并转换为 RewardTemplate。
 */
data class RewardTemplate(
    val key: String,
    val description: String?,
    val lines: List<String>
)

object RewardConfigManager {

    lateinit var file: YamlConfiguration
        private set

    private fun readTemplate(key: String): RewardTemplate? {
        val basePath = "reward.templates.$key"
        val section = file.getConfigurationSection(basePath) ?: return null
        val description = section.getString("description")
        val scripts = if (section.contains("actions")) section.getStringList("actions") else section.getStringList("kether")
        online.bingzi.bilibili.video.internal.service.RewardExecutor.validate(scripts)
        if (scripts.isEmpty()) {
            return null
        }
        return RewardTemplate(
            key = key,
            description = description,
            lines = scripts
        )
    }

    /**
     * 根据 bvid 解析应该使用的奖励模板：
     *
     * - 如果 reward.videos.<bvid>.rewardKey 存在且非空，则使用该 key；
     * - 如果 reward.videos.<bvid> 结点存在但未配置 rewardKey，则回退到 defaultKey；
     * - 如果 reward.videos.<bvid> 不存在，则同样回退到 defaultKey。
     *
     * 即：对任意 bvid 至少尝试使用 defaultKey，对应模板不存在时返回的 template 为 null。
     */
    data class ResolveResult(
        val rewardKey: String,
        val template: RewardTemplate?
    )

    private fun readResolveForBvid(bvid: String, defaultKey: String = "default"): ResolveResult {
        val videoPath = "reward.videos.$bvid"
        val videoSection = file.getConfigurationSection(videoPath)
        val configuredKey = videoSection
            ?.getString("rewardKey")
            ?.takeIf { it.isNotBlank() }

        val keyToUse = configuredKey ?: defaultKey
        val template = readTemplate(keyToUse)
        return ResolveResult(
            rewardKey = keyToUse,
            template = template
        )
    }

    private data class Snapshot(val videos: Map<String, ResolveResult>, val strategy: DedupStrategy,
                                val presentations: Map<String, VideoPresentation>)
    @Volatile private var snapshot = Snapshot(emptyMap(), DedupStrategy.PLAYER_ONLY, emptyMap())

    fun initialize() { file = loadYaml("config.yml"); loadSnapshot() }

    @Synchronized fun loadSnapshot() {
        val strategy = try {
            DedupStrategy.valueOf((file.getString("reward.dedup-strategy") ?: "PLAYER_ONLY").uppercase().replace("-", "_"))
        } catch (_: IllegalArgumentException) { error("无效的奖励判重策略") }
        file.getConfigurationSection("reward.templates")?.getKeys(false).orEmpty().forEach { key ->
            require(readTemplate(key) != null) { "奖励模板不能为空：$key" }
        }
        val videos = file.getConfigurationSection("reward.videos")?.getKeys(false).orEmpty()
            .associateWith { id ->
                require(id.matches(Regex("BV1[A-Za-z0-9]{9}"))) { "无效BV号：$id" }
                readResolveForBvid(id).also { require(it.template != null) { "活动${id}的奖励模板不存在" } }
            }
        val presentations = videos.keys.associateWith { VideoPresentation.read(file.getConfigurationSection("reward.videos.$it")) }
        snapshot = Snapshot(videos, strategy, presentations)
    }
    @Synchronized fun reload(): Boolean = try {
        file = loadYaml("config.yml")
        loadSnapshot()
        true
    } catch (t: Throwable) { warning("奖励配置加载失败: ${t.message}"); false }

    fun resolveForBvid(bvid: String, defaultKey: String = "default"): ResolveResult =
        snapshot.videos[bvid] ?: ResolveResult(defaultKey, null)
    fun getConfiguredBvids(): List<String> = snapshot.videos.keys.toList()
    fun isBvidConfigured(bvid: String): Boolean = snapshot.videos.containsKey(bvid)
    fun getDedupStrategy(): DedupStrategy = snapshot.strategy
    fun getVideoPresentations(): Map<String, VideoPresentation> = snapshot.presentations
}
