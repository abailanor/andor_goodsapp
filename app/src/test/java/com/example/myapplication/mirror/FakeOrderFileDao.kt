package com.example.myapplication.mirror

import com.example.myapplication.db.ContribAggregate
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderDayRow
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderGroupEntity
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderItemIndexEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.db.OutboxGroupSummary
import com.example.myapplication.db.StatsAggEntity
import com.example.myapplication.db.StatsContribEntity
import com.example.myapplication.db.StatsMismatch
import com.example.myapplication.sync.StatsProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [OrderFileDao] 的内存实现，给 [RoomMirrorStateStoreTest] 用。
 *
 * ## 它照抄了什么、没照抄什么
 *
 * 与 [com.example.myapplication.sync.FakeStockDao] 同一套取舍：
 * 这里对齐的是**每条 SQL 的可观测语义**——
 * `IGNORE` 冲突返回 `-1`、`UPDATE` 返回受影响行数、`SUM()` 在没有行时返回 `NULL`、
 * `markDiverged` 的 SET 列表里**没有** `firstSyncedAt`（所以原值保留）。
 * 这些正是 `RoomMirrorStateStore` 的幂等判据所依赖的东西。
 *
 * **没有**被测到的是 SQL 本身（`substr` 提取 writer、`LIKE`、`GROUP BY`、
 * `deleteContribOfScope` 少写一个 `WHERE` 会怎样）。那部分由 androidTest 的
 * `OrderFileDaoTest` 覆盖，两边刻意断言同一批场景。
 *
 * `applyContribDelta` 与 `archiveFile` 用的是接口里的**真实默认实现**
 * （Room 生成的实现类也继承它们），所以"先 UPDATE 再 INSERT"与
 * "归档时冻结贡献而不删行"这两段编排是被真测到的。
 *
 * ## ⚠ 键一律用 data class / Triple，不拼字符串
 *
 * 拼出来的键在值本身含分隔符时会撞（`writer` 里可以有 `|`），
 * 而撞了之后测试**照样通过** —— 只是断言的东西悄悄变了。
 */
internal class FakeOrderFileDao : OrderFileDao {

    /** `stats_contrib` 的主键。 */
    internal data class CKey(
        val storeKey: String,
        val scope: String,
        val dimKey: String,
        val metric: String,
    )

    val fileStates = LinkedHashMap<Pair<String, String>, FileStateEntity>()
    val orders = LinkedHashMap<Triple<String, String, String>, OrderIndexEntity>()
    val items = mutableListOf<OrderItemIndexEntity>()
    val contrib = LinkedHashMap<CKey, StatsContribEntity>()
    val agg = LinkedHashMap<Triple<String, String, String>, StatsAggEntity>()
    val outbox = LinkedHashMap<Pair<String, String>, OrderOutboxEntity>()
    val groups = LinkedHashMap<Pair<String, String>, OrderGroupEntity>()

    /** 调用顺序日志。用来钉住"索引行插入与贡献增减必须相邻发生"这类顺序不变量。 */
    val ops = mutableListOf<String>()

    private var rowId = 0L

    // ==================== 测试辅助 ====================

    fun contribValue(storeKey: String, scope: String, dimKey: String, metric: String): Long =
        contrib[CKey(storeKey, scope, dimKey, metric)]?.value ?: 0L

    /** 某个 scope 下全部贡献的合计（跨 dimKey）。 */
    fun contribTotal(storeKey: String, scope: String, metric: String): Long =
        contrib.values.filter { it.storeKey == storeKey && it.scope == scope && it.metric == metric }
            .sumOf { it.value }

    fun order(storeKey: String, fileId: String, uuid: String): OrderIndexEntity? =
        orders[Triple(storeKey, fileId, uuid)]

    fun itemCount(storeKey: String, fileId: String, uuid: String): Int =
        items.count { it.storeKey == storeKey && it.fileId == fileId && it.orderUuid == uuid }

    fun piecesOf(storeKey: String, fileId: String, uuid: String): Int =
        items.filter { it.storeKey == storeKey && it.fileId == fileId && it.orderUuid == uuid }
            .sumOf { it.quantity }

