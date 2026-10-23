package com.example.myapplication.data

import com.example.myapplication.sync.OrderFileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * [CartLine] 与 [CartPricing] 的算钱规则。
 *
 * ## 为什么这一层值得这么厚的测试
 *
 * 这几个数字要同时出现在**结算对话框、小票、订单详情页**三处，而且顾客会拿小票去对账。
 * 算错的后果不是崩，是"101.00 印成 105.00"——多收顾客 4 块钱，
 * 或者少收 4 块钱而店主到月底盘点才发现。两种都不会有任何日志。
 *
 * 尤其要盯的是**整箱优惠**：`整箱价 ≠ 箱规 × 单价`，
 * 所以"单价 × 数量"与"行金额"天然对不上，而这个差额必须有地方交代。
 */
class CartPricingTest {

    private fun product(
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        price: String = "3.50",
        boxPrice: String? = "80.00",
        boxSize: Int = 24,
    ) = Product(
        barcode = barcode,
        name = name,
        price = BigDecimal(price),
        boxPrice = boxPrice?.let { BigDecimal(it) },
        boxSize = boxSize,
    )

    // ==================== 整箱优惠：最核心的一组 ====================

    @Test
    fun `一箱加六个_金额与个数分别按各自的公式算`() {
        // 箱规 24、整箱 80.00、单价 3.50，买 30 个 = 1 箱 + 6 个
        val l = CartLine(product(), boxes = 1, singles = 6)

        // amount = 1×80.00 + 6×3.50 = 101.00
        assertEquals(10_100L, l.amountCents)
        // quantity = 1×24 + 6 = 30（个数，服务端直接用它扣库存）
        assertEquals(30, l.quantity)
        // 按单价全散卖 = 30 × 3.50 = 105.00
        assertEquals(10_500L, l.listPriceCents)
    }

    @Test
    fun `整箱优惠是_按单价算的钱_减去_实际金额`() {
        val t = CartPricing.totals(listOf(CartLine(product(), boxes = 1, singles = 6)))

        assertEquals(10_500L, t.listPriceSum)
        assertEquals(10_100L, t.amountSum)
        assertEquals(400L, t.discount)
        assertTrue(t.showDiscount)
        assertEquals(0L, t.rounding)
        assertFalse(t.showRounding)
        assertFalse(t.manualTotal)
    }

    @Test
    fun `纯散卖时折扣为_0_且不显示`() {
        val t = CartPricing.totals(listOf(CartLine(product(), boxes = 0, singles = 6)))

        assertEquals(2_100L, t.amountSum)
        assertEquals(2_100L, t.listPriceSum)
        assertEquals(0L, t.discount)
        assertFalse("折扣为 0 就不该显示这一行", t.showDiscount)
    }

    @Test
    fun `纯整箱时折扣是箱规乘单价减整箱价`() {
        // 2 箱：amount = 160.00；quantity = 48；list = 48 × 3.50 = 168.00 → 折扣 8.00
        val t = CartPricing.totals(listOf(CartLine(product(), boxes = 2, singles = 0)))

        assertEquals(16_000L, t.amountSum)
        assertEquals(48, CartLine(product(), boxes = 2, singles = 0).quantity)
        assertEquals(800L, t.discount)
    }

    @Test
    fun `不按整箱卖的商品_箱数为_0_就没有折扣`() {
        // boxPrice = null → 不按整箱卖
        val p = product(boxPrice = null, boxSize = 0)
        val t = CartPricing.totals(listOf(CartLine(p, boxes = 0, singles = 10)))

        assertEquals(3_500L, t.amountSum)
        assertEquals(3_500L, t.listPriceSum)
        assertEquals(0L, t.discount)
    }

    @Test
    fun `多行累加_且恒等式成立`() {
        val lines = listOf(
            CartLine(product(), boxes = 1, singles = 6),                             // 折扣 4.00
            CartLine(product(barcode = "6900000000002", price = "5.00", boxPrice = null, boxSize = 0),
                boxes = 0, singles = 3),                                             // 折扣 0
        )
        val t = CartPricing.totals(lines)

        assertEquals(10_100L + 1_500L, t.amountSum)
        assertEquals(10_500L + 1_500L, t.listPriceSum)
        assertEquals(400L, t.discount)
        // 小票上这四个数必须首尾相接，顾客自己按计算器也能对上
        assertEquals(t.amountSum, t.listPriceSum!! - t.discount!!)
        assertEquals(t.total, t.amountSum - t.rounding)
    }

