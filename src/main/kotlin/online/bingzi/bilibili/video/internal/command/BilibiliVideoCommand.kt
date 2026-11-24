package online.bingzi.bilibili.video.internal.command

import online.bingzi.bilibili.video.internal.bilibiliMessage
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.credential.QrLoginService
import online.bingzi.bilibili.video.internal.repository.BoundAccountRepository
import online.bingzi.bilibili.video.internal.service.BindingService
import online.bingzi.bilibili.video.internal.service.CredentialService
import online.bingzi.bilibili.video.internal.service.RewardKetherExecutor
import online.bingzi.bilibili.video.internal.service.RewardService
import online.bingzi.bilibili.video.internal.ui.VirtualItemSession
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.command.ConsoleCommandSender
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.expansion.createHelper
import online.bingzi.bilibili.video.internal.TaskScheduler
import online.bingzi.bilibili.video.internal.OperationGate
import java.util.UUID

/**
 * /bv 主命令。
 *
 * 当前实现的子命令：
 * - /bv help        查看帮助
 * - /bv qrcode      生成绑定用二维码地图
 * - /bv videoqr <bvid>  生成跳转指定视频页面的二维码地图
 * - /bv triple <bvid>  使用当前玩家绑定的 B 站账号检测指定稿件的三连状态
 * - /bv status      查看当前绑定状态与凭证信息
 * - /bv reward <bvid>  基于三连记录登记奖励
 * - /bv admin credential ... 管理/查看凭证
 *
 * 权限节点：
 * - bilibili.video.use          玩家基础命令（qrcode/status/triple/reward/videoqr）
 * - bilibili.video.admin        管理员命令
 */
