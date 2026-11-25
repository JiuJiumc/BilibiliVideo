package online.bingzi.bilibili.video.internal.service

/** 原生动作解析；整份模板先校验，未知脚本拒绝加载。 */
internal object RewardActions {
    sealed interface Action
    data class Item(val material: String, val amount: Int) : Action
    data class Message(val text: String) : Action
    data class Console(val command: String) : Action
    data class PlayerCommand(val command: String) : Action
    private val tell = Regex("""tell\s+"([^"\r\n]*)"""")
    private val command = Regex("""command\s+(?:papi\s+)?"([^"\r\n]*)"(?:\s+by\s+(console|player))?""")
    private val give = Regex("""(?:minecraft:)?give\s+(?:%player_name%|\{player})\s+([a-zA-Z0-9_:]+)\s+(\d+)""")
    fun parse(lines: List<String>): List<Action> {
        require(lines.isNotEmpty() && lines.size <= 64) { "奖励动作数量必须为1～64" }
        return lines.map { raw ->
            val line = raw.trim()
            require(line.length <= 2048 && !line.contains('\n') && !line.contains('\r')) { "奖励动作过长或包含换行" }
            when {
                line.startsWith("item ") -> {
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size == 3) { "item格式：item minecraft:diamond 1" }
                    item(parts[1], parts[2])
                }
                line.startsWith("message ") -> Message(line.removePrefix("message "))
                line.startsWith("console ") -> console(line.removePrefix("console "))
                line.startsWith("player ") -> PlayerCommand(validCommand(line.removePrefix("player ")))
                tell.matches(line) -> Message(tell.matchEntire(line)!!.groupValues[1])
                command.matches(line) -> {
                    val m = command.matchEntire(line)!!
                    if (m.groupValues[2] == "console") console(m.groupValues[1])
                    else PlayerCommand(validCommand(m.groupValues[1]))
                }
                else -> error("不支持的奖励语句；请用item/message/console/player。旧复杂Kether须改写。")
            }
        }
    }
    private fun item(id: String, amount: String): Item {
        require(id.matches(Regex("(?:minecraft:)?[a-zA-Z0-9_]+"))) { "item仅支持原版物品ID" }
        val n = amount.toIntOrNull()
        require(n != null && n in 1..2304) { "单条物品数量须为1～2304" }
        return Item(id, n)
    }
    private fun validCommand(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/')) { "命令不能为空或以斜杠开头" }
        return value
    }
    private fun console(value: String): Action {
        validCommand(value)
        val m = give.matchEntire(value)
        return if (m != null) item(m.groupValues[1], m.groupValues[2]) else Console(value)
    }
}
