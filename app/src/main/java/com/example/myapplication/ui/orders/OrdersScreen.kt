package com.example.myapplication.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.DriftedOrder
import com.example.myapplication.data.OrderRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订单页：已同步与未上传的**合并视图**。
 *
 * ## 用户要的三件事都落在这一页
 *
 *  1. "本地存在未同步数据时弹窗询问是否同步" → 顶部的汇总条 + 「上传选中的」按钮；
 *  2. "可以选择其中一部分进行同步" → 每行的勾选框；
 *  3. "其余提供删除选项" → 每行的「删除」。
 *
 * ⚠ **不做成模态弹窗**：店里正在结账时弹一个必须回答的框是最糟的时机。
 * 未上传的单一直在列表里显示着，用户什么时候想处理就什么时候处理。
 */
@Composable
fun OrdersScreen(viewModel: OrdersViewModel) {
    val rows by viewModel.rows.collectAsState()
    val range by viewModel.range.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val report by viewModel.report.collectAsState()
    val drift by viewModel.drift.collectAsState()

    // 进页面时扫一次。不做成 Flow 是因为它要查商品表，
    // 而商品价格会随同步频繁变化 —— 跟着每次变化重扫会把列表刷个不停。
    LaunchedEffect(Unit) { viewModel.refreshDrift() }

    val pending = rows.count { it.source.isPending }
    val uploadable = rows.count { it.source == OrderRow.Source.PENDING_READY }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in OrderRange.entries) {
                FilterChip(
                    selected = range == r,
                    onClick = { viewModel.setRange(r) },
                    label = { Text(r.label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 汇总条。有未上传的单时**必须显眼**：那是钱还没进账、而且只有这台设备知道。
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    buildString {
                        append("${rows.size} 笔 · ")
                        append(Money.fromCents(rows.sumOf { if (it.status == 1) it.totalCents else 0L }))
                        if (pending > 0) append(" · 未上传 $pending 笔")
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (pending > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "未上传的订单只存在于本机。店里其它设备看不到，营业额也只有这台算进去了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (uploadable > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { viewModel.selectAllUploadable() }, enabled = !busy) {
                            Text("全选可上传的（$uploadable）")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.uploadSelected() },
                            enabled = !busy && selected.isNotEmpty(),
                        ) {
                            Text(if (busy) "上传中…" else "上传选中的（${selected.size}）")
                        }
                        if (selected.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearSelection() }) { Text("取消选择") }
                        }
                    }
                }
            }
        }

        // 价格漂移提示。**放在上传按钮旁边**：用户正要点上传的那一刻才是他愿意处理它的时刻，
        // 藏在二级页面里等于没有。
        DriftCard(drift, onRefresh = { viewModel.refreshDrift() })

        report?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.consumeReport() }) { Text("知道了") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Text(
                "这个时间段没有订单。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.orderUuid }) { row ->
                    OrderRowView(
                        row = row,
                        checked = row.orderUuid in selected,
                        // 只有 READY 能勾：其余状态勾了也发不出去，
                        // 而"已选 N 笔"这个数字骗人比不给勾更糟。
                        checkable = row.source == OrderRow.Source.PENDING_READY && !busy,
                        onToggle = { viewModel.toggle(row.orderUuid) },
                        onDiscard = { viewModel.discard(row.orderUuid) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * 价格漂移提示卡。
 *
 * ⚠ 文案**不许**说"价格已自动更新"：顾客已经按旧价付过钱了，
 * 悄悄改成新价等于事后涨价，而营业额会与收银台里的现金对不上。
 * 这里只陈述事实 + 给出三个用户可选的动作。
 */
@Composable
private fun DriftCard(drift: List<DriftedOrder>, onRefresh: () -> Unit) {
    if (drift.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${drift.size} 笔未上传订单的价格已经变了",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "这些单是按开单当时的价格算的，顾客也已经按那个价付过钱。" +
                    "直接上传是**对的**（账实相符）；只有确认当初录错了才需要回开单页用新价重开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            for (d in drift.take(MAX_DRIFT_SHOWN)) {
                Text(
                    buildString {
                        append(Money.fromCents(d.totalCents))
                        append(" · ").append(d.writer.ifEmpty { "（未署名）" })
                        if (d.increased > 0) append(" · ${d.increased} 项涨价")
                        if (d.decreased > 0) append(" · ${d.decreased} 项降价")
                        d.lines.firstOrNull()?.let {
                            append("（如 ${it.name}：")
                            append(Money.fromCents(it.unitPriceThen))
                            append(" → ")
                            append(Money.fromCents(it.unitPriceNow))
                            append("）")
                        }
                        if (d.gone.isNotEmpty()) append(" · ${d.gone.size} 个商品已不在目录里")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (drift.size > MAX_DRIFT_SHOWN) {
                Text("…还有 ${drift.size - MAX_DRIFT_SHOWN} 笔", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRefresh) { Text("重新扫描") }
        }
    }
}

/** 卡片里最多列几笔。多了会把上传按钮挤出屏幕，而那一屏的重点是"能上传"。 */
private const val MAX_DRIFT_SHOWN = 5

@Composable
private fun OrderRowView(
    row: OrderRow,
    checked: Boolean,
    checkable: Boolean,
    onToggle: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.source.isPending) {
            Checkbox(checked = checked, onCheckedChange = { if (checkable) onToggle() }, enabled = checkable)
        } else {
            Spacer(Modifier.width(48.dp))
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Money.fromCents(row.totalCents),
                    style = MaterialTheme.typography.titleMedium,
                    // 已取消的单划线而不是隐藏：库存回退了、营收也不算了，
                    // 但用户需要看得见"这一笔发生过又被撤了"。
                    color = if (row.status == 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (row.status == 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge("已取消")
                }
                if (row.manualTotal) {
                    Spacer(Modifier.width(6.dp))
                    // 总额被手动覆盖过（Σ items.amount != total）。
                    // 不标出来的话用户会以为明细加起来等于总额，而它对不上。
                    Badge("含手工调整")
                }
                if (row.groupId.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Badge("拆单 ${row.groupId.take(4)}")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(TS.format(Date(row.orderTs)))
                    append(" · ").append(row.writer.ifEmpty { "（未知）" })
                    append(" · ").append(row.itemCount).append(" 件")
                    // orderTs == 0 表示这笔订单的时间是**猜的**（别的客户端没写 content.ts，
                    // StatsProjection 用文件月份兜底）。不标出来的话用户对不上账却查不出原因。
                    if (row.orderTs == 0L) append(" · 时间未知")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.remark?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            row.detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.source == OrderRow.Source.PENDING_FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            SourceLabel(row.source)
            if (row.source == OrderRow.Source.PENDING_READY ||
                row.source == OrderRow.Source.PENDING_BLOCKED ||
                row.source == OrderRow.Source.PENDING_FAILED
            ) {
                TextButton(onClick = onDiscard) { Text("删除") }
            }
        }
    }
}

@Composable
private fun SourceLabel(source: OrderRow.Source) {
    val (text, error) = when (source) {
        OrderRow.Source.SYNCED -> "已同步" to false
        OrderRow.Source.PENDING_READY -> "未上传" to false
        OrderRow.Source.PENDING_BLOCKED -> "缺箱规" to true
        // 结果未知。⚠ 文案不能说"失败"——说了用户就会重开一张单，
        // 而服务端不查重，重开就是两笔订单、库存扣两次。
        OrderRow.Source.PENDING_IN_FLIGHT -> "已发出待核对" to false
        OrderRow.Source.PENDING_FAILED -> "上传失败" to true
        OrderRow.Source.DRAFT -> "草稿" to false
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** ⚠ Locale 固定：默认 Locale 可能输出非 ASCII 数字（见 `StatsProjection` 类注释）。 */
private val TS = SimpleDateFormat("MM-dd HH:mm", Locale.US)
