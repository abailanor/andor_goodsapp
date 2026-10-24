package com.example.myapplication.mirror

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderItemIndexEntity
import com.example.myapplication.db.StatsContribEntity
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.StatsProjection
import com.example.myapplication.sync.outbox.OutboxContribution
import java.util.TimeZone

/**
 * [MirrorStateStore] 的 Room 实现。
 *
 * ## 四条贯穿全类的规则
 *
 * **一、每个公开方法都是一个 `withTransaction`。** 接口契约要求的。
 * 事务里**绝不做文件 I/O**：[commitFull] 因此先在事务外把整个镜像扫成内存里的行，
 * 再进事务一次性写库。事务持有数据库写锁，在里面流式读几十 MB 会把整个 App 的数据库卡住。
 *
 * **二、幂等靠"`order_index` 那一行插进去了没有"判断**，不靠 `record.alreadyApplied`。
 * 见 [OrderFileDao] 的类注释：这一个判据同时覆盖了 SSE 回声双投递，
 * 与"文件已 fsync、Room 事务还没跑就被杀"之后的重发 —— 后者恰恰是
 * `alreadyApplied == true` 而**必须补齐** Room 的情形。
 *
 * **三、统计的加与减走同一套贡献行，符号由调用点决定。**
 * [StatsProjection.contributionOf] 故意不收 `status`，所以这里减的时候
 * 用的是**原订单**的 writer/orderTs/total/件数，不可能误用取消那一刻的状态。
 *
 * **四、`OUTBOX` 桶的搬移也在这里，不在上传器里。** 见 [promoteFromOutbox]：
 * "这一单已经在服务器上了"有三条来路（`/add` 响应、SSE 回声、FULL），
 * 三条都经过这个类，而上传器只看得到第一条。
 */
