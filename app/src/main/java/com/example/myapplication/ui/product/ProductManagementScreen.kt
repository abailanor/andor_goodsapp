package com.example.myapplication.ui.product

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.Product
import com.example.myapplication.data.ImportResult
import com.example.myapplication.util.PriceFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll

enum class ProductManagementTab {
    ADD_PRODUCT,  // 添加商品
    MODIFY_PRODUCT,  // 商品修改
    //PRODUCT_LIST,  // 商品列表
    IMPORT_DB  // 导入数据库
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductManagementScreen(
    viewModel: ProductViewModel,
    modifier: Modifier = Modifier
) {
    val products by viewModel.products.collectAsState()
    val message by viewModel.message.collectAsState()
    
    var selectedTab by remember { mutableStateOf(ProductManagementTab.ADD_PRODUCT) }
    
    LaunchedEffect(message) {
        message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Tab切换
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == ProductManagementTab.ADD_PRODUCT,
                onClick = { selectedTab = ProductManagementTab.ADD_PRODUCT },
                text = { Text("添加商品") }
            )
            Tab(
                selected = selectedTab == ProductManagementTab.MODIFY_PRODUCT,
                onClick = { selectedTab = ProductManagementTab.MODIFY_PRODUCT },
                text = { Text("商品修改") }
            )
//            Tab(
//                selected = selectedTab == ProductManagementTab.PRODUCT_LIST,
//                onClick = { selectedTab = ProductManagementTab.PRODUCT_LIST },
//                text = { Text("商品列表") }
//            )
            Tab(
                selected = selectedTab == ProductManagementTab.IMPORT_DB,
                onClick = { selectedTab = ProductManagementTab.IMPORT_DB },
                text = { Text("数据库操作") }
            )
        }
        
        // 消息提示
        message?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        
        // 根据选中的Tab显示对应界面
        when (selectedTab) {
            ProductManagementTab.ADD_PRODUCT -> {
                AddProductScreen(viewModel = viewModel)
            }
            ProductManagementTab.MODIFY_PRODUCT -> {
                ModifyProductScreen(viewModel = viewModel, products = products)
            }
//            ProductManagementTab.PRODUCT_LIST -> {
//                ProductListScreen(products = products)
//            }
            ProductManagementTab.IMPORT_DB -> {
                ImportDatabaseScreen(viewModel = viewModel)
            }
        }
    }
}

// 添加商品界面
@Composable
fun AddProductScreen(
    viewModel: ProductViewModel,
    modifier: Modifier = Modifier
) {
    var barcode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    var pricewhole by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "扫描条形码",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it },
                        label = { Text("条形码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onNext = {
                                keyboardController?.hide()
                            }
                        )
                    )
                    
                    Text(
                        text = "提示: 使用扫码器扫描条形码，或手动输入条形码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "商品信息",
                    style = MaterialTheme.typography.titleLarge
                )
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品名") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("一个的价格") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pricewhole,
                    onValueChange = { pricewhole = it },
                    label = { Text("整件的价格") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.addOrUpdateProduct("0"+barcode, name+"整件", pricewhole,onSuccess = {

                            })
                            viewModel.addOrUpdateProduct(barcode, name, price,onSuccess = {
                                // 成功时清空输入框
                                barcode = ""
                                name = ""
                                price = ""
                                pricewhole=""

                            })
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存商品")
                    }
                    
                    OutlinedButton(
                        onClick = {
                            barcode = ""
                            name = ""
                            price = ""
                            pricewhole=""
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清空")
                    }
                }
            }
        }
    }
}

