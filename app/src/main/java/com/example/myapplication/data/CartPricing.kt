package com.example.myapplication.data

import com.example.myapplication.core.money.Money
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.OrderGroupAssembler
import com.example.myapplication.sync.outbox.OrderLine

/**
 * 购物车里的一行。
 *
 * ## 为什么要有"箱 / 个"两个数
 *
 * 旧的 `OrderItem` 只有 `product + quantity`，`subtotal = price × quantity` —— 完全没有整箱概念。
 * 而整箱价 **≠** 箱规 × 单价，所以按个数乘单价会多收钱：
 * 箱规 24、整箱 80.00、单价 3.50，买 30 个 → 1 箱(80.00) + 6 个(21.00) = **101.00**，
 * 而 30 × 3.50 = 105.00。
 *
 * 拆成 `boxes` 与 `singles` 之后两个量各走各的公式：
 * ```
 * amount   = boxes × boxPrice + singles × unitPrice     ← 不需要箱规
 * quantity = boxes × boxSize  + singles                 ← **需要**箱规
 * ```
 *
 * ⚠ 这两条的依赖不同，正是 `needsBoxSize` 那个坑的来源：
 * 缺箱规时钱**算得出来**、个数**算不出来**，而 `quantity` 是协议的必填正整数。
 * 所以那种行不能上传（见 [CartPricing.missingBoxSize]）。
 *
 * ## 上传的 payload 里只有 `quantity`（个数）
 *
 * 箱/个的拆分**不存进 payload** —— 服务端 `quantity` 的语义就是"个数，直接用于扣库存"。
 * 代价是事后无法从 payload 还原"几箱几个"，所以小票上也不显示拆分（用户已确认）。
 * 折扣行靠 `unitPrice` 反推，不需要箱数。
 *
 * @param overrideUnitPriceCents 手动改过的**单个零售价**（分）。null = 用目录价。
 *        ⚠ 它只影响散卖那部分；整箱价仍然取目录的 `boxPrice` ——
 *        "改单价"改的是零售单价，不是把整箱价也跟着换算。
 */
data class CartLine(
    val product: Product,
    val boxes: Int = 0,
    val singles: Int = 0,
    val overrideUnitPriceCents: Long? = null,
) {

    init {
        require(boxes >= 0) { "箱数不能为负：$boxes" }
        require(singles >= 0) { "散卖个数不能为负：$singles" }
        require(boxes > 0 || singles > 0) { "一行至少要有一个（箱或个）" }
    }

    /** 单个零售价（分）。 */
    val unitPriceCents: Long =
        overrideUnitPriceCents ?: Money.toCents(product.price.toPlainString())

    /** 整箱价（分）。null = 这个商品不按整箱卖。 */
    val boxPriceCents: Long? = product.boxPrice?.let { Money.toCents(it.toPlainString()) }

    /**
     * 缺箱规但填了整箱数。
     *
     * 这一行**能算钱、不能算个数**，所以不能上传。开单时要么弹箱规表单当场补，
     * 要么整单挂进 outbox 标 `BLOCKED_MISSING_BOX_SIZE`。
     */
    val needsBoxSize: Boolean get() = boxes > 0 && product.boxSize <= 0

    /**
     * 该行金额（分）。**永远算得出来**（不依赖箱规）。
     *
     * 没有整箱价时（`boxPriceCents == null`）整箱数按单价计 —— 用户填了箱数但商品
     * 不按整箱卖，那"一箱"就等于 boxSize 个散卖；而 boxSize 也没有，所以退化成
     * `boxes × unitPrice`，这**一定**是错的，因此 [needsBoxSize] 那一侧会拦住它。
     */
    val amountCents: Long
        get() {
            val boxPart = boxes.toLong() * (boxPriceCents ?: unitPriceCents)
            return boxPart + singles.toLong() * unitPriceCents
        }

    /**
     * 个数。**null = 算不出来**（缺箱规且填了整箱数）。
     *
     * ⚠ 不要用 0 或"猜一个"代替 null：`quantity` 是服务端校验的正整数，
     * 猜出来的值会**扣错库存**，而且镜像里那一行看起来完全正常。
     */
    val quantity: Int?
        get() = if (needsBoxSize) null else boxes * product.boxSize + singles

    /**
     * 按当前单价、全部散卖算出来的钱 = `quantity × unitPrice`。
     *
     * 它与 [amountCents] 的差就是**整箱优惠**（小票底部那一行）。
     * [quantity] 为 null 时也是 null。
     */
    val listPriceCents: Long?
        get() = quantity?.toLong()?.times(unitPriceCents)
}

/**
 * 购物车的算钱规则。**纯函数**，全部用 `Long` 分做算术。
 *
 * ## 为什么单独抽一层
 *
 * 这几个数要在**四个地方**保持一致：结算对话框、小票、订单详情页、以及上传前的一致性自检。
 * 各写一遍的话迟早会出现"结算框显示 101.00、小票印出 105.00"，
 * 而顾客手里那张纸是要拿去对账的。
 *
 * ## ⚠ 一律用 Long 分，不用 BigDecimal 也不用 Double
 *
 * `Product.price` 是 BigDecimal，进这一层之前先用 [Money.toCents] 转成分。
 * 金额过 Double 有精度问题；一路 BigDecimal 则要每步都记得 setScale，
 * 漏一次就是 0.005 的偏差，而那种偏差在几百行累加之后会变成看得见的差额。
 */
object CartPricing {

