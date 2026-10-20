package com.example.myapplication.data

import java.math.BigDecimal

/**
 * 商品的**读取投影**（v4 起不再是 Room Entity）。
 *
 * v3 之前 `Product` 是唯一的实体，映射到 `products(barcode, name, price)` 三列，
 * "整件"靠在条码前加 `0` 再插一行来表示。v4 起它变成 `product_synced`（服务器权威）
 * 与 `product_local`（未推送）两张表的**合并视图投影**，`synced 优先`。
 *
 * ## 为什么改成投影而不是继续当实体 + 标记列
 *
 * 单表加一个 `syncState` 标记也能表达"这条是本地的"，但那样"FULL 整表替换"就必须靠
 * 代码记得先检查标记、先拦下来——**约定性防护**，漏一次就永久丢掉 400 条手录商品。
 * 分区之后 FULL 只写 `product_synced`，**结构上**就碰不到本地数据
 * （ANDROID.md §3.1 的硬性要求：已同步区与未同步区必须分开存放）。
 *
 * ## 字段顺序
 *
 * `barcode / name / price` 排在最前是为了**源码兼容**：现有 UI 里大量
 * `Product(barcode, name, price)` 的位置参数构造仍然能编译。
 * Room 按**列名**映射，与字段顺序无关。
 *
 * ## `stock` 的语义（容易读错）
 *
 * 这里的 `stock` 是"最后一次服务器绝对观测值"（基线），**不是真实库存**。
 * 真实库存要走 `StockLedger.effectiveStock()`：基线还要减去吸收边界之后的订单卖出、
 * 加回吸收边界之后的取消回退。UI 与补货筛选**只准走 StockLedger**，不要直接读这个字段。
 *
 * 原因：`order_write.lua` 第 5 节里 B 类订单**直接 HSET 商品 hash 且不写库存流**，
 * 所以库存流给的 `new_stock` 已经吸收了此前所有 B 类订单的扣减。
 * 而"哪些已被吸收"无法用版本判定——`version_stock` 与 per-fileId 的订单版本
 * 是两个独立空间，协议不提供任何交叉参照。
 */
data class Product(
    val barcode: String,
    val name: String,
    val price: BigDecimal,
    /** `null` = 不按整箱卖。**不要**用 0 表示，0 是"整箱免费"。 */
    val boxPrice: BigDecimal? = null,
    /** `0` = 未设置箱规。此时"整箱数 × 箱规"换算不出个数，开单时必须先让用户补规格。 */
    val boxSize: Int = 0,
    val stock: Int = 0,
    val orderable: Boolean = true,
    val updatedAt: Long = 0L,
    /** 本地扩展字段，服务器没有、FULL 不会带回（单独存在 `product_extra` 表）。 */
    val category: String = "",
    val note: String = "",
    /** [ORIGIN_SYNCED] 或 [ORIGIN_LOCAL]。来自视图里的字面量列。 */
    val origin: String = ORIGIN_LOCAL,
    val storeKey: String = "",
) {
    /** 是否按整箱卖：有整箱价**且**有箱规。缺箱规时整箱价是个孤立数字，算不出个数。 */
    val sellsByBox: Boolean get() = boxPrice != null && boxSize > 0

    /**
     * 箱规未知但填了整箱价。
     *
     * 这类商品**能算价、不能上传**：`amount = 箱数×boxPrice + 零散×price` 不需要箱规，
     * 但 `quantity = 箱数×boxSize + 零散` 需要。开单时若用户填了整箱数，
     * 必须弹箱规表单让他当场补，或者把订单挂进 outbox 标 `BLOCKED_MISSING_BOX_SIZE`。
     */
    val needsBoxSize: Boolean get() = boxPrice != null && boxSize == 0

    companion object {
        const val ORIGIN_SYNCED = "SYNCED"
        const val ORIGIN_LOCAL = "LOCAL"
    }
}
