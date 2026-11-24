package online.bingzi.bilibili.video.internal.database

import online.bingzi.bilibili.video.internal.config.DatabaseType
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor

/**
 * SQLite 写操作序列化执行器。
 *
 * SQLite 使用文件级锁，多线程并发写入会导致 SQLITE_BUSY 错误。
 * 此执行器通过单线程队列序列化所有写操作，彻底消除写冲突。
 *
 * 对于 MySQL 等其他数据库，写操作直接执行（透传模式）。
 */
internal object SqliteWriteExecutor {

    @Volatile
    private var writeExecutor: ExecutorService? = null

    @Volatile
    private var sqliteMode: Boolean = false
    private val writing = ThreadLocal<Boolean>()

    private val threadFactory = object : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            return Thread(r, "bv-sqlite-write-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    /**
     * 初始化执行器。
     *
     * @param databaseType 数据库类型，仅 SQLite 时启用序列化
     */
    fun initialize(databaseType: DatabaseType) {
        sqliteMode = (databaseType == DatabaseType.SQLITE)
        if (sqliteMode && writeExecutor == null) {
            writeExecutor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                ArrayBlockingQueue(128), threadFactory, ThreadPoolExecutor.AbortPolicy())
        }
    }

    /**
     * 同步执行写操作。
     *
     * - SQLite 模式：提交到单线程队列执行，阻塞等待结果
     * - 其他数据库：直接在当前线程执行
     *
     * @param operation 写操作
     * @return 操作结果
     */
    fun <T> executeWrite(operation: () -> T): T {
        return if (sqliteMode) {
            val executor = writeExecutor
                ?: throw IllegalStateException("SqliteWriteExecutor not initialized")
            if (writing.get() == true) return operation()
            val future = executor.submit(Callable {
                writing.set(true)
                try { operation() } finally { writing.remove() }
            })
            try {
                future.get(35, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                future.cancel(true)
                if (t is InterruptedException) Thread.currentThread().interrupt()
                throw t
            }
        } else {
            operation()
        }
    }

    /**
     * 关闭执行器，释放资源。
     *
     * 等待最多 5 秒让队列中的任务完成。
     */
    fun shutdown() {
        writeExecutor?.let { executor ->
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(true) }
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(true) }
                Thread.currentThread().interrupt()
            }
        }
        writeExecutor = null
        sqliteMode = false
    }

    /**
     * 检查当前是否为 SQLite 模式。
     */
    fun isSqliteMode(): Boolean = sqliteMode
}
