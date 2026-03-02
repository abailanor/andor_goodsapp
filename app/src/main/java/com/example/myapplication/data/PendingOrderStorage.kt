package com.example.myapplication.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待开单存储类
 * 用于保存待处理的订单到待开单文件夹
 */
data class PendingOrderLine(
    val barcode: String,
    val name: String,
    val price: String,
    val quantity: Int,
    val subtotal: String
)

data class PendingOrderRecord(
    val createdAt: Long,
    val fileName: String,
    val totalPrice: String,
    val items: List<PendingOrderLine>
)

data class PendingOrderFile(
    val file: File,
    val record: PendingOrderRecord
)

class PendingOrderStorage(private val context: Context) {
    private val gson = Gson()

    private fun baseDir(): File {
        val dir = File(context.filesDir, "PendingOrders")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 保存待开单订单
     * @param orderItems 订单项列表
     * @param totalPrice 总价字符串
     * @param customFileName 自定义文件名（可选，为null时自动生成）
     * @return 保存的文件信息
     */
    fun savePendingOrder(orderItems: List<OrderItem>, totalPrice: String, customFileName: String? = null): PendingOrderFile {
        val now = Date()
        val lines = orderItems.map {
            PendingOrderLine(
                barcode = it.product.barcode,
                name = it.product.name,
                price = it.product.price.toPlainString(),
                quantity = it.quantity,
                subtotal = it.subtotal.toPlainString()
            )
        }

        // 使用文件名包含时间戳
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
        val fileName = customFileName ?: "pending_$timeStamp.json"

        val record = PendingOrderRecord(
            createdAt = now.time,
            fileName = fileName,
            totalPrice = totalPrice,
            items = lines
        )

        val file = File(baseDir(), fileName)
        file.writeText(gson.toJson(record))
        return PendingOrderFile(file = file, record = record)
    }

    /**
     * 获取所有待开单文件（按时间排序，时间越大的越靠前）
     */
    fun listAll(): List<PendingOrderFile> {
        val dir = baseDir()
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptyList()

        val type = object : TypeToken<PendingOrderRecord>() {}.type
        val list = mutableListOf<PendingOrderFile>()

        for (f in files) {
            try {
                val record: PendingOrderRecord = gson.fromJson(f.readText(), type)
                list.add(PendingOrderFile(file = f, record = record))
            } catch (_: Exception) {
            }
        }

        // 按时间戳降序排列（时间越大的越靠前）
        return list.sortedByDescending { it.record.createdAt }
    }

    /**
     * 从文件加载订单项列表
     */
    fun loadOrderItems(file: File): List<OrderItem>? {
        if (!file.exists()) return null

        return try {
            val type = object : TypeToken<PendingOrderRecord>() {}.type
            val record: PendingOrderRecord = gson.fromJson(file.readText(), type)

            // 这里需要ProductRepository来获取Product对象
            // 由于不能直接访问repository，我们返回record，由ViewModel处理
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删除待开单文件
     */
    fun delete(file: File) {
        if (file.exists()) file.delete()
    }

    /**
     * 检查文件是否存在
     */
    fun exists(fileName: String): Boolean {
        return File(baseDir(), fileName).exists()
    }
}

