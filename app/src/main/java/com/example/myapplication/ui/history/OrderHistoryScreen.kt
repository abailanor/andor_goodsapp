package com.example.myapplication.ui.history

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.OrderHistoryFile
import com.example.myapplication.data.OrderHistoryRecord
import com.example.myapplication.data.OrderHistoryStorage
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.Product
import com.example.myapplication.ui.print.PrintService
import com.example.myapplication.ui.print.PrintUtil
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期选择层级
 */
sealed class DateSelectionLevel {
    object Years : DateSelectionLevel()
    data class YearSelected(val year: String) : DateSelectionLevel()
    data class MonthSelected(val year: String, val month: String) : DateSelectionLevel()
    data class DaySelected(val year: String, val month: String, val day: String) : DateSelectionLevel()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderHistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val storage = remember { OrderHistoryStorage(context) }

    var selectedOrder by remember { mutableStateOf<OrderHistoryFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // 日期选择器状态
    var dateLevel by remember { mutableStateOf<DateSelectionLevel>(DateSelectionLevel.Years) }
    var years by remember { mutableStateOf<List<String>>(emptyList()) }
    var months by remember { mutableStateOf<List<String>>(emptyList()) }
    var daysWithCount by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var currentOrders by remember { mutableStateOf<List<OrderHistoryFile>>(emptyList()) }

    // 控制日期选择器展开
    var isDatePickerExpanded by remember { mutableStateOf(true) }

    // 懒加载年份
    LaunchedEffect(Unit) {
        years = storage.getYearsWithOrders()
    }

    // 当年份选择变化时，懒加载月份
    LaunchedEffect(dateLevel) {
        when (val level = dateLevel) {
            is DateSelectionLevel.Years -> {
                years = storage.getYearsWithOrders()
            }
            is DateSelectionLevel.YearSelected -> {
                months = storage.getMonthsWithOrders(level.year)
            }
            is DateSelectionLevel.MonthSelected -> {
                daysWithCount = storage.getDaysWithOrders(level.year, level.month)
            }
            is DateSelectionLevel.DaySelected -> {
                selectedDate = "${level.year}-${level.month}-${level.day}"
                currentOrders = storage.getOrdersByDate(selectedDate!!)
            }
        }
    }

    // 加载最近7天有订单的日期（默认视图）
    LaunchedEffect(Unit) {
        val recentDates = storage.getRecentDaysWithOrders(7)
        if (recentDates.isNotEmpty()) {
            selectedDate = recentDates.first()
            currentOrders = storage.getOrdersByDate(selectedDate!!)
        }
    }

    // 自动消失的消息效果
    LaunchedEffect(message) {
        message?.let {
            delay(5000L)
            message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史订单") },
                actions = {
                    IconButton(onClick = {
                        // 重置到默认视图
                        dateLevel = DateSelectionLevel.Years
                        val recentDates = storage.getRecentDaysWithOrders(7)
                        if (recentDates.isNotEmpty()) {
                            selectedDate = recentDates.first()
                            currentOrders = storage.getOrdersByDate(selectedDate!!)
                        }
                        message = "已刷新"
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 消息显示（5秒自动消失）
                AnimatedVisibility(
                visible = message != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                message?.let { msg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 日期层级选择器
            AnimatedVisibility(
                visible = isDatePickerExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 顶部导航栏
                        DatePickerNavigationBar(
                            dateLevel = dateLevel,
                            onBack = {
                                when (val level = dateLevel) {
                                    is DateSelectionLevel.Years -> {
                                        isDatePickerExpanded = false
                                    }
                                    is DateSelectionLevel.YearSelected -> {
                                        dateLevel = DateSelectionLevel.Years
                                    }
                                    is DateSelectionLevel.MonthSelected -> {
                                        dateLevel = DateSelectionLevel.YearSelected(level.year)
                                    }
                                    is DateSelectionLevel.DaySelected -> {
                                        dateLevel = DateSelectionLevel.MonthSelected(level.year, level.month)
                                    }
                                }
                            },
                            onToggleExpand = { isDatePickerExpanded = !isDatePickerExpanded }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 年份选择
                        AnimatedContent(
                            targetState = dateLevel,
                            transitionSpec = {
                                if (targetState is DateSelectionLevel.Years) {
                                    slideInHorizontally { -it } + fadeIn() togetherWith
                                            slideOutHorizontally { -it } + fadeOut()
                                } else {
                                    slideInHorizontally { it } + fadeIn() togetherWith
                                            slideOutHorizontally { it } + fadeOut()
                                }
                            },
                            label = "year_selector"
                        ) { level ->
                            when (level) {
                                is DateSelectionLevel.Years -> {
                                    YearSelector(
                                        years = years,
                                        selectedYear = null,
                                        onYearSelected = { year ->
                                            dateLevel = DateSelectionLevel.YearSelected(year)
                                        }
                                    )
                                }
                                is DateSelectionLevel.YearSelected -> {
                                    MonthSelector(
                                        year = level.year,
                                        months = months,
                                        onMonthSelected = { month ->
                                            dateLevel = DateSelectionLevel.MonthSelected(level.year, month)
                                        }
                                    )
                                }
                                is DateSelectionLevel.MonthSelected -> {
                                    DayCalendar(
                                        year = level.year,
                                        month = level.month,
                                        daysWithCount = daysWithCount,
                                        onDaySelected = { day ->
                                            dateLevel = DateSelectionLevel.DaySelected(level.year, level.month, day)
                                        }
                                    )
                                }
                                is DateSelectionLevel.DaySelected -> {
                                    // 已选择日期，显示订单列表
                                }
                            }
                        }
                    }
                }
            }

            // 当前选中的日期显示
            selectedDate?.let { date ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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
                            text = "查看: $date",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (isDatePickerExpanded) {
                            IconButton(onClick = { isDatePickerExpanded = false }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "收起")
                            }
                        } else {
                            IconButton(onClick = { isDatePickerExpanded = true }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开")
                            }
                        }
                    }
                }
            }

            // 订单列表
            if (currentOrders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = if (selectedDate == null) "选择日期查看订单" else "该日期暂无订单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentOrders) { orderFile ->
                        OrderItemCard(
                            orderFile = orderFile,
                            onClick = { selectedOrder = orderFile }
                        )
                    }
                }
            }

            // 订单详情底部弹窗
            selectedOrder?.let { order ->
                BottomSheetDialog(
                    orderFile = order,
                    onDismissRequest = { /* 移除外部点击关闭 */ },
                    onClose = { selectedOrder = null },
                    onDelete = {
                        storage.delete(order.file)
                        selectedOrder = null
                        selectedDate?.let { date ->
                            currentOrders = storage.getOrdersByDate(date)
                        }
                        message = "订单已删除"
                    },
                    onSave = { updatedRecord ->
                        storage.update(order.file, updatedRecord)
                        selectedOrder = selectedOrder?.copy(record = updatedRecord)
                        selectedDate?.let { date ->
                            currentOrders = storage.getOrdersByDate(date)
                        }
                        message = "订单已更新"
                    },
                    onPrint = {
                        try {
                            val receipt = PrintUtil.generateReceipt(
                                order.record.items.map { item ->
                                    OrderItem(
                                        product = Product(
                                            name = item.name,
                                            barcode = item.barcode,
                                            price = item.price.toBigDecimalOrNull() ?: BigDecimal.ZERO
                                        ),
                                        quantity = item.quantity
                                    )
                                },
                                order.record.totalPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            )
                            PrintService.printReceipt(context, receipt)
                            message = "正在打印..."
                        } catch (e: Exception) {
                            message = "打印失败: ${e.message}"
                        }
                    }
                )
                isDatePickerExpanded=false
            }
        }
    }
}

