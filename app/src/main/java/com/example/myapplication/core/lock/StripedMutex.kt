package com.example.myapplication.core.lock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 key 分段的协程互斥锁。
 *
 * ## 为什么需要
 *
 * 同一个订单文件镜像的**追加**与**就地补丁**必须严格互斥（ANDROID.md §7）：
 * 补丁写的是绝对字节偏移，如果在它落盘的瞬间另一个协程追加了一行，
 * 文件长度变了、但补丁的偏移没变，覆写就会打到错误的字节上——**而且不会报错**。
 *
 * 但不同 fileId 之间没有任何冲突（B 类按月分文件、A 类按年分文件），
 * 懒同步的后台逐月补同步正需要它们并行。所以锁的粒度是 `storeKey|fileId`，不是全局。
 *
 * ## 锁顺序（全局唯一，不许反过来）
 *
 * ```
 * mirror 锁  →  Room 事务
 * ```
 *
 * 任何路径都不许在持有 Room 事务的同时去拿 mirror 锁，否则会与正常路径形成死锁环。
 * 同理，**不要在 Room 事务内做文件 I/O**：事务持有数据库写锁，
 * 在里面 fsync 一个几十 MB 的镜像会把整个 App 的数据库卡住。
 *
 * ## 为什么不混用 synchronized
 *
 * 全部镜像写入都在 `Dispatchers.IO.limitedParallelism(N)` 上、提交点是 suspend 串行 await，
 * 协程锁够用。混用 `synchronized` 与 `Mutex` 会让"谁先谁后"变得无法推理。
 */
class StripedMutex(private val stripes: Int = 64) {

    private val locks = Array(stripes) { Mutex() }

    /** 该 key 对应的锁。同一 key 恒定映射到同一把锁。 */
    fun forKey(key: String): Mutex = locks[(key.hashCode() and 0x7FFFFFFF) % stripes]

    suspend fun <T> withLock(key: String, block: suspend () -> T): T =
        forKey(key).withLock { block() }

    companion object {
        /** 镜像锁的 key 构造。全项目只能用这一个函数拼，避免各处写法不一致导致锁不住。 */
        fun mirrorKey(storeKey: String, fileId: String): String = "$storeKey|$fileId"
    }
}