    fun outboxState(storeKey: String, uuid: String): String? = outbox[storeKey to uuid]?.state

    // ==================== file_state ====================

    override suspend fun fileState(storeKey: String, fileId: String): FileStateEntity? =
        fileStates[storeKey to fileId]

    override suspend fun allFileStates(storeKey: String): List<FileStateEntity> =
        fileStates.values.filter { it.storeKey == storeKey }
            .sortedWith(compareBy({ it.fileType }, { it.year }, { it.month }))

    override suspend fun upsertFileState(row: FileStateEntity) {
        fileStates[row.storeKey to row.fileId] = row
        ops += "fileState:${row.fileId}=v${row.version}/${row.syncState}"
    }

    override suspend fun markDiverged(storeKey: String, fileId: String, at: Long): Int {
        val k = storeKey to fileId
        // ⚠ firstSyncedAt 不在 SET 列表里，所以 copy 时原样带过去
        val cur = fileStates[k] ?: return 0
        fileStates[k] = cur.copy(
            syncState = "DIVERGED", version = 0, lastStreamId = null,
            checksum = 0, mirrorBytes = 4, lastSyncAttemptAt = at,
        )
        ops += "markDiverged:$fileId"
        return 1
    }

    override suspend fun markArchived(storeKey: String, fileId: String, at: Long): Int {
        val k = storeKey to fileId
        val cur = fileStates[k] ?: return 0
        fileStates[k] = cur.copy(syncState = "ARCHIVED", lastSyncAttemptAt = at)
        ops += "markArchived:$fileId"
        return 1
    }

    override suspend fun touchAttempt(storeKey: String, fileId: String, at: Long): Int {
        val k = storeKey to fileId
        val cur = fileStates[k] ?: return 0
        fileStates[k] = cur.copy(lastSyncAttemptAt = at)
        return 1
    }

    // ==================== order_index ====================

    override suspend fun orderIndex(storeKey: String, fileId: String, orderUuid: String) =
        orders[Triple(storeKey, fileId, orderUuid)]

    override suspend fun orderIndexByStatusOffset(storeKey: String, fileId: String, offset: Long) =
        orders.values.firstOrNull {
            it.storeKey == storeKey && it.fileId == fileId && it.statusOffset == offset
        }

    override suspend fun orderIndexByRemarkOffset(storeKey: String, fileId: String, offset: Long) =
        orders.values.firstOrNull {
            it.storeKey == storeKey && it.fileId == fileId && it.remarkOffset == offset
        }

    override suspend fun insertOrderIndex(row: OrderIndexEntity): Long {
        val k = Triple(row.storeKey, row.fileId, row.orderUuid)
        if (k in orders) return -1L      // OnConflictStrategy.IGNORE
        orders[k] = row
        ops += "insertOrder:${row.orderUuid}"
        return ++rowId
    }

    override suspend fun insertOrderIndexes(rows: List<OrderIndexEntity>) {
        for (r in rows) insertOrderIndex(r)
    }

    override suspend fun deleteOrderIndexesOfFile(storeKey: String, fileId: String): Int {
        val ks = orders.keys.filter { it.first == storeKey && it.second == fileId }
        ks.forEach { orders.remove(it) }
        ops += "deleteOrders:$fileId=${ks.size}"
        return ks.size
    }

    override suspend fun countOrderIndexesOfFile(storeKey: String, fileId: String): Int =
        orders.keys.count { it.first == storeKey && it.second == fileId }

    override suspend fun earliestOrderTs(storeKey: String): Long? {
        // ⚠ `orderTs > 0`：0 表示"时间未知"，算进 MIN 会让下界恒被夹到硬下界
        val ts = orders.values.filter { it.storeKey == storeKey && it.orderTs > 0L }.map { it.orderTs }
        // SQL 的 MIN() 在没有行时是 NULL，不是 0
        return if (ts.isEmpty()) null else ts.min()
    }

