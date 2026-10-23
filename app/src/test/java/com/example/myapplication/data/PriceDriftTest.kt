package com.example.myapplication.data

import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceDrift] 的判定逻辑。
 *
 * 这里钉住的错误全都是**方向性**的，而且都很贵：
 *  - 拿当前目录价当"开单价" → 每一笔单都会被报成漂移（或每一笔都不报），
 *    用户三次之后就再也不看这个提示了；
 *  - 把降价报成涨价 → 用户按"涨价了"去重算，反而多收顾客的钱；
 *  - 商品被删就报漂移 → 那张单按原价上传是完全正确的，报出来只是噪音。
 */
class PriceDriftTest {

    private val dao = FakeOrderFileDao()
    private val storeKey = "00002"
    private val uuid = "11111111-2222-3333-4444-555555555555"

    /** `currentUnitPrices` 被调用时收到的条码批次。用来断言"批量查一次"而不是逐条。 */
    private val priceCalls = mutableListOf<List<String>>()
    private var pricesNow: Map<String, Long> = emptyMap()

    private val drift = PriceDrift(
        dao = dao,
        storeKey = { storeKey },
        currentUnitPrices = { barcodes ->
            priceCalls += barcodes
            pricesNow.filterKeys { it in barcodes }
        },
    )

    // ==================== 构造测试数据 ====================

    private fun line(
        barcode: String,
        qty: Int,
        amount: String,
        unitPrice: String,
        name: String = "可口可乐330ml",
    ) = OrderLine(barcode, name, qty, amount, unitPrice)

    private fun payload(items: List<OrderLine>, total: String): ByteArray =
        StableOrderJson.serialize(
            OrderDraft(uuid, "alice", items, total, 1_758_000_000_000L)
        ).payload

    /**
     * 手工拼一个**不带 unitPrice** 的 payload（模拟早期写入镜像的订单）。
     *
     * 不能走 `StableOrderJson`：它现在每行都写 unitPrice，造不出"缺字段"的形状。
     *
     * ⚠ 用普通转义字符串而不是 raw string：这段 JSON 里 `"items":[` 之后紧跟着
     * `],"total":"` ——写成 raw string 就会出现四个连续引号，其中一个属于内容，
     * 拼出来的东西不是合法 JSON，而解析失败看起来像 PriceDrift 的 bug。
     */
    private fun legacyPayload(barcode: String, qty: Int, amount: String, total: String): ByteArray {
        val json = "{\"order_uuid\":\"$uuid\",\"writer\":\"alice\",\"content\":{\"items\":" +
            "[{\"barcode\":\"$barcode\",\"name\":\"x\",\"quantity\":$qty,\"amount\":\"$amount\"}]," +
            "\"total\":\"$total\",\"ts\":1758000000000},\"status\":1}"
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun row(
        payload: ByteArray,
        state: String = OrderOutboxEntity.READY,
        totalCents: Long = 10_100L,
        pricedAt: Long = 1_758_000_000_000L,
    ) = OrderOutboxEntity(
        storeKey = storeKey, orderUuid = uuid, groupId = "",
        fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        payload = payload, state = state,
        blockedReason = null, blockedBarcodes = null,
        attemptCount = 0, lastError = null,
        pricedAt = pricedAt, createdAt = pricedAt,
        writer = "alice", totalCents = totalCents, itemCount = 1, orderTs = pricedAt,
    )

    private fun parse(p: ByteArray) = OrderPayloadIndex.parse(p)!!

    private fun driftOf(p: ByteArray, prices: Map<String, Long>): DriftedOrder? =
        PriceDrift.driftOf(row(p), parse(p), prices)

    // ==================== 判定方向 ====================

    @Test
    fun `涨价被检测出来_且_diff_为正`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")

        val d = driftOf(p, mapOf("690" to 400L))

        assertNotNull(d)
        val l = d!!.lines.single()
        assertEquals(350L, l.unitPriceThen)
        assertEquals(400L, l.unitPriceNow)
        assertEquals(50L, l.diff)
        assertEquals(1, d.increased)
        assertEquals(0, d.decreased)
        assertEquals(30, l.quantity)
    }

    @Test
    fun `降价被检测出来_且_diff_为负`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")

        val d = driftOf(p, mapOf("690" to 300L))!!

