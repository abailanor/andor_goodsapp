package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 购物车里一行的持久化形状。
 *
 * ## 为什么 boxes 与 singles 都要存，而不是只存总数
 *
 * 整箱价 ≠ 箱规 × 单价，所以"30 个"可以是 1 箱 + 6 个（101.00），
 * 也可以是 30 个散卖（105.00）—— **同一个总数对应两个不同的金额**。
 * 只存总数的话，恢复购物车时就得靠 `CartPricing.fromTotalQuantity` 按箱规反推，
 * 而那是"对顾客有利"的猜测：店里其实没整箱可拆时会少收钱。
 * 用户明确输了箱数就存箱数，不要替他猜。
 *
 * @param quantity 冗余字段，= boxes × 箱规 + singles。**只为读旧数据保留** ——
 *        升级前存的车只有这一个字段。新写入时照旧填上，这样降级回旧版本也不会全丢。
 */
data class SavedOrderItem(
    val barcode: String,
    val quantity: Int = 0,
    val boxes: Int = 0,
    val singles: Int = 0,
) {
    /**
     * 这一行是不是**旧格式**（只有总数、没有箱/个拆分）。
     *
     * ⚠ 判据必须三个条件一起看：`boxes == 0 && singles == 0 && quantity > 0`。
     * 只看前两个的话，"新格式但两个框都没填"会被误判成旧数据 ——
     * 不过那种行本来就不该被存下来（[CartLine] 的 init 要求至少有一个）。
     */
    val isLegacy: Boolean get() = boxes == 0 && singles == 0 && quantity > 0
}
data class OrderStorageData(
    val orderItems: List<SavedOrderItem>,
    val sourcePendingFileName: String? = null,
    val targetPendingFileName: String? = null
)


/**
 * 开单页购物车的持久化。**销售与进货各一个槽位。**
 *
 * ## 为什么必须持久化、而且必须分两个
 *
 * 进货单常常是"边盘点边录"，录到一半被叫去收银、或者进程被国产 ROM 杀掉，
 * 回来发现几十个条目全没了 —— 那比没有持久化更糟，因为用户会以为存过了。
 *
 * 分两个槽是因为两种模式的内容不兼容：销售车要算钱，进货车不算
 * （服务端根本不存储买入价，本地目录里的 `price` 是**卖出价**）。
 * 共用一个槽的话，从进货切回销售会看到一堆没有价格语义的条目。
 *
 * 切换模式**不清空**任何一边：两边的数据都留着，切回来还在。
 */
class OrderStorage(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("order_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    private companion object {
        /** 旧版本只有一个槽，键名就是这个。升级后把它当**销售车**读回来。 */
        const val LEGACY_KEY = "order_data"

        fun keyOf(mode: String) = "order_data_$mode"
    }

    suspend fun saveOrderData(
        mode: String,
        orderItems: List<CartLine>,
        sourcePendingFileName: String? = null,
        targetPendingFileName: String? = null
    ) {
        withContext(Dispatchers.IO) {
            // 转换为可存储的格式
            val savedItems = orderItems.map {
                SavedOrderItem(
                    barcode = it.product.barcode,
                    // quantity 是冗余的，但照旧填上：降级回旧版本时至少总数还在
                    quantity = it.quantity ?: 0,
                    boxes = it.boxes,
                    singles = it.singles,
                )
            }

            // 创建完整的数据对象
            val storageData = OrderStorageData(
                orderItems = savedItems,
                sourcePendingFileName = sourcePendingFileName,
                targetPendingFileName = targetPendingFileName
            )

            val json = gson.toJson(storageData)
            val editor = prefs.edit().putString(keyOf(mode), json)
            // 旧键一并清掉：它已经被搬进销售槽了，留着的话下一次"兼容读取"
            // 会把一份陈旧的车再盖回来。
            if (mode == "SALE") editor.remove(LEGACY_KEY)
            editor.apply()
        }
    }

    /**
     * @param mode `"SALE"` / `"PURCHASE"`（[com.example.myapplication.ui.OrderMode] 的 name）。
     *        用字符串而不是枚举是为了让这个类不依赖 ui 层。
     */
    suspend fun loadOrderData(mode: String): OrderStorageData {
        return withContext(Dispatchers.IO) {
            // 销售槽为空时回退读旧键：升级前正在编辑的车不能因为改了键名就丢掉
            val json = prefs.getString(keyOf(mode), null)
                ?: (if (mode == "SALE") prefs.getString(LEGACY_KEY, null) else null)
                ?: return@withContext OrderStorageData(
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
    
    /** 只清当前模式那一个槽 —— 另一种模式的车不能跟着一起没。 */
    suspend fun clearOrderItems(mode: String) {
        withContext(Dispatchers.IO) {
            val editor = prefs.edit().remove(keyOf(mode))
            if (mode == "SALE") editor.remove(LEGACY_KEY)
            editor.apply()
        }
    }
}
