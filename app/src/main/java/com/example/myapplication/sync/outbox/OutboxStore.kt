package com.example.myapplication.sync.outbox

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.myapplication.core.money.Money
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.StatsProjection
import java.util.TimeZone
import java.util.UUID

/**
 * 一笔 outbox 行对 `OUTBOX` 桶的贡献。**纯函数**，加与减都必须走它。
 *
 * ## 为什么单独抽出来
 *
 * 一笔未上传订单的钱要在两个时刻被算进来、又在第三个时刻被搬走：
 *
 * | 时刻 | 谁做 | 符号 |
 * |---|---|---|
 * | 进 outbox | [OutboxStore.enqueue] | `+` 到 `OUTBOX` |
 * | 用户删掉 / 换价重算 | [OutboxStore.discard] / [OutboxStore.enqueue] | `−` 从 `OUTBOX` |
 * | 提升进镜像 | `RoomMirrorStateStore.applyAdded` / `.commitFull` | `−` 从 `OUTBOX`（同时 `+` 到 `FILE:`） |
 *
 * 第三行的执行者是**同步引擎**，不是这个类 —— 因为提升是被"镜像里出现了这一单"这个事件
 * 触发的，而那个事件可能来自 `/add` 的响应、来自 SSE 回声、也可能来自一次 FULL
 * （上传超时后服务器其实已经收下）。三条路径都必须搬桶，所以搬桶的代码只能放在它们
 * 共同经过的那一层。
 *
 * 于是"加"与"减"分居两个类。让它们调用**同一个函数**是唯一能保证两边算出同一个数字的办法：
 * 差一分钱不会报错，只会让营业额永久偏掉，而且偏在哪一天、哪个人名下都查不出来。
 *
 * ## 为什么从 `payload` 解析、不用 `OrderDraft`
 *
 * `enqueue` 手上明明有 draft（writer/total/items 都在），从它算贡献更直接。
 * 但提升那一侧只能拿到**镜像里的字节**，那边必须解析。两侧要同源，
 * 那就只能都走解析 —— 让"从 draft 算"这条看起来更近的路不存在。
 *
 * 这同时兜住了 `OrderPayloadIndex.parse` 的宽容规则：items 里有一条不合格时它会
 * **整体丢弃** items（`itemsDropped`），件数变成 0。从 draft 算的话加的是真实件数、
 * 减的是 0，OUTBOX 桶里留下一笔永远搬不走的件数。
 */
object OutboxContribution {

    /**
     * @return 空列表表示这一行不该贡献（A 类、DRAFT、或 payload 解析失败）。
     *         调用方对空列表什么都不做 —— **不要**用"贡献为 0"代替"没有贡献"，
     *         那样 `stats_contrib` 里会多出一堆值为 0 的行，把自检查询淹掉。
     */
    fun of(
        row: OrderOutboxEntity,
        parts: OrderFileId.Parts,
        timeZone: TimeZone,
    ): List<StatsProjection.Contribution> {
        // DRAFT 从来没被加进去过，所以也不能被减掉。
        if (row.state == OrderOutboxEntity.DRAFT) return emptyList()
        val parsed = OrderPayloadIndex.parse(row.payload) ?: return emptyList()
        return StatsProjection.contributionOf(
            parts = parts,
            writer = parsed.writer,
            orderTs = parsed.orderTs,
            timeZone = timeZone,
            totalCents = parsed.totalCents,
            pieces = parsed.items.sumOf { it.quantity },
        )
    }

    /**
     * 从 outbox 行还原它**将要**落进的文件。
     *
     * ⚠ `ownerName` 必须是**店主**用户名，不是 `row.writer`（那是登录名，员工开单时两者不同）。
     * 文件归属店主、员工操作的是店铺文件（README §4.2），用 writer 拼出来的 fileId
     * 指向一个根本不存在的文件 —— 而它照样能同步（服务端 `ensureFileBlocking` 按需建），
     * 于是订单会落进一个只有这台设备知道的私有文件里，第二台设备永远看不到。
     */
    fun partsOf(ownerName: String, row: OrderOutboxEntity): OrderFileId.Parts =
        if (row.fileType == OrderFileId.TYPE_A) {
            OrderFileId.Parts(OrderFileId.TYPE_A, ownerName, row.year, OrderFileId.NO_MONTH)
        } else {
            OrderFileId.Parts(OrderFileId.TYPE_B, ownerName, row.year, row.month)
        }
}

