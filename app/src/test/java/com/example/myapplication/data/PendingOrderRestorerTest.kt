package com.example.myapplication.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 待开单还原的回归测试。
 *
 * 锁死的 bug：进货单的商品不在本地商品库（来自管理员目录），
 * 旧实现逐行查库、查不到就丢行 → 点「开单」后购物车是空的，且无任何提示。
 */
class PendingOrderRestorerTest {

    private fun line(
        barcode: String,
        name: String,
        price: String,
        quantity: Int,
    ) = PendingOrderLine(
        barcode = barcode,
        name = name,
        price = price,
        quantity = quantity,
        // subtotal 也要容错：测试数据里故意放过非法价格，helper 自己不能先崩
        subtotal = (price.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            .multiply(BigDecimal(quantity)).toPlainString(),
    )

    private fun record(items: List<PendingOrderLine>, mode: String = "PURCHASE") =
        PendingOrderRecord(createdAt = 1_700_000_000_000, fileName = "pending_test.json",
                           totalPrice = "0.00", items = items, mode = mode)

    /** 库里只有 part1；part2/part3 查不到（模拟进货单商品未同步目录）。 */
    private val inDb = mapOf(
        "6900000000001" to Product(barcode = "6900000000001", name = "可乐", price = BigDecimal("3.50")),
    )

    private suspend fun lookup(barcode: String): Product? = inDb[barcode]

    @Test
    fun 库里有的行用库里最新数据() = runTest {
        val r = record(listOf(line("6900000000001", "可乐", "3.50", 3)))
        val (items, fromSnapshot) = restorePendingOrderItems(r, ::lookup)
        assertEquals(1, items.size)
        assertEquals(0, fromSnapshot)
        assertEquals("6900000000001", items[0].product.barcode)
        assertEquals(3, items[0].quantity)
    }

    @Test
    fun 库里没有的行按快照恢复_不丢行() = runTest {
        val r = record(listOf(
            line("6900000000001", "可乐", "3.50", 3),
            line("ADMIN001", "丽华金龙·大矿泉水", "45.00", 2),
        ))
        val (items, fromSnapshot) = restorePendingOrderItems(r, ::lookup)
        // 关键断言：两行都在，而不是只剩第一行
        assertEquals(2, items.size)
        assertEquals(1, fromSnapshot)
        assertEquals("ADMIN001", items[1].product.barcode)
        assertEquals("丽华金龙·大矿泉水", items[1].product.name)
        assertEquals(BigDecimal("45.00"), items[1].product.price)
        assertEquals(2, items[1].quantity)
    }

    @Test
    fun 全部查不到时返回快照行而不是空列表() = runTest {
        val r = record(listOf(line("X1", "甲", "1.00", 1), line("X2", "乙", "2.00", 2)))
        val (items, fromSnapshot) = restorePendingOrderItems(r) { null }
        assertEquals(2, items.size)
        assertEquals(2, fromSnapshot)
        assertEquals("甲", items[0].product.name)
    }

    @Test
    fun 快照价格非法时退零_不至于崩() = runTest {
        val r = record(listOf(line("X1", "甲", "不是数字", 1)))
        val (items, _) = restorePendingOrderItems(r) { null }
        assertEquals(BigDecimal.ZERO, items[0].product.price)
    }

    @Test
    fun 快照名为空时用条码占位() = runTest {
        val r = record(listOf(line("X1", "", "1.00", 1)))
        val (items, _) = restorePendingOrderItems(r) { null }
        assertEquals("X1", items[0].product.name)
    }

    @Test
    fun 行顺序保持文件顺序() = runTest {
        val r = record(listOf(
            line("B", "乙", "2.00", 1),
            line("A", "甲", "1.00", 1),
            line("6900000000001", "可乐", "3.50", 1),
        ))
        val (items, _) = restorePendingOrderItems(r, ::lookup)
        assertEquals(listOf("B", "A", "6900000000001"), items.map { it.product.barcode })
    }

    @Test
    fun 快照行与库行的标识不同_不会串数据() = runTest {
        val r = record(listOf(line("X1", "甲", "9.99", 1)))
        val (items, _) = restorePendingOrderItems(r) { null }
        val snap = items[0].product
        // 快照行的 origin 必须是 LOCAL（不是 SYNCED）——库存/价格链路据此区分数据来源
        assertEquals(Product.ORIGIN_LOCAL, snap.origin)
        assertNotEquals(Product.ORIGIN_SYNCED, snap.origin)
        assertTrue(snap.stock == 0 && snap.boxSize == 0)
    }
}