        // 涨价与降价必须分开说：用户对待它们的态度完全不同
        assertEquals(-50L, d.lines.single().diff)
        assertEquals(0, d.increased)
        assertEquals(1, d.decreased)
    }

    @Test
    fun `价格没变时返回_null`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")

        assertNull(driftOf(p, mapOf("690" to 350L)))
    }

    @Test
    fun `多行里只有一行漂移_整单也要进报告`() {
        val p = payload(
            listOf(
                line("6900000000001", 1, "3.50", "3.50"),
                line("6900000000002", 1, "5.00", "5.00"),
            ),
            "8.50",
        )

        val d = driftOf(p, mapOf("6900000000001" to 350L, "6900000000002" to 600L))!!

        assertEquals(listOf("6900000000002"), d.lines.map { it.barcode })
    }

    // ==================== 不能比对的两种情况 ====================

    @Test
    fun `缺_unitPrice_的行不参与判定_只记数`() {
        val p = legacyPayload("690", 30, "101.00", "101.00")

        // ⚠ 即使当前价与"看起来的单价"不同，也不能报漂移：
        // 拿现价当开单价比，等于把"没涨"和"涨了我不知道"混为一谈。
        val d = driftOf(p, mapOf("690" to 999L))

        assertNull(d)
        // 但要能从 parsed 里看出这一行没有单价
        assertNull(parse(p).items.single().unitPriceCents)
    }

    @Test
    fun `查不到的条码记进_gone_而不算漂移`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")

        // 商品被删了：那张单按原价上传是完全正确的
        assertNull(driftOf(p, emptyMap()))
    }

    @Test
    fun `有真漂移时_gone_与_unknownThen_作为附带信息一起给出`() {
        val p = payload(
            listOf(
                line("6900000000001", 1, "3.50", "3.50"),   // 涨价 → 漂移
                line("6900000000002", 1, "5.00", "5.00"),   // 已删 → gone
            ),
            "8.50",
        )

        val d = driftOf(p, mapOf("6900000000001" to 400L))!!

        assertEquals(listOf("6900000000001"), d.lines.map { it.barcode })
        assertEquals(listOf("6900000000002"), d.gone)
    }

    // ==================== scan ====================

    @Test
    fun `scan_批量取价_而不是逐条查库`() {
        val p = payload(
            listOf(
                line("6900000000001", 1, "3.50", "3.50"),
                line("6900000000002", 1, "5.00", "5.00"),
                line("6900000000001", 2, "7.00", "3.50"),   // 重复条码
            ),
            "15.50",
        )
        runBlocking { dao.insertOutbox(row(p)) }
        pricesNow = mapOf("6900000000001" to 350L, "6900000000002" to 500L)

        val out = runBlocking { drift.scan() }

        // 一张单几十行时，逐条查就是几十次数据库往返
        assertEquals(1, priceCalls.size)
        // 去重过：重复条码只查一次
        assertEquals(
            listOf("6900000000001", "6900000000002"),
            priceCalls.single().sorted(),
        )
        assertTrue("没有漂移就不该进报告", out.isEmpty())
    }

    @Test
    fun `scan_跳过_DRAFT_因为草稿还没成交`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")
        runBlocking { dao.insertOutbox(row(p, state = OrderOutboxEntity.DRAFT)) }
        pricesNow = mapOf("690" to 999L)

        val out = runBlocking { drift.scan() }

        assertTrue(out.isEmpty())
        assertTrue("草稿连成交都没确定，不该去查它的价", priceCalls.isEmpty())
    }

    @Test
    fun `scan_没有未上传订单时一个价都不查`() {
        assertTrue(runBlocking { drift.scan() }.isEmpty())
        assertTrue(priceCalls.isEmpty())
    }

    @Test
    fun `scan_不扫已_PROMOTED_的行`() {
        // PROMOTED 行保留不删（是"这个 uuid 已上传"的唯一凭据），
        // 但 dao.outboxPending 已经排除了它 —— 这里钉住这个前提。
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")
        runBlocking {
            dao.insertOutbox(row(p, state = OrderOutboxEntity.PROMOTED))
        }
        pricesNow = mapOf("690" to 999L)

        assertTrue(runBlocking { drift.scan() }.isEmpty())
    }

    @Test
    fun `scan_跨多笔订单时合并成一次取价`() {
        val a = payload(listOf(line("6900000000001", 1, "3.50", "3.50")), "3.50")
        val b = payload(listOf(line("6900000000002", 1, "5.00", "5.00")), "5.00")
        runBlocking {
            dao.insertOutbox(row(a))
            dao.insertOutbox(row(b).copy(orderUuid = "22222222-3333-4444-5555-666666666666"))
        }
        pricesNow = mapOf("6900000000001" to 400L, "6900000000002" to 500L)

        val out = runBlocking { drift.scan() }

        assertEquals(1, priceCalls.size)
        assertEquals(1, out.size)
        assertEquals(uuid, out.single().orderUuid)
        assertEquals(400L, out.single().lines.single().unitPriceNow)
    }

    @Test
    fun `scanOne_对不存在的_uuid_返回_null_而不是抛`() {
        assertNull(runBlocking { drift.scanOne("ffffffff-ffff-ffff-ffff-ffffffffffff") })
    }

    @Test
    fun `报告里带着_pricedAt_好让_UI_说明这单是什么时候按旧价开的`() {
        val p = payload(listOf(line("690", 30, "101.00", "3.50")), "101.00")

        val d = driftOf(p, mapOf("690" to 400L))!!

        assertEquals(1_758_000_000_000L, d.pricedAt)
        assertEquals("alice", d.writer)
        assertEquals(10_100L, d.totalCents)
    }
}
