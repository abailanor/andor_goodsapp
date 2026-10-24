package com.example.myapplication.data

import com.example.myapplication.db.OrderDayRow
import com.example.myapplication.sync.StatsProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 日历聚合的测试。
 *
 * ## 这里最容易错、且错了看不出来的两件事
 *
 *  1. **时区**：分桶必须与 `StatsProjection.dateKey` 用同一个时区。差一小时（跨午夜的那笔）
 *     就会把一笔订单画到隔壁那天上 —— 日历与列表各说各话，两边看都"对"。
 *  2. **销售与进货混算**：进货单的 `totalCents` 恒为 0（payload 里没有金额字段），
 *     混进销售金额不会算错钱，但会把"这天进了 5 笔货"混进"这天开了 12 笔单"，
 *     而用户看日历正是为了分清这两件事。
 */
class OrderCalendarTest {

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    /** 东八区的某个时刻。`h` 是当地小时。 */
    private fun ts(year: Int, month: Int, day: Int, h: Int = 12): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, h, 0, 0)
        }.timeInMillis

    private fun b(orderTs: Long, totalCents: Long, status: Int = 1) =
        OrderDayRow(orderTs = orderTs, totalCents = totalCents, status = status, fileId = "B:alice:2026-10")

    private fun a(orderTs: Long) =
        OrderDayRow(orderTs = orderTs, totalCents = 0L, status = 1, fileId = "A:alice:2026")

    private fun outbox(orderTs: Long, totalCents: Long, fileType: String = "B") =
        OrderOutboxRow(orderTs = orderTs, totalCents = totalCents, fileType = fileType)

    // ==================== 分桶 ====================

    @Test
    fun `同一天的多笔合成一格_金额相加`() {
        val cal = OrderCalendar.of(
            synced = listOf(
                b(ts(2026, 10, 5, 9), 1_000L),
                b(ts(2026, 10, 5, 18), 2_500L),
                b(ts(2026, 10, 6, 9), 700L),
            ),
            pending = emptyList(),
            timeZone = tz,
        )

        assertEquals(OrderDay("2026-10-05", saleOrders = 2, saleCents = 3_500L, saleCancelled = 0, purchaseOrders = 0),
            cal.dayOf(2026, 10, 5))
        assertEquals(700L, cal.dayOf(2026, 10, 6)!!.saleCents)
        assertEquals(setOf(5, 6), cal.daysOf(2026, 10).keys)
    }

    @Test
    fun `分桶与_StatsProjection_dateKey_用的是同一套日期`() {
        // 这一条是"日历与统计不会对不上"的唯一保证：两边都必须走 dateKey。
        val t = ts(2026, 3, 14, 23)
        val cal = OrderCalendar.of(listOf(b(t, 100L)), emptyList(), tz)

        assertEquals(StatsProjection.dateKey(t, tz), cal.daysOf(2026, 3).keys.single().let { d ->
            "2026-03-%02d".format(d)
        })
    }

    @Test
    fun `跨午夜的订单落在当地时区的那一天_而不是_UTC_那一天`() {
        // 东八区 10-05 00:30 == UTC 10-04 16:30。按 UTC 分桶会画到 4 号上。
        val t = ts(2026, 10, 5, 0).let { it + 30 * 60_000L }
        val cal = OrderCalendar.of(listOf(b(t, 100L)), emptyList(), tz)

        assertEquals(setOf(5), cal.daysOf(2026, 10).keys)
        assertNull(cal.dayOf(2026, 10, 4))
    }

    @Test
    fun `换时区会换一天_这正是为什么必须与统计共用同一个时区`() {
        val t = ts(2026, 10, 5, 0).let { it + 30 * 60_000L }   // 东八区 10-05 00:30
        val utc = OrderCalendar.of(listOf(b(t, 100L)), emptyList(), TimeZone.getTimeZone("UTC"))

        assertEquals(setOf(4), utc.daysOf(2026, 10).keys)
    }

    // ==================== 销售 / 进货 / 取消 分开计 ====================

    @Test
    fun `进货单单独计数_且不进销售金额`() {
        val cal = OrderCalendar.of(
            synced = listOf(b(ts(2026, 10, 5), 1_000L), a(ts(2026, 10, 5)), a(ts(2026, 10, 5, 15))),
            pending = emptyList(),
            timeZone = tz,
        )
        val d = cal.dayOf(2026, 10, 5)!!

        assertEquals(1, d.saleOrders)
        assertEquals(1_000L, d.saleCents)
        assertEquals(2, d.purchaseOrders)
        assertEquals(3, d.totalOrders)
    }

    @Test
    fun `已取消的销售单不计笔数与金额_但单独留痕`() {
        val cal = OrderCalendar.of(
            synced = listOf(b(ts(2026, 10, 5), 1_000L), b(ts(2026, 10, 5, 13), 2_000L, status = 0)),
            pending = emptyList(),
            timeZone = tz,
        )
        val d = cal.dayOf(2026, 10, 5)!!

        assertEquals(1, d.saleOrders)
        assertEquals(1_000L, d.saleCents)
        assertEquals("取消要看得见，否则用户对着『1 笔』数出 2 张单子会以为系统漏了", 1, d.saleCancelled)
    }

    @Test
    fun `整天只剩一张取消单时_这一天仍然出现在日历上`() {
        val cal = OrderCalendar.of(
            synced = listOf(b(ts(2026, 10, 5), 1_000L, status = 0)),
            pending = emptyList(),
            timeZone = tz,
        )

        assertTrue("全被撤掉的那天如果从日历上消失，用户就再也找不到那张被撤的单了",
            cal.daysOf(2026, 10).containsKey(5))
        assertEquals(0, cal.monthTotal(2026, 10).orders)
        assertEquals(0L, cal.monthTotal(2026, 10).cents)
    }

    // ==================== 未上传的单 ====================

    @Test
    fun `未上传的单也算进日历_刚开完的单必须马上看得见`() {
        val cal = OrderCalendar.of(
            synced = listOf(b(ts(2026, 10, 5), 1_000L)),
            pending = listOf(outbox(ts(2026, 10, 5, 14), 2_500L)),
            timeZone = tz,
        )
        val d = cal.dayOf(2026, 10, 5)!!

        assertEquals(2, d.saleOrders)
        assertEquals(3_500L, d.saleCents)
    }

    @Test
    fun `未上传的进货单进的是进货计数`() {
        val cal = OrderCalendar.of(
            synced = emptyList(),
            pending = listOf(outbox(ts(2026, 10, 5), 0L, fileType = "A")),
            timeZone = tz,
        )

        assertEquals(1, cal.dayOf(2026, 10, 5)!!.purchaseOrders)
        assertEquals(0, cal.dayOf(2026, 10, 5)!!.saleOrders)
    }

    // ==================== 年 / 月列表 ====================

    @Test
    fun `years_与_monthsOf_只含有单的_且降序`() {
        val cal = OrderCalendar.of(
            synced = listOf(
                b(ts(2025, 3, 1), 100L),
                b(ts(2026, 10, 1), 100L),
                b(ts(2026, 2, 1), 100L),
                b(ts(2026, 10, 20), 100L),
            ),
            pending = emptyList(),
            timeZone = tz,
        )

        assertEquals(listOf(2026, 2025), cal.years)
        assertEquals(listOf(10, 2), cal.monthsOf(2026))
        assertEquals(listOf(3), cal.monthsOf(2025))
        assertEquals("没单的年份返回空列表，而不是 null 或 1..12", emptyList<Int>(), cal.monthsOf(2024))
    }

    @Test
    fun `monthTotal_与_yearTotal_是各天之和`() {
        val cal = OrderCalendar.of(
            synced = listOf(
                b(ts(2026, 10, 5), 1_000L),
                b(ts(2026, 10, 6), 2_000L),
                b(ts(2026, 11, 1), 4_000L),
            ),
            pending = emptyList(),
            timeZone = tz,
        )

        assertEquals(OrderCalendar.MonthTotal(orders = 2, cents = 3_000L, purchaseOrders = 0, days = 2),
            cal.monthTotal(2026, 10))
        assertEquals(OrderCalendar.MonthTotal(orders = 3, cents = 7_000L, purchaseOrders = 0, days = 3),
            cal.yearTotal(2026))
        assertEquals(OrderCalendar.MonthTotal(0, 0L, 0, 0), cal.monthTotal(2026, 1))
    }

    @Test
    fun `空日历_什么都查不出来但不抛`() {
        val cal = OrderCalendar.of(emptyList(), emptyList(), tz)

        assertTrue(cal.isEmpty)
        assertEquals(emptyList<Int>(), cal.years)
        assertEquals(emptyList<Int>(), cal.monthsOf(2026))
        assertTrue(cal.daysOf(2026, 10).isEmpty())
        assertNull(cal.dayOf(2026, 10, 5))
        assertEquals(OrderCalendar.MonthTotal(0, 0L, 0, 0), cal.yearTotal(2026))
        // EMPTY 是选择器查询还没回来时的初始值，形状必须与"查完了但一笔都没有"一致
        assertTrue(OrderCalendar.EMPTY.isEmpty)
    }
}