    override suspend fun updateOrderStatus(
        storeKey: String,
        fileId: String,
        orderUuid: String,
        status: Int,
        cancelAppliedVersion: Int?,
    ): Int {
        val k = Triple(storeKey, fileId, orderUuid)
        val cur = orders[k] ?: return 0
        orders[k] = cur.copy(status = status, cancelAppliedVersion = cancelAppliedVersion)
        ops += "status:$orderUuid=$status/cav=$cancelAppliedVersion"
        return 1
    }

    override suspend fun updateOrderRemark(
        storeKey: String,
        fileId: String,
        orderUuid: String,
        remark: String?,
    ): Int {
        val k = Triple(storeKey, fileId, orderUuid)
        val cur = orders[k] ?: return 0
        orders[k] = cur.copy(remark = remark)
        ops += "remark:$orderUuid"
        return 1
    }

    // ==================== order_item_index ====================

    override suspend fun insertItems(rows: List<OrderItemIndexEntity>) {
        for (r in rows) {
            // REPLACE：同主键的旧行先删掉
            items.removeAll {
                it.storeKey == r.storeKey && it.fileId == r.fileId &&
                    it.orderUuid == r.orderUuid && it.lineNo == r.lineNo
            }
            items += r
        }
    }

    override suspend fun deleteItemsOfFile(storeKey: String, fileId: String): Int {
        val before = items.size
        items.removeAll { it.storeKey == storeKey && it.fileId == fileId }
        return before - items.size
    }

    override suspend fun sumQuantityOfOrder(storeKey: String, fileId: String, orderUuid: String): Int? {
        val rows = items.filter {
            it.storeKey == storeKey && it.fileId == fileId && it.orderUuid == orderUuid
        }
        // SQL 的 SUM() 在没有任何行时是 NULL，不是 0
        return if (rows.isEmpty()) null else rows.sumOf { it.quantity }
    }

    // ==================== stats_contrib ====================

    override suspend fun addContribDelta(
        storeKey: String,
        scope: String,
        dimKey: String,
        metric: String,
        delta: Long,
    ): Int {
        val k = CKey(storeKey, scope, dimKey, metric)
        val cur = contrib[k] ?: return 0
        contrib[k] = cur.copy(value = cur.value + delta)
        ops += "contrib:$metric${if (delta >= 0) "+" else ""}$delta"
        return 1
    }

    override suspend fun insertContrib(row: StatsContribEntity): Long {
        val k = CKey(row.storeKey, row.scope, row.dimKey, row.metric)
        if (k in contrib) return -1L
        contrib[k] = row
        ops += "contrib:${row.metric}=${row.value}"
        return ++rowId
    }

    override suspend fun insertContribs(rows: List<StatsContribEntity>) {
        for (r in rows) contrib[CKey(r.storeKey, r.scope, r.dimKey, r.metric)] = r
    }

    override suspend fun deleteContribOfScope(storeKey: String, scope: String): Int {
        val ks = contrib.keys.filter { it.storeKey == storeKey && it.scope == scope }
        ks.forEach { contrib.remove(it) }
        ops += "deleteContrib:$scope=${ks.size}"
        return ks.size
    }

    override suspend fun freezeContribOfScope(storeKey: String, scope: String): Int {
        var n = 0
        for ((k, v) in contrib.toMap()) {
            if (k.storeKey == storeKey && k.scope == scope && v.frozen != 1) {
                contrib[k] = v.copy(frozen = 1)
                n++
            }
        }
        return n
    }

    // ==================== order_outbox ====================
    //
    // 与 SQL 对齐的几个细节（`OutboxStore` 的幂等判据全靠它们）：
    //  - `insertOutbox` 是 **ABORT**：撞主键要**抛**，不能悄悄替换（那正是"换掉不可变 payload"的入口）；
    //  - 每个 UPDATE 返回**受影响行数**，`markOutboxPromoted` 的 1/0 是搬桶的唯一授权；
    //  - `deleteOutboxRow` 对 `IN_FLIGHT` / `PROMOTED` 返回 0。

    override suspend fun insertOutbox(row: OrderOutboxEntity): Long {
        val k = row.storeKey to row.orderUuid
        if (k in outbox) throw IllegalStateException("UNIQUE constraint failed: order_outbox ($k)")
        outbox[k] = row
        ops += "outboxInsert:${row.orderUuid}/${row.state}"
        return ++rowId
    }

