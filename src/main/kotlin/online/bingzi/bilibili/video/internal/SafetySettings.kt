package online.bingzi.bilibili.video.internal

import online.bingzi.bilibili.video.internal.config.RewardConfigManager

/** 启动时读取并校验；调整容量需要完整重启。 */
object SafetySettings {
    var ioThreads = 4; private set
    var ioQueue = 128; private set
    var maxOperations = 64; private set
    var maxSessions = 128; private set
    var pollSeconds = 3L; private set
    var sessionMillis = 180_000L; private set
    var httpSeconds = 15L; private set
    var virtualMapId = 2_000_000_001; private set
    var qrRefreshMillis = 250L; private set
    fun load() {
        val config = RewardConfigManager.file
        fun int(key: String, default: Int, range: IntRange): Int {
            val value = config.getInt("safety.$key", default)
            require(value in range) { "safety.$key 必须在 ${range.first}..${range.last}" }
            return value
        }
        ioThreads = int("io-threads", 4, 1..16)
        ioQueue = int("io-queue", 128, 8..1024)
        maxOperations = int("max-operations", 64, 1..512)
        maxSessions = int("max-login-sessions", 128, 1..512)
        pollSeconds = int("poll-seconds", 3, 2..30).toLong()
        sessionMillis = int("session-seconds", 180, 30..180) * 1000L
        httpSeconds = int("http-timeout-seconds", 15, 3..30).toLong()
        virtualMapId = int("virtual-map-id", 2_000_000_001, 1_000_000_000..Int.MAX_VALUE)
        qrRefreshMillis = int("qr-refresh-millis", 250, 100..2000).toLong()
    }
}
