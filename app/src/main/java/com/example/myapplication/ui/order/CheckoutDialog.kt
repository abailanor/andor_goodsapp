package com.example.myapplication.ui.order

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
 * 结算对话框。
 *
 * ## 它要让用户在**按下确定之前**看见三件事
 *
 *  1. **单价 × 数量 ≠ 金额**。整箱价 ≠ 箱规 × 单价，所以这两个数天然对不上，
 *     而顾客会拿小票当场按计算器。底部的「折扣」行就是给这个差额一个交代。
 *  2. **手工改过总额**。改完之后 `Σ行金额 ≠ 合计`，必须显示「抹零」行，
 *     否则小票上的数字自己打自己。
 *  3. **这一单记在谁名下**（`writer`）。⚠ 归属人只能在下单这一刻定对 ——
 *     服务端没有任何接口能修改已上传订单的 `writer`，而且它**不校验** writer
 *     与登录身份是否一致（只验非空白），所以代传是合法的：现实中存在
 *     "某店员的账号永远不再登录，但他开的单仍需上传"的情况。
 *
 * ## ⚠ 手工总额是**覆盖**，不是"再打一个折"
 *
 * 存进 payload 的 `content.total` 就是权威总额，统计一律用它（`manualTotal` 标记
 * 只是告诉 UI"这一单的 Σitems 对不上 total，别装作对得上"）。
 * 所以这里输入的是**最终收多少钱**，不是"减多少"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutDialog(
    lines: List<CartLine>,
    /** 当前登录用户名，作为归属人的默认值。离线时可能是空串。 */
    defaultWriter: String,
    /** 同店其它店员的名字，供代传时选。可以为空（离线、或没有 EMPLOYEE_MANAGE 权限）。 */
    clerkNames: List<String>,
    /** A 类（进货）才需要备注；B 类传了会被 `OrderGroupAssembler` 直接拒掉。 */
    isPurchase: Boolean,
    onConfirm: (writer: String, manualTotalCents: Long?, remarkText: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var writer by remember { mutableStateOf(defaultWriter) }
    var totalText by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var writerMenuOpen by remember { mutableStateOf(false) }

    // manualTotalCents == null 表示"没手改"，此时 total 就是 Σ行金额。
    // ⚠ 输入框留空 == null，而不是 0：0 是"这一单免费"，与"没改过"是两件事。
    val manualTotal = totalText.trim().takeIf { it.isNotEmpty() }
        ?.let { runCatching { Money.toCents(it) }.getOrNull() }
    val totals = remember(lines, manualTotal) { CartPricing.totals(lines, manualTotal) }

    // 归属人为空就不许提交：writer 是服务端必填字段（非空白），
    // 而且统计是按它分组的 —— 空 writer 会让这一单归到"（未署名）"，
    // 之后再也认不出是谁卖的。
    val writerOk = writer.isNotBlank()
    // 输入了一个解析不出来的金额时明确报错，不要静默当成"没改过"
    val totalBad = totalText.isNotBlank() && manualTotal == null

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(writer.trim(), manualTotal, remark.trim().ifEmpty { null }) },
                enabled = writerOk && !totalBad,
            ) { Text("确定并上传") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回修改") } },
        title = { Text(if (isPurchase) "结算 · 进货单" else "结算") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(lines, key = { it.product.barcode }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(l.product.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        append(Money.fromCents(l.unitPriceCents)).append("/个")
                                        if (l.boxes > 0) {
                                            append(" · ").append(l.boxes).append("箱")
                                            if (l.singles > 0) append(" + ")
                                        }
                                        if (l.singles > 0) append(l.singles).append("个")
                                        l.quantity?.let { append("（共 ").append(it).append("）") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(Money.fromCents(l.amountCents), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

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

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text("手工改总额（留空 = ${Money.fromCents(totals.amountSum)}）") },
                    supportingText = {
                        if (totalBad) {
                            Text("金额格式不对，示例：100.00", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("填的是**最终收多少钱**，不是减多少")
                        }
                    },
                    isError = totalBad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                // 归属人：默认是自己，可以改成别的店员（代传）
                ExposedDropdownMenuBox(expanded = writerMenuOpen, onExpandedChange = { writerMenuOpen = it }) {
                    OutlinedTextField(
                        value = writer,
                        onValueChange = { writer = it },
                        label = { Text("记在谁名下") },
                        supportingText = {
                            Text(
                                if (clerkNames.isEmpty()) "上传之后**改不了**，请确认"
                                else "可下拉选别的店员（代传）。上传之后**改不了**",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        isError = !writerOk,
                        singleLine = true,
                        trailingIcon = if (clerkNames.isEmpty()) null else {
                            { ExposedDropdownMenuDefaults.TrailingIcon(expanded = writerMenuOpen) }
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    if (clerkNames.isNotEmpty()) {
                        ExposedDropdownMenu(expanded = writerMenuOpen, onDismissRequest = { writerMenuOpen = false }) {
                            for (name in clerkNames) {
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { writer = name; writerMenuOpen = false },
                                )
                            }
                        }
                    }
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
        missing.associate { it.product.barcode to mutableStateOf("") }.mapValues { it.value }
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                "只能整单挂起等店主补齐。挂起的单会留在「订单 → 未上传」里，" +
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
