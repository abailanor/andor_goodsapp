package com.example.myapplication.ui.pending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.data.PendingOrderFile
import com.example.myapplication.ui.CurrentOrderMode
import com.example.myapplication.ui.OrderMode
import com.example.myapplication.ui.orderModeOf
import com.example.myapplication.util.PriceFormatter


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingOrdersScreen(
    viewModel: PendingOrdersViewModel,
    onNavigateToOrder: () -> Unit
) {
    val pendingOrders by viewModel.pendingOrders.collectAsState()
    val mode by CurrentOrderMode.mode.collectAsState()
    val hasOtherMode by viewModel.hasOtherMode.collectAsState()
    val selectedOrder by viewModel.selectedOrder.collectAsState()
    val message by viewModel.message.collectAsState()
    val navigateToOrder by viewModel.navigateToOrder.collectAsState()

    // 导航到开单页面
    LaunchedEffect(navigateToOrder) {
        if (navigateToOrder) {
            viewModel.onNavigationComplete()
            onNavigateToOrder()
        }
    }

    // 显示消息
    LaunchedEffect(message) {
        message?.let {
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // ⚠ 标题带模式名：列表是过滤过的，用户必须知道"现在看到的是哪一种"。
                // 只靠底色不够 —— 待开单页上方还有一条模式切换条，两者要一致才对得上。
                title = { Text("待开单 · ${mode.label}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadPendingOrders() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (pendingOrders.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        // ⚠ 带上模式名：列表是**按当前模式过滤**的，
                        // 只说"暂无待开单"的话，用户在销售模式下看不到自己昨天挂的
                        // 进货单，会以为丢了。上面那条模式切换条就是出口。
                        text = "暂无${mode.label}待开单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (hasOtherMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "另一种模式的待开单还在，用上面的开关切过去看",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = pendingOrders,
                    key = { it.file.absolutePath }
                ) { pendingOrder ->
                    PendingOrderCard(
                        pendingOrderFile = pendingOrder,
                        onPreview = { viewModel.previewOrder(pendingOrder) },
                        onOpen = { viewModel.openOrder(pendingOrder) },
                        onDelete = { viewModel.deletePendingOrder(pendingOrder) },
                        formatTime = { viewModel.formatOrderTime(pendingOrder.record.createdAt) }
                    )
                }
            }
        }
    }

    // 预览弹窗
    selectedOrder?.let { order ->
        OrderPreviewDialog(
            orderItem = order,
            onDismiss = { viewModel.dismissPreview() },
            onOpen = {
                viewModel.dismissPreview()
                viewModel.openOrder(
                    PendingOrderFile(
                        file = order.file,
                        record = order.record
                    )
                )
            }
        )
    }
}

@Composable
fun PendingOrderCard(
    pendingOrderFile: PendingOrderFile,
    onPreview: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    formatTime: () -> String
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreview() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatTime(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "共 ${pendingOrderFile.record.items.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (orderModeOf(pendingOrderFile.record.modeOrSale) == OrderMode.PURCHASE) {
                    // ⚠ 进货单一律不显示金额，哪怕文件里存着数字（升级前存的那些）。
                    // 那个数是拿**卖出价**算出来的，而服务端根本不存进货价 ——
                    // 显示出来就是编的数字，还会让用户以为进货要付这么多钱。
                    Text(
                        text = "不计金额",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                if (orderModeOf(pendingOrderFile.record.modeOrSale) == OrderMode.PURCHASE) {
                    // ⚠ 进货单一律不显示金额，哪怕文件里存着数字（升级前存的那些就有）。
                    // 那个数是拿**卖出价**算的，而服务端根本不存进货价 ——
                    // 显示出来就是编的，还会让人以为进货要付这么多钱。
                    Text(
                        text = "不计金额",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = "¥${PriceFormatter.format(pendingOrderFile.record.totalPrice)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                // 开单按钮
                FilledTonalButton(
                    onClick = onOpen,
                    modifier = Modifier.padding(end = 6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开单", style = MaterialTheme.typography.labelMedium)
                }

                // 删除按钮
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这个待开单吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderPreviewDialog(
    orderItem: PendingOrderItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    // 进货单：全程不出现金额（理由同 PendingOrderCard 里那条注释）。
    val purchase = orderModeOf(orderItem.record.modeOrSale) == OrderMode.PURCHASE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏
                TopAppBar(
                    title = { Text(if (purchase) "进货单预览" else "订单预览") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = onOpen,
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("开单", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                // 订单信息
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = formatPreviewTime(orderItem.record.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // 商品列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = orderItem.orderItems,
                        key = { it.product.barcode }
                    ) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.product.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (purchase) "× ${item.quantity}"
                                        else "¥${PriceFormatter.format(item.product.price)} × ${item.quantity}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (!purchase) {
                                    Text(
                                        text = "¥${PriceFormatter.format(item.subtotal)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部总价
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (purchase) "共 ${orderItem.record.items.size} 项" else "合计",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (purchase) "不计金额"
                            else "¥${PriceFormatter.format(orderItem.record.totalPrice)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (purchase) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun formatPreviewTime(createdAt: Long): String {
    val date = java.util.Date(createdAt)
    val format = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
    return format.format(date)
}
