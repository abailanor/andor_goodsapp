package com.example.myapplication.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import com.example.myapplication.data.OrderDetail
import com.example.myapplication.data.OrderRow
import com.example.myapplication.sync.OrderFileId
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
    val fileType by viewModel.fileType.collectAsState()
    val detail by viewModel.detail.collectAsState()

    // 进页面时扫一次。不做成 Flow 是因为它要查商品表，
    // 而商品价格会随同步频繁变化 —— 跟着每次变化重扫会把列表刷个不停。
    LaunchedEffect(Unit) { viewModel.refreshDrift() }

    val pending = rows.count { it.source.isPending }
    // ⚠ "可上传"只数**本机**的单。别的设备通过热点同步传来的那些不算 ——
    // 正常情况下创建者自己会上传，两边都传就是同一个 uuid 在服务端文件里两行
    // （服务端不查重）+ 库存扣两次。接管必须一笔一笔手动勾。
    val uploadable = rows.count { it.source == OrderRow.Source.PENDING_READY && !it.isForeign }
    val foreign = rows.count { it.isForeign && it.source.isPending }

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

        Spacer(Modifier.height(6.dp))

        // 销售 / 进货 / 全部。⚠ 必须能筛：两者混在一个列表里，
        // 用户会把 A 类进货单当成一笔营收，而它既不计营收也不扣库存。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = fileType == OrderFileId.TYPE_B,
                onClick = { viewModel.setFileType(OrderFileId.TYPE_B) },
                label = { Text("销售") },
            )
            FilterChip(
                selected = fileType == OrderFileId.TYPE_A,
                onClick = { viewModel.setFileType(OrderFileId.TYPE_A) },
                label = { Text("进货") },
            )
            FilterChip(
                selected = fileType == null,
                onClick = { viewModel.setFileType(null) },
                label = { Text("全部") },
            )
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

        // 别的设备通过热点同步传来的单。单独说明，因为它们的上传规则不一样。
        if (foreign > 0) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "$foreign 笔来自其他设备",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这些单是离线时从别的设备同步过来的。**不会自动上传** —— " +
                            "正常情况下开单的那台设备自己会传，两边都传的话服务端不查重，" +
                            "会变成两笔订单、库存扣两次。" +
                            "只有那台设备确定不会再开机了，才在这里一笔一笔勾出来接管。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onClick = { viewModel.openDetail(row) },
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

        detail?.let { d ->
            OrderDetailDialog(detail = d, onDismiss = { viewModel.closeDetail() })
        }
    }
}

/**
 * 订单详情。
 *
 * 已同步与未上传两条来路在这里**长得一样**（见 [OrderRepository.detail]）——
 * 未上传的单没有 `order_item_index` 行，明细是从 `order_outbox.payload` 解析出来的。
 * 用户不该因为一笔单还没传上去就看不到它买了什么。
 */
@Composable
private fun OrderDetailDialog(detail: OrderDetail, onDismiss: () -> Unit) {
    val purchase = detail.fileType == OrderFileId.TYPE_A
    val b = detail.breakdown

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(if (purchase) "进货单" else "销售单") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    buildString {
                        append(TS_DETAIL.format(Date(detail.orderTs)))
                        append(" · ").append(detail.writer.ifEmpty { "（未署名）" })
                        if (detail.groupId.isNotEmpty()) append(" · 组 ${detail.groupId.take(4)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (detail.source) {
                        OrderRow.Source.SYNCED -> "已同步"
                        OrderRow.Source.PENDING_READY -> "未上传"
                        OrderRow.Source.PENDING_BLOCKED -> "未上传 · 缺箱规"
                        OrderRow.Source.PENDING_IN_FLIGHT -> "已发出、结果未知"
                        OrderRow.Source.PENDING_FAILED -> "上传失败"
                        OrderRow.Source.DRAFT -> "草稿"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(detail.lines, key = { "${it.barcode}:${it.quantity}" }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name.ifEmpty { l.barcode }, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        append(l.barcode).append(" · ").append(l.quantity).append(" 件")
                                        // ⚠ 单价缺失就不显示（早期订单没有这一列）。
                                        // 绝不拿当前目录价补 —— 那会让一张历史单的金额
                                        // 随着后来越改越不对，而顾客手里那张纸是不变的。
                                        l.unitPriceCents?.let {
                                            append(" · ").append(Money.fromCents(it)).append("/个")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!purchase) {
                                Text(Money.fromCents(l.amountCents), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (!purchase) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // 与打印小票同一套规则（ReceiptMath）：≤0 不显示。
                    // 屏幕上看到的与打印出来的必须是同一份数字。
                    if (b.showDiscount) DetailRow("折扣（整箱优惠）", "-" + Money.fromCents(b.discount!!))
                    DetailRow("行金额合计", Money.fromCents(b.amountSum))
                    if (b.showRounding) DetailRow("抹零", "-" + Money.fromCents(b.rounding))
                    DetailRow("合计", Money.fromCents(detail.totalCents), emphasized = true)
                    if (detail.manualTotal) {
                        Text(
                            "含手工调整：明细加起来不等于合计",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    DetailRow("品种数", "${detail.lines.size}", emphasized = true)
                    DetailRow("总件数", detail.lines.sumOf { it.quantity }.toString())
                }

                detail.remark?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("备注：$it", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                // uuid 要能看见：报障时对服务端日志、以及在「订单」页里定位重复单都靠它
                Text(
                    detail.orderUuid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
    }
}

private val TS_DETAIL = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)

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
    onClick: () -> Unit,
    checked: Boolean,
    checkable: Boolean,
    onToggle: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        // 整行可点开详情。⚠ 勾选框与删除按钮要**吃掉**自己的点击，
        // 否则点勾选会同时弹出详情框。
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
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
                if (row.isForeign) {
                    Spacer(Modifier.width(6.dp))
                    // 必须显眼：勾选它 = 接管，而接管的后果是"两台设备都可能传这一笔"。
                    Text(
                        "其他设备",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
