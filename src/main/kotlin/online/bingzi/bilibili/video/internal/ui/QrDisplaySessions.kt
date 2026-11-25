package online.bingzi.bilibili.video.internal.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 会话不存活体玩家；每个会话最多有一个待调度刷新，旧回调不能覆盖新显示。 */
internal class QrDisplaySessions<T>(private val capacity: () -> Int) {
    class Entry<T>(val slot: Int, val deadline: Long, val payload: T) {
        val queued = AtomicBoolean(false)
    }
    private val entries = ConcurrentHashMap<UUID, Entry<T>>()
    @Synchronized fun put(id: UUID, entry: Entry<T>): Boolean {
        if (!entries.containsKey(id) && entries.size >= capacity()) return false
        entries[id] = entry
        return true
    }
    fun current(id: UUID, entry: Entry<T>) = entries[id] === entry
    fun remove(id: UUID): Entry<T>? = entries.remove(id)
    fun remove(id: UUID, entry: Entry<T>) = entries.remove(id, entry)
    fun contains(id: UUID) = entries.containsKey(id)
    fun forEach(action: (UUID, Entry<T>) -> Unit) = entries.forEach(action)
    fun clear() = entries.clear()
}
