package com.example.myapplication.data

import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.sync.FakeProductDao
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.StatsProjection
import com.example.myapplication.sync.outbox.OutboxStore
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

/**
 * [HotspotMerger] 的合并规则。
 *
 * 盯的是三类静默错误：
 *  - 串店：两家不同的店互相同步，订单会上传到错误的 fileId 下，只有那一台设备看得到；
 *  - 贡献漏加：收到一笔外来订单却没加进 `OUTBOX` 桶 → 营业额少一笔，
 *    而之后提升时会从桶里减走一笔从来没加进去的钱 → 桶变负数，
 *    且 `statsSelfCheck` 查不出来（它比的是 contrib 与 agg，两边都错就互相抵消）；
 *  - 商品改价被旧值覆盖：谁先点"开始同步"谁说了算，那不是合并是抢。
 */
class HotspotMergerTest {

    private val dao = FakeOrderFileDao()
    private val products = FakeProductDao(
        stockSynced = LinkedHashMap(),
        stockLocal = LinkedHashMap(),
        ops = mutableListOf(),
    )
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")
    private var clock = 1_700_000_000_000L

    private val storeKey = "00002"
    private val otherStore = "00007"
    private val myDevice = "device-mine"
    private val peerDevice = "device-theirs"

    private val outbox = OutboxStore(
        dao = dao,
        inTransaction = { body -> body() },
        timeZone = { utc },
        now = { clock },
        uuidFactory = { "aaaaaaaa-bbbb-cccc-dddd-000000000001" },
        deviceId = { myDevice },
        audit = { audits += it },
    )

    private val merger = HotspotMerger(
        outbox = outbox,
        productDao = products,
        ownerName = { "alice" },
        audit = { audits += it },
    )

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun orderDto(
        uuid: String = this.uuid,
        state: String = OrderOutboxEntity.READY,
        originDevice: String = peerDevice,
        totalCents: Long = 5200L,
    ) = HotspotSync.OrderDto(
        orderUuid = uuid, groupId = "", fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        // 一个能被 OrderPayloadIndex 解析的最小 payload：writer=alice、ts=sept15、14 件、52.00
        payloadB64 = (
            """{"order_uuid":"$uuid","writer":"alice","content":{"items":"""+
                """[{"barcode":"6901234567890","name":"x","quantity":14,"amount":"52.00","unitPrice":"3.50"}]"""+
                ""","total":"52.00","ts":1789430400000},"status":1}"""
            ).toByteArray(Charsets.UTF_8).toByteString().base64(),
        state = state, blockedReason = null, blockedBarcodes = null,
        attemptCount = 0, lastError = null,
        pricedAt = clock, createdAt = clock, writer = "alice",
        totalCents = totalCents, itemCount = 1, orderTs = 1789430400000L,
        originDevice = originDevice,
    )

    private fun productDto(
        barcode: String = "6901234567890",
        queuedAt: Long = clock,
        price: String = "3.50",
    ) = HotspotSync.ProductDto(
        barcode = barcode, op = ProductPendingChange.OP_UPSERT, name = "可口可乐330ml",
        price = price, boxPrice = "80.00", boxSize = 24, stock = 97,
        orderable = true, queuedAt = queuedAt,
    )

    private fun bundle(
        storeKey: String = this.storeKey,
        orders: List<HotspotSync.OrderDto> = emptyList(),
        products: List<HotspotSync.ProductDto> = emptyList(),
    ) = HotspotSync.Bundle(HotspotSync.VERSION, peerDevice, storeKey, orders, products)

    private fun merge(b: HotspotSync.Bundle) = runBlocking { merger.merge(b, storeKey) }

    private fun outboxTotal(metric: String) =
        dao.contribTotal(storeKey, StatsProjection.OUTBOX_SCOPE, metric)

    private fun localPending(barcode: String) = products.pending[storeKey to barcode]

    // ==================== 串店防护 ====================