@Composable
private fun DatePickerNavigationBar(
    dateLevel: DateSelectionLevel,
    onBack: () -> Unit,
    onToggleExpand: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 返回按钮（只在不是最顶层年份选择时显示）
            if (dateLevel !is DateSelectionLevel.Years) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 标题
            Text(
                text = when (dateLevel) {
                    is DateSelectionLevel.Years -> "选择年份"
                    is DateSelectionLevel.YearSelected -> "${dateLevel.year}年"
                    is DateSelectionLevel.MonthSelected -> "选择月份"
                    is DateSelectionLevel.DaySelected -> "选择日期"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "收起",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun YearSelector(
    years: List<String>,
    selectedYear: String?,
    onYearSelected: (String) -> Unit
) {
    if (years.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无历史订单",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            years.forEach { year ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onYearSelected(year) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedYear == year)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
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
                            text = "${year}年",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    year: String,
    months: List<String>,
    onMonthSelected: (String) -> Unit
) {
    val monthNames = mapOf(
        "01" to "一月", "02" to "二月", "03" to "三月", "04" to "四月",
        "05" to "五月", "06" to "六月", "07" to "七月", "08" to "八月",
        "09" to "九月", "10" to "十月", "11" to "十一月", "12" to "十二月"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$year 年",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )

        months.forEach { month ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMonthSelected(month) },
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
                        text = monthNames[month] ?: "${month}月",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCalendar(
    year: String,
    month: String,
    daysWithCount: List<Pair<String, Int>>,
    onDaySelected: (String) -> Unit
) {
    Column {
        Text(
            text = "$year 年${month.toInt()}月",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 日历网格（一行7天）
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 星期标题
            item {
                Text(
                    text = "日",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "一",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "二",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "三",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "四",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "五",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    text = "六",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 计算当月第一天是星期几
            val calendar = Calendar.getInstance()
            calendar.set(year.toInt(), month.toInt() - 1, 1)
            val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday

            // 获取当月天数
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

            // 空白天数（第一天之前的格子）
            repeat(firstDayOfWeek) {
                item {
                    Spacer(modifier = Modifier.aspectRatio(1f))
                }
            }

            // 所有天数
            val allDays = (1..daysInMonth).map { day ->
                val dayStr = day.toString().padStart(2, '0')
                val count = daysWithCount.find { it.first == dayStr }?.second ?: 0
                dayStr to count
            }

            items(allDays) { (day, count) ->
                DayCell(
                    day = day,
                    count = count,
                    onClick = { onDaySelected(day) }
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    day: String,
    count: Int,
    onClick: () -> Unit
) {
    val hasOrders = count > 0

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hasOrders -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = day.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasOrders)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                if (hasOrders) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderItemCard(
    orderFile: OrderHistoryFile,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = dateFormat.format(Date(orderFile.record.createdAt)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${orderFile.record.items.size} 件商品",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "¥${orderFile.record.totalPrice}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // 显示前几个商品名称作为预览
            if (orderFile.record.items.isNotEmpty()) {
                val previewText = orderFile.record.items
                    .take(2)
                    .joinToString("、") { "${it.name} ×${it.quantity}" }

                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (orderFile.record.items.size > 2) {
                    Text(
                        text = "...等${orderFile.record.items.size - 2}件商品",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun OrderDetailBottomSheet(
    orderFile: OrderHistoryFile,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onSave: (OrderHistoryRecord) -> Unit,
    onPrint: () -> Unit
) {
    var editedItems by remember {
        mutableStateOf(orderFile.record.items.map { it.copy() })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // 头部信息
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "订单详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(orderFile.record.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭详情")
            }
        }

        // 商品编辑列表（可滚动）
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(editedItems) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "¥${item.price}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ⚠ 只读。这里原来是一个可编辑的数量输入框 ——
                    // 但历史订单是**已经发生过**的交易，改它的数量等于改账：
                    // 小票已经打给顾客了、统计已经算进去了，而这里改完只动一个
                    // 本地 JSON 文件，两边从此对不上，且没有任何一处会报错。
                    // 要修正一笔旧单，正确的做法是取消它再开一张新的。
                    Text(
                        text = "×${item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(70.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            item() {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text("删除订单")
                    }

                    // 「保存修改」按钮已移除：数量改成只读之后没有任何可编辑的东西了。
                    // 留着它等于摆一个按下去什么都不发生的按钮，而用户会以为改成功了。
                }
                Button(
                    onClick = onPrint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打印订单")
                }
            }


        }


    }
}

@Composable
fun BottomSheetDialog(
    orderFile: OrderHistoryFile,
    onDismissRequest: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onSave: (OrderHistoryRecord) -> Unit,
    onPrint: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(1f)
                .background(
                    MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
        ) {
            // 顶部拖动指示器
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }

            // 订单详情内容（可滚动）
            OrderDetailBottomSheet(
                orderFile = orderFile,
                onClose = onClose,
                onDelete = onDelete,
                onSave = onSave,
                onPrint = onPrint
            )
        }
    }
}
