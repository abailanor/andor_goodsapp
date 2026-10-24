package com.example.myapplication.ui.print

import com.example.myapplication.data.CartLine
import com.example.myapplication.data.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 小票的文本生成。
 *
 * ## 为什么这个值得测
 *
 * 小票是顾客**唯一会带走**的东西，也是事后对账的唯一凭据。它错了不会有日志、
 * 不会有崩溃，只会有一个拿着纸来问"你这算错了"的顾客。
 *
 * 最要盯的是那三列的关系：`单价 × 数量 ≠ 金额` 是常态（整箱价 ≠ 箱规 × 单价），
 * 所以底部必须有「折扣」行把差额交代清楚 —— 少了它，小票在顾客眼里就是自相矛盾的。
 */
class PrintUtilTest {

    private fun product(
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        price: String = "3.50",
        boxPrice: String? = "80.00",
        boxSize: Int = 24,
    ) = Product(
        barcode = barcode, name = name, price = BigDecimal(price),
        boxPrice = boxPrice?.let { BigDecimal(it) }, boxSize = boxSize,
    )

    /** 箱规 24、整箱 80.00、单价 3.50：1 箱 + 6 个 = 101.00，而 30 × 3.50 = 105.00。 */
    private fun colaWithBox() = CartLine(product(), boxes = 1, singles = 6)

    private fun receipt(
        lines: List<CartLine> = listOf(colaWithBox()),
        manualTotalCents: Long? = null,
        purchase: Boolean = false,
    ) = PrintUtil.generateReceipt(lines, manualTotalCents, purchase)

    private fun lines(r: String) = r.lines()

    // ==================== 三列语义 ====================

    @Test
    fun `明细行印出单价_数量_金额三列`() {
        val r = receipt()

        assertTrue(r, r.contains("3.50"))     // 单价 = 单个零售价
        assertTrue(r, r.contains("30"))       // 数量 = 总个数（1箱×24 + 6）
        assertTrue(r, r.contains("101.00"))   // 金额 = 含整箱优惠的实收
        // ⚠ 绝不能印 105.00 当金额：那是 30 × 3.50，不是实际收的钱
        assertFalse(r, r.contains("105.00"))
    }

    @Test
    fun `单价乘数量不等于金额时_折扣行必须出现`() {
        val r = receipt()

        assertTrue("缺了折扣行，小票在顾客眼里就是自相矛盾的：\n$r", r.contains("折扣（整箱优惠）"))
        assertTrue(r, r.contains("-4.00"))
    }

    @Test
    fun `没有整箱优惠时不印折扣行`() {
        val r = receipt(listOf(CartLine(product(), boxes = 0, singles = 6)))

        assertFalse(r, r.contains("折扣"))
        assertTrue(r, r.contains("21.00"))
    }

    // ==================== 抹零 ====================

    @Test
    fun `手工改低总额时印抹零行`() {
        val r = receipt(manualTotalCents = 10_000L)   // 101.00 → 100.00

        assertTrue(r, r.contains("抹零"))
        assertTrue(r, r.contains("-1.00"))
        assertTrue(r, r.contains("合计: 100.00"))
    }

    @Test
    fun `没改总额时不印抹零行`() {
        val r = receipt()

        assertFalse(r, r.contains("抹零"))
        assertTrue(r, r.contains("合计: 101.00"))
    }

    @Test
    fun `手工把总额调高时印出含手工调整_因为那时抹零是负的按规则不显示`() {
        // 抹零 ≤ 0 不印，可那样"商品金额"与"合计"就对不上而没有任何解释。
        // 这一行兜住它 —— 否则顾客会认为店家算错了。
        val r = receipt(manualTotalCents = 11_000L)   // 101.00 → 110.00

        assertFalse(r, r.contains("抹零"))
        assertTrue(r, r.contains("（含手工调整）"))
        assertTrue(r, r.contains("合计: 110.00"))
    }

