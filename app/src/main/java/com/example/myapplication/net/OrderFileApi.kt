package com.example.myapplication.net

import com.example.myapplication.net.dto.OrderAddResultDto
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
 * `/add` 在 P5 抽进来了（[addOrder]），因为 `OutboxUploader` 的每个错误分支都必须能测：
 * 分类错一次就是"服务端其实收下了、客户端退回 READY 重推" → 同一个 uuid 在服务器文件里
 * 出现两行（服务端不查重）→ 镜像比服务器长 → 此后所有偏移静默错位。
 *
 * `/patch` **仍然不抽**：它属于取消/改备注路径，那边写到了再按真实需要抽。
 *
 * ## 错误约定
 *
 * 与 [ProductApi] 一致：失败一律**抛 [ApiError]**，绝不返回 null 或空集合来表示失败。
 * "同步失败"与"同步成功但没有增量"必须能区分，否则引擎会把网络故障当成 LATEST，
 * 游标停在原地却以为已经追平 —— 之后统计少一笔，且没有任何报错。
 */
interface OrderFileApi {

    /**
     * `POST /api/orderfiles/add?type=&year=&month=`，body 是**裸订单 JSON**。
     *
     * @param payloadJson 由 `StableOrderJson` 生成的字节串，**必须是 outbox 里存的那一份**
     *        （`order_outbox.payload` 一次写入不可变）。重新序列化会让字节变掉 → 偏移变掉。
     *
     * ## ⚠ 调用之前必须已把 outbox 行置 `IN_FLIGHT` 并落盘
     *
     * write-ahead 是防重复订单的**唯一**手段：服务端 `order_write.lua` 里没有任何 uuid 去重，
     * 重复上传同一 uuid 返回 **200**、追加为新行、版本 +1、**库存再扣一次**。
     * 先发请求再落盘的话，"请求已出去但本地不记得"这个窗口里进程被杀 → 重启后行还是 READY
     * → 再发一次 → 服务器文件里同一个 uuid 两行 → 镜像比服务器长 → 此后所有偏移静默错位。
     *
     * ## 实现方必须走**不重试**的 client
     *
     * OkHttp 的自动重试会在"结果未知"时把同一笔订单发两遍，而服务端不查重。
     *
     * @return 服务端算出的权威偏移（`uuidOffset` / `statusOffset`）与新版本号。
     *         `remarkOffset` **不在响应里**，要按 `uuidOffset + (r - u)` 自己推算。
     */
    suspend fun addOrder(
        type: String,
        year: Int,
        month: Int?,
        payloadJson: String,
    ): OrderAddResultDto

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
