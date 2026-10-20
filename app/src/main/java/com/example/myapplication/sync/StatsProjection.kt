package com.example.myapplication.sync

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 统计投影的**纯函数**部分：`scope` 的命名、`dimKey` 的构造、以及"一笔订单贡献多少"。
 *
 * 落库（`stats_contrib` 的增减）在 [com.example.myapplication.db.OrderFileDao]，
 * 调用它的是 `RoomMirrorStateStore`。把这三件事分开是为了让**规则本身可以单测**：
 * 统计算错的后果是账目对不上，而它对不上时不会有任何报错。
 *
 * ## dimKey 的形状是 `"<yyyy-MM-dd>|w:<writer>"`，日期在前
 *
 * 只存**日**这一种粒度，月/年/自定义区间靠范围查询聚合出来。日期必须在前面，
 * 因为那是唯一能让 `WHERE dimKey >= ? AND dimKey < ?` 用上主键索引的顺序 ——
 * 统计页的典型查询正是"某个时间段内、按 writer 分组"：
 *
 * ```sql
 * SELECT substr(dimKey, 14) AS writer, SUM(value)
 * FROM stats_contrib
 * WHERE storeKey=? AND scope=? AND dimKey >= '2026-09-01|' AND dimKey < '2026-10-01|'
 * GROUP BY writer, metric
 * ```
 *
 * writer 在后半段做 `GROUP BY` 是**在已经被索引筛过的小集合上**分组，不是全表扫描。
 * 行数本身也小：5 个店员 × 365 天 × 3 个指标 ≈ 5500 行/年。
 *
 * ⚠ 日期必须**恒为 10 字符、零填充**，`|` 与 `w:` 必须**恒为 3 字符**，
 * 否则 `substr(dimKey, 14)` 就取错位置——而它取错时不报错，只会把两个人的业绩并到一个名字下。
 * 所以格式化一律用 [Locale.US]：默认 Locale 若是 `ar`/`fa` 之类，`%d` 会输出非 ASCII 数字，
 * 宽度对得上但字典序和 `substr` 的结果全乱。
 *
 * ## ⚠ 存日粒度意味着**改时区必须重投影**
 *
 * dimKey 是按当时设备的时区算出来的。用户把手机时区从 UTC+8 调到 UTC+0，
 * 同一笔订单的日键就从 `2026-09-15` 变成 `2026-09-14`：新数据落新键、旧数据留在旧键，
 * 于是**某一天的营业额被劈成两半**，而且两半都在库里、都能查出来，只是各自都偏小。
 * 唯一的修法是重投影（对每个 fileId 走一次 FULL，或从 `order_index.orderTs` 重算贡献）。
 * 这条限制必须写在设置页的时区提示里，不能只写在代码注释里。
 */
object StatsProjection {

    /** 金额（分）。统计一律用 `content.total`，明细只用于打"含手工调整"标记。 */
    const val METRIC_AMOUNT = "amountCents"

    /** 开单笔数。 */
    const val METRIC_ORDERS = "orderCount"

    /**
     * 件数 = `Σ items.quantity`（**个数**，不是行数）。
     *
     * ⚠ 与 `order_index.itemCount` **不是一回事**：后者是明细行数。
     * 一单买了 3 种商品、其中一种 14 个，`itemCount=3` 而件数 = 3 种各自数量之和。
     * 用户说的"件数"是后者，所以这个指标必须从 `order_item_index` 求和，
     * 不能拿 `itemCount` 顶替 —— 两个数字都"看着像件数"，混用了不会报错。
     */
    const val METRIC_PIECES = "pieceCount"

    /**
     * 未上传订单的贡献桶。
     *
     * 未上传订单**要**算进"今日开单金额"（不算的话用户开完单看统计没变，以为丢单），
     * 但它的贡献不属于任何 fileId、FULL 覆盖不到 —— 所以必须单独一个桶。
     * 订单提升进镜像时，在**同一个 Room 事务**里把贡献从 [OUTBOX_SCOPE] 搬到
     * [fileScope]（同 dimKey、同 metric，一边减一边加）。不做原子搬移必然双算或漏算。
     */
    const val OUTBOX_SCOPE = "OUTBOX"

    private const val FILE_SCOPE_PREFIX = "FILE:"

    /** dimKey 里 writer 维度的前缀。 */
    const val WRITER_DIM_PREFIX = "w:"

    /** dimKey 里日期段的宽度（`yyyy-MM-dd`）。 */
    const val DATE_WIDTH = 10

    /**
     * SQL 里取 writer 用的 `substr` 起点：`DATE_WIDTH`(10) + `|`(1) + `WRITER_DIM_PREFIX`(2) + 1。
     * （SQL 的 `substr` 是 1-based。）
     */
    const val WRITER_SQL_FROM = DATE_WIDTH + 1 + WRITER_DIM_PREFIX.length + 1

    /** 供 `@Query` 直接拼接的 writer 提取表达式。 */
    const val WRITER_SQL = "substr(dimKey, $WRITER_SQL_FROM)"

    /** 供 `@Query` 直接拼接的日期提取表达式。 */
    const val DATE_SQL = "substr(dimKey, 1, $DATE_WIDTH)"