    /**
     * 一整车算完的结果。
     *
     * ```
     * listPriceSum − discount = amountSum
     * amountSum    − rounding = total
     * ```
     * 所以小票上"单价×数量"的合计、折扣、抹零、总额四者是首尾相接的，
     * 顾客自己按计算器也能对上。
     */
    data class Totals(
        /** `Σ(quantity × unitPrice)`。有任何一行算不出 quantity 时为 null。 */
        val listPriceSum: Long?,
        /** `Σ 行金额`。 */
        val amountSum: Long,
        /** 权威总额：[manualTotalCents] 非 null 时用它，否则等于 [amountSum]。 */
        val total: Long,
    ) {
        /** 整箱优惠。null = 算不出来（有行缺箱规或缺 unitPrice）。 */
        val discount: Long? get() = listPriceSum?.let { it - amountSum }

        /** 手工调整 / 抹零。 */
        val rounding: Long get() = amountSum - total

        /** 与 [ReceiptMath.Breakdown.showDiscount] 同一条规则：≤ 0 不显示。 */
        val showDiscount: Boolean get() = (discount ?: 0L) > 0L
        val showRounding: Boolean get() = rounding > 0L

        /** 总额是不是被手动覆盖过。UI 要打"含手工调整"标记。 */
        val manualTotal: Boolean get() = total != amountSum
    }

    /**
     * @param manualTotalCents 用户在结算框里手改的总额（分）。null = 用 `Σ 行金额`。
     */
    fun totals(lines: List<CartLine>, manualTotalCents: Long? = null): Totals {
        var list: Long? = 0L
        var amount = 0L
        for (l in lines) {
            amount += l.amountCents
            val lp = l.listPriceCents
            // 全有或全无：任何一行算不出来，整车的折扣就不显示。
            // 只累加算得出的那几行会得到一个**偏小**的折扣，
            // 而"单价×数量"与"合计"仍然对不上 —— 那是一张自相矛盾的小票。
            list = if (lp == null || list == null) null else list + lp
        }
        return Totals(list, amount, manualTotalCents ?: amount)
    }

    /**
     * 缺箱规的条码：`boxes > 0 且 boxSize == 0`。
     *
     * 非空 → 整单挂进 outbox 标 `BLOCKED_MISSING_BOX_SIZE`（[OutboxStore.enqueue] 的
     * `missingBoxSizeBarcodes` 参数）。
     *
     * ⚠ **整单挂起，不是只挂那一行**：逐行判断的话用户会看到"这一单 3 块里上传了 2 块"——
     * 那是半张销售单，库存只扣三分之二、统计只算三分之二，而服务器上的订单组看起来完整。
     *
     * ⚠ 补箱规需要 `PRICE_CHANGE` 权限，而 B 级员工默认只有 `SYNC_SUB + STOCK_CHANGE`
     * —— 他补不了，只能挂起等店主。UI 必须把这一点写出来，否则员工会当成 bug 报。
     */
    fun missingBoxSize(lines: List<CartLine>): List<String> =
        lines.filter { it.needsBoxSize }.map { it.product.barcode }.distinct()

    /**
     * 转成上传用的 [OrderLine]。
     *
     * @return null 表示**有行算不出 quantity**（缺箱规）。调用方必须先处理
     *         [missingBoxSize]，不能拿一个不完整的列表去上传。
     */
    fun toOrderLines(lines: List<CartLine>): List<OrderLine>? {
        val out = ArrayList<OrderLine>(lines.size)
        for (l in lines) {
            val qty = l.quantity ?: return null
            out += OrderLine(
                barcode = l.product.barcode,
                // 商品名**必须带**：商品被删之后，镜像里的历史订单就只剩一串条码，
                // 统计与历史页都无法还原。服务端只读 barcode 与 quantity，多余字段原样落盘。
                name = l.product.name,
                quantity = qty,
                amount = Money.fromCents(l.amountCents),
                // 每行都写。缺了它，小票的折扣行与价格漂移检测都做不了。
                unitPrice = Money.fromCents(l.unitPriceCents),
            )
        }
        return out
    }

    /**
     * 拼出交给 [com.example.myapplication.sync.outbox.OutboxStore.enqueue] 的请求。
     *
     * @param writer 订单的**归属人**。默认是当前登录用户名，但可以是别的店员 ——
     *        现实中存在"某店员的账号永远不再登录，但他开的单仍需上传"的情况，
     *        而服务端**不校验** `writer` 与登录身份是否一致（只验非空白），所以代传是合法的。
     *        ⚠ 归属人只能在下单这一刻定对：服务端没有任何接口能改已上传订单的 `writer`。
     * @param fileType [OrderFileId.TYPE_B] 销售 / [OrderFileId.TYPE_A] 进货。
     * @param month B 类必须 1..12；**A 类必须传 null**（传 0 会撞服务端月份校验拿 400）。
     * @return null 表示有行算不出 quantity。
     */
    fun toRequest(
        lines: List<CartLine>,
        writer: String,
        ts: Long,
        fileType: String,
        year: Int,
        month: Int?,
        manualTotalCents: Long? = null,
        remarkText: String? = null,
    ): OrderGroupAssembler.Request? {
        val orderLines = toOrderLines(lines) ?: return null
        return OrderGroupAssembler.Request(
            fileType = fileType,
            year = year,
            month = month,
            writer = writer,
            lines = orderLines,
            ts = ts,
            totalOverrideCents = manualTotalCents,
            remarkText = remarkText,
        )
    }
}
