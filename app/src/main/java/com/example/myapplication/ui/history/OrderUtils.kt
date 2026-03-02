// 新增文件：OrderUtils.kt
package com.example.myapplication.ui.history

import com.example.myapplication.data.OrderHistoryFile
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

object OrderUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val priceFormat = DecimalFormat("#,##0.00")

    fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    fun formatTime(timestamp: Long): String {
        return timeFormat.format(Date(timestamp))
    }

    fun formatFullDate(timestamp: Long): String {
        return fullDateFormat.format(Date(timestamp))
    }

    fun formatPrice(price: String): String {
        return try {
            "¥${priceFormat.format(price.toBigDecimal())}"
        } catch (e: Exception) {
            "¥0.00"
        }
    }

    fun getMonthName(month: String): String {
        val months = listOf(
            "一月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "十一月", "十二月"
        )
        return months.getOrNull(month.toIntOrNull()?.minus(1) ?: 0) ?: month
    }

    fun getWeekday(date: Date): String {
        val weekdays = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val calendar = Calendar.getInstance().apply { time = date }
        return weekdays[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }

    fun calculateStatistics(orders: List<OrderHistoryFile>): OrderStatistics {
        var totalAmount = BigDecimal.ZERO
        var totalItems = 0

        orders.forEach { order ->
            totalAmount = totalAmount.add(
                order.record.totalPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
            )
            totalItems += order.record.items.sumOf { it.quantity }
        }

        return OrderStatistics(
            orderCount = orders.size,
            totalAmount = totalAmount,
            totalItems = totalItems,
            averageAmount = if (orders.isNotEmpty()) totalAmount.divide(
                BigDecimal(orders.size), 2, BigDecimal.ROUND_HALF_UP
            ) else BigDecimal.ZERO
        )
    }
}

data class OrderStatistics(
    val orderCount: Int,
    val totalAmount: BigDecimal,
    val totalItems: Int,
    val averageAmount: BigDecimal
)