// 商品修改界面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModifyProductScreen(
    viewModel: ProductViewModel,
    products: List<Product>,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    
    var editBarcode by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableStateOf(0) }
    val filteredProducts = remember(searchQuery,refreshTrigger) {
        viewModel.searchProductsByName(searchQuery)
    }
    
    LaunchedEffect(selectedProduct) {
        selectedProduct?.let { product ->
            editBarcode = product.barcode
            editName = product.name
            editPrice = product.price.toString()
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 搜索框
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "搜索商品",
                    style = MaterialTheme.typography.titleLarge
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        selectedProduct = null
                    },
                    label = { Text("输入商品名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
        
        // 商品选择列表
        if (searchQuery.isNotBlank()) {
            if (filteredProducts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未找到商品")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredProducts) { product ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedProduct = product }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = product.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "条形码: ${product.barcode}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = PriceFormatter.format(product.price),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("请输入商品名称进行搜索")
            }
        }
        
        // 修改表单
        if (selectedProduct != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "修改商品信息",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    OutlinedTextField(
                        value = editBarcode,
                        onValueChange = { },
                        label = { Text("条形码") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false
                    )
                    
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("商品名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = editPrice,
                        onValueChange = { editPrice = it },
                        label = { Text("价格") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (editBarcode.isNotBlank()) {
                                    viewModel.updateProduct(editBarcode, editName, editPrice)

                                    refreshTrigger++
                                    selectedProduct = null

                                    editBarcode = ""
                                    editName = ""
                                    editPrice = ""
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存修改")
                        }
                        
                        OutlinedButton(
                            onClick = {
                                selectedProduct = null
                                editBarcode = ""
                                editName = ""
                                editPrice = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
}

// 商品列表界面
@Composable
fun ProductListScreen(
    products: List<Product>,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredProducts = remember(products, searchQuery) {
        if (searchQuery.isBlank()) {
            products
        } else {
            products.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.barcode.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 搜索框
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "搜索商品",
                    style = MaterialTheme.typography.titleLarge
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("输入商品名称或条形码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
        
        // 商品列表
        if (filteredProducts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无商品")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredProducts) { product ->
                    ProductItem(product = product)
                }
            }
        }
    }
}

@Composable
fun ProductItem(product: Product) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "条形码: ${product.barcode}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = PriceFormatter.format(product.price),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// 导入数据库界面
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun ImportDatabaseScreen(
    viewModel: ProductViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isImporting by viewModel.isImporting.collectAsState()
    val isSharing by viewModel.isSharing.collectAsState()
    val isRequesting by viewModel.isRequesting.collectAsState()
    val shareStatus by viewModel.shareStatus.collectAsState()
    val localIpAddress by viewModel.localIpAddress.collectAsState()
    val targetIpAddress by viewModel.targetIpAddress.collectAsState()

    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var ipInput by remember { mutableStateOf("") }

    // 初始化 DatabaseImporter 和 DataShareService
    LaunchedEffect(Unit) {
        viewModel.initializeDatabaseImporter(context)
        viewModel.initializeDataShareService(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // 获取文件名
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "未知文件"
            selectedFileName = fileName

            // 执行导入
            viewModel.importDatabaseFromUri(it)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),

        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // 选择文件按钮
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting
                ) {
                    Text(if (selectedFileName != null) "重新选择文件: $selectedFileName" else "选择数据库文件")
                }

                selectedFileName?.let { fileName ->
                    Text(
                        text = "已选择: $fileName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting
                ) {
                    Text("浏览文件")
                }

                OutlinedButton(
                    onClick = {
                        viewModel.exportDatabaseToDownloads()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting
                ) {
                    Text("保存数据库文件")
                }

            }
        }

        // 数据共享功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "数据共享",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "通过热点与另一台设备同步数据库。确保两台设备已连接到同一热点。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 本机IP显示
                if (localIpAddress.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = "本机IP地址: $localIpAddress",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            )
                    }
                }

                // 数据共享开关按钮
                Button(
                    onClick = { viewModel.toggleSharing() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSharing)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isSharing) "关闭数据共享" else "打开数据共享")
                }

                // 状态显示
                if (shareStatus.isNotBlank()) {
                    Text(
                        text = shareStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSharing)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 请求共享数据功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "请求共享数据",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "向已开启数据共享的设备请求数据库。如果没有响应，5秒后自动重试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 目标IP输入
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = {
                        ipInput = it
                        viewModel.setTargetIpAddress(it)
                    },
                    label = { Text("对方IP地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isRequesting
                )

                // 请求按钮
                Button(
                    onClick = {
                        viewModel.setTargetIpAddress(ipInput)
                        viewModel.toggleRequesting()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRequesting)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary
                    ),
                    enabled = !isSharing || ipInput.isNotBlank()
                ) {
                    Text(if (isRequesting) "取消请求" else "请求共享数据")
                }

                // 请求状态显示
                if (isRequesting) {
                    Text(
                        text = "正在等待对方响应...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // 导入状态
        if (isImporting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在导入数据库...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