    override suspend fun insertOutboxRows(rows: List<OrderOutboxEntity>) {
        // 真 Room 的批量插入是"整批一个语句"，任何一行冲突整批都不落地。
        // 这里先全查一遍再插，模拟同一个可观测结果。
        val clash = rows.firstOrNull { (it.storeKey to it.orderUuid) in outbox }
        if (clash != null) throw IllegalStateException("UNIQUE constraint failed: order_outbox (${clash.orderUuid})")
        for (r in rows) insertOutbox(r)
    }

    override suspend fun outboxRow(storeKey: String, orderUuid: String): OrderOutboxEntity? =
        outbox[storeKey to orderUuid]

    override suspend fun outboxByState(storeKey: String, state: String): List<OrderOutboxEntity> =
        outbox.values.filter { it.storeKey == storeKey && it.state == state }.sortedBy { it.createdAt }

    override suspend fun outboxPending(storeKey: String): List<OrderOutboxEntity> =
        outbox.values.filter { it.storeKey == storeKey && it.state != OrderOutboxEntity.PROMOTED }
            .sortedBy { it.createdAt }

    override suspend fun outboxContributingUuids(storeKey: String): List<String> =
        outbox.values
            .filter {
                it.storeKey == storeKey &&
                    it.state != OrderOutboxEntity.PROMOTED && it.state != OrderOutboxEntity.DRAFT
            }
            .map { it.orderUuid }

    override suspend fun outboxRowsOfGroup(storeKey: String, groupId: String): List<OrderOutboxEntity> =
        outbox.values.filter {
            it.storeKey == storeKey && it.groupId == groupId && it.state != OrderOutboxEntity.PROMOTED
        }

    override suspend fun outboxGroupSummary(storeKey: String): List<OutboxGroupSummary> =
        outbox.values
            .filter {
                it.storeKey == storeKey && it.groupId.isNotEmpty() &&
                    it.state != OrderOutboxEntity.PROMOTED && it.state != OrderOutboxEntity.DRAFT
            }
            .groupBy { it.groupId }
            .map { (g, rows) -> OutboxGroupSummary(g, rows.size, rows.sumOf { it.totalCents }) }

    override suspend fun markOutboxInFlight(storeKey: String, orderUuid: String): Int =
        updateOutbox(storeKey, orderUuid, "markInFlight:$orderUuid") {
            // ⚠ `AND state = 'READY'`：两个上传协程抢同一行时只有一个能拿到 1
            if (it.state != OrderOutboxEntity.READY) null else it.copy(state = OrderOutboxEntity.IN_FLIGHT)
        }

    override suspend fun transitionOutbox(
        storeKey: String,
        orderUuid: String,
        expectedState: String,
        toState: String,
        lastError: String?,
    ): Int = updateOutbox(storeKey, orderUuid, "outbox:$orderUuid->$toState") {
        if (it.state != expectedState) null else it.copy(state = toState, lastError = lastError)
    }

    override suspend fun bumpOutboxAttempt(storeKey: String, orderUuid: String, lastError: String?): Int =
        updateOutbox(storeKey, orderUuid, "attempt:$orderUuid") {
            it.copy(attemptCount = it.attemptCount + 1, lastError = lastError)
        }

    override suspend fun markOutboxPromoted(storeKey: String, orderUuid: String): Int =
        updateOutbox(storeKey, orderUuid, "promoted:$orderUuid") {
            if (it.state == OrderOutboxEntity.PROMOTED || it.state == OrderOutboxEntity.DRAFT) null
            else it.copy(state = OrderOutboxEntity.PROMOTED, lastError = null)
        }

    override suspend fun deleteOutboxRow(storeKey: String, orderUuid: String): Int {
        val k = storeKey to orderUuid
        val cur = outbox[k] ?: return 0
        if (cur.state == OrderOutboxEntity.IN_FLIGHT || cur.state == OrderOutboxEntity.PROMOTED) return 0
        outbox.remove(k)
        ops += "outboxDelete:$orderUuid"
        return 1
    }

