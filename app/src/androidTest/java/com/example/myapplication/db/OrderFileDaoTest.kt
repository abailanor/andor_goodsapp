package com.example.myapplication.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.StatsProjection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `OrderFileDao` 的**真 SQL** 测试（instrumented，跑真 Room + 真 SQLite）。
 *
 * ## 与 `RoomMirrorStateStoreTest`（JVM）的分工
 *
 * 那边用 `FakeOrderFileDao` 照抄 SQL 语义、测 store 的编排（幂等、加减对称）；
 * 这边测**那些被照抄的语义本身**：
 * `IGNORE` 冲突真的返回 `-1`、`SUM()` 没行时真的是 `NULL`、`REPLACE` 真的整行覆盖、
 * `markDiverged` 的 SET 列表里真的没有 `firstSyncedAt`、`substr` 对中文真的是按字符而不是按字节。
 *
 * 两边刻意断言同一批场景 —— fake 抄错了这边会红，SQL 改错了那边会红。
 *
 * ## 为什么这些必须真机验
 *
 * 每一条的失败模式都是**静默**的：
 *  - `IGNORE` 若返回 0 而不是 -1 → 每次重复投递都再加一遍贡献 → 营业额偏大；
 *  - `SUM()` 若返回 0 而不是 NULL → "这单没有明细"与"明细合计为 0"分不开；
 *  - `markDiverged` 若清了 `firstSyncedAt` → 自愈之后 UI 把这个月显示成"从没同步过"；
 *  - `substr` 若按字节 → 中文店主的业绩被并进一个乱码名字下，数字只是偏小，看不出异常。
 *
 * ## ⚠ 方法名里**不能有空格**（androidTest 专有约束）
 *
 * JVM 单测可以写 `` `401 空响应体` ``，instrumented 测试不行：要过 D8，而
 * `Space characters in SimpleName … are not allowed prior to DEX version 040`，
 * DEX 040 需要 minSdk 30（本项目 24）。后果是 `dexBuilderDebugAndroidTest` **整个失败**，
 * 不是这一条测试失败 —— 一个带空格的名字能让全部真 SQL 测试都跑不起来，
 * 而 `compileDebugAndroidTestKotlin` 是绿的，所以很容易漏。一律用下划线。
 */
