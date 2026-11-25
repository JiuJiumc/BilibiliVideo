package online.bingzi.bilibili.video.internal.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 严格容量上限；比较身份删除防止旧页面关闭回调误删新会话。 */
internal class GuiSessions<T : Any> {
    private val entries = ConcurrentHashMap<UUID, T>()
    @Synchronized fun admit(id: UUID, value: T, limit: Int): Boolean {
        if (!entries.containsKey(id) && entries.size >= limit) return false
        entries[id] = value
        return true
    }
    operator fun get(id: UUID): T? = entries[id]
    fun remove(id: UUID, expected: T) = entries.remove(id, expected)
    fun remove(id: UUID) { entries.remove(id) }
    fun clear() = entries.clear()
    fun size() = entries.size
}