    // ==================== 缺箱规 ====================

    @Test
    fun `缺箱规时金额算得出_个数算不出`() {
        // boxPrice 有、boxSize = 0：钱能算（1×80 + 6×3.5），个数不能算（1×? + 6）
        val l = CartLine(product(boxSize = 0), boxes = 1, singles = 6)

        assertTrue(l.needsBoxSize)
        assertEquals(10_100L, l.amountCents)
        // ⚠ 必须是 null，不能是 0 也不能猜一个：quantity 是服务端校验的正整数，
        // 猜出来的值会扣错库存，而镜像里那一行看起来完全正常。
        assertNull(l.quantity)
        assertNull(l.listPriceCents)
    }

    @Test
    fun `缺箱规但没填整箱数时不算缺`() {
        val l = CartLine(product(boxSize = 0), boxes = 0, singles = 6)

        assertFalse(l.needsBoxSize)
        assertEquals(6, l.quantity)
    }

    @Test
    fun `missingBoxSize_收集条码并去重`() {
        val noSize = product(barcode = "6900000000001", boxSize = 0)
        val lines = listOf(
            CartLine(noSize, boxes = 1, singles = 0),
            CartLine(noSize, boxes = 2, singles = 0),                       // 同一条码
            CartLine(product(barcode = "6900000000002"), boxes = 1),        // 有箱规
        )

        assertEquals(listOf("6900000000001"), CartPricing.missingBoxSize(lines))
    }

    @Test
    fun `toOrderLines_有行算不出个数时返回_null_而不是给一个残缺列表`() {
        val lines = listOf(
            CartLine(product(), boxes = 1, singles = 6),
            CartLine(product(barcode = "6900000000001", boxSize = 0), boxes = 1),
        )

        assertNull(CartPricing.toOrderLines(lines))
    }

    @Test
    fun `有行算不出个数时整车折扣也不显示`() {
        // 只累加算得出的那几行会得到一个**偏小**的折扣，
        // 而"单价×数量"与"合计"仍然对不上 —— 那是一张自相矛盾的小票
        val lines = listOf(
            CartLine(product(), boxes = 1, singles = 6),
            CartLine(product(barcode = "6900000000001", boxSize = 0), boxes = 1),
        )
        val t = CartPricing.totals(lines)

        assertNull(t.listPriceSum)
        assertNull(t.discount)
        assertFalse(t.showDiscount)
        // 行金额合计不受影响（它不依赖箱规）
        assertEquals(10_100L + 8_000L, t.amountSum)
    }

    // ==================== 转 OrderLine ====================

    @Test
    fun `每行都写_unitPrice_且金额用两位小数字符串`() {
        val out = CartPricing.toOrderLines(listOf(CartLine(product(), boxes = 1, singles = 6)))

        assertNotNull(out)
        val l = out!!.single()
        assertEquals("6901234567890", l.barcode)
        assertEquals("可口可乐330ml", l.name)
        assertEquals(30, l.quantity)
        assertEquals("101.00", l.amount)
        // ⚠ 必须每行都写：缺了它，小票的折扣行与价格漂移检测都做不了
        assertEquals("3.50", l.unitPrice)
    }

    @Test
    fun `商品名原样带出去_即使含引号`() {
        val p = product(name = "可乐\"大瓶\"\\促销")
        val out = CartPricing.toOrderLines(listOf(CartLine(p, singles = 1)))!!

        // 转义是 StableOrderJson 的事，这一层只负责把原文交出去
        assertEquals("可乐\"大瓶\"\\促销", out.single().name)
    }

    // ==================== 手动改价 ====================

    @Test
    fun `手动总额产生抹零_并打上_manualTotal_标记`() {
        val t = CartPricing.totals(
            listOf(CartLine(product(), boxes = 1, singles = 6)),
            manualTotalCents = 10_000L,      // 101.00 → 100.00
        )

        assertEquals(10_100L, t.amountSum)
        assertEquals(10_000L, t.total)
        assertEquals(100L, t.rounding)
        assertTrue(t.showRounding)
        assertTrue(t.manualTotal)
        // 折扣不受手工总额影响（它是行级的整箱优惠）
        assertEquals(400L, t.discount)
    }

