package com.example.myapplication.data

import com.example.myapplication.sync.OrderPayloadIndex

/**
 * 小票（与订单详情页）上的金额分解。
 *
 * ## 为什么需要它
 *
 * 整箱价 ≠ 箱规 × 单价，所以**单价 × 数量 ≠ 该行金额**：
 * 箱规 24、整箱 80.00、单价 3.50，买 30 个 → 1 箱(80.00) + 6 个(21.00) = 101.00，
 * 而 30 × 3.50 = 105.00。小票上把这三个数并排印出来，顾客会当场发现"算错了"。
 *
 * 差额必须在票面上有个交代，所以底部加两行：
 * ```
 * 折扣（整箱优惠） = Σ(数量 × 单价) − Σ行金额
 * 抹零（手工调整） = Σ行金额 − 合计
 * ```
 *
 * ## ⚠ 为什么抽成一个纯函数、放在两处共用的地方
 *
 * 打印小票与查看历史订单是**两个入口、同一份数据**。各写一遍的话，
 * 迟早会出现"打印出来的折扣与屏幕上看到的不一樣"—— 而顾客手里那张纸是要拿去对账的。
 *
 * ## ⚠ 一律用订单里**记录的**单价，绝不查当前目录价
 *
 * `items` 来自 [OrderPayloadIndex.parse]，也就是镜像文件里的字节。
 * 拿当前目录价重算的话，一张历史小票的金额会随着后来越改越不对，
 * 而顾客手里那张纸是不变的 —— 这种"账对不上"最难查，因为两边各自看都是对的。
 *
 * （唯一会按现价重扫的是 `AppSettings.rescanPricesOnLoadPending`，
 * 那作用于**待开单**：还没成交，重定价是对的。两者是不同机制，不要合并。）
 */
object ReceiptMath {

    /**
     * 一张订单的金额分解。
     *
     * @param listPriceSum `Σ(数量 × 单价)`。**null = 算不出来**（有行缺 `unitPrice`）。
     * @param amountSum    `Σ 行金额`。
     * @param total        权威总额（`content.total`，可能被手工覆盖过）。
     */
    data class Breakdown(
        val listPriceSum: Long?,
        val amountSum: Long,
        val total: Long,
    ) {

        /**
         * 整箱优惠。**null = 无法计算**，此时不显示这一行。
         *
         * ⚠ 缺 `unitPrice` 的订单（早期写入镜像的）走这里。
         * **绝不能**只累加有单价的那几行：那样算出的折扣偏小，
         * 而票面上"单价×数量"与"合计"仍然对不上，用户看到的是一张自相矛盾的小票。
         * 宁可整行不显示。
         */
        val discount: Long? get() = listPriceSum?.let { it - amountSum }

        /** 手工调整 / 抹零。 */
        val rounding: Long get() = amountSum - total

        /**
         * 用户定的规则：**≤ 0 就不显示这一项**，打印与屏幕两边一致。
         *
         * 负的 discount 意味着行金额之和**大于**按目录单价算出来的钱（加价卖？录错价？），
         * 那不是"优惠"，印成"折扣 -4.00"只会让人更糊涂。
         */
        val showDiscount: Boolean get() = (discount ?: 0L) > 0L

        /**
         * ⚠ 负的 rounding 意味着总额被手工**调高**了（不是抹零）。
         * 按同一条规则它也不显示 —— 但那时 `Σ行金额 ≠ 合计` 这件事就没有任何解释了。
         * 兜底靠 [OrderPayloadIndex.Parsed.manualTotal] 那个"含手工调整"标记，
         * 小票与详情页都要把它印/显示出来。
         */
        val showRounding: Boolean get() = rounding > 0L

        /**
         * 票面自洽性检查：`Σ(数量 × 单价) − 折扣 − 抹零 == 合计`。
         *
         * 它就是定义式移项（`discount = listPriceSum − amountSum`、`rounding = amountSum − total`），
         * 所以恒成立 —— 它不是运行时校验，而是给读代码的人一个
         * "票面上这几行确实首尾相接"的确认。
         *
         * ⚠ 起点必须是 [listPriceSum] 而不是 [amountSum]：
         * 折扣是"按目录单价算出来的钱"与"实际行金额"的差，
         * 从 amountSum 起算等于把折扣减了两遍。
         *
         * `discount == null`（有行缺单价）时返回 false —— 那时这条恒等式根本不成立，
         * 因为没有 listPriceSum 可减。
         */
        val balances: Boolean
            get() = listPriceSum != null && discount?.let { listPriceSum - it - rounding == total } == true
    }

    /**
     * @param items      从 payload 解析出的行。**不要**传当前目录价重算过的东西。
     * @param totalCents 权威总额（分）。
     */
    fun of(items: List<OrderPayloadIndex.Item>, totalCents: Long): Breakdown {
        // 全有或全无：任何一行缺 unitPrice，整张单的折扣就不显示。
        var listSum: Long? = 0L
        var amountSum = 0L
        for (it in items) {
            amountSum += it.amountCents
            val unit = it.unitPriceCents
            if (unit == null) {
                listSum = null
            } else if (listSum != null) {
                listSum += unit * it.quantity
            }
        }
        return Breakdown(listSum, amountSum, totalCents)
    }
}
