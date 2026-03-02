package com.example.myapplication.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

/**
 * 数据库导入器
 * 用于从外部SQLite数据库文件导入产品数据
 * 将 REAL 类型的价格转换为精确的 BigDecimal 存储
 */
class DatabaseImporter(private val context: Context) {

    /**
     * 从外部db文件提取产品数据并保存到Room数据库
     * @param dbFilePath 外部数据库文件的路径
     * @return 导入结果
     */
    suspend fun importFromExternalDb(dbFilePath: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            val externalDbFile = File(dbFilePath)
            if (!externalDbFile.exists()) {
                return@withContext ImportResult.Error("文件不存在: $dbFilePath")
            }

            // 打开外部数据库
            val externalDb = SQLiteDatabase.openDatabase(
                dbFilePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            try {
                // 读取外部数据库中的产品数据
                val products = mutableListOf<Product>()
                val cursor = externalDb.rawQuery(
                    "SELECT barcode, name, price FROM products",
                    null
                )

                cursor.use {
                    while (it.moveToNext()) {
                        val barcode = it.getString(0) ?: ""
                        val name = it.getString(1) ?: ""

                        val priceString = it.getString(2)?:""
                        val price = BigDecimal(priceString)

                        if (barcode.isNotBlank() && name.isNotBlank()) {
                            products.add(Product(barcode, name, price))
                        }
                    }
                }

                if (products.isEmpty()) {
                    return@withContext ImportResult.Error("外部数据库中没有找到产品数据")
                }

                // 清空当前数据库并导入新数据
                val database = AppDatabase.getDatabase(context)
                val productDao = database.productDao()


                // 批量插入新数据
                products.forEach { product ->
                    productDao.insertProduct(product)
                }

                ImportResult.Success(products.size)

            } finally {
                externalDb.close()
            }

        } catch (e: Exception) {
            ImportResult.Error("导入失败: ${e.message}")
        }
    }

    /**
     * 将 Double 格式化为精确的字符串表示
     * 避免浮点精度问题
     */
    private fun formatPriceAsString(price: Double): String {
        // 如果是整数，直接返回整数形式
        if (price == price.toLong().toDouble()) {
            return price.toLong().toString()
        }
        // 否则保留最多8位小数（足够精度）
        return String.format("%.8f", price).trimEnd('0').trimEnd('.')
    }
}

/**
 * 导入结果密封类
 */
sealed class ImportResult {
    data class Success(val importedCount: Int) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

