package com.example.myapplication.data

import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.StableOrderJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进货侧出库路径的测试。
 *
 * ## 这里唯一真正要守住的东西
 *
 * **进货单的字节里不能出现任何金额。** 不是"金额是 0"，也不是"金额很小"——
 * 是 `amount` / `unitPrice` / `total` 三个键**根本不存在**。
 *
 * 因为服务端不存买入价（README §6.2），本地 `price` 是卖出价；拿它算出来的数字
 * 会落进 `content`，而 `content` 上传后**永远改不了**（补丁只能等长覆写 status 那一个字节）。
 * 一旦写进去，半年后没人能分清那个数是进价还是售价。
 */
class PurchaseCartTest {

    private fun product(
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        price: String = "3.50",
        boxPrice: String? = "80.00",
        boxSize: Int = 24,
    ) = Product(
        barcode = barcode,
        name = name,
        price = price.toBigDecimal(),
        boxPrice = boxPrice?.toBigDecimal(),
        boxSize = boxSize,
    )

    // ==================== 行转换 ====================

    @Test
    fun `个数等于箱数乘箱规加零散数`() {
        // 进货的最小单位仍然是"个"：服务端 extractItems 拿 quantity 直接扣库存，
        // 而 A 类不扣 —— 但 quantity 的语义两边必须一致，否则总部收到的数量就是错的。
        val lines = PurchaseCart.linesOf(listOf(CartLine(product(), boxes = 2, singles = 5)))

        assertEquals(listOf(PurchaseCart.Line("6901234567890", "可口可乐330ml", 53)), lines)
    }

    @Test
    fun `空行被丢掉_而不是变成_quantity_0`() {
        // quantity = 0 会让整张单拿 400（服务端要求正整数），
        // 而"两个框都空"只是编辑过程中的中间态，不是"要进 0 个"。
        val lines = PurchaseCart.linesOf(
            listOf(CartLine(product()), CartLine(product(barcode = "6901234567891"), singles = 3))
        )!!

        assertEquals(1, lines.size)
        assertEquals("6901234567891", lines.single().barcode)
    }

    @Test
    fun `有行算不出个数时返回_null_而不是给一个残缺列表`() {
        // 缺箱规（boxSize = 0）却填了箱数：钱算得出来、个数算不出来。
        // 半张进货单发到总部，对方按半张备货，而这边看起来"已经下单了"。
        assertNull(PurchaseCart.linesOf(listOf(CartLine(product(boxSize = 0), boxes = 1))))
    }

    // ==================== 请求形状 ====================

    @Test
    fun `请求是_A_类_且_month_为_null`() {
        // A 类没有月份维度；传 0 会撞服务端 validateTypeAndMonth 的 month<1||month>12 → 400，
        // 而且是在上传时才炸，那时 outbox 行已经 IN_FLIGHT 了。
        val req = PurchaseCart.toRequest(
            cart = listOf(CartLine(product(), singles = 3)),
            writer = "alice", ts = 0L, year = 2026, remarkText = "进货一批",
        )!!

        assertEquals(OrderFileId.TYPE_A, req.fileType)
        assertNull(req.month)
        assertEquals("进货一批", req.remarkText)
        assertNull("进货单没有金额，也就没有手工覆盖总额这回事", req.totalOverrideCents)
    }

    @Test
    fun `每一行的_amount_与_unitPrice_都是_null`() {
        // ⚠ 这条断言看着像废话（toRequest 里写死了 null），但它守的是**回归**：
        // 哪天有人"顺手"把 CartPricing 的价格填进来，编译不会报错、
        // 拆单器也只是 require 通过（A 类要求就是 null），只有这里会红。
        val req = PurchaseCart.toRequest(
            cart = listOf(CartLine(product(), boxes = 1, singles = 6)),
            writer = "alice", ts = 0L, year = 2026, remarkText = null,
        )!!

        req.lines.forEach {
            assertNull("barcode=${it.barcode}", it.amount)
            assertNull("barcode=${it.barcode}", it.unitPrice)
        }
    }

    @Test
    fun `购物车为空时返回_null_而不是拼一张空单`() {
        // 服务端 items 不能为空；进了 outbox 也只会永远 FAILED_PERMANENT。
        assertNull(
            PurchaseCart.toRequest(
                cart = listOf(CartLine(product())),   // 两个框都空
                writer = "alice", ts = 0L, year = 2026, remarkText = null,
            )
        )
    }

    // ==================== 落到字节上 ====================

    @Test
    fun `序列化后的_payload_里三个金额键都不存在`() {
        val req = PurchaseCart.toRequest(
            cart = listOf(CartLine(product(), boxes = 1, singles = 6)),
            writer = "alice", ts = 1_758_000_000_000L, year = 2026, remarkText = "向丽华金龙进货",
        )!!
        val draft = StableOrderJson.serialize(
            com.example.myapplication.sync.outbox.OrderDraft(
                orderUuid = "11111111-2222-3333-4444-555555555555",
                writer = req.writer,
                items = req.lines,
                total = null,
                ts = req.ts,
                remark = com.example.myapplication.core.bytes.ByteOps.padRemark(req.remarkText.orEmpty()),
            )
        )
        val j = String(draft.payload, Charsets.UTF_8)

        assertFalse(j.contains("\"amount\""))
        assertFalse(j.contains("\"unitPrice\""))
        assertFalse(j.contains("\"total\""))
        // 该有的还得有：总部要按商品名备货，条码只是键
        assertTrue(j.contains("\"name\":\"可口可乐330ml\""))
        assertTrue(j.contains("\"quantity\":30"))
        assertNotNull(draft.remarkValueByteIndex)
    }
}
