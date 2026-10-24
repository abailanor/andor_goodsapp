package com.example.myapplication.data

import com.example.myapplication.db.OrderDayRow
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.StatsProjection
import java.util.TimeZone

/**
 * 日历上**一天**的汇总。
 *
 * ## 为什么销售与进货分开计数、而进货没有金额
 *
 * 进货单（A 类）的 payload 里**根本没有金额字段**（见 [com.example.myapplication.sync.outbox.OrderDraft.total]），
 * `totalCents` 恒为 0。把它与销售金额加在一起不会算错钱（0 加进去等于没加），
 * 但会把"这一天进了 5 笔货"混进"这一天开了 12 笔单"里 ——
 * 而用户看日历时想知道的正是"哪天生意好、哪天该补货"，两者混一格就都看不出来了。
 *
 * @param saleOrders 有效销售笔数（`status == 1`）。已取消的不算笔数，但单独计一个数。
 * @param saleCents  有效销售金额（分）。
 * @param saleCancelled 已取消的销售笔数。日历上要点一下就能看出"这一天有单被撤了"，
 *        否则用户对着"3 笔"数出 4 张单子，会以为是系统漏了。
 * @param purchaseOrders 进货笔数（A 类不看 status：A 类的 2/3 是总部那边的完成/删除，
 *        与"本店进没进这批货"是两件事）。
 */
data class OrderDay(
    val date: String,
    val saleOrders: Int,
    val saleCents: Long,
    val saleCancelled: Int,
    val purchaseOrders: Int,
) {
    val totalOrders: Int get() = saleOrders + purchaseOrders
    val isEmpty: Boolean get() = totalOrders == 0 && saleCancelled == 0
}

/**
 * 按天聚合好的订单索引。**「订单」页的日历与期间选择器都读它。**
 *
 * ## 为什么要在客户端建这一层，而不是每次查询现算
 *
 * 用户要的是"点开只列出**数据库里实际存在**的年份/月份"。那需要知道全部有单的日期，
 * 而列表查询是按区间过滤的 —— 拿不到区间外的日期。
 * 所以这里一次性把 `order_index` + `order_outbox` 的日期桶建出来，
 * 之后"哪些年有单""这个月哪几天有单""这一天几笔多少钱"全是内存里的 map 查找。
 *
 * 规模：一个店一天上百单、跑三年 ≈ 10 万行，但**桶**只有 ~1100 个（天），
 * 建一次是几毫秒的事，且由 Flow 驱动、只在数据真变了时重建。
 *
 * ## ⚠ 分桶一律走 [StatsProjection.dateKey]
 *
 * 与统计、与列表区间用的是同一套日期算法（同一个 [TimeZone]）。
 * 各算一套的话会出现"列表里有这一笔、日历上那天是空的" —— 两边看都对，合起来是鬼。
 */
