package com.example.myapplication.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class OrderHistoryLine(
    val barcode: String,
    val name: String,
    val price: String, // 用字符串避免BigDecimal序列化坑
    val quantity: Int,
    val subtotal: String
)

data class OrderHistoryRecord(
    val id: String,
    val createdAt: Long,
    val totalPrice: String,
    val items: List<OrderHistoryLine>
)

data class OrderHistoryFile(
    val file: File,
    val record: OrderHistoryRecord
)

class OrderHistoryStorage(private val context: Context) {
    private val gson = Gson()

    private fun baseDir(): File {
        val dir = File(context.filesDir, "Orders")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun dayDir(date: Date): File {
        val y = SimpleDateFormat("yyyy", Locale.getDefault()).format(date)
        val m = SimpleDateFormat("MM", Locale.getDefault()).format(date)
        val d = SimpleDateFormat("dd", Locale.getDefault()).format(date)
        val dir = File(File(File(baseDir(), y), m), d)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveNewOrder(orderItems: List<OrderItem>, totalPrice: String): OrderHistoryFile {
        val now = Date()
        val id = UUID.randomUUID().toString().replace("-", "")
        val lines = orderItems.map {
            OrderHistoryLine(
                barcode = it.product.barcode,
                name = it.product.name,
                price = it.product.price.toPlainString(),
                quantity = it.quantity,
                subtotal = it.subtotal.toPlainString()
            )
        }
        val record = OrderHistoryRecord(
            id = id,
            createdAt = now.time,
            totalPrice = totalPrice,
            items = lines
        )

        val fileName = "order-${SimpleDateFormat("HHmmss", Locale.getDefault()).format(now)}-${id}.json"
        val file = File(dayDir(now), fileName)
        file.writeText(gson.toJson(record))
        return OrderHistoryFile(file = file, record = record)
    }

    fun listAll(): List<OrderHistoryFile> {
        val dir = baseDir()
        if (!dir.exists()) return emptyList()

        val files = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".json") }
            .toList()

        val type = object : TypeToken<OrderHistoryRecord>() {}.type
        val list = mutableListOf<OrderHistoryFile>()
        for (f in files) {
            try {
                val record: OrderHistoryRecord = gson.fromJson(f.readText(), type)
                list.add(OrderHistoryFile(file = f, record = record))
            } catch (_: Exception) {
            }
        }

        return list.sortedByDescending { it.record.createdAt }
    }

    fun update(file: File, record: OrderHistoryRecord) {
        // 如果日期变了：移动到新日期目录
        val date = Date(record.createdAt)
        val targetDir = dayDir(date)
        val targetFile = File(targetDir, file.name)
        targetFile.writeText(gson.toJson(record))
        if (targetFile.absolutePath != file.absolutePath && file.exists()) {
            file.delete()
        }
    }

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }

    // ========== 日期查询相关方法 ==========

    /**
     * 获取所有有订单的年份列表（懒加载，按降序排列）
     */
    fun getYearsWithOrders(): List<String> {
        val dir = baseDir()
        if (!dir.exists()) return emptyList()

        val years = mutableSetOf<String>()
        dir.listFiles()?.forEach { yearDir ->
            if (yearDir.isDirectory && yearDir.name.matches(Regex("\\d{4}"))) {
                years.add(yearDir.name)
            }
        }
        return years.sortedDescending()
    }

    /**
     * 获取指定年份有订单的月份列表（懒加载，按降序排列）
     */
    fun getMonthsWithOrders(year: String): List<String> {
        val yearDir = File(baseDir(), year)
        if (!yearDir.exists()) return emptyList()

        val months = mutableSetOf<String>()
        yearDir.listFiles()?.forEach { monthDir ->
            if (monthDir.isDirectory && monthDir.name.matches(Regex("\\d{2}"))) {
                months.add(monthDir.name)
            }
        }
        return months.sortedDescending()
    }

    /**
     * 获取指定年月的日期列表（懒加载，返回每天的订单数量）
     */
    fun getDaysWithOrders(year: String, month: String): List<Pair<String, Int>> {
        val monthDir = File(File(baseDir(), year), month)
        if (!monthDir.exists()) return emptyList()

        val days = mutableMapOf<String, Int>()
        monthDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { file ->
            try {
                val type = object : TypeToken<OrderHistoryRecord>() {}.type
                val record: OrderHistoryRecord = gson.fromJson(file.readText(), type)
                val day = SimpleDateFormat("dd", Locale.getDefault()).format(Date(record.createdAt))
                days[day] = days.getOrDefault(day, 0) + 1
            } catch (_: Exception) {
            }
        }
        return days.map { (day, count) -> day to count }.sortedBy { it.first }
    }

    /**
     * 获取最近N天有订单的日期列表
     */
    fun getRecentDaysWithOrders(days: Int): List<String> {
        val allOrders = listAll()
        if (allOrders.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val recentDays = mutableSetOf<String>()

        // 收集最近days天的日期
        for (i in 0 until days) {
            val targetTime = now - (oneDayMillis * i)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            recentDays.add(dateFormat.format(Date(targetTime)))
        }

        // 筛选出有订单的日期
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return allOrders
            .filter { order ->
                val orderDate = dateFormat.format(Date(order.record.createdAt))
                recentDays.contains(orderDate)
            }
            .map { order ->
                dateFormat.format(Date(order.record.createdAt))
            }
            .distinct()
            .sortedDescending()
    }

    /**
     * 根据日期获取订单列表
     */
    fun getOrdersByDate(date: String): List<OrderHistoryFile> {
        val allOrders = listAll()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        return allOrders.filter { order ->
            val orderDate = dateFormat.format(Date(order.record.createdAt))
            orderDate == date
        }
    }

    /**
     * 获取指定日期的订单数量
     */
    fun getOrderCountByDate(date: String): Int {
        return getOrdersByDate(date).size
    }
}

