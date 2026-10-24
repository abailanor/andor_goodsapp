package com.example.myapplication.ui.print

import com.example.myapplication.core.money.Money
import com.example.myapplication.data.CartLine
import com.example.myapplication.data.CartPricing
import com.example.myapplication.data.ReceiptMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PrintUtil {

    /** 版面宽度（半角字符数）。热敏纸常见 32 或 48 列，70 是给"一行放得下六列"留的余量。 */
    private const val LINE_WIDTH = 70

    /**
     * 明细表的列宽。**列宽之和 + 2×(列数−1) 必须 ≤ [LINE_WIDTH]**。
     *
     * ⚠ [formatLine] 每列之间还会插两个空格，所以列宽加起来不等于行宽。
     * 原先是 (4,14,32,5,7,10)=72，加分隔符 82 —— 每行都溢出 12 个字符，
     * 热敏纸直接折行，整张小票的对齐全毁。这个约束由 `PrintUtilTest` 盯着。
     *
     * 六列：序号 3 + 条形码 13（EAN-13 正好 13 位）+ 品名 22（约 11 个汉字）
     * + 数量 5 + 单价 7（`1234.50`）+ 金额 10（`123456.78`）= 60，加 10 = 70。
     */
    private val SALE_WIDTHS = listOf(3, 13, 22, 5, 7, 10)

    /** 进货单只有四列：4 + 15 + 37 + 8 = 64，加 3×2 = 70。 */
    private val PURCHASE_WIDTHS = listOf(4, 15, 37, 8)

    /**
     * 生成小票。
     *
     * ## 三列的语义（用户定的）
     *
     * ```
     * 单价 = 单个零售价        数量 = 总个数        金额 = 实际收的钱（含整箱优惠）
     * ```
     *
     * ⚠ **单价 × 数量 ≠ 金额**，这是常态不是 bug：整箱价 ≠ 箱规 × 单价。
     * 箱规 24、整箱 80.00、单价 3.50，买 30 个 → 1 箱(80) + 6 个(21) = **101.00**，
     * 而 30 × 3.50 = 105.00。所以底部必须有「折扣」行给这个差额一个交代，
     * 否则顾客当场按计算器就会发现"算错了"。
     *
     * ## 底部三行的显示规则
     *
     * `折扣`（整箱优惠）与 `抹零`（手工调整）**≤ 0 就不印**（用户定的规则，
     * 与订单详情页共用 [ReceiptMath]，两边必须一致 —— 顾客手里那张纸是要拿去对账的）。
     *
     * @param manualTotalCents 手工改过的总额（分）。null = 没改，合计就是行金额之和。
     * @param purchase 进货单：**不印任何金额**。系统里没有买入价（服务端根本不存储），
     *        本地目录的 price 是卖出价 —— 印出来会被当成成本记账。
     */
    fun generateReceipt(
        lines: List<CartLine>,
        manualTotalCents: Long? = null,
        purchase: Boolean = false,
        shopName: String = "",
        shopPhone: String = "",
        shopAddress: String = "",
        title: String = if (purchase) "进货单" else "销售小票",
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val date = dateFormat.format(Date())

        val receipt = StringBuilder()
        val lineWidth = LINE_WIDTH

        // 头部信息
        receipt.appendLine(centerText(title, lineWidth))
        receipt.appendLine("=".repeat(lineWidth))
        if (shopName.isNotBlank()) receipt.appendLine(shopName)
        receipt.appendLine("时间: $date" + if (shopPhone.isNotBlank()) "        电话：$shopPhone" else "")
        if (shopAddress.isNotBlank()) receipt.appendLine("地址：$shopAddress")
        receipt.appendLine("-".repeat(lineWidth))

        val totals = CartPricing.totals(lines, manualTotalCents)
        // ⚠ 与订单详情页共用同一个 ReceiptMath：屏幕上看到的与打印出来的必须是同一份数字。
        val breakdown = ReceiptMath.of(
            lines.map {
                com.example.myapplication.sync.OrderPayloadIndex.Item(
                    it.product.barcode, it.product.name, it.quantity ?: 0, it.amountCents, it.unitPriceCents,
                )
            },
            totals.total,
        )

        if (purchase) {
            // 进货单：只有品名与件数
            receipt.appendLine(formatLine(listOf("序号", "条形码", "商品名称", "件数"), PURCHASE_WIDTHS))
            receipt.appendLine("-".repeat(lineWidth))
            lines.filterNot { it.isEmpty }.forEachIndexed { index, l ->
                receipt.appendLine(
                    formatLine(
                        listOf(
                            (index + 1).toString(),
                            l.product.barcode,
                            clean(l.product.name),
                            (l.quantity ?: 0).toString(),
                        ),
                        PURCHASE_WIDTHS,
                    )
                )
            }
            receipt.appendLine("-".repeat(lineWidth))
            receipt.appendLine(rightLabel("品种数", lines.count { !it.isEmpty }.toString(), lineWidth))
            receipt.appendLine(rightLabel("总件数", lines.sumOf { it.quantity ?: 0 }.toString(), lineWidth))
        } else {
            receipt.appendLine(
                formatLine(listOf("序号", "条形码", "商品名称", "数量", "单价", "金额"), SALE_WIDTHS)
            )
            receipt.appendLine("-".repeat(lineWidth))
            lines.filterNot { it.isEmpty }.forEachIndexed { index, l ->
                receipt.appendLine(
                    formatLine(
                        listOf(
                            (index + 1).toString(),
                            l.product.barcode,
                            clean(l.product.name),
                            (l.quantity ?: 0).toString(),
                            Money.fromCents(l.unitPriceCents),
                            Money.fromCents(l.amountCents),
                        ),
                        SALE_WIDTHS,
                    )
                )
            }
            receipt.appendLine("-".repeat(lineWidth))
            // 折扣 = Σ(数量×单价) − Σ金额，也就是整箱优惠的合计。
            // 不印的话"单价×数量"与"金额"这两列在顾客眼里就是自相矛盾的。
            if (breakdown.showDiscount) {
                receipt.appendLine(rightLabel("折扣（整箱优惠）", "-" + Money.fromCents(breakdown.discount!!), lineWidth))
            }
            receipt.appendLine(rightLabel("商品金额", Money.fromCents(breakdown.amountSum), lineWidth))
            if (breakdown.showRounding) {
                receipt.appendLine(rightLabel("抹零", "-" + Money.fromCents(breakdown.rounding), lineWidth))
            }
            receipt.appendLine(rightLabel("合计", Money.fromCents(totals.total), lineWidth))
            if (totals.manualTotal && !breakdown.showRounding) {
                // ⚠ 手工把总额**调高**时 rounding 是负的、按规则不显示，
                // 那样"商品金额"与"合计"就对不上而没有任何解释。这一行兜住它。
                receipt.appendLine(rightLabel("（含手工调整）", "", lineWidth))
            }
        }

        receipt.appendLine("=".repeat(lineWidth))
        receipt.appendLine(centerText(if (purchase) "请核对品名与数量" else "感谢您的惠顾！", lineWidth))
        receipt.appendLine("=".repeat(lineWidth))

        return receipt.toString()
    }

    /** 商品名里的换行会把一行劈成两行，整张小票的对齐就全毁了。 */
    private fun clean(name: String): String = name.replace("[\n\r]+".toRegex(), "")

    /** 右对齐的"label: value"行。value 为空时只印 label（用于「（含手工调整）」这种纯提示）。 */
    private fun rightLabel(label: String, value: String, lineWidth: Int): String {
        val text = if (value.isEmpty()) label else "$label: $value"
        val pad = (lineWidth - getDisplayWidth(text)).coerceAtLeast(0)
        return " ".repeat(pad) + text
    }

    // 居中对齐函数
    private fun centerText(text: String, width: Int): String {
        val padding = width - getDisplayWidth(text)
        if (padding <= 0) return text
        val leftPadding = padding / 2
        val rightPadding = padding - leftPadding
        return " ".repeat(leftPadding) + text + " ".repeat(rightPadding)
    }

    // 格式化行数据
    private fun formatLine(columns: List<String>, widths: List<Int>): String {
        val result = StringBuilder()
        columns.forEachIndexed { index, column ->
            val width = widths[index]
            val displayWidth = getDisplayWidth(column)

            if (displayWidth <= width) {
                // 如果内容宽度小于等于列宽，居中显示
                val padding = width - displayWidth
                val leftPadding = padding / 2
                val rightPadding = padding - leftPadding
                result.append(" ".repeat(leftPadding))
                    .append(column)
                    .append(" ".repeat(rightPadding))
            } else {
                // 如果内容宽度超过列宽，截断并添加省略号
                val truncated = truncateText(column, width)
                result.append(truncated)
            }
            // 列间添加一个空格
            if (index < columns.size - 1) {
                result.append("  ")
            }
        }
        return result.toString()
    }

    // 获取显示宽度（中文字符宽度为2，英文字符宽度为1）
    private fun getDisplayWidth(text: String): Int {
        return text.sumOf { char ->
            (if (char.code in 0x4E00..0x9FFF ||
                char.code in 0x3400..0x4DBF ||
                char.code in 0x20000..0x2A6DF) {
                2 // 中文字符
            } else {
                1 // 英文字符和数字
            }).toInt()
        }
    }

    // 截断文本以适应宽度
    private fun truncateText(text: String, maxWidth: Int): String {
        var currentWidth = 0
        var result = StringBuilder()

        for (char in text) {
            val charWidth = if (char.code in 0x4E00..0x9FFF ||
                char.code in 0x3400..0x4DBF ||
                char.code in 0x20000..0x2A6DF) 2 else 1

            if (currentWidth + charWidth > maxWidth) {
                if (currentWidth + 2 <= maxWidth) {
                    result.append("..")
                } else if (currentWidth + 1 <= maxWidth) {
                    result.append(".")
                }
                return result.toString()
            }
            result.append(char)
            currentWidth += charWidth
        }
        return result.toString()
    }
}