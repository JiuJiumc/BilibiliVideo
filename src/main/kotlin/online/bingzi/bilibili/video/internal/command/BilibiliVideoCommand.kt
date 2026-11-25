package online.bingzi.bilibili.video.internal.command

import online.bingzi.bilibili.video.internal.*
import online.bingzi.bilibili.video.internal.config.RewardConfigManager
import online.bingzi.bilibili.video.internal.credential.QrLoginService
import online.bingzi.bilibili.video.internal.repository.BoundAccountRepository
import online.bingzi.bilibili.video.internal.service.*
import online.bingzi.bilibili.video.internal.ui.VirtualItemSession
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.command.*
import java.util.UUID

object BilibiliVideoCommand : CommandExecutor, TabCompleter {
    private val BVID_REGEX = Regex("BV1[A-Za-z0-9]{9}")
    private val gate by lazy { OperationGate(online.bingzi.bilibili.video.internal.SafetySettings.maxOperations) }

    // 在玩家Entity上下文采集不可变标识，IO闭包不持有Player。
    internal fun request(player: Player, onFailure: (Player) -> Unit = {}, work: (UUID, String) -> Unit): Boolean {
        val uuid = player.uniqueId
        val name = player.name
        if (!gate.enter(uuid)) { player.bilibiliMessage(Messages.text("command.busy")); return false }
        if (!TaskScheduler.async {
            try { work(uuid, name) }
            catch (t: Throwable) {
                online.bingzi.bilibili.video.internal.warning("[BV] 后台操作失败: ${t.javaClass.simpleName}")
                TaskScheduler.entity(uuid) { it.bilibiliMessage(Messages.text("command.operation-failed")); onFailure(it) }
            } finally { gate.leave(uuid) }
        }) { gate.leave(uuid); player.bilibiliMessage(Messages.text("command.queue-full")); return false }
        return true
    }


    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("bilibili.video.use")) { sender.bilibiliMessage(Messages.text("command.no-permission")); return true }
        if (args.firstOrNull()?.equals("testreward", true) == true) {
            if (!sender.hasPermission("bilibili.video.admin") || !sender.hasPermission("bilibili.video.testreward")) {
                sender.bilibiliMessage(Messages.text("command.no-admin-permission")); return true
            }
            val player = sender as? Player ?: run { sender.bilibiliMessage(Messages.text("command.player-only")); return true }
            val bvid = if (args.size == 1) RewardConfigManager.getConfiguredBvids().singleOrNull()
                else args[1].let(::extractBvid)
            if (bvid == null || args.size > 2) { sender.bilibiliMessage(Messages.text("test-reward.usage")); return true }
            TaskScheduler.entity(player.uniqueId) { current -> testReward(current, bvid) }
            return true
        }
        if (args.firstOrNull()?.equals("reload", true) == true || args.firstOrNull()?.equals("admin", true) == true) {
            if (!sender.hasPermission("bilibili.video.admin")) { sender.bilibiliMessage(Messages.text("command.no-admin-permission")); return true }
            admin(sender, if (args[0].equals("reload", true)) listOf("reload") else args.drop(1)); return true
        }
        if (args.isEmpty() || args[0].equals("help", true)) { help(sender); return true }
        val player=sender as? Player ?: run { sender.bilibiliMessage(Messages.text("command.player-only")); return true }
        val id=player.uniqueId
        val sub=args[0].lowercase()
        val raw=args.getOrNull(1)
        TaskScheduler.entity(id) { current ->
            if (!current.hasPermission("bilibili.video.use")) return@entity
            when(sub) {
                "open" -> online.bingzi.bilibili.video.internal.ui.BilibiliGui.open(current)
                "qrcode" -> login(current)
                "status" -> status(current)
                "videoqr", "triple", "reward" -> {
                    val bvid=raw?.let(::extractBvid)
                    if (bvid == null) { current.bilibiliMessage(Messages.text("command.video-usage", "sub" to (sub))); return@entity }
                    when(sub) {
                        "videoqr" -> {
                            VirtualItemSession.sendVirtualItem(current,"https://www.bilibili.com/video/$bvid")
                            current.bilibiliMessage(Messages.text("command.video-qr-generated"))
                        }
                        "triple" -> triple(current,bvid)
                        "reward" -> claim(current,bvid)
                    }
                }
                else -> help(current)
            }
        }
        return true
    }
    private fun help(sender: CommandSender) {
        sender.bilibiliMessage(Messages.text("command.help-main"))
        sender.bilibiliMessage(Messages.text("command.help-video"))
        if(sender.hasPermission("bilibili.video.admin")) sender.bilibiliMessage(Messages.text("command.help-admin"))
        if(sender.hasPermission("bilibili.video.admin") && sender.hasPermission("bilibili.video.testreward")) sender.bilibiliMessage(Messages.text("test-reward.usage"))
    }
    /** 仅执行当前活动模板；不访问绑定、三连接口或任何领取记录。每位玩家串行。 */
    private fun testReward(player: Player, bvid: String) {
        if (!player.hasPermission("bilibili.video.admin") || !player.hasPermission("bilibili.video.testreward")) {
            player.bilibiliMessage(Messages.text("command.no-admin-permission")); return
        }
        if (!ensureConfiguredBvid(player, bvid)) return
        val uuid = player.uniqueId
        if (!gate.enter(uuid)) { player.bilibiliMessage(Messages.text("command.busy")); return }
        try {
            val template = RewardConfigManager.resolveForBvid(bvid).template
                ?: error(Messages.text("command.reward-template-missing"))
            RewardExecutor.checkCapacity(player, template)
            player.bilibiliMessage(Messages.text("test-reward.started", "bvid" to bvid))
            val name = player.name
            RewardExecutor.execute(player, template, bvid, bvid).whenComplete { _, failure ->
                gate.leave(uuid)
                online.bingzi.bilibili.video.internal.info("测试发奖 player=$name uuid=$uuid bvid=$bvid template=${template.key} success=${failure == null}")
                TaskScheduler.entity(uuid) { current ->
                    current.bilibiliMessage(Messages.text(if (failure == null) "test-reward.success" else "test-reward.failed", "bvid" to bvid))
                }
            }
        } catch (t: Throwable) {
            gate.leave(uuid)
            player.bilibiliMessage(Messages.text("test-reward.rejected", "reason" to (t.message ?: t.javaClass.simpleName)))
        }
    }
    private fun login(player: Player) {
        request(player) { uuid,name ->
            val result=QrLoginService.startLogin(uuid,name)
            if (!result.success || result.qrUrl == null) {
                TaskScheduler.entity(uuid) { it.bilibiliMessage(Messages.text("command.result", "message" to (result.message))) }; return@request
            }
            TaskScheduler.entity(uuid, retired={ QrLoginService.cancelLogin(uuid) }) {
                VirtualItemSession.sendVirtualItem(it,result.qrUrl)
                it.bilibiliMessage(Messages.text("command.qr-generated"))
            }
        }
    }
    private fun status(player: Player) {
        request(player) { uuid,_ ->
            val binding=BindingService.getBoundAccount(uuid.toString())
            val credential=CredentialService.getCredentialInfoByPlayer(uuid.toString())
            TaskScheduler.entity(uuid) {
                if(binding == null) it.bilibiliMessage(Messages.text("command.unbound"))
                else it.bilibiliMessage(Messages.text("command.binding-status", "bilibiliName" to (binding.bilibiliName), "bilibiliMid" to (binding.bilibiliMid)))
                it.bilibiliMessage(if(credential == null) Messages.text("command.credential-missing") else Messages.text("command.credential-status", "state" to (when(credential.status) {1 -> Messages.text("command.credential-normal"); 2 -> Messages.text("command.credential-expired"); else -> Messages.text("command.credential-disabled")})))
            }
        }
    }
    private fun triple(player: Player,bvid: String) {
        if (!ensureConfiguredBvid(player,bvid)) return
        request(player) { uuid,_ ->
            val result=CredentialService.checkTripleByPlayer(uuid.toString(),bvid)
            TaskScheduler.entity(uuid) {
                val t=result.tripleStatus
                if(!result.success || t == null) it.bilibiliMessage(Messages.text("command.result", "message" to (result.message)))
                else it.bilibiliMessage(Messages.text("command.triple-status", "liked" to (t.liked), "coinCount" to (t.coinCount), "favoured" to (t.favoured), "state" to (if(t.isTriple) Messages.text("command.triple-done") else Messages.text("command.triple-missing"))))
            }
        }
    }
    private fun admin(sender: CommandSender,args: List<String>) {
        when(args.firstOrNull()?.lowercase()) {
            "reload" -> adminRequest(sender) { reply ->
                try { ChatMessages.load() } catch (t: Throwable) {
                    online.bingzi.bilibili.video.internal.warning("提示配置重载失败，保留原设置: ${t.message}")
                    reply(Messages.text("command.reload-failed"))
                    return@adminRequest
                }
                val reward=RewardConfigManager.reload()
                val gui=online.bingzi.bilibili.video.internal.ui.BilibiliGui.reload()
                reply(if(reward && gui) Messages.text("command.reload-success") else Messages.text("command.reload-failed"))
            }
            "unbind" -> {
                if(sender !is ConsoleCommandSender) { sender.bilibiliMessage(Messages.text("command.unbind-console-only")); return }
                val target=args.getOrNull(1) ?: run { sender.bilibiliMessage(Messages.text("command.unbind-usage")); return }
                adminRequest(sender) { reply ->
                    val binding=runCatching { UUID.fromString(target) }.getOrNull()?.let { BoundAccountRepository.findByPlayerUuid(it.toString(),true) }
                        ?: BoundAccountRepository.findByPlayerName(target,true)
                        ?: target.toLongOrNull()?.let { BoundAccountRepository.findByBilibiliMid(it,true) }
                    reply(if(binding == null) Messages.text("command.binding-missing") else Messages.text("command.result", "message" to (BindingService.unbind(binding.playerUuid).message)))
                }
            }
            "credential" -> when(args.getOrNull(1)?.lowercase()) {
                "list" -> adminRequest(sender) { reply ->
                    val records=online.bingzi.bilibili.video.internal.repository.CredentialRepository.findPage()
                    reply(Messages.text("command.credential-list-header"))
                    records.take(50).forEach { reply(Messages.text("command.credential-list-row", "label" to (it.label), "bilibiliMid" to (it.bilibiliMid), "status" to (it.status))) }
                }
                "info" -> {
                    val label=args.getOrNull(2) ?: run { help(sender); return }
                    adminRequest(sender) { reply ->
                        val c=CredentialService.getCredentialInfo(label)
                        reply(if(c == null) Messages.text("command.credential-not-found") else Messages.text("command.credential-info", "label" to (label), "bilibiliMid" to (c.bilibiliMid), "status" to (c.status)))
                    }
                }
                "refresh" -> sender.bilibiliMessage(Messages.text("command.credential-refresh"))
                else -> help(sender)
            }
            else -> help(sender)
        }
    }
    override fun onTabComplete(sender: CommandSender,command: Command,alias: String,args: Array<out String>): List<String> {
        if(!sender.hasPermission("bilibili.video.use")) return emptyList()
        val admin=sender.hasPermission("bilibili.video.admin")
        val options=when {
            args.size==1 -> listOf("open","qrcode","status","videoqr","triple","reward","help")+(if(admin) listOf("reload","admin") else emptyList())+(if(admin && sender.hasPermission("bilibili.video.testreward")) listOf("testreward") else emptyList())
            admin && sender.hasPermission("bilibili.video.testreward") && args.size==2 && args[0].equals("testreward",true) -> RewardConfigManager.getConfiguredBvids()
            args.size==2 && args[0] in listOf("videoqr","triple","reward") -> RewardConfigManager.getConfiguredBvids()
            admin && args.size==2 && args[0]=="admin" -> listOf("reload","credential")+if(sender is ConsoleCommandSender) listOf("unbind") else emptyList()
            admin && args.size==3 && args[0]=="admin" && args[1]=="credential" -> listOf("list","info","refresh")
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(),true) }
    }
    /** 命令与GUI共用唯一领奖通道；声音依据落库后的真实结果，不另走发奖捷径。 */
    internal fun claim(player: Player, bvid: String, completed: (Player, Boolean) -> Unit = { _, _ -> }) {
        if (!player.hasPermission("bilibili.video.use") || !ensureConfiguredBvid(player, bvid)) { completed(player, false); return }
        try {
            val template = RewardConfigManager.resolveForBvid(bvid).template ?: error(Messages.text("command.reward-template-missing"))
            RewardExecutor.checkCapacity(player, template)
        } catch (t: Throwable) {
            player.bilibiliMessage(Messages.text("command.reward-precheck-failed", "reason" to (t.message ?: Messages.text("command.reward-config-invalid")))); completed(player, false); return
        }
        if (!request(player, onFailure = { completed(it, false) }) { uuid, name ->
            val result = RewardService.rewardByPlayerAndBvid(uuid.toString(), name, bvid)
            TaskScheduler.entity(uuid) { current ->
                if (!result.success) {
                    current.bilibiliMessage(Messages.text("command.result-error", "message" to (result.message)))
                    completed(current, false)
                } else {
                    try {
                        RewardExecutor.execute(current, result.template!!, result.bvid!!, result.targetKey!!)
                            .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .whenComplete { _, failure ->
                                if (!TaskScheduler.async {
                                    try {
                                        RewardService.complete(result, uuid.toString(), name, failure == null)
                                        TaskScheduler.entity(uuid) {
                                            it.bilibiliMessage(if (failure == null) Messages.text("command.result-success", "message" to (result.message)) else Messages.text("command.reward-locked"))
                                            completed(it, failure == null)
                                        }
                                    } catch (t: Throwable) {
                                        online.bingzi.bilibili.video.internal.warning("[BV] 领奖结果写入失败，须人工核账: ${result.claimKey}")
                                        TaskScheduler.entity(uuid) { it.bilibiliMessage(Messages.text("command.reward-register-failed")); completed(it, false) }
                                    }
                                }) {
                                    online.bingzi.bilibili.video.internal.warning("[BV] 未能登记领奖结果，须人工核账: ${result.claimKey}")
                                    TaskScheduler.entity(uuid) { it.bilibiliMessage(Messages.text("command.reward-register-pending")); completed(it, false) }
                                }
                            }
                    } catch (t: Throwable) {
                        TaskScheduler.async { RewardService.complete(result, uuid.toString(), name, false) }
                        current.bilibiliMessage(Messages.text("command.reward-execution-failed"))
                        completed(current, false)
                    }
                }
            }
        }) completed(player, false)
    }

    private fun adminRequest(sender: CommandSender, work: ((String) -> Unit) -> Unit) {
        val origin = sender
        val uuid = (origin as? Player)?.uniqueId
        val reply: (String) -> Unit = { message ->
            if (uuid != null) TaskScheduler.entity(uuid) { it.bilibiliMessage(message) }
            else Bukkit.getConsoleSender().bilibiliMessage(message)
        }
        if (!TaskScheduler.async {
            try { work(reply) } catch (t: Throwable) {
                // 不记录异常消息或SQL参数，避免凭证泄露；保留类型与调用位置供定位。
                online.bingzi.bilibili.video.internal.warning("[BV] 管理查询失败: ${t.javaClass.name}; ${t.stackTrace.take(8).joinToString(" <- ")}")
                reply(Messages.text("command.query-failed"))
            }
        }) reply(Messages.text("command.queue-full"))
    }

    private fun ensureConfiguredBvid(player: Player, bvid: String): Boolean {
        if (!RewardConfigManager.isBvidConfigured(bvid)) {
            player.bilibiliMessage(Messages.text("command.video-unregistered", "bvid" to (bvid)))
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
