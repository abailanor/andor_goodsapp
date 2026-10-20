package com.example.myapplication.data

import java.math.BigDecimal

/**
 * 旧数据里"**条码前加 0 表示整件**"这个约定的合并器。
 *
 * v3 的模型只有 `barcode/name/price` 三列，整件靠额外插一行 `0<barcode>` 表示：
 * ```
 * 06901234567890  可口可乐330ml整件  80.00
 * 6901234567890   可口可乐330ml      3.50
 * ```
 * v4 对齐服务端 `ProductData`（`price` / `boxPrice` / `boxSize`），这两行是一条记录：
 * `barcode=6901234567890, price=3.50, boxPrice=80.00, boxSize=0`。
 *
 * ## 为什么抽成独立对象
 *
 * 有三条路径要跑同一套规则，**不能写三遍**（写三遍就一定会漂移）：
 *  1. `MIGRATION_3_4`（老设备升级）
 *  2. 导入旧版 SQLite 导出文件（`DatabaseImporter`）
 *  3. 导入 v0 CSV（`barcode,name,price` 三列，见 `docs/INTERCHANGE_FORMAT.md` §4.2）
 *
 * ## `boxSize` 在旧模型里根本不存在
 *
 * 合并后一律填 `0`（未设置箱规）。后果必须让用户看见，不能静默：
 * `amount = 箱数×boxPrice + 零散×price` **不需要**箱规，但
 * `quantity = 箱数×boxSize + 零散` **需要** —— 而 quantity 是上传给服务器扣库存的个数。
 * 所以这类商品"能算价、不能上传"，开单时必须弹箱规表单让用户当场补
 * （见 [Product.needsBoxSize]）。
 */
object BoxRowMerger {

    /** 一行旧数据。`price` 一律按**字符串**读入再转 BigDecimal（不要走 Double，金额过 double 有精度问题）。 */
    data class LegacyRow(val barcode: String, val name: String, val price: BigDecimal)

    /**
     * 一条合并结果。
     *
     * @param mergedFromBoxRow 是否吸收了一条 `0` 前缀行（用于报告"合并整件行 N 条"）
     * @param nameHadBoxSuffix 原始名字是否以"整件"结尾。旧数据不总是规范，
     *                         不以"整件"结尾的 `0` 前缀行仍按整件行处理，但要在报告里列出。
     */
    data class Merged(
        val product: Product,
        val mergedFromBoxRow: Boolean,
        val nameHadBoxSuffix: Boolean,
    )

    data class Result(
        val merged: List<Merged>,
        /** 只有 `0X` 没有 `X` 的行：缺了单个价就没法定价。 */
        val orphanBoxRows: List<LegacyRow>,
    ) {
        val mergedBoxRowCount: Int get() = merged.count { it.mergedFromBoxRow }
        val irregularNameCount: Int get() = merged.count { it.mergedFromBoxRow && !it.nameHadBoxSuffix }
    }

    /** 旧约定的整件行前缀。 */
    private const val BOX_PREFIX = "0"

    /** 旧版给整件行的名字后缀（`ProductManagementScreen.AddProductScreen` 写死过这个）。 */
    private const val BOX_NAME_SUFFIX = "整件"

    fun merge(rows: List<LegacyRow>): Result {
        val byBarcode = LinkedHashMap<String, LegacyRow>()
        rows.forEach { r ->
            if (r.barcode.isNotBlank()) byBarcode[r.barcode] = r
        }

        val merged = ArrayList<Merged>()
        val orphans = ArrayList<LegacyRow>()

        for ((barcode, row) in byBarcode) {
            if (barcode.startsWith(BOX_PREFIX)) {
                // 整件行：只有对应的单行不存在时才算孤儿
                val singleBarcode = barcode.removePrefix(BOX_PREFIX)
                if (singleBarcode.isEmpty() || !byBarcode.containsKey(singleBarcode)) {
                    orphans.add(row)
                }
                // 有对应单行时，这一行会被下面处理单行时吸收，这里跳过
                continue
            }
            val boxRow = byBarcode[BOX_PREFIX + barcode]
            val name = stripBoxSuffix(row.name)
            merged.add(
                Merged(
                    product = Product(
                        barcode = barcode,
                        name = name,
                        price = row.price,
                        // 整件行的 price → boxPrice；没有整件行就是 null（不按整箱卖）
                        boxPrice = boxRow?.price,
                        // 旧模型没有箱规，一律 0，之后要用户手工补
                        boxSize = 0,
                        stock = 0,
                        orderable = true,
                        origin = Product.ORIGIN_LOCAL,
                    ),
                    mergedFromBoxRow = boxRow != null,
                    nameHadBoxSuffix = boxRow != null && boxRow.name.endsWith(BOX_NAME_SUFFIX),
                )
            )
        }
        return Result(merged, orphans)
    }

    /** 去掉尾部的"整件"二字。SQLite 的 `LENGTH()` 数字符，Kotlin 的 `dropLast` 也数字符，一致。 */
    private fun stripBoxSuffix(name: String): String =
        if (name.endsWith(BOX_NAME_SUFFIX) && name.length > BOX_NAME_SUFFIX.length) {
            name.dropLast(BOX_NAME_SUFFIX.length)
        } else name
}
