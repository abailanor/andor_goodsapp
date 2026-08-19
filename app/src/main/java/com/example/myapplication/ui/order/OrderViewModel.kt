package com.example.myapplication.ui.order

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DatabaseImporter
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.OrderHistoryStorage
import com.example.myapplication.data.OrderStorage
import com.example.myapplication.data.PendingOrderStorage
import com.example.myapplication.data.Product
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.util.AssetsSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.cancellation.CancellationException

class OrderViewModel(
    private val repository: ProductRepository,
    private val context: Context? = null
) : ViewModel() {
    private val _orderItems = MutableStateFlow<List<OrderItem>>(emptyList())
    val orderItems: StateFlow<List<OrderItem>> = _orderItems.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val totalPrice: BigDecimal
        get() = _orderItems.value.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.subtotal)
        }

    // 待开单相关状态
    private val _sourcePendingFileName = MutableStateFlow<String?>(null)
    val sourcePendingFileName: StateFlow<String?> = _sourcePendingFileName.asStateFlow()

    // 目标待开单文件（保存时覆盖的目标）
    private val _targetPendingFileName = MutableStateFlow<String?>(null)
    val targetPendingFileName: StateFlow<String?> = _targetPendingFileName.asStateFlow()

    // 保存完成回调（用于开单页面通知待开单页面保存完成）
    var onSaveComplete: (() -> Unit)? = null

    private var soundPlayer: AssetsSoundPlayer?=null


    private val _soundEnabled = MutableStateFlow(true) // 默认值
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()
    init {
        // 加载保存的订单
        if (context != null) {
            loadSavedOrder()
        }
        context?.let {
            soundPlayer = AssetsSoundPlayer(it)
            _soundEnabled.value = soundPlayer?.returnstat() ?: true
        }
    }

    /**
     * 加载待开单项到订单
     * @param items 待开单商品列表
     * @param sourceFileName 来源文件名称（用于显示）
     * @param targetFileName 目标文件名称（保存时覆盖的目标，初始为空）
     */
    fun loadPendingOrderItems(items: List<OrderItem>, sourceFileName: String, targetFileName: String? = null) {
        _orderItems.value = items
        _sourcePendingFileName.value = sourceFileName
        _targetPendingFileName.value = targetFileName
        saveOrder()
    }

    // OrderViewModel.kt - 添加这些变量和方法

    // 在类中添加这些成员变量
    private val _searchResults = MutableStateFlow<List<Product>>(emptyList())
    val searchResults: StateFlow<List<Product>> = _searchResults.asStateFlow()



    // 在类中添加这些方法
