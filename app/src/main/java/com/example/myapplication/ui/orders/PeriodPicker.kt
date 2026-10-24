package com.example.myapplication.ui.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.OrderCalendar
import com.example.myapplication.data.OrderDay
import java.util.Calendar

/**
 * 时间范围选择器：**年 → 月 → 日** 三层，一层比一层窄。
 *
 * ## 为什么只列"真的有单的"年/月
 *
 * 用户原话是"只列出数据库里实际存在的年份/月份"。列全部年份的话，
 * 一个 2026 年开始用的店会显示 2020…2026 一长串，点进去全是空的 ——
 * 而"点进去是空的"与"数据丢了"在界面上无法区分。
 *
 * ## 最后一层是月历，不是日期列表
 *
 * 用户要的是"能一眼看出每天的开单情况"。列表做不到"一眼"：
 * 30 行数字要逐行读，而月历上"哪几天密、哪几天空、周末是不是比工作日忙"是**看**出来的。
 * 所以第三层是一张真正的月历，有单的格子标出笔数，点一天就把列表收窄到那一天。
 *
 * ## 这里没有用日历开源库，是有意的
 *
 * `com.kizitonwose.calendar` 那一类库基于 `java.time.YearMonth`，
 * 而本项目 `minSdk = 24` 且**没有开 core library desugaring** ——
 * 为一个选择器引入全构建的脱糖改造（APK 变大、构建变慢、影响所有模块）不划算。
 * 而库真正省掉的那部分（翻页动画、月份分页）这里恰恰不需要：
 * 月份是从上一层**点进来的**，不是滑出来的；每格里显示什么（笔数、金额）无论如何都得自己写。
 * 剩下要手写的就是一张 7 列的格子，见 [MonthGrid]。
 */
@Composable
fun PeriodPickerDialog(
    calendar: OrderCalendar,
    current: OrderPeriod,
    onPick: (OrderPeriod) -> Unit,
    onDismiss: () -> Unit,
) {
    // 三层各自的状态。⚠ 不合并成一个 enum + 参数：
    // "在哪一层"与"选到了哪个年/月"是两件事，混在一起会出现"回到年列表却还留着上次的月份"。
    var year by remember { mutableStateOf((current as? OrderPeriod.Month)?.year ?: (current as? OrderPeriod.Year)?.year) }
    var month by remember { mutableStateOf((current as? OrderPeriod.Month)?.month ?: (current as? OrderPeriod.Day)?.month) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                PickerHeader(
                    // 返回上一层而不是直接关：从月历退回来应该还在月份列表上，
                    // 而不是让用户从年份重新开始点。
                    title = when {
                        year == null -> "选择时间范围"
                        month == null -> "${year}年"
                        else -> "${year}年${month}月"
                    },
                    onBack = when {
                        year == null -> null
                        // ⚠ 必须先把内层置空再置空外层：`year = null` 之后 `month` 还留着旧值，
                        // 下一次点进别的年份会**直接跳到那个年份的同一个月**的月历上，
                        // 看起来像是点错了 —— 而用户点的是年份列表。
                        month == null -> ({ year = null })
                        else -> ({ month = null })
                    },
                    onClose = onDismiss,
                )
                HorizontalDivider()

                when {
                    year == null -> YearLevel(
                        calendar = calendar,
                        current = current,
                        onQuick = onPick,
                        onPickYear = { year = it },
                    )

                    month == null -> MonthLevel(
                        calendar = calendar,
                        year = year!!,
                        current = current,
                        onPickYear = { onPick(OrderPeriod.Year(it)) },
                        onPickMonth = { month = it },
                    )

                    else -> DayLevel(
                        calendar = calendar,
                        year = year!!,
                        month = month!!,
                        current = current,
                        onPickMonth = { onPick(OrderPeriod.Month(year!!, month!!)) },
                        onPickDay = { onPick(OrderPeriod.Day(year!!, month!!, it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一层")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
        }
    }
}

/** 第一层：快捷项 + **有单的**年份。 */
@Composable
private fun YearLevel(
    calendar: OrderCalendar,
    current: OrderPeriod,
    onQuick: (OrderPeriod) -> Unit,
    onPickYear: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current is OrderPeriod.Today,
                onClick = { onQuick(OrderPeriod.Today) },
                label = { Text("今天") },
            )
            FilterChip(
                selected = current is OrderPeriod.All,
                onClick = { onQuick(OrderPeriod.All) },
                label = { Text("全部") },
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        if (calendar.isEmpty) {
            Spacer(Modifier.height(16.dp))
            // ⚠ 必须说清楚"是还没查到"还是"真的一笔都没有"。
            // 只写"暂无数据"的话，用户在一个刚同步完的店上看到它会以为同步失败了。
            Text(
                "本地还没有任何订单，所以没有可选的年份。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(calendar.years, key = { it }) { y ->
                    val t = calendar.yearTotal(y)
                    PickerRow(
                        title = "${y}年",
                        subtitle = "${t.days} 天有单 · ${t.orders} 笔 · ${Money.fromCents(t.cents)}",
                        selected = current is OrderPeriod.Year && current.year == y,
                        onClick = { onPickYear(y) },
                    )
                }
            }
        }
    }
}

/** 第二层：某一年的**有单的**月份。 */
@Composable
private fun MonthLevel(
    calendar: OrderCalendar,
    year: Int,
    current: OrderPeriod,
    onPickYear: (Int) -> Unit,
    onPickMonth: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        TextButton(onClick = { onPickYear(year) }, modifier = Modifier.fillMaxWidth()) {
            Text("整年（$year）")
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        val months = calendar.monthsOf(year)
        if (months.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "$year 年没有订单。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(months, key = { it }) { m ->
                    val t = calendar.monthTotal(year, m)
                    PickerRow(
                        title = "${m}月",
                        subtitle = "${t.days} 天有单 · ${t.orders} 笔 · ${Money.fromCents(t.cents)}",
                        selected = current is OrderPeriod.Month && current.year == year && current.month == m,
                        onClick = { onPickMonth(m) },
                    )
                }
            }
        }
    }
}

