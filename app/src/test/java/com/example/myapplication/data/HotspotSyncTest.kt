package com.example.myapplication.data

import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.sync.OrderFileId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * [HotspotSync] 的编解码。
 *
 * 这里盯的是两类**静默**故障：
 *  - payload 是"将要原样发给服务端的那些字节"，传输中动一个字节偏移就全错，
 *    而等长覆写不报错、文件长度不变、校验和自洽 —— 没有任何机制会发现；
 *  - Gson 对**缺失字段不报错**，所以格式不匹配时解出来的是一堆看起来合法的默认值
 *    （空 payload、0 元价格、空 originDevice），而不是一个错误。
 */
class HotspotSyncTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    /** 故意用非 ASCII + 引号 + 反斜杠：base64 往返若有任何编码假设错误，这里会先炸。 */
    private val payloadBytes =
        """{"order_uuid":"$uuid","writer":"丽华金龙","content":{"items":[{"name":"可乐\"大瓶\"\\促销"}]}}"""
            .toByteArray(Charsets.UTF_8)

    private fun entity(
        state: String = OrderOutboxEntity.READY,
        originDevice: String = "device-theirs",
        payload: ByteArray = payloadBytes,
    ) = OrderOutboxEntity(
        storeKey = "00002", orderUuid = uuid, groupId = "grp12345",
        fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        payload = payload, state = state,
        blockedReason = "缺 1 个商品的箱规", blockedBarcodes = "6901234567890",
        attemptCount = 2, lastError = "读超时",
        pricedAt = 1_700_000_000_000L, createdAt = 1_700_000_001_000L,
        writer = "bob", totalCents = 5200L, itemCount = 14, orderTs = 1_700_000_002_000L,
        originDevice = originDevice,
    )

    private fun bundle(
        version: Int = HotspotSync.VERSION,
        orders: List<HotspotSync.OrderDto> = listOf(HotspotSync.fromEntity(entity())),
        products: List<HotspotSync.ProductDto> = emptyList(),
    ) = HotspotSync.Bundle(version, "device-mine", "00002", orders, products)

    private fun roundTrip(b: HotspotSync.Bundle = bundle()) = HotspotSync.decode(HotspotSync.encode(b))

    // ==================== 往返保真 ====================

    @Test
    fun `订单往返后每个字段都不变`() {
        val out = roundTrip()

        assertNotNull(out)
        val dto = out!!.orders.single()
        val original = HotspotSync.fromEntity(entity())
        assertEquals(original, dto)
    }

    @Test
    fun `payload_字节逐字节保真_含中文与转义`() {
        val out = roundTrip()!!
        val restored = HotspotSync.toEntity(out.orders.single(), "00002", "device-mine")!!

        // ⚠ 这是本类最重要的一条断言。payload 是"将要原样发给服务端的字节"，
        // 传输中动一个字节，偏移就全错，而且没有任何机制会发现。
        assertArrayEquals(payloadBytes, restored.payload)
    }

    @Test
    fun `originDevice_原样保留_不被盖成发送方`() {
        val out = roundTrip()!!
        val restored = HotspotSync.toEntity(out.orders.single(), "00002", "device-mine")!!

        // A → B → C 转一手之后，C 仍要知道这单原本是 A 开的：
        // 只有 A 该自动上传它。盖成转发者的 id 会让每一跳都以为是自己开的，
        // 于是三台设备都自动上传 → 服务端不查重 → 三行。
        assertEquals("device-theirs", restored.originDevice)
    }

    @Test
    fun `originDevice_为空时用发送方设备_id_兜底_绝不留空`() {
        val dto = HotspotSync.fromEntity(entity(originDevice = "")).copy(originDevice = "")

        val restored = HotspotSync.toEntity(dto, "00002", "device-mine")!!

        // 空串在本地的语义是"迁移前的老行 = 本机"。留空的话外来单会被当成本机单
        // **自动**上传，而那正是要防的重复上传。
        assertEquals("device-mine", restored.originDevice)
    }

    @Test
    fun `storeKey_与_deviceId_随包传递`() {
        val out = roundTrip()!!

        assertEquals("00002", out.storeKey)
        assertEquals("device-mine", out.deviceId)
        assertEquals(HotspotSync.VERSION, out.version)
    }

    // ==================== 拒收 ====================

    @Test
    fun `magic_不匹配时返回_null_而不是抛`() {
        val bytes = ("SOME_OTHER_MAGIC\n{}").toByteArray(Charsets.UTF_8)

        assertNull(HotspotSync.decode(bytes))
    }

    @Test
    fun `不是_UTF8_的垃圾字节返回_null`() {
        assertNull(HotspotSync.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)))
    }

    @Test
    fun `JSON_坏了返回_null`() {
        val bytes = (HotspotSync.MAGIC + "\n{这不是 JSON").toByteArray(Charsets.UTF_8)

        assertNull(HotspotSync.decode(bytes))
    }

    @Test
    fun `版本号不认识时拒收`() {
        // 将来加了必填字段的话，旧 App 用默认值解出来的是**假数据**，
        // 而假数据看起来完全合法（空 payload、0 元价格）。宁可拒收。
        assertNull(roundTrip(bundle(version = 99)))
        assertNull(roundTrip(bundle(version = 0)))
    }

    @Test
    fun `PROMOTED_行拒收_因为状态只能由同步路径写`() {
        val dto = HotspotSync.fromEntity(entity(state = OrderOutboxEntity.PROMOTED))

        assertNull(HotspotSync.toEntity(dto, "00002", "device-mine"))
    }

    @Test
    fun `DRAFT_行拒收_因为草稿还没确定成交`() {
        val dto = HotspotSync.fromEntity(entity(state = OrderOutboxEntity.DRAFT))

        // 收下来会让对方把一笔"可能根本没成交"的单算进营业额
        assertNull(HotspotSync.toEntity(dto, "00002", "device-mine"))
    }

    @Test
    fun `空_payload_拒收_而不是收下一笔内容为空的订单`() {
        val dto = HotspotSync.fromEntity(entity()).copy(payloadB64 = "")

        // 收下来的话上传时会被服务端 400，而用户看到的是"上传失败"却查不出原因
        assertNull(HotspotSync.toEntity(dto, "00002", "device-mine"))
    }

    @Test
    fun `base64_解不开的_payload_拒收`() {
        val dto = HotspotSync.fromEntity(entity()).copy(payloadB64 = "!!!不是base64!!!")

        assertNull(HotspotSync.toEntity(dto, "00002", "device-mine"))
    }

    // ==================== 商品改动 ====================

    private fun pending(
        price: String = "3.50",
        boxPrice: String? = "80.00",
        op: String = ProductPendingChange.OP_UPSERT,
    ) = ProductPendingChange(
        storeKey = "00002", barcode = "6901234567890", op = op, name = "可口可乐330ml",
        price = BigDecimal(price), boxPrice = boxPrice?.let { BigDecimal(it) },
        boxSize = 24, stock = 97, orderable = true,
        queuedAt = 1_700_000_000_000L, attemptCount = 3, lastError = "413",
    )

    @Test
    fun `商品改动往返保真_金额尾零不丢`() {
        val dto = HotspotSync.productFromEntity(pending(price = "85.00"))
        val out = HotspotSync.productToEntity(dto, "00002")!!

        // ⚠ 必须走字符串。BigDecimal → double → BigDecimal 会把 "85.00" 变成 "85.0"，
        // 而服务端要求两位小数。
        assertEquals("85.00", out.price.toPlainString())
        assertEquals("80.00", out.boxPrice!!.toPlainString())
        assertEquals(24, out.boxSize)
        assertEquals(97, out.stock)
        assertTrue(out.orderable)
        assertEquals(ProductPendingChange.OP_UPSERT, out.op)
    }

    @Test
    fun `attemptCount_与_lastError_不跨设备传递`() {
        val out = HotspotSync.productToEntity(HotspotSync.productFromEntity(pending()), "00002")!!

        // 那是本机推送失败的记录。带过去会让对方以为自己也失败过三次而放弃推送。
        assertEquals(0, out.attemptCount)
        assertNull(out.lastError)
    }

    @Test
    fun `boxPrice_为_null_与为_0_是两件事_必须区分`() {
        val noBox = HotspotSync.productToEntity(
            HotspotSync.productFromEntity(pending(boxPrice = null)), "00002"
        )!!
        val freeBox = HotspotSync.productToEntity(
            HotspotSync.productFromEntity(pending(boxPrice = "0")), "00002"
        )!!

        // null = 不按整箱卖；0 = 整箱免费。混用会让整箱优惠算出一个巨大的负数。
        assertNull(noBox.boxPrice)
        assertNotNull(freeBox.boxPrice)
        assertEquals(0, BigDecimal.ZERO.compareTo(freeBox.boxPrice))
    }

    @Test
    fun `价格解析不出来时丢掉整条_而不是填_0`() {
        val dto = HotspotSync.productFromEntity(pending()).copy(price = "面议")

        // 填 0 会静默覆盖掉对方目录里的真实价格，用户看到的是"价格莫名其妙变成 0 了"
        assertNull(HotspotSync.productToEntity(dto, "00002"))
    }

    @Test
    fun `boxPrice_解析不出来时也丢掉整条_而不是当成不按整箱卖`() {
        val dto = HotspotSync.productFromEntity(pending()).copy(boxPrice = "面议")

        // 退成 null 看起来无害，但那等于把"整箱 80 元"悄悄改成了"不按整箱卖"，
        // 之后开单会按散卖计价 —— 多收顾客的钱，而且票面自洽看不出来。
        assertNull(HotspotSync.productToEntity(dto, "00002"))
    }

    @Test
    fun `DELETE_类型的改动照常传递`() {
        val dto = HotspotSync.productFromEntity(pending(op = ProductPendingChange.OP_DELETE))
        val out = HotspotSync.productToEntity(dto, "00002")!!

        assertEquals(ProductPendingChange.OP_DELETE, out.op)
    }

    // ==================== 空包与多行 ====================

    @Test
    fun `空包能往返`() {
        val out = roundTrip(bundle(orders = emptyList(), products = emptyList()))!!

        assertTrue(out.orders.isEmpty())
        assertTrue(out.products.isEmpty())
    }

    @Test
    fun `多行订单顺序与内容都保留`() {
        val a = entity().copy(orderUuid = "aaaaaaaa-0000-0000-0000-000000000001")
        val b = entity().copy(orderUuid = "aaaaaaaa-0000-0000-0000-000000000002", state = OrderOutboxEntity.FAILED_PERMANENT)
        val out = roundTrip(bundle(orders = listOf(HotspotSync.fromEntity(a), HotspotSync.fromEntity(b))))!!

        assertEquals(2, out.orders.size)
        val restored = out.orders.map { HotspotSync.toEntity(it, "00002", "device-mine")!! }
        // FAILED_PERMANENT 要保留：重推也不会有不同结果的单，不该在对方那里变回 READY
        // 然后被白烧三次退避。
        assertEquals(OrderOutboxEntity.READY, restored[0].state)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, restored[1].state)
    }
}
