package com.example.myapplication.sync

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.money.Money
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * 一行订单原文里**可补丁位置**的行内字节下标（不含 lineStart）。
 *
 * 三个值分别对应服务端 `/add` 响应与流消息里的 `uuid_offset` / `status_offset`，
 * 以及客户端按 README §2.5 推算的 `remark_offset`。
 *
 * ⚠ **只有在 FULL 恢复时才需要推算它们**：增量路径上服务端会把权威偏移直接给过来，
 * 那时必须用服务端给的（见 [OrderFileSyncEngine]）。推算是不得已的兜底，
 * 而它错一位就意味着此后每次取消都打在别的订单的字节上——**静默且不可恢复**。
 */
data class OrderLineOffsets(
    /** uuid **值**首字节（已跳过开引号）。`lineStart + 它` == 服务端 `uuidOffset`。 */
    val uuidValueIndex: Int,

    /** status **数字**字节。`lineStart + 它` == 服务端 `statusOffset`。 */
    val statusIndex: Int,

    /**
     * remark **值**首字节。
     *
     * ⚠ **remark 不是恰好 [ByteOps.REMARK_WIDTH] 字节时为 null**，即使它存在。
     * 这不是偷懒：补丁是**等长覆写**，拿 150 字节去覆写一个 20 字节的 remark
     * 会吃掉后面 130 字节的 JSON（`}`、逗号、下一个字段……），
     * 文件长度不变所以校验和自洽、WAL 也会忠实回放——**没有任何机制会发现**。
     * 记 null 的后果只是"这条备注改不动"，用户会在 UI 上看到明确提示。
     */
    val remarkValueIndex: Int?,
)

/**
 * 按**字节**扫描一行订单 JSON，取出顶层三个字段的值下标。
 *
 * ## 为什么不能用正则或 `indexOf`
 *
 * 服务端自己的兜底路径就是 `indexOf`（`OrderFileService:1122` / `:1147`），
 * 而它有个已知缺陷：`rawJson.indexOf("\"status\"")` 找的是**全文第一次出现**，
 * 不认嵌套层级。一个商品名叫 `status` 的行（`"name":"status"`）排在前面就会命中它。
 * 那条兜底平时不触发（Jackson 的主路径给出正确提示），所以我们不去改服务端，
 * 但**本地推算必须比它严格**：只认大括号深度 == 1 的键。
 *
 * ## 为什么按字节而不是按字符
 *
 * 偏移最终要变成文件里的字节位置。按 `String` 下标算完再换算，等于把
 * "码点数 == 字节数"这个假设偷偷埋进去 —— 而中文商品名恰好让它不成立
 * （方案 R6 里 `test_all.py` 的 E2/E10 就是这么坏的：「可口可乐330ml」让两种下标差 8）。
 *
 * 按字节扫描是安全的：UTF-8 的续字节一律 ≥ 0x80，
 * 永远不可能与 `"` `\` `:` `{` `}` 这些 ASCII 结构字符混淆。
 *
 * ## 跳过空白与引号的规则**逐字照抄服务端**
 *
 * `normalizeUuidOffset`(:1109-1120) / `normalizeStatusOffset`(:1130-1153) 都是
 * `while (c == '"' || c == ' ') i++;` —— **只跳空格与双引号**，不跳 `\t` `\n`。
 * 这里保持一致。多跳一种字符就会与服务端算出不同的偏移；
 * 而 `\t`/`\n` 本来也不可能出现（`validateSingleLine` 拒绝所有 ≤0x1F 的控制字符），
 * 但**空格会出现**：`s1client.py` 用 Python `json.dumps` 的默认分隔符（`", "` / `": "`），
 * 参照实现写出来的订单就是带空格的。
 */
object OrderJsonScanner {

    private const val QUOTE = 0x22
    private const val BACKSLASH = 0x5C
    private const val COLON = 0x3A
    private const val SPACE = 0x20
    private const val LBRACE = 0x7B
    private const val RBRACE = 0x7D
    private const val LBRACKET = 0x5B
    private const val RBRACKET = 0x5D

