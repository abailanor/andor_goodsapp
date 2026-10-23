package com.example.myapplication.data

import com.example.myapplication.db.ContribAggregate
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.StatsProjection
import com.example.myapplication.sync.outbox.OutboxStore
import com.example.myapplication.data.StatsRepository.Companion.incompleteMonths
import com.example.myapplication.data.StatsRepository.Companion.monthsBetween
import com.example.myapplication.data.StatsRepository.Companion.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 两个 Repository 里的**纯函数**：`OrderRepository.merge` 与 `StatsRepository.summarize`。
 *
 * 抽成纯函数就是为了能这样测 —— 它们各自都有一种"错了但看不出来"的失败方式：
 *  - `merge` 去重方向反了 → 同一笔单显示两遍，或者显示成"未上传"诱导用户再点一次上传
 *    （而服务端不查重，再点一次就是文件里两行、库存扣两次）；
 *  - `summarize` 把两个店员并进一个 dimKey → 营业额总数对得上，但"谁卖的"全错；
 *  - `incompleteMonths` 判据用错（比如看 `version == 0` 而不是 `firstSyncedAt == null`）
 *    → `*` 该显示时不显示，用户以为数字是全的，而它其实是下界。
 */
class OrderAndStatsRepositoryTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val owner = "alice"

    /** 2026-09-15T00:00:00Z / 09-16 / 09-17。 */
    private val d15 = 1789430400000L
    private val d16 = d15 + 86_400_000L
    private val d17 = d15 + 2 * 86_400_000L

    private val u1 = "11111111-2222-3333-4444-555555555555"
    private val u2 = "22222222-3333-4444-5555-666666666666"
    private val u3 = "33333333-4444-5555-6666-777777777777"

    // ==================== 构造测试数据 ====================

    private fun synced(
        uuid: String,
        ts: Long = d15,
        writer: String = "alice",
        total: Long = 5200L,
        items: Int = 14,
        status: Int = 1,
        groupId: String? = null,
        manualTotal: Boolean = false,
        remark: String? = null,
    ) = OrderIndexEntity(
        storeKey = "00002", fileId = "B:alice:2026-09", orderUuid = uuid, writer = writer,
        status = status, orderTs = ts, totalCents = total, manualTotal = manualTotal,
        itemCount = items, lineStart = 4, uuidOffset = 19, statusOffset = 104,
        remarkOffset = null, remark = remark, appliedFileVersion = 1,
        cancelAppliedVersion = null, groupId = groupId,
    )

    private fun pending(
        uuid: String,
        state: String,
        ts: Long = d16,
        writer: String = "alice",
        total: Long = 3000L,
        items: Int = 3,
        groupId: String = "",
        blockedReason: String? = null,
        blockedBarcodes: String? = null,
        attemptCount: Int = 0,
        lastError: String? = null,
    ) = OrderOutboxEntity(
        storeKey = "00002", orderUuid = uuid, groupId = groupId,
        fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        payload = ByteArray(0), state = state,
        blockedReason = blockedReason, blockedBarcodes = blockedBarcodes,
        attemptCount = attemptCount, lastError = lastError,
        pricedAt = 0L, createdAt = ts, writer = writer,
        totalCents = total, itemCount = items, orderTs = ts,
    )

    private fun fileState(
        year: Int,
        month: Int,
        firstSyncedAt: Long?,
        type: String = OrderFileId.TYPE_B,
        version: Int = 0,
    ) = FileStateEntity(
        storeKey = "00002",
        fileId = if (type == OrderFileId.TYPE_B) OrderFileId.ofB(owner, year, month)
        else OrderFileId.ofA(owner, year),
        fileType = type, year = year, month = month,
        version = version, lastStreamId = null, checksum = 0, mirrorBytes = 4,
        firstSyncedAt = firstSyncedAt, syncState = "LIVE", lastSyncAttemptAt = 0L,
    )

    private fun agg(dimKey: String, metric: String, total: Long) = ContribAggregate(dimKey, metric, total)

    /** 一个 writer 在某天的完整三条贡献。 */
    private fun dayContribs(date: String, writer: String, amount: Long, orders: Long, pieces: Long) = listOf(
        agg("$date|w:$writer", StatsProjection.METRIC_AMOUNT, amount),
        agg("$date|w:$writer", StatsProjection.METRIC_ORDERS, orders),
        agg("$date|w:$writer", StatsProjection.METRIC_PIECES, pieces),
    )

    // ==================== merge ====================

    @Test
    fun `两侧合并按下单时间倒序`() {
        val rows = OrderRepository.merge(
            synced = listOf(synced(u1, ts = d15)),
            pending = listOf(pending(u2, OrderOutboxEntity.READY, ts = d16)),
        )

        // 未上传的那笔更晚，所以排在前面
        assertEquals(listOf(u2, u1), rows.map { it.orderUuid })
        assertEquals(OrderRow.Source.PENDING_READY, rows[0].source)
        assertEquals(OrderRow.Source.SYNCED, rows[1].source)
    }

    @Test
    fun `同_uuid_出现在两侧时保留已同步那一行`() {
        val rows = OrderRepository.merge(
            synced = listOf(synced(u1, total = 5200L, status = 0)),
            pending = listOf(pending(u1, OrderOutboxEntity.IN_FLIGHT, total = 9999L)),
        )

        // 这是 PromoteMissed 那个不变量被破坏时的形状。保留已同步的那一行，
        // 因为服务器上有它、取消要走它的偏移；显示成"未上传"会诱导用户再点一次上传，
        // 而服务端不查重 → 文件里两行 → 库存扣两次。
        assertEquals(1, rows.size)
        assertEquals(OrderRow.Source.SYNCED, rows.single().source)
        assertEquals(5200L, rows.single().totalCents)
        assertEquals(0, rows.single().status)
    }

    @Test
    fun `outbox_各状态映射到正确的_Source`() {
        val rows = OrderRepository.merge(
            synced = emptyList(),
            pending = listOf(
                pending(u1, OrderOutboxEntity.READY, ts = d15),
                pending(u2, OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, ts = d16),
                pending(u3, OrderOutboxEntity.IN_FLIGHT, ts = d17),
            ),
        )

        val byUuid = rows.associateBy { it.orderUuid }
        assertEquals(OrderRow.Source.PENDING_READY, byUuid[u1]!!.source)
        assertEquals(OrderRow.Source.PENDING_BLOCKED, byUuid[u2]!!.source)
        assertEquals(OrderRow.Source.PENDING_IN_FLIGHT, byUuid[u3]!!.source)
        assertTrue(byUuid.values.all { it.source.isPending })
    }

    @Test
    fun `PROMOTED_不该被当成未上传显示`() {
        // DAO 查询已经排除了它；这里防的是"将来有人放宽那条 SQL"。
        // 显示成未上传会诱导用户再点一次上传。
        val rows = OrderRepository.merge(emptyList(), listOf(pending(u1, OrderOutboxEntity.PROMOTED)))

        assertEquals(OrderRow.Source.SYNCED, rows.single().source)
        assertFalse(rows.single().source.isPending)
    }

    @Test
    fun `BLOCKED_行带出缺哪些箱规`() {
        val rows = OrderRepository.merge(
            emptyList(),
            listOf(
                pending(
                    u1, OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE,
                    blockedReason = OutboxStore.blockedReason(2),
                    blockedBarcodes = "6901234567890,6901234567891",
                )
            ),
        )

        assertEquals(OutboxStore.blockedReason(2), rows.single().detail)
    }

    @Test
    fun `BLOCKED_行没有文案时按条码数兜一句`() {
        val rows = OrderRepository.merge(
            emptyList(),
            listOf(pending(u1, OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, blockedBarcodes = "a,b,c")),
        )

        assertEquals("缺 3 个商品的箱规", rows.single().detail)
    }

    @Test
    fun `FAILED_行带出错误与尝试次数`() {
        val rows = OrderRepository.merge(
            emptyList(),
            listOf(
                pending(
                    u1, OrderOutboxEntity.FAILED_PERMANENT,
                    attemptCount = 3, lastError = "请求体超 20KB，应拆单而不是重试",
                )
            ),
        )

        val d = rows.single().detail!!
        assertTrue(d, d.contains("3 次"))
        assertTrue(d, d.contains("20KB"))
    }

    @Test
    fun `IN_FLIGHT_行的文案明确说不要重复上传`() {
        val rows = OrderRepository.merge(emptyList(), listOf(pending(u1, OrderOutboxEntity.IN_FLIGHT)))

        // 结果未知时重推 = 服务端可能已经有这一行了 → 同 uuid 两行 → 镜像比服务器长
        assertTrue(rows.single().detail!!.contains("不要重复上传"))
    }

    @Test
    fun `READY_行不需要补充说明`() {
        val rows = OrderRepository.merge(emptyList(), listOf(pending(u1, OrderOutboxEntity.READY)))

        assertEquals(null, rows.single().detail)
    }

    @Test
    fun `outbox_行的状态一律显示为正常_因为它还没进镜像`() {
        val rows = OrderRepository.merge(emptyList(), listOf(pending(u1, OrderOutboxEntity.READY)))

        // 服务端那个 status 字节还不存在，本地不该假装它已取消/已完成
        assertEquals(1, rows.single().status)
    }

    @Test
    fun `时间相同时用_uuid_兜底_保证顺序稳定`() {
        val a = synced(u1, ts = d15)
        val b = synced(u2, ts = d15)

        // 不稳定的顺序会让 Compose 的列表在每次重组时跳动
        assertEquals(
            OrderRepository.merge(listOf(a, b), emptyList()).map { it.orderUuid },
            OrderRepository.merge(listOf(b, a), emptyList()).map { it.orderUuid },
        )
    }

    @Test
    fun `已同步行的手工总额标记与备注被带出来`() {
        val rows = OrderRepository.merge(
            listOf(synced(u1, manualTotal = true, remark = "少收了两块")),
            emptyList(),
        )

        assertTrue(rows.single().manualTotal)
        assertEquals("少收了两块", rows.single().remark)
    }

    @Test
    fun `两侧都空时返回空列表`() {
        assertTrue(OrderRepository.merge(emptyList(), emptyList()).isEmpty())
    }

    // ==================== dimKey 的逆运算 ====================

    @Test
    fun `dateOf_与_writerOf_是_dayKey_的逆运算`() {
        val key = StatsProjection.dayKey("丽华金龙", d15, utc)

        assertEquals("2026-09-15", StatsProjection.dateOf(key))
        // writer 可以是中文，而且不能因为多字节就被截断
        assertEquals("丽华金龙", StatsProjection.writerOf(key))
    }

    @Test
    fun `writer_为空串时也能拆回来`() {
        val key = StatsProjection.dayKey("", d15, utc)

        assertEquals("2026-09-15", StatsProjection.dateOf(key))
        assertEquals("", StatsProjection.writerOf(key))
    }

    @Test
    fun `writer_里含分隔符时不会被截断`() {
        // writer 是自由文本（服务端只验"非空白"），可以含 '|'
        val key = StatsProjection.dayKey("a|b", d15, utc)

        assertEquals("a|b", StatsProjection.writerOf(key))
    }

    // ==================== summarize ====================

    @Test
    fun `三个_metric_汇总正确`() {
        val s = summarize(
            dayContribs("2026-09-15", "alice", 5200, 1, 14) +
                dayContribs("2026-09-16", "alice", 3000, 2, 6),
            emptyList(), owner, "2026-09-01", "2026-09-30",
        )

        assertEquals(8200L, s.amountCents)
        assertEquals(3L, s.orderCount)
        assertEquals(20L, s.pieceCount)
    }

    @Test
    fun `按_writer_分组不串_即使同一天`() {
        val s = summarize(
            dayContribs("2026-09-15", "alice", 5200, 1, 14) +
                dayContribs("2026-09-15", "bob", 3000, 1, 3),
            emptyList(), owner, "2026-09-01", "2026-09-30",
        )

        assertEquals(8200L, s.amountCents)
        assertEquals(2, s.byWriter.size)
        val alice = s.byWriter.first { it.writer == "alice" }
        val bob = s.byWriter.first { it.writer == "bob" }
        assertEquals(5200L, alice.amountCents)
        assertEquals(14L, alice.pieceCount)
        assertEquals(3000L, bob.amountCents)
        // 同一天的两个店员必须合成一个 DayStat，但**不能**合成一个 WriterStat
        assertEquals(1, s.byDay.size)
        assertEquals(8200L, s.byDay.single().amountCents)
    }

    @Test
    fun `byWriter_按金额降序_byDay_按日期升序`() {
        val s = summarize(
            dayContribs("2026-09-17", "bob", 900, 1, 1) +
                dayContribs("2026-09-15", "alice", 500, 1, 1) +
                dayContribs("2026-09-16", "carol", 700, 1, 1),
            emptyList(), owner, "2026-09-01", "2026-09-30",
        )

        assertEquals(listOf("bob", "carol", "alice"), s.byWriter.map { it.writer })
        assertEquals(listOf("2026-09-15", "2026-09-16", "2026-09-17"), s.byDay.map { it.date })
    }

    @Test
    fun `没有贡献行时全是零_而不是_null`() {
        val s = summarize(emptyList(), emptyList(), owner, "2026-09-01", "2026-09-30")

        assertEquals(0L, s.amountCents)
        assertTrue(s.byWriter.isEmpty())
        assertTrue(s.byDay.isEmpty())
    }

    // ==================== incompleteMonths：那个 `*` ====================

    @Test
    fun `file_state_里没有那一行_就是没同步过`() {
        val months = incompleteMonths(emptyList(), owner, "2026-08-01", "2026-09-30")

        assertEquals(listOf("2026-08", "2026-09"), months)
    }

    @Test
    fun `firstSyncedAt_为_null_时算不完整_即使_version_已经大于_0`() {
        // ⚠ 判据必须是 firstSyncedAt == null，不是 version == 0：
        // 一个空文件同步完之后 version 也是 0，而它确实同步过了。
        // 用 version 判的话每个还没开过单的新月份都会被误标成"数据不全"。
        val months = incompleteMonths(
            listOf(fileState(2026, 9, firstSyncedAt = null, version = 7)),
            owner, "2026-09-01", "2026-09-30",
        )

        assertEquals(listOf("2026-09"), months)
    }

    @Test
    fun `firstSyncedAt_非_null_且_version_为_0_时算完整`() {
        val months = incompleteMonths(
            listOf(fileState(2026, 9, firstSyncedAt = d15, version = 0)),
            owner, "2026-09-01", "2026-09-30",
        )

        assertTrue(months.isEmpty())
    }

    @Test
    fun `全部同步过时_partial_为_false`() {
        val s = summarize(
            dayContribs("2026-09-15", "alice", 5200, 1, 14),
            listOf(fileState(2026, 9, firstSyncedAt = d15)),
            owner, "2026-09-01", "2026-09-30",
        )

        assertFalse(s.partial)
        assertTrue(s.incompleteMonths.isEmpty())
    }

    @Test
    fun `只看_B_类_A_类没同步不影响营收是不是下界`() {
        // A 类是向总部订货，不计入营收（StatsProjection.contributionOf 对非 B 类返回空）
        val months = incompleteMonths(
            listOf(
                fileState(2026, 9, firstSyncedAt = d15),                       // B 类，已同步
                fileState(2026, 0, firstSyncedAt = null, type = OrderFileId.TYPE_A),  // A 类，没同步
            ),
            owner, "2026-09-01", "2026-09-30",
        )

        assertTrue(months.isEmpty())
    }

    @Test
    fun `跨年区间正确枚举月份`() {
        val months = incompleteMonths(emptyList(), owner, "2025-11-15", "2026-02-03")

        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02"), months)
    }

    @Test
    fun `同一个月内的区间只算一次`() {
        assertEquals(
            listOf(2026 to 9),
            monthsBetween("2026-09-01", "2026-09-30"),
        )
    }

    @Test
    fun `区间写反或日期非法时返回空_而不是转几万圈`() {
        assertTrue(monthsBetween("2026-09-30", "2026-09-01").isEmpty())
        assertTrue(monthsBetween("不是日期", "2026-09-30").isEmpty())
        assertTrue(monthsBetween("2026-13-01", "2026-13-30").isEmpty())
    }

    @Test
    fun `ownerName_错了就会把每个月都标成不完整`() {
        // 这条不是断言"代码有 bug"，而是钉住一个**依赖关系**：
        // `*` 的判据要靠 ownerName 拼出正确的 fileId。员工登录时若误用了 username，
        // 这里会拼出一个服务器上并不存在的 fileId → 每个月都判成"没同步过" → 满屏星号。
        // 星号本身不会报错，所以这是唯一能发现那个错误的地方。
        val syncedStates = listOf(fileState(2026, 9, firstSyncedAt = d15))

        assertTrue(incompleteMonths(syncedStates, owner, "2026-09-01", "2026-09-30").isEmpty())
        assertEquals(
            listOf("2026-09"),
            incompleteMonths(syncedStates, "clerk1", "2026-09-01", "2026-09-30"),
        )
    }
}
