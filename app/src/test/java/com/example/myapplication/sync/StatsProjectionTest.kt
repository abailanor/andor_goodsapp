package com.example.myapplication.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 统计投影的规则本身。
 *
 * 这些断言盯的是"账算错了也不会报错"的那一类问题：
 * dimKey 的宽度、A/B 的营收判据、取消回退用哪个时刻的字段、`ts=0` 的兜底。
 */
class StatsProjectionTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val cst = TimeZone.getTimeZone("GMT+8")

    /** 2026-09-15T00:00:00Z。 */
    private val sept15 = 1789430400000L

    /** 2026-09-30T16:00:00Z —— 在 UTC+8 已经是 10-01 00:00。 */
    private val sept30late = 1790784000000L

    private val bSept = OrderFileId.parse("B:alice:2026-09")
    private val a2026 = OrderFileId.parse("A:alice:2026")

    // ==================== dimKey 的形状 ====================

    @Test
    fun `日期键恒为_10_字符且零填充`() {
        val k = StatsProjection.dateKey(sept15, utc)
        assertEquals("2026-09-15", k)
        assertEquals(StatsProjection.DATE_WIDTH, k.length)
    }

    @Test
    fun `月与日都补零_否则字典序与_substr_全乱`() {
        // 2026-09-15 是那一年第 258 天、2026-01-05 是第 5 天，差 253 天
        val jan5 = StatsProjection.dateKey(sept15 - 253L * 86400000, utc)
        assertEquals("2026-01-05", jan5)
        assertEquals(StatsProjection.DATE_WIDTH, jan5.length)
    }

    @Test
    fun `完整_dimKey_是_日期_竖线_w_冒号_writer`() {
        assertEquals("2026-09-15|w:alice", StatsProjection.dayKey("alice", sept15, utc))
    }

    @Test
    fun `WRITER_SQL_FROM_真的落在_writer_的第一个字符上`() {
        // SQL 的 substr 是 1-based，Kotlin 的 substring 是 0-based，所以差 1。
        // 这条断言就是那个换算的守卫：它错了不会报错，只会把两个人的业绩并到一个名字下。
        for (writer in listOf("alice", "张三", "b", "a|b")) {
            val key = StatsProjection.dayKey(writer, sept15, utc)
            assertEquals("writer=$writer", writer, key.substring(StatsProjection.WRITER_SQL_FROM - 1))
        }
    }

    @Test
    fun `日期段与_writer_段的边界是固定的`() {
        val key = StatsProjection.dayKey("alice", sept15, utc)
        assertEquals("2026-09-15", key.substring(0, StatsProjection.DATE_WIDTH))
        assertEquals("|", key.substring(StatsProjection.DATE_WIDTH, StatsProjection.DATE_WIDTH + 1))
        assertEquals(StatsProjection.WRITER_DIM_PREFIX, key.substring(StatsProjection.DATE_WIDTH + 1, StatsProjection.WRITER_SQL_FROM - 1))
    }

    @Test
    fun `writer_里带竖线也不会让日期段错位_因为日期在最前面`() {
        // 反过来的顺序（`w:a|b|2026-09-15`）就会被这个用户名劈开
        val key = StatsProjection.dayKey("a|b", sept15, utc)
        assertEquals("2026-09-15", key.substring(0, StatsProjection.DATE_WIDTH))
        assertEquals("a|b", key.substring(StatsProjection.WRITER_SQL_FROM - 1))
    }

    // ==================== 时区 ====================

    @Test
    fun `同一个时刻在不同时区落到不同的日键_这就是改时区必须重投影的原因`() {
        assertEquals("2026-09-30", StatsProjection.dateKey(sept30late, utc))
        assertEquals("2026-10-01", StatsProjection.dateKey(sept30late, cst))
        assertNotEquals(
            StatsProjection.dayKey("alice", sept30late, utc),
            StatsProjection.dayKey("alice", sept30late, cst),
        )
    }

    // ==================== scope ====================

    @Test
    fun `scope_的命名与解析互逆`() {
        val f = "B:alice:2026-09"
        assertEquals("FILE:$f", StatsProjection.fileScope(f))
        assertEquals(f, StatsProjection.fileIdOfScope(StatsProjection.fileScope(f)))
    }

    @Test
    fun `OUTBOX_桶与文件桶不会互相解析`() {
        assertNull(StatsProjection.fileIdOfScope(StatsProjection.OUTBOX_SCOPE))
        assertNotEquals(StatsProjection.OUTBOX_SCOPE, StatsProjection.fileScope("B:alice:2026-09"))
    }

    // ==================== 贡献 ====================

    @Test
    fun `B_类一笔订单产出三条正数贡献`() {
        val c = StatsProjection.contributionOf(bSept, "alice", sept15, utc, 5200L, 14)
        assertEquals(3, c.size)
        val byMetric = c.associate { it.metric to it.value }
        assertEquals(5200L, byMetric[StatsProjection.METRIC_AMOUNT])
        assertEquals(1L, byMetric[StatsProjection.METRIC_ORDERS])
        assertEquals(14L, byMetric[StatsProjection.METRIC_PIECES])
        assertTrue(c.all { it.dimKey == "2026-09-15|w:alice" })
        assertTrue("贡献必须一律是正数，符号由调用点决定", c.all { it.value > 0 })
    }

    @Test
    fun `A_类不产出任何贡献_进货款不是本店营收`() {
        assertTrue(StatsProjection.contributionOf(a2026, "alice", sept15, utc, 5200L, 14).isEmpty())
    }

    @Test
    fun `三个指标名互不相同`() {
        val s = setOf(
            StatsProjection.METRIC_AMOUNT,
            StatsProjection.METRIC_ORDERS,
            StatsProjection.METRIC_PIECES,
        )
        assertEquals(3, s.size)
    }

    @Test
    fun `同一天的两笔订单产出同一个_dimKey_于是可以在库里相加`() {
        val a = StatsProjection.contributionOf(bSept, "alice", sept15, utc, 100L, 1)
        val b = StatsProjection.contributionOf(bSept, "alice", sept15 + 3600_000, utc, 250L, 3)
        assertEquals(a.map { it.dimKey }, b.map { it.dimKey })
    }

    @Test
    fun `件数为_0_时仍然产出那一行_否则_笔数_与_件数_会对不上`() {
        // 服务端对 A 类订单一条 item 不合格就整体清空（extractItems 的宽容路径），
        // B 类也可能因为 items 缺失而为 0。这时"1 笔、0 件、0 元"是真实的账，不能整行省掉。
        val c = StatsProjection.contributionOf(bSept, "alice", sept15, utc, 0L, 0)
        assertEquals(3, c.size)
        assertEquals(1L, c.first { it.metric == StatsProjection.METRIC_ORDERS }.value)
    }

    // ==================== ts = 0 的兜底 ====================

    @Test
    fun `ts_非_0_时原样使用`() {
        assertEquals(sept15, StatsProjection.effectiveTs(sept15, bSept, utc))
    }

    @Test
    fun `ts_为_0_时兜到文件所属月份的第一天_而不是_1970`() {
        val ts = StatsProjection.effectiveTs(0L, bSept, utc)
        assertEquals("2026-09-01", StatsProjection.dateKey(ts, utc))
    }

    @Test
    fun `兜底之后这笔钱仍然落在正确的月份里`() {
        // 用 0 的话日键是 1970-01-01，任何真实查询区间都覆盖不到它 → 营业额静默少一笔。
        // 这条断言钉住"月份是对的，只有哪一天是猜的"。
        val c = StatsProjection.contributionOf(bSept, "alice", 0L, utc, 5200L, 14)
        assertTrue(c.all { it.dimKey.startsWith("2026-09-") })
    }

    @Test
    fun `A_类没有月份维度_兜底按_1_月算`() {
        assertEquals(
            "2026-01-01",
            StatsProjection.dateKey(StatsProjection.effectiveTs(0L, a2026, utc), utc),
        )
    }

    @Test
    fun `兜底随时区变化_因为_这个月的第一天零点_本来就是时区相关的`() {
        // UTC+8 的 9-01 00:00 比 UTC 的早 8 小时；两者都仍然落在 9 月，这是要紧的部分
        assertEquals(
            "2026-09-01",
            StatsProjection.dateKey(StatsProjection.effectiveTs(0L, bSept, cst), cst),
        )
    }
}
