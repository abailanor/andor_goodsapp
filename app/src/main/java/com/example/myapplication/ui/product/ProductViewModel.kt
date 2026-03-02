package com.example.myapplication.ui.product

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DataShareService
import com.example.myapplication.data.DatabaseImporter
import com.example.myapplication.data.ImportResult
import com.example.myapplication.data.Product
import com.example.myapplication.data.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



class ProductViewModel(private val repository: ProductRepository) : ViewModel() {
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private var databaseImporter: DatabaseImporter? = null
    private var applicationContext: Context? = null

    private val _isExporting = MutableStateFlow(false)  // 添加这行
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()  // 添加这行

    // 数据共享相关状态
    private var dataShareService: DataShareService? = null

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _isRequesting = MutableStateFlow(false)
    val isRequesting: StateFlow<Boolean> = _isRequesting.asStateFlow()

    private val _shareStatus = MutableStateFlow<String>("")
    val shareStatus: StateFlow<String> = _shareStatus.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String>("")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _targetIpAddress = MutableStateFlow<String>("")
    val targetIpAddress: StateFlow<String> = _targetIpAddress.asStateFlow()




    fun initializeDatabaseImporter(context: Context) {
        if (databaseImporter == null) {
            databaseImporter = DatabaseImporter(context)
            applicationContext = context.applicationContext
        }
    }
    
    init {
        loadProducts()
    }
    
    private fun loadProducts() {
        viewModelScope.launch {
            repository.getAllProducts().collect { productList ->
                _products.value = productList
            }
        }
    }
    
