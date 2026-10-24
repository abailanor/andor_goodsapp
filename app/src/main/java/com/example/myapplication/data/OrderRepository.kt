package com.example.myapplication.data

import com.example.myapplication.db.ContribAggregate
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.StatsProjection
import com.example.myapplication.sync.outbox.OutboxStore
import com.example.myapplication.sync.outbox.OutboxUploader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Locale
import java.util.TimeZone

/**
 * 订单列表的一行。**已同步与未上传合并成一个视图**。
 *
 * ## 为什么合并
 *
 * 分成两个页面的话，用户要在"我开过哪些单"与"哪些单还没上去"之间来回切，
 * 而这两件事在店里是同一段对话：结账时发现网络不通 → 单子进了未上传 →
 * 网络恢复后他要确认它上去了。拆开就等于让他自己记住哪些看过、哪些没看过。
 *
 * 用户要的"弹窗询问是否同步这些数据 + 勾选一部分同步 + 其余删除"也正好落在这一个列表上。
 */
data class OrderRow(
    val orderUuid: String,
    val writer: String,
    /** `content.ts`。为 0 表示这笔订单的时间是**猜的**（别的客户端没写这个字段）。 */
    val orderTs: Long,
    val totalCents: Long,
    val itemCount: Int,
    /** `1` 正常 / `0` 已取消 / `2` 已完成（仅 A 类）/ `3` 已完成后被删除（仅 A 类）。 */
    val status: Int,
    /** 空串 = 不是拆单。非空时同组的其它块要在 UI 上归到一起。 */
    val groupId: String,
    val source: Source,
    /**
     * 这一行是**别的设备**通过热点同步传过来的，不是本机开的。
     *
     * ⚠ 外部单**只能手动勾选上传**，绝不参与"全选可上传的"或自动批量上传：
     * 正常情况下创建它的那台设备自己会上传，两边都传就是同一个 uuid 在服务端
     * 文件里出现两行（服务端不查重）+ 库存扣两次。手动勾选是"接管"——
     * 用于那台设备再也不会开机了的情况。
     *
     * 空 `originDevice` 算本机（v5 迁移前的老行，本来就是这台设备开的）。
     */
    val isForeign: Boolean = false,
    /**
     * `A` = 进货 / `B` = 销售（[com.example.myapplication.sync.OrderFileId.TYPE_A] / `TYPE_B`）。
     *
     * 订单页要按开单模式筛选：销售模式下不该看到进货单，反之亦然 ——
     * 两者混在一个列表里，用户会以为进货单也是一笔营收。
     */
    val fileType: String,
    /** `true` = 总额被手动覆盖过（`Σ items.amount != total`）。UI 要打标记，不静默取其一。 */
    val manualTotal: Boolean,
    val remark: String?,
    /**
     * 给用户看的一句补充说明：缺哪些箱规、或者上次上传失败的错误。
     * 其它状态为 null。
     */
    val detail: String? = null,
) {

    /**
     * 这一行来自哪儿。**决定了 UI 上能对它做什么**：
     *
     * | Source | 能上传 | 能删除 | 能取消 |
     * |---|---|---|---|
     * | [SYNCED] | — | ✗（要走取消补丁） | ✓ |
     * | [PENDING_READY] | ✓ | ✓ | ✗ |
     * | [PENDING_BLOCKED] | ✗（先补箱规） | ✓ | ✗ |
     * | [PENDING_IN_FLIGHT] | ✗（正在飞） | ✗ | ✗ |
     * | [PENDING_FAILED] | ✗（重推无意义） | ✓（唯一出路） | ✗ |
     * | [DRAFT] | ✗ | ✓ | ✗ |
     */
    enum class Source {
        /** 已经在镜像里，服务器上有这一行。 */
        SYNCED,
        PENDING_READY,
        PENDING_BLOCKED,
        /** 结果未知。**不许重推也不许删** —— 服务端可能已经收下了。 */
        PENDING_IN_FLIGHT,
        PENDING_FAILED,
        DRAFT,
        ;

        val isPending: Boolean get() = this != SYNCED
    }
}