class OrderCalendar private constructor(
    private val days: Map<String, OrderDay>,
    private val months: Map<Int, Set<Int>>,   // year → 有单的月份
) {

    /**
     * 某个月的总笔数与总金额。月历的表头与"年月列表"要用。
     *
     * 从 [days] 现加，不另存一份 —— 两份聚合值迟早会对不上，而对不上的时候
     * 用户看到的是"月历加起来不等于表头那个数"，没有任何报错。
     */
    data class MonthTotal(
        val orders: Int,
        val cents: Long,
        val purchaseOrders: Int,
        val days: Int,
    )

    fun monthTotal(year: Int, month: Int): MonthTotal {
        var orders = 0
        var cents = 0L
        var purchase = 0
        var days = 0
        for ((_, d) in daysOf(year, month)) {
            orders += d.saleOrders
            cents += d.saleCents
            purchase += d.purchaseOrders
            days++
        }
        return MonthTotal(orders, cents, purchase, days)
    }

    fun yearTotal(year: Int): MonthTotal {
        var orders = 0
        var cents = 0L
        var purchase = 0
        var dayCount = 0
        for (m in monthsOf(year)) {
            val t = monthTotal(year, m)
            orders += t.orders
            cents += t.cents
            purchase += t.purchaseOrders
            dayCount += t.days
        }
        return MonthTotal(orders, cents, purchase, dayCount)
    }

    /** 有单的年份，**降序**（最近的在上面，选择器不用倒着翻）。 */
    val years: List<Int> = months.keys.sortedDescending()

    /** 该年里有单的月份，降序。没有单就是空列表。 */
    fun monthsOf(year: Int): List<Int> = months[year]?.sortedDescending() ?: emptyList()

    /** `"yyyy-MM"`（[month] 是 1..12）。 */
    fun monthKey(year: Int, month: Int): String = "%04d-%02d".format(year, month)

    /** 某一天。没单返回 null（**不是**一个全 0 的 [OrderDay]，调用方用 null 判"这天是空的"）。 */
    fun dayOf(date: String): OrderDay? = days[date]

    /** 某一天（按年月日）。 */
    fun dayOf(year: Int, month: Int, day: Int): OrderDay? =
        days["%04d-%02d-%02d".format(year, month, day)]

    /** 该月里有单的日子 → 当天的汇总。日历用它决定哪些格子要点亮。 */
    fun daysOf(year: Int, month: Int): Map<Int, OrderDay> {
        val prefix = "%04d-%02d-".format(year, month)
        val out = LinkedHashMap<Int, OrderDay>()
        for ((date, d) in days) {
            if (date.startsWith(prefix)) {
                // substring 的起点是固定的（date 恒为 yyyy-MM-dd，见 StatsProjection.DATE_WIDTH）
                out[date.substring(StatsProjection.DATE_WIDTH - 2).toInt()] = d
            }
        }
        return out
    }

    val isEmpty: Boolean get() = days.isEmpty()

    companion object {

        /**
         * 空日历。**不是 null**：选择器可能在查询还没回来时就打开，
         * 空列表只是"暂时没有可选项"，而 null 会让整个对话框闪一下或干脆不显示。
         */
        val EMPTY: OrderCalendar = OrderCalendar(emptyMap(), emptyMap())

        /**
         * @param synced 已同步区（镜像里的订单）
         * @param pending 未上传区。⚠ **必须一起算**：刚开完单还没传上去的那几笔
         *        如果不在日历上，用户会以为"这一单没存下来"。
         *        `PROMOTED` 的行要排除 —— 它们已经在 [synced] 里了，两边都算就是双算。
         * @param includeCancelled 取消的单要不要在日历上留痕（默认留，单独计数）。
         */
        fun of(
            synced: List<OrderDayRow>,
            pending: List<OrderOutboxRow>,
            timeZone: TimeZone,
        ): OrderCalendar {
            val acc = HashMap<String, Acc>()

            fun add(orderTs: Long, totalCents: Long, status: Int, revenue: Boolean) {
                val key = StatsProjection.dateKey(orderTs, timeZone)
                val a = acc.getOrPut(key) { Acc() }
                when {
                    !revenue -> a.purchaseOrders++
                    status == 1 -> {
                        a.saleOrders++
                        a.saleCents += totalCents
                    }
                    status == 0 -> a.saleCancelled++
                    // status 2/3 只出现在 A 类（revenue == false），走不到这里
                }
            }

            for (r in synced) {
                add(r.orderTs, r.totalCents, r.status, OrderFileId.isRevenueFile(r.fileId))
            }
            for (r in pending) {
                // 未上传的单还没有"取消"这回事（取消是服务端补丁），status 一律当 1。
                add(r.orderTs, r.totalCents, 1, r.fileType == OrderFileId.TYPE_B)
            }

            val days = HashMap<String, OrderDay>(acc.size)
            val months = HashMap<Int, MutableSet<Int>>()
            for ((date, a) in acc) {
                val day = OrderDay(date, a.saleOrders, a.saleCents, a.saleCancelled, a.purchaseOrders)
                if (day.isEmpty) continue
                days[date] = day
                // date 恒为 "yyyy-MM-dd"（StatsProjection.dateKey 用 %04d-%02d-%02d 且 Locale.US）
                val year = date.substring(0, 4).toInt()
                val month = date.substring(5, 7).toInt()
                months.getOrPut(year) { LinkedHashSet() }.add(month)
            }
            return OrderCalendar(days, months)
        }
    }

    private class Acc {
        var saleOrders = 0
        var saleCents = 0L
        var saleCancelled = 0
        var purchaseOrders = 0
    }
}

/**
 * [OrderCalendar.of] 要的未上传行的最小形状。
 *
 * 只声明四个字段而不是直接收 `OrderOutboxEntity`：这一层是纯函数，
 * 让它依赖 Room 实体的话就没法在 JVM 上单测（而"哪天有几笔"算错是看不出来的）。
 */
data class OrderOutboxRow(
    val orderTs: Long,
    val totalCents: Long,
    /** `A` / `B`。 */
    val fileType: String,
)