    @Test
    fun `手动总额高于行金额合计时抹零为负_且不显示`() {
        val t = CartPricing.totals(
            listOf(CartLine(product(), singles = 2)),
            manualTotalCents = 800L,          // 7.00 → 8.00
        )

        assertEquals(-100L, t.rounding)
        assertFalse(t.showRounding)
        // 但 manualTotal 仍然为真：Σ行金额 ≠ 合计这件事必须让用户看见
        assertTrue(t.manualTotal)
    }

    @Test
    fun `手动单价只影响散卖_整箱价仍取目录`() {
        // 单价改成 4.00：6 个散卖 = 24.00，1 箱仍是 80.00 → 104.00
        val l = CartLine(product(), boxes = 1, singles = 6, overrideUnitPriceCents = 400L)

        assertEquals(400L, l.unitPriceCents)
        assertEquals(80L * 100L + 6 * 400L, l.amountCents)
        assertEquals(30, l.quantity)
        // list 用改后的单价：30 × 4.00 = 120.00
        assertEquals(12_000L, l.listPriceCents)
    }

    @Test
    fun `手动单价会改变整箱优惠的数额`() {
        val before = CartPricing.totals(listOf(CartLine(product(), boxes = 1, singles = 6)))
        val after = CartPricing.totals(
            listOf(CartLine(product(), boxes = 1, singles = 6, overrideUnitPriceCents = 400L))
        )

        assertEquals(400L, before.discount)
        // 单价涨了，"全散卖"的钱变多，而实际金额只涨了散卖那 6 个 → 折扣变大
        assertEquals(12_000L - 10_400L, after.discount)
    }

    // ==================== 从总个数反推箱/个 ====================

    @Test
    fun `总数超过箱规时拆成一箱加零散`() {
        // 店员数了 30 个就输 30，系统自己知道 24 个一箱
        val l = CartPricing.fromTotalQuantity(product(), 30)!!

        assertEquals(1, l.boxes)
        assertEquals(6, l.singles)
        assertEquals(30, l.quantity)
        // 1 箱 80.00 + 6 个 21.00 = 101.00，而 30 × 3.50 = 105.00
        assertEquals(10_100L, l.amountCents)
        assertEquals(10_500L, l.listPriceCents)
    }

    @Test
    fun `总数不足一箱时全按散卖`() {
        val l = CartPricing.fromTotalQuantity(product(), 6)!!

        assertEquals(0, l.boxes)
        assertEquals(6, l.singles)
        assertEquals(2_100L, l.amountCents)
        // 没有整箱参与就没有折扣
        assertEquals(0L, CartPricing.totals(listOf(l)).discount)
    }

    @Test
    fun `正好整数箱时零散为_0`() {
        val l = CartPricing.fromTotalQuantity(product(), 48)!!

        assertEquals(2, l.boxes)
        assertEquals(0, l.singles)
        assertEquals(48, l.quantity)
        assertEquals(16_000L, l.amountCents)
        assertEquals(800L, CartPricing.totals(listOf(l)).discount)
    }

    @Test
    fun `不按整箱卖的商品不拆`() {
        val p = product(boxPrice = null, boxSize = 0)
        val l = CartPricing.fromTotalQuantity(p, 30)!!

        assertEquals(0, l.boxes)
        assertEquals(30, l.singles)
        assertEquals(10_500L, l.amountCents)
    }

    @Test
    fun `有整箱价但没箱规时不拆_也不能除零`() {
        // boxSize = 0：拆箱会除零。这种商品要被 ambiguousBoxProducts 报出来，
        // 而不是静默按散卖计价。
        val p = product(boxPrice = "80.00", boxSize = 0)
        val l = CartPricing.fromTotalQuantity(p, 30)!!

        assertEquals(0, l.boxes)
        assertEquals(30, l.singles)
        assertTrue(p.needsBoxSize)
        assertEquals(10_500L, l.amountCents)   // 整箱价被忽略了 → 多收钱
    }