/**
 * 订单列表 + 对它的三个动作（上传选中的、删除、刷新阻塞）。
 *
 * ## 这一层为什么薄
 *
 * 状态机、搬桶、CAS 全在 [OutboxStore] 与 [OutboxUploader] 里，而且在那边被测。
 * 这里只做两件事：**把两张表合成一个列表**、把 UI 的动作转发过去。
 *
 * 唯一有实质逻辑的是 [merge]，它被抽成 companion 里的纯函数，
 * 就是为了能拿两组构造好的行直接断言，不必启动 Room。
 */
class OrderRepository(
    private val dao: OrderFileDao,
    private val outbox: OutboxStore,
    private val uploader: OutboxUploader,
    private val storeKey: () -> String,
    private val ownerName: () -> String,
    /** 本机设备 id，用来把"别的设备传来的单"标出来。见 [OrderRow.isForeign]。 */
    private val deviceId: () -> String = { "" },
    private val audit: (String) -> Unit = {},
) {

    /**
     * @param fromTs / @param toTs 半开区间 `[from, to)`。**to 必须给**：
     *        不给上限的话"全部订单"会一次读几万行进内存。
     * @param writer null = 不按人筛。
     */
    fun orders(fromTs: Long, toTs: Long, writer: String?, limit: Int = DEFAULT_LIMIT): Flow<List<OrderRow>> {
        val key = storeKey()
        return combine(
            dao.observeOrderIndexInRange(key, fromTs, toTs, writer, limit),
            dao.observeOutboxPending(key),
        ) { synced, pending ->
            // outbox 那侧的 DAO 查询不带日期与人筛（它是全店一张表，行数很少），
            // 所以在内存里过滤 —— 与已同步侧保持同一个区间，否则两边会显示不同的范围。
            val pendingHere = pending.filter { r ->
                r.orderTs in fromTs until toTs && (writer == null || r.writer == writer)
            }
            merge(synced, pendingHere, deviceId())
        }
    }

    /**
     * 上传选中的订单。**只发 [OrderRow.Source.PENDING_READY] 的那些**，其余静默跳过并记审计。
     *
     * 逐个串行发，不并发：429 的桶按 IP 共享，一次并发十笔等于自己把自己锁死。
     *
     * @return 每笔的结果，UI 据此汇总"N 笔成功、M 笔待重试"。
     */
    suspend fun uploadSelected(orderUuids: List<String>): List<OutboxUploader.UploadResult> {
        val key = storeKey()
        val owner = ownerName()
        val out = ArrayList<OutboxUploader.UploadResult>(orderUuids.size)
        for (uuid in orderUuids) {
            val row = dao.outboxRow(key, uuid)
            if (row == null) {
                audit("上传跳过：$uuid 不在 outbox 里")
                continue
            }
            if (row.state != OrderOutboxEntity.READY) {
                // BLOCKED / IN_FLIGHT / FAILED_PERMANENT / DRAFT 都不该被"上传"按钮发出去。
                // 跳过而不是报错：用户勾了一批，其中一笔状态刚好变了是正常并发。
                audit("上传跳过：$uuid 处于 ${row.state}，只有 READY 能起飞")
                continue
            }
            out += uploader.uploadOne(key, owner, uuid)
        }
        return out
    }

    /**
     * 删掉一笔未上传的订单（连同它同组的其它未上传块 —— 见 [OutboxStore.discard]）。
     *
     * @return 实际删掉的行数。0 = 不存在、或已在飞/已上传（那两种删不掉，要撤只能走取消）。
     */
    suspend fun discard(orderUuid: String): Int = outbox.discard(storeKey(), ownerName(), orderUuid)

    /**
     * 一笔订单的明细。
     *
     * ## ⚠ 两个来源，一套结果
     *
     * 已同步的单从 `order_item_index` 读；未上传的单**没有**索引行（它还没进镜像），
     * 明细只能从 `order_outbox.payload` 里解析。两者必须归一成同一个形状，
     * 否则详情页要写两套渲染，而"未上传的单看不到明细"正是用户会当成 bug 报的那种缺失。
     *
     * `unitPriceCents` 为 null 表示那一行没有单价（早期写入的订单）——
     * 详情页与 `ReceiptMath` 一样，缺了就**不显示**折扣，绝不拿当前目录价补。
     */
    suspend fun detail(row: OrderRow): OrderDetail? {
        val key = storeKey()
        val lines: List<OrderDetailLine> = if (row.source == OrderRow.Source.SYNCED) {
            // SYNCED 行来自 order_index，它的 fileId 没存进 OrderRow（省一列），
            // 但 order_item_index 的主键需要它 —— 用 uuid 在两张表里都能定位，
            // 所以这里直接按 uuid 查全部 fileId 下的行。一笔订单不会跨文件。
            dao.orderItemsOfUuid(key, row.orderUuid).map {
                OrderDetailLine(it.barcode, it.name, it.quantity, it.amountCents, null)
            }
        } else {
            val payload = dao.outboxRow(key, row.orderUuid)?.payload ?: return null
            OrderPayloadIndex.parse(payload)?.items?.map {
                OrderDetailLine(it.barcode, it.name, it.quantity, it.amountCents, it.unitPriceCents)
            } ?: emptyList()
        }
        return OrderDetail(
            orderUuid = row.orderUuid,
            writer = row.writer,
            orderTs = row.orderTs,
            totalCents = row.totalCents,
            fileType = row.fileType,
            source = row.source,
            manualTotal = row.manualTotal,
            remark = row.remark,
            groupId = row.groupId,
            lines = lines,
        )
    }

    /**
     * 箱规补齐之后重新核对所有挂起的单。
     *
     * @param hasBoxSize 由调用方查商品表提供 —— [OutboxStore] 故意不持有 `ProductDao`，
     *        否则"outbox 的阻塞状态"就要依赖商品同步的时序，而商品同步随时可能被一次 FULL 打断。
     * @return 被放行的 uuid，UI 可以提示"N 张单现在可以上传了"。
     */
    suspend fun refreshBlocked(hasBoxSize: (String) -> Boolean): List<String> =
        outbox.refreshBlocked(storeKey(), hasBoxSize)

    companion object {

        const val DEFAULT_LIMIT = 500

        /**
         * 把已同步与未上传两侧合成一个按时间倒序的列表。**纯函数**，所以可以直接测。
         *
         * ## ⚠ 按 uuid 去重，且**已同步优先**
         *
         * 正常情况下同一个 uuid 不会同时出现在两侧（outbox 查询排除了 `PROMOTED`）。
         * 真出现了就是 `OutboxUploader.Recovery.PromoteMissed` 那个不变量被破坏了 ——
         * 索引行已经建了（`FILE:` 桶算过这笔钱）而 outbox 行还没提升（`OUTBOX` 桶也算着）。
         * 这里保留已同步那一行，因为服务器上有它、取消要走它的偏移；
         * 而重复显示两行会让用户以为开了两笔单。
         * 审计由 `OutboxUploader` 负责，这里不重复喊一次。
         */
        fun merge(
            synced: List<OrderIndexEntity>,
            pending: List<OrderOutboxEntity>,
            myDeviceId: String = "",
        ): List<OrderRow> {
            val rows = ArrayList<OrderRow>(synced.size + pending.size)
            val seen = HashSet<String>(synced.size + pending.size)
            for (s in synced) {
                seen += s.orderUuid
                rows += OrderRow(
                    orderUuid = s.orderUuid,
                    writer = s.writer,
                    orderTs = s.orderTs,
                    totalCents = s.totalCents,
                    itemCount = s.itemCount,
                    status = s.status,
                    groupId = s.groupId.orEmpty(),
                    source = OrderRow.Source.SYNCED,
                    // order_index 不存 fileType 列，但 fileId 的第一个字符就是它
                    // （`A:<owner>:<年>` / `B:<owner>:<年>-<月>`，见 OrderFileId）。
                    fileType = s.fileId.take(1),
                    manualTotal = s.manualTotal,
                    remark = s.remark,
                )
            }
            for (p in pending) {
                if (p.orderUuid in seen) continue
                rows += OrderRow(
                    orderUuid = p.orderUuid,
                    writer = p.writer,
                    orderTs = p.orderTs,
                    totalCents = p.totalCents,
                    itemCount = p.itemCount,
                    // outbox 里的单还没进镜像，服务端那个 status 字节还不存在。
                    // 一律按"正常"显示：它确实是一笔成交了的单，只是没上传。
                    status = 1,
                    groupId = p.groupId,
                    source = sourceOf(p.state),
                    // 空串算本机：那是 v5 迁移前的老行，本来就是这台设备开的。
                    // 判成"外部"的话用户自己开的单会永远不自动上传。
                    isForeign = p.originDevice.isNotEmpty() && p.originDevice != myDeviceId,
                    fileType = p.fileType,
                    manualTotal = false,
                    remark = null,
                    detail = detailOf(p),
                )
            }
            // 两侧各自有序（SQL 的 ORDER BY），合并后要重新排一次。
            // orderTs 相同的（同一秒开两笔）用 uuid 兜底，保证顺序稳定 ——
            // 不稳定的顺序会让 Compose 的列表在每次重组时跳动。
            return rows.sortedWith(compareByDescending<OrderRow> { it.orderTs }.thenBy { it.orderUuid })
        }

        private fun sourceOf(state: String): OrderRow.Source = when (state) {
            OrderOutboxEntity.READY -> OrderRow.Source.PENDING_READY
            OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE -> OrderRow.Source.PENDING_BLOCKED
            OrderOutboxEntity.IN_FLIGHT -> OrderRow.Source.PENDING_IN_FLIGHT
            OrderOutboxEntity.FAILED_PERMANENT -> OrderRow.Source.PENDING_FAILED
            OrderOutboxEntity.DRAFT -> OrderRow.Source.DRAFT
            // PROMOTED 不该出现在这里（DAO 查询排除了它）。真出现了就当已同步显示，
            // 因为服务器上有这一行 —— 显示成"未上传"会诱导用户再点一次上传。
            else -> OrderRow.Source.SYNCED
        }

        /** 缺哪些箱规 / 上次为什么失败。这两个是用户唯一能据此采取行动的信息。 */
        private fun detailOf(p: OrderOutboxEntity): String? = when (p.state) {
            OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE -> {
                val codes = OutboxStore.splitCsv(p.blockedBarcodes)
                p.blockedReason ?: if (codes.isEmpty()) null else "缺 ${codes.size} 个商品的箱规"
            }
            OrderOutboxEntity.FAILED_PERMANENT ->
                "上传失败（已试 ${p.attemptCount} 次）：${p.lastError ?: "原因未知"}"
            OrderOutboxEntity.IN_FLIGHT ->
                "已发出、结果未知。重启或重连后会自动核对，不要重复上传。"
            else -> null
        }
    }
}

