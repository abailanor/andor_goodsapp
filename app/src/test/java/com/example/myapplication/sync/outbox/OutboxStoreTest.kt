package com.example.myapplication.sync.outbox

import com.example.myapplication.db.OrderGroupEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.StatsProjection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

/**
 * [OutboxStore] 的两件事：**状态机的 CAS 守卫**与 **`OUTBOX` 桶的加减对称**。
 *
 * 这里的每条断言都对应一种"算错了但不会报错"的情形：
 *  - 贡献加了两次 / 减漏了一次 → 营业额永久偏掉，而且偏在哪一天、哪个人名下都查不出来；
 *  - `takeOff` 的 CAS 失效 → 两个上传协程发同一笔 → 服务端不查重 → 同 uuid 两行 →
 *    镜像比服务器长 → 此后所有偏移静默错位；
 *  - `noteUnknownResult` 退回 READY → "其实已经收下了"被重推 → 同上；
 *  - `unblockOutbox` 的 WHERE 漏了状态条件 → 把 FAILED_PERMANENT 一起放出去重推。
 *
 * SQL 语义本身（`ABORT` 真的抛、`UPDATE` 真的返回受影响行数）由 androidTest 的
 * `OrderFileDaoTest` 对着真 Room 覆盖；[FakeOrderFileDao] 只对齐**可观测语义**。
 *
 * ⚠ `promoteFromOutbox`（`OUTBOX → FILE:` 的搬桶）**不在这里测** —— 它的执行者是
 * `RoomMirrorStateStore`，用例在 `RoomMirrorStateStoreTest`。两边刻意不重叠：
 * 搬桶只有一个执行者，测试也应当只有一处。
 */
class OutboxStoreTest {

    private val dao = FakeOrderFileDao()
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")
    private var clock = 1_000L

    /** 顺序发号，让"哪一块拿到哪个 uuid"可断言。格式必须合法（服务端要求 8-4-4-4-12 十六进制）。 */
    private var uuidSeq = 0

    /** 非空时优先从这里取，用来故意制造 uuid 撞车。 */
    private val forcedUuids = ArrayDeque<String>()

    private val store = OutboxStore(
        dao = dao,
        // 单线程测试：直接跑。生产实现是 db.withTransaction（见 OutboxStore.of）
        inTransaction = { body -> body() },
        timeZone = { utc },
        now = { clock },
        uuidFactory = { forcedUuids.removeFirstOrNull() ?: nextUuid() },
        audit = { audits += it },
    )

    private fun nextUuid(): String {
        uuidSeq++
        return "aaaaaaaa-bbbb-cccc-dddd-" + uuidSeq.toString(16).padStart(12, '0')
    }

    private val storeKey = "00002"
    private val ownerName = "alice"

    /** 2026-09-15T00:00:00Z。 */
    private val sept15 = 1789430400000L
    private val dimKey = "2026-09-15|w:alice"

    /** 400 行 × 约 85 字节 ≈ 34KB，稳稳超过拆单的 18000 字节预算。 */
    private val splitLines = 400

    // ==================== 构造测试数据 ====================

    private fun line(
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        qty: Int = 14,
        amount: String = "52.00",
    ) = OrderLine(barcode, name, qty, amount, "1.00")

    /** n 条互不相同的行，用来把订单顶过拆单的字节预算。 */
    private fun manyLines(n: Int, amountEach: String = "1.00"): List<OrderLine> =
        (1..n).map { line(barcode = "69012345${it.toString().padStart(6, '0')}", qty = 1, amount = amountEach) }

    private fun req(
        fileType: String = OrderFileId.TYPE_B,
        year: Int = 2026,
        month: Int? = 9,
        writer: String = "alice",
        ts: Long = sept15,
        lines: List<OrderLine> = listOf(line()),
        totalOverrideCents: Long? = null,
        remarkText: String? = null,
    ) = OrderGroupAssembler.Request(fileType, year, month, writer, lines, ts, totalOverrideCents, remarkText)

    private fun enqueue(
        r: OrderGroupAssembler.Request = req(),
        missing: List<String> = emptyList(),
        replacing: List<String> = emptyList(),
    ): OutboxStore.Enqueued = runBlocking {
        store.enqueue(storeKey, ownerName, r, missing, replacing)
    }

    private fun outboxTotal(metric: String): Long =
        dao.contribTotal(storeKey, StatsProjection.OUTBOX_SCOPE, metric)

