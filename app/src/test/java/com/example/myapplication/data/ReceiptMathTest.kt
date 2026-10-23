package com.example.myapplication.data

import com.example.myapplication.sync.OrderPayloadIndex
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReceiptMath] 的金额分解，以及它依赖的 `unitPrice` **往返**（序列化 → 解析 → 计算）。
 *
 * 往返那几条是这里最重要的：`unitPrice` 曾经"只写不读"——
 * `StableOrderJson` 在手动改价时才写它，而 `OrderPayloadIndex.Item` 里根本没这个字段。
 * 只测 [ReceiptMath] 的算术会全绿，而真实路径上 `unitPriceCents` 恒为 null、
 * 折扣行永远不显示。所以必须从 payload 字节一路测到分解结果。
 */
class ReceiptMathTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun item(
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        qty: Int = 30,
        amount: String = "101.00",
        unitPrice: String = "3.50",
    ) = OrderLine(barcode, name, qty, amount, unitPrice)

    private fun draft(items: List<OrderLine>, total: String) = OrderDraft(
        orderUuid = uuid, writer = "alice", items = items, total = total, ts = 1_758_000_000_000L,
    )

    /** 序列化 → 解析 → 分解。这是打印小票走的真实路径。 */
    private fun roundTrip(items: List<OrderLine>, total: String): ReceiptMath.Breakdown {
        val payload = StableOrderJson.serialize(draft(items, total)).payload
        val parsed = OrderPayloadIndex.parse(payload)
        assertNotNull("自己写出去的 payload 必须能解析回来", parsed)
        assertFalse("items 不该被整体丢弃", parsed!!.itemsDropped)
        return ReceiptMath.of(parsed.items, parsed.totalCents)
    }

    /**
     * 手工拼一个**不带 unitPrice** 的 payload（模拟早期写入镜像的订单）。
     *
     * 不能走 `StableOrderJson`：它现在每行都写 unitPrice，造不出"缺字段"的形状。
     *
     * ⚠ 分三段拼而不是写成一个大 raw string：`"""…{"items":""""` 结尾那四个引号里
     * 有一个属于内容，会把 items 数组变成一个**字符串**，于是解析器整体丢弃 items，
     * 而这个失败看起来像 ReceiptMath 的 bug。
     */
    private fun legacyPayload(total: String, vararg items: Triple<String, Int, String>): ByteArray {
        val sb = StringBuilder()
        sb.append("""{"order_uuid":"$uuid","writer":"alice","content":{"items":""")
        sb.append("[")
        items.forEachIndexed { i, (barcode, qty, amount) ->
            if (i > 0) sb.append(",")
            sb.append("""{"barcode":"$barcode","name":"x","quantity":$qty,"amount":"$amount"}""")
        }
        sb.append("]")
        sb.append(""","total":"$total","ts":1758000000000},"status":1}""")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ==================== 往返：unitPrice 真的能读回来 ====================

    @Test
    fun `unitPrice_序列化后能被解析回来`() {
        val payload = StableOrderJson.serialize(draft(listOf(item(unitPrice = "3.50")), "101.00")).payload

        val parsed = OrderPayloadIndex.parse(payload)!!

        assertEquals(350L, parsed.items.single().unitPriceCents)
    }

    @Test
    fun `整箱优惠被正确算出`() {
        // 箱规 24、整箱 80.00、单价 3.50，买 30 个：
        // 1 箱(80.00) + 6 个(21.00) = 行金额 101.00，而 30 × 3.50 = 105.00 → 折扣 4.00
        val b = roundTrip(listOf(item(qty = 30, amount = "101.00", unitPrice = "3.50")), "101.00")

        assertEquals(10_500L, b.listPriceSum)
        assertEquals(10_100L, b.amountSum)
        assertEquals(400L, b.discount)
        assertTrue(b.showDiscount)
        assertEquals(0L, b.rounding)
        assertFalse("没有手工调整就不该显示抹零行", b.showRounding)
        assertTrue(b.balances)
    }

    @Test
    fun `抹零被正确算出`() {
        // 行金额合计 101.00，用户手改成 100.00 → 抹零 1.00
        val b = roundTrip(listOf(item(qty = 30, amount = "101.00", unitPrice = "3.50")), "100.00")

        assertEquals(100L, b.rounding)
        assertTrue(b.showRounding)
        assertEquals(400L, b.discount)
        assertTrue(b.balances)
    }

    @Test
    fun `多行累加正确`() {
        val b = roundTrip(
            listOf(
                item(barcode = "6900000000001", qty = 30, amount = "101.00", unitPrice = "3.50"),   // 折扣 4.00
                item(barcode = "6900000000002", qty = 2, amount = "10.00", unitPrice = "5.00"),      // 折扣 0
            ),
            "110.00",
        )

        assertEquals(10_500L + 1_000L, b.listPriceSum)
        assertEquals(11_100L, b.amountSum)
        assertEquals(400L, b.discount)
        assertEquals(100L, b.rounding)
        assertTrue(b.balances)
    }

    // ==================== 缺 unitPrice（早期订单）====================

    @Test
    fun `缺_unitPrice_时折扣是_null_且不显示`() {
        val parsed = OrderPayloadIndex.parse(
            legacyPayload("101.00", Triple("6901234567890", 30, "101.00"))
        )!!

        assertNull("缺字段必须解析成 null，不是 0", parsed.items.single().unitPriceCents)

        val b = ReceiptMath.of(parsed.items, parsed.totalCents)
        assertNull(b.listPriceSum)
        assertNull(b.discount)
        assertFalse(b.showDiscount)
        assertFalse(b.balances)
        // 抹零仍然算得出来（它不依赖单价）
        assertEquals(0L, b.rounding)
    }

    @Test
    fun `部分行缺_unitPrice_时整张单都不显示折扣`() {
        val withUnit = OrderPayloadIndex.Item("6900000000001", "x", 30, 10_100L, 350L)
        val without = OrderPayloadIndex.Item("6900000000002", "x", 2, 1_000L, null)

        val b = ReceiptMath.of(listOf(withUnit, without), 11_100L)

        // ⚠ 只累加有单价的那几行会算出一个**偏小**的折扣，
        // 而票面上"单价×数量"与"合计"仍然对不上 —— 那是一张自相矛盾的小票。
        // 宁可整行不显示。
        assertNull(b.listPriceSum)
        assertNull(b.discount)
        assertFalse(b.showDiscount)
        // 行金额合计不受影响（它不依赖单价）
        assertEquals(11_100L, b.amountSum)
    }

    // ==================== ≤ 0 不显示（用户定的规则）====================

    @Test
    fun `没有整箱优惠时折扣为_0_且不显示`() {
        // 单价 3.50 × 2 = 7.00，行金额也是 7.00 → 折扣 0
        val b = roundTrip(listOf(item(qty = 2, amount = "7.00", unitPrice = "3.50")), "7.00")

        assertEquals(0L, b.discount)
        assertFalse("折扣为 0 就不显示这一行", b.showDiscount)
        assertFalse(b.showRounding)
    }

    @Test
    fun `负的折扣不显示`() {
        // 行金额之和**大于**按目录单价算出来的钱（加价卖 / 录错价）。
        // 印成"折扣 -4.00"只会让人更糊涂。
        val b = roundTrip(listOf(item(qty = 2, amount = "11.00", unitPrice = "3.50")), "11.00")

        assertEquals(-400L, b.discount)
        assertFalse(b.showDiscount)
    }

    @Test
    fun `负的抹零不显示`() {
        // 总额被手工**调高**了。这不是抹零，而 manualTotal 标记会兜住"对不上"这件事。
        val b = roundTrip(listOf(item(qty = 2, amount = "7.00", unitPrice = "3.50")), "8.00")

        assertEquals(-100L, b.rounding)
        assertFalse(b.showRounding)
    }

    @Test
    fun `折扣与抹零都为正时两行都显示`() {
        val b = roundTrip(listOf(item(qty = 30, amount = "101.00", unitPrice = "3.50")), "100.00")

        assertTrue(b.showDiscount)
        assertTrue(b.showRounding)
    }

    // ==================== 边界 ====================

    @Test
    fun `空_items_不抛`() {
        val b = ReceiptMath.of(emptyList(), 0L)

        assertEquals(0L, b.listPriceSum)
        assertEquals(0L, b.amountSum)
        assertEquals(0L, b.discount)
        assertFalse(b.showDiscount)
    }

    @Test
    fun `大数量不会溢出_Int`() {
        // quantity 是 Int、unitPriceCents 是 Long。单价 × 数量必须在 Long 域里算：
        // 2000 个 × 68.00 元 = 136000 分，用 Int 还没事，但 20 万个就要溢出了，
        // 而溢出是静默的 —— 小票上会印出一个负数折扣。
        val b = roundTrip(
            listOf(item(qty = 200_000, amount = "1360000.00", unitPrice = "6.80")),
            "1360000.00",
        )

        assertEquals(200_000L * 680L, b.listPriceSum)
        assertEquals(1_360_000L * 100L, b.amountSum)
        assertEquals(0L, b.discount)
    }
}