/** 订单详情的一行。已同步与未上传两条来路都归一成这个形状，见 [OrderRepository.detail]。 */
data class OrderDetailLine(
    val barcode: String,
    val name: String,
    val quantity: Int,
    val amountCents: Long,
    /** null = 这一行没有单价（早期写入的订单）。缺了就别显示折扣，不要拿目录价补。 */
    val unitPriceCents: Long?,
)

/** 一笔订单的完整信息，给详情页/打印用。 */
data class OrderDetail(
    val orderUuid: String,
    val writer: String,
    val orderTs: Long,
    val totalCents: Long,
    val fileType: String,
    val source: OrderRow.Source,
    val manualTotal: Boolean,
    val remark: String?,
    val groupId: String,
    val lines: List<OrderDetailLine>,
) {
    /**
     * 小票底部那两行。与 [ReceiptMath] 共用同一套规则（≤0 不显示），
     * 因为屏幕上看到的与打印出来的必须是同一份数字。
     */
    val breakdown: ReceiptMath.Breakdown
        get() = ReceiptMath.of(
            lines.map { OrderPayloadIndex.Item(it.barcode, it.name, it.quantity, it.amountCents, it.unitPriceCents) },
            totalCents,
        )
}

/**
 * 统计读取层。
 *
 * ## 数据从哪来
 *
 * `stats_contrib` 的 `dimKey` 是 `<yyyy-MM-dd>|w:<writer>`（日期在前才能走范围裁剪），
 * 三个 metric 是 `amountCents` / `orderCount` / `pieceCount`。
 * 这一层只做两件事：把聚合行**摊平成 UI 要的形状**、以及算出那个 `*`。
 *
 * ⚠ `stats_agg` 那张增量缓存表**目前无写入方**（由 P8 的 StatsProjector 维护），
 * 所以这里直接查 `stats_contrib` 现算，不碰 `stats_agg`。
 */