    override suspend fun outboxBlockedRows(storeKey: String): List<OrderOutboxEntity> =
        outbox.values
            .filter { it.storeKey == storeKey && it.state == OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE }
            .sortedBy { it.createdAt }

    override suspend fun updateOutboxBlockedBarcodes(
        storeKey: String,
        orderUuid: String,
        reason: String,
        barcodesCsv: String?,
    ): Int = updateOutbox(storeKey, orderUuid, "blockedBarcodes:$orderUuid") {
        if (it.state != OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE) null
        else it.copy(blockedReason = reason, blockedBarcodes = barcodesCsv)
    }

    override suspend fun markOutboxBlocked(
        storeKey: String,
        orderUuid: String,
        reason: String,
        barcodesCsv: String?,
    ): Int = updateOutbox(storeKey, orderUuid, "blocked:$orderUuid") {
        if (it.state == OrderOutboxEntity.PROMOTED) null
        else it.copy(
            state = OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE,
            blockedReason = reason,
            blockedBarcodes = barcodesCsv,
        )
    }

    override suspend fun unblockOutbox(storeKey: String, orderUuid: String): Int =
        updateOutbox(storeKey, orderUuid, "unblocked:$orderUuid") {
            if (it.state != OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE) null
            else it.copy(state = OrderOutboxEntity.READY, blockedReason = null, blockedBarcodes = null)
        }

    /** 把一条 UPDATE 的"WHERE 命中才改、返回受影响行数"这套语义收在一处。 */
    private inline fun updateOutbox(
        storeKey: String,
        orderUuid: String,
        op: String,
        transform: (OrderOutboxEntity) -> OrderOutboxEntity?,
    ): Int {
        val k = storeKey to orderUuid
        val cur = outbox[k] ?: return 0
        val next = transform(cur) ?: return 0
        outbox[k] = next
        ops += op
        return 1
    }

    // ==================== order_group ====================
    //
    // `recomputeOrderGroup` 用的是接口里的**真实默认实现**，
    // 所以"成员来自两张表、PROMOTED 不数两遍、空组要删行"这三条编排是被真测到的。

    override suspend fun upsertOrderGroup(row: OrderGroupEntity) {
        groups[row.storeKey to row.groupId] = row
        ops += "group:${row.groupId}=${row.orderCount}/${row.cancelState}"
    }

    override suspend fun upsertOrderGroups(rows: List<OrderGroupEntity>) {
        for (r in rows) upsertOrderGroup(r)
    }

    override suspend fun orderGroup(storeKey: String, groupId: String): OrderGroupEntity? =
        groups[storeKey to groupId]

    override suspend fun allOrderGroups(storeKey: String): List<OrderGroupEntity> =
        groups.values.filter { it.storeKey == storeKey }.sortedByDescending { it.createdAt }

    override suspend fun deleteOrderGroup(storeKey: String, groupId: String): Int {
        ops += "groupDelete:$groupId"
        return if (groups.remove(storeKey to groupId) != null) 1 else 0
    }

    override suspend fun orderIndexesOfGroup(
        storeKey: String,
        fileId: String,
        groupId: String,
    ): List<OrderIndexEntity> =
        orders.values.filter {
            it.storeKey == storeKey && it.fileId == fileId && it.groupId == groupId
        }

    override suspend fun groupIdsOfFile(storeKey: String, fileId: String): List<String> =
        orders.values
            .filter { it.storeKey == storeKey && it.fileId == fileId && it.groupId != null }
            .map { it.groupId!! }
            .distinct()

    // ==================== 诊断 ====================

    override suspend fun countOrdersMissingTs(storeKey: String): Int =
        orders.values.count { it.storeKey == storeKey && it.orderTs == 0L }