    /** 推不出来（不是 JSON 对象、缺 order_uuid 或 status）时返回 null，**不抛**。 */
    fun offsets(payload: ByteArray): OrderLineOffsets? {
        var uuidIdx = -1
        var statusIdx = -1
        var remarkIdx = -1

        var depth = 0
        var inString = false
        var escaped = false
        /** 当前字符串**值**的首字节下标（开引号的下一个）。 */
        var strStart = -1
        /** 当前字符串开始时的深度。只有 1 才有资格当顶层键。 */
        var strDepth = -1

        val n = payload.size
        var i = 0
        while (i < n) {
            val b = payload[i].toInt() and 0xFF
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == BACKSLASH -> escaped = true
                    b == QUOTE -> {
                        inString = false
                        // 字符串结束于 i。它是"键"当且仅当后面（跳过空格）紧跟冒号。
                        // 顶层的**值**字符串也会走到这里，靠这个前瞻排除掉。
                        if (strDepth == 1) {
                            var k = i + 1
                            while (k < n && (payload[k].toInt() and 0xFF) == SPACE) k++
                            if (k < n && (payload[k].toInt() and 0xFF) == COLON) {
                                // 键名一律 ASCII（协议固定字段），解码整段是安全的
                                val key = String(payload, strStart, i - strStart, Charsets.UTF_8)
                                var v = k + 1
                                while (v < n && (payload[v].toInt() and 0xFF) == SPACE) v++
                                when (key) {
                                    "order_uuid" -> uuidIdx = skipQuoteAndSpace(payload, v, n)
                                    "status" -> statusIdx = skipQuoteAndSpace(payload, v, n)
                                    "remark" -> {
                                        // remark 必须是字符串；值长度按**文件里的字节**算，
                                        // 含转义 —— 那才是补丁真正会覆写的范围。
                                        //
                                        // ⚠ 这里**不能**用 [skipQuoteAndSpace]：它连空格一起跳，
                                        // 而 A 类订单的 remark 出生就是 150 个空格
                                        // （方案 §3.8：`OrderGroupAssembler` 对 A 类强制生成 remark，
                                        // 哪怕全空格 —— 因为补丁是等长覆写，下单时没带就永远补不进去）。
                                        // 跳完空格会正好落在**闭引号**上，于是 end == vs，
                                        // 判成"不可补丁" → 最常见的 A 类备注全都改不动，而且不报错。
                                        // 所以只跳过冒号后的空格（[v] 已经跳过了）与那一个开引号。
                                        val end = findStringEnd(payload, v, n)
                                        val vs = v + 1
                                        if (end > vs && end - vs == ByteOps.REMARK_WIDTH) {
                                            remarkIdx = vs
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                i++
                continue
            }
            when (b) {
                QUOTE -> {
                    inString = true
                    escaped = false
                    strStart = i + 1
                    strDepth = depth
                }
                LBRACE, LBRACKET -> depth++
                RBRACE, RBRACKET -> depth--
            }
            i++
        }

        if (uuidIdx < 0 || statusIdx < 0 || uuidIdx >= n || statusIdx >= n) return null
        return OrderLineOffsets(uuidIdx, statusIdx, if (remarkIdx < 0) null else remarkIdx)
    }

    /**
     * 跳过空格与双引号，返回值首字节下标。**与服务端 `normalize*Offset` 的循环逐字一致。**
     *
     * `"status":"1"` 与 `"status":1` 服务端都接受（`parseOrderJson:1017-1024`），
     * 前者偏移指向引号内的数字、后者指向数字本身 —— 跳引号正好把两种情况统一。
     */
    private fun skipQuoteAndSpace(payload: ByteArray, from: Int, n: Int): Int {
        var v = from
        while (v < n) {
            val c = payload[v].toInt() and 0xFF
            if (c == QUOTE || c == SPACE) v++ else break
        }
        return if (v < n) v else -1
    }

    /** [openQuote] 必须指向开引号。返回配对闭引号的下标，找不到返回 -1。 */
    private fun findStringEnd(payload: ByteArray, openQuote: Int, n: Int): Int {
        if (openQuote < 0 || openQuote >= n || (payload[openQuote].toInt() and 0xFF) != QUOTE) return -1
        var esc = false
        var j = openQuote + 1
        while (j < n) {
            val c = payload[j].toInt() and 0xFF
            when {
                esc -> esc = false
                c == BACKSLASH -> esc = true
                c == QUOTE -> return j
            }
            j++
        }
        return -1
    }
}

/**
 * 把一行订单原文解析成建索引需要的全部字段。
 *
 * ## 解析用 Gson、序列化用手写 —— 这个不对称是有意的
 *
 * `StableOrderJson` 手写是因为**写出去的字节必须完全可控**（字段顺序、零空白、不 HTML 转义）。
 * 读进来则没有这个约束：Gson 只负责把值取出来，偏移一律由 [OrderJsonScanner] 在字节上算，
 * **不从 Gson 拿任何位置信息**。所以这里用 `JsonObject` 手工取字段，
 * 而不是 `fromJson<T>` 反序列化到 data class —— 后者会踩 Gson 用
 * `Unsafe.allocateInstance` 绕过构造函数的坑（默认值不生效、`val` 可能是 null）。
 *
 * ## 失败一律返回 null，不抛
 *
 * 调用方是 FULL 重建（一次几万行）与增量 ADD。抛异常会让整次 FULL 失败，
 * 而那时镜像**已经写好了**、游标还没提交 → 下次同步又是 FULL → 死循环。
 * 返回 null 让调用方跳过该行并记审计日志：少一笔是看得见的（行数对不上），
 * 编一笔是看不见的。
 */
object OrderPayloadIndex {

    /** 一条商品行。 */
    data class Item(
        val barcode: String,
        val name: String,
        val quantity: Int,
        val amountCents: Long,
        /**
         * 该行的单个零售价（分）。**null = 文件里没有这一列**（早期写入的订单）。
         *
         * ## ⚠ null 时不许拿当前目录价补
         *
         * 小票上的「折扣」行 = `Σ(quantity × unitPrice) − Σ amount`。缺 unitPrice 就算不出来，
         * 那就**不显示这一行**（与"折扣 ≤ 0 不显示"是同一条规则）。
         *
         * 拿目录现价去补的话，一张历史小票的金额会随着后来越改越不对 ——
         * 而顾客手里那张纸是不变的。这种"账对不上"是最难查的一类，
         * 因为两边各自看都是对的。
         */
        val unitPriceCents: Long?,
    )

    /**
     * 解析结果。字段与 `OrderIndexEntity` 一一对应，但**不含 storeKey/fileId/偏移**——
     * 那些由调用方按上下文补（增量用服务端给的偏移，FULL 用 [offsets] 推算的）。
     */
    data class Parsed(
        val orderUuid: String,
        val writer: String,
        /** 文件里当前的状态数字。FULL 恢复时它是**打过补丁之后**的值。 */
        val status: Int,
        /**
         * `content.ts`（客户端写入的 epoch millis）。
         *
         * ⚠ **协议本身没有订单时间字段**，FULL 恢复的订单拿不到流消息里的 `timestamp`，
         * 所以只能靠这个自带字段。缺失时为 0 —— 按日/月统计会把它归到 1970，
         * 这是"别的客户端写的订单"这一情况的固有代价，不是可以兜住的 bug。
         */
        val orderTs: Long,
        val totalCents: Long,
        /**
         * `Σ items.amount != content.total`。
         *
         * 出现即意味着总额被手动覆盖过（或数据来自别处）。**不静默取其一**：
         * UI 要打"含手工调整"标记，统计一律用 [totalCents]（覆盖发生在总额层）。
         */
        val manualTotal: Boolean,
        val groupId: String?,
        /** 已 `trimEnd()` 的备注原文（文件里是 padding 到 150 字节的）。 */
        val remark: String?,
        val items: List<Item>,
        /**
         * `content.items` **有**条目、但至少一条不合格 → [items] 被整体清空（与服务端 A 类
         * 宽容路径同形）。调用方必须记审计日志。
         *
         * 与"这单本来就没 items"区分开是必要的：B 类文件里的订单是服务端**严格校验**后才落盘的，
         * 所以 `itemsDropped=true` 出现在 B 类上只可能意味着**本解析器与 Jackson 判断不一致**
         * —— 那是会让 [StockLedger] 静默算错库存的 bug。
         * 不打这个标记的话，`itemCount=0` 与"真的没条目"在数据上完全一样。
         */
        val itemsDropped: Boolean,
        val offsets: OrderLineOffsets,
    ) {
        val itemCount: Int get() = items.size
    }

    fun parse(payload: ByteArray): Parsed? {
        val offsets = OrderJsonScanner.offsets(payload) ?: return null
        val root = try {
            JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        } catch (e: JsonSyntaxException) {
            return null
        } catch (e: IllegalStateException) {
            return null   // 顶层不是对象
        }

        val uuid = root.str("order_uuid") ?: return null
        val writer = root.str("writer") ?: return null
        if (writer.isBlank()) return null
        val status = root.get("status")?.takeIf { it.isJsonPrimitive }?.let { p ->
            // `"status":"1"` 与 `"status":1` 服务端都接受，asInt 两种都能读
            runCatching { p.asInt }.getOrNull()
        } ?: return null
        if (status !in 0..9) return null

        // 服务端要求 `content` **键存在且非 null**（`parseOrderJson:1008-1015` 的 `hasContent`），
        // 但它**不要求**是对象 —— `"content":"x"` 也能通过（`extractItems` 的 `path` 拿不到 items
        // 就返回空列表）。这里照抄：只挡"没有 content"，不挡"content 不是对象"。
        val contentEl = root.get("content")
        if (contentEl == null || contentEl.isJsonNull) return null
        val content = contentEl.takeIf { it.isJsonObject }?.asJsonObject

        val parsedItems = parseItems(content)
        val items = parsedItems ?: emptyList()
        val itemsDropped = parsedItems == null

        val sumItems = items.sumOf { it.amountCents }
        val totalText = content?.str("total")
        val totalCents: Long
        val manualTotal: Boolean
        if (totalText == null) {
            // 没有总额字段（不是我们写的订单）：明细求和是唯一可用值，谈不上"手工调整"。
            totalCents = sumItems
            manualTotal = false
        } else {
            val parsed = runCatching { Money.toCents(totalText) }.getOrNull()
            totalCents = parsed ?: sumItems
            // ⚠ `parsed == null`（总额字符串不是合法十进制数）也要打标记。
            // 不打的话"total 字段坏了"会被彻底隐藏 —— 统计照跑、数字悄悄用了明细和。
            manualTotal = parsed == null || parsed != sumItems
        }

        val remarkRaw = root.str("remark")
        return Parsed(
            orderUuid = uuid,
            writer = writer,
            status = status,
            orderTs = content?.get("ts")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L,
            totalCents = totalCents,
            manualTotal = manualTotal,
            groupId = root.str("grp"),
            // 存 trimEnd 后的文本：文件里那 150 字节的尾部空格对用户没有意义，
            // 而**偏移不受影响**（remarkValueIndex 指向值首字节，长度固定 150）。
            remark = remarkRaw?.trimEnd()?.takeIf { it.isNotEmpty() },
            items = items,
            itemsDropped = itemsDropped,
            offsets = offsets,
        )
    }

    /**
     * 返回 `null` 表示**有条目但至少一条不合格**（→ 调用方按空列表建索引并打 [Parsed.itemsDropped]），
     * 返回空列表表示**本来就没有条目**。两者必须分开，理由见 [Parsed.itemsDropped]。
     *
     * ## 合格判据逐字照抄服务端 `extractItems`(:1084-1085)
     *
     * ```java
     * boolean valid = barcode.isTextual() && !barcode.asText().isBlank()
     *         && quantity.isIntegralNumber() && quantity.asInt() > 0;
     * ```
     *
     * 三个容易写歪的点：
     *  - `isTextual()` → `"barcode":6901234567890`（数字）是**不合格**的，不能靠 `asString` 兜；
     *  - `isIntegralNumber()` → `"quantity":3.0` 也**不合格**（Jackson 按 token 类型判断，
     *    DoubleNode 一律 false），所以不能写 `asInt()`（Gson 会把 `3.0` 悄悄截成 3）；
     *  - `> 0` → 0 与负数不合格。
     *
     * ## 为什么是"清空整单 items"而不是"丢掉整单"
     *
     * 服务端对 A 类订单（`strictItems=false`）就是这个行为(:1087-1088)：**一条不合格就 `List.of()`**，
     * 但订单本身照常落盘。跟着它走是有代价的（`order_item_index` 少了行 → 销量与库存偏差），
     * 但丢掉整单代价更大：那一行**确实在镜像里**，不建索引就等于
     * ①历史页看不见这张单、②它的 `statusOffset` 无人记录 → 用户在 UI 上取消不了它、
     * ③行数对不上自检也说不清是哪一行少了。而 items 的偏差有 [Parsed.itemsDropped] 兜着可见。
     *
     * B 类不会走到这里（服务端严格校验后才落盘），走到了就是本解析器的 bug。
     */
    private fun parseItems(content: JsonObject?): List<Item>? {
        val arr = content?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<Item>(arr.size())
        for (e in arr) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val barcode = o.str("barcode")?.takeIf { it.isNotBlank() } ?: return null
            val qty = o.integralInt("quantity")?.takeIf { it > 0 } ?: return null
            // `amount` 是我们自己的扩展字段（`StableOrderJson` 写、服务端 `extractItems` 根本不读），
            // 参照实现 `s1client.py` 也不写它。缺失/坏掉一律记 0 —— 于是 `Σitems ≠ total`，
            // [Parsed.manualTotal] 会自动打上标记，不会静默吞掉。
            val amount = o.str("amount")?.let { runCatching { Money.toCents(it) }.getOrNull() } ?: 0L
            // `unitPrice` 同样是自己的扩展字段。与 `amount` 的差别是**缺失时不能记 0**：
            // 0 会被当成"这一行免费"参与折扣计算，于是折扣行凭空多出一笔；
            // 记 null 则那一行不参与、折扣行不显示（见 Item.unitPriceCents 的注释）。
            val unitPrice = o.str("unitPrice")?.let { runCatching { Money.toCents(it) }.getOrNull() }
            out += Item(barcode, o.str("name").orEmpty(), qty, amount, unitPrice)
        }
        return out
    }

    /**
     * 取**整数字面量**成员；不是数字、带小数位、或超出 Int 范围都返回 null。
     *
     * 用 `scale() > 0` 而不是 `toBigIntegerExact()` 判整数：后者会把 `3.0` 当成合法的 3，
     * 而 Jackson 的 `isIntegralNumber()` 对 `3.0`（DoubleNode）返回 false。
     * Gson 的 `asBigDecimal` 保留原始字面量，`"3"`→scale 0、`"3.0"`→scale 1，正好对得上。
     */
    private fun JsonObject.integralInt(key: String): Int? {
        val p = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        val bd = runCatching { p.asBigDecimal }.getOrNull() ?: return null
        if (bd.scale() > 0) return null
        return runCatching { bd.intValueExact() }.getOrNull()
    }

    /** 取字符串成员；缺失、是 null、或不是字符串都返回 null。 */
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