    @Test
    fun `合计等于商品金额减折扣减抹零`() {
        val r = receipt(manualTotalCents = 10_000L)

        // 顾客会拿计算器按这三行。对不上的话，错的是我们。
        assertTrue(r, r.contains("商品金额: 101.00"))
        assertTrue(r, r.contains("-4.00"))     // 折扣
        assertTrue(r, r.contains("-1.00"))     // 抹零
        assertTrue(r, r.contains("合计: 100.00"))
    }

    // ==================== 进货单 ====================

    @Test
    fun `进货单不印任何金额`() {
        val r = receipt(purchase = true)

        // 系统里没有买入价（服务端根本不存储），本地目录的 price 是**卖出价**。
        // 印出来会被当成成本记账。
        assertFalse(r, r.contains("3.50"))
        assertFalse(r, r.contains("101.00"))
        assertFalse(r, r.contains("合计"))
        assertFalse(r, r.contains("单价"))
        assertTrue(r, r.contains("进货单"))
        assertTrue(r, r.contains("总件数"))
        assertTrue(r, r.contains("30"))
    }

    @Test
    fun `进货单的表头是件数而不是金额列`() {
        val r = receipt(purchase = true)

        assertTrue(r, r.contains("件数"))
        assertFalse(r, r.contains("金额"))
    }

    // ==================== 版面 ====================

    @Test
    fun `商品名里的换行被清掉_否则一行会被劈成两行`() {
        val r = receipt(
            listOf(CartLine(product(name = "可乐\n大瓶\r促销"), boxes = 0, singles = 1))
        )

        assertFalse(r, r.contains("可乐\n"))
        assertTrue(r, r.contains("可乐大瓶促销"))
    }

    @Test
    fun `两个框都空的行不印`() {
        val r = receipt(
            listOf(colaWithBox(), CartLine(product(barcode = "6900000000009"), boxes = 0, singles = 0))
        )

        assertFalse(r, r.contains("6900000000009"))
        // 序号也不该给它留位置
        assertTrue(r, r.contains("1"))
        assertFalse(r, r.contains("2 "))
    }

    @Test
    fun `抬头来自参数_不再是硬编码的某一家店`() {
        // 原来 PrintUtil 里写死了"电话：15288180414 / 地址：官渡区新广丰B区14-16"，
        // 装在别的店手机上就会印出别人家的电话。
        val r = PrintUtil.generateReceipt(
            lines = listOf(colaWithBox()),
            shopName = "丽华金龙",
            shopPhone = "0871-1234567",
            shopAddress = "某地某路 1 号",
        )

        assertTrue(r, r.contains("丽华金龙"))
        assertTrue(r, r.contains("0871-1234567"))
        assertTrue(r, r.contains("某地某路 1 号"))
        assertFalse(r, r.contains("15288180414"))
        assertFalse(r, r.contains("官渡区"))
    }

    @Test
    fun `抬头为空时不印空行占位`() {
        val r = receipt()

        assertFalse(r, r.contains("电话："))
        assertFalse(r, r.contains("地址："))
    }

    @Test
    fun `每一行都不超过版面宽度`() {
        val r = receipt(
            listOf(
                CartLine(
                    product(barcode = "6901234567890123", name = "一个名字特别特别特别长的商品"),
                    boxes = 12, singles = 345,
                )
            )
        )

        // 超宽会被截断（truncateText），但绝不能溢出 —— 热敏纸会直接换行，
        // 一旦换行整张小票的对齐就全毁了。
        for (l in lines(r)) {
            assertTrue("这一行超宽(${displayWidth(l)})：$l", displayWidth(l) <= 70)
        }
    }

    /** 与 PrintUtil 内部的宽度算法一致：中文 2、其它 1。 */
    private fun displayWidth(s: String): Int {
        var w = 0
        for (c in s) {
            val code = c.code
            w += if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF) 2 else 1
        }
        return w
    }

    @Test
    fun `空车也能生成小票而不抛`() {
        val r = receipt(lines = emptyList())

        assertTrue(r, r.contains("销售小票"))
        assertEquals(0, r.lines().count { it.contains("6901234567890") })
    }
}
