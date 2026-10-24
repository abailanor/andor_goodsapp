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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.CartLine
import com.example.myapplication.data.CartPricing
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.Product
import com.example.myapplication.ui.OrderMode
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
    aiViewModel: AIViewModel? = null, // 可选的 AI ViewModel
    /**
     * 小票抬头。来自 `AppSettings.printShopName/Phone/Address`。
     *
     * ⚠ 原来是硬编码在 PrintUtil 里的（`电话：15288180414`、`地址：官渡区新广丰B区14-16`）——
     * 那是某一家店的信息，装在别的店手机上就会印出别人家的电话。
     */
    printShopName: String = "",
    printShopPhone: String = "",
    printShopAddress: String = "",
    /**
     * 本店用不用整箱（`AppSettings.enableBoxSelling`）。
     *
     * ⚠ 必须是**每次重组都重新读**的值，不能 remember 住：用户在设置里改完切回来，
     * 这一页要立刻按新设置显示或隐藏「件」输入框。
     */
    enableBoxSelling: Boolean = true,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val orderItems by viewModel.orderItems.collectAsState()
    val message by viewModel.message.collectAsState()
    val sourcePendingFileName by viewModel.sourcePendingFileName.collectAsState(initial = null)
    val searchResults by viewModel.searchResults.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val checkout by viewModel.checkout.collectAsState()
    val manualTotalText by viewModel.manualTotalText.collectAsState()
    val manualTotalInvalid = viewModel.manualTotalInvalid

    // AI 预选输入

