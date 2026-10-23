package com.example.myapplication.ui.order

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.CartLine
import com.example.myapplication.data.CartPricing

/**
 * 上传前的确认框。
 *
 * ## 它**不是**录入界面
 *
 * 手工总额在开单页主界面上改（就放在"总计"旁边，边扫码边抹零），
 * 归属人就是当前登录用户、这里只读展示。到了这一步所有数字都已经定了，
 * 这个框只负责让用户**看一眼再确认**。
 *
 * 之所以要这一眼：整箱价 ≠ 箱规 × 单价，所以"单价 × 数量"与"行金额"天然对不上，
 * 而顾客会拿小票当场按计算器。底部的「折扣」行就是给这个差额一个交代 ——
 * 但前提是用户在上传前看见过它。
 *
 * @param writer 订单记在谁名下 = 当前登录用户名。**只读**，理由见下面那条注释。
 */
@Composable
fun CheckoutDialog(
    lines: List<CartLine>,
    /**
     * ⚠ 这里曾经是一个可编辑的下拉框（为了"代传"：某店员的账号永远不再登录，
     * 但他开的单仍需上传）。拿掉是因为在收银台上它纯粹是误操作来源 ——
     * 选错一次，这一单就**永久**记在别人名下，而服务端没有任何接口能改
     * 已上传订单的 writer（补丁只能等长覆写，且响应里不返回 writerOffset）。
     *
     * 代传如果真要做，应该放到「订单」页对**未上传**的单单独改，
     * 而不是混在结账那一下里。
     */
    writer: String,
    /** 主界面上填的手工总额。null = 没改过，总额就是 Σ 行金额。 */
    manualTotalCents: Long?,
    isPurchase: Boolean,
    /**
     * 设了整箱价却没填箱规的商品数。这些会被按散卖计价 —— 整箱价被忽略、
     * **多收顾客的钱**，而票面自洽看不出来，所以必须在按下确定之前说出来。
     */
    ambiguousCount: Int = 0,
    onConfirm: (remarkText: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var remark by remember { mutableStateOf("") }

    val totals = remember(lines, manualTotalCents) { CartPricing.totals(lines, manualTotalCents) }

    // 归属人为空就不许提交：writer 是服务端必填字段（非空白），
    // 而且统计按它分组 —— 空 writer 会让这一单归到"（未署名）"，之后再也认不出是谁卖的。
    // 离线且从没登录过时就会是空的，那时该提示用户先去登录，而不是让他上传一张无主的单。
    val writerOk = writer.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(remark.trim().ifEmpty { null }) },
                enabled = writerOk,
            ) { Text(if (isPurchase) "确认并上传进货单" else "确认并上传") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回修改") } },
        title = { Text(if (isPurchase) "上传进货单" else "上传订单") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(lines, key = { it.product.barcode }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(l.product.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        // 进货模式不显示单价：那个数字是**卖出价**，
                                        // 摆在进货单上会被当成成本价看。
                                        if (!isPurchase) append(Money.fromCents(l.unitPriceCents)).append("/个")
                                        if (l.boxes > 0) {
                                            if (!isPurchase) append(" · ")
                                            append(l.boxes).append("箱")
                                            if (l.singles > 0) append(" + ")
                                        }
                                        if (l.singles > 0) append(l.singles).append("个")
                                        l.quantity?.let { append("（共 ").append(it).append("）") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isPurchase) {
                                Text("${l.quantity ?: 0} 件", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(Money.fromCents(l.amountCents), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                if (ambiguousCount > 0) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "有 $ambiguousCount 个商品设了整箱价但没填箱规，本次按散卖计价 —— " +
                                "也就是整箱价没生效、会多收钱。去商品管理里补上箱规就能享受整箱价。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (isPurchase) {
                    // ⚠ 进货单**完全不显示金额**：服务端根本不存储买入价
                    // （README:696"目录不含进货价"），本地目录里的 price 是卖出价。
                    // 拿卖出价算进货总额是编数字，而编出来的数字会被打印出去、被当成成本记账。
                    Text(
                        "进货单不计金额：系统里没有买入价，本地目录的价格是卖出价。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TotalRow("品种数", "${lines.size}", emphasized = true)
                    TotalRow("总件数", lines.sumOf { it.quantity ?: 0 }.toString())
                } else {
                    // 折扣：整箱优惠。≤0 不显示（用户定的规则，打印与屏幕一致）
                    if (totals.showDiscount) {
                        TotalRow("折扣（整箱优惠）", "-" + Money.fromCents(totals.discount!!))
                    }
                    TotalRow("行金额合计", Money.fromCents(totals.amountSum))
                    // 抹零：手工调整。同样 ≤0 不显示
                    if (totals.showRounding) {
                        TotalRow("抹零", "-" + Money.fromCents(totals.rounding))
                    }
                    TotalRow("应收", Money.fromCents(totals.total), emphasized = true)
                }

                Spacer(Modifier.height(10.dp))

                // 归属人只读展示。让用户看见"这一单记在我名下"，
                // 但不给他改的机会 —— 见上面 writer 参数的注释。
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "记在谁名下",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (writerOk) {
                        Text(writer, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("未登录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!writerOk) {
                    Text(
                        "没有登录就没法确定这一单记在谁名下，而 writer 是服务端必填字段、" +
                            "统计也按它分组。请先在「我的」里登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (isPurchase) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text("备注（进货单）") },
                        // ⚠ A 类的 remark 必须**出生就是 150 字节**：补丁是等长覆写，
                        // 下单时没带 remark 的那一行之后无法插入（插入会让文件变长，
                        // 此后所有偏移全部错位）。这里不填，ByteOps.coerceRemark 也会补空位。
                        supportingText = { Text("留空也会占好 150 字节的位置，之后仍可改") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/**
 * 缺箱规时弹的表单。
 *
 * ## ⚠ 为什么必须在这里拦
 *
 * 缺箱规（`boxPrice` 有、`boxSize` = 0）时**钱算得出、个数算不出**：
 * `amount = 箱数×整箱价 + 零散×单价` 不需要箱规，
 * 而 `quantity = 箱数×箱规 + 零散` 需要。`quantity` 是服务端校验的正整数、
 * 也是扣库存的依据，猜一个出来就会**扣错库存**，而镜像里那一行看起来完全正常。
 *
 * ## ⚠ 补箱规需要 PRICE_CHANGE 权限
 *
 * B 级员工默认只有 `SYNC_SUB + STOCK_CHANGE`，他补不了 —— 只能整单挂起等店主。
 * 这一点必须写在界面上，否则员工会当成 bug 报。
 */
@Composable
fun BoxSizeDialog(
    /** 缺箱规的商品。 */
    missing: List<CartLine>,
    /** 有没有 PRICE_CHANGE 权限。没有就只能挂起。 */
    canEditBoxSize: Boolean,
    onFill: (barcode: String, boxSize: Int) -> Unit,
    /** 整单挂起：进 outbox 标 BLOCKED_MISSING_BOX_SIZE，等箱规补齐后自动放行。 */
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inputs = remember(missing.map { it.product.barcode }) {
        missing.associate { it.product.barcode to mutableStateOf("") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    inputs.forEach { (barcode, state) ->
                        val n = state.value.trim().toIntOrNull() ?: 0
                        if (n > 0) onFill(barcode, n)
                    }
                },
                enabled = canEditBoxSize && inputs.values.any { (it.value.trim().toIntOrNull() ?: 0) > 0 },
            ) { Text("补齐箱规") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("返回") }
                TextButton(onClick = onBlock) { Text("整单挂起") }
            }
        },
        title = { Text("${missing.size} 个商品缺箱规") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "按整箱卖的商品必须知道一箱装多少个，否则算不出总个数，" +
                        "而个数是扣库存的依据 —— 猜一个会扣错库存，且事后看不出来。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (!canEditBoxSize) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "你的账号没有改价权限（补箱规需要 PRICE_CHANGE），" +
                                "只能整单挂起等店主补齐。挂起的单会留在「订单」页的未上传里，" +
                                "箱规补上后自动放行，不会丢。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                for (l in missing) {
                    val state = inputs[l.product.barcode] ?: continue
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { v -> state.value = v.filter { it.isDigit() } },
                        label = { Text("${l.product.name}（${l.product.barcode}）") },
                        supportingText = {
                            Text(
                                if (l.product.boxPrice != null) "整箱价 ${l.product.boxPrice} 已填，只缺箱规"
                                else "一箱装几个"
                            )
                        },
                        enabled = canEditBoxSize,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun TotalRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
