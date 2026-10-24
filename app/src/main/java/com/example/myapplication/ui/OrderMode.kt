package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 开单模式。
 *
 * 两种进货要分清（用户的原话）：
 *  - **自采**：本店要进的货，用途是"打印一张单子拿着去别的店买"，或者"从这个界面直接入库"。
 *    服务端**根本不知道**有这回事，它纯本地。
 *  - **向总部下单**：A 类订单，会上传给丽华金龙，等总部处理（complete / cancel）。
 *
 * 现在只有一个 [PURCHASE]，两种用途共用：打印出来就是自采清单，上传出去就是 A 类订单。
 * 要不要拆成两个模式，等 A 类订单的库存联动定了再说（见任务 P11）。
 */
enum class OrderMode(val label: String) {
    /** 卖给顾客。B 类文件，计营收、扣库存。 */
    SALE("销售"),

    /**
     * 进货。A 类文件，**不计营收、不动库存**。
     *
     * ⚠ 这个模式下不算价格、不显示总额、小票上也不印金额 —— 因为
     * **服务端根本不存储买入价**（README 第 696 行："目录不含进货价"），
     * 本地目录里的 `price` 是**卖出价**。拿卖出价去算进货总额是编数字。
     */
    PURCHASE("进货"),
}

/**
 * 把存储里的模式字符串还原成枚举。
 *
 * ⚠ 认不出的一律当 [OrderMode.SALE]，**不抛异常**：待开单是本机 JSON 文件，
 * 升级前存的那些根本没有 `mode` 字段（null），而那时确实只有销售单。
 * 用 `valueOf` 的话，一个脏文件就能让整条 Flow 的 collector 崩掉。
 */
fun orderModeOf(name: String?): OrderMode =
    if (name == OrderMode.PURCHASE.name) OrderMode.PURCHASE else OrderMode.SALE

/**
 * 当前开单模式。**进程级单例**，与 [com.example.myapplication.db.CurrentStore] 同一个理由：
 * 整条装配链都是手工的（没有 DI 框架），做成构造参数要改一大片签名，
 * 而它的语义本来就是"整个 App 现在处于哪个模式"。
 *
 * ## ⚠ 刻意**不持久化**：进程重启一律回到 [OrderMode.SALE]
 *
 * 让进货模式跨天留着太危险：第二天早上第一个顾客来买东西，
 * 店员照着昨天的界面点下去，开出来的是一张 A 类进货单 ——
 * 而 A 类不计营收、不扣库存，那一单就**凭空消失**了，账上完全看不出来。
 *
 * 底色是防这个的第一道（也是唯一一道实时）防线，但它只在用户**看**的时候有用；
 * 真正兜底的是"每次冷启动都回到销售模式"。
 */
object CurrentOrderMode {

    private val _mode = MutableStateFlow(OrderMode.SALE)

    val mode: StateFlow<OrderMode> = _mode.asStateFlow()

    val now: OrderMode get() = _mode.value

    fun set(mode: OrderMode) {
        _mode.value = mode
    }
}

/** 进货模式的底色。要一眼能看出来，但不能刺眼到影响读数字。 */
val PurchaseBackgroundColor = Color(0xFFFFF3E0)

/** 销售模式的底色（与原 `MainActivity` 一致）。 */
val SaleBackgroundColor = Color(0x0FF5F5F5)

/** 按当前模式取整个应用的底色。 */
@Composable
fun rememberAppBackgroundColor(): Color {
    val mode by CurrentOrderMode.mode.collectAsState()
    return if (mode == OrderMode.PURCHASE) PurchaseBackgroundColor else SaleBackgroundColor
}

/**
 * 模式切换条。开单页、待开单页、历史订单页**都要放一个**。
 *
 * ## 为什么三个页面都要
 *
 * 只放开单页的话，用户从待开单或历史页进来时看不到当前处于哪个模式，
 * 而底色虽然变了，"我现在到底是销售还是进货"这件事仍然要靠猜 ——
 * 尤其历史页会同时列出两种单，没有这个开关就没法按模式筛。
 *
 * @param onModeChanged 切换后回调。开单页要据此清空购物车或重新计价
 *        （销售单有价格，进货单没有，两种模式的行内容不兼容，不能直接沿用）。
 */
@Composable
fun ModeSwitchBar(
    modifier: Modifier = Modifier,
    onModeChanged: ((OrderMode) -> Unit)? = null,
) {
    val mode by CurrentOrderMode.mode.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 进货模式下整条也跟着变色：底色在滚动之后可能被列表盖住，
            // 但这一条是吸顶的，永远看得见。
            .background(if (mode == OrderMode.PURCHASE) PurchaseBackgroundColor else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (m in OrderMode.entries) {
            FilterChip(
                selected = mode == m,
                onClick = {
                    if (mode != m) {
                        CurrentOrderMode.set(m)
                        onModeChanged?.invoke(m)
                    }
                },
                label = { Text(m.label) },
            )
        }
        if (mode == OrderMode.PURCHASE) {
            // ⚠ 这句提示不是装饰。进货模式下不算钱、不扣库存、不计营收，
            // 而界面上少了总额之后，用户很容易以为"这单没保存成功"。
            Text(
                "进货模式：不算金额、不计营收、不动库存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