// 替换为：
    val aiPresetInputs by aiViewModel?.outputs?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<OutputItem>()) }
    var showAIPresetDialog by remember { mutableStateOf(false) }

    // ⚠ 用 ViewModel 的 totalPrice，不要在界面里自己 `Σ(price × quantity)`：
    // 整箱价 ≠ 箱规 × 单价，那样算 30 个会得到 105.00 而实收 101.00 ——
    // 总计、实收框、行内金额、结算框、小票必须是同一个来源，
    // 否则顾客当场就会发现对不上。
    val totalPrice = remember(orderItems) { viewModel.totalPrice }
    val totalCents = remember(orderItems) { viewModel.totalCents }
    /** 两个框都空的行是编辑中间态，不参与金额与件数。 */
    val validLines = remember(orderItems) { orderItems.filterNot { it.isEmpty } }

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
                // 底部分两行：第一行是"总计 + 结算"（结账时唯一要看的两样东西），
                // 第二行是三个次级动作。
                //
                // ⚠ 不要挤回一行。这一栏在窄屏上本来就排满了（当初"保存"前面那个
                // 18dp 图标就是因此被删掉的），再加一个"结算"必然把文字截断或换行，
                // 而收银台是站着用的、屏幕还常常是碎的。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mode == OrderMode.SALE) {
                            // 计算值 + 可改的框并排。框里实时跟着计算值走，
                            // 用户一旦动手改就变成手工总额（抹零），改完还能一键还原。
                            Column {
                                Text(text = "总计", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = PriceFormatter.format(totalPrice),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                // null = 没改过 → 显示实时计算值
                                value = manualTotalText ?: PriceFormatter.format(totalPrice),
                                onValueChange = { viewModel.setManualTotal(it) },
                                label = { Text("实收") },
                                supportingText = {
                                    when {
                                        manualTotalInvalid -> Text(
                                            "格式不对",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        manualTotalText != null -> Text("点右侧还原")
                                        else -> Text("可改，用于抹零")
                                    }
                                },
                                isError = manualTotalInvalid,
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                                ),
                                trailingIcon = {
                                    if (manualTotalText != null) {
                                        TextButton(onClick = { viewModel.resetManualTotal() }) { Text("还原") }
                                    }
                                },
                                modifier = Modifier.width(130.dp)
                            )
                        } else {
                            // 进货模式**不显示任何金额**：服务端根本不存储买入价，
                            // 本地目录里的 price 是卖出价 —— 拿它算进货总额是编数字。
                            Column {
                                Text(text = "进货单", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = "${orderItems.size} 个品种（不计金额）",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // ⚠ 叫「上传」不叫「结算」：按下它这一单就进 outbox 并**立刻尝试上传**，
                        // 用户必须知道钱已经离开这台设备了。叫"结算"的话，
                        // 离线时用户会以为"只是本地记一笔"，而实际上它排队等着上传。
                        Button(
                            onClick = { viewModel.startCheckout() },
                            enabled = orderItems.isNotEmpty() && !manualTotalInvalid
                        ) {
                            Text(if (mode == OrderMode.SALE) "上传" else "上传进货单")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearOrder() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清空")
                        }
                        // "挂起"就是原来的"保存"：存进待开单。改名是因为"保存"在
                        // 有了结算之后含义不清 —— 用户会以为"保存"等于"这单成了"。
                        OutlinedButton(
                            onClick = { viewModel.savePendingOrder() },
                            enabled = orderItems.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("挂起")
                        }
                        OutlinedButton(
                            onClick = {
                                // ⚠ 这里**不再**调 saveOrderToHistory()。
                                // 它往 filesDir/Orders/ 下写 JSON，那是迁移前的旧存储；
                                // 新订单已经在「上传」时进了 order_outbox → order_index。
                                // 两边都写的后果是同一笔单在旧历史页和新订单页各出现一次，
                                // 而用户会以为开了两单。旧历史页从此只显示迁移前的老数据。
                                val receipt = PrintUtil.generateReceipt(
                                    lines = validLines,
                                    manualTotalCents = viewModel.manualTotalCents,
                                    // 进货单不印任何金额：系统里没有买入价，
                                    // 本地目录的 price 是卖出价，印出来会被当成成本记账。
                                    purchase = mode == OrderMode.PURCHASE,
                                    shopName = printShopName,
                                    shopPhone = printShopPhone,
                                    shopAddress = printShopAddress,
                                )
                                PrintService.printReceipt(context, receipt)
                            },
                            enabled = orderItems.isNotEmpty(),
                            modifier = Modifier.weight(1f)
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
                    items(orderItems, key = { it.product.barcode }) { item ->
                        OrderItemCard(
                            line = item,
                            onBoxesChange = { viewModel.updateBoxes(item.product.barcode, it) },
                            onSinglesChange = { viewModel.updateSingles(item.product.barcode, it) },
                            onRemove = { viewModel.removeItem(item.product.barcode) },
                            mode = mode,
                            enableBoxSelling = enableBoxSelling,
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

        // ==================== 结算 ====================

        when (val step = checkout) {
            is CheckoutStep.NeedBoxSize -> BoxSizeDialog(
                missing = step.lines,
                // 补箱规要改商品，而改商品需要 PRICE_CHANGE 权限。
                // B 级员工默认只有 SYNC_SUB + STOCK_CHANGE，他补不了 —— 只能整单挂起。
                // 这一点必须写在界面上，否则员工会当成 bug 报。
                canEditBoxSize = true,
                onFill = { barcode, size -> viewModel.fillBoxSize(barcode, size) },
                onBlock = { viewModel.blockOrder() },
                onDismiss = { viewModel.dismissCheckout() },
            )

            is CheckoutStep.Ready -> CheckoutDialog(
                lines = step.lines,
                writer = viewModel.defaultWriter(),
                manualTotalCents = viewModel.manualTotalCents,
                isPurchase = mode == OrderMode.PURCHASE,
                // 设了整箱价却没箱规的商品会被按散卖计价 —— 整箱价被忽略、多收顾客的钱，
                // 而票面自洽看不出来。所以必须在上传前说出来，不能等到打完小票。
                ambiguousCount = step.ambiguous.size,
                onConfirm = { remark -> viewModel.confirmCheckout(remark) },
                onDismiss = { viewModel.dismissCheckout() },
            )

            CheckoutStep.Idle -> Unit
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
    line: CartLine,
    /** 改「件」（整箱数）。 */
    onBoxesChange: (Int) -> Unit,
    /** 改「个」（散卖数）。 */
    onSinglesChange: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** 进货模式下这一行**不显示任何金额**，见下面的注释。 */
    mode: OrderMode = OrderMode.SALE,
    /**
     * 本店用不用整箱（`AppSettings.enableBoxSelling`）。
     *
     * 关着就只显示「个」一个框。⚠ 进货模式**一律**不显示「件」框：
     * 进货的最小单位就是"个"，而且系统里没有买入价，按箱算不出任何东西。
     */
    enableBoxSelling: Boolean = true,
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
                text = line.product.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 「件」+「个」两个框。
            //
            // ⚠ 两个条件都要满足才显示「件」：
            //  1. 全局设置开着整箱（`AppSettings.enableBoxSelling`）—— 大多数店根本不按整箱卖，
            //     多一个用不上的框就是在抢收银台上按秒计算的横向空间；
            //  2. 这个商品**确实**有整箱价（`boxPrice != null`）—— 摆一个填了也没用的框，
            //     用户会以为整箱价生效了，而实际按散卖算。
            if (enableBoxSelling && mode == OrderMode.SALE && line.product.boxPrice != null) {
                CountField(value = line.boxes, label = "件", onChange = onBoxesChange)
            }
            CountField(value = line.singles, label = "个", onChange = onSinglesChange)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
                // ⚠ 这里原来有一个"件/个"切换按钮，点它去扫 `"0"+barcode` 或
                // `barcode.substring(1)`。那是 v3 的约定（整件靠额外插一行 0 前缀表示），
                // 而 MIGRATION_3_4 / BoxRowMerger 的作用正是把那两行**合并成一条** ——
                // 合并之后 `0<barcode>` 这个商品已经不存在了，所以那个按钮点了必然
                // 报"未找到商品"。箱/个的拆分现在由 CartPricing.fromTotalQuantity
                // 按箱规自动做（数了 30 个就输 30），不需要用户手动切。

            // 计算过程。
            // ⚠ 用 CartLine 的金额，**不是** `单价 × 数量`：整箱价 ≠ 箱规 × 单价，
            // 后者在有整箱参与时是错的 —— 30 个会显示 3.50 × 30 = 105.00，
            // 而实际收 1 箱(80) + 6 个(21) = 101.00。行内显示的数与总计、实收框、
            // 结算框、小票必须是同一个来源，否则顾客当场就会发现对不上。
            if (mode == OrderMode.PURCHASE) {
                // 进货模式：不显示任何金额。本地目录里的 price 是**卖出价**，
                // 摆在进货单上会被当成成本价看，而系统里根本没有买入价。
                Text(
                    text = "${line.quantity ?: 0} 件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                Text(
                    text = buildString {
                        append(Money.fromCents(line.unitPriceCents)).append("/个")
                        if (line.boxes > 0) {
                            append(" · ").append(line.boxes).append("件")
                            if (line.singles > 0) append(" + ").append(line.singles).append("个")
                            append(" = ")
                        } else {
                            append(" × ").append(line.singles).append(" = ")
                        }
                        append(Money.fromCents(line.amountCents))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * 「件」/「个」的紧凑数字输入框。
 *
 * ## ⚠ 显示的是**文本**，不是 `value.toString()`
 *
 * 直接用 `value.toString()` 的话，用户清空输入框的瞬间 `value` 变成 0，
 * 框里立刻跳出一个 "0" —— 光标还在里面，但已经没法把它删掉了，
 * 只能选中再覆盖。用本地文本状态就能真的清空。
 *
 * 空文本按 0 处理（"无输入就是默认 0"），所以 [onChange] 收到的一直是有效整数。
 */
@Composable
private fun CountField(value: Int, label: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            // 只收数字，空文本按 0 处理（"无输入就是默认 0"）
            val digits = raw.filter { it.isDigit() }
            text = digits
            onChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = { Text("0", style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .width(62.dp)
            .padding(horizontal = 2.dp),
    )
}