    override suspend fun outboxContribSelfCheck(storeKey: String): Long {
        // 与 SQL 一致：两个桶都算（OUTBOX 与 OUTBOX_FAILED）
        val bucket = contrib.values
            .filter {
                it.storeKey == storeKey && it.metric == "amountCents" &&
                    (it.scope == "OUTBOX" || it.scope == "OUTBOX_FAILED")
            }
            .sumOf { it.value }
        val expected = outbox.values
            .filter {
                it.storeKey == storeKey && it.fileType == "B" &&
                    it.state != OrderOutboxEntity.PROMOTED && it.state != OrderOutboxEntity.DRAFT
            }
            .sumOf { it.totalCents }
        return bucket - expected
    }

    override suspend fun statsSelfCheck(storeKey: String): List<StatsMismatch> {
        val grouped = contrib.values.filter { it.storeKey == storeKey }
            .groupBy { it.dimKey to it.metric }
            .mapValues { (_, v) -> v.sumOf { it.value } }
        return grouped.mapNotNull { (k, sum) ->
            val a = agg[Triple(storeKey, k.first, k.second)]?.value ?: 0L
            if (sum == a) null else StatsMismatch(k.first, k.second, sum, a)
        }
    }

    // ==================== UI 读取 ====================
    //
    // 真 Room 的这几个方法返回**热** Flow（表一变就重发）。这里用一次性的 `flow { emit(...) }`
    // 就够：用它的测试断言的是"过滤 / 排序 / 聚合的语义对不对"，不是"变了会不会重发"——
    // 后者是 Room 失效跟踪器的职责，替身去模仿它没有意义。

    override fun observeFileStates(storeKey: String): Flow<List<FileStateEntity>> = flow {
        emit(allFileStates(storeKey))
    }

    override fun observeOrderIndexInRange(
        storeKey: String,
        fromTs: Long,
        toTs: Long,
        writer: String?,
        limit: Int,
    ): Flow<List<OrderIndexEntity>> = flow {
        emit(
            orders.values
                .filter {
                    it.storeKey == storeKey && it.orderTs >= fromTs && it.orderTs < toTs &&
                        (writer == null || it.writer == writer)
                }
                .sortedByDescending { it.orderTs }
                .take(limit)
        )
    }

    override fun observeOutboxPending(storeKey: String): Flow<List<OrderOutboxEntity>> = flow {
        emit(outboxPending(storeKey))
    }

    override fun observeOrderDayRows(storeKey: String): Flow<List<OrderDayRow>> = flow {
        // 与 SQL 一致：`WHERE storeKey = ? AND orderTs > 0`（orderTs = 0 表示时间未知，
        // 画进日历会堆在 1970-01-01 那格上）。
        emit(
            orders.values
                .filter { it.storeKey == storeKey && it.orderTs > 0L }
                .map { OrderDayRow(it.orderTs, it.totalCents, it.status, it.fileId) }
        )
    }

    override suspend fun orderItemsOfUuid(storeKey: String, orderUuid: String): List<OrderItemIndexEntity> =
        items
            .filter { it.storeKey == storeKey && it.orderUuid == orderUuid }
            // SQL 是 ORDER BY fileId, lineNo
            .sortedWith(compareBy({ it.fileId }, { it.lineNo }))

    override fun observeContribInRange(
        storeKey: String,
        fromDate: String,
        toDate: String,
        includeFailed: Boolean,
    ): Flow<List<ContribAggregate>> = flow {
        // ⚠ 与 SQL 保持一致的两点：**不筛 scope**（OUTBOX 与 FILE: 都算进营业额 —— 未上传的单
        // 货已经给客人了，不算的话用户开完单看统计没变，以为丢单），
        // 也**不排除 frozen**（归档只是"不再同步"，不是"不再统计"）。
        emit(
            contrib.values
                .filter {
                    it.storeKey == storeKey &&
                        it.dimKey.take(StatsProjection.DATE_WIDTH) in fromDate..toDate &&
                        // 与 SQL 的 `(:includeFailed = 1 OR scope <> 'OUTBOX_FAILED')` 一致
                        (includeFailed || it.scope != StatsProjection.OUTBOX_FAILED_SCOPE)
                }
                .groupBy { it.dimKey to it.metric }
                .map { (k, rows) -> ContribAggregate(k.first, k.second, rows.sumOf { it.value }) }
        )
    }
}
