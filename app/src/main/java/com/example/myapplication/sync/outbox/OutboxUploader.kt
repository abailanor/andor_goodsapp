package com.example.myapplication.sync.outbox

import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.dto.OrderAddResultDto
import com.example.myapplication.sync.OrderFileSyncEngine
import com.example.myapplication.sync.OrderJsonScanner
import com.example.myapplication.sync.StockLedger
import java.io.IOException

/**
 * outbox 的**唯一出网出口**：把 `READY` 的订单发去 `/add`，并处理"结果未知"。
 *
 * ## 为什么需要这么一个类
 *
 * [OutboxStore] 只管状态与统计，明确不碰网络（"事务里不做文件 I/O，也不做网络"）。
 * 但状态机的四个出口（`takeOff` / `backToReady` / `noteUnknownResult` / `failPermanently`）
 * 全都需要一个"知道每个错误码意味着服务端到底收没收下"的调用方 —— 那就是这里。
 *
 * 分错的代价是**静默且不可恢复**的：服务端 `order_write.lua` 里没有任何 uuid 去重，
 * 把"其实已经收下了"误判成"没收下"就会重推，同一个 `order_uuid` 在服务器文件里出现两行，
 * 镜像比服务器长，此后所有偏移错位 —— 而等长覆写不报错、文件长度不变、校验和自洽，
 * **没有任何机制会发现**。所以本类的错误分类宁可保守：不确定就留在 `IN_FLIGHT`。
 *
 * ## 三条贯穿全类的规则
 *
 * **一、write-ahead 顺序不可调换。** [OutboxStore.takeOff] 返回非 null 才允许发请求。
 *
 * **二、本类从不搬统计桶。** `OUTBOX → FILE:` 的唯一执行者是
 * [com.example.myapplication.mirror.RoomMirrorStateStore.promoteFromOutbox]，
 * 由 [MirrorCommitter.commitAdd] 在同一事务里触发。三条来路（`/add` 响应、SSE 回声、FULL）
 * 都经过那里；在这里再搬一次就是双算。本类只负责**触发**提交。
 *
 * **三、"结果未知"的重试计数只能由 [recoverInFlight] 加。**
 * `noteUnknownResult` 会 bump `attemptCount`，但**判 `MAX_ATTEMPTS` 的动作不在这里** ——
 * 必须先同步该 fileId、在 `order_index` 里确认服务端确实没有这一单，才有资格放弃它。
 * 否则用户看到"上传失败"就删掉重开一张，而服务器上那张还在 → 重复订单 + 重复扣库存。
 */