class StatsRepository(
    private val dao: OrderFileDao,
    private val storeKey: () -> String,
    private val ownerName: () -> String,
) {

    /** 一段时间内的统计。 */
    data class Summary(
        val amountCents: Long,
        val orderCount: Long,
        val pieceCount: Long,
        /** 按店员分。`writer` 是下单人，可能与登录人不同（代传场景）。 */
        val byWriter: List<WriterStat>,
        /** 按天分，升序。 */
        val byDay: List<DayStat>,
        /**
         * **这些数字是下界**：区间里有月份的数据本地不完整。UI 必须显示 `*`。
         *
         * 两种成因，都汇到这一个标记上：
         *  - 从没同步过（`file_state` 没有那一行，或 `firstSyncedAt == null`）——
         *    新手机刚登录、或后台补跑的月份预算（默认 12 个月）没覆盖到；
         *  - 用户主动删过本地文件（`partial`，P10）。
         *
         * 做过一次完整 FULL 之后 [incompleteMonths] 会变空，`*` 自动消失。
         */
        val incompleteMonths: List<String>,
    ) {
        val partial: Boolean get() = incompleteMonths.isNotEmpty()
    }

    data class WriterStat(val writer: String, val amountCents: Long, val orderCount: Long, val pieceCount: Long)
    data class DayStat(val date: String, val amountCents: Long, val orderCount: Long, val pieceCount: Long)

    /**
     * @param fromDate / @param toDate `yyyy-MM-dd`，**闭区间**（SQL 的 BETWEEN）。
     */
    fun summary(fromDate: String, toDate: String): Flow<Summary> =
        dao.observeContribInRange(storeKey(), fromDate, toDate)
            .combine(dao.observeFileStates(storeKey())) { rows, states ->
                summarize(rows, states, ownerName(), fromDate, toDate)
            }

    companion object {

        /**
         * 摊平 + 算 `*`。**纯函数**，所以那些"少算一天""把两个店员并成一个"的错误能被直接测到。
         */
        fun summarize(
            rows: List<ContribAggregate>,
            fileStates: List<FileStateEntity>,
            ownerName: String,
            fromDate: String,
            toDate: String,
        ): Summary {
            // dimKey → (metric → value)
            val byDim = HashMap<String, MutableMap<String, Long>>()
            for (r in rows) {
                byDim.getOrPut(r.dimKey) { HashMap() }[r.metric] = r.total
            }

            var amount = 0L
            var orders = 0L
            var pieces = 0L
            val writers = HashMap<String, LongArray>()   // [amount, orders, pieces]
            val days = HashMap<String, LongArray>()

            for ((dimKey, metrics) in byDim) {
                val a = metrics[StatsProjection.METRIC_AMOUNT] ?: 0L
                val o = metrics[StatsProjection.METRIC_ORDERS] ?: 0L
                val p = metrics[StatsProjection.METRIC_PIECES] ?: 0L
                amount += a
                orders += o
                pieces += p

                val date = StatsProjection.dateOf(dimKey)
                val writer = StatsProjection.writerOf(dimKey)
                days.merge3(date, a, o, p)
                writers.merge3(writer, a, o, p)
            }

            return Summary(
                amountCents = amount,
                orderCount = orders,
                pieceCount = pieces,
                byWriter = writers.map { (w, v) -> WriterStat(w, v[0], v[1], v[2]) }
                    .sortedByDescending { it.amountCents },
                byDay = days.map { (d, v) -> DayStat(d, v[0], v[1], v[2]) }.sortedBy { it.date },
                incompleteMonths = incompleteMonths(fileStates, ownerName, fromDate, toDate),
            )
        }

        /**
         * 区间里哪些月份的本地数据不完整。
         *
         * 判据是**每个 B 类月份文件**：`file_state` 里没有那一行、或 `firstSyncedAt == null`
         * （"还没同步过"的判据是它，不是 `version == 0` —— 空文件同步完 version 也是 0）。
         *
         * ⚠ 只看 B 类：只有 B 类计入营收（A 类是向总部订货）。
         * A 类没同步不影响这里的数字是不是下界。
         */
        fun incompleteMonths(
            fileStates: List<FileStateEntity>,
            ownerName: String,
            fromDate: String,
            toDate: String,
        ): List<String> {
            val byFileId = fileStates.associateBy { it.fileId }
            val out = ArrayList<String>()
            for ((year, month) in monthsBetween(fromDate, toDate)) {
                val st = byFileId[OrderFileId.ofB(ownerName, year, month)]
                if (st == null || st.firstSyncedAt == null) {
                    // Locale 固定：默认 Locale 可能输出非 ASCII 数字（见 StatsProjection 类注释），
                    // 而这个字符串是要拿去与 dimKey 的日期段比的。
                    out += String.format(Locale.US, "%04d-%02d", year, month)
                }
            }
            return out
        }

        /** 闭区间 `[from, to]` 覆盖到的所有 (年, 月)。 */
        fun monthsBetween(fromDate: String, toDate: String): List<Pair<Int, Int>> {
            // 日期是恒宽的 `yyyy-MM-dd`，所以字典序 == 时间序，可以直接比字符串。
            // ⚠ 不能只比 (年, 月)：`"2026-09-30" → "2026-09-01"` 写反了但同属九月，
            // 按月比会当成合法区间放过去，于是算出一个只有一个月的 `*`，静默错。
            if (toDate < fromDate) return emptyList()
            val from = parseYm(fromDate) ?: return emptyList()
            val to = parseYm(toDate) ?: return emptyList()
            val out = ArrayList<Pair<Int, Int>>()
            var y = from.first
            var m = from.second
            // 上限防呆：区间写反或写错成 9999 年时不要在这里转几万圈
            var guard = 0
            while ((y < to.first || (y == to.first && m <= to.second)) && guard++ < 1200) {
                out += y to m
                if (m == 12) { y++; m = 1 } else m++
            }
            return out
        }

        /** `yyyy-MM-dd` → (年, 月)。取不到就 null，不猜。 */
        private fun parseYm(date: String): Pair<Int, Int>? {
            val parts = date.split('-')
            if (parts.size < 2) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return if (m in 1..12) y to m else null
        }

        private fun HashMap<String, LongArray>.merge3(key: String, a: Long, o: Long, p: Long) {
            val v = getOrPut(key) { LongArray(3) }
            v[0] += a
            v[1] += o
            v[2] += p
        }
    }
}
