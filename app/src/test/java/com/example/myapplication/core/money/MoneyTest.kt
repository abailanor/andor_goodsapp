package com.example.myapplication.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * 金额换算的测试。
 *
 * 这里的每一条断言都对应一个**不报错**的账目损坏路径 —— 差一分查不出是哪一笔，
 * 而一天上百单、一单上百行会把它累加成肉眼可见的差额。
 */
class MoneyTest {

    @Test
    fun `toCents_不走_Double_所以不会少一分`() {
        // `"1.15".toDouble() * 100` == 114.99999999999999 → `toInt()` 截断成 **114**。
        // 这是全项目禁止 Double 参与金额换算的原因，断言必须钉住它。
        assertEquals(114L, (1.15 * 100).toInt().toLong())          // 先证明 Double 真的会错
        assertEquals(115L, Money.toCents("1.15"))                  // 再证明 BigDecimal 不会
        assertEquals(5200L, Money.toCents("52.00"))
        assertEquals(0L, Money.toCents("0"))
        assertEquals(1L, Money.toCents("0.01"))
        assertEquals(-350L, Money.toCents("-3.50"))
        assertEquals(99999999999L, Money.toCents("999999999.99"))
    }

    @Test
    fun `toCents_超过两位小数按_HALF_UP_取整到分`() {
        assertEquals(115L, Money.toCents("1.149"))
        assertEquals(115L, Money.toCents("1.1450"))
        assertEquals(114L, Money.toCents("1.144"))
        // 正常数据不会走到这里（服务端价格与 StableOrderJson 都是两位），
        // 留着是为了不因一条怪数据毁掉整次 FULL 重建。
    }

    @Test
    fun `toCents_遇到垃圾字符串就抛_这是故意的`() {
        // 抛而不兜底：金额解析不出来说明镜像里那一行不是我们写的，
        // 此时任何兜底数字都是在编造账目。调用方负责捕获、跳过该行并记审计日志。
        assertThrows(NumberFormatException::class.java) { Money.toCents("") }
        assertThrows(NumberFormatException::class.java) { Money.toCents("abc") }
        assertThrows(NumberFormatException::class.java) { Money.toCents("12.3.4") }
        assertThrows(NumberFormatException::class.java) { Money.toCents("1,000.00") }
        assertThrows(NumberFormatException::class.java) { Money.toCents("null") }
    }

    @Test
    fun `toCents_容忍首尾空白_但不容忍中间的空格`() {
        // 手写/Excel 导出的 CSV 常带尾随空格，所以 trim 是必要的。
        assertEquals(5200L, Money.toCents("  52.00  "))
        assertThrows(NumberFormatException::class.java) { Money.toCents("52. 00") }
    }

    @Test
    fun `toCentsOr_把三种失败都收敛到_fallback`() {
        assertEquals(0L, Money.toCentsOr(null))
        assertEquals(0L, Money.toCentsOr("   "))
        assertEquals(0L, Money.toCentsOr("abc"))
        assertEquals(-1L, Money.toCentsOr("abc", -1L))
        assertEquals(5200L, Money.toCentsOr("52.00", -1L))
    }

    @Test
    fun `fromCents_恒定两位小数_不随值变化`() {
        // 恒定两位是必须的：这个数字有可能被写回订单 JSON（结算时手动覆盖总额），
        // 而 `52` 与 `52.00` 会让两台设备算出不同的 total 字符串 → 不同的字节 → 镜像分歧。
        assertEquals("0.00", Money.fromCents(0))
        assertEquals("0.01", Money.fromCents(1))
        assertEquals("52.00", Money.fromCents(5200))
        assertEquals("1.10", Money.fromCents(110))
        assertEquals("-3.50", Money.fromCents(-350))
        assertEquals("0.10", Money.fromCents(10))       // 不是 "0.1"
        assertEquals("100.00", Money.fromCents(10000))  // 不是 "100"
    }

    @Test
    fun `往返_分_到_字符串_再回到分_恒等`() {
        val samples = longArrayOf(0, 1, 5, 99, 100, 115, 5200, 999999, -1, -350, Long.MAX_VALUE / 100)
        for (c in samples) {
            assertEquals("cents=$c", c, Money.toCents(Money.fromCents(c)))
        }
    }

    @Test
    fun `fromCents_绝不产生科学计数法`() {
        // `toPlainString()` 而不是 `toString()`：BigDecimal 在指数为负时会输出 `1E+2` 这种形状，
        // 写进 JSON 就成了非法金额。
        for (c in longArrayOf(0, 100, 100000, 10000000000, -100000000)) {
            val s = Money.fromCents(c)
            assertEquals("cents=$c 输出了非十进制形状", s, BigDecimal(s).toPlainString())
            assertFalse("cents=$c 含科学计数法", s.contains('E') || s.contains('e'))
        }
    }
}
