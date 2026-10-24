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
    
    /**
     * 保存一个商品（**v4 形状**：一条记录带 `boxPrice` / `boxSize`）。
     *
     * ## ⚠ 取代"建两行"的旧做法
     *
     * 旧的新增表单调两次 `addOrUpdateProduct`（已删）：一次存 `<barcode>`（单品价），
     * 一次存 `"0"+<barcode>`（名字加"整件"后缀、价格填整件价）。那是 v3 的约定，
     * 而 `MIGRATION_3_4` / `BoxRowMerger` 的作用**正是把这两行合并成一条**。
     * 于是 schema 已经是 v4、录入界面还在生产 v3 数据：新加的商品会多出一条
     * `0<barcode>` 的幽灵行，而 `CartPricing` 永远读不到它的整箱价
     * （整箱价挂在另一个条码上），整箱优惠因此完全不生效。
     *
     * ## ⚠ 必须保留已有行的其它字段
     *
     * 旧路径构造 `Product(barcode, name, priceValue)`，`stock` / `orderable` /
     * `category` / `note` 全走默认值，然后 `insertProduct` 原样写库 ——
     * **改一次价格就把库存清零、把分类和备注抹掉**，而且不报错。
     * 这里从已有行把它们读回来。
     *
     * @param sellsByBox 这个商品按整箱卖吗。false 时 [boxPrice] / [boxSize] 一律忽略，
     *        存成 `null` / `0`。
     * @param boxPrice   整箱价文本。**不按整箱卖时必须存 null，不能存 0** ——
     *        `0` 的语义是"整箱免费"（见 `docs/INTERCHANGE_FORMAT.md` §2.2），
     *        而 `null` 才是"不按整箱卖"。两者混用会让整箱优惠算出一个负数。
     * @param boxSize    一箱几个。`0` = 未设置。
     */
    fun saveProduct(
        barcode: String,
        name: String,
        price: String,
        sellsByBox: Boolean,
        boxPrice: String,
        boxSize: String,
        /**
         * `sellsByBox == false` 时**保留**已有行的 `boxPrice` / `boxSize`，不清成 null/0。
         *
         * 用于"全局关掉了整箱"这种情况：用户只是想暂时不看那两个框，
         * 不是想丢掉已经填好的整箱价。清掉的话重新打开设置，数据就没了 ——
         * 而用户会以为是自己记错了。
         */
        preserveExistingBox: Boolean = false,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val code = barcode.trim()
                if (code.isEmpty()) {
                    _message.value = "请输入条形码"
                    return@launch
                }
                if (name.isBlank()) {
                    _message.value = "请输入商品名"
                    return@launch
                }
                val priceValue = parsePrice(price)
                if (priceValue == null) {
                    _message.value = "请输入有效的单价"
                    return@launch
                }

                val existingForBox = if (preserveExistingBox) repository.getProductByBarcode(code) else null
                var boxPriceValue: BigDecimal? = existingForBox?.boxPrice
                var boxSizeValue = existingForBox?.boxSize ?: 0
                if (sellsByBox) {
                    boxPriceValue = parsePrice(boxPrice)
                    if (boxPriceValue == null) {
                        _message.value = "按整箱卖就必须填整箱价"
                        return@launch
                    }
                    boxSizeValue = boxSize.trim().toIntOrNull() ?: 0
                    // ⚠ 有整箱价却没箱规 = Product.needsBoxSize，那是个坏状态：
                    // 钱算得出、个数算不出，于是开单时只能按散卖计价 —— 整箱价被忽略、
                    // 多收顾客的钱，而且票面自洽看不出来（CartPricing.ambiguousBoxProducts
                    // 就是为了把这种情况报出来）。在录入这一刻就拦住，比事后补救便宜。
                    if (boxSizeValue <= 0) {
                        _message.value = "按整箱卖就必须填一箱装几个（大于 0）"
                        return@launch
                    }
                }

                val existing = repository.getProductByBarcode(code)
                val product = Product(
                    barcode = code,
                    name = name.trim(),
                    price = priceValue,
                    boxPrice = boxPriceValue,
                    boxSize = boxSizeValue,
                    // 这四个一律沿用已有行：它们是这次编辑**没有涉及**的东西
                    stock = existing?.stock ?: 0,
                    orderable = existing?.orderable ?: true,
                    category = existing?.category ?: "",
                    note = existing?.note ?: "",
                    origin = existing?.origin ?: Product.ORIGIN_LOCAL,
                    storeKey = existing?.storeKey ?: "",
                )
                if (existing != null) repository.updateProduct(product) else repository.insertProduct(product)

                _message.value = if (sellsByBox) {
                    "商品已保存（整箱 $boxPriceValue / 一箱 $boxSizeValue 个）"
                } else {
                    "商品已保存"
                }
                onSuccess()
            } catch (e: Exception) {
                _message.value = "保存失败: ${e.message}"
            }
        }
    }

    /** 价格文本 → BigDecimal。空串、解析失败、负数一律 null（调用方负责报错）。 */
    private fun parsePrice(raw: String): BigDecimal? =
        runCatching { BigDecimal(raw.trim()) }.getOrNull()?.takeIf { it >= BigDecimal.ZERO }

    
    fun searchProductsByName(query: String): List<Product> {
        if (query.isBlank()) {
            return emptyList()
        }
        if (query.matches(Regex("\\d+"))) {
            // ⚠ 原来要求 `length >= 13` 才按条码查，于是 12 位条码（UPC-A）、
            // 8 位的店内码、以及扫到一半的输入一律返回空 —— 用户看到的是
            // "明明有这个商品却搜不出来"，而原因藏在一个长度判断里。
            // 门槛降到 4：再低的话输一个 "1" 会把半个目录都列出来。
            if (query.length < 4) return emptyList()
            return _products.value.filter { it.barcode.contains(query) }
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
                        _isSharing.value=false
                        _isRequesting.value=false
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
        if (service.isSharing() || _isSharing.value==true) {
            service.stopSharing()
            _isSharing.value = false
        } else {
            if (service.startSharing()) {
                _isSharing.value = true
            }
        }
    }

    /**
     * 发送数据
     */
    fun toggleSending() {
        val service = dataShareService ?: return
        if (service.isRequesting() || isRequesting.value == true) {
            service.stopRequesting()
            _isRequesting.value = false
        } else {
            // 开始请求数据
            service.startRequesting()
            _isRequesting.value = true

        }
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