/**
 * 未同步区（outbox）的存储层：状态机 + `OUTBOX` 统计桶 + `order_group`。
 *
 * ## 与 [com.example.myapplication.mirror.RoomMirrorStateStore] 的分工
 *
 * 那边管已同步区（镜像、游标、`order_index`、`FILE:` 桶），这边管未同步区。
 * **交接发生在同步引擎的事务里**，不在这个类里：提升一笔订单时，
 * `applyAdded` 在同一个事务中 ①插 `order_index` ②加 `FILE:` 贡献
 * ③调 `markOutboxPromoted` ④**仅当它返回 1** 才减 `OUTBOX` 贡献。
 *
 * 这个类因此**没有** `promote` 方法。加一个的话就会有两个地方能搬桶，
 * 而它们各自的幂等判据不同 —— 结果要么双算要么漏算，两种都静默。
 *
 * ## 事务里不做文件 I/O，也不做网络
 *
 * 与 `RoomMirrorStateStore` 同一条规矩。[enqueue] 收到的 `req` 是已经算好价的内存对象，
 * 拆单（`OrderGroupAssembler`）在事务**外**做完；[takeOff] 只改状态，发请求是调用方的事。
 *
 * ## ⚠ 时区改了要重投影
 *
 * `dimKey` 里含日期，而日期按当时设备的时区算。用户在"进 outbox"与"提升"之间改了时区，
 * 加与减就会落在两个不同的 dimKey 上：旧键留下一笔搬不走的钱、新键多出一笔负数。
 * 这不是这里能兜的（旧时区没有被存下来），`StatsProjection` 的类注释里已经把
 * "改时区必须重投影"列为全局约束。设置页的时区提示必须写明这一点。
 */