@RunWith(AndroidJUnit4::class)
class OrderFileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OrderFileDao
    private val store = "00002"
    private val other = "00003"
    private val sep = "B:alice:2026-09"
    private val oct = "B:alice:2026-10"
    private val aFile = "A:alice:2026"
    private val scope = StatsProjection.fileScope(sep)
    private val dimKey = "2026-09-15|w:alice"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.orderFileDao()
    }

    @After
    fun tearDown() = db.close()

    // ==================== 写入辅助 ====================

    private fun fileState(
        fileId: String = sep,
        storeKey: String = store,
        version: Int = 5,
        firstSyncedAt: Long? = 777L,
        syncState: String = "LIVE",
    ): FileStateEntity {
        // 年/月/类型从 fileId 解析出来，不硬编码 —— 排序那条断言依赖它们真的对得上。
        // 写死 `year = 2026` 的话 "B:alice:2025-12" 会被排到 2026 年的月份后面，
        // 而失败信息看起来像 ORDER BY 写错了。
        val p = OrderFileId.parse(fileId)
        return FileStateEntity(
            storeKey = storeKey,
            fileId = fileId,
            fileType = p.type,
            year = p.year,
            month = p.month,
            version = version,
            lastStreamId = "$version-0",
            checksum = 4242,
            mirrorBytes = 4096L,
            firstSyncedAt = firstSyncedAt,
            syncState = syncState,
            lastSyncAttemptAt = 1L,
        )
    }

    private fun order(
        orderUuid: String,
        fileId: String = sep,
        storeKey: String = store,
        writer: String = "alice",
        status: Int = 1,
        orderTs: Long = 1789430400000L,
        statusOffset: Long = 100L,
        remarkOffset: Long? = null,
        remark: String? = null,
    ) = OrderIndexEntity(
        storeKey = storeKey,
        fileId = fileId,
        orderUuid = orderUuid,
        writer = writer,
        status = status,
        orderTs = orderTs,
        totalCents = 5200L,
        manualTotal = false,
        itemCount = 1,
        lineStart = 4L,
        uuidOffset = 20L,
        statusOffset = statusOffset,
        remarkOffset = remarkOffset,
        remark = remark,
        appliedFileVersion = 5,
        cancelAppliedVersion = null,
        groupId = null,
    )

    private fun item(orderUuid: String, lineNo: Int, quantity: Int, fileId: String = sep) =
        OrderItemIndexEntity(
            storeKey = store, fileId = fileId, orderUuid = orderUuid, lineNo = lineNo,
            barcode = "690123456789$lineNo", name = "可口可乐330ml",
            quantity = quantity, amountCents = 0L,
        )

    private fun contrib(
        dimKey: String = this.dimKey,
        metric: String = StatsProjection.METRIC_AMOUNT,
        value: Long = 0L,
        frozen: Int = 0,
        scope: String = this.scope,
        storeKey: String = store,
    ) = StatsContribEntity(storeKey, scope, dimKey, metric, value, frozen)

    private fun one(query: String, vararg args: Any?): Long {
        val c = db.openHelper.readableDatabase.query(query, args)
        c.use { it.moveToFirst(); return it.getLong(0) }
    }

    /**
     * 读一条贡献的值。**没有那一行时返回 [absent]**，
     * 这样"行不存在"与"值恰好是 0"在断言里分得开 ——
     * 取消回退到 0 与"从来没加过"是两件完全不同的事。
     */
    private fun valueOf(
        metric: String = StatsProjection.METRIC_AMOUNT,
        dimKey: String = this.dimKey,
        scope: String = this.scope,
        storeKey: String = store,
        absent: Long = ABSENT,
    ): Long = one(
        "SELECT IFNULL(SUM(value), ?) FROM stats_contrib " +
            "WHERE storeKey = ? AND scope = ? AND dimKey = ? AND metric = ?",
        absent, storeKey, scope, dimKey, metric,
    )

    private companion object {
        /** 不可能是真实贡献值的哨兵。 */
        const val ABSENT = Long.MIN_VALUE
    }

    // ==================== order_index：IGNORE 与 rowId ====================

    @Test
    fun `IGNORE_冲突返回负一_新插入返回正数`() = runBlocking {
        // ⚠ 这一条是整个幂等模型的地基。`RoomMirrorStateStore.applyAdded` 就靠
        // `-1` 判断"贡献已经加过了"。真 SQLite 若返回 0（`INSERT OR IGNORE` 的
        // changes() 就是 0），那条判据永远不会命中 → 每次双投递都再加一遍营业额。
        val first = dao.insertOrderIndex(order("u1"))
        assertTrue("新插入必须返回正的 rowId，实际 $first", first > 0L)
        assertEquals(-1L, dao.insertOrderIndex(order("u1")))
        assertEquals(1, dao.countOrderIndexesOfFile(store, sep))
    }

    @Test
    fun `批量插入同样是_IGNORE_重复_uuid_只留第一行`() = runBlocking {
        // 服务端不查重：某台设备盲重试过 /add，文件里就会有同 uuid 的两行。
        // 主键容不下两行 → 第一行赢。于是"扫描行数 > 插入行数"，
        // commitFull 靠比对这两个数字记审计。
        dao.insertOrderIndexes(listOf(order("u1"), order("u1"), order("u2")))
        assertEquals(2, dao.countOrderIndexesOfFile(store, sep))
        assertNotNull(dao.orderIndex(store, sep, "u1"))
        assertNotNull(dao.orderIndex(store, sep, "u2"))
    }

    @Test
    fun `主键含_fileId_同一个_uuid_在两个文件里是两行`() = runBlocking {
        dao.insertOrderIndex(order("u1", fileId = sep))
        dao.insertOrderIndex(order("u1", fileId = oct))
        assertEquals(1, dao.countOrderIndexesOfFile(store, sep))
        assertEquals(1, dao.countOrderIndexesOfFile(store, oct))
    }

    @Test
    fun `删除与计数都按_店铺_加_文件_限定`() = runBlocking {
        dao.insertOrderIndex(order("u1"))
        dao.insertOrderIndex(order("u2"))
        dao.insertOrderIndex(order("u3", fileId = oct))
        dao.insertOrderIndex(order("u4", storeKey = other))

        // ⚠ 少写一个 `AND fileId = ?` 就会把别的月份一起删掉，
        // 而 FULL 重建之后看起来一切正常（那个月本来就要重建）—— 只有别的月份静默空了。
        assertEquals(2, dao.deleteOrderIndexesOfFile(store, sep))
        assertEquals(0, dao.countOrderIndexesOfFile(store, sep))
        assertEquals(1, dao.countOrderIndexesOfFile(store, oct))
        assertEquals(1, dao.countOrderIndexesOfFile(other, sep))
    }

    // ==================== order_index：按偏移反查 ====================

    @Test
    fun `按_statusOffset_反查_且偏移相同的两个文件不会串`() = runBlocking {
        // 流消息里的 CANCEL 可能不带 order_uuid，偏移是唯一的线索。
        // 两个文件的偏移区间天然重叠（都从 4 开始），所以 `AND fileId = ?` 是承重的。
        dao.insertOrderIndex(order("u-sep", fileId = sep, statusOffset = 300L))
        dao.insertOrderIndex(order("u-oct", fileId = oct, statusOffset = 300L))

        assertEquals("u-sep", dao.orderIndexByStatusOffset(store, sep, 300L)!!.orderUuid)
        assertEquals("u-oct", dao.orderIndexByStatusOffset(store, oct, 300L)!!.orderUuid)
        assertNull("偏移不在任何订单上 → UNKNOWN，不猜", dao.orderIndexByStatusOffset(store, sep, 301L))
        assertNull(dao.orderIndexByStatusOffset(other, sep, 300L))
    }

    @Test
    fun `按_remarkOffset_反查_且_null_偏移不会被_0_命中`() = runBlocking {
        // B 类订单的 remarkOffset 是 NULL。`remarkOffset = 0` 在 SQL 里对 NULL 求值
        // 结果是 NULL（不是 TRUE），所以不会被捞出来 —— 这条钉住它。
        dao.insertOrderIndex(order("u-b", remarkOffset = null))
        dao.insertOrderIndex(order("u-a", fileId = aFile, remarkOffset = 500L))
        assertNull(dao.orderIndexByRemarkOffset(store, sep, 0L))
        assertEquals("u-a", dao.orderIndexByRemarkOffset(store, aFile, 500L)!!.orderUuid)
    }

    // ==================== order_index：更新 ====================

    @Test
    fun `updateOrderStatus_能把_cancelAppliedVersion_写回_NULL`() = runBlocking {
        // 总部标完成（1→2）走的就是这一条：newStatusDigit=2、cancelAppliedVersion=null。
        // 若 SQL 写成 `IFNULL(:cancelAppliedVersion, cancelAppliedVersion)` 之类，
        // 一个已完成订单会带着取消版本 → StockLedger 把这笔扣减加回去 → 库存偏高。
        dao.insertOrderIndex(order("u1"))
        assertEquals(1, dao.updateOrderStatus(store, sep, "u1", 0, 7))
        assertEquals(7, dao.orderIndex(store, sep, "u1")!!.cancelAppliedVersion)
        assertEquals(1, dao.updateOrderStatus(store, sep, "u1", 2, null))
        assertNull(dao.orderIndex(store, sep, "u1")!!.cancelAppliedVersion)
        assertEquals(2, dao.orderIndex(store, sep, "u1")!!.status)
    }

    @Test
    fun `updateOrderStatus_对不存在的订单返回_0`() = runBlocking {
        assertEquals(0, dao.updateOrderStatus(store, sep, "nope", 0, 1))
    }

    @Test
    fun `updateOrderRemark_不动偏移`() = runBlocking {
        // 补丁是等长覆写：备注文本变了，它在文件里的位置一个字节都没动。
        // 顺手改掉 remarkOffset 的话，下一次改备注会打在别的字段上 —— 而等长覆写不报错。
        dao.insertOrderIndex(order("u1", fileId = aFile, remarkOffset = 500L, remark = "旧"))
        assertEquals(1, dao.updateOrderRemark(store, aFile, "u1", null))
        val row = dao.orderIndex(store, aFile, "u1")!!
        assertNull("清空备注写 NULL，不写空串（空串会被 trimEnd 判据当成有备注）", row.remark)
        assertEquals(500L, row.remarkOffset)
    }

    // ==================== order_item_index ====================

    @Test
    fun `SUM_在没有行时是_NULL_不是_0`() = runBlocking {
        // `applyPatched` 用 `?: 0` 兜底，兜的是"这单没有明细"。
        // 若 SQL 返回 0，两种情况在数据上完全一样：件数指标少了这一单，且不报错。
        assertNull(dao.sumQuantityOfOrder(store, sep, "u1"))
        dao.insertItems(listOf(item("u1", 0, 14), item("u1", 1, 3)))
        assertEquals("要的是 Σquantity，不是行数", 17, dao.sumQuantityOfOrder(store, sep, "u1"))
    }

    @Test
    fun `明细按主键_REPLACE_重复插同一行不会翻倍`() = runBlocking {
        // FULL 重建与增量 ADD 都可能重投同一批明细。REPLACE 让件数停在真值上。
        dao.insertItems(listOf(item("u1", 0, 14)))
        dao.insertItems(listOf(item("u1", 0, 14)))
        assertEquals(14, dao.sumQuantityOfOrder(store, sep, "u1"))
        dao.insertItems(listOf(item("u1", 0, 9)))
        assertEquals("同主键的新值覆盖旧值", 9, dao.sumQuantityOfOrder(store, sep, "u1"))
    }

    @Test
    fun `删除明细按_店铺_加_文件_限定并返回行数`() = runBlocking {
        dao.insertItems(listOf(item("u1", 0, 1), item("u1", 1, 2)))
        dao.insertItems(listOf(item("u2", 0, 5, fileId = oct)))
        assertEquals(2, dao.deleteItemsOfFile(store, sep))
        assertEquals("别的月份一行都不许动", 5, dao.sumQuantityOfOrder(store, oct, "u2"))
        assertEquals(0, dao.deleteItemsOfFile(store, sep))
    }

    // ==================== stats_contrib ====================

    @Test
    fun `applyContribDelta_先_UPDATE_再_INSERT_两次调用只留一行`() = runBlocking {
        // 没有 SQLite 的 UPSERT（要 3.24 = API 30，minSdk 24），所以是两步。
        // 两步之间被别的线程插入 → 第二次 UPDATE 命中 1 行 → 不会重复建行。
        assertEquals("没有行时 UPDATE 命中 0 行", 0,
            dao.addContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, 1L))
        dao.applyContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, 5200L)
        assertEquals("有行了 → UPDATE 命中 1 行", 1,
            dao.addContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, 0L))
        dao.applyContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, -200L)

        assertEquals(5000L, valueOf())
        assertEquals("两步之间不许长出第二行", 1, one(
            "SELECT COUNT(*) FROM stats_contrib WHERE storeKey = ? AND scope = ? AND dimKey = ? AND metric = ?",
            store, scope, dimKey, StatsProjection.METRIC_AMOUNT,
        ))
    }

    @Test
    fun `负增量减到负数也照写_不夹到零`() = runBlocking {
        // 取消回退发生在 ADD 之前（乱序投递）时值会是负的，之后那笔 ADD 把它拉回 0。
        // 夹到 0 的话这一笔就永久少算 —— 而"营业额偏小"没有任何机制会发现。
        dao.applyContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, -5200L)
        assertEquals(-5200L, valueOf())
        dao.applyContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, 5200L)
        assertEquals("回到 0，但那一行**在**（与 ABSENT 不同）", 0L, valueOf())
    }

    @Test
    fun `applyContribDelta_不覆盖已有行的_frozen`() = runBlocking {
        // 归档是**文件级**事件，由 freezeContribOfScope 统一置位。
        // 若这里顺手写 frozen，归档之后来的一笔迟到消息会把那一行解冻，
        // 下一次 FULL 就会把它删掉重建 → 历史统计被悄悄改写。
        dao.insertContrib(contrib(value = 100L, frozen = 1))
        dao.applyContribDelta(store, scope, dimKey, StatsProjection.METRIC_AMOUNT, 50L, frozen = 0)
        assertEquals(150L, valueOf())
        assertEquals("frozen 必须原样保留", 1L, one(
            "SELECT frozen FROM stats_contrib WHERE storeKey = ? AND scope = ? AND dimKey = ? AND metric = ?",
            store, scope, dimKey, StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `insertContrib_是_IGNORE_insertContribs_是_REPLACE`() = runBlocking {
        // 两者服务于不同的调用点：
        //  - IGNORE 给 applyContribDelta（已有行时它根本不该被调用到）；
        //  - REPLACE 给 commitFull（先 delete 再整批写，但若 delete 漏了 scope 也不能翻倍）。
        assertTrue("第一次插入必须成功", dao.insertContrib(contrib(value = 1L)) > 0L)
        assertEquals("IGNORE：冲突返回 -1", -1L, dao.insertContrib(contrib(value = 2L)))
        assertEquals("IGNORE：第一行赢", 1L, valueOf())

        dao.insertContribs(listOf(contrib(value = 999L)))
        assertEquals("REPLACE：后来的赢", 999L, valueOf())
        assertEquals("REPLACE 也不许多出一行", 1, one(
            "SELECT COUNT(*) FROM stats_contrib WHERE storeKey = ? AND scope = ?", store, scope))
    }

    @Test
    fun `deleteContribOfScope_连冻结行一起删`() = runBlocking {
        // 与方案 §5.4 的字面写法**有意不同**，理由写在 DAO 的注释里：
        // FULL 之后插入的新行 frozen=0，若旧行 frozen=1 留着，
        // 同一个 (dimKey, metric) 会被算两遍 —— 比"归档清零"更隐蔽（数字只是偏大）。
        // "归档不清零"由 freezeContribOfScope 保证，它一行都不删。
        dao.insertContribs(listOf(contrib(value = 100L), contrib(value = 200L, frozen = 1)))
        dao.insertContribs(listOf(contrib(metric = StatsProjection.METRIC_ORDERS, value = 1L)))
        dao.insertContribs(listOf(contrib(value = 7L, scope = StatsProjection.OUTBOX_SCOPE)))

        assertEquals("amount 与 orders 两行都被删掉", 2, dao.deleteContribOfScope(store, scope))
        assertEquals("删干净了，不是留个 0 值行", ABSENT, valueOf())
        assertEquals("OUTBOX 桶不属于任何文件，FULL 碰不到它", 7L,
            valueOf(scope = StatsProjection.OUTBOX_SCOPE))
        assertEquals(1, one("SELECT COUNT(*) FROM stats_contrib WHERE storeKey = ?", store))
    }

    @Test
    fun `freezeContribOfScope_只置位_值一行都不改`() = runBlocking {
        // 归档只是"不再同步"，**不是"不再统计"**。
        dao.insertContribs(listOf(
            contrib(value = 5200L),
            contrib(metric = StatsProjection.METRIC_ORDERS, value = 1L),
            contrib(value = 7L, scope = StatsProjection.OUTBOX_SCOPE),
        ))
        assertEquals("只冻结 FILE 桶的两行", 2, dao.freezeContribOfScope(store, scope))
        assertEquals(5200L, valueOf())
        assertEquals(1L, valueOf(metric = StatsProjection.METRIC_ORDERS))
        assertEquals("冻结是置位，不是清零", 5201L, one(
            "SELECT SUM(value) FROM stats_contrib WHERE storeKey = ? AND scope = ?", store, scope))
        assertEquals(0, one(
            "SELECT COUNT(*) FROM stats_contrib WHERE storeKey = ? AND frozen = 0 AND scope = ?", store, scope))
        assertEquals("别的桶不受影响", 0, one(
            "SELECT frozen FROM stats_contrib WHERE storeKey = ? AND scope = ?",
            store, StatsProjection.OUTBOX_SCOPE))
    }

    // ==================== file_state ====================

    @Test
    fun `markDiverged_归零游标但保留_firstSyncedAt`() = runBlocking {
        dao.upsertFileState(fileState(version = 87, firstSyncedAt = 555L))
        assertEquals(1, dao.markDiverged(store, sep, 9_999L))
        val fs = dao.fileState(store, sep)!!
        assertEquals("DIVERGED", fs.syncState)
        assertEquals(0, fs.version)
        assertNull(fs.lastStreamId)
        assertEquals(0, fs.checksum)
        assertEquals(4, fs.mirrorBytes)
        assertEquals(9_999L, fs.lastSyncAttemptAt)
        assertEquals("三态语义靠它区分'从没同步过'与'同步过但没单'", 555L, fs.firstSyncedAt)
    }

    @Test
    fun `markArchived_把游标原样冻结`() = runBlocking {
        // 归零会导致：① 与 stats_contrib 的记账对不上；
        // ② 运维哪天恢复文件 → version=0 → FULL 覆盖归档镜像，而 order_index 还指着归档路径。
        dao.upsertFileState(fileState(version = 87))
        assertEquals(1, dao.markArchived(store, sep, 9_999L))
        val fs = dao.fileState(store, sep)!!
        assertEquals("ARCHIVED", fs.syncState)
        assertEquals(87, fs.version)
        assertEquals("87-0", fs.lastStreamId)
        assertEquals(4242, fs.checksum)
        assertEquals(4096L, fs.mirrorBytes)
        assertEquals(777L, fs.firstSyncedAt)
        assertEquals(9_999L, fs.lastSyncAttemptAt)
    }

    @Test
    fun `三个标记方法在没有行时返回_0_让调用方去补插`() = runBlocking {
        assertEquals(0, dao.markDiverged(store, sep, 1L))
        assertEquals(0, dao.markArchived(store, sep, 1L))
        assertEquals(0, dao.touchAttempt(store, sep, 1L))
        assertNull(dao.fileState(store, sep))
    }

    @Test
    fun `touchAttempt_只动那一个字段`() = runBlocking {
        dao.upsertFileState(fileState(version = 87))
        assertEquals(1, dao.touchAttempt(store, sep, 4_000L))
        val fs = dao.fileState(store, sep)!!
        assertEquals(4_000L, fs.lastSyncAttemptAt)
        assertEquals(87, fs.version)
        assertEquals("LIVE", fs.syncState)
    }

    @Test
    fun `upsertFileState_是_REPLACE_整行覆盖_包括_firstSyncedAt`() = runBlocking {
        // ⚠ 这条钉住的是一个**陷阱**而不是一个好性质：
        // 调用方若拿一个 firstSyncedAt=null 的 MirrorState 去 upsert，
        // "这个月第一次同步是什么时候"就被抹掉了。
        // `RoomMirrorStateStore` 因此必须把 prev.firstSyncedAt 带进新状态里。
        dao.upsertFileState(fileState(firstSyncedAt = 555L))
        dao.upsertFileState(fileState(version = 6, firstSyncedAt = null))
        val fs = dao.fileState(store, sep)!!
        assertEquals(6, fs.version)
        assertNull(fs.firstSyncedAt)
    }

    @Test
    fun `allFileStates_按_类型_年_月_排序_且只返回本店铺`() = runBlocking {
        // 调度器一次拿全部游标；顺序决定了"先补哪个月"，也决定了 UI 列表的顺序。
        dao.upsertFileState(fileState(fileId = "B:alice:2026-10"))
        dao.upsertFileState(fileState(fileId = "A:alice:2026"))
        dao.upsertFileState(fileState(fileId = "B:alice:2026-09"))
        dao.upsertFileState(fileState(fileId = "B:alice:2025-12"))
        dao.upsertFileState(fileState(fileId = sep, storeKey = other))

        assertEquals(
            listOf("A:alice:2026", "B:alice:2025-12", "B:alice:2026-09", "B:alice:2026-10"),
            dao.allFileStates(store).map { it.fileId },
        )
    }

    @Test
    fun `archiveFile_已有行时只改状态并冻结贡献`() = runBlocking {
        dao.upsertFileState(fileState(version = 87, firstSyncedAt = 555L))
        dao.insertContribs(listOf(contrib(value = 5200L)))

        dao.archiveFile(store, sep, "B", 2026, 9, 9_999L)

        val fs = dao.fileState(store, sep)!!
        assertEquals("ARCHIVED", fs.syncState)
        assertEquals("游标冻结不归零", 87, fs.version)
        assertEquals(555L, fs.firstSyncedAt)
        assertEquals(1, one("SELECT frozen FROM stats_contrib WHERE storeKey = ? AND scope = ?", store, scope))
        assertEquals("历史统计一行都不许少", 5200L, valueOf())
    }

    @Test
    fun `archiveFile_没有行时补一行_版本_0_且_firstSyncedAt_非空`() = runBlocking {
        // 409 探测得到 LATEST = 服务器根本没这个文件。响应体**不含**服务器版本，
        // 所以 0 是唯一诚实的值；而 firstSyncedAt 非空 + version=0 表达的是
        // "同步过了，那个月确实没单"，不是"还没拉"。
        dao.archiveFile(store, oct, "B", 2026, 10, 9_999L)
        val fs = dao.fileState(store, oct)!!
        assertEquals("ARCHIVED", fs.syncState)
        assertEquals(0, fs.version)
        assertNull(fs.lastStreamId)
        assertEquals(9_999L, fs.firstSyncedAt)
        assertEquals("B", fs.fileType)
        assertEquals(2026, fs.year)
        assertEquals(10, fs.month)
    }

    // ==================== 诊断 ====================

    @Test
    fun `countOrdersMissingTs_只数_ts_为_0_的`() = runBlocking {
        dao.insertOrderIndex(order("u1"))
        dao.insertOrderIndex(order("u0", orderTs = 0L))
        dao.insertOrderIndex(order("u9", storeKey = other, orderTs = 0L))
        assertEquals("ts=0 的时间是猜的（用文件月份兜底），诊断页要列出来", 1, dao.countOrdersMissingTs(store))
        assertEquals("别的店铺自己数自己的", 1, dao.countOrdersMissingTs(other))
    }

    @Test
    fun `statsSelfCheck_在_agg_缺失时报出差值_对上时一行都不返回`() = runBlocking {
        // **返回任何一行都是 bug**（P8 的 StatsProjector 落地之前 agg 表是空的，
        // 那时报差值是预期的）。这条测的是 SQL 自己：IFNULL、LEFT JOIN、GROUP BY。
        dao.insertContribs(listOf(
            contrib(value = 5200L),
            contrib(dimKey = "2026-09-16|w:alice", value = 3000L),
            contrib(metric = StatsProjection.METRIC_ORDERS, value = 1L),
        ))
        assertEquals(3, dao.statsSelfCheck(store).size)

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO stats_agg (storeKey, dimKey, metric, value, updatedAt) VALUES (?, ?, ?, ?, ?)",
            arrayOf(store, dimKey, StatsProjection.METRIC_AMOUNT, 5200L, 0L),
        )
        val rest = dao.statsSelfCheck(store)
        assertEquals(2, rest.size)
        assertTrue("对上的那一行必须消失", rest.none {
            it.dimKey == dimKey && it.metric == StatsProjection.METRIC_AMOUNT
        })

        // 同一 dimKey 的两个 scope 会被 GROUP BY 合并 —— 这是有意的：
        // agg 是"用户看到的数"，OUTBOX 桶提升进 FILE 桶之后总额不该变。
        dao.insertContribs(listOf(contrib(value = 800L, scope = StatsProjection.OUTBOX_SCOPE)))
        val m = dao.statsSelfCheck(store).first {
            it.dimKey == dimKey && it.metric == StatsProjection.METRIC_AMOUNT
        }
        assertEquals(6000L, m.contribValue)
        assertEquals(5200L, m.aggValue)
    }

    @Test
    fun `statsSelfCheck_不跨店铺`() = runBlocking {
        dao.insertContribs(listOf(contrib(value = 5200L, storeKey = other)))
        assertTrue(dao.statsSelfCheck(store).isEmpty())
        assertEquals(1, dao.statsSelfCheck(other).size)
    }

    // ==================== substr：dimKey 的 SQL 侧切分 ====================

    @Test
    fun `WRITER_SQL_对中文_writer_也切在正确的字符上`() = runBlocking {
        // `StatsProjectionTest` 只能在 Kotlin 侧断言 `substring(WRITER_SQL_FROM - 1)`。
        // SQLite 的 `substr` 对 TEXT 是**按字符**而不是按字节 —— 但这一点必须真机验：
        // 若它按字节，「张三」的日键会被切成乱码，两个店主的业绩并到一个不存在的名字下，
        // 而统计页只是"某个人的数字偏小"，看不出异常。
        val utc = java.util.TimeZone.getTimeZone("UTC")
        for (writer in listOf("alice", "张三", "b", "a|b")) {
            val key = StatsProjection.dayKey(writer, 1789430400000L, utc)
            dao.insertContribs(listOf(contrib(dimKey = key, value = 1L)))
            val q = db.openHelper.readableDatabase.query(
                "SELECT ${StatsProjection.WRITER_SQL}, ${StatsProjection.DATE_SQL} " +
                    "FROM stats_contrib WHERE storeKey = ? AND dimKey = ?",
                arrayOf(store, key),
            )
            q.use {
                it.moveToFirst()
                assertEquals("writer=$writer", writer, it.getString(0))
                assertEquals("2026-09-15", it.getString(1))
            }
        }
    }

    @Test
    fun `按_writer_分组的月度查询在真_SQL_上给出正确的账`() = runBlocking {
        // 这就是 P8 统计页要跑的那条查询的形状：日期区间用**字符串比较**（能吃上主键索引，
        // 因为日期在 dimKey 最前面且零填充定宽），分组用 substr 切出来的 writer。
        //
        // 两个陷阱在这里同时被钉住：
        //  1. 区间上界写成 `'2026-09-30'`（而不是 `'2026-10-01|'`）会漏掉 9-30 那天；
        //  2. writer 里带 `|` 时，`substr` 从固定位置切所以不会被劈开 —— 日期在最前面。
        val utc = java.util.TimeZone.getTimeZone("UTC")
        suspend fun day(writer: String, dayOfMonth: Int, cents: Long) {
            val ts = 1789430400000L + (dayOfMonth - 15) * 86400000L
            dao.insertContribs(listOf(contrib(
                dimKey = StatsProjection.dayKey(writer, ts, utc), value = cents)))
        }
        day("alice", 15, 5200L)
        day("alice", 30, 100L)
        day("张三", 15, 3000L)
        day("a|b", 15, 700L)
        day("alice", 31, 999L)      // 十月，必须被区间排除
        day("alice", 1, 888L)       // 九月一号，在区间内

        val q = db.openHelper.readableDatabase.query(
            """SELECT ${StatsProjection.WRITER_SQL} AS writer, SUM(value) AS total
               FROM stats_contrib
               WHERE storeKey = ? AND scope = ? AND metric = ?
                 AND dimKey >= '2026-09-01|' AND dimKey < '2026-10-01|'
               GROUP BY writer ORDER BY total DESC""",
            arrayOf(store, scope, StatsProjection.METRIC_AMOUNT),
        )
        val got = LinkedHashMap<String, Long>()
        q.use {
            while (it.moveToNext()) got[it.getString(0)] = it.getLong(1)
        }
        assertEquals(mapOf("alice" to 6188L, "张三" to 3000L, "a|b" to 700L), got)
    }
}
