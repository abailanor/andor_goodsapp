package com.example.myapplication.ui.pending

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.PendingOrderFile
import com.example.myapplication.data.PendingOrderLoader
import com.example.myapplication.data.PendingOrderRecord
import com.example.myapplication.data.PendingOrderStorage
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.restorePendingOrderItems
import com.example.myapplication.ui.CurrentOrderMode
import com.example.myapplication.ui.orderModeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待开单列表项（包含完整产品信息）
 */
data class PendingOrderItem(
    val file: File,
    val record: PendingOrderRecord,
    val orderItems: List<OrderItem>
)

class PendingOrdersViewModel(
    private val repository: ProductRepository,
    private val context: Context
) : ViewModel() {

    private val storage = PendingOrderStorage(context)

    /** 磁盘上的全部待开单（两种模式混在一起）。UI 不直接看这个。 */
    private val _allPending = MutableStateFlow<List<PendingOrderFile>>(emptyList())

    /**
     * **只显示当前模式**的待开单。
     *
     * ⚠ 必须过滤，不能全列：进货单和销售单的行内容不兼容（进货不算金额），
     * 混在一个列表里点「开单」会把进货单塞进销售车，上传出去就是一笔
     * 计营收、扣库存的 B 类订单 —— 正好是进货单不该做的两件事。
     */
    val pendingOrders: StateFlow<List<PendingOrderFile>> =
        combine(_allPending, CurrentOrderMode.mode) { all, mode ->
            all.filter { orderModeOf(it.record.modeOrSale) == mode }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 另一种模式下**还有**待开单。
     *
     * 过滤之后必须能解释"我明明挂了一张，怎么不见了"。少了这一条，
     * 用户在销售模式下看不到昨天挂的进货单，只会以为它丢了 —— 而"以为丢单"
     * 比真的丢单更难查，因为磁盘上的文件其实好好的。
     */
    val hasOtherMode: StateFlow<Boolean> =
        combine(_allPending, CurrentOrderMode.mode) { all, mode ->
            all.any { orderModeOf(it.record.modeOrSale) != mode }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selectedOrder = MutableStateFlow<PendingOrderItem?>(null)
    val selectedOrder: StateFlow<PendingOrderItem?> = _selectedOrder.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _navigateToOrder = MutableStateFlow<Boolean>(false)
    val navigateToOrder: StateFlow<Boolean> = _navigateToOrder.asStateFlow()

    private val _orderItemsToLoad = MutableStateFlow<List<OrderItem>?>(null)
    val orderItemsToLoad: StateFlow<List<OrderItem>?> = _orderItemsToLoad.asStateFlow()

    private val _sourceFileName = MutableStateFlow<String?>(null)
    val sourceFileName: StateFlow<String?> = _sourceFileName.asStateFlow()

    init {
        loadPendingOrders()
    }

    /**
     * 加载所有待开单文件
     */
    fun loadPendingOrders() {
        viewModelScope.launch {
            val files = storage.listAll()
            _allPending.value = files
        }
    }

    /**
     * 预览订单详情
     */
    fun previewOrder(pendingOrderFile: PendingOrderFile) {
        viewModelScope.launch {
            try {
                val (orderItems, _) = toOrderItems(pendingOrderFile.record)
                _selectedOrder.value = PendingOrderItem(
                    file = pendingOrderFile.file,
                    record = pendingOrderFile.record,
                    orderItems = orderItems
                )
            } catch (e: Exception) {
                _message.value = "加载订单失败: ${e.message}"
            }
        }
    }

    /**
     * 把待开单的行还原成购物车行。逻辑在 [restorePendingOrderItems]（纯函数，可单测）。
     *
     * ⚠ 商品库查不到**不能丢行**：进货单的商品来自管理员目录，本地库里本来就可能
     * 没有。逐行查库、查不到就跳过的写法会让进货单点「开单」后购物车是空的。
     *
     * @return (购物车行, 其中按快照恢复的行数)
     */
    private suspend fun toOrderItems(record: PendingOrderRecord): Pair<List<OrderItem>, Int> =
        restorePendingOrderItems(record) { barcode ->
            repository.getProductByBarcode(barcode)
        }

    /**
     * 关闭预览
     */
    fun dismissPreview() {
        _selectedOrder.value = null
    }

    /**
     * 打开订单到开单页面
     * 如果开单页面已有订单项，先保存当前订单，然后清除，再加载新订单
     */
    fun openOrder(pendingOrderFile: PendingOrderFile) {
        viewModelScope.launch {
            try {
                val (orderItems, fromSnapshot) = toOrderItems(pendingOrderFile.record)
                if (fromSnapshot > 0) {
                    // 进货单常见：商品不在本地库。告知用户是按保存时的快照载入的，
                    // 免得他以为"开单页自动变出了我没录过的商品"。
                    _message.value = "有 $fromSnapshot 项商品不在本地商品库，已按保存时的快照载入"
                }

                // 设置新订单数据
                PendingOrderLoader.orderItems = orderItems
                PendingOrderLoader.sourceFileName = pendingOrderFile.record.fileName
                PendingOrderLoader.targetOrder = pendingOrderFile.record.fileName
                // ⚠ 模式必须一起传过去：开单页要**先切模式再放购物车**。
                // 少了它，进货单会被塞进销售车，上传出去是一张计营收、扣库存的 B 类单。
                PendingOrderLoader.mode = pendingOrderFile.record.modeOrSale

                // 添加小延迟确保数据设置完成
                kotlinx.coroutines.delay(50)
                _navigateToOrder.value = true
            } catch (e: Exception) {
                _message.value = "打开订单失败: ${e.message}"
            }
        }
    }

    /**
     * 导航完成
     */
    fun onNavigationComplete() {
        _navigateToOrder.value = false
    }

    /**
     * 删除待开单
     */
    fun deletePendingOrder(pendingOrderFile: PendingOrderFile) {
        viewModelScope.launch {
            try {
                storage.delete(pendingOrderFile.file)
                loadPendingOrders()
                _message.value = "已删除待开单"
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    /**
     * 获取订单时间格式化字符串
     */
    fun formatOrderTime(createdAt: Long): String {
        val date = Date(createdAt)
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(date)
    }

    /**
     * 清空消息
     */
    fun clearMessage() {
        _message.value = null
    }
}

class PendingOrdersViewModelFactory(
    private val repository: ProductRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PendingOrdersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PendingOrdersViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

