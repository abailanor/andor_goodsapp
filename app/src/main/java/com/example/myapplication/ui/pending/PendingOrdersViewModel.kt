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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _pendingOrders = MutableStateFlow<List<PendingOrderFile>>(emptyList())
    val pendingOrders: StateFlow<List<PendingOrderFile>> = _pendingOrders.asStateFlow()

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
            _pendingOrders.value = files
        }
    }

    /**
     * 预览订单详情
     */
    fun previewOrder(pendingOrderFile: PendingOrderFile) {
        viewModelScope.launch {
            try {
                val orderItems = mutableListOf<OrderItem>()
                for (line in pendingOrderFile.record.items) {
                    val product = repository.getProductByBarcode(line.barcode)
                    if (product != null) {
                        orderItems.add(OrderItem(product = product, quantity = line.quantity))
                    }
                }
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
                val orderItems = mutableListOf<OrderItem>()
                for (line in pendingOrderFile.record.items) {
                    val product = repository.getProductByBarcode(line.barcode)
                    if (product != null) {
                        orderItems.add(OrderItem(product = product, quantity = line.quantity))
                    }
                }

                // 设置新订单数据
                PendingOrderLoader.orderItems = orderItems
                PendingOrderLoader.sourceFileName = pendingOrderFile.record.fileName
                PendingOrderLoader.targetOrder = pendingOrderFile.record.fileName

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