    /**
     * 一条统计贡献。**一律正数**，符号由调用方决定（加还是回退）。
     *
     * 不含 `storeKey` / `scope`：那是落库时才确定的上下文，
     * 混进来的话"同一笔贡献搬桶"就得先拆再拼。
     */
    data class Contribution(
        val dimKey: String,
        val metric: String,
        val value: Long,
    )

    /** `B:alice:2026-09` → `"FILE:B:alice:2026-09"`。 */
    fun fileScope(fileId: String): String = "$FILE_SCOPE_PREFIX$fileId"

    /** `"FILE:B:alice:2026-09"` → `"B:alice:2026-09"`；不是文件桶时返回 null。 */
    fun fileIdOfScope(scope: String): String? =
        scope.removePrefix(FILE_SCOPE_PREFIX).takeIf { it != scope }

    /** `"2026-09-15"`。见类注释里关于恒宽与 [Locale.US] 的要求。 */
    fun dateKey(epochMillis: Long, timeZone: TimeZone): String {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = epochMillis
        return "%04d-%02d-%02d".formatUs(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 拼出完整的 dimKey。 */
    fun dayKey(writer: String, epochMillis: Long, timeZone: TimeZone): String =
        "${dateKey(epochMillis, timeZone)}|$WRITER_DIM_PREFIX$writer"

    /**
     * 一笔订单对统计的贡献。**A 类返回空列表。**
     *
     * ## 为什么这里不收 `status` 参数
     *
     * 收了这个参数就意味着"本函数会按状态决定要不要算"，于是调用方在**回退**时
     * 会顺手把**新状态**传进来 —— 取消一笔订单时新状态是 `0`，函数看到 `0` 就不产出贡献，
     * 回退变成了空操作，那笔钱永远留在账上，而且不报错。
     *
     * 正确的用法是：本函数**总是**产出正数贡献，符号由调用方一次性决定：
     * ```
     * sign = +1  下单
     * sign = -1  取消（且必须用**原订单**的 writer/orderTs/total/pieces，不是取消那一刻的）
     * ```
     *
     * ## 为什么 A 类是空
     *
     * A 类是**向丽华金龙进货**，不是本店的收入（[OrderFileId.isRevenueFile]）。
     * 在这里返回空而不是让调用方记得判断，是结构性的防护：忘了判断的结果是"没有贡献行"，
     * 而不是"营业额里混进了进货款"。
     *
     * @param parts fileId 解析结果。既提供 A/B 判据，也提供 [orderTs] 缺失时的月份兜底
     * @param orderTs `content.ts`。为 0（别的客户端没写这个字段）时用 [parts] 的年月兜底，
     *        见 [effectiveTs]
     * @param pieces `Σ items.quantity`
     */
    fun contributionOf(
        parts: OrderFileId.Parts,
        writer: String,
        orderTs: Long,
        timeZone: TimeZone,
        totalCents: Long,
        pieces: Int,
    ): List<Contribution> {
        if (!parts.isB) return emptyList()
        val key = dayKey(writer, effectiveTs(orderTs, parts, timeZone), timeZone)
        return listOf(
            Contribution(key, METRIC_AMOUNT, totalCents),
            Contribution(key, METRIC_ORDERS, 1L),
            Contribution(key, METRIC_PIECES, pieces.toLong()),
        )
    }

    /**
     * `orderTs == 0` 时用**文件所属月份的第一天零点**兜底。
     *
     * 协议本身没有订单时间字段，`content.ts` 是我们自己加的；别的客户端
     * （包括参照实现 `s1client.py`）不写它，FULL 恢复时也就拿不到流消息里的 `timestamp`。
     *
     * 为什么不就用 0：那样这笔钱会落到 `1970-01-01`，**任何真实查询区间都覆盖不到它** ——
     * 营业额静默少一笔，而且少了多少、少在哪天都看不出来。用文件月份兜底的话
     * 月/年统计是对的，只有"哪一天"是猜的（恒为 1 号）。
     *
     * ⚠ 兜底不改变 `order_index.orderTs`（那列仍然存 0），所以"这笔订单的时间是猜的"
     * 这件事永远查得出来。诊断页据此列出所有 `orderTs = 0` 的订单。
     */
    fun effectiveTs(orderTs: Long, parts: OrderFileId.Parts, timeZone: TimeZone): Long {
        if (orderTs != 0L) return orderTs
        val cal = Calendar.getInstance(timeZone)
        cal.clear()
        // A 类没有月份维度（NO_MONTH=0），按 1 月算 —— 反正 A 类不产出贡献，
        // 这条分支只在"将来有人把 A 类也纳入统计"时才会被走到。
        val month = if (parts.month in 1..12) parts.month else 1
        cal.set(parts.year, month - 1, 1, 0, 0, 0)
        return cal.timeInMillis
    }

    /** `String.format` 的 Locale 固定版。见类注释：默认 Locale 可能输出非 ASCII 数字。 */
    private fun String.formatUs(vararg args: Any?): String =
        String.format(Locale.US, this, *args)
}