    @Test
    fun `ambiguousBoxProducts_报出设了整箱价却没箱规的商品`() {
        val bad = product(barcode = "6900000000001", boxPrice = "80.00", boxSize = 0)
        val ok = product(barcode = "6900000000002")
        val noBox = product(barcode = "6900000000003", boxPrice = null, boxSize = 0)

        val out = CartPricing.ambiguousBoxProducts(
            listOf(bad to 30, ok to 30, noBox to 5, bad to 10)
        )

        // 去重，且不把"本来就不按整箱卖"的算进来
        assertEquals(listOf("6900000000001"), out)
    }

    @Test
    fun `数量为_0_或负数的行被丢掉_而不是让整次结算失败`() {
        val out = CartPricing.fromCart(listOf(product() to 5, product(barcode = "x") to 0, product(barcode = "y") to -3))

        assertEquals(1, out.size)
        assertEquals(5, out.single().singles)
    }

    // ==================== 输入校验 ====================

    @Test
    fun `负数箱数或个数被拒`() {
        for (l in listOf(
            { CartLine(product(), boxes = -1, singles = 1) },
            { CartLine(product(), boxes = 1, singles = -1) },
        )) {
            try {
                l()
                fail("负数应当被拒")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("不能为负"))
            }
        }
    }

    @Test
    fun `一行至少要有一个`() {
        try {
            CartLine(product(), boxes = 0, singles = 0)
            fail("空行应当被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("至少要有一个"))
        }
    }

    // ==================== toRequest ====================

    @Test
    fun `toRequest_带上_writer_与_手动总额`() {
        val req = CartPricing.toRequest(
            lines = listOf(CartLine(product(), boxes = 1, singles = 6)),
            writer = "clerk1",
            ts = 1_758_000_000_000L,
            fileType = OrderFileId.TYPE_B,
            year = 2026,
            month = 9,
            manualTotalCents = 10_000L,
        )

        assertNotNull(req)
        // writer 可以是别人：现实中存在"某店员的账号永远不再登录，但他开的单仍需上传"，
        // 而服务端不校验 writer 与登录身份是否一致（只验非空白），所以代传是合法的。
        assertEquals("clerk1", req!!.writer)
        assertEquals(10_000L, req.totalOverrideCents)
        assertEquals(OrderFileId.TYPE_B, req.fileType)
        assertEquals(9, req.month)
        assertEquals(1, req.lines.size)
        assertEquals("3.50", req.lines.single().unitPrice)
    }

    @Test
    fun `toRequest_A_类的_month_必须是_null`() {
        // 传 0 会撞服务端 validateTypeAndMonth 的 month<1||month>12 → 400
        val req = CartPricing.toRequest(
            lines = listOf(CartLine(product(), singles = 1)),
            writer = "alice", ts = 0L,
            fileType = OrderFileId.TYPE_A, year = 2026, month = null,
            remarkText = "进货一批",
        )!!

        assertEquals(OrderFileId.TYPE_A, req.fileType)
        assertNull(req.month)
        assertEquals("进货一批", req.remarkText)
    }

    @Test
    fun `toRequest_有行算不出个数时返回_null`() {
        val req = CartPricing.toRequest(
            lines = listOf(CartLine(product(boxSize = 0), boxes = 1)),
            writer = "alice", ts = 0L,
            fileType = OrderFileId.TYPE_B, year = 2026, month = 9,
        )

        assertNull(req)
    }

    // ==================== 溢出 ====================

    @Test
    fun `大额订单不溢出_Int`() {
        // 20 万个 × 68.00 = 1360 万分。quantity × unitPrice 必须在 Long 域里算：
        // 用 Int 的话 20 万 × 6800 = 13.6 亿还在范围内，但 200 万 × 6800 就溢出了，
        // 而溢出是静默的 —— 小票上会印出一个负数折扣。
        val p = product(price = "68.00", boxPrice = null, boxSize = 0)
        val l = CartLine(p, boxes = 0, singles = 2_000_000)

        assertEquals(2_000_000, l.quantity)
        assertEquals(2_000_000L * 6_800L, l.amountCents)
        assertEquals(2_000_000L * 6_800L, l.listPriceCents)
        assertEquals(0L, CartPricing.totals(listOf(l)).discount)
    }
}
