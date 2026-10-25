package com.example.myapplication.data

import java.math.BigDecimal

/**
 * 待开单 → 购物车行的还原逻辑（纯函数，无 Android 依赖，可单测）。
 *
 * ## 为什么必须按快照兜底
 *
 * 进货单的商品来自**管理员目录**（丽华金龙），本地商品库里本来就可能没有：
 * 用户没在商品页同步过目录、或目录里这件商品后来被删/改名。
 * 逐行查库、查不到就跳过的写法会让进货单点「开单」后购物车是空的 ——
 * 不报错、不提示，用户看到的就是"单子不见了"。
 *
 * 所以恢复的权威数据源是 [PendingOrderLine] 自己存下的快照（name/price/subtotal
 * 都在里面），商品库只做**增强**：查得到就用库里的最新数据（改名改价即时生效），
 * 查不到就按快照构造一个离线投影继续用。
 *
 * ⚠ 价格语义：购物车行的小计由 `price × quantity` 现算，所以"库里有就用新价"
 * 会让旧待开单按新价结账。这是**有意的**——待开单是草稿不是合约，
 * 结账前价格漂移检测（`PriceDriftScanner`）会另外提示；真正不能接受的是
 * "整张单消失"，那没有任何机制能补救。
 *
 * @param lookup 按条码查本地商品库，返回 null 表示库里没有。
 * @return (购物车行, 其中按快照恢复的行数) —— 第二个数字供 UI 告知用户。
 */
suspend fun restorePendingOrderItems(
    record: PendingOrderRecord,
    lookup: suspend (String) -> Product?,
): Pair<List<OrderItem>, Int> {
    val items = mutableListOf<OrderItem>()
    var fromSnapshot = 0
    for (line in record.items) {
        val live = lookup(line.barcode)
        if (live != null) {
            items.add(OrderItem(product = live, quantity = line.quantity))
        } else {
            fromSnapshot++
            items.add(
                OrderItem(
                    product = Product(
                        barcode = line.barcode,
                        name = line.name.ifBlank { line.barcode },
                        price = line.price.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    ),
                    quantity = line.quantity,
                )
            )
        }
    }
    return items to fromSnapshot
}