    @Test
    fun `店铺不符时整包拒收`() {
        val r = merge(bundle(storeKey = otherStore, orders = listOf(orderDto())))

        assertNull(r)
        // ⚠ 一条都不能落库：两家不同的店互相同步会把订单串店，
        // 而串店之后那些单会上传到错误的 fileId 下，只有那一台设备看得到，两边都不报错。
        assertNull(dao.outbox[storeKey to uuid])
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertTrue(audits.any { "不同店铺之间不能互相同步" in it })
    }

    // ==================== 订单 ====================

    @Test
    fun `外来订单落库并加进_OUTBOX_桶`() {
        val r = merge(bundle(orders = listOf(orderDto())))!!

        assertEquals(1, r.ordersInserted)
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(14L, outboxTotal(StatsProjection.METRIC_PIECES))
        assertEquals(OrderOutboxEntity.READY, dao.outboxState(storeKey, uuid))
    }

    @Test
    fun `外来订单保留创建者的设备_id`() {
        merge(bundle(orders = listOf(orderDto())))

        // 盖成本机 id 的话下一次同步就分不清谁该自动上传它了 ——
        // 两台设备都以为是自己的单，都自动传，服务端不查重 → 两行。
        assertEquals(peerDevice, dao.outbox[storeKey to uuid]!!.originDevice)
    }