// 检查是否包含中文字符



    fun setsound(){
        soundPlayer?.setSoundEnabled()
        _soundEnabled.value= soundPlayer?.returnstat()?:true
    }

    private fun containsChinese(text: String): Boolean {
        val pattern = Pattern.compile("[\u4e00-\u9fa5]")
        return pattern.matcher(text).find()
    }
    // 商品搜索
    private var searchJob: Job? = null
    fun searchProducts(query: String) {
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }

        // 如果是纯数字，视为条形码，不进行查询
        val isNumeric = query.matches(Regex("\\d+"))
        if (isNumeric && !containsChinese(query)) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            try {
                val results = repository.searchProductsByCharacters(query)

                // 更新结果
                _searchResults.value = results

                // 可选：记录搜索结果
                if (results.isEmpty()) {
                    _message.value = "未找到包含 '${query}' 所有字符的商品"
                }
            } catch (e: Exception) {
                _message.value = "查询失败: ${e.message}"
                _searchResults.value = emptyList()
            } finally {
            }
        }
    }

    // 清空搜索结果
    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    // 添加商品（从搜索结果中选择）
    fun addProductFromSearch(product: Product) {
        viewModelScope.launch {
            try {
                val existingItem = _orderItems.value.find { it.product.barcode.contains( product.barcode) }
                if (existingItem != null) {
                    // 如果商品已存在
                    _message.value = "${product.name} 已在订单中"

                    _orderItems.value = listOf(existingItem) + _orderItems.value.filter { it.product.barcode != product.barcode }
                    _message.value = "已更新: ${product.name} (移至最前)"
                    soundPlayer?.playAlreadyInList()
                } else {
                    // 添加新商品，默认数量为5
                    val newItem = OrderItem(product = product, quantity = 5)
                    _orderItems.value = listOf(newItem) + _orderItems.value
                    _message.value = "已添加: ${product.name}"
                    soundPlayer?.playScanSuccess()
                }
                saveOrder()
                // 添加成功后清空搜索结果
                clearSearchResults()
            } catch (e: Exception) {
                _message.value = "添加失败: ${e.message}"
                soundPlayer?.playError()
            }
        }
    }


    private fun loadSavedOrder() {
        viewModelScope.launch {
            if (context != null) {
                val storage = OrderStorage(context)
                val savedItems = storage.loadOrderData()
                if (savedItems.orderItems.isNotEmpty()) {
                    val restoredItems = mutableListOf<OrderItem>()
                    for (savedItem in savedItems.orderItems) {
                        val product = repository.getProductByBarcode(savedItem.barcode)
                        if (product != null) {
                            restoredItems.add(OrderItem(product = product, quantity = savedItem.quantity))
                        }
                    }
                    if (restoredItems.isNotEmpty()) {
                        _orderItems.value = restoredItems
                    }
                    _sourcePendingFileName.value=savedItems.sourcePendingFileName
                    _targetPendingFileName.value=savedItems.targetPendingFileName
                }
            }
        }
    }

    private fun saveOrder() {
        viewModelScope.launch {
            if (context != null) {
                val storage = OrderStorage(context)
                storage.saveOrderData(_orderItems.value,_sourcePendingFileName.value,_targetPendingFileName.value)
            }
        }
    }

    fun scanBarcode(barcode: String) {
        viewModelScope.launch {
            try {
                val product = repository.getProductByBarcode(barcode)
                if (product != null) {
                    val existingItem = _orderItems.value.find { it.product.barcode.contains( barcode) }
                    if (existingItem != null) {
                        _message.value = "${product.name} 已在订单中，当前数量: ${existingItem.quantity}"
                        soundPlayer?.playAlreadyInList()
                    } else {
                        if(barcode[0]=='0') {
                            val newItem = OrderItem(product = product, quantity = 1)
                            _orderItems.value = listOf(newItem) + _orderItems.value
                            _message.value = "已添加: ${product.name}"
                            soundPlayer?.playScanSuccess()
                            saveOrder()
                        }
                        else {
                            val newItem = OrderItem(product = product, quantity = 5)
                            _orderItems.value = listOf(newItem) + _orderItems.value
                            _message.value = "已添加: ${product.name}"
                            soundPlayer?.playScanSuccess()
                            saveOrder()
                        }
                        }
                } else {
                    _message.value = "未找到商品: $barcode"
                    soundPlayer?.playNotExsist()
                }
            } catch (e: Exception) {
                _message.value = "扫描失败: ${e.message}"
                soundPlayer?.playError()
            }
        }
    }

    fun updateQuantity(barcode: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(barcode)
            return
        }

        _orderItems.value = _orderItems.value.map { item ->
            if (item.product.barcode == barcode) {
                // 创建新的OrderItem实例，确保subtotal重新计算
                OrderItem(product = item.product, quantity = newQuantity)
            } else {
                item
            }
        }
        saveOrder()
    }

    fun removeItem(barcode: String) {
        _orderItems.value = _orderItems.value.filter { it.product.barcode != barcode }
        saveOrder()
    }

    fun clearOrder() {
        _orderItems.value = emptyList()
        _message.value = "订单已清空"
        _sourcePendingFileName.value = null
        _targetPendingFileName.value = null
        viewModelScope.launch {
            if (context != null) {
                val storage = OrderStorage(context)
                storage.clearOrderItems()
            }
        }
    }

    /**
     * 清除订单（不清除来源标记，用于开单加载新订单前）
     */
    fun clearOrderWithoutSource() {
        _orderItems.value = emptyList()
        viewModelScope.launch {
            if (context != null) {
                val storage = OrderStorage(context)
                storage.clearOrderItems()
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun saveOrderToHistory() {
        viewModelScope.launch {
            try {
                val ctx = context ?: return@launch
                val storage = OrderHistoryStorage(ctx)
                val total = totalPrice.toPlainString()
                storage.saveNewOrder(_orderItems.value, total)
            } catch (e: Exception) {
                _message.value = "保存历史订单失败: ${e.message}"
            }
        }
    }

    /**
     * 保存待开单
     * 如果有待开单数据（targetOrder 不为空），则覆盖保存
     * 否则新建一个待开单文件
     * @return 保存后的文件名，如果无订单项则返回 null
     */
    fun savePendingOrder(): String? {
        if (_orderItems.value.isEmpty()) return null

        var savedFileName: String? = null
        viewModelScope.launch {
            try {
                val ctx = context ?: return@launch
                val storage = PendingOrderStorage(ctx)
                val total = totalPrice.toPlainString()
                val targetFileName = _targetPendingFileName.value

                // 如果有目标文件，先删除旧文件
                if (targetFileName != null) {
                    val targetFile = java.io.File(ctx.filesDir, "PendingOrders/$targetFileName")
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                }

                // 保存新文件
                val savedFile = storage.savePendingOrder(_orderItems.value, total)
                savedFileName = savedFile.record.fileName

                // 更新目标文件
                _targetPendingFileName.value = savedFile.record.fileName
                _sourcePendingFileName.value = _targetPendingFileName.value
                _message.value = "已保存到待开单"
                saveOrder()

                // 通知保存完成
                onSaveComplete?.invoke()
            } catch (e: Exception) {
                _message.value = "保存待开单失败: ${e.message}"
            }
        }
        return savedFileName
    }

    /**
     * 获取当前订单的商品数量
     */
    fun getOrderItemCount(): Int = _orderItems.value.size

    /**
     * 获取目标待开单文件名
     */
    fun getTargetPendingFileName(): String? = _targetPendingFileName.value
}