/** 第三层：整月按钮 + 月历。 */
@Composable
private fun DayLevel(
    calendar: OrderCalendar,
    year: Int,
    month: Int,
    current: OrderPeriod,
    onPickMonth: () -> Unit,
    onPickDay: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        val t = calendar.monthTotal(year, month)
        TextButton(onClick = onPickMonth, modifier = Modifier.fillMaxWidth()) {
            Text("整月（${t.orders} 笔 · ${Money.fromCents(t.cents)}）")
        }
        Spacer(Modifier.height(8.dp))

        // 星期表头。⚠ 必须与 MonthGrid 用的是同一个"一周从哪天开始"，
        // 各自算一遍的话表头与格子会错开一列，而错开一列的日历看起来完全正常 ——
        // 只是每一天都标在了错误的星期上。
        val firstDayOfWeek = remember { Calendar.getInstance().firstDayOfWeek }
        Row(Modifier.fillMaxWidth()) {
            for (label in weekLabels(firstDayOfWeek)) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        MonthGrid(
            year = year,
            month = month,
            firstDayOfWeek = firstDayOfWeek,
            calendar = calendar,
            selected = (current as? OrderPeriod.Day)?.takeIf { it.year == year && it.month == month }?.day,
            onPickDay = onPickDay,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "灰格子 = 那天没有订单（点不动）。数字是当天的**销售**笔数，进货单另算。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一张月历。7 列，行数按该月实际需要的周数（4~6 行）。
 *
 * 用普通 `Column` + `Row` 而不是 `LazyVerticalGrid`：一屏最多 42 格，
 * 懒加载省不下什么，反而在对话框里要额外处理滚动嵌套。
 */
@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    firstDayOfWeek: Int,
    calendar: OrderCalendar,
    selected: Int?,
    onPickDay: (Int) -> Unit,
) {
    val days = remember(year, month) { calendar.daysOf(year, month) }
    val (leadingBlanks, daysInMonth) = remember(year, month, firstDayOfWeek) {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1, 0, 0, 0)
        // DAY_OF_WEEK 是 SUNDAY=1 … SATURDAY=7；换成"从 firstDayOfWeek 数起第几格"
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        var lead = dow - firstDayOfWeek
        if (lead < 0) lead += 7
        lead to cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    val cells = List(leadingBlanks) { null } + (1..daysInMonth).toList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(0.86f)) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                info = days[day],
                                selected = day == selected,
                                onClick = { onPickDay(day) },
                            )
                        }
                    }
                }
                // 最后一行不满 7 格时要补齐，否则 weight 会把那几格拉宽
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, info: OrderDay?, selected: Boolean, onClick: () -> Unit) {
    val hasOrders = info != null && !info.isEmpty
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        hasOrders -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Box(
        // ⚠ 没有单的格子**不给点击**：点下去列表会变空，而"空列表"与"这一天没开单"
        // 是同一个画面 —— 用户会以为筛选坏了。灰着不响应比响应一个空结果清楚。
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(bg, RoundedCornerShape(6.dp))
            .then(if (hasOrders) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$day",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasOrders || selected) fg else MaterialTheme.colorScheme.outline,
                fontWeight = if (hasOrders) FontWeight.Bold else FontWeight.Normal,
            )
            if (info != null && info.saleOrders > 0) {
                Text(
                    "${info.saleOrders}笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (info != null && info.purchaseOrders > 0) {
                // 进货单没有金额，所以这里显示的是笔数而不是钱 —— 与销售的"笔"区分开。
                Text(
                    "进${info.purchaseOrders}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (info != null && info.saleCancelled > 0) {
                // 这一天所有销售单都被撤了：仍然要点得进去，
                // 否则用户看到"我记得那天开过单"却在日历上找不到它。
                Box(
                    Modifier.padding(top = 2.dp).width(6.dp).height(6.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一周七列的表头，**从 [firstDayOfWeek] 开始**。
 *
 * `Calendar.SUNDAY = 1 … SATURDAY = 7`，所以要转成 0-based 再旋转。
 * 写死 `日一二三四五六` 的话，设备设成"周一为一周之始"（国内不少 ROM 的默认）就会错开一列。
 */
private fun weekLabels(firstDayOfWeek: Int): List<String> {
    val names = listOf("日", "一", "二", "三", "四", "五", "六")
    val start = (firstDayOfWeek - Calendar.SUNDAY).coerceIn(0, 6)
    return (0 until 7).map { names[(start + it) % 7] }
}