    fun addOrUpdateProduct(barcode: String, name: String, price: String,onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val priceValue = try {
                    BigDecimal(price)
                } catch (e: Exception) {
                    null
                }
                if (priceValue == null || priceValue < BigDecimal.ZERO) {
                    _message.value = "请输入有效的价格"
                    return@launch
                }
                if (name.isBlank()) {
                    _message.value = "请输入商品名"
                    return@launch
                }
                if (barcode.isBlank()) {
                    _message.value = "请输入条形码"
                    return@launch
                }

                val product = Product(barcode = barcode, name = name, price = priceValue)
                repository.insertProduct(product)
                _message.value = "商品已保存"
                onSuccess()
            } catch (e: Exception) {
                _message.value = "保存失败: ${e.message}"
            }
        }
    }

    fun updateProduct(barcode: String, name: String, price: String) {
        viewModelScope.launch {
            try {
                val priceValue = try {
                    BigDecimal(price)
                } catch (e: Exception) {
                    null
                }
                if (priceValue == null || priceValue < BigDecimal.ZERO) {
                    _message.value = "请输入有效的价格"
                    return@launch
                }
                if (name.isBlank()) {
                    _message.value = "请输入商品名"
                    return@launch
                }

                val product = repository.getProductByBarcode(barcode)
                if (product != null) {
                    val updatedProduct = product.copy(name = name, price = priceValue)
                    repository.updateProduct(updatedProduct)
                    _message.value = "商品已更新"
                } else {
                    _message.value = "商品不存在"
                }
            } catch (e: Exception) {
                _message.value = "更新失败: ${e.message}"
            }
        }
    }
    
    fun searchProductsByName(query: String): List<Product> {
        if (query.isBlank()) {
            return emptyList()
        }
        if(query.matches(Regex("\\d+"))){
            if(query.length<13){
                return emptyList()
            }
            else{
               return _products.value.filter { it.barcode.contains(query) }
            }
        }
        val tokens = splitQueryIntoTokens(query)
        return _products.value.filter {
            val productName = it.name
            val barcode=it.barcode
            tokens.all { token ->
                matchesToken(productName, token,barcode)
            }
        }
    }

    
    fun clearMessage() {
        _message.value = null
    }

    /**
     * 从Uri导入数据库文件
     */
    fun importDatabaseFromUri(uri: Uri) {
        val importer = databaseImporter ?: return
        val context = applicationContext ?: return
        _isImporting.value = true

        viewModelScope.launch {
            try {
                // 将Uri复制到临时文件
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = File(context.cacheDir, "temp_import.db")
                inputStream?.use { input: java.io.InputStream ->
                    tempFile.outputStream().use { output: java.io.OutputStream ->
                        input.copyTo(output)
                    }
                }

                // 执行导入
                val result = importer.importFromExternalDb(tempFile.absolutePath)

                // 删除临时文件
                tempFile.delete()

                _isImporting.value = false

                when (result) {
                    is ImportResult.Success -> {
                        _message.value = "数据库导入成功！共 ${result.importedCount} 条记录。"
                        // 重新加载产品列表
                        loadProducts()
                    }
                    is ImportResult.Error -> {
                        _message.value = "导入失败: ${result.message}"
                    }
                }
            } catch (e: Exception) {
                _isImporting.value = false
                _message.value = "导入失败: ${e.message}"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun exportDatabaseToDownloads() {
        val context = applicationContext ?: return
        _isExporting.value = true

        viewModelScope.launch {
            try {
                // 获取数据库文件
                val databaseFile = context.getDatabasePath("product_database")
                if (!databaseFile.exists()) {
                    _isExporting.value = false
                    _message.value = "数据库文件不存在"
                    return@launch
                }


                // 生成带时间戳的文件名
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "product_database_$timestamp.db"


                exportUsingMediaStore(context, databaseFile, fileName)


            } catch (e: Exception) {
                _isExporting.value = false
                _message.value = "导出失败: ${e.message}"
            }
        }
    }

    /**
     * Android 10及以上：使用MediaStore API保存到下载文件夹
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION")
    private fun exportUsingMediaStore(context: Context, sourceFile: File, fileName: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-sqlite3")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // 对于Android Q及以上，需要将IS_PENDING设置为0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                _isExporting.value = false
                _message.value = "数据库已导出到下载文件夹：$fileName"

            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            _isExporting.value = false
            _message.value = "创建文件失败"
        }
    }

    // ==================== 数据共享功能 ====================

    /**
     * 初始化数据共享服务
     */
    fun initializeDataShareService(context: Context) {
        if (dataShareService == null) {
            dataShareService = DataShareService(context.applicationContext)
            dataShareService?.setOnStatusChangedListener { status ->
                _shareStatus.value = status
            }
            dataShareService?.setOnDataReceivedListener { result ->
                _message.value = result
                _isRequesting.value = false
                loadProducts()
            }
            // 获取本机IP
            refreshLocalIpAddress()
        }
    }

    /**
     * 获取本机IP地址
     */
    private fun refreshLocalIpAddress() {
        val ip = dataShareService?.getLocalIpAddress()?.hostAddress ?: ""
        _localIpAddress.value = ip
    }

    /**
     * 切换数据共享状态（开启/关闭）
     */
    fun toggleSharing() {
        val service = dataShareService ?: return
        if (service.isSharing()) {
            service.stopSharing()
            _isSharing.value = false
        } else {
            if (service.startSharing()) {
                _isSharing.value = true
            }
        }
    }

    /**
     * 切换数据请求状态（请求/取消）
     */
    fun toggleRequesting() {
        val service = dataShareService ?: return
        if (service.isRequesting()) {
            service.stopRequesting()
            _isRequesting.value = false
        } else {
            // 开始请求数据
            val targetIp = _targetIpAddress.value.trim()
            if (targetIp.isBlank()) {
                _message.value = "请输入对方IP地址"
                return
            }
            try {
                val address = java.net.InetAddress.getByName(targetIp)
                service.startRequesting(address)
                _isRequesting.value = true
            } catch (e: Exception) {
                _message.value = "无效的IP地址: ${e.message}"
            }
        }
    }

    /**
     * 设置目标IP地址
     */
    fun setTargetIpAddress(ip: String) {
        _targetIpAddress.value = ip
    }

    /**
     * 释放数据共享服务资源
     */
    override fun onCleared() {
        super.onCleared()
        dataShareService?.release()
        dataShareService = null
    }

    private fun matchesToken(productName: String, token: String,barcode: String): Boolean {
        // 如果 token 是纯数字，进行精确匹配
        if (token.matches(Regex("\\d+"))) {
            // 使用正则表达式确保数字精确匹配
            // \\D 表示非数字，? 表示 0 或 1 个
            // 匹配模式：前面是非数字或开头，后面是非数字或结尾
            val pattern = "(?<!\\d)$token(?!\\d)"
            return Regex(pattern).containsMatchIn(productName)
        } else {
            // 非数字 token，直接包含匹配
            return productName.contains(token)
        }
    }

    private fun splitQueryIntoTokens(query: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val length = query.length

        while (i < length) {
            val currentChar = query[i]

            // 处理连续数字
            if (currentChar.isDigit()) {
                val start = i
                while (i < length && query[i].isDigit()) {
                    i++
                }
                tokens.add(query.substring(start, i))
            }
            // 处理其他字符（每个单独）
            else {
                tokens.add(currentChar.toString())
                i++
            }
        }

        return tokens.filter { it.isNotBlank() }
    }


}

class ProductViewModelFactory(private val repository: ProductRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProductViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProductViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