@CommandHeader(name = "bv", aliases = ["bilibili"], permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
object BilibiliVideoCommand {

    /**
     * BVID 正则：BV 开头，长度 10-13 的字母数字组合。
     * 兼容旧版 9 位与新版 12 位编号。
     */
    private val gate by lazy { OperationGate(online.bingzi.bilibili.video.internal.SafetySettings.maxOperations) }

    // 在玩家Entity上下文采集不可变标识，IO闭包不持有Player。
    internal fun request(player: Player, onFailure: (Player) -> Unit = {}, work: (UUID, String) -> Unit): Boolean {
        val uuid = player.uniqueId
        val name = player.name
        if (!gate.enter(uuid)) { player.bilibiliMessage("§e[BV] 操作处理中或服务器繁忙，请稍后再试。"); return false }
        if (!TaskScheduler.async {
            try { work(uuid, name) }
            catch (t: Throwable) {
                taboolib.common.platform.function.warning("[BV] 后台操作失败: ${t.javaClass.simpleName}")
                TaskScheduler.entity(uuid) { it.bilibiliMessage("§c[BV] 操作失败，请稍后再试；领奖异常请先联系管理员核账。"); onFailure(it) }
            } finally { gate.leave(uuid) }
        }) { gate.leave(uuid); player.bilibiliMessage("§e[BV] 请求队列已满，请稍后再试。"); return false }
        return true
    }

    private val BVID_REGEX = Regex("BV1[A-Za-z0-9]{9}")

    @CommandBody
    val main = mainCommand {
        createHelper()
    }

    @CommandBody
    val help = subCommand { execute<ProxyCommandSender> { sender, _, _ ->
        sender.bilibiliMessage("§b[BV] /bv open §7打开三连界面")
        sender.bilibiliMessage("§b[BV] /bv qrcode §7扫码绑定；/bv status 查看绑定")
        sender.bilibiliMessage("§b[BV] /bv triple <BV号> §7检查；/bv reward <BV号> 领奖")
    } }

    @CommandBody
    val open = subCommand { execute<Player> { player, _, _ ->
        val uuid = player.uniqueId
        TaskScheduler.entity(uuid) { online.bingzi.bilibili.video.internal.ui.BilibiliGui.open(it) }
    } }

    /**
     * 生成二维码地图，供玩家扫码绑定 B 站账号。
     */
    @CommandBody(permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
    val qrcode = subCommand {
        execute<Player> { player, _, _ ->
            request(player) { uuid, name ->
                val result = QrLoginService.startLogin(uuid, name)
                if (!result.success || result.qrUrl == null) {
                    val message = result.message
                    TaskScheduler.entity(uuid) { player ->
                        player.bilibiliMessage("§c[BV] $message")
                    }
                    return@request
                }
                val qrUrl = result.qrUrl
                TaskScheduler.entity(uuid, retired = { QrLoginService.cancelLogin(uuid) }) { player ->
                    // 使用虚拟物品发送二维码地图到主手
                    VirtualItemSession.sendVirtualItem(player, qrUrl)
                    player.bilibiliMessage("§a[BV] 已为你生成二维码，请使用手机扫码完成绑定。")
                }
            }
        }
    }

    /**
     * 生成视频页面跳转二维码地图，供玩家扫码打开 B 站视频。
     *
     * 参数：
     * - bvid：视频的 BVID，例如 BVxxxxxxxxx
     *
     * 生成的二维码内容固定为官方视频页 URL：
     *   https://www.bilibili.com/video/<bvid>
     *
     * 与 /bv qrcode 共用 VirtualItemSession 通道，二维码生成本身不需要网络请求，
     * 因此在主线程同步完成，避免与异步通道互相覆盖导致玩家无法及时看到二维码。
     */
    @CommandBody(permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
    val videoqr = subCommand {
        dynamic("bvid") {
            suggestion<Player> { _, _ ->
                RewardConfigManager.getConfiguredBvids()
            }
            execute<Player> { player, context, _ ->
                val raw = context["bvid"].trim()
                val bvid = extractBvid(raw)
                if (bvid == null) {
                    player.bilibiliMessage("§c[BV] 无法识别的 BVID：$raw，应为 BV 开头、长度 10-13 的字母数字组合。")
                    return@execute
                }
                val videoUrl = "https://www.bilibili.com/video/$bvid"
                VirtualItemSession.sendVirtualItem(player, videoUrl)
                player.bilibiliMessage("§a[BV] 已生成视频跳转二维码，扫码即可打开：$videoUrl")
            }
        }
    }

    /**
     * 查看当前玩家的绑定状态与凭证信息。
     */
    @CommandBody(permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
    val status = subCommand {
        execute<Player> { player, _, _ ->
            request(player) { uuid, _ ->
                val binding = BindingService.getBoundAccount(uuid.toString())
                val credential = CredentialService.getCredentialInfoByPlayer(uuid.toString())

                TaskScheduler.entity(uuid) { player ->
                    if (binding == null) {
                        player.bilibiliMessage("§c[BV] 你还没有绑定任何 B 站账号。")
                    } else {
                        player.bilibiliMessage("§b[BV] 当前绑定信息：")
                        player.bilibiliMessage(" §7- 玩家：§f${binding.playerName}")
                        player.bilibiliMessage(" §7- B 站 UID：§f${binding.bilibiliMid}")
                        player.bilibiliMessage(" §7- B 站昵称：§f${binding.bilibiliName}")
                    }

                    if (credential == null) {
                        player.bilibiliMessage("§e[BV] 尚未为你保存登录凭证，请通过 /bv qrcode 扫码登录。")
                    } else {
                        val statusText = when (credential.status) {
                            0 -> "§c禁用"
                            1 -> "§a正常"
                            2 -> "§c过期"
                            else -> "§e未知(${credential.status})"
                        }
                        player.bilibiliMessage("§b[BV] 凭证信息：")
                        player.bilibiliMessage(" §7- 标签：§f${credential.label}")
                        player.bilibiliMessage(" §7- 状态：$statusText")
                        player.bilibiliMessage(" §7- 绑定 UID：§f${credential.bilibiliMid ?: binding?.bilibiliMid}")
                    }
                }
            }
        }
    }

    /**
     * 使用当前玩家绑定的 B 站账号检测指定稿件的三连状态。
     *
     * 参数：
     * - bvid：视频的 BVID，例如 BVxxxxxxxxx
     */
    @CommandBody(permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
    val triple = subCommand {
        dynamic("bvid") {
            suggestion<Player> { _, _ ->
                RewardConfigManager.getConfiguredBvids()
            }
            execute<Player> { player, context, _ ->
                val bvid = context["bvid"]
                if (!ensureConfiguredBvid(player, bvid)) {
                    return@execute
                }
                request(player) { uuid, _ ->
                    val result = CredentialService.checkTripleByPlayer(uuid.toString(), bvid)
                    TaskScheduler.entity(uuid) { player ->
                        if (!result.success || result.tripleStatus == null) {
                            player.bilibiliMessage("§c[BV] ${result.message}")
                            return@entity
                        }
                        val status = result.tripleStatus
                        val likeText = if (status.liked) "§a已点赞" else "§c未点赞"
                        val coinText = if (status.coinCount > 0) "§a已投币(${status.coinCount})" else "§c未投币"
                        val favText = if (status.favoured) "§a已收藏" else "§c未收藏"
                        val tripleText = if (status.isTriple) "§a已完成三连" else "§e尚未完成三连"

                        player.bilibiliMessage("§b[BV] 对稿件 $bvid 的三连状态：")
                        player.bilibiliMessage(" §7- $likeText")
                        player.bilibiliMessage(" §7- $coinText")
                        player.bilibiliMessage(" §7- $favText")
                        player.bilibiliMessage(" §7- $tripleText")
                    }
                }
            }
        }
    }

    /**
     * 基于三连记录登记奖励。
     */
    @CommandBody(permission = "bilibili.video.use", permissionDefault = PermissionDefault.TRUE)
    val reward = subCommand {
        dynamic("bvid") {
            suggestion<Player> { _, _ -> RewardConfigManager.getConfiguredBvids() }
            execute<Player> { player, context, _ -> claim(player, context["bvid"]) }
        }
    }

    /** 命令与GUI共用唯一领奖通道；声音依据落库后的真实结果，不另走发奖捷径。 */
    internal fun claim(player: Player, bvid: String, completed: (Player, Boolean) -> Unit = { _, _ -> }) {
        if (!player.hasPermission("bilibili.video.use") || !ensureConfiguredBvid(player, bvid)) { completed(player, false); return }
        if (!request(player, onFailure = { completed(it, false) }) { uuid, name ->
            val result = RewardService.rewardByPlayerAndBvid(uuid.toString(), name, bvid)
            TaskScheduler.entity(uuid) { current ->
                if (!result.success) {
                    current.bilibiliMessage("§c[BV] ${result.message}")
                    completed(current, false)
                } else {
                    try {
                        RewardKetherExecutor.execute(current, result.template!!, result.bvid!!, result.targetKey!!)
                            .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .whenComplete { _, failure ->
                                if (!TaskScheduler.async {
                                    try {
                                        RewardService.complete(result, uuid.toString(), name, failure == null)
                                        TaskScheduler.entity(uuid) {
                                            it.bilibiliMessage(if (failure == null) "§a[BV] ${result.message}" else "§c[BV] 奖励执行异常，已锁定记录，请联系管理员核账。")
                                            completed(it, failure == null)
                                        }
                                    } catch (t: Throwable) {
                                        taboolib.common.platform.function.warning("[BV] 领奖结果写入失败，须人工核账: ${result.claimKey}")
                                        TaskScheduler.entity(uuid) { it.bilibiliMessage("§c[BV] 奖励结果登记异常，请联系管理员核账。"); completed(it, false) }
                                    }
                                }) {
                                    taboolib.common.platform.function.warning("[BV] 未能登记领奖结果，须人工核账: ${result.claimKey}")
                                    TaskScheduler.entity(uuid) { it.bilibiliMessage("§c[BV] 奖励结果尚未登记，请联系管理员核账。"); completed(it, false) }
                                }
                            }
                    } catch (t: Throwable) {
                        TaskScheduler.async { RewardService.complete(result, uuid.toString(), name, false) }
                        current.bilibiliMessage("§c[BV] 奖励执行异常，请联系管理员核账。")
                        completed(current, false)
                    }
                }
            }
        }) completed(player, false)
    }

    /**
     * 管理员命令：管理与查看凭证。
     *
     * - /bv admin credential list
     * - /bv admin credential info <label>
     * - /bv admin credential refresh <label>   （占位，刷新逻辑后续实现）
     * - /bv admin unbind <target>
     * - /bv admin reload
     */
    @CommandBody(permission = "bilibili.video.admin", permissionDefault = PermissionDefault.OP)
    val admin = subCommand {
        literal("unbind") {
            dynamic("target") {
                execute<ProxyCommandSender> { sender, context, _ ->
                    if (sender.origin !is ConsoleCommandSender) {
                        sender.bilibiliMessage("§f[BV] 解绑仅限服主在服务器控制台执行，玩家及OP不能自行解绑。")
                        return@execute
                    }
                    val argument = context["target"]
                    adminRequest(sender) { reply ->
                        val binding = runCatching { UUID.fromString(argument) }.getOrNull()?.let {
                            BoundAccountRepository.findByPlayerUuid(it.toString(), true)
                        } ?: BoundAccountRepository.findByPlayerName(argument, true)
                          ?: argument.toLongOrNull()?.let { BoundAccountRepository.findByBilibiliMid(it, true) }
                        if (binding == null) reply("§c[BV] 未找到绑定记录。")
                        else reply("§b[BV] ${BindingService.unbind(binding.playerUuid).message}")
                    }
                }
            }
        }
        literal("credential") {
            literal("list") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    adminRequest(sender) { reply ->
                        val records = online.bingzi.bilibili.video.internal.repository.CredentialRepository.findPage()
                        reply("§b[BV] 凭证列表（最多50条）:")
                        records.take(50).forEach { reply("§7- ${it.label} | UID=${it.bilibiliMid} | 状态=${it.status}") }
                    }
                }
            }
            literal("info") {
                dynamic("label") {
                    execute<ProxyCommandSender> { sender, context, _ ->
                        val label = context["label"]
                        adminRequest(sender) { reply ->
                            val credential = CredentialService.getCredentialInfo(label)
                            reply(if (credential == null) "§c[BV] 未找到凭证。" else
                                "§b[BV] $label | UID=${credential.bilibiliMid} | 状态=${credential.status}")
                        }
                    }
                }
            }
            literal("refresh") {
                dynamic("label") {
                    execute<ProxyCommandSender> { sender, _, _ -> sender.bilibiliMessage("§e[BV] 凭证刷新尚未实现，请重新扫码登录。") }
                }
            }
        }
        literal("reload") {
            execute<ProxyCommandSender> { sender, _, _ ->
                adminRequest(sender) { reply ->
                    online.bingzi.bilibili.video.internal.ChatMessages.load()
                    val reward = RewardConfigManager.reload()
                    val gui = online.bingzi.bilibili.video.internal.ui.BilibiliGui.reload()
                    reply(if (reward && gui) "§a[BV] 奖励及GUI配置重载完成；已打开页面关闭后重新打开生效。" else "§e[BV] 部分配置重载失败或GUI依赖未就绪，请查看日志。")
                }
            }
        }
        createHelper()
    }

    private fun adminRequest(sender: ProxyCommandSender, work: ((String) -> Unit) -> Unit) {
        val origin = sender.origin
        val uuid = (origin as? Player)?.uniqueId
        val reply: (String) -> Unit = { message ->
            if (uuid != null) TaskScheduler.entity(uuid) { it.bilibiliMessage(message) }
            else Bukkit.getConsoleSender().bilibiliMessage(message)
        }
        if (!TaskScheduler.async {
            try { work(reply) } catch (t: Throwable) {
                // 不记录异常消息或SQL参数，避免凭证泄露；保留类型与调用位置供定位。
                taboolib.common.platform.function.warning("[BV] 管理查询失败: ${t.javaClass.name}; ${t.stackTrace.take(8).joinToString(" <- ")}")
                reply("§c[BV] 查询失败，请检查日志。")
            }
        }) reply("§e[BV] 请求队列已满，请稍后再试。")
    }

    private fun ensureConfiguredBvid(player: Player, bvid: String): Boolean {
        if (!RewardConfigManager.isBvidConfigured(bvid)) {
            player.bilibiliMessage("§c[BV] 稿件 $bvid 尚未在 config.yml 的 reward.videos 中登记，无法使用该命令。")
            return false
        }
        return true
    }

    /**
     * 从玩家输入中提取 BVID，支持以下格式：
     * - BVxxxxxxxxxx            直接输入
     * - BV1xxxxxxxxxx           直接输入
     * - https://www.bilibili.com/video/BVxxxxxxxxxx
     * - https://www.bilibili.com/video/BVxxxxxxxxxx?p=1&spm=...
     * - https://b23.tv/xxxxxx   （无法定位，提示玩家用 BV 号）
     *
     * 仅在格式合法时返回，否则返回 null。
     */
    private fun extractBvid(raw: String): String? {
        if (raw.isEmpty()) return null
        // 1. 如果玩家直接粘贴了完整 URL，从中截取 BV 段
        val fromUrl = if (raw.startsWith("https://www.bilibili.com/video/")) BVID_REGEX.find(raw)?.value else null
        if (fromUrl != null && BVID_REGEX.matches(fromUrl)) {
            return fromUrl
        }
        // 2. 否则按字面 BVID 校验
        if (BVID_REGEX.matches(raw)) {
            return raw
        }
        return null
    }

}
