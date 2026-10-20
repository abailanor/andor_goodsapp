package com.example.myapplication.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额 ↔ 分（Long）的换算。**全项目唯一允许做这件事的地方。**
 *
 * ## 为什么必须有这么个类
 *
 * 协议里所有金额都是**字符串**（README §2.3：`amount` / `total` / `price` 用字符串避免
 * `12.5` 与 `12.50` 的序列化差异）。而本地要做大量求和（统计、补货、结算），
 * 所以落库时统一转成"分"存 Long。两个方向都可能出错，而且都**不报错**：
 *
 *  - `"52.00".toDouble() * 100` = `5200.000000000001`，`toInt()` 截断成 `5200` 看着没事，
 *    但 `"1.15".toDouble() * 100` = `114.99999999999999` → 截断成 **114**，少一分。
 *    一天上百单、一单上百行，累加起来就是肉眼可见的差额，而且**永远查不出是哪一笔**。
 *  - 反向 `cents / 100.0` 再格式化，`0.1 + 0.2` 那类误差会让 `2.67` 显示成 `2.6699999`。
 *
 * 所以一律走 [BigDecimal]，**绝不经过 Double**。这与方案 R3 对导入格式的要求是同一条：
 * "价格一律按字符串读再转 BigDecimal"。
 *
 * ## 为什么用 `BigDecimal(String)` 而不是 `BigDecimal(Double)`
 *
 * `BigDecimal(0.1)` 得到的是 `0.1000000000000000055511151231257827021181583404541015625` ——
 * 那个构造函数的 Javadoc 自己都建议永远别用。要从小数构造只能用 `valueOf` 或字符串构造。
 */
object Money {

    /**
     * 金额字符串 → 分。
     *
     * @throws NumberFormatException 字符串不是合法十进制数。**这是故意的**：
     *   金额解析不出来说明镜像里那一行不是我们写的，此时**任何**兜底数字都是在编造账目。
     *   调用方（`OrderPayloadIndex`）负责捕获、跳过该行并记审计日志 —— 少一笔看得见，
     *   编一笔看不见。
     *
     * 超过 2 位小数按 HALF_UP 取整到分。正常数据不会走到这里
     * （服务端价格与 `StableOrderJson` 都是两位），留着是为了不因一条怪数据毁掉整次 FULL 重建。
     */
    fun toCents(text: String): Long =
        BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** [toCents] 的容错版：null / 空白 / 解析失败都返回 [fallback]（默认 0）。 */
    fun toCentsOr(text: String?, fallback: Long = 0L): Long =
        if (text.isNullOrBlank()) fallback
        else runCatching { toCents(text) }.getOrDefault(fallback)

    /**
     * 分 → 两位小数字符串。**恒定两位**，不随值变化。
     *
     * 恒定两位是必须的：这个数字有可能被写回订单 JSON（结算时手动覆盖总额），
     * 而 `52` 与 `52.00` 会让两台设备算出不同的 `total` 字符串 → 不同的字节 → 镜像分歧。
     */
    fun fromCents(cents: Long): String =
        BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}