class OutboxUploader(
    private val api: OrderFileApi,
    private val store: OutboxStore,
    private val committer: MirrorCommitter,
    /**
     * "把这个 fileId 追平"。生产实现传 `engine::sync`。
     *
     * 只用于 [recoverInFlight]：把镜像追平之后，才能在 `order_index` 里查
     * "服务端到底有没有这一单"。
     *
     * ## 为什么是函数参数而不是直接依赖 [OrderFileSyncEngine]
     *
     * 与 [OrderFileApi] 同一个理由：engine 是 `final` 的，项目又没引 Mockito。
     * 而 `recoverInFlight` 的**每个分支**都由 sync 的返回值决定 ——
     * 判错一次就是"服务端其实收下了，却被当成没收下而重推" → 同 uuid 两行 → 镜像比服务器长。
     * 这条路径必须能在 JVM 上逐分支钉住。
     */
    private val syncFile: suspend (storeKey: String, fileId: String) -> OrderFileSyncEngine.Outcome,
    /** **只读**：查 `order_outbox` 的当前状态与 `order_index`。写入一律经 [store] / [committer]。 */
    private val dao: OrderFileDao,
    /** `/add` 响应的 `stockChanges` 是服务器那一刻的绝对库存，用它做一次不对称对账。 */
    private val stockLedger: StockLedger,
    /**
     * 本机设备 id。[uploadReady] 只自动上传 `originDevice` 等于它的行。
     *
     * ⚠ 空串也算本机：那是 v5 迁移之前的老行，本来就是这台设备自己开的。
     */
    private val deviceId: () -> String = { "" },
    private val audit: (String) -> Unit = {},
) {

    companion object {
        /** 一次批量上传的默认条数。与 `SyncScheduler.DEFAULT_CATCHUP_BUDGET` 无关，别混用。 */
        const val DEFAULT_BATCH = 20
    }

    // ==================== 结果 ====================

    /**
     * 一笔上传的结果。**每个分支都要求调用方做点什么**，所以不用布尔值
     * （与 [OrderFileSyncEngine.Outcome] 同一个理由）。
     */
    sealed interface UploadResult {

        /** 服务端收下了、镜像已落地、贡献已搬到 `FILE:` 桶、行已 `PROMOTED`。 */
        data class Promoted(val orderUuid: String, val version: Int) : UploadResult

        /**
         * 行不在 `READY`（被另一个上传协程抢走、已经提升、或已经失败）。**什么都没发生**。
         *
         * [state] 是查到的当前状态；`MISSING` 表示这一行根本不存在。
         */
        data class Skipped(val orderUuid: String, val state: String) : UploadResult

        /**
         * **确定没提交**（429 / 401 / 403），已退回 `READY` 等下一轮。
         *
         * @param abortBatch 这一批剩下的也别发了。429 的桶**按 IP 共享**（整店一起被锁死，
         *        且 Guava 平滑桶几乎没有突发额度）、401 说明 token 已死（后续必失败）、
         *        403 说明缺 `ORDER_A`/`ORDER_B` 权限（重试无意义，权限是每请求实时读 Redis 的）。
         *        与 `SyncScheduler.runBatch` 中止整批的理由完全一致。
         */
        data class RetryLater(val orderUuid: String, val error: String, val abortBatch: Boolean) : UploadResult

        /**
         * **结果未知**（超时 / 连接被切 / 500 / 响应形状不对），或服务端收下了但镜像没能落地。
         * 行**留在 `IN_FLIGHT`**，等 [recoverInFlight] 判定。
         *
         * ⚠ 绝不能退回 `READY`：退回就等于授权重推 → 重复订单。
         */
        data class Unknown(val orderUuid: String, val error: String) : UploadResult

        /**
         * 重推也不会有不同结果（413 请求体过大 / 400 参数不合法 / 404 接口不存在），
         * 或本地 payload 已经不可信。交人工，UI 显示 `lastError` 并允许"删掉这一单"。
         *
         * 413 的正确反应是**拆单**（发生在 [OutboxStore.enqueue]），不是重试 ——
         * 同样大小的 body 只会再拿一个 413，白烧三次退避。
         */
        data class FailedPermanent(val orderUuid: String, val error: String) : UploadResult
    }

    /** 一次 [uploadReady] 的汇总。 */
    data class BatchReport(
        val promoted: Int = 0,
        val skipped: Int = 0,
        val retryLater: Int = 0,
        val unknown: Int = 0,
        val failedPermanent: Int = 0,
        /** 因 429 / 401 / 403 提前中止，[remaining] 条没发。 */
        val aborted: Boolean = false,
        val remaining: Int = 0,
    ) {
        val attempted: Int get() = promoted + skipped + retryLater + unknown + failedPermanent
    }

    /** 一次 [recoverInFlight] 对单笔 `IN_FLIGHT` 行的判定。 */
    sealed interface Recovery {

        /** 同步后在 `order_index` 里找到了它，且 outbox 行已 `PROMOTED`。当成功。 */
        data class Confirmed(val orderUuid: String) : Recovery

        /** 同步后确认服务端**没有**它，于是重发了一次 `/add`。 */
        data class Resent(val orderUuid: String, val result: UploadResult) : Recovery

        /**
         * 无法判定（同步本身失败了：409 / 429 / 401 / 断网）。行**原样留在 `IN_FLIGHT`**，
         * 下次启动或重连再试。这是保守的那一侧：猜错会重复订单，等一等只会晚一点。
         */
        data class StillUnknown(val orderUuid: String, val reason: String) : Recovery

        /**
         * 确认服务端没有它，但未知结果的尝试次数已经到顶 → `FAILED_PERMANENT` 交人工。
         *
         * 只有走过"同步 + 在 `order_index` 里没找到"这条路才允许到这里，
         * 所以放弃是**有据的**，不会把一张其实已经上传成功的单标成失败。
         */
        data class GaveUp(val orderUuid: String, val reason: String) : Recovery

        /**
         * ⚠ **不变量被破坏**：`order_index` 里有这一行（说明 `FILE:` 桶已经算过它了），
         * 而 outbox 行却不是 `PROMOTED`（说明 `OUTBOX` 桶还算着它）→ **营业额永久多算一笔**。
         *
         * `promoteFromOutbox` 的三条来路（`/add` 响应、SSE 回声、FULL）都该覆盖到它，
         * 走到这里意味着有一条漏了。本类**不自己去搬桶** —— 那会造出第二个搬桶点，
         * 而两处的幂等判据不同，结果要么双算要么漏算，两种都静默。
         * 只能大声记审计 + 交给 UI 提示用户跑一次「校验镜像」。
         */
        data class PromoteMissed(val orderUuid: String, val state: String?) : Recovery
    }

    // ==================== 上传 ====================

    /**
     * 上传一笔。**不抛**协议/网络错误（一律折进 [UploadResult]）。
     *
     * @param ownerName **店主**用户名，用来拼 fileId。⚠ 不是 `row.writer`（那是登录名，
     *        员工开单时两者不同）—— 见 [OutboxContribution.partsOf] 的警告。
     */
    suspend fun uploadOne(storeKey: String, ownerName: String, orderUuid: String): UploadResult {
        // CAS：`AND state = 'READY'`。返回 null 就是没抢到（或行不在 READY）。
        val row = store.takeOff(storeKey, orderUuid)
            ?: return UploadResult.Skipped(
                orderUuid,
                dao.outboxRow(storeKey, orderUuid)?.state ?: "MISSING",
            )
        return send(storeKey, ownerName, row)
    }

    /**
     * 按 `createdAt` 顺序批量上传**本机创建的** `READY` 行。
     *
     * 顺序有意义：先开的单先上传，服务端文件里的行顺序就与开单顺序一致，
     * 对账时"文件尾部那几行"就是"最近开的那几单"。
     *
     * ## ⚠ 两道防跨设备重复上传的闸门
     *
     * 离线时两台设备会通过热点互相同步未上传的订单，于是双方手上都有同一批 uuid。
     * 而服务端 `order_write.lua` 里**没有任何 uuid 去重**：重复上传返回 200、
     * 追加为新行、**库存再扣一次**，镜像比服务器长、此后所有偏移静默错位 ——
     * 等长覆写不报错、文件长度不变、校验和自洽，没有任何机制会发现。
     *
     * **闸门一：只挑本机单。** 别的设备传来的行 `originDevice` 是对方 id，
     * 这里直接跳过 —— 正常情况下创建者自己会上传它。要接管得走
     * [uploadOne]（「订单」页用户明确勾选时调那条路）。
     *
     * **闸门二：上传前先同步一次。** 这一步才是真正关掉竞态的：
     * 如果对方已经把某几笔传上去了，同步会把它们建进 `order_index` →
     * `applyAdded` → `promoteFromOutbox` → 本地那几行**变成 PROMOTED**，
     * 于是下面重读时就看不到它们了。
     *
     * ⚠ 闸门二不是绝对的：两台设备**同时**同步、又都还没看到对方的上传时，
     * 仍然会双双发出去。要彻底关死需要服务端支持幂等键（uuid 唯一约束），
     * 而它现在没有。所以「订单」页把外部单单独分组、只允许手动上传 ——
     * 让人而不是代码来做最后那个判断。
     */
    suspend fun uploadReady(
        storeKey: String,
        ownerName: String,
        limit: Int = DEFAULT_BATCH,
    ): BatchReport {
        require(limit > 0) { "limit 必须为正，收到 $limit" }
        val mine = deviceId()

        // outboxByState 的 SQL 已带 ORDER BY createdAt。
        val own = dao.outboxByState(storeKey, OrderOutboxEntity.READY).filter { it.isMine(mine) }
        if (own.isEmpty()) return BatchReport()

        // 闸门二：先把涉及的每个文件追平。按 fileId 分组，一个文件只同步一次
        // （一次 FULL 可能是几 MB，而同一个月的十笔单共享同一个文件）。
        for (fileId in own.map { OutboxContribution.partsOf(ownerName, it).fileId }.distinct()) {
            val outcome = runCatching { syncFile(storeKey, fileId) }.getOrElse {
                audit("上传前同步抛异常 fileId=$fileId：${it.message}（继续上传，竞态窗口会变大）")
                null
            }
            // ⚠ 引擎**不抛异常**，失败是折进 Outcome 返回的（见 OrderFileSyncEngine.sync
            // 的注释）。所以只看 runCatching 的 onFailure 等于永远不会告警 ——
            // 而"没追平就上传"正是竞态窗口变大的那一刻，必须记下来。
            if (outcome != null &&
                outcome !is OrderFileSyncEngine.Outcome.UpToDate &&
                outcome !is OrderFileSyncEngine.Outcome.Applied &&
                outcome !is OrderFileSyncEngine.Outcome.Rebuilt
            ) {
                audit("上传前同步没追平 fileId=$fileId：$outcome（继续上传，竞态窗口会变大）")
            }
        }

        // ⚠ 同步之后**必须重读**：刚被 promote 成 PROMOTED 的行不该再发一次。
        val rows = dao.outboxByState(storeKey, OrderOutboxEntity.READY).filter { it.isMine(mine) }
        if (rows.isEmpty()) {
            audit("上传前同步之后 ${own.size} 笔本机单都已被别的设备传上去了，无需重发")
            return BatchReport(promoted = own.size)
        }
        val batch = if (rows.size > limit) rows.subList(0, limit) else rows

        var report = BatchReport(remaining = rows.size - batch.size)
        for ((i, row) in batch.withIndex()) {
            val r = uploadOne(storeKey, ownerName, row.orderUuid)
            report = when (r) {
                is UploadResult.Promoted -> report.copy(promoted = report.promoted + 1)
                is UploadResult.Skipped -> report.copy(skipped = report.skipped + 1)
                is UploadResult.Unknown -> report.copy(unknown = report.unknown + 1)
                is UploadResult.FailedPermanent ->
                    report.copy(failedPermanent = report.failedPermanent + 1)
                is UploadResult.RetryLater -> {
                    val left = batch.size - i - 1 + report.remaining
                    if (r.abortBatch && left > 0) {
                        audit("批量上传中止（${r.error}）：还有 $left 条没发，留给下一轮")
                        return report.copy(retryLater = report.retryLater + 1, aborted = true, remaining = left)
                    }
                    report.copy(retryLater = report.retryLater + 1)
                }
            }
        }
        return report
    }

    /**
     * 手动上传用户**明确勾选**的那几笔（「订单」页）。
     *
     * ## ⚠ 这里也必须先同步，而且比 [uploadReady] 更要紧
     *
     * 勾选清单里最典型的就是"接管别的设备传来的单"。而那笔单**很可能创建它的设备
     * 已经上传过了** —— 热点同步只发生在两台设备都离线的时候，之后谁先上线谁就传了。
     * 不同步就直接发，服务端不查重 → 同一个 uuid 两行 → 库存扣两次 → 镜像比服务器长。
     *
     * 同步之后重读每一行的状态：已经变成 `PROMOTED` 的就跳过（`takeOff` 的 CAS
     * 也会挡住，但那样会白记一次"Skipped"，用户看到"跳过 3 笔"会以为出了什么问题）。
     */
    suspend fun uploadSelected(
        storeKey: String,
        ownerName: String,
        orderUuids: List<String>,
    ): List<UploadResult> {
        if (orderUuids.isEmpty()) return emptyList()
        val mine = deviceId()

        val rows = orderUuids.mapNotNull { dao.outboxRow(storeKey, it) }
        // 只有 READY 能起飞；其余状态先挑出来，好给用户一句准确的交代
        val ready = rows.filter { it.state == OrderOutboxEntity.READY }

        for (fileId in ready.map { OutboxContribution.partsOf(ownerName, it).fileId }.distinct()) {
            val outcome = runCatching { syncFile(storeKey, fileId) }.getOrElse {
                audit("接管上传前同步抛异常 fileId=$fileId：${it.message}（继续上传，竞态窗口会变大）")
                null
            }
            if (outcome != null &&
                outcome !is OrderFileSyncEngine.Outcome.UpToDate &&
                outcome !is OrderFileSyncEngine.Outcome.Applied &&
                outcome !is OrderFileSyncEngine.Outcome.Rebuilt
            ) {
                audit("接管上传前同步没追平 fileId=$fileId：$outcome（继续上传，竞态窗口会变大）")
            }
        }

        val out = ArrayList<UploadResult>(orderUuids.size)
        for (uuid in orderUuids) {
            // ⚠ 同步之后**重读**：对方可能已经传上去了，本地那行已经被 promoteFromOutbox
            // 翻成 PROMOTED。这时候再走 takeOff 只会拿到一个 Skipped，用户看不懂。
            val fresh = dao.outboxRow(storeKey, uuid)
            when {
                fresh == null -> {
                    audit("接管上传：$uuid 已不在 outbox 里")
                    out += UploadResult.Skipped(uuid, "MISSING")
                }
                fresh.state == OrderOutboxEntity.PROMOTED -> {
                    // 正是我们要防的那一幕：同步之后发现别人已经传了
                    audit("接管上传：$uuid 同步后发现已经在服务器上了，跳过")
                    out += UploadResult.Skipped(uuid, OrderOutboxEntity.PROMOTED)
                }
                fresh.state != OrderOutboxEntity.READY -> {
                    audit("接管上传：$uuid 处于 ${fresh.state}，只有 READY 能起飞")
                    out += UploadResult.Skipped(uuid, fresh.state)
                }
                // 外部单在这里是允许上传的 —— 用户一笔一笔勾出来的，那就是"接管"的明确意图。
                // 闸门只加在 uploadReady（自动批量）上。
                //
                // ⚠ 必须走 uploadOne 而不是直接 send：uploadOne 里的 takeOff 是 write-ahead，
                // 先把行置 IN_FLIGHT 并落盘**再**发请求。跳过它的话，请求发出去之后进程被杀，
                // 重启后这一行还是 READY → 再发一次 → 服务端不查重 → 同 uuid 两行。
                else -> {
                    if (fresh.originDevice.isNotEmpty() && fresh.originDevice != mine) {
                        audit("接管上传：$uuid 原本是设备 ${fresh.originDevice.take(8)} 开的")
                    }
                    out += uploadOne(storeKey, ownerName, uuid)
                }
            }
        }
        return out
    }

    // ==================== 崩溃恢复 ====================

    /**
     * 处理所有 `IN_FLIGHT` 的行：先同步它将要落进的那个 fileId，再据 `order_index` 判定。
     *
     * 调用时机：**启动后**、**重连后**、以及每次 [uploadReady] 之前（上一次进程被杀
     * 留下的行不会被 `outboxByState(READY)` 选中，不先恢复它们就永远卡在 `IN_FLIGHT`）。
     *
     * 按 fileId 分组，每个 fileId **只同步一次** —— 一次 FULL 可能是几 MB，
     * 而同一个月的十笔单共享同一个文件。
     */
    suspend fun recoverInFlight(storeKey: String, ownerName: String): List<Recovery> {
        val rows = dao.outboxByState(storeKey, OrderOutboxEntity.IN_FLIGHT)
        if (rows.isEmpty()) return emptyList()

        val out = ArrayList<Recovery>(rows.size)
        for ((fileId, group) in rows.groupBy { OutboxContribution.partsOf(ownerName, it).fileId }) {
            val outcome = syncFile(storeKey, fileId)
            when (outcome) {
                // 镜像已追平（或本来就是最新的）：`order_index` 现在可信。
                is OrderFileSyncEngine.Outcome.UpToDate,
                is OrderFileSyncEngine.Outcome.Applied,
                is OrderFileSyncEngine.Outcome.Rebuilt ->
                    group.forEach { out += resolveOne(storeKey, ownerName, fileId, it.orderUuid) }

                // 归档是终态：本地停止同步该月，UI 该月置灰、不允许再往里开单。
                // 重推会让服务端按需重建一个已归档的文件（`ensureFileBlocking`），
                // 而本地再也不会同步它 → 只有这台设备知道那份数据。所以直接交人工。
                is OrderFileSyncEngine.Outcome.Archived -> group.forEach {
                    val msg = "该月已归档（fileId=$fileId），不能再上传"
                    store.failPermanently(storeKey, ownerName, it.orderUuid, msg)
                    audit("IN_FLIGHT 恢复：$msg uuid=${it.orderUuid}")
                    out += Recovery.GaveUp(it.orderUuid, msg)
                }

                // 其余（SERVER_BEHIND / 401 / 429 / 断网）都无法判定服务端有没有它。
                else -> {
                    val reason = outcome.toString()
                    audit("IN_FLIGHT 恢复受阻 fileId=$fileId：$reason（${group.size} 笔留在 IN_FLIGHT）")
                    group.forEach { out += Recovery.StillUnknown(it.orderUuid, reason) }
                }
            }
        }
        return out
    }

    private suspend fun resolveOne(
        storeKey: String,
        ownerName: String,
        fileId: String,
        orderUuid: String,
    ): Recovery {
        if (dao.orderIndex(storeKey, fileId, orderUuid) != null) {
            // 服务端有它，而且同步已经把它建进索引了 → applyAdded / commitFull 里的
            // promoteFromOutbox 应该已经在同一事务把行翻成 PROMOTED 并搬走了贡献。
            val state = dao.outboxRow(storeKey, orderUuid)?.state
            return if (state == OrderOutboxEntity.PROMOTED) {
                Recovery.Confirmed(orderUuid)
            } else {
                audit("⚠ 不变量被破坏：order_index 里有 $orderUuid（fileId=$fileId）但 outbox 行是 $state。" +
                    "FILE: 桶与 OUTBOX 桶现在各算了一遍这笔钱 → 营业额多算。请跑一次「校验镜像」")
                Recovery.PromoteMissed(orderUuid, state)
            }
        }

        // 服务端确实没有它 → 现在才有资格重推或放弃。重读一行拿最新 attemptCount。
        val row = dao.outboxRow(storeKey, orderUuid) ?: return Recovery.StillUnknown(orderUuid, "行已不存在")
        if (row.attemptCount >= OrderOutboxEntity.MAX_ATTEMPTS) {
            val msg = "结果未知的尝试已达 ${row.attemptCount} 次，且同步后服务端仍无此单"
            store.failPermanently(storeKey, ownerName, orderUuid, msg)
            audit("IN_FLIGHT 放弃 uuid=$orderUuid：$msg")
            return Recovery.GaveUp(orderUuid, msg)
        }
        // 行已经是 IN_FLIGHT，write-ahead 早已满足，所以**不能再走 takeOff**
        // （它的 CAS 要求 READY，会返回 null 而让这一单永远卡住）。直接发。
        return Recovery.Resent(orderUuid, send(storeKey, ownerName, row))
    }

    // ==================== 内部 ====================

    /**
     * 这一行是不是本机创建的。
     *
     * ⚠ 空串**算本机**：那是 v5 迁移之前的老行，本来就是这台设备自己开的、
     * 从来没有被别人同步走过。把空串当"外部"的话，升级之后用户自己开的单
     * 会全部变成"来自其他设备"、永远不自动上传，而他明明记得是自己开的。
     */
    private fun OrderOutboxEntity.isMine(myDeviceId: String): Boolean =
        originDevice.isEmpty() || originDevice == myDeviceId

    /**
     * 真正发 `/add` 并落地。**调用方必须保证行已经处于 `IN_FLIGHT`**（write-ahead）。
     */
    private suspend fun send(storeKey: String, ownerName: String, row: OrderOutboxEntity): UploadResult {
        val uuid = row.orderUuid
        val parts = OutboxContribution.partsOf(ownerName, row)
        val fileId = parts.fileId
        val payload = row.payload

        val off = OrderJsonScanner.offsets(payload)
        if (off == null) {
            // payload 是 enqueue 时由 StableOrderJson 生成、且当时自检过的字节。
            // 扫不出偏移说明库里的 BLOB 已经坏了 —— 绝不能发出去：服务端会用它自己的
            // indexOf 兜底算偏移，两边不一致就是静默错位。
            val msg = "本地 payload 扫不出偏移，拒绝上传（BLOB 可能已损坏）"
            audit("⚠ $msg uuid=$uuid fileId=$fileId")
            store.failPermanently(storeKey, ownerName, uuid, msg)
            return UploadResult.FailedPermanent(uuid, msg)
        }

        val res = try {
            api.addOrder(row.fileType, row.year, parts.monthOrNull, String(payload, Charsets.UTF_8))
        } catch (e: ApiError) {
            return onFailure(storeKey, ownerName, uuid, e)
        }

        // ---- 响应校验：任何一处对不上都不落地 ----
        val serverUuid = res.orderUuid
        if (serverUuid != null && serverUuid != uuid) {
            // 服务端回显了另一个 uuid：要么响应解析错位，要么请求串了。
            // 落地就是把一笔订单记到别人名下，且偏移全是错的。
            val msg = "/add 响应的 order_uuid=$serverUuid 与本地 $uuid 不符"
            audit("⚠ $msg fileId=$fileId —— 留在 IN_FLIGHT，不落地")
            store.noteUnknownResult(storeKey, uuid, msg)
            return UploadResult.Unknown(uuid, msg)
        }
        if (serverUuid == null) {
            val msg = "/add 响应缺 order_uuid"
            audit("⚠ $msg fileId=$fileId —— 留在 IN_FLIGHT（服务端很可能已经收下了）")
            store.noteUnknownResult(storeKey, uuid, msg)
            return UploadResult.Unknown(uuid, msg)
        }

        val uuidOffset = res.uuidOffset
        val statusOffset = res.statusOffset
        val version = res.version
        if (uuidOffset == null || statusOffset == null || version == null) {
            val msg = "/add 响应缺偏移或版本号（uuidOffset=$uuidOffset statusOffset=$statusOffset version=$version）"
            audit("⚠ $msg fileId=$fileId —— 留在 IN_FLIGHT，等一次 FULL 用推算偏移重建")
            store.noteUnknownResult(storeKey, uuid, msg)
            return UploadResult.Unknown(uuid, msg)
        }

        // 服务端回的 fileId 是**它**把字节写进了哪个文件的权威说法。
        // 对不上说明本地存的 ownerName 是错的（员工登录时误存了 username），
        // 此时重推会不断往一个本地永不同步的文件里追加 → 必须立刻停下交人工。
        val serverFileId = res.fileId
        if (serverFileId != null && serverFileId != fileId) {
            val msg = "服务端把订单写进了 $serverFileId，而本地按 ownerName 算出的是 $fileId"
            audit("⚠⚠ $msg uuid=$uuid —— 极可能 ownerName 存成了登录名。" +
                "不再重推（会不断追加到一个本地永不同步的文件里），转 FAILED_PERMANENT 交人工")
            store.failPermanently(storeKey, ownerName, uuid, msg)
            return UploadResult.FailedPermanent(uuid, msg)
        }

        // 偏移交叉校验：两边独立实现，不一致就说明有一方错了，而错的一方会让
        // 此后每次取消都打在别的订单的字节上。它很便宜，能抓住的 bug 极贵。
        val serialized = SerializedOrder(
            payload = payload,
            uuidValueByteIndex = off.uuidValueIndex,
            remarkValueByteIndex = off.remarkValueIndex,
            statusValueByteIndex = off.statusIndex,
        )
        try {
            StableOrderJson.verifyAgainstServer(serialized, uuidOffset, statusOffset)
        } catch (e: IllegalStateException) {
            val msg = "偏移交叉校验失败：${e.message}"
            audit("⚠⚠ $msg uuid=$uuid fileId=$fileId —— 不落地，留在 IN_FLIGHT。" +
                "FULL 重建会用本地扫描重算偏移，若扫描本身错了则 §7.2 硬门会失败")
            store.noteUnknownResult(storeKey, uuid, msg)
            return UploadResult.Unknown(uuid, msg)
        }

        // ---- 落地：commitAdd → applyAdded → promoteFromOutbox（翻 PROMOTED + 搬桶）----
        val committed = try {
            committer.commitAdd(
                storeKey = storeKey,
                fileId = fileId,
                orderUuid = uuid,
                msgVersion = version,
                payloadBytes = payload,
                uuidValueByteIndex = off.uuidValueIndex,
                remarkValueByteIndex = off.remarkValueIndex,
                serverUuidOffset = uuidOffset,
                serverStatusOffset = statusOffset,
                newStreamId = res.streamId,
            )
        } catch (e: IOException) {
            val msg = "服务端已收下但镜像写入失败：${e.message}"
            audit("⚠ $msg uuid=$uuid fileId=$fileId —— 留在 IN_FLIGHT，下次同步走 FULL 自愈")
            store.noteUnknownResult(storeKey, uuid, msg)
            return UploadResult.Unknown(uuid, msg)
        }

        return when (committed) {
            is MirrorCommitter.Result.Diverged -> {
                val msg = "服务端已收下但镜像被判分歧：${committed.reason}"
                audit("⚠ $msg uuid=$uuid 隔离到=${committed.quarantinedTo} —— " +
                    "留在 IN_FLIGHT，游标已归零，FULL 会重建并提升")
                store.noteUnknownResult(storeKey, uuid, msg)
                UploadResult.Unknown(uuid, msg)
            }
            is MirrorCommitter.Result.Committed -> {
                reconcileStock(storeKey, uuid, res)
                UploadResult.Promoted(uuid, committed.version)
            }
        }
    }

    /**
     * 错误分类。**判据是"服务端到底收没收下"，不是"这个错误严不严重"。**
     *
     * | 错误 | 收下了吗 | 动作 |
     * |---|---|---|
     * | 429 / 401 / 403 | **确定没有**（请求根本没进到写文件那一步） | 退回 `READY`，**不计**尝试次数 |
     * | 413 / 400 / 404 | 确定没有 | `FAILED_PERMANENT`（重推不会有不同结果） |
     * | 超时 / 连接被切 / 500 / 响应形状不对 | **不知道** | 留在 `IN_FLIGHT` |
     *
     * ⚠ 429 与鉴权失败**不计尝试次数**：那不是这一单的问题，
     * 计了的话一次早上的重登风暴就能把全店的单烧进 `FAILED_PERMANENT`。
     *
     * ⚠ `MalformedResponse` 必须当"不知道"，不能当"没收下"——
     * 它是 **200 之后**解析失败，服务端极可能已经写进文件了。
     */
    private suspend fun onFailure(
        storeKey: String,
        ownerName: String,
        uuid: String,
        e: ApiError,
    ): UploadResult {
        val msg = e.message ?: e.javaClass.simpleName
        return when (e) {
            is ApiError.RateLimited -> retry(storeKey, uuid, msg, abortBatch = true)
            is ApiError.Unauthorized -> retry(storeKey, uuid, "需要重新登录：$msg", abortBatch = true)
            is ApiError.Forbidden -> retry(storeKey, uuid, "权限不足：$msg", abortBatch = true)

            is ApiError.PayloadTooLarge -> giveUp(storeKey, ownerName, uuid, "请求体超 20KB，应拆单而不是重试：$msg")
            is ApiError.BadRequest -> giveUp(storeKey, ownerName, uuid, "服务端拒绝（400）：$msg")
            // /add 的 404 只可能是接口路径不存在（部署/反代错了），重推无用。
            is ApiError.NotFound -> giveUp(storeKey, ownerName, uuid, "接口不存在（404）：$msg")

            is ApiError.ServerError -> {
                // 500 的响应体只有一个追踪号。连时间一起记，否则事后对不上服务端日志。
                audit("/add 500 uuid=$uuid traceId=${e.traceId} msg=${e.msg}")
                unknown(storeKey, uuid, "服务端异常（追踪号 ${e.traceId}）")
            }
            is ApiError.Network -> unknown(storeKey, uuid, "网络失败: ${e.message}")
            is ApiError.MalformedResponse -> unknown(storeKey, uuid, "响应形状错误: ${e.msg}")
            // /add 不会返回 409（409 只在 sync），真收到了就按"不知道"保守处理。
            is ApiError.Conflict -> unknown(storeKey, uuid, "意外收到 409: ${e.msg}")
            is ApiError.HttpOther -> unknown(storeKey, uuid, "HTTP ${e.code}: ${e.msg}")
        }
    }

    private suspend fun retry(storeKey: String, uuid: String, error: String, abortBatch: Boolean): UploadResult {
        store.backToReady(storeKey, uuid, error, countAttempt = false)
        return UploadResult.RetryLater(uuid, error, abortBatch)
    }

    private suspend fun giveUp(
        storeKey: String,
        ownerName: String,
        uuid: String,
        error: String,
    ): UploadResult {
        audit("/add 永久失败 uuid=$uuid：$error")
        store.failPermanently(storeKey, ownerName, uuid, error)
        return UploadResult.FailedPermanent(uuid, error)
    }

    private suspend fun unknown(storeKey: String, uuid: String, error: String): UploadResult {
        // 留在 IN_FLIGHT，只记一次尝试。判定与放弃都交给 recoverInFlight。
        store.noteUnknownResult(storeKey, uuid, error)
        return UploadResult.Unknown(uuid, error)
    }

    /**
     * 用 `/add` 响应里的绝对库存做一次对账。
     *
     * ## ⚠ 对账规则必须**不对称**
     *
     * [StockLedger.reconcile] 已经实现了它：本地偏高（`effectiveStock > newStock`）是正常的
     * ——别的设备此后又卖了货，本地还没同步到；本地偏低才是算错了，会被绝对值自愈。
     * 对称地写（`!=` 就告警）会让每次正常的多设备使用都报一次假警，
     * 用户三次之后就再也不看告警了。
     *
     * 注意 `/add` **不写** `stream:stock`（服务端 `order_write.lua:83-87`），
     * 所以这是这笔卖出唯一的绝对观测来源。
     */
    private suspend fun reconcileStock(storeKey: String, uuid: String, res: OrderAddResultDto) {
        val changes = res.stockChanges ?: return
        for (c in changes) {
            val barcode = c.barcode
            val newStock = c.newStock
            if (barcode == null || newStock == null) {
                audit("/add 的 stockChanges 有一条缺字段（barcode=$barcode newStock=$newStock）uuid=$uuid，跳过")
                continue
            }
            when (val r = stockLedger.reconcile(storeKey, StockLedger.DedupKeys.orderAdd(uuid, barcode), barcode, newStock)) {
                is StockLedger.Result.LOCAL_TOO_LOW -> audit(
                    "库存对账：$barcode 本地偏低，已按服务器绝对值 $newStock 自愈（healed=${r.healed}）uuid=$uuid"
                )
                else -> Unit
            }
        }
    }
}
