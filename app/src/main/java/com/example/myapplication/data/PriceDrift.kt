package com.example.myapplication.data

import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.sync.OrderPayloadIndex

/**
 * 一条价格漂移：某个条码**开单时**的单价与**现在**的单价不一致。
 */
data class DriftLine(
    val barcode: String,
    val name: String,
    val quantity: Int,
    val unitPriceThen: Long,
    val unitPriceNow: Long,
) {
    /** 正数 = 涨价了，负数 = 降价了。 */
    val diff: Long get() = unitPriceNow - unitPriceThen
}

/**
 * 一笔未上传订单的价格漂移报告。
 *
 * @param gone 开单时有价、现在**查不到**的条码（商品被删了，或从没同步到本机）。
 *        这些行无法比对，所以不算漂移，但 UI 值得一并说明。
 * @param unknownThen payload 里没有 `unitPrice` 的行数（早期写入的订单）。
 *        ⚠ 这些行**无法**参与判定 —— 不能拿当前目录价当"开单价"，
 *        那等于把"没涨价"和"涨了但我不知道"混为一谈。
 */
data class DriftedOrder(
    val orderUuid: String,
    val groupId: String,
    val writer: String,
    val orderTs: Long,
    /** 挂起时算价的时刻。UI 显示"这单是 X 天前按旧价开的"。 */
    val pricedAt: Long,
    val totalCents: Long,
    val lines: List<DriftLine>,
    val gone: List<String>,
    val unknownThen: Int,
) {
    /** 涨了的行数。降价与涨价要分开说：用户对待它们的态度完全不同。 */
    val increased: Int get() = lines.count { it.diff > 0 }
    val decreased: Int get() = lines.count { it.diff < 0 }
}

/**
 * 价格漂移**检测**。
 *
 * ## 为什么只有检测，没有"自动重算"
 *
 * 有 4 处注释指向一个叫 `OutboxStore.reprice` 的方法，而它**不该存在**：
 *
 * 用新价重算一笔订单，需要知道每行是"几箱 + 几个"——因为整箱价 ≠ 箱规 × 单价，
 * 只给 `quantity`（个数）与新单价是**推不出**新金额的（30 个可以是 1 箱 + 6 个，
 * 也可以是 30 个散卖，两者的钱差好几块）。而箱/个拆分**故意没有存进 payload**
 * （见 `OrderLine.quantity` 的注释：那只是开单页的录入便利）。
 *
 * 所以重算只能由**还持有购物车的那一层**（开单页）给出新的 draft，
 * 而"删旧行 + 插新行 + 减旧贡献 + 重算受影响的组"这套事务
 * **已经是** [com.example.myapplication.sync.outbox.OutboxStore.enqueue] 的 `replacing` 参数。
 * 再加一个 `reprice` 方法只会有两处能搬桶，而它们各自的幂等判据不同 ——
 * 结果要么双算要么漏算，两种都静默。
 *
 * ## ⚠ 检测出来之后**不要自动改价**
 *
 * 顾客已经按旧价付过钱了。把未上传的订单悄悄改成新价，等于事后涨价 ——
 * 而营业额会与收银台里的现金对不上，且对不上的原因藏在一次自动重算里。
 * 这个类只负责**告诉用户**，改不改由用户决定（原价上传 / 用新价重算 / 删掉）。
 *
 * ## ⚠ 与 `AppSettings.rescanPricesOnLoadPending` 是两套机制
 *
 * 那个作用于**待开单**（还没成交，按现价重扫是对的），这个作用于**已成交未上传**。
 * 合并它们会让"重扫价"悄悄改掉一张已经收了钱的单。
 */
class PriceDrift(
    private val dao: OrderFileDao,
    private val storeKey: () -> String,
    /**
     * 批量取当前单价（分）。**返回的 map 里缺某个条码 = 查不到**（已删或本机没有）。
     *
     * 做成批量而不是逐条：一张单可能有几十行，逐条查就是几十次数据库往返。
     */
    private val currentUnitPrices: suspend (List<String>) -> Map<String, Long>,
) {

    /**
     * 扫描全部未上传订单，返回**有漂移**的那些。
     *
     * 草稿（`DRAFT`）不扫：它连"成交了吗"都没确定，谈不上"按旧价开了单"。
     */
    suspend fun scan(): List<DriftedOrder> {
        val key = storeKey()
        val rows = dao.outboxPending(key).filter { it.state != OrderOutboxEntity.DRAFT }
        if (rows.isEmpty()) return emptyList()

        // 先解析一遍收集条码，再一次性取价：逐条查会让 N 行变成 N 次往返
        val parsed = rows.mapNotNull { row -> OrderPayloadIndex.parse(row.payload)?.let { row to it } }
        val barcodes = parsed.flatMap { (_, p) -> p.items.map { it.barcode } }.distinct()
        if (barcodes.isEmpty()) return emptyList()
        val prices = currentUnitPrices(barcodes)

        return parsed.mapNotNull { (row, p) -> driftOf(row, p, prices) }
    }

    /** 单笔。null = 没有漂移（或这一行不存在/解析不出来）。 */
    suspend fun scanOne(orderUuid: String): DriftedOrder? {
        val row = dao.outboxRow(storeKey(), orderUuid) ?: return null
        val p = OrderPayloadIndex.parse(row.payload) ?: return null
        val prices = currentUnitPrices(p.items.map { it.barcode }.distinct())
        return driftOf(row, p, prices)
    }

    companion object {

        /**
         * 判定一笔订单有没有漂移。**纯函数**，所以那些"把降价报成涨价""拿现价当开单价"
         * 的错误能被直接测到。
         *
         * @return null = 没有需要用户处理的漂移。
         */
        fun driftOf(
            row: OrderOutboxEntity,
            parsed: OrderPayloadIndex.Parsed,
            pricesNow: Map<String, Long>,
        ): DriftedOrder? {
            val lines = ArrayList<DriftLine>()
            val gone = ArrayList<String>()
            var unknownThen = 0

            for (it in parsed.items) {
                val then = it.unitPriceCents
                if (then == null) {
                    // ⚠ 不能拿 pricesNow 当"开单价"来比：那等于假设它没涨过，
                    // 而"没涨"与"涨了我不知道"在结果上无法区分。记下来让 UI 说明。
                    unknownThen++
                    continue
                }
                val now = pricesNow[it.barcode]
                if (now == null) {
                    gone += it.barcode
                    continue
                }
                if (now != then) {
                    lines += DriftLine(it.barcode, it.name, it.quantity, then, now)
                }
            }

            // 只有**真的检测到价格变化**才算漂移。商品被删（gone）与老订单缺字段
            // （unknownThen）都只是附带说明，单独拿它们去打扰用户是噪音 ——
            // 那张单按原价上传是完全正确的。
            if (lines.isEmpty()) return null

            return DriftedOrder(
                orderUuid = row.orderUuid,
                groupId = row.groupId,
                writer = row.writer,
                orderTs = row.orderTs,
                pricedAt = row.pricedAt,
                totalCents = row.totalCents,
                lines = lines,
                gone = gone,
                unknownThen = unknownThen,
            )
        }
    }
}
