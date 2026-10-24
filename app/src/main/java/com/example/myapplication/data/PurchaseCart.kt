package com.example.myapplication.data

import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.OrderGroupAssembler
import com.example.myapplication.sync.outbox.OrderLine

/**
 * **进货**单的出库路径。与 [CartPricing] 并列，不是它的一个开关。
 *
 * ## 为什么不复用 [CartPricing]
 *
 * 用户的原话是"你还是搞了计算价格那一系列操作，没有自己写新的接口"。那句话是对的，
 * 而且理由不止是洁癖：
 *
 *  1. **服务端根本不存储买入价**（README §6.2「目录不含进货价」）。本地目录里的
 *     `price` 是**卖出价**。用卖出价给进货行标金额，等于把一个编出来的数字写进订单。
 *  2. 那个数字会落在 `content` 里，而 `content` 是变长字段 ——
 *     **上传后永远改不了**（补丁只能等长覆写 `status` 那一个字节）。
 *     半年后没人能分清镜像里那个 `amount` 是进价还是售价。
 *  3. 复用同一个函数的话，"进货不要算钱"就只是一条**约定**：
 *     调用点忘了传 `priced = false`，编译照样过、测试照样绿，
 *     而账上多出一笔根本不该存在的进货金额。
 *
 * 所以这里做成**类型上不可能带价**：[Line] 没有 amount/unitPrice 字段，
 * [toRequest] 也不接受 `manualTotalCents` 参数。想给进货单算钱，得先改类型。
 *
 * ## 进货的最小单位仍然是"个"
 *
 * [Line.quantity] 是**个数**，与服务端 `extractItems` 的语义一致。
 * 开单页在进货模式下只显示「个」输入框（整箱框由
 * `enableBoxSelling && mode == SALE && boxPrice != null` 三个条件共同把关），
 * 所以正常情况下 `boxes == 0`；[linesOf] 仍然按 `boxes × boxSize + singles` 算，
 * 是为了兼容从旧待开单文件反推出来的行（那时只有总个数）。
 */
object PurchaseCart {

    /** 进货单里的一行。**没有价格字段** —— 这是本文件存在的唯一理由。 */
    data class Line(
        val barcode: String,
        val name: String,
        /** 个数。恒为正（空行在 [linesOf] 里就被丢掉了）。 */
        val quantity: Int,
    )

    /**
     * 把购物车转成进货行。
     *
     * @return null 表示**有行算不出个数**（缺箱规）。与销售侧同一条规矩：
     *         不能拿一个不完整的列表去下单，否则用户会看到"这一单 3 行只上了 2 行"。
     */
    fun linesOf(cart: List<CartLine>): List<Line>? {
        val out = ArrayList<Line>(cart.size)
        for (l in cart) {
            // 两个框都空 = 编辑中间态，不是"数量为 0 的商品"。
            // ⚠ 绝不能让它变成一条 `quantity = 0` 的行：服务端要求正整数，0 会拿 400。
            if (l.isEmpty) continue
            val qty = l.quantity ?: return null
            out += Line(barcode = l.product.barcode, name = l.product.name, quantity = qty)
        }
        return out
    }

    /**
     * 拼出交给 [com.example.myapplication.sync.outbox.OutboxStore.enqueue] 的请求。
     *
     * @param year 当前年份。**没有 month 参数** —— A 类文件不按月份分片，
     *        传 0 会撞服务端 `validateTypeAndMonth` 的 `month<1||month>12` 拿 400。
     * @param remarkText 备注原文（未 padding，可以超长、可以含引号）。
     *        ⚠ 无论填不填，`OrderGroupAssembler` 都会给 A 类**强制**生成 150 字节的 remark ——
     *        补丁是等长覆写，下单时没带就永远插不进去。
     * @return null 表示有行算不出个数。
     */
    fun toRequest(
        cart: List<CartLine>,
        writer: String,
        ts: Long,
        year: Int,
        remarkText: String?,
    ): OrderGroupAssembler.Request? {
        val lines = linesOf(cart) ?: return null
        if (lines.isEmpty()) return null
        return OrderGroupAssembler.Request(
            fileType = OrderFileId.TYPE_A,
            year = year,
            month = null,
            writer = writer,
            // amount / unitPrice 一律 null：见类注释第 1 条。
            lines = lines.map {
                OrderLine(
                    barcode = it.barcode,
                    name = it.name,
                    quantity = it.quantity,
                    amount = null,
                    unitPrice = null,
                )
            },
            ts = ts,
            totalOverrideCents = null,
            remarkText = remarkText,
        )
    }
}
