package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedOrderItem(
    val barcode: String,
    val quantity: Int
)
data class OrderStorageData(
    val orderItems: List<SavedOrderItem>,
    val sourcePendingFileName: String? = null,
    val targetPendingFileName: String? = null
)


class OrderStorage(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("order_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun saveOrderData(
        orderItems: List<OrderItem>,
        sourcePendingFileName: String? = null,
        targetPendingFileName: String? = null
    ) {
        withContext(Dispatchers.IO) {
            // 转换为可存储的格式
            val savedItems = orderItems.map {
                SavedOrderItem(it.product.barcode, it.quantity)
            }

            // 创建完整的数据对象
            val storageData = OrderStorageData(
                orderItems = savedItems,
                sourcePendingFileName = sourcePendingFileName,
                targetPendingFileName = targetPendingFileName
            )

            val json = gson.toJson(storageData)
            prefs.edit().putString("order_data", json).apply()
        }
    }


    suspend fun loadOrderData(): OrderStorageData {
        return withContext(Dispatchers.IO) {
            val json = prefs.getString("order_data", null) ?: return@withContext OrderStorageData(
                orderItems = emptyList(),
                sourcePendingFileName = null,
                targetPendingFileName = null
            )

            try {
                val type = object : TypeToken<OrderStorageData>() {}.type
                gson.fromJson<OrderStorageData>(json, type) ?: OrderStorageData(
                    orderItems = emptyList(),
                    sourcePendingFileName = null,
                    targetPendingFileName = null
                )
            } catch (e: Exception) {
                // 兼容旧版本：尝试加载只有订单项的数据
                try {
                    val oldType = object : TypeToken<List<SavedOrderItem>>() {}.type
                    val oldItems = gson.fromJson<List<SavedOrderItem>>(json, oldType) ?: emptyList()
                    OrderStorageData(
                        orderItems = oldItems,
                        sourcePendingFileName = null,
                        targetPendingFileName = null
                    )
                } catch (e2: Exception) {
                    OrderStorageData(
                        orderItems = emptyList(),
                        sourcePendingFileName = null,
                        targetPendingFileName = null
                    )
                }
            }
        }
    }

//    /**
//     * 仅加载订单项（向后兼容）
//     */
//    suspend fun loadOrderItems(): List<SavedOrderItem> {
//        return loadOrderData().orderItems
//    }
    
    suspend fun clearOrderItems() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove("order_data").apply()
        }
    }
}
