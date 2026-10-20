package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * `0` 前缀整件行合并规则的测试。
 *
 * 这套逻辑被三条路径共用（`MIGRATION_3_4`、旧版 SQLite 导入、v0 CSV 导入），
 * 所以它错了会同时污染三个入口。
 */
class BoxRowMergerTest {

    private fun row(barcode: String, name: String, price: String) =
        BoxRowMerger.LegacyRow(barcode, name, BigDecimal(price))

    @Test
    fun `整件行与单行合并成一条_boxPrice_来自整件行`() {
        val r = BoxRowMerger.merge(
            listOf(
                row("06901234567890", "可口可乐330ml整件", "80.00"),
                row("6901234567890", "可口可乐330ml", "3.50"),
            )
        )
        assertEquals(1, r.merged.size)
        assertEquals(0, r.orphanBoxRows.size)
        val p = r.merged.single().product
        assertEquals("6901234567890", p.barcode)
        assertEquals("可口可乐330ml", p.name)
        assertEquals(BigDecimal("3.50"), p.price)
        assertEquals(BigDecimal("80.00"), p.boxPrice)
    }

    @Test
    fun `箱规在旧模型里不存在_一律填_0_并标记为_needsBoxSize`() {
        val r = BoxRowMerger.merge(
            listOf(
                row("0690", "可乐整件", "80.00"),
                row("690", "可乐", "3.50"),
            )
        )
        val p = r.merged.single().product
        assertEquals(0, p.boxSize)
        // 这是"能算价、不能上传"的那一类：开单时必须弹箱规表单
        assertTrue("有整箱价但没箱规，必须标记为待补规格", p.needsBoxSize)
        assertFalse(p.sellsByBox)
    }

    @Test
    fun `名字尾部的整件二字被去掉`() {
        val r = BoxRowMerger.merge(listOf(row("690", "可口可乐整件", "3.50")))
        assertEquals("可口可乐", r.merged.single().product.name)
    }

    @Test
    fun `名字恰好只有整件二字时不能截成空串`() {
        val r = BoxRowMerger.merge(listOf(row("690", "整件", "3.50")))
        assertEquals("整件", r.merged.single().product.name)
    }

    @Test
    fun `只有单行没有整件行_boxPrice_为_null_而不是_0`() {
        // null = 不按整箱卖；0 = 整箱免费。两者语义不同，不能混。
        val r = BoxRowMerger.merge(listOf(row("690", "可乐", "3.50")))
        val p = r.merged.single().product
        assertNull(p.boxPrice)
        assertFalse(p.needsBoxSize)
        assertFalse(p.sellsByBox)
        assertFalse(r.merged.single().mergedFromBoxRow)
    }

    @Test
    fun `孤儿整件行不丢_报进_orphanBoxRows`() {
        // 迁移里静默删用户数据是不可接受的：用户没有任何机会挽回。
        val r = BoxRowMerger.merge(listOf(row("0690", "可乐整件", "80.00")))
        assertEquals(0, r.merged.size)
        assertEquals(1, r.orphanBoxRows.size)
        assertEquals("0690", r.orphanBoxRows.single().barcode)
    }

    @Test
    fun `条码就是单个_0_时不算出空条码的孤儿`() {
        val r = BoxRowMerger.merge(listOf(row("0", "奇怪的数据", "1.00")))
        // removePrefix 之后是空串，没有对应的单行 → 孤儿，但绝不能产出 barcode="" 的商品
        assertEquals(1, r.orphanBoxRows.size)
        assertEquals(0, r.merged.size)
    }

    @Test
    fun `整件行名字不以整件结尾时仍按整件行处理但记为不规范`() {
        // 旧数据不总是规范的（AddProductScreen 写死了后缀，但手工导入的不一定）
        val r = BoxRowMerger.merge(
            listOf(
                row("0690", "可乐箱装", "80.00"),
                row("690", "可乐", "3.50"),
            )
        )
        assertEquals(1, r.merged.size)
        val m = r.merged.single()
        assertTrue(m.mergedFromBoxRow)
        assertFalse(m.nameHadBoxSuffix)
        assertEquals(1, r.irregularNameCount)
        assertEquals(BigDecimal("80.00"), m.product.boxPrice)
        // 名字取单行的，不取整件行的
        assertEquals("可乐", m.product.name)
    }

    @Test
    fun `整件行在前还是在后都不影响结果`() {
        val a = BoxRowMerger.merge(listOf(row("0690", "可乐整件", "80"), row("690", "可乐", "3.5")))
        val b = BoxRowMerger.merge(listOf(row("690", "可乐", "3.5"), row("0690", "可乐整件", "80")))
        assertEquals(a.merged.single().product, b.merged.single().product)
    }

    @Test
    fun `空条码行被忽略`() {
        val r = BoxRowMerger.merge(listOf(row("", "没有条码", "1.00"), row("690", "可乐", "3.50")))
        assertEquals(1, r.merged.size)
        assertEquals("690", r.merged.single().product.barcode)
    }

    @Test
    fun `同一条码重复出现时后者覆盖前者_不产出两条`() {
        val r = BoxRowMerger.merge(
            listOf(row("690", "旧名", "1.00"), row("690", "新名", "2.00"))
        )
        assertEquals(1, r.merged.size)
        assertEquals("新名", r.merged.single().product.name)
    }

    @Test
    fun `合并后的_origin_是_LOCAL`() {
        // 迁移与导入进来的商品都属于"未同步区"，FULL 覆盖不到它们
        val r = BoxRowMerger.merge(listOf(row("690", "可乐", "3.50")))
        assertEquals(Product.ORIGIN_LOCAL, r.merged.single().product.origin)
    }

    @Test
    fun `一批真实形态的数据_数量守恒`() {
        val rows = (1..200).flatMap { i ->
            val bc = "69%011d".format(i)
            listOf(
                row("0$bc", "商品${i}整件", "100.00"),
                row(bc, "商品$i", "5.00"),
            )
        }
        val r = BoxRowMerger.merge(rows)
        assertEquals("400 行旧数据应当合并成 200 条", 200, r.merged.size)
        assertEquals(0, r.orphanBoxRows.size)
        assertEquals(200, r.mergedBoxRowCount)
        assertTrue(r.merged.all { it.product.boxPrice == BigDecimal("100.00") })
        assertTrue(r.merged.all { it.product.price == BigDecimal("5.00") })
        assertNotNull(r.merged.first().product)
    }
}
