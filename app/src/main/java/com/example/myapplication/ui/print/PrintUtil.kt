package com.example.myapplication.ui.print

import com.example.myapplication.data.OrderItem
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

object PrintUtil {
    // 使用等宽字体，每个中文字符宽度设为2个英文字符宽度
    fun generateReceipt(orderItems: List<OrderItem>, totalPrice: BigDecimal): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = dateFormat.format(Date())

        val receipt = StringBuilder()
        val lineWidth = 70 // 固定宽度，便于对齐

        // 使用 NumberFormat 格式化货币
        val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }

        // 头部信息
        receipt.appendLine(centerText("销售小票", lineWidth))
        receipt.appendLine("=".repeat(lineWidth))
        receipt.appendLine("时间: $date               电话：15288180414")
        receipt.appendLine("地址：官渡区新广丰B区14-16")
        receipt.appendLine("-".repeat(lineWidth))

        // 表头
        receipt.appendLine(formatLine(
            listOf("序号", "条形码", "商品名称", "数量", "单价", "小计"),
            listOf(4, 14, 32, 5, 7, 10)
        ))
        receipt.appendLine("-".repeat(lineWidth))

        // 商品明细
        orderItems.forEachIndexed { index, item ->
            val rowNumber = (index + 1).toString()
            var barcode = item.product.barcode
            var productName = item.product.name
            productName=productName.replace("[\n\r]+".toRegex(),"")
            var quantity = item.quantity.toString()
            val unitPrice = numberFormat.format(item.product.price)
            val subtotal = numberFormat.format(item.subtotal)
            if(barcode[0]=='0'){
                barcode=barcode.substring(1)
                quantity=quantity+"件"
            }
            receipt.appendLine(formatLine(
                listOf(rowNumber, barcode, productName, quantity, unitPrice, subtotal),
                listOf(4, 14, 32, 5, 7, 10)
            ))
        }

        // 总计
        receipt.appendLine("-".repeat(lineWidth))
        receipt.appendLine(
            "总计:".padEnd(lineWidth - 14) +
                    numberFormat.format(totalPrice).padStart(12)
        )
        receipt.appendLine("=".repeat(lineWidth))
        receipt.appendLine(centerText("感谢您的惠顾！", lineWidth))
        receipt.appendLine("=".repeat(lineWidth))

        return receipt.toString()
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