package com.example.myapplication.net

import com.example.myapplication.net.dto.OrderSyncRequestDto

/**
 * [Service1Api] 里订单文件同步那一小段的窄接口。
 *
 * ## 为什么要有这个接口
 *
 * 与 [ProductApi] 同一个理由：`Service1Api` 是 `final` 的，项目又没引 Mockito，
 * 所以 `OrderFileSyncEngine` 若直接依赖它就没法在 JVM 上测。
 * 而订单文件同步恰恰是**全项目最不能出错**的一段（偏移错了是静默且不可恢复的），
 * 它的每个分支都必须能被单测钉住。
 *
 * 只抽 `sync` / `probeAfter409` 两个方法，**不抽 `/add` 与 `/patch`**：
 * 那两个属于 P5 的 outbox 上传路径，等真写到那儿再按需抽，
 * 免得现在猜一个用不上的形状。
 *
 * ## 错误约定
 *
 * 与 [ProductApi] 一致：失败一律**抛 [ApiError]**，绝不返回 null 或空集合来表示失败。
 * "同步失败"与"同步成功但没有增量"必须能区分，否则引擎会把网络故障当成 LATEST，
 * 游标停在原地却以为已经追平 —— 之后统计少一笔，且没有任何报错。
 */
interface OrderFileApi {

    /**
     * `POST /api/orderfiles/sync`。三种响应形状由实现方分辨好，
     * SERVER_BEHIND 已经抛成 [ApiError.Conflict]（`isOrderFileServerBehind = true`）。
     *
     * @param onFull FULL 时**在 IO 线程上同步回调**，响应体仍然打开。
     *        实现里必须流式写入，绝不把几十 MB 读进内存。
     *
     * ⚠ 类型是 **suspend** 而不是普通函数：这个回调要跑
     * [com.example.myapplication.mirror.MirrorCommitter.commitFull]，
     * 里面是"取分段锁 + 写文件 + fsync + 一个 Room 事务"，全是挂起函数。
     * 若声明成普通 lambda，实现方只能在回调里 `runBlocking` —— 那会占着 IO 线程
     * 再阻塞一个线程池，几十 MB 的文件下就是实打实的线程泄漏。
     *
     * Room 事务在连接还没关的时候执行是**可接受**的：此刻所有字节都已经 fsync，
     * 事务失败/进程被杀都不会丢镜像内容，下一次 sync 会走 LATEST 自愈。
     */
    suspend fun syncOrderFile(
        request: OrderSyncRequestDto,
        onFull: suspend (Service1Api.FullFileBody) -> Unit,
    ): Service1Api.SyncOutcome

    /**
     * 409 之后的二值探测。**两个游标值是字面量 `version=0, lastStreamId=""`**，
     * 不接受参数 —— 传进来的任何 version 都会让探测失去意义。
     *
     * | 探测结果 | 含义 |
     * |---|---|
     * | `Latest` | 服务器根本没这个文件（运维已归档删除）→ `ARCHIVED` |
     * | `Full` | 服务器确实落后但文件还在 → 问用户 |
     * | `Incremental` | 不该出现 → 当形状错误处理 |
     *
     * ⚠ 探测到 `Full` 时**绝对不要把 `onFull` 拿到的 body 落地**。
     * 那个 body 是服务器**较短**的文件，而本地镜像是唯一更全的副本；
     * 覆盖它就是不可逆丢数据。只读 `X-File-Version` 拿服务器版本号，body 直接丢掉。
     * "重推本地尾部"是 P5 的事，且必须走 `order_uuid` 差集，不能直接覆盖。
     */
    suspend fun probeAfter409(
        type: String,
        year: Int,
        month: Int?,
        onFull: suspend (Service1Api.FullFileBody) -> Unit,
    ): Service1Api.SyncOutcome
}
