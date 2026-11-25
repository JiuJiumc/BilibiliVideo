package online.bingzi.bilibili.video.internal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** 不保留Player；限制总量与每位玩家的并发操作，完成后立即释放。 */
class OperationGate(limit: Int) {
    private val capacity = Semaphore(limit)
    private val active = ConcurrentHashMap.newKeySet<UUID>()
    fun enter(uuid: UUID): Boolean {
        if (!capacity.tryAcquire()) return false
        if (active.add(uuid)) return true
        capacity.release()
        return false
    }
    fun leave(uuid: UUID) { if (active.remove(uuid)) capacity.release() }
    fun size(): Int = active.size
}