class RoomMirrorStateStore(
    private val dao: OrderFileDao,
    /**
     * "在一个数据库事务里跑这段"。生产实现是 `db.withTransaction { … }`（见 [of]）。
     *
     * 做成参数而不是直接持有 `RoomDatabase`，是为了让**幂等规则**能在 JVM 上测：
     * 那几个判据（`insertOrderIndex` 返回 -1 就不加贡献、`cancelAppliedVersion` 已非空就不回退）
     * 是"静默双算"的唯一防线，而真 Room 只能上 instrumented 测试。
     * SQL 语义本身仍由 androidTest 的 `OrderFileDaoTest` 覆盖，两边刻意断言同一批场景。
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    /**
     * dimKey 的日粒度依赖时区。**用户改时区必须重投影**，
     * 否则同一天的营业额会被劈成两个 dimKey、各自偏小。见 [StatsProjection] 的类注释。
     */
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
) : MirrorStateStore {

    companion object {
        fun of(
            db: RoomDatabase,
            dao: OrderFileDao,
            timeZone: () -> TimeZone = { TimeZone.getDefault() },
            now: () -> Long = { System.currentTimeMillis() },
            audit: (String) -> Unit = {},
        ) = RoomMirrorStateStore(dao, { body -> db.withTransaction { body() } }, timeZone, now, audit)
    }

    override suspend fun read(storeKey: String, fileId: String): MirrorState? {
        val row = dao.fileState(storeKey, fileId) ?: return null
        return MirrorState(
            version = row.version,
            lastStreamId = row.lastStreamId,
            checksum = row.checksum,
            mirrorBytes = row.mirrorBytes,
            firstSyncedAt = row.firstSyncedAt,
            syncState = parseSyncState(row.syncState, fileId),
        )
    }

    // ==================== ADD / PATCH ====================

    override suspend fun commit(record: MirrorCommitRecord) {
        val parts = OrderFileId.parse(record.fileId)
        inTransaction {
            when (val d = record.order) {
                null -> Unit   // 只提交游标（调用方没有订单级变化）
                is OrderDelta.Added -> applyAdded(record.storeKey, record.fileId, parts, d)
                is OrderDelta.Patched -> applyPatched(record.storeKey, record.fileId, parts, d)
            }
            dao.upsertFileState(
                FileStateEntity(
                    storeKey = record.storeKey,
                    fileId = record.fileId,
                    fileType = parts.type,
                    year = parts.year,
                    month = parts.month,
                    version = record.state.version,
                    lastStreamId = record.state.lastStreamId,
                    checksum = record.state.checksum,
                    mirrorBytes = record.state.mirrorBytes,
                    firstSyncedAt = record.state.firstSyncedAt,
                    syncState = record.state.syncState.name,
                    // REPLACE 会整行覆盖，而 MirrorState 里没有这一列。
                    // 提交本身就是一次同步尝试，所以填 now() 是对的。
                    lastSyncAttemptAt = now(),
                )
            )
        }
    }

    private suspend fun applyAdded(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        d: OrderDelta.Added,
    ) {
        val parsed = OrderPayloadIndex.parse(d.payload)
        if (parsed == null) {
            // 偏移是服务端给的、一定有效，所以**仍然建索引行**：
            // 少了它这一单在历史页看不见、而且它的 statusOffset 无人记录 → 用户取消不了它。
            // 但金额/件数一概不猜 —— 编一笔账是看不见的，少一笔至少行数对不上。
            audit("ADD 的订单原文解析失败 fileId=$fileId uuid=${d.orderUuid} lineStart=${d.lineStart}：" +
                "仍建索引行（偏移可用），但不计入统计")
        }
        if (parsed?.itemsDropped == true) {
            audit("ADD 的 items 被整体丢弃（至少一条不合格）fileId=$fileId uuid=${d.orderUuid}：" +
                "件数与销量会偏小。B 类走到这里意味着本解析器与 Jackson 判断不一致")
        }

        val row = OrderIndexEntity(
            storeKey = storeKey,
            fileId = fileId,
            orderUuid = d.orderUuid,
            writer = parsed?.writer.orEmpty(),
            status = parsed?.status ?: 1,
            orderTs = parsed?.orderTs ?: 0L,
            totalCents = parsed?.totalCents ?: 0L,
            manualTotal = parsed?.manualTotal ?: false,
            itemCount = parsed?.itemCount ?: 0,
            lineStart = d.lineStart,
            uuidOffset = d.uuidOffset,
            statusOffset = d.statusOffset,
            remarkOffset = d.remarkOffset,
            remark = parsed?.remark,
            appliedFileVersion = d.appliedFileVersion,
            // 新订单不可能已经被取消。这一列之后由 [applyPatched] 在确认 1→0 时填。
            cancelAppliedVersion = null,
            groupId = parsed?.groupId,
        )

        // IGNORE + rowId：-1 表示这一行已经在了 → 贡献也已经加过了 → 什么都不做。
        if (dao.insertOrderIndex(row) == -1L) {
            audit("ADD 命中已存在的 order_index 行（重复投递或崩溃后重发）fileId=$fileId " +
                "uuid=${d.orderUuid} v=${d.appliedFileVersion}")
            return
        }

        if (parsed != null) {
            if (parsed.items.isNotEmpty()) {
                dao.insertItems(
                    parsed.items.mapIndexed { i, it ->
                        OrderItemIndexEntity(
                            storeKey = storeKey,
                            fileId = fileId,
                            orderUuid = d.orderUuid,
                            lineNo = i,
                            barcode = it.barcode,
                            name = it.name,
                            quantity = it.quantity,
                            amountCents = it.amountCents,
                        )
                    }
                )
            }
            addContribution(storeKey, fileId, parts, row, parsed.items.sumOf { it.quantity }, +1)
        }

        promoteFromOutbox(storeKey, parts, d.orderUuid)
        // 组的重算放在最后：它要读到刚刚插进去的那一行。
        row.groupId?.let { dao.recomputeOrderGroup(storeKey, fileId, it, now()) }
    }

    /**
     * 这一单如果本来是本地 outbox 里的，就把它**提升**：翻状态 + 把贡献从 `OUTBOX` 桶搬到 `FILE:` 桶。
     *
     * ## 为什么在这里、而不在上传器里
     *
     * "这一单已经在服务器上了"有三条来路：`/add` 的响应、SSE 回声、以及一次 FULL
     * （上传超时但服务器其实收下了，重启后靠 FULL 才发现）。三条都经过这里，
     * 而上传器只看得到第一条。把搬桶放在上传器里的话，后两条来路会让那笔钱
     * **永久留在 OUTBOX 桶**：营业额多算一笔，而且没有任何一处会报错。
     *
     * ## 幂等判据是 `markOutboxPromoted` 的返回值
     *
     * 它的 SQL 带 `state NOT IN ('DRAFT','PROMOTED')`，所以**只有第一次**返回 1。
     * 这与上面 `insertOrderIndex` 返回 `-1` 的判据是同一思路的两侧：
     * `FILE:` 桶靠"索引行插进去了没有"，`OUTBOX` 桶靠"状态迁移发生了没有"。
     * 两边都必须由数据库告诉我们，不能靠调用方记着一个 boolean ——
     * 进程在两步之间被杀的时候，那个 boolean 已经不在了。
     *
     * ## 减多少由 outbox 行自己的 payload 决定
     *
     * 不是"把刚加进 `FILE:` 桶的那几个数照抄一遍"。加进 OUTBOX 桶的是
     * [OutboxContribution.of]（`enqueue` 时算的），减的时候就必须还用它 ——
     * 同一个函数、同一份字节，才保证净值为零。
     * 万一镜像里的字节与 outbox 里的不一致（那已经是 §7.2 硬门失败级别的事故），
     * 这样写会让差值落在 `FILE:` 桶（看得见，`statsSelfCheck` 能查），
     * 而不是在 OUTBOX 桶里留一笔永远搬不走的残留。
     */
    private suspend fun promoteFromOutbox(storeKey: String, parts: OrderFileId.Parts, orderUuid: String) {
        // 先读再翻：读到的 state 就是"贡献是按哪个状态加进去的"那一个。
        val before = dao.outboxRow(storeKey, orderUuid) ?: return   // 别的设备开的单，与 outbox 无关
        if (dao.markOutboxPromoted(storeKey, orderUuid) != 1) {
            audit("outbox 行 $orderUuid 处于 ${before.state}，未提升（已经提升过，或它还是草稿）")
            return
        }
        // ⚠ 从**这一行当初加进去的那个桶**减，不是硬编码 OUTBOX。
        // `FAILED_PERMANENT` 的钱在 `OUTBOX_FAILED` 桶里（用户可以把上传失败的单
        // 排除出营业额），而 `markOutboxPromoted` 的 SQL 允许 FAILED_PERMANENT 被提升 ——
        // 那一单其实早就传上去了，只是本机之前判成了失败。
        // 从错误的桶减会让 OUTBOX 桶留下一笔永远搬不走的钱，而 OUTBOX_FAILED 变成负数。
        val scope = StatsProjection.scopeOfOutboxState(before.state)
        if (scope != null) {
            for (c in OutboxContribution.of(before, parts, timeZone())) {
                dao.applyContribDelta(storeKey, scope, c.dimKey, c.metric, -c.value)
            }
        }
    }

    private suspend fun applyPatched(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        d: OrderDelta.Patched,
    ) {
        // 流消息里的 CANCEL **可能不带 order_uuid**，那时补丁偏移是唯一的线索。
        val target = d.orderUuid?.let { dao.orderIndex(storeKey, fileId, it) }
            ?: when (d.patchKind) {
                PatchKind.STATUS -> dao.orderIndexByStatusOffset(storeKey, fileId, d.patchOffset)
                PatchKind.REMARK -> dao.orderIndexByRemarkOffset(storeKey, fileId, d.patchOffset)
                // UNKNOWN 不猜（MirrorCommitter 已经记了审计并会触发 FULL 自愈）。
                // 这里再按偏移瞎找一遍，等于把"我不知道这是什么"改写成"我猜它是那一单"。
                PatchKind.UNKNOWN -> null
            }
        if (target == null) {
            audit("补丁找不到对应的索引行 fileId=$fileId offset=${d.patchOffset} " +
                "kind=${d.patchKind} uuid=${d.orderUuid} —— 索引可能已过期，等 FULL 自愈")
            return
        }

        when (d.patchKind) {
            PatchKind.REMARK -> {
                // 只改文本。⚠ 不动统计、不动库存 —— 把改备注误判成取消会错误回退，
                // 而且不会报错（PatchClassifier 就是为此存在的）。
                dao.updateOrderRemark(storeKey, fileId, target.orderUuid, d.newRemark)
            }

            PatchKind.STATUS -> {
                // newStatusDigit 为 null 表示补丁字节不是 '0'..'9'（服务端不会发，
                // 但流消息是服务端给的、客户端不该假设）→ 保持原状态，不写垃圾进去。
                val newStatus = d.newStatusDigit ?: target.status
                val cancel = d.cancelAppliedVersion
                val firstCancel = cancel != null && target.cancelAppliedVersion == null

                // `?:` 的语义：已经记过取消版本就**保留第一次的**。
                // 同一条 CANCEL 会被投递两次，第二次的 msgVersion 相同，但保留旧值
                // 才能保证"回退只发生一次"这个判据（firstCancel）与这列的值一致。
                dao.updateOrderStatus(storeKey, fileId, target.orderUuid, newStatus,
                    target.cancelAppliedVersion ?: cancel)

                if (firstCancel) {
                    val pieces = dao.sumQuantityOfOrder(storeKey, fileId, target.orderUuid) ?: 0
                    addContribution(storeKey, fileId, parts, target, pieces, -1)
                }

                // 拆过的单要更新"3 笔中已取消 2 笔"。**不只 firstCancel 时**：
                // 总部把 A 类标成完成（1→2）或删已完成（2→3）时 cancelState 不变，
                // 但重算是幂等的，而漏算会让 UI 停在一个过期的数字上。
                target.groupId?.let { dao.recomputeOrderGroup(storeKey, fileId, it, now()) }
            }

            PatchKind.UNKNOWN -> Unit
        }
    }

    /**
     * 加（[sign] = +1）或回退（[sign] = -1）一笔订单的统计贡献。
     *
     * @param pieces `Σ items.quantity`。ADD 时从 payload 现算，回退时从
     *        `order_item_index` 查 —— 两处必须同源，否则加一次减一次不会归零。
     */
    private suspend fun addContribution(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        row: OrderIndexEntity,
        pieces: Int,
        sign: Int,
    ) {
        // A 类返回空列表（向丽华金龙进货不是本店收入），所以这里不需要再判一次类型。
        val contribs = StatsProjection.contributionOf(
            parts = parts,
            writer = row.writer,
            orderTs = row.orderTs,
            timeZone = timeZone(),
            totalCents = row.totalCents,
            pieces = pieces,
        )
        if (contribs.isEmpty()) return
        val scope = StatsProjection.fileScope(fileId)
        for (c in contribs) {
            dao.applyContribDelta(storeKey, scope, c.dimKey, c.metric, sign * c.value)
        }
    }

    // ==================== FULL ====================

    override suspend fun commitFull(storeKey: String, fileId: String, state: MirrorState, mirror: Mirror) {
        val parts = OrderFileId.parse(fileId)
        val scope = StatsProjection.fileScope(fileId)
        val tz = timeZone()

        // ---- 第一步：事务**外**扫描镜像。这一步全是文件 I/O。 ----
        val indexes = ArrayList<OrderIndexEntity>()
        val items = ArrayList<OrderItemIndexEntity>()
        // (dimKey, metric) -> 值。用 Map 累加而不是逐条 applyContribDelta：
        // 一个月几千笔订单，逐条 UPDATE 是几千次 SQL 往返。
        val contrib = LinkedHashMap<Pair<String, String>, Long>()
        var unparseable = 0

        val summary = MirrorLineScanner.scan(mirror) { lineStart, payload ->
            val parsed = OrderPayloadIndex.parse(payload)
            if (parsed == null) {
                unparseable++
                audit("FULL 重建：第 ${indexes.size + unparseable} 行解析失败 fileId=$fileId " +
                    "lineStart=$lineStart —— 跳过该行，不建索引、不计入统计")
                return@scan
            }
            if (parsed.itemsDropped) {
                audit("FULL 重建：items 被整体丢弃 fileId=$fileId uuid=${parsed.orderUuid} " +
                    "lineStart=$lineStart —— 件数与销量会偏小")
            }
            // FULL 恢复的订单**没有**流消息里的 appliedFileVersion，只能填本次 FULL 的版本。
            // 这与吸收边界是自洽的：商品 FULL 会把边界推到各 fileId 的当前版本
            // （StockLedger），于是这批订单的扣减全部落在边界之内、由基线吸收。
            val cancelled = parsed.status == 0
            val row = OrderIndexEntity(
                storeKey = storeKey,
                fileId = fileId,
                orderUuid = parsed.orderUuid,
                writer = parsed.writer,
                status = parsed.status,
                orderTs = parsed.orderTs,
                totalCents = parsed.totalCents,
                manualTotal = parsed.manualTotal,
                itemCount = parsed.itemCount,
                // 行内下标 + 行起点 = 绝对偏移。这是 FULL 恢复唯一能拿到偏移的办法。
                lineStart = lineStart,
                uuidOffset = lineStart + parsed.offsets.uuidValueIndex,
                statusOffset = lineStart + parsed.offsets.statusIndex,
                remarkOffset = parsed.offsets.remarkValueIndex?.let { lineStart + it },
                remark = parsed.remark,
                appliedFileVersion = state.version,
                // ⚠ 已取消的订单必须把取消版本也填成 state.version。
                // 只填 appliedFileVersion 的话，一旦吸收边界停在这次 FULL 之前，
                // StockLedger 会把这笔**已经取消**的扣减再算一次 → 库存偏低。
                // 两个版本相等时，无论边界落在哪里，`> 边界` 都同时成立或同时不成立，
                // 净增量恒为 0 —— 结构上不可能算错。
                cancelAppliedVersion = if (cancelled) state.version else null,
                groupId = parsed.groupId,
            )
            indexes += row
            parsed.items.forEachIndexed { i, it ->
                items += OrderItemIndexEntity(
                    storeKey = storeKey,
                    fileId = fileId,
                    orderUuid = parsed.orderUuid,
                    lineNo = i,
                    barcode = it.barcode,
                    name = it.name,
                    quantity = it.quantity,
                    amountCents = it.amountCents,
                )
            }
            // 已取消的订单不计入统计：增量路径上它先 +1 再 -1，净值就是 0，
            // 重投影必须得到同一个数，否则一次 FULL 就会把历史营业额改大。
            if (!cancelled) {
                for (c in StatsProjection.contributionOf(
                    parts, parsed.writer, parsed.orderTs, tz, parsed.totalCents,
                    parsed.items.sumOf { it.quantity },
                )) {
                    val k = c.dimKey to c.metric
                    contrib[k] = (contrib[k] ?: 0L) + c.value
                }
            }
        }

        if (summary.droppedOverlong > 0) {
            audit("FULL 重建：${summary.droppedOverlong} 行超过 ${MirrorLineScanner.MAX_LINE_BYTES} 字节被丢弃 " +
                "fileId=$fileId —— 镜像几乎肯定坏了，行数自检也对不上")
        }

        // ---- 第二步：一个事务里换掉全部派生数据。 ----
        inTransaction {
            // ⚠ 这两次读**必须在 delete 之前**：`groupIdsOfFile` 查的正是即将被删掉的那些行。
            val staleGroups = dao.groupIdsOfFile(storeKey, fileId)
            val contributing = dao.outboxContributingUuids(storeKey).toSet()

            dao.deleteOrderIndexesOfFile(storeKey, fileId)
            dao.deleteItemsOfFile(storeKey, fileId)
            // 先减旧贡献：这里是整段删掉再重建，等价于"减到 0 再加回来"。
            // 为什么不带 `AND frozen = 0`，见 OrderFileDao.deleteContribOfScope 的注释。
            dao.deleteContribOfScope(storeKey, scope)

            if (indexes.isNotEmpty()) dao.insertOrderIndexes(indexes)
            if (items.isNotEmpty()) dao.insertItems(items)
            if (contrib.isNotEmpty()) {
                dao.insertContribs(
                    contrib.map { (k, v) ->
                        StatsContribEntity(storeKey, scope, k.first, k.second, v, 0)
                    }
                )
            }

            // 行数自检：扫描出的行数（减去解析失败的）必须等于插入的行数。
            // 差值 > 0 只可能是**文件里有重复的 order_uuid**（服务端不查重，
            // 某台设备盲重试过 /add）—— 那是数据已经出问题的证据，必须记下来。
            val expected = summary.lines - unparseable
            val inserted = dao.countOrderIndexesOfFile(storeKey, fileId)
            if (inserted != expected) {
                audit("FULL 重建行数对不上 fileId=$fileId：扫描 $expected 行、索引 $inserted 行，" +
                    "差 ${expected - inserted} 行。极可能是文件里有重复的 order_uuid（服务端不查重），" +
                    "此后这些订单的偏移只保留第一行")
            }

            // ---- outbox 提升：FULL 也是"这一单其实已经在服务器上"的一条来路 ----
            //
            // 上传超时但服务器收下了 → 行留在 IN_FLIGHT → 之后任何一次 FULL 都会
            // 在镜像里扫到它。不在这里搬桶的话，那笔钱会**永久留在 OUTBOX 桶**：
            // 它已经在 FILE 桶里被算过一遍了（上面那批 contrib 行），营业额从此多算一笔。
            if (contributing.isNotEmpty()) {
                for (row in indexes) {
                    if (row.orderUuid in contributing) promoteFromOutbox(storeKey, parts, row.orderUuid)
                }
            }

            // ---- 组重算：先算旧组（可能已经不在新文件里了，重算会把空组删掉），再算新组 ----
            val groupIds = LinkedHashSet(staleGroups)
            indexes.mapNotNullTo(groupIds) { it.groupId }
            for (g in groupIds) dao.recomputeOrderGroup(storeKey, fileId, g, now())

            dao.upsertFileState(
                FileStateEntity(
                    storeKey = storeKey,
                    fileId = fileId,
                    fileType = parts.type,
                    year = parts.year,
                    month = parts.month,
                    version = state.version,
                    lastStreamId = state.lastStreamId,
                    checksum = state.checksum,
                    mirrorBytes = state.mirrorBytes,
                    firstSyncedAt = state.firstSyncedAt,
                    syncState = state.syncState.name,
                    lastSyncAttemptAt = now(),
                )
            )
        }
    }

    // ==================== DIVERGED ====================

    override suspend fun commitDiverged(storeKey: String, fileId: String, reason: String) {
        val parts = OrderFileId.parse(fileId)
        val at = now()
        inTransaction {
            if (dao.markDiverged(storeKey, fileId, at) == 0) {
                // 还没有行（守卫在第一次同步时就判了分歧）。
                // firstSyncedAt = null：这个月**从未**成功同步过，与"同步过但没单"必须区分开。
                dao.upsertFileState(
                    FileStateEntity(
                        storeKey = storeKey,
                        fileId = fileId,
                        fileType = parts.type,
                        year = parts.year,
                        month = parts.month,
                        version = 0,
                        lastStreamId = null,
                        checksum = 0,
                        // 空镜像就是 4 字节版本头，与 MirrorState.fresh() 一致
                        mirrorBytes = ByteOps.HEADER_LEN.toLong(),
                        firstSyncedAt = null,
                        syncState = SyncState.DIVERGED.name,
                        lastSyncAttemptAt = at,
                    )
                )
            }
        }
        // ⚠ 索引与贡献行**不清**：镜像虽然被隔离了，那些行仍然是目前最好的信息
        // （历史页还能看、统计还大致对）。下一次 FULL 会整体重建它们。
        audit("commitDiverged fileId=$fileId reason=$reason")
    }

    // ==================== ARCHIVED ====================

    override suspend fun commitArchived(storeKey: String, fileId: String, reason: String) {
        val parts = OrderFileId.parse(fileId)
        // 先读旧值**只为审计**：归档的要害是"游标冻结"，而冻结前后的版本号必须留痕，
        // 否则将来运维恢复了这个文件，没人能判断本地那份归档镜像停在哪个版本。
        val before = dao.fileState(storeKey, fileId)
        inTransaction {
            // OrderFileDao.archiveFile 的默认实现：markArchived 命中现有行时
            // SET 列表里只有 syncState 与 lastSyncAttemptAt —— version/lastStreamId/
            // checksum/mirrorBytes/firstSyncedAt 因此**原样保留**；没有行才补一行。
            // 随后把该 scope 的贡献置 frozen=1（**不是删**，见接口 KDoc）。
            dao.archiveFile(storeKey, fileId, parts.type, parts.year, parts.month, now())
        }
        audit("commitArchived fileId=$fileId reason=$reason 游标冻结在 " +
            "v${before?.version?.toString() ?: "无记录"}/stream=${before?.lastStreamId}")
    }

    // ==================== 内部 ====================

    /** 未知字面量按 DIVERGED 处理：那会让调度器强制走 FULL，是自愈的一侧。 */
    private fun parseSyncState(raw: String, fileId: String): SyncState =
        runCatching { SyncState.valueOf(raw) }.getOrElse {
            audit("file_state.syncState 是未知值 '$raw'（fileId=$fileId），按 DIVERGED 处理")
            SyncState.DIVERGED
        }
}
