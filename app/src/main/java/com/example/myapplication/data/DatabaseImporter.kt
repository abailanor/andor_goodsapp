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
                val legacy = mutableListOf<BoxRowMerger.LegacyRow>()
                val cursor = externalDb.rawQuery(
                    "SELECT barcode, name, price FROM products",
                    null
                )

                cursor.use {
                    while (it.moveToNext()) {
                        val barcode = it.getString(0) ?: ""
                        val name = it.getString(1) ?: ""
                        // price 一律按**字符串**读再转 BigDecimal：
                        // 走 Double 会有精度问题，而服务端的金额字段是保留尾零的字符串。
                        val priceString = it.getString(2) ?: ""
                        val price = try {
                            BigDecimal(priceString.trim())
                        } catch (e: NumberFormatException) {
                            null
                        }
                        if (barcode.isNotBlank() && name.isNotBlank() && price != null) {
                            legacy.add(BoxRowMerger.LegacyRow(barcode, name, price))
                        }
                    }
                }

                if (legacy.isEmpty()) {
                    return@withContext ImportResult.Error("外部数据库中没有找到产品数据")
                }

                // 旧格式用"条码前加 0 再插一行"表示整件，必须合并成一条
                // （与 MIGRATION_3_4 走同一套 BoxRowMerger，规则不会漂移）
                val merged = BoxRowMerger.merge(legacy)
                val products = merged.merged.map { it.product } +
                    merged.orphanBoxRows.map { Product(it.barcode, it.name, it.price) }

                if (products.isEmpty()) {
                    return@withContext ImportResult.Error("外部数据库中没有找到可用的产品数据")
                }

                val database = AppDatabase.getDatabase(context)
                ProductRepository(database.productDao()).insertProducts(products)

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