    @Test
    fun `已经有的_uuid_跳过_且贡献不翻倍`() {
        merge(bundle(orders = listOf(orderDto())))
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))

        val second = merge(bundle(orders = listOf(orderDto())))!!

        assertEquals(0, second.ordersInserted)
        assertEquals(1, second.ordersSkipped)
        assertEquals("贡献不能翻倍", 5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `PROMOTED_与_DRAFT_被拒收并计入_rejected`() {
        val r = merge(
            bundle(
                orders = listOf(
                    orderDto(uuid = "aaaaaaaa-0000-0000-0000-000000000001", state = OrderOutboxEntity.PROMOTED),
                    orderDto(uuid = "aaaaaaaa-0000-0000-0000-000000000002", state = OrderOutboxEntity.DRAFT),
                    orderDto(uuid = "aaaaaaaa-0000-0000-0000-000000000003"),
                )
            )
        )!!

        assertEquals(1, r.ordersInserted)
        assertEquals(2, r.rejected)
    }

    @Test
    fun `两边_payload_不一致时保留本地并报冲突`() {
        // 先让本机有一笔同 uuid 的单
        runBlocking { outbox.adoptForeign(storeKey, "alice", listOf(localRow())) }
        val localBytes = dao.outbox[storeKey to uuid]!!.payload.copyOf()

        val r = merge(bundle(orders = listOf(orderDto())))!!

        assertEquals(listOf(uuid), r.payloadConflicts)
        assertTrue(localBytes.contentEquals(dao.outbox[storeKey to uuid]!!.payload))
    }

    private fun localRow() = HotspotSync.toEntity(orderDto(), storeKey, myDevice)!!
        .copy(originDevice = myDevice, payload = "本地不同的字节".toByteArray(Charsets.UTF_8))

    // ==================== 商品改动 ====================

    @Test
    fun `商品改动_queuedAt_更晚的赢`() {
        products.pending[storeKey to "6901234567890"] = ProductPendingChange(
            storeKey, "6901234567890", ProductPendingChange.OP_UPSERT, "旧名",
            BigDecimal("9.99"), null, 0, 1, true,
            queuedAt = clock - 10_000L, attemptCount = 0, lastError = null,
        )

        val r = merge(bundle(products = listOf(productDto(queuedAt = clock, price = "3.50"))))!!

        assertEquals(1, r.productsUpdated)
        assertEquals(0, BigDecimal("3.50").compareTo(localPending("6901234567890")!!.price))
        assertEquals("可口可乐330ml", localPending("6901234567890")!!.name)
    }

    @Test
    fun `本地更新时跳过外来的旧改动`() {
        products.pending[storeKey to "6901234567890"] = ProductPendingChange(
            storeKey, "6901234567890", ProductPendingChange.OP_UPSERT, "本机新名",
            BigDecimal("1.00"), null, 0, 1, true,
            queuedAt = clock + 10_000L, attemptCount = 0, lastError = null,
        )

        val r = merge(bundle(products = listOf(productDto(queuedAt = clock))))!!

        // ⚠ "外来的总是赢"会让先点同步按钮的那台设备永远覆盖后一台 ——
        // 那不是合并是抢。
        assertEquals(0, r.productsUpdated)
        assertEquals(1, r.productsSkipped)
        assertEquals("本机新名", localPending("6901234567890")!!.name)
    }

    @Test
    fun `queuedAt_相等时保留本地_不覆盖`() {
        products.pending[storeKey to "6901234567890"] = ProductPendingChange(
            storeKey, "6901234567890", ProductPendingChange.OP_UPSERT, "同一笔",
            BigDecimal("3.50"), null, 0, 1, true,
            queuedAt = clock, attemptCount = 2, lastError = "413",
        )

        val r = merge(bundle(products = listOf(productDto(queuedAt = clock))))!!

        assertEquals(0, r.productsUpdated)
        assertEquals(1, r.productsSkipped)
        // 覆盖会让 attemptCount 归零、重新烧三次退避 —— 而它本来就是同一笔改动转了一圈回来
        assertEquals(2, localPending("6901234567890")!!.attemptCount)
        assertEquals("413", localPending("6901234567890")!!.lastError)
    }

    @Test
    fun `价格解析不出来的商品改动被拒收_不是填_0`() {
        val r = merge(bundle(products = listOf(productDto().copy(price = "面议"))))!!

        assertEquals(0, r.productsUpdated)
        assertEquals(1, r.rejected)
        assertNull(localPending("6901234567890"))
    }

    // ==================== 导出 ====================

    @Test
    fun `export_带上未上传订单与待推送商品改动`() {
        runBlocking { outbox.adoptForeign(storeKey, "alice", listOf(localRow().copy(originDevice = myDevice))) }
        products.pending[storeKey to "6901234567890"] = ProductPendingChange(
            storeKey, "6901234567890", ProductPendingChange.OP_UPSERT, "可口可乐330ml",
            BigDecimal("3.50"), BigDecimal("80.00"), 24, 97, true,
            queuedAt = clock, attemptCount = 1, lastError = "x",
        )

        val b = runBlocking { merger.export(myDevice, storeKey) }

        assertEquals(HotspotSync.VERSION, b.version)
        assertEquals(myDevice, b.deviceId)
        assertEquals(storeKey, b.storeKey)
        assertEquals(1, b.orders.size)
        assertEquals(1, b.products.size)
        // attemptCount 不跨设备传递：那是本机推送失败的记录
        assertEquals(0, HotspotSync.productToEntity(b.products.single(), storeKey)!!.attemptCount)
    }

    @Test
    fun `export_不含_PROMOTED_行`() {
        val row = localRow().copy(originDevice = myDevice)
        runBlocking {
            outbox.adoptForeign(storeKey, "alice", listOf(row))
            outbox.takeOff(storeKey, uuid)
            dao.markOutboxPromoted(storeKey, uuid)
        }

        val b = runBlocking { merger.export(myDevice, storeKey) }

        assertTrue(b.orders.isEmpty())
    }

    @Test
    fun `Report_summary_把冲突说出来`() {
        val r = merge(
            bundle(
                orders = listOf(orderDto(), orderDto(uuid = "aaaaaaaa-0000-0000-0000-000000000009", state = OrderOutboxEntity.PROMOTED)),
                products = listOf(productDto()),
            )
        )!!

        val s = r.summary
        assertTrue(s, s.contains("新增 1 笔"))
        assertTrue(s, s.contains("更新 1 条"))
        assertTrue(s, s.contains("1 条数据格式不对被拒收"))
    }

    @Test
    fun `空包什么都不做`() {
        val r = merge(bundle())!!

        assertEquals(0, r.ordersInserted)
        assertEquals(0, r.productsUpdated)
        assertNotNull(r.summary)
    }
}
