package com.example.myapplication.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.Product
import com.example.myapplication.ui.ai.AIViewModel
import com.example.myapplication.ui.print.PrintService
import com.example.myapplication.ui.print.PrintUtil
import com.example.myapplication.util.PriceFormatter
import java.math.BigDecimal
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import com.example.myapplication.ui.ai.OutputItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(
    viewModel: OrderViewModel,
    modifier: Modifier = Modifier,
    aiViewModel: AIViewModel? = null // 可选的 AI ViewModel
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val orderItems by viewModel.orderItems.collectAsState()
    val message by viewModel.message.collectAsState()
    val sourcePendingFileName by viewModel.sourcePendingFileName.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()

    // AI 预选输入

// 替换为：
    val aiPresetInputs by aiViewModel?.outputs?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<OutputItem>()) }
    var showAIPresetDialog by remember { mutableStateOf(false) }

    val totalPrice = remember(orderItems) {
        orderItems.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.subtotal)
        }
    }

    var barcodeInput by remember { mutableStateOf("") }


    val listState = rememberLazyListState()


    LaunchedEffect(message) {
        message?.let {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(searchResults) {
        if (searchResults.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .padding(bottom = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
//                        .windowInsetsPadding(WindowInsets.navigationBars),
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "总计",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = PriceFormatter.format(totalPrice),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearOrder() }
                        ) {
                            Text("清空")
                        }
                        // 保存按钮
                        OutlinedButton(
                            onClick = {
                                if (orderItems.isNotEmpty()) {
                                    viewModel.savePendingOrder()
                                }
                            },
                            enabled = orderItems.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("保存")
                        }
                        Button(
                            onClick = {
                                // 打印前保存一份历史订单（按年/月/日文件夹）
                                viewModel.saveOrderToHistory()
                                val receipt = PrintUtil.generateReceipt(orderItems, totalPrice)
                                PrintService.printReceipt(context, receipt)
                            },
                            enabled = orderItems.isNotEmpty()
                        ) {
                            Text("打印")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 如果是从待开单读取的，显示提示
            sourcePendingFileName?.let { fileName ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已加载待开单",
                            style = MaterialTheme.typography.bodySmall
                        )

                    }
                }
            }

            // 条形码输入区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {viewModel.setsound() }
                    ) {
                        Icon(
                            imageVector = if (soundEnabled)
                                Icons.Default.VolumeUp
                            else
                                Icons.Default.VolumeOff,
                            contentDescription = if (soundEnabled) "关闭声音" else "开启声音",
                            tint = if (soundEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }


                    OutlinedTextField(

                        value = barcodeInput,
                        onValueChange = {
                            barcodeInput = it
                            if (barcodeInput.isNotBlank()) {
                                viewModel.searchProducts(barcodeInput.trim())
                            } else {
                                viewModel.clearSearchResults()
                            }
                        },
                        label = { Text("条形码/商品名", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (barcodeInput.isNotBlank()) {
                                    viewModel.scanBarcode(barcodeInput.trim())
                                    barcodeInput = ""
                                    viewModel.clearSearchResults()
                                    keyboardController?.hide()
                                }
                            }
                        )
                    )
                    
                    // AI 预选按钮（如果 AI ViewModel 可用且有预选输入）
                    if (aiViewModel != null && aiPresetInputs.isNotEmpty()) {
                        IconButton(
                            onClick = { showAIPresetDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI 预选",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
//                    Button(
//                        onClick = {
//                            if (barcodeInput.isNotBlank()) {
//                                viewModel.scanBarcode(barcodeInput.trim())
//                                barcodeInput = ""
//                                viewModel.clearSearchResults()
//                            }
//                        },
//                        modifier = Modifier.height(48.dp)
//                    ) {
//                        Text("添加", style = MaterialTheme.typography.bodyMedium)
//                    }
                }
            }

            // 搜索结果显示区域
            if (searchResults.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {

                            Text(
                                text = "搜索结果（点击添加）：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                        state = listState
                            ) {
                                items(
                                    items = searchResults,
                                    key = { it.barcode }
                                ) { product ->
                                    SearchResultItem(
                                        product = product,
                                        onClick = {
                                            viewModel.addProductFromSearch(product)
                                            barcodeInput = ""
                                        }
                                    )
                                }
                            }

                    }
                }
            }

            // 消息提示
            message?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // 订单商品列表
            if (orderItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "订单为空",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "请扫描或输入条形码添加商品",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(orderItems) { item ->
                        OrderItemCard(
                            orderItem = item,
                            onQuantityChange = { newQuantity ->
                                viewModel.updateQuantity(item.product.barcode, newQuantity)
                            },
                            onRemove = {
                                viewModel.removeItem(item.product.barcode)
                            },
                            viewModel=viewModel
                        )
                    }
                }
            }
        }

        // AI 预选输入弹窗
        if (showAIPresetDialog && aiViewModel != null) {
            AlertDialog(
                onDismissRequest = { showAIPresetDialog = false },
                title = { Text("AI 预选输入 (${aiPresetInputs.size})") },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "点击选择后填入输入框",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { aiViewModel.clearoutput() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("清空", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val unselectedItems by remember(aiPresetInputs) {
                            derivedStateOf { aiPresetInputs.filter { !it.isSelected } }
                        }
                        val selectedItems by remember(aiPresetInputs) {
                            derivedStateOf { aiPresetInputs.filter { it.isSelected } }
                        }

                        // 找到OrderScreen中AI预选弹窗的LazyColumn部分，替换为以下代码
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 先展示未选中的项，再展示已选中的项


                            // 未选中的项
                            itemsIndexed(unselectedItems) { index, item ->
                                // 计算在原始列表中的真实索引
                                val originalIndex = aiPresetInputs.indexOfFirst { it.text == item.text }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // 选择并填入输入框
                                            val selected = aiViewModel.selectoutput(originalIndex)
                                            if (selected != null) {
                                                barcodeInput = selected
                                                viewModel.searchProducts(selected.trim())
                                                showAIPresetDialog = false
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable(
                                                    // 阻止事件冒泡到Card的click事件
                                                    onClick = {
                                                        aiViewModel.removeoutput(originalIndex)
                                                    }
                                                )
                                        )
                                    }
                                }
                            }

                            // 已选中的项（灰色显示）
                            itemsIndexed(selectedItems) { index, item ->
                                val originalIndex = aiPresetInputs.indexOfFirst { it.text == item.text }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val selected = aiViewModel.selectoutput(originalIndex)
                                            if (selected != null) {
                                                barcodeInput = selected
                                                viewModel.searchProducts(selected.trim())
                                                showAIPresetDialog = false
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        // 选中项使用更浅的灰色
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            // 文字也变灰
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除",
                                            // 删除图标也变灰
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    aiViewModel.removeoutput(originalIndex)
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAIPresetDialog = false }) {
                        Text("关闭")
                    }
                }
            )
        }

    }
}
@Composable
fun CompactQuantityInput(
    quantity: Int,
    onQuantityChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(quantity) { mutableStateOf(quantity.toString()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(quantity) {
        text = quantity.toString()
    }

    Box(
        modifier = modifier
            .width(44.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(3.dp)
            )
            .clickable { focusRequester.requestFocus() }
    ) {
        BasicTextField(
            value = text,
            onValueChange = { newText ->
                // 只允许数字，限制长度
                val filtered = newText.filter { it.isDigit() }.take(4)
                text = filtered
                filtered.toIntOrNull()?.let { num ->
                    if (num in 1..10000) onQuantityChange(num)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .padding(horizontal = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = VisualTransformation.None
        )
    }
}

@Composable
fun SearchResultItem(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "条码: ${product.barcode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = "¥${PriceFormatter.format(product.price)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
@Composable
fun OrderItemCard(
    orderItem: OrderItem,
    onQuantityChange: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderViewModel
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 添加状态控制弹窗显示
            var showConfirmDialog by remember { mutableStateOf(false) }

            IconButton(
                onClick = { showConfirmDialog = true }, // 点击时显示确认弹窗
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }

// 添加确认弹窗
            if (showConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showConfirmDialog = false },
                    title = { Text(text = "确认删除") },
                    text = { Text(text = "您确定要删除此项吗？此操作不可撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showConfirmDialog = false
                                onRemove() // 用户确认后执行删除操作
                            }
                        ) {
                            Text("确认", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showConfirmDialog = false }
                        ) {
                            Text("取消")
                        }
                    }
                )
            }
            // 商品名
            Text(
                text = orderItem.product.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 使用紧凑输入框
            CompactQuantityInput(
                quantity = orderItem.quantity,
                onQuantityChange = onQuantityChange,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
                if (orderItem.product.barcode[0] == '0') {
                    Button(
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .height(28.dp) ,
                        onClick = {
                            viewModel.scanBarcode(orderItem.product.barcode.substring(1))
                            onRemove()
                        }

                    ) {
                        Text(text="件",fontSize = 12.sp,)
                    }
                } else {
                    Button(
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .height(28.dp),
                        onClick = {
                            viewModel.scanBarcode("0" + orderItem.product.barcode)
                            onRemove()
                        }
                    ) {
                        Text(text="个",fontSize = 12.sp,)
                    }
                }


            // 计算过程
            Text(
                text = "${PriceFormatter.format(orderItem.product.price)} × ${orderItem.quantity} = ${
                    PriceFormatter.format(
                        orderItem.subtotal
                    )
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),

            )
        }
    }
}