class OutboxStore(
    private val dao: OrderFileDao,
    /**
     * "在一个数据库事务里跑这段"。生产实现是 `db.withTransaction { … }`（见 [of]）。
     * 做成参数的理由与 `RoomMirrorStateStore` 完全一样：让**贡献的加减对称性**能在 JVM 上测。
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 每块一个 uuid。**绝不让多块共用一个**，理由见 `OrderGroupAssembler.assemble` 的参数注释。 */
    private val uuidFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * 本机设备 id，写进每一行的 `originDevice`。
     *
     * ⚠ 默认空串只是为了让 JVM 测试不必构造一个 Android Context；
     * 生产装配（`ServiceLocator.outboxStore`）**必须**传真实的 id，
     * 否则所有行都看起来是"迁移前的老行"，热点同步过来的外部单与本机单
     * 就分不开了 —— 而那正是要防的重复上传。
     */
    private val deviceId: () -> String = { "" },
    private val audit: (String) -> Unit = {},
) {

    companion object {
        fun of(
            db: RoomDatabase,
            dao: OrderFileDao,
            timeZone: () -> TimeZone = { TimeZone.getDefault() },
            now: () -> Long = { System.currentTimeMillis() },
            deviceId: () -> String = { "" },
            audit: (String) -> Unit = {},
        ) = OutboxStore(
            dao,
            { body -> db.withTransaction { body() } },
            timeZone,
            now,
            deviceId = deviceId,
            audit = audit,
        )

        /** `blockedReason` 的文案模板。UI 直接显示它，所以不要把条码列表塞进这一句里。 */
        fun blockedReason(count: Int) = "缺 $count 个商品的箱规，补齐后才能上传"

        /** `blockedBarcodes` 是逗号分隔的。空串与 null 都算"没有"。 */
        fun splitCsv(csv: String?): List<String> =
            csv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** [enqueue] 的结果。 */
    data class Enqueued(
        /** 只有一块时为 null（那时 payload 里也没有 `grp` 字段）。 */
        val groupId: String?,
        val rows: List<OrderOutboxEntity>,
        /** 整单是否因缺箱规被挂起。 */
        val blocked: Boolean,
    ) {
        val chunkCount: Int get() = rows.size
    }

    // ==================== 写入 ====================

    /**
     * 把一张逻辑订单拆块、入库、并加进 `OUTBOX` 统计桶。
     *
     * @param ownerName **店主**用户名，用来拼 fileId。见 [OutboxContribution.partsOf] 的警告。
     * @param missingBoxSizeBarcodes 这一单里 `箱数 > 0 且 boxSize == 0` 的条码
     *        （方案 §5.1 第 3 步的"暂不上传"出口）。非空 → 整单挂起。
     * @param replacing **要整单换掉的旧 uuid**（"用新价重算"走这里，见 `OrderOutboxEntity` 的类注释）。
     *        旧行在同一事务里删掉、贡献减回去。传空就是普通新单。
     *
     * ## ⚠ 整单挂起，不是"只挂起缺规格的那一块"
     *
     * 拆出来的块里可能只有一块含缺规格的商品。逐块判断的话，用户会看到
     * "这一单 3 块里上传了 2 块"—— 而那是**半张销售单**：库存只扣了三分之二，
     * 统计只算了三分之二，服务器上却有一个看起来完整的订单组。
     * 挂起整单的代价只是"多等一次补规格"，比静默的半张单便宜得多。
     */
    suspend fun enqueue(
        storeKey: String,
        ownerName: String,
        req: OrderGroupAssembler.Request,
        missingBoxSizeBarcodes: List<String> = emptyList(),
        replacing: List<String> = emptyList(),
    ): Enqueued {
        // 拆单在事务**外**：它会序列化几十次（探路），而事务持有数据库写锁。
        val result = OrderGroupAssembler.assemble(req, uuidFactory = uuidFactory)
        val at = now()
        val blocked = missingBoxSizeBarcodes.isNotEmpty()
        val state = if (blocked) OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE else OrderOutboxEntity.READY

        val rows = result.chunks.map { c ->
            OrderOutboxEntity(
                storeKey = storeKey,
                orderUuid = c.draft.orderUuid,
                // 单块订单存**空串**（列是 NOT NULL），UI 判分组时把空串当"自成一组"。
                groupId = result.groupId.orEmpty(),
                fileType = req.fileType,
                year = req.year,
                month = req.month ?: OrderFileId.NO_MONTH,
                // ⚠ 存的是**序列化后的字节**，不是 draft。之后上传发的就是这份，
                // 一个字都不会变（DAO 里没有 UPDATE payload 的方法）。
                payload = c.serialized.payload,
                state = state,
                blockedReason = if (blocked) blockedReason(missingBoxSizeBarcodes.size) else null,
                blockedBarcodes = if (blocked) missingBoxSizeBarcodes.joinToString(",") else null,
                attemptCount = 0,
                lastError = null,
                pricedAt = at,
                createdAt = at,
                writer = req.writer,
                // A 类进货单没有 total（见 OrderDraft.total 的注释），存 0。
                // ⚠ 这个 0 **不参与统计**：OutboxContribution.of → StatsProjection.contributionOf
                // 对 `!parts.isB` 直接返回空列表。UI 显示金额时必须先看 fileType，
                // 不能把 0 印成 "¥0.00" —— 那是"这一单免费"的意思，而进货单根本不该有金额。
                totalCents = c.draft.total?.let(Money::toCents) ?: 0L,
                itemCount = c.draft.items.size,
                orderTs = req.ts,
                // 这一行是**本机**开的。热点同步过来的外部单由接收侧写成对方的 id，
                // 于是 `uploadReady` 能只挑本机单自动上传 —— 见 OutboxUploader。
                originDevice = deviceId(),
            )
        }

        val newFileId = partsOfReq(ownerName, req).fileId

        inTransaction {
            // ---- 先处理被换掉的旧行 ----
            val staleGroups = LinkedHashSet<Pair<String, String>>()   // (fileId, groupId)
            for (uuid in replacing) {
                val old = dao.outboxRow(storeKey, uuid)
                if (old == null) {
                    audit("换单：旧 uuid $uuid 不在 outbox 里（已被删掉？），跳过")
                    continue
                }
                require(old.state != OrderOutboxEntity.IN_FLIGHT && old.state != OrderOutboxEntity.PROMOTED) {
                    "不能换掉 ${old.state} 的订单 $uuid：它可能已经发给服务端了。" +
                        "重推会让同一个 uuid 在服务器文件里出现两行，此后所有偏移静默错位"
                }
                val n = dao.deleteOutboxRow(storeKey, uuid)
                check(n == 1) { "删掉旧 outbox 行 $uuid 失败（影响 $n 行）—— 状态检查与 SQL 条件不一致" }
                applyContribution(storeKey, ownerName, old, -1)
                if (old.groupId.isNotEmpty()) {
                    staleGroups += OutboxContribution.partsOf(ownerName, old).fileId to old.groupId
                }
            }

            // ---- 再插新行 ----
            if (rows.size == 1) dao.insertOutbox(rows[0]) else dao.insertOutboxRows(rows)
            for (r in rows) applyContribution(storeKey, ownerName, r, +1)

            // ---- 最后重算受影响的组 ----
            val groups = LinkedHashSet(staleGroups)
            result.groupId?.let { groups += newFileId to it }
            for ((fid, gid) in groups) dao.recomputeOrderGroup(storeKey, fid, gid, at)
        }

        if (result.isSplit) {
            audit("订单被拆成 ${result.chunkCount} 块 grp=${result.groupId} fileId=$newFileId " +
                "writer=${req.writer}${if (blocked) "（缺 ${missingBoxSizeBarcodes.size} 个箱规，已挂起）" else ""}")
        }
        return Enqueued(result.groupId, rows, blocked)
    }

    /**
     * 删掉一张未上传的订单（连同它**同组的其它未上传块**）。
     *
     * 连带删同组是必须的：只删一块的话，剩下的块上传后组成一张**少了几行**的销售单，
     * 而服务器上的 `grp` 看起来完全正常 —— 用户不会发现少了什么，库存却少扣了。
     * 已提升的块删不掉（`deleteOutboxRow` 拒绝），要撤只能走取消。
     *
     * @return 实际删掉的行数。0 = 这一行不存在、或已经在飞/已上传。
     */
    suspend fun discard(storeKey: String, ownerName: String, orderUuid: String): Int {
        val target = dao.outboxRow(storeKey, orderUuid) ?: return 0
        val victims = if (target.groupId.isEmpty()) {
            listOf(target)
        } else {
            // outboxRowsOfGroup 已排除 PROMOTED，所以这里拿到的正好是"还能删的那些"
            dao.outboxRowsOfGroup(storeKey, target.groupId).ifEmpty { listOf(target) }
        }

        var deleted = 0
        inTransaction {
            val fileId = OutboxContribution.partsOf(ownerName, target).fileId
            for (v in victims) {
                if (v.state == OrderOutboxEntity.IN_FLIGHT || v.state == OrderOutboxEntity.PROMOTED) {
                    audit("删除订单：${v.orderUuid} 处于 ${v.state}，拒绝删除")
                    continue
                }
                if (dao.deleteOutboxRow(storeKey, v.orderUuid) != 1) {
                    audit("删除订单：${v.orderUuid} 删除失败（并发删掉了？）")
                    continue
                }
                applyContribution(storeKey, ownerName, v, -1)
                deleted++
            }
            if (deleted > 0 && target.groupId.isNotEmpty()) {
                dao.recomputeOrderGroup(storeKey, fileId, target.groupId, now())
            }
        }
        return deleted
    }

    /**
     * 箱规补齐之后，重新核对所有挂起的单：还缺的收窄 CSV，不缺的放行成 `READY`。
     *
     * @param hasBoxSize 该条码现在是否已经有箱规（`boxSize > 0`）。由调用方查商品表提供 ——
     *        这个类不持有 `ProductDao`，否则"outbox 的阻塞状态"就要依赖商品同步的时序，
     *        而商品同步随时可能被一次 FULL 打断。
     * @return 被放行的 uuid。UI 可以据此提示"N 张单现在可以上传了"。
     */
    suspend fun refreshBlocked(storeKey: String, hasBoxSize: (String) -> Boolean): List<String> {
        val blocked = dao.outboxBlockedRows(storeKey)
        if (blocked.isEmpty()) return emptyList()
        val released = ArrayList<String>()
        inTransaction {
            for (row in blocked) {
                val before = splitCsv(row.blockedBarcodes)
                val missing = before.filterNot(hasBoxSize)
                if (missing.isNotEmpty()) {
                    // 一个都没补上就不写库：CSV 与文案都没变，写一次是纯浪费。
                    if (missing.size != before.size) {
                        dao.updateOutboxBlockedBarcodes(
                            storeKey, row.orderUuid, blockedReason(missing.size), missing.joinToString(","),
                        )
                    }
                    continue
                }
                // CAS：只有真被箱规挡住的那一行才会被放行（见 DAO 注释）。
                if (dao.unblockOutbox(storeKey, row.orderUuid) == 1) released += row.orderUuid
            }
        }
        return released
    }

    // ==================== 热点同步：接收别的设备的单 ====================

    /** [adoptForeign] 的结果。 */
    data class AdoptReport(
        /** 真的插进来的行数。 */
        val inserted: Int,
        /** 本地已经有了、跳过的行数（不是错误：同一批数据被同步两次是正常情况）。 */
        val skipped: Int,
        /**
         * 同一个 uuid 但 payload **字节不同**的行。
         *
         * ⚠ 这是严重不一致：payload 一次写入不可变，两台设备手上同一个 uuid 的字节
         * 应当逐字节相同（其中一台是从另一台同步来的）。不同就说明有一边被改过 ——
         * 而"发出去的字节与库里存的字节不一致"会让所有偏移作废，且没有任何机制会报错。
         * 这种情况下**保留本地那份**（它可能已经发出去过了），但要大声记审计。
         */
        val payloadConflicts: List<String>,
    )

    /**
     * 接收一批从别的设备热点同步过来的 outbox 行。
     *
     * ## ⚠ 必须走这里，不能直接 `dao.insertOutbox`
     *
     * 插一行未上传的订单，同时意味着**这个店的营业额多了一笔**（货已经给客人了，
     * 只是还没传上去）。`OUTBOX` 桶必须跟着加，否则：
     *  - 统计少一笔，而用户看不出少的是哪一笔；
     *  - 更糟的是这一行之后被提升时，`promoteFromOutbox` 会从 `OUTBOX` 桶**减**走
     *    一笔从来没加进去的钱 → 桶变成负数 → 营业额永久偏小，且 `statsSelfCheck`
     *    查不出来（它比的是 contrib 与 agg，两边都错就互相抵消）。
     *
     * 加与减必须走同一个纯函数 [OutboxContribution.of]，理由见它的类注释。
     *
     * ## ⚠ 只接收**未上传**的行，不接收 PROMOTED
     *
     * 热点协议不传"已上传"这个状态。B 设备要知道 A 已经传了某笔单，
     * 正确的途径是**同步那个文件**：ADD 消息到达 → `applyAdded` → `promoteFromOutbox`，
     * 那条路径会同时把偏移、索引行、统计桶三件事一起做对。
     * 让热点协议直接改状态就等于开了第二个搬桶入口，而两处的幂等判据不同 ——
     * 结果要么双算要么漏算，两种都静默。
     *
     * ## 幂等
     *
     * 同一批数据被同步两次是正常情况（用户可能连了两回热点）。
     * 已存在的 uuid 一律跳过，**不覆盖状态也不覆盖 payload**：
     * 本地那份的状态可能已经比对方新（比如本地已经 IN_FLIGHT 了）。
     */
    suspend fun adoptForeign(
        storeKey: String,
        ownerName: String,
        incoming: List<OrderOutboxEntity>,
    ): AdoptReport {
        var inserted = 0
        var skipped = 0
        val conflicts = ArrayList<String>()

        inTransaction {
            for (row in incoming) {
                if (row.state == OrderOutboxEntity.PROMOTED) {
                    // 不该出现在 payload 里（发送侧已经过滤），真出现了就跳过：
                    // 提升状态只能由同步路径来写，见上面的注释。
                    skipped++
                    continue
                }
                val existing = dao.outboxRow(storeKey, row.orderUuid)
                if (existing != null) {
                    skipped++
                    if (!existing.payload.contentEquals(row.payload)) {
                        conflicts += row.orderUuid
                        audit("⚠ 热点同步：uuid ${row.orderUuid} 两边 payload 不一致。" +
                            "保留本地那份（它可能已经发出去过），对方 ${row.payload.size} 字节 / " +
                            "本地 ${existing.payload.size} 字节。这说明有一边的字节被改过")
                    }
                    continue
                }
                // 外来的行要盖上**本机的**接收痕迹吗？不 —— originDevice 必须保持
                // 创建者的 id，否则下一次热点同步就分不清谁该自动上传它了。
                dao.insertOutbox(row)
                applyContribution(storeKey, ownerName, row, +1)
                inserted++
                if (row.groupId.isNotEmpty()) {
                    dao.recomputeOrderGroup(
                        storeKey,
                        OutboxContribution.partsOf(ownerName, row).fileId,
                        row.groupId,
                        now(),
                    )
                }
            }
        }
        if (inserted > 0) audit("热点同步接收了 $inserted 笔未上传订单（跳过 $skipped 笔已有的）")
        return AdoptReport(inserted, skipped, conflicts)
    }

    /**
     * 把本机**未上传**的行导出成可以发给别的设备的形状。
     *
     * ⚠ 排除 `PROMOTED`，理由见 [adoptForeign]：状态只能由同步路径写。
     * ⚠ 也排除 `DRAFT`：草稿连"成交了吗"都没确定，传过去会让对方算进营业额。
     */
    suspend fun exportForPeer(storeKey: String): List<OrderOutboxEntity> =
        dao.outboxPending(storeKey).filter { it.state != OrderOutboxEntity.DRAFT }

    // ==================== 状态机（不碰贡献） ====================
    //
    // 下面这几个方法**都不改统计**：`READY` / `IN_FLIGHT` / `FAILED_PERMANENT` 三种状态
    // 都在给 `OUTBOX` 桶贡献（只有 `DRAFT` 与 `PROMOTED` 不贡献）。
    // 所以状态迁移与搬桶是两件独立的事，混在一起的话每次加一个状态都要重新想一遍钱的事。

    /**
     * write-ahead 起飞：`READY → IN_FLIGHT`，**返回非 null 才允许真的发 `/add`**。
     *
     * 顺序不能反。先发请求再落盘的话，"请求已经出去了但本地不记得"这个窗口里进程被杀，
     * 重启后这一行还是 READY → 再发一次 → 服务器文件里同一个 uuid 两行（服务端不查重）
     * → 镜像比服务器长 → 此后所有偏移静默错位。
     *
     * `markOutboxInFlight` 的 `AND state = 'READY'` 同时挡住了两个上传协程抢同一行。
     */
    suspend fun takeOff(storeKey: String, orderUuid: String): OrderOutboxEntity? {
        if (dao.markOutboxInFlight(storeKey, orderUuid) != 1) return null
        // 起飞后重读：调用方要拿 payload 发请求，而它必须是**库里那一份**。
        return dao.outboxRow(storeKey, orderUuid)
    }

    /**
     * 结果**确定没有提交**的失败（429 / 401 / 403）：退回 `READY` 等下一次。
     *
     * @param countAttempt 是否计入重推次数。429 与鉴权失败**不该计**——
     *        那不是这一单的问题，计了的话一次早上的重登风暴就能把全店的单烧进 FAILED_PERMANENT。
     */
    suspend fun backToReady(storeKey: String, orderUuid: String, error: String?, countAttempt: Boolean): Int {
        if (countAttempt) dao.bumpOutboxAttempt(storeKey, orderUuid, error)
        return dao.transitionOutbox(
            storeKey, orderUuid,
            expectedState = OrderOutboxEntity.IN_FLIGHT,
            toState = OrderOutboxEntity.READY,
            lastError = error,
        )
    }

    /**
     * 结果**未知**的失败（超时 / 连接被切 / 500）：**留在 `IN_FLIGHT`**，只记一次尝试。
     *
     * ⚠ 千万不要退回 READY。服务器可能已经收下了，退回就等于授权重推 → 重复订单。
     * 留在 IN_FLIGHT 会让 `OutboxUploader.recoverInFlight` 在重启/重连后先同步该 fileId、
     * 在 `order_index` 里找这个 uuid：找到 → 当成功；没找到 → 才允许重推。
     */
    suspend fun noteUnknownResult(storeKey: String, orderUuid: String, error: String?): Int =
        dao.bumpOutboxAttempt(storeKey, orderUuid, error)

    /**
     * 重推到顶、或拿到了"重推也不会有不同结果"的错误（413 请求体过大、400 参数不合法）：
     * 转 `FAILED_PERMANENT` 交人工。
     *
     * 413 尤其不能重推：同样大小的 body 只会再拿一个 413，
     * 白烧三次 `attemptCount` 之后还是这里 —— 但那时用户已经等了三轮退避。
     * 413 的正确反应是**拆单**，而拆单发生在 [enqueue]，不是重试。
     */
    suspend fun failPermanently(
        storeKey: String,
        ownerName: String,
        orderUuid: String,
        error: String?,
    ): Int {
        // 先读再翻：读到的 state 就是"贡献是按哪个桶加进去的"那一个。
        val before = dao.outboxRow(storeKey, orderUuid) ?: return 0
        var moved = 0
        inTransaction {
            moved = dao.transitionOutbox(
                storeKey, orderUuid,
                expectedState = OrderOutboxEntity.IN_FLIGHT,
                toState = OrderOutboxEntity.FAILED_PERMANENT,
                lastError = error,
            )
            // ⚠ 只有 CAS 真的命中（返回 1）才搬桶。
            // 行已经不是 IN_FLIGHT（比如同一时刻被 promoteFromOutbox 提升了）的话，
            // 搬桶就会把钱从一个错误的桶里减走 —— 而那边从来没加过。
            if (moved == 1) {
                moveScope(
                    storeKey, ownerName, before,
                    StatsProjection.OUTBOX_SCOPE, StatsProjection.OUTBOX_FAILED_SCOPE,
                )
            }
        }
        return moved
    }

    // ==================== 内部 ====================

    /**
     * 给一行加/减贡献。**桶由 [StatsProjection.scopeOfOutboxState] 决定**，
     * 不是这里硬编码 `OUTBOX_SCOPE`。
     *
     * ⚠ 加与减必须走同一个函数：`FAILED_PERMANENT` 的钱在 `OUTBOX_FAILED` 桶里，
     * 删掉它时若还从 `OUTBOX` 桶减，就是一个桶永久多一笔、另一个永久少一笔 ——
     * 差一分钱不会报错，只会让营业额偏掉，而且偏在哪一天、哪个人名下都查不出来。
     */
    private suspend fun applyContribution(
        storeKey: String,
        ownerName: String,
        row: OrderOutboxEntity,
        sign: Int,
    ) {
        val scope = StatsProjection.scopeOfOutboxState(row.state) ?: return
        val parts = OutboxContribution.partsOf(ownerName, row)
        val contribs = OutboxContribution.of(row, parts, timeZone())
        if (contribs.isEmpty()) return
        for (c in contribs) {
            dao.applyContribDelta(storeKey, scope, c.dimKey, c.metric, sign * c.value)
        }
    }

    /**
     * 把一行的贡献从 [from] 桶搬到 [to] 桶（同一个 dimKey、同一批 metric）。
     *
     * 必须在**同一个事务**里做完：搬一半就是双算或漏算，而两者都只体现为
     * "营业额对不上"，查不出是哪一笔。
     */
    private suspend fun moveScope(
        storeKey: String,
        ownerName: String,
        row: OrderOutboxEntity,
        from: String,
        to: String,
    ) {
        if (from == to) return
        val parts = OutboxContribution.partsOf(ownerName, row)
        for (c in OutboxContribution.of(row, parts, timeZone())) {
            dao.applyContribDelta(storeKey, from, c.dimKey, c.metric, -c.value)
            dao.applyContribDelta(storeKey, to, c.dimKey, c.metric, +c.value)
        }
    }

    private fun partsOfReq(ownerName: String, req: OrderGroupAssembler.Request): OrderFileId.Parts =
        if (req.fileType == OrderFileId.TYPE_A) {
            OrderFileId.Parts(OrderFileId.TYPE_A, ownerName, req.year, OrderFileId.NO_MONTH)
        } else {
            OrderFileId.Parts(OrderFileId.TYPE_B, ownerName, req.year, req.month ?: OrderFileId.NO_MONTH)
        }
}