    private fun outboxRows(): Int =
        dao.contrib.keys.count { it.scope == StatsProjection.OUTBOX_SCOPE }

    private fun row(uuid: String): OrderOutboxEntity = dao.outbox[storeKey to uuid]!!

    private fun takeOff(uuid: String): OrderOutboxEntity? = runBlocking { store.takeOff(storeKey, uuid) }

    // ==================== enqueue：贡献 ====================

    @Test
    fun `enqueue_把金额_单数_件数三个_metric_都加进_OUTBOX_桶`() {
        enqueue(req(lines = listOf(line(qty = 14, amount = "52.00"))))

        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, outboxTotal(StatsProjection.METRIC_PIECES))
        // dimKey 由 payload 里的 writer 与 content.ts 决定
        assertEquals(
            5200L,
            dao.contribValue(storeKey, StatsProjection.OUTBOX_SCOPE, dimKey, StatsProjection.METRIC_AMOUNT),
        )
    }

    @Test
    fun `enqueue_后行是_READY_且库里的_payload_能被自己的解析器读回来`() {
        val uuid = enqueue().rows.single().orderUuid

        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
        // payload 是**序列化后的字节**，不是 draft。之后上传发的就是这一份，一个字都不会变，
        // 所以它必须自洽：读回来的金额要与入库列一致。
        val parsed = OrderPayloadIndex.parse(row(uuid).payload)
        assertNotNull("入库的 payload 解析不出来", parsed)
        assertEquals(row(uuid).totalCents, parsed!!.totalCents)
        assertEquals(row(uuid).itemCount, parsed.items.size)
    }

    @Test
    fun `A_类订单不产出任何_OUTBOX_贡献行`() {
        enqueue(req(fileType = OrderFileId.TYPE_A, month = null, remarkText = "进货一批"))

        // 不是"贡献为 0"，而是**根本没有行** —— 见 OutboxContribution.of 的注释：
        // 值为 0 的行会把自检查询淹掉。A 类是向总部进货，不是本店收入。
        assertEquals(0, outboxRows())
    }

    @Test
    fun `enqueue_单块时_groupId_存空串而不是_null`() {
        val e = enqueue()

        assertNull(e.groupId)
        // 列是 NOT NULL，UI 判分组时把空串当"自成一组"
        assertEquals("", e.rows.single().groupId)
    }

    // ==================== enqueue：拆单 ====================

    @Test
    fun `拆成多块时每块一行_共享同一个_groupId_且贡献之和等于各块之和`() {
        val e = enqueue(req(lines = manyLines(splitLines)))

        assertTrue("应当被拆开，实际只有 ${e.chunkCount} 块", e.chunkCount > 1)
        assertNotNull(e.groupId)
        // 每块都存同一个 grp（不是空串）
        assertTrue(e.rows.all { it.groupId == e.groupId })
        // uuid 互异：服务端不查重，同 uuid 两行会让镜像比服务器长
        assertEquals(e.chunkCount, e.rows.map { it.orderUuid }.distinct().size)
        // 金额守恒：桶里的总额 == 各块 totalCents 之和 == 400 × 1.00
        assertEquals(e.rows.sumOf { it.totalCents }.toLong(), outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(40000L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        // 单数 = 块数（每块在服务器上是独立的一行订单）
        assertEquals(e.chunkCount.toLong(), outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(splitLines.toLong(), outboxTotal(StatsProjection.METRIC_PIECES))
    }

    @Test
    fun `拆单后_order_group_记下块数与总额`() {
        val e = enqueue(req(lines = manyLines(splitLines)))
        val gid = e.groupId!!

        val g = dao.groups[storeKey to gid]
        assertNotNull(g)
        assertEquals(e.chunkCount, g!!.orderCount)
        assertEquals(40000L, g.totalCents)
        assertEquals(OrderGroupEntity.CANCEL_NONE, g.cancelState)
    }

    // ==================== enqueue：BLOCKED ====================

    @Test
    fun `缺箱规时整单挂起为_BLOCKED_并记下条码_CSV_与文案`() {
        val e = enqueue(missing = listOf("6901234567890", "6901234567891"))

        assertTrue(e.blocked)
        val row = e.rows.single()
        assertEquals(OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, row.state)
        assertEquals("6901234567890,6901234567891", row.blockedBarcodes)
        assertEquals(OutboxStore.blockedReason(2), row.blockedReason)
    }

    @Test
    fun `挂起的是整单_每一块都是_BLOCKED_而不是只挂缺规格的那一块`() {
        // 半张销售单 = 库存只扣三分之二、统计只算三分之二，而服务器上的 grp 看起来完全正常
        val e = enqueue(req(lines = manyLines(splitLines)), missing = listOf("69012345000001"))

        assertTrue(e.chunkCount > 1)
        assertTrue(e.rows.all { it.state == OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE })
    }

    @Test
    fun `BLOCKED_的行仍然贡献统计`() {
        // 货已经给客人了，只是箱规没补齐不能上传 —— 营业额必须看得到，否则用户以为丢单
        enqueue(missing = listOf("6901234567890"))

        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    // ==================== enqueue：换单（replacing） ====================

    @Test
    fun `replacing_在同一步里删旧行并减回旧贡献`() {
        val old = enqueue().rows.single().orderUuid
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))

        // 价格漂移后"用新价重算"：新 uuid + 新 payload 插一行，旧行删掉
        val new = enqueue(req(lines = listOf(line(amount = "60.00"))), replacing = listOf(old))

        assertNull("旧行必须被删掉", dao.outbox[storeKey to old])
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, new.rows.single().orderUuid))
        // 净值 = 新单的钱，旧单那笔不能残留
        assertEquals(6000L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, outboxTotal(StatsProjection.METRIC_ORDERS))
    }

    @Test
    fun `replacing_一个不在_outbox_里的_uuid_只记审计不抛`() {
        val e = enqueue(replacing = listOf("ffffffff-ffff-ffff-ffff-ffffffffffff"))

        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertTrue(audits.any { "不在 outbox 里" in it })
        assertEquals(1, e.chunkCount)
    }

    @Test
    fun `replacing_撞_IN_FLIGHT_必须抛_而不是悄悄换掉`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, dao.outboxState(storeKey, uuid))

        try {
            enqueue(replacing = listOf(uuid))
            fail("换掉 IN_FLIGHT 的订单应当抛：它可能已经发给服务端了")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("IN_FLIGHT"))
        }
        // 旧行必须还在（不能被删掉一半）
        assertNotNull(dao.outbox[storeKey to uuid])
    }

    @Test
    fun `replacing_撞_PROMOTED_必须抛`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)
        assertEquals(1, runBlocking { dao.markOutboxPromoted(storeKey, uuid) })

        try {
            enqueue(replacing = listOf(uuid))
            fail("换掉 PROMOTED 的订单应当抛：服务器上已经有这一行了")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("PROMOTED"))
        }
        // PROMOTED 行是"这个 uuid 已上传"的唯一凭据，绝不能被删
        assertEquals(OrderOutboxEntity.PROMOTED, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `撞已存在的_uuid_必须抛_因为_insertOutbox_是_ABORT_不是_REPLACE`() {
        val uuid = enqueue().rows.single().orderUuid
        val before = row(uuid).payload.copyOf()

        // REPLACE 会悄悄换掉一份**不可变**的 payload：已发出去的字节与库里存的字节不一致，
        // 偏移全部作废，且没有任何机制会报错。
        forcedUuids += uuid
        try {
            enqueue(req(lines = listOf(line(amount = "99.00"))))
            fail("uuid 撞车应当抛")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UNIQUE"))
        }
        assertTrue("原 payload 必须一个字节都没变", before.contentEquals(row(uuid).payload))
    }

    // ==================== 加减对称 ====================

    @Test
    fun `enqueue_后_discard_OUTBOX_桶净值回到_0`() {
        val uuid = enqueue(req(lines = listOf(line(qty = 14, amount = "52.00")))).rows.single().orderUuid

        assertEquals(1, runBlocking { store.discard(storeKey, ownerName, uuid) })

        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_PIECES))
        assertNull(dao.outbox[storeKey to uuid])
    }

    @Test
    fun `discard_连带删掉同组的其它未上传块`() {
        val e = enqueue(req(lines = manyLines(splitLines)))
        assertTrue(e.chunkCount > 1)

        // 只删一块的话，剩下的块上传后组成一张少了几行的销售单，而服务器上的 grp 看起来完全正常
        val deleted = runBlocking { store.discard(storeKey, ownerName, e.rows.first().orderUuid) }

        assertEquals(e.chunkCount, deleted)
        assertTrue(e.rows.none { dao.outbox.containsKey(storeKey to it.orderUuid) })
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `删光整组后空的_order_group_行被删掉`() {
        val e = enqueue(req(lines = manyLines(splitLines)))
        val gid = e.groupId!!
        assertNotNull(dao.groups[storeKey to gid])

        runBlocking { store.discard(storeKey, ownerName, e.rows.first().orderUuid) }

        assertFalse("空组必须删行，否则未上传列表里永远挂着一个 0 单的组", dao.groups.containsKey(storeKey to gid))
    }

    @Test
    fun `discard_对_IN_FLIGHT_拒删_且不减贡献`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)

        assertEquals(0, runBlocking { store.discard(storeKey, ownerName, uuid) })
        assertEquals(OrderOutboxEntity.IN_FLIGHT, dao.outboxState(storeKey, uuid))
        // 钱还在账上：这一单可能已经被服务端收下了
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertTrue(audits.any { "拒绝删除" in it })
    }

    @Test
    fun `discard_一个不存在的_uuid_返回_0_而不是抛`() {
        assertEquals(0, runBlocking { store.discard(storeKey, ownerName, "ffffffff-ffff-ffff-ffff-ffffffffffff") })
        assertEquals(0, outboxRows())
    }

    // ==================== 状态机 ====================

    @Test
    fun `takeOff_只有第一次返回行_第二次返回_null`() {
        val uuid = enqueue().rows.single().orderUuid

        val first = takeOff(uuid)
        val second = takeOff(uuid)

        assertNotNull(first)
        // CAS（`AND state = 'READY'`）：两个上传协程抢同一行时只有一个能拿到 1
        assertNull(second)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `takeOff_返回的是库里那一份_payload`() {
        val uuid = enqueue().rows.single().orderUuid
        val stored = row(uuid).payload.copyOf()

        val taken = takeOff(uuid)

        // 重推时重新序列化会让字节变掉 → 偏移变掉 → remarkOffset 全错
        assertTrue(stored.contentEquals(taken!!.payload))
    }

    @Test
    fun `takeOff_对_BLOCKED_的行返回_null`() {
        val uuid = enqueue(missing = listOf("6901234567890")).rows.single().orderUuid

        assertNull(takeOff(uuid))
        assertEquals(OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `takeOff_对已_PROMOTED_的行返回_null`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)
        runBlocking { dao.markOutboxPromoted(storeKey, uuid) }

        // 重推一个已上传的 uuid = 服务器文件里出现两行 = 镜像比服务器长
        assertNull(takeOff(uuid))
    }

    @Test
    fun `backToReady_把_IN_FLIGHT_退回_READY_且_countAttempt_false_不烧次数`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)

        val n = runBlocking { store.backToReady(storeKey, uuid, "429 太频繁", countAttempt = false) }

        assertEquals(1, n)
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
        // 429 与鉴权失败不是这一单的问题：计了的话一次早上的重登风暴就能把全店的单烧进 FAILED_PERMANENT
        assertEquals(0, row(uuid).attemptCount)
        assertEquals("429 太频繁", row(uuid).lastError)
    }

    @Test
    fun `backToReady_带_countAttempt_true_时加一次尝试`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)

        runBlocking { store.backToReady(storeKey, uuid, "网络抖动", countAttempt = true) }

        assertEquals(1, row(uuid).attemptCount)
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `backToReady_对不是_IN_FLIGHT_的行返回_0_且不改状态`() {
        val uuid = enqueue().rows.single().orderUuid

        // expectedState 守卫：READY 的行不该被"退回 READY"
        val n = runBlocking { store.backToReady(storeKey, uuid, "x", countAttempt = false) }

        assertEquals(0, n)
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
        assertNull(row(uuid).lastError)
    }

    @Test
    fun `noteUnknownResult_只记一次尝试_状态留在_IN_FLIGHT`() {
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)

        val n = runBlocking { store.noteUnknownResult(storeKey, uuid, "读超时") }

        assertEquals(1, n)
        // ⚠ 千万不要退回 READY：服务端可能已经收下了，退回就等于授权重推 → 重复订单
        assertEquals(OrderOutboxEntity.IN_FLIGHT, dao.outboxState(storeKey, uuid))
        assertEquals(1, row(uuid).attemptCount)
        assertEquals("读超时", row(uuid).lastError)
    }

    @Test
    fun `failPermanently_从_IN_FLIGHT_转_FAILED_PERMANENT_并对_READY_无效`() {
        val a = enqueue().rows.single().orderUuid
        takeOff(a)
        assertEquals(1, runBlocking { store.failPermanently(storeKey, ownerName, a, "413 请求体过大") })
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, dao.outboxState(storeKey, a))

        val b = enqueue(req(lines = listOf(line(barcode = "6909999999999")))).rows.single().orderUuid
        assertEquals(0, runBlocking { store.failPermanently(storeKey, ownerName, b, "不该生效") })
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, b))
    }

    @Test
    fun `FAILED_PERMANENT_的钱搬进_FAILED_桶_删掉时从那个桶减回来`() {
        // 货已经给客人了，钱真收了。是否计入营收由用户决定（设置里的开关），
        // 而开关要能在**读取侧**生效，就必须先把这笔钱单独放一个桶。
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))

        runBlocking { store.failPermanently(storeKey, ownerName, uuid, "400") }

        // 搬走了：OUTBOX 归零、OUTBOX_FAILED 接住，**总额不变**
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(5200L, failedTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, failedTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, failedTotal(StatsProjection.METRIC_PIECES))

        // ⚠ 删掉时必须从 FAILED 桶减。从 OUTBOX 减的话一个桶永久多一笔、
        // 另一个永久少一笔 —— 差一分钱不会报错，只会让营业额偏掉。
        runBlocking { store.discard(storeKey, ownerName, uuid) }
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(0L, failedTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `IN_FLIGHT_留在_OUTBOX_桶_不进_FAILED_桶`() {
        // IN_FLIGHT 是"结果未知"，服务端很可能已经收下了。
        // 记进失败桶的话，用户一关开关这笔钱就消失，而它其实已经在服务器上了。
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)

        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(0L, failedTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `FAILED_行之后被提升时_从_FAILED_桶减而不是从_OUTBOX_桶减`() {
        // 那一单其实早就传上去了，只是本机之前判成了失败（比如 500 之后重推撞顶）。
        // 从错误的桶减会让 OUTBOX 留下一笔永远搬不走的钱，而 OUTBOX_FAILED 变成负数。
        val uuid = enqueue().rows.single().orderUuid
        takeOff(uuid)
        runBlocking { store.failPermanently(storeKey, ownerName, uuid, "到顶了") }
        assertEquals(5200L, failedTotal(StatsProjection.METRIC_AMOUNT))

        // 模拟 promoteFromOutbox 的减法（真实执行者是 RoomMirrorStateStore）
        runBlocking {
            val row = dao.outbox[storeKey to uuid]!!
            val scope = StatsProjection.scopeOfOutboxState(row.state)
            assertEquals(StatsProjection.OUTBOX_FAILED_SCOPE, scope)
            for (c in OutboxContribution.of(row, OutboxContribution.partsOf(ownerName, row), utc)) {
                dao.applyContribDelta(storeKey, scope!!, c.dimKey, c.metric, -c.value)
            }
            dao.markOutboxPromoted(storeKey, uuid)
        }

        assertEquals(0L, failedTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals("不该在 OUTBOX 桶里留下负数", 0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `自检把两个桶都算上`() {
        val a = enqueue().rows.single().orderUuid
        val b = enqueue(req(lines = listOf(line(barcode = "6909999999999")))).rows.single().orderUuid
        takeOff(b)
        runBlocking { store.failPermanently(storeKey, ownerName, b, "413") }

        // 一笔在 OUTBOX、一笔在 OUTBOX_FAILED，两笔都是 B 类未上传 → 自检必须为 0
        assertEquals(0L, runBlocking { dao.outboxContribSelfCheck(storeKey) })
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(5200L, failedTotal(StatsProjection.METRIC_AMOUNT))
        assertNotNull(a)
    }

    @Test
    fun `状态迁移本身不动贡献_三个_metric_全程不变`() {
        val uuid = enqueue().rows.single().orderUuid
        val before = metrics()

        runBlocking {
            store.takeOff(storeKey, uuid)
            store.backToReady(storeKey, uuid, "429", countAttempt = false)
            store.takeOff(storeKey, uuid)
            store.noteUnknownResult(storeKey, uuid, "超时")
            store.failPermanently(storeKey, ownerName, uuid, "到顶了")
        }

        // READY / IN_FLIGHT / FAILED_PERMANENT 都在贡献，只有 DRAFT 与 PROMOTED 不贡献。
        // 状态迁移与搬桶是两件独立的事，混在一起的话每加一个状态都要重新想一遍钱的事。
        assertEquals(before, metrics())
    }

    private fun failedTotal(metric: String) =
        dao.contribTotal(storeKey, StatsProjection.OUTBOX_FAILED_SCOPE, metric)

    /**
     * 两个桶的合计。
     *
     * 状态迁移（READY → IN_FLIGHT → FAILED_PERMANENT）会在两个桶之间搬钱，
     * 所以"迁移不动贡献"这条断言必须看**合计**，只看 OUTBOX 会误判成搬丢了。
     */
    private fun metrics() = listOf(
        outboxTotal(StatsProjection.METRIC_AMOUNT) + failedTotal(StatsProjection.METRIC_AMOUNT),
        outboxTotal(StatsProjection.METRIC_ORDERS) + failedTotal(StatsProjection.METRIC_ORDERS),
        outboxTotal(StatsProjection.METRIC_PIECES) + failedTotal(StatsProjection.METRIC_PIECES),
    )

    // ==================== refreshBlocked ====================

    @Test
    fun `箱规全补齐时放行成_READY_并返回被放行的_uuid`() {
        val uuid = enqueue(missing = listOf("6901234567890", "6901234567891")).rows.single().orderUuid

        val released = runBlocking { store.refreshBlocked(storeKey) { true } }

        assertEquals(listOf(uuid), released)
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
        // 放行后阻塞信息必须清掉，否则 UI 一直显示"缺 2 个箱规"
        assertNull(row(uuid).blockedReason)
        assertNull(row(uuid).blockedBarcodes)
    }

    @Test
    fun `只补上一部分时收窄_CSV_与文案_状态不变`() {
        val uuid = enqueue(missing = listOf("6901234567890", "6901234567891")).rows.single().orderUuid

        val released = runBlocking { store.refreshBlocked(storeKey) { it == "6901234567890" } }

        assertTrue(released.isEmpty())
        assertEquals(OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, dao.outboxState(storeKey, uuid))
        assertEquals("6901234567891", row(uuid).blockedBarcodes)
        assertEquals(OutboxStore.blockedReason(1), row(uuid).blockedReason)
    }

    @Test
    fun `一个都没补上时不写库`() {
        val uuid = enqueue(missing = listOf("6901234567890")).rows.single().orderUuid
        dao.ops.clear()

        val released = runBlocking { store.refreshBlocked(storeKey) { false } }

        assertTrue(released.isEmpty())
        // CSV 与文案都没变，写一次是纯浪费
        assertFalse(dao.ops.any { it.startsWith("blockedBarcodes:") })
        assertEquals(OrderOutboxEntity.BLOCKED_MISSING_BOX_SIZE, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `放行的_CAS_不会把_FAILED_PERMANENT_一起放出去`() {
        // unblockOutbox 的 SQL 带 `WHERE state = 'BLOCKED_MISSING_BOX_SIZE'`
        val blocked = enqueue(missing = listOf("6901234567890")).rows.single().orderUuid
        val failed = enqueue(req(lines = listOf(line(barcode = "6909999999999")))).rows.single().orderUuid
        takeOff(failed)
        runBlocking { store.failPermanently(storeKey, ownerName, failed, "413") }

        val released = runBlocking { store.refreshBlocked(storeKey) { true } }

        assertEquals(listOf(blocked), released)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, dao.outboxState(storeKey, failed))
    }

    @Test
    fun `没有挂起的单时_refreshBlocked_直接返回空_且不碰库`() {
        enqueue()
        dao.ops.clear()

        assertTrue(runBlocking { store.refreshBlocked(storeKey) { true } }.isEmpty())
        assertTrue(dao.ops.isEmpty())
    }

    // ==================== adoptForeign：接收别的设备的单 ====================

    /** 造一笔"别的设备热点同步过来的"未上传订单。 */
    private fun foreignRow(
        uuid: String,
        state: String = OrderOutboxEntity.READY,
        payload: ByteArray = foreignPayload(uuid),
        originDevice: String = "device-theirs",
        totalCents: Long = 3000L,
        itemCount: Int = 3,
        groupId: String = "",
    ) = OrderOutboxEntity(
        storeKey = storeKey, orderUuid = uuid, groupId = groupId,
        fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        payload = payload, state = state,
        blockedReason = null, blockedBarcodes = null,
        attemptCount = 0, lastError = null,
        pricedAt = clock, createdAt = clock,
        writer = "bob", totalCents = totalCents, itemCount = itemCount, orderTs = sept15,
        originDevice = originDevice,
    )

    /**
     * 独立拼一个 payload，**不用** `StableOrderJson`：
     * 接收侧不该假设对方用的是同一个序列化器版本。
     * writer=bob、ts=sept15、3 件、30.00 —— 与 dimKey 对得上。
     */
    private fun foreignPayload(uuid: String, total: String = "30.00", qty: Int = 3): ByteArray =
        ("{\"order_uuid\":\"$uuid\",\"writer\":\"bob\",\"content\":{\"items\":" +
            "[{\"barcode\":\"6901234567890\",\"name\":\"x\",\"quantity\":$qty,\"amount\":\"$total\"," +
            "\"unitPrice\":\"10.00\"}],\"total\":\"$total\",\"ts\":$sept15},\"status\":1}")
            .toByteArray(Charsets.UTF_8)

    private val foreignUuid = "99999999-8888-7777-6666-555555555555"

    @Test
    fun `adoptForeign_插入外来行并把钱加进_OUTBOX_桶`() {
        val r = runBlocking { store.adoptForeign(storeKey, "alice", listOf(foreignRow(foreignUuid))) }

        assertEquals(1, r.inserted)
        assertEquals(0, r.skipped)
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, foreignUuid))
        // ⚠ 这条是关键：插一行未上传的订单同时意味着这个店的营业额多了一笔。
        // 不加的话统计少一笔，而之后提升时 promoteFromOutbox 会从桶里**减**走一笔
        // 从来没加进去的钱 → 桶变成负数 → 营业额永久偏小，且 statsSelfCheck 查不出来。
        assertEquals(3000L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(3L, outboxTotal(StatsProjection.METRIC_PIECES))
        // dimKey 用的是**对方 payload 里的** writer（bob），不是本机登录名
        assertEquals(
            3000L,
            dao.contribValue(storeKey, StatsProjection.OUTBOX_SCOPE, "2026-09-15|w:bob", StatsProjection.METRIC_AMOUNT),
        )
    }

    @Test
    fun `adoptForeign_保留创建者的_originDevice_不盖成本机`() {
        runBlocking { store.adoptForeign(storeKey, "alice", listOf(foreignRow(foreignUuid))) }

        // 盖成本机 id 的话，下一次热点同步就分不清谁该自动上传它了 ——
        // 两台设备都会以为是自己的单，都自动传，服务端不查重 → 两行。
        assertEquals("device-theirs", dao.outbox[storeKey to foreignUuid]!!.originDevice)
    }

    @Test
    fun `同一批数据同步两次_第二次全部跳过且贡献不翻倍`() {
        val row = foreignRow(foreignUuid)
        runBlocking { store.adoptForeign(storeKey, "alice", listOf(row)) }
        assertEquals(3000L, outboxTotal(StatsProjection.METRIC_AMOUNT))

        // 用户连了两回热点是正常情况，不是错误
        val second = runBlocking { store.adoptForeign(storeKey, "alice", listOf(row)) }

        assertEquals(0, second.inserted)
        assertEquals(1, second.skipped)
        assertTrue(second.payloadConflicts.isEmpty())
        assertEquals("贡献不能翻倍", 3000L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, outboxTotal(StatsProjection.METRIC_ORDERS))
    }

    @Test
    fun `同_uuid_但_payload_不同时保留本地那份并报冲突`() {
        val local = enqueue(req(lines = listOf(line(amount = "52.00")))).rows.single()
        // 对方声称有同一个 uuid，但字节不一样 —— 说明有一边被改过
        val incoming = foreignRow(local.orderUuid, payload = foreignPayload(local.orderUuid, total = "99.00"))
        val localBytes = local.payload.copyOf()

        val r = runBlocking { store.adoptForeign(storeKey, "alice", listOf(incoming)) }

        assertEquals(listOf(local.orderUuid), r.payloadConflicts)
        assertEquals(0, r.inserted)
        assertEquals(1, r.skipped)
        // ⚠ 保留本地那份：它可能已经发出去过了。用对方的字节覆盖会让
        // "发出去的字节与库里存的字节不一致"，所有偏移作废，且没有任何机制会报错。
        assertTrue(localBytes.contentEquals(dao.outbox[storeKey to local.orderUuid]!!.payload))
        assertTrue(audits.any { "payload 不一致" in it })
    }

    @Test
    fun `PROMOTED_行被跳过_即使发送侧漏了过滤`() {
        // 提升状态只能由同步路径写（applyAdded → promoteFromOutbox），
        // 那条路径会同时把偏移、索引行、统计桶三件事一起做对。
        val r = runBlocking {
            store.adoptForeign(storeKey, "alice", listOf(foreignRow(foreignUuid, state = OrderOutboxEntity.PROMOTED)))
        }

        assertEquals(0, r.inserted)
        assertEquals(1, r.skipped)
        assertNull(dao.outbox[storeKey to foreignUuid])
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `外来行之后被提升时_桶净值归零`() {
        runBlocking { store.adoptForeign(storeKey, "alice", listOf(foreignRow(foreignUuid))) }
        assertEquals(3000L, outboxTotal(StatsProjection.METRIC_AMOUNT))

        // 模拟同步路径：对方上传了，本机同步到 ADD 消息 → promoteFromOutbox
        runBlocking {
            store.takeOff(storeKey, foreignUuid)
            assertEquals(1, dao.markOutboxPromoted(storeKey, foreignUuid))
            // promoteFromOutbox 减的就是 OutboxContribution.of 算出来的那一份
            val row = dao.outbox[storeKey to foreignUuid]!!
            for (c in OutboxContribution.of(row, OutboxContribution.partsOf("alice", row), utc)) {
                dao.applyContribDelta(storeKey, StatsProjection.OUTBOX_SCOPE, c.dimKey, c.metric, -c.value)
            }
        }

        // 加与减必须走同一个纯函数，否则差一分钱不会报错，只会让营业额永久偏掉
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_PIECES))
    }

    @Test
    fun `exportForPeer_排除_PROMOTED_与_DRAFT`() {
        val ready = enqueue().rows.single().orderUuid
        val blocked = enqueue(req(lines = listOf(line(barcode = "6909999999999"))), missing = listOf(colaMissing))
            .rows.single().orderUuid
        val promoted = enqueue(req(lines = listOf(line(barcode = "6908888888888")))).rows.single().orderUuid
        runBlocking {
            store.takeOff(storeKey, promoted)
            dao.markOutboxPromoted(storeKey, promoted)
            // DRAFT 只能手搓：enqueue 只会写 READY 或 BLOCKED
            dao.insertOutbox(
                dao.outbox[storeKey to ready]!!.copy(orderUuid = draftUuid, state = OrderOutboxEntity.DRAFT)
            )
        }

        val out = runBlocking { store.exportForPeer(storeKey) }

        // PROMOTED 不传：状态只能由同步路径写。
        // DRAFT 不传：草稿连"成交了吗"都没确定，传过去会让对方算进营业额。
        assertEquals(setOf(ready, blocked), out.map { it.orderUuid }.toSet())
    }

    private val colaMissing = "6901234567890"
    private val draftUuid = "88888888-7777-6666-5555-444444444444"

    @Test
    fun `统计查询能只按开关排除_FAILED_桶_而不动库`() {
        val ok = enqueue().rows.single().orderUuid
        val bad = enqueue(req(lines = listOf(line(barcode = "6909999999999")))).rows.single().orderUuid
        takeOff(bad)
        runBlocking { store.failPermanently(storeKey, ownerName, bad, "413") }
        assertNotNull(ok)

        val withFailed = runBlocking {
            dao.observeContribInRange(storeKey, "2026-09-01", "2026-09-30", includeFailed = true).first()
        }
        val withoutFailed = runBlocking {
            dao.observeContribInRange(storeKey, "2026-09-01", "2026-09-30", includeFailed = false).first()
        }

        fun amount(rows: List<com.example.myapplication.db.ContribAggregate>) =
            rows.filter { it.metric == StatsProjection.METRIC_AMOUNT }.sumOf { it.total }

        // 开关是**读取侧**的事：两次查询之间库一个字节都没变，
        // 所以来回切多少次都不会漂移。靠改库实现的话，任何一次搬错都会永久留在账上。
        assertEquals(10_400L, amount(withFailed))
        assertEquals(5_200L, amount(withoutFailed))
        assertEquals(5_200L, failedTotal(StatsProjection.METRIC_AMOUNT))
    }

    // ==================== splitCsv ====================

    @Test
    fun `splitCsv_把空串与_null_都当没有_并去掉空白项`() {
        assertEquals(emptyList<String>(), OutboxStore.splitCsv(null))
        assertEquals(emptyList<String>(), OutboxStore.splitCsv(""))
        assertEquals(emptyList<String>(), OutboxStore.splitCsv(" , ,"))
        assertEquals(listOf("a", "b"), OutboxStore.splitCsv(" a , b "))
    }
}